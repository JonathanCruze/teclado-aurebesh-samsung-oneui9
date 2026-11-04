package com.aurebesh.teclado.llm;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Transformer decodificador (llama, qwen2, qwen3) sobre un archivo GGUF: hiperparametros,
 * tensores, cache KV y paso hacia delante. Las matrices se leen fila a fila del mapeo y se
 * multiplican contra activaciones cuantizadas a int8 (o, en modo exacto, descuantizando la fila).
 *
 * Paso por capa: h = rmsnorm(x)·attn_norm; q,k,v = W·h (+sesgo); [qwen3: rmsnorm por cabeza de q y k];
 * RoPE (NORM en llama, NEOX en qwen2/qwen3); atencion causal con grupos (GQA); x += Wo·att;
 * h = rmsnorm(x)·ffn_norm; x += Wdown·(silu(Wgate·h) ⊙ Wup·h). Final: logits = Wout·(rmsnorm(x)·output_norm).
 */
final class Model implements Closeable {

    /** Maximo de tokens por trozo en el prellenado (limita los buferes de activacion). */
    static final int CHUNK = 64;

    static final class Layer {
        float[] attnNorm, ffnNorm, qNorm, kNorm;
        Gguf.Tensor wq, wk, wv, wo, wGate, wUp, wDown;
        float[] bq, bk, bv, bo;
    }

    /** Memoria de trabajo propia de cada hilo. */
    static final class Scratch {
        byte[] row;
        float[] deq;
        float[] scores;
        ByteBuffer[] dups;
    }

    final Gguf gguf;
    final String arch;
    final int nLayer, nEmbd, nFf, nHead, nHeadKv, headDim, nRot, nVocab, nCtxTrain;
    final float eps, ropeBase;
    final boolean neox;
    final Gguf.Tensor tokEmbd, output;
    final float[] outputNorm, outputBias;
    final Layer[] layers;
    final Workers workers;
    final int kvStride;            // nHeadKv * headDim
    final int maxRowBytes, maxCols;
    /** Camino exacto (descuantizar cada fila y multiplicar en float); solo para verificacion. */
    boolean exact;
    /** Mascara de etapas que usan activaciones int8 (bits MM_*); solo para diagnostico en pruebas. */
    int int8Mask = -1;
    static final int MM_QKV = 1, MM_OUT = 2, MM_GATE_UP = 4, MM_DOWN = 8, MM_HEAD = 16;

    // Cache KV y tablas RoPE para el contexto en uso
    float[][] kCache, vCache;
    int kvCap;
    int nPast;
    private float[] ropeCos, ropeSin;

    // Buferes de activacion para un trozo de hasta bufTokens tokens
    private int bufTokens;
    private float[] x, h, q, k, v, att, gate, up;
    private Quant.QVec xq, attq, ffq;
    private final float[] logits;
    private final Scratch[] scratch;

    Model(Gguf g, Workers workers) throws IOException {
        this.gguf = g;
        this.workers = workers;
        arch = g.getString("general.architecture", "");
        if (!arch.equals("llama") && !arch.equals("qwen2") && !arch.equals("qwen3")) {
            throw new IOException("Arquitectura no admitida: '" + arch + "' (se admiten llama, qwen2 y qwen3)");
        }
        nLayer = req(g, arch + ".block_count");
        nCtxTrain = g.getInt(arch + ".context_length", 2048);
        nEmbd = req(g, arch + ".embedding_length");
        nFf = req(g, arch + ".feed_forward_length");
        nHead = req(g, arch + ".attention.head_count");
        nHeadKv = g.getInt(arch + ".attention.head_count_kv", nHead);
        eps = g.getFloat(arch + ".attention.layer_norm_rms_epsilon", 1e-5f);
        headDim = g.getInt(arch + ".attention.key_length", nEmbd / nHead);
        int vlen = g.getInt(arch + ".attention.value_length", headDim);
        if (vlen != headDim) throw new IOException("key_length distinto de value_length no admitido");
        ropeBase = g.getFloat(arch + ".rope.freq_base", 10000f);
        nRot = g.getInt(arch + ".rope.dimension_count", headDim);
        if (nRot > headDim || (nRot & 1) != 0) throw new IOException("rope.dimension_count invalido: " + nRot);
        String scaling = g.getString(arch + ".rope.scaling.type", "none");
        float factor = g.getFloat(arch + ".rope.scaling.factor", 1f);
        if (!(scaling.equals("none") || (scaling.equals("linear") && factor == 1f))) {
            throw new IOException("Escalado RoPE '" + scaling + "' no admitido");
        }
        if (nHead <= 0 || nHeadKv <= 0 || nHead % nHeadKv != 0) throw new IOException("head_count/head_count_kv invalidos");
        neox = !arch.equals("llama");
        kvStride = nHeadKv * headDim;

        tokEmbd = g.require("token_embd.weight");
        String[] toks = g.getStrings("tokenizer.ggml.tokens");
        nVocab = tokEmbd.rows;
        if (toks != null && toks.length > nVocab) throw new IOException("Vocabulario mayor que token_embd");
        check(tokEmbd, nEmbd, nVocab);
        outputNorm = vector(g.require("output_norm.weight"), nEmbd);
        Gguf.Tensor out = g.tensor("output.weight");
        output = out != null ? out : tokEmbd;          // embeddings compartidas
        check(output, nEmbd, nVocab);
        outputBias = g.tensor("output.bias") != null ? vector(g.tensor("output.bias"), nVocab) : null;

        layers = new Layer[nLayer];
        int qRows = nHead * headDim, kvRows = nHeadKv * headDim;
        int mrb = Math.max(tokEmbd.rowBytes, output.rowBytes), mc = Math.max(tokEmbd.cols, output.cols);
        for (int i = 0; i < nLayer; i++) {
            Layer l = new Layer();
            String p = "blk." + i + ".";
            l.attnNorm = vector(g.require(p + "attn_norm.weight"), nEmbd);
            l.ffnNorm = vector(g.require(p + "ffn_norm.weight"), nEmbd);
            Gguf.Tensor fused = g.tensor(p + "attn_qkv.weight");
            if (fused != null) {
                check(fused, nEmbd, qRows + 2 * kvRows);
                l.wq = fused.view(0, qRows);
                l.wk = fused.view(qRows, kvRows);
                l.wv = fused.view(qRows + kvRows, kvRows);
                Gguf.Tensor fb = g.tensor(p + "attn_qkv.bias");
                if (fb != null) {
                    float[] all = vector(fb, qRows + 2 * kvRows);
                    l.bq = slice(all, 0, qRows);
                    l.bk = slice(all, qRows, kvRows);
                    l.bv = slice(all, qRows + kvRows, kvRows);
                }
            } else {
                l.wq = g.require(p + "attn_q.weight");
                l.wk = g.require(p + "attn_k.weight");
                l.wv = g.require(p + "attn_v.weight");
                check(l.wq, nEmbd, qRows);
                check(l.wk, nEmbd, kvRows);
                check(l.wv, nEmbd, kvRows);
                l.bq = optVector(g, p + "attn_q.bias", qRows);
                l.bk = optVector(g, p + "attn_k.bias", kvRows);
                l.bv = optVector(g, p + "attn_v.bias", kvRows);
            }
            l.wo = g.require(p + "attn_output.weight");
            check(l.wo, qRows, nEmbd);
            l.bo = optVector(g, p + "attn_output.bias", nEmbd);
            l.qNorm = optVector(g, p + "attn_q_norm.weight", headDim);
            l.kNorm = optVector(g, p + "attn_k_norm.weight", headDim);
            l.wGate = g.require(p + "ffn_gate.weight");
            l.wUp = g.require(p + "ffn_up.weight");
            l.wDown = g.require(p + "ffn_down.weight");
            check(l.wGate, nEmbd, nFf);
            check(l.wUp, nEmbd, nFf);
            check(l.wDown, nFf, nEmbd);
            Gguf.Tensor[] mats = {l.wq, l.wk, l.wv, l.wo, l.wGate, l.wUp, l.wDown};
            for (Gguf.Tensor t : mats) {
                mrb = Math.max(mrb, t.rowBytes);
                mc = Math.max(mc, t.cols);
            }
            layers[i] = l;
        }
        maxRowBytes = mrb;
        maxCols = mc;
        logits = new float[nVocab];
        scratch = new Scratch[workers.size()];
        for (int i = 0; i < scratch.length; i++) {
            Scratch s = new Scratch();
            s.row = new byte[maxRowBytes];
            s.deq = new float[maxCols];
            s.dups = new ByteBuffer[g.segmentCount()];
            scratch[i] = s;
        }
    }

    private static int req(Gguf g, String key) throws IOException {
        int v = g.getInt(key, -1);
        if (v <= 0) throw new IOException("Falta el metadato " + key);
        return v;
    }

    private static void check(Gguf.Tensor t, int cols, int rows) throws IOException {
        if (t.cols != cols || t.rows != rows) {
            throw new IOException("Dimensiones inesperadas en " + t.name + ": " + t.cols + "x" + t.rows
                    + ", se esperaba " + cols + "x" + rows);
        }
    }

    private static float[] slice(float[] a, int off, int n) {
        float[] r = new float[n];
        System.arraycopy(a, off, r, 0, n);
        return r;
    }

    private float[] optVector(Gguf g, String name, int n) throws IOException {
        Gguf.Tensor t = g.tensor(name);
        return t == null ? null : vector(t, n);
    }

    /** Lee un tensor pequeno completo (normas, sesgos) a float[]. */
    private float[] vector(Gguf.Tensor t, int n) throws IOException {
        if (t.nelements != n) throw new IOException("Dimensiones inesperadas en " + t.name);
        float[] out = new float[n];
        byte[] row = new byte[t.rowBytes];
        ByteBuffer dup = gguf.duplicate(t.segment);
        for (int r = 0, o = 0; r < t.rows; r++, o += t.cols) {
            t.row(dup, r, row);
            Quant.dequantRow(t.type, row, t.cols, out, o);
        }
        return out;
    }

    private ByteBuffer dup(Scratch s, Gguf.Tensor t) {
        ByteBuffer b = s.dups[t.segment];
        if (b == null) {
            b = gguf.duplicate(t.segment);
            s.dups[t.segment] = b;
        }
        return b;
    }

    // ------------------------------------------------------------------ contexto

    /** Prepara la cache KV y las tablas RoPE para ctx tokens y vacia el historial. */
    void reset(int ctx) {
        if (ctx < 1) ctx = 1;
        if (kCache == null || kvCap < ctx || kvCap > ctx * 2) {
            kCache = new float[nLayer][];
            vCache = new float[nLayer][];
            for (int l = 0; l < nLayer; l++) {
                kCache[l] = new float[ctx * kvStride];
                vCache[l] = new float[ctx * kvStride];
            }
            kvCap = ctx;
            int half = nRot / 2;
            ropeCos = new float[ctx * half];
            ropeSin = new float[ctx * half];
            for (int i = 0; i < half; i++) {
                double theta = Math.pow(ropeBase, -2.0 * i / nRot);
                for (int p = 0; p < ctx; p++) {
                    double a = p * theta;
                    ropeCos[p * half + i] = (float) Math.cos(a);
                    ropeSin[p * half + i] = (float) Math.sin(a);
                }
            }
            for (Scratch s : scratch) s.scores = new float[ctx];
        }
        nPast = 0;
    }

    /** Libera la cache KV (entre peticiones, para no retener memoria). */
    void releaseCache() {
        kCache = null;
        vCache = null;
        kvCap = 0;
        ropeCos = null;
        ropeSin = null;
        nPast = 0;
        for (Scratch s : scratch) s.scores = null;
        freeBuffers();
    }

    private void ensureBuffers(int count) {
        if (count <= bufTokens) return;
        bufTokens = count;
        x = new float[count * nEmbd];
        h = new float[count * nEmbd];
        q = new float[count * nHead * headDim];
        k = new float[count * kvStride];
        v = new float[count * kvStride];
        att = new float[count * nHead * headDim];
        gate = new float[count * nFf];
        up = new float[count * nFf];
        xq = new Quant.QVec(nEmbd, count);
        attq = new Quant.QVec(nHead * headDim, count);
        ffq = new Quant.QVec(nFf, count);
    }

    private void freeBuffers() {
        bufTokens = 0;
        x = h = q = k = v = att = gate = up = null;
        xq = attq = ffq = null;
    }

    // ------------------------------------------------------------------ paso hacia delante

    /**
     * Procesa tokens[off..off+len) en las posiciones nPast.. y devuelve los logits del ultimo
     * (el array se reutiliza entre llamadas) o null si wantLogits es false.
     */
    float[] forward(int[] tokens, int off, int len, boolean wantLogits) throws IOException {
        if (kCache == null) throw new IOException("Contexto no inicializado");
        if (nPast + len > kvCap) throw new IOException("Contexto lleno (" + kvCap + " tokens)");
        float[] result = null;
        int done = 0;
        while (done < len) {
            int c = Math.min(CHUNK, len - done);
            boolean last = done + c == len;
            result = forwardChunk(tokens, off + done, c, wantLogits && last);
            done += c;
        }
        return result;
    }

    private float[] forwardChunk(int[] tokens, int off, int count, boolean wantLogits) throws IOException {
        ensureBuffers(count);
        final int p0 = nPast;
        final int qDim = nHead * headDim;
        // Embeddings
        Scratch s0 = scratch[0];
        ByteBuffer d0 = dup(s0, tokEmbd);
        for (int t = 0; t < count; t++) {
            int id = tokens[off + t];
            if (id < 0 || id >= nVocab) throw new IOException("Token fuera de rango: " + id);
            tokEmbd.row(d0, id, s0.row);
            Quant.dequantRow(tokEmbd.type, s0.row, nEmbd, x, t * nEmbd);
        }
        for (int li = 0; li < nLayer; li++) {
            final Layer l = layers[li];
            // Atencion
            for (int t = 0; t < count; t++) {
                rmsnorm(x, t * nEmbd, l.attnNorm, h, t * nEmbd, nEmbd);
                xq.set(h, t * nEmbd, t);
            }
            matmul(new Gguf.Tensor[]{l.wq, l.wk, l.wv}, new float[][]{l.bq, l.bk, l.bv}, xq, count,
                    new float[][]{q, k, v}, false, MM_QKV);
            for (int t = 0; t < count; t++) {
                int pos = p0 + t;
                for (int hh = 0; hh < nHead; hh++) {
                    int o = t * qDim + hh * headDim;
                    if (l.qNorm != null) rmsnorm(q, o, l.qNorm, q, o, headDim);
                    rope(q, o, pos);
                }
                for (int hh = 0; hh < nHeadKv; hh++) {
                    int o = t * kvStride + hh * headDim;
                    if (l.kNorm != null) rmsnorm(k, o, l.kNorm, k, o, headDim);
                    rope(k, o, pos);
                }
                System.arraycopy(k, t * kvStride, kCache[li], pos * kvStride, kvStride);
                System.arraycopy(v, t * kvStride, vCache[li], pos * kvStride, kvStride);
            }
            attention(li, count, p0);
            for (int t = 0; t < count; t++) attq.set(att, t * qDim, t);
            matmul(new Gguf.Tensor[]{l.wo}, new float[][]{l.bo}, attq, count, new float[][]{x}, true, MM_OUT);
            // Red de avance (SwiGLU)
            for (int t = 0; t < count; t++) {
                rmsnorm(x, t * nEmbd, l.ffnNorm, h, t * nEmbd, nEmbd);
                xq.set(h, t * nEmbd, t);
            }
            matmul(new Gguf.Tensor[]{l.wGate, l.wUp}, new float[][]{null, null}, xq, count,
                    new float[][]{gate, up}, false, MM_GATE_UP);
            for (int t = 0; t < count; t++) {
                int o = t * nFf;
                for (int j = 0; j < nFf; j++) {
                    float g = gate[o + j];
                    gate[o + j] = g / (1f + (float) Math.exp(-g)) * up[o + j];
                }
                ffq.set(gate, o, t);
            }
            matmul(new Gguf.Tensor[]{l.wDown}, new float[][]{null}, ffq, count, new float[][]{x}, true, MM_DOWN);
        }
        nPast += count;
        if (!wantLogits) return null;
        int t = count - 1;
        rmsnorm(x, t * nEmbd, outputNorm, h, 0, nEmbd);
        xq.set(h, 0, 0);
        matmul(new Gguf.Tensor[]{output}, new float[][]{outputBias}, xq, 1, new float[][]{logits}, false, MM_HEAD);
        return logits;
    }

    /** out = x / sqrt(media(x²) + eps) · w (out puede ser el mismo array que x). */
    private void rmsnorm(float[] in, int io, float[] w, float[] out, int oo, int n) {
        float ss = 0f;
        for (int j = 0; j < n; j++) ss += in[io + j] * in[io + j];
        float scale = (float) (1.0 / Math.sqrt(ss / n + eps));
        for (int j = 0; j < n; j++) out[oo + j] = in[io + j] * scale * w[j];
    }

    /** RoPE sobre una cabeza en v[o..o+headDim) para la posicion pos. */
    private void rope(float[] vec, int o, int pos) {
        int half = nRot / 2;
        int to = pos * half;
        if (neox) {
            for (int i = 0; i < half; i++) {
                float c = ropeCos[to + i], s = ropeSin[to + i];
                float x0 = vec[o + i], x1 = vec[o + i + half];
                vec[o + i] = x0 * c - x1 * s;
                vec[o + i + half] = x0 * s + x1 * c;
            }
        } else {
            for (int i = 0; i < half; i++) {
                float c = ropeCos[to + i], s = ropeSin[to + i];
                int a = o + 2 * i;
                float x0 = vec[a], x1 = vec[a + 1];
                vec[a] = x0 * c - x1 * s;
                vec[a + 1] = x0 * s + x1 * c;
            }
        }
    }

    /** Atencion causal con grupos para los count tokens del trozo (posiciones p0..), en paralelo por (cabeza, token). */
    private void attention(final int layer, final int count, final int p0) {
        final float[] kc = kCache[layer], vc = vCache[layer];
        final int qDim = nHead * headDim;
        final int group = nHead / nHeadKv;
        final float scale = (float) (1.0 / Math.sqrt(headDim));
        workers.parallel(nHead * count, 1, new Workers.Task() {
            @Override
            public void run(int worker, int from, int to) {
                float[] scores = scratch[worker].scores;
                for (int item = from; item < to; item++) {
                    int hh = item / count, t = item % count;
                    int pos = p0 + t;
                    int kvh = hh / group;
                    int qo = t * qDim + hh * headDim;
                    float max = Float.NEGATIVE_INFINITY;
                    for (int s = 0; s <= pos; s++) {
                        float sc = Quant.dotFloat(q, qo, kc, s * kvStride + kvh * headDim, headDim) * scale;
                        scores[s] = sc;
                        if (sc > max) max = sc;
                    }
                    float sum = 0f;
                    for (int s = 0; s <= pos; s++) {
                        float e = (float) Math.exp(scores[s] - max);
                        scores[s] = e;
                        sum += e;
                    }
                    float inv = 1f / sum;
                    int oo = t * qDim + hh * headDim;
                    for (int d = 0; d < headDim; d++) att[oo + d] = 0f;
                    for (int s = 0; s <= pos; s++) {
                        float p = scores[s] * inv;
                        int vo = s * kvStride + kvh * headDim;
                        for (int d = 0; d < headDim; d++) att[oo + d] += p * vc[vo + d];
                    }
                }
            }
        });
    }

    /**
     * ys[i][t*rows_i + r] (=|+=) W_i[r]·x[t] (+ bias_i[r]) para varias matrices con la misma entrada,
     * en una sola region paralela repartida por filas.
     */
    private void matmul(final Gguf.Tensor[] ws, final float[][] biases, final Quant.QVec x, final int count,
                        final float[][] ys, final boolean accumulate, int stage) {
        int total = 0;
        final int[] starts = new int[ws.length + 1];
        for (int i = 0; i < ws.length; i++) {
            starts[i] = total;
            total += ws[i].rows;
        }
        starts[ws.length] = total;
        int chunk = Math.max(4, Math.min(256, total / (workers.size() * 8)));
        final int n = x.n;
        final boolean exactPath = exact || (int8Mask & stage) == 0 || n % Quant.QB != 0;
        workers.parallel(total, chunk, new Workers.Task() {
            @Override
            public void run(int worker, int from, int to) {
                Scratch s = scratch[worker];
                byte[] row = s.row;
                int wi = 0;
                while (from >= starts[wi + 1]) wi++;
                Gguf.Tensor w = ws[wi];
                ByteBuffer dup = dup(s, w);
                float[] y = ys[wi];
                float[] bias = biases[wi];
                int rows = w.rows;
                for (int r = from; r < to; r++) {
                    while (r >= starts[wi + 1]) {
                        wi++;
                        w = ws[wi];
                        dup = dup(s, w);
                        y = ys[wi];
                        bias = biases[wi];
                        rows = w.rows;
                    }
                    int lr = r - starts[wi];
                    w.row(dup, lr, row);
                    float b = bias != null ? bias[lr] : 0f;
                    if (exactPath && !Quant.isFloat(w.type)) {
                        // Camino exacto: descuantizar la fila y multiplicar en float
                        Quant.dequantRow(w.type, row, n, s.deq, 0);
                        for (int t = 0; t < count; t++) {
                            float val = Quant.dotFloat(s.deq, 0, x.f, t * n, n) + b;
                            int yi = t * rows + lr;
                            y[yi] = accumulate ? y[yi] + val : val;
                        }
                    } else {
                        // Camino rapido: activaciones int8 (las filas F32/F16/BF16 usan los floats)
                        for (int t = 0; t < count; t++) {
                            float val = Quant.dot(w.type, row, n, x, t) + b;
                            int yi = t * rows + lr;
                            y[yi] = accumulate ? y[yi] + val : val;
                        }
                    }
                }
            }
        });
    }

    /** Numero total de parametros (elementos de todos los tensores del archivo). */
    long parameterCount() {
        long n = 0;
        for (Gguf.Tensor t : gguf.tensorList) n += t.nelements;
        return n;
    }

    @Override
    public void close() {
        releaseCache();
        gguf.close();
    }
}
