package com.aurebesh.teclado.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizador BPE a nivel de byte (tokenizer.ggml.model = "gpt2"), fiel a llama-vocab.cpp:
 * particion por tokens especiales, pre-tokenizador por expresiones regulares segun
 * tokenizer.ggml.pre, codificacion byte→caracter de GPT-2, fusiones por rango con cola de
 * prioridad y destokenizacion acumulando bytes hasta completar secuencias UTF-8.
 */
final class Tokenizer {

    static final int TYPE_NORMAL = 1, TYPE_UNKNOWN = 2, TYPE_CONTROL = 3, TYPE_USER_DEFINED = 4,
            TYPE_UNUSED = 5, TYPE_BYTE = 6;

    private static final String RX_CONTRACTIONS = "(?:'[sS]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD])";
    private static final String RX_LLAMA3 = RX_CONTRACTIONS
            + "|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+";
    private static final String RX_QWEN2 = RX_CONTRACTIONS
            + "|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+";
    private static final String RX_GPT2 = "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)";
    private static final String RX_DIGIT = "\\p{N}";

    /** Tabla byte → caracter de GPT-2 (unicode_byte_to_utf8_map) y su inversa. */
    private static final char[] BYTE_TO_CHAR = new char[256];
    private static final int[] CHAR_TO_BYTE = new int[512];

    static {
        Arrays.fill(CHAR_TO_BYTE, -1);
        boolean[] direct = new boolean[256];
        for (int b = 0x21; b <= 0x7E; b++) direct[b] = true;
        for (int b = 0xA1; b <= 0xAC; b++) direct[b] = true;
        for (int b = 0xAE; b <= 0xFF; b++) direct[b] = true;
        int n = 0;
        for (int b = 0; b < 256; b++) {
            char c = direct[b] ? (char) b : (char) (256 + n++);
            BYTE_TO_CHAR[b] = c;
            CHAR_TO_BYTE[c] = b;
        }
    }

    final String pre;
    final String[] tokens;          // texto codificado (forma GPT-2) por id
    final int[] types;
    final int bos, eos, eot, eom, unk, pad;
    final boolean addBos;
    final boolean ignoreMerges;
    /** Ids de fin de generacion (eos/eot/eom y los textos conocidos). */
    final Set<Integer> eog = new HashSet<>();
    private final Map<String, Integer> tokenToId;
    /** Fusiones: clave (idIzq << 32 | idDer) → rango << 32 | idResultado. */
    private final LongLongMap merges;
    /** Tokens especiales (CONTROL, USER_DEFINED, UNKNOWN), de mas largo a mas corto, en texto crudo. */
    private final int[] specialIds;
    private final String[] specialTexts;
    private final Pattern[] prePatterns;
    private final int[] byteCharIds = new int[256];

    Tokenizer(Gguf g) throws IOException {
        String model = g.getString("tokenizer.ggml.model", "");
        if (!"gpt2".equals(model)) {
            throw new IOException("Tokenizador no admitido: " + model + " (solo BPE 'gpt2')");
        }
        tokens = g.getStrings("tokenizer.ggml.tokens");
        if (tokens == null) throw new IOException("Falta tokenizer.ggml.tokens");
        int[] tt = g.getInts("tokenizer.ggml.token_type");
        types = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) types[i] = tt != null && i < tt.length ? tt[i] : TYPE_NORMAL;
        pre = g.getString("tokenizer.ggml.pre", "default");
        tokenToId = new HashMap<>(tokens.length * 2);
        for (int i = 0; i < tokens.length; i++) {
            if (!tokenToId.containsKey(tokens[i])) tokenToId.put(tokens[i], i);
        }
        for (int b = 0; b < 256; b++) {
            Integer id = tokenToId.get(String.valueOf(BYTE_TO_CHAR[b]));
            byteCharIds[b] = id == null ? -1 : id;
        }
        String[] mergeList = g.getStrings("tokenizer.ggml.merges");
        merges = new LongLongMap(mergeList == null ? 16 : mergeList.length * 2);
        if (mergeList != null) {
            for (int rank = 0; rank < mergeList.length; rank++) {
                String m = mergeList[rank];
                int sp = m.indexOf(' ', 1);
                if (sp < 0) continue;
                Integer l = tokenToId.get(m.substring(0, sp));
                Integer r = tokenToId.get(m.substring(sp + 1));
                Integer res = tokenToId.get(m.substring(0, sp) + m.substring(sp + 1));
                if (l == null || r == null || res == null) continue;
                long key = ((long) l << 32) | (r & 0xFFFFFFFFL);
                if (!merges.contains(key)) merges.put(key, ((long) rank << 32) | (res & 0xFFFFFFFFL));
            }
        }
        bos = g.getInt("tokenizer.ggml.bos_token_id", -1);
        eos = g.getInt("tokenizer.ggml.eos_token_id", -1);
        eot = g.getInt("tokenizer.ggml.eot_token_id", -1);
        eom = g.getInt("tokenizer.ggml.eom_token_id", -1);
        unk = g.getInt("tokenizer.ggml.unknown_token_id", -1);
        pad = g.getInt("tokenizer.ggml.padding_token_id", -1);
        boolean llama3 = isLlama3Pre(pre);
        ignoreMerges = llama3;
        addBos = g.getBool("tokenizer.ggml.add_bos_token", llama3);
        if (eos >= 0) eog.add(eos);
        if (eot >= 0) eog.add(eot);
        if (eom >= 0) eog.add(eom);
        String[] eogTexts = {"<|im_end|>", "<|endoftext|>", "<|eot_id|>", "<|end|>", "<|end_of_text|>",
                "<end_of_turn>", "</s>", "<|eom_id|>", "<EOT>"};
        for (String s : eogTexts) {
            Integer id = tokenToId.get(s);
            if (id != null && (types[id] == TYPE_CONTROL || types[id] == TYPE_USER_DEFINED)) eog.add(id);
        }
        // Tokens especiales, de mas largo a mas corto (la particion es voraz por longitud).
        List<Integer> sp = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            int t = types[i];
            if ((t == TYPE_CONTROL || t == TYPE_USER_DEFINED || t == TYPE_UNKNOWN) && !tokens[i].isEmpty()) sp.add(i);
        }
        java.util.Collections.sort(sp, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int d = tokens[b].length() - tokens[a].length();
                return d != 0 ? d : a - b;
            }
        });
        specialIds = new int[sp.size()];
        specialTexts = new String[sp.size()];
        for (int i = 0; i < sp.size(); i++) {
            specialIds[i] = sp.get(i);
            specialTexts[i] = tokens[sp.get(i)];     // los especiales se guardan sin codificar
        }
        prePatterns = patternsFor(pre);
    }

    static boolean isLlama3Pre(String pre) {
        return "llama-bpe".equals(pre) || "llama3".equals(pre) || "dbrx".equals(pre) || "smaug-bpe".equals(pre);
    }

    private static Pattern[] patternsFor(String pre) {
        int flags = Pattern.UNICODE_CHARACTER_CLASS;
        if (isLlama3Pre(pre)) return new Pattern[]{Pattern.compile(RX_LLAMA3, flags)};
        switch (pre) {
            case "qwen2": case "stablelm2": case "deepseek-r1-qwen":
                return new Pattern[]{Pattern.compile(RX_QWEN2, flags)};
            case "smollm": case "refact": case "command-r": case "codeshell": case "exaone":
                return new Pattern[]{Pattern.compile(RX_DIGIT, flags), Pattern.compile(RX_GPT2, flags)};
            default:   // gpt-2, phi-2, olmo, mpt y cualquier id desconocido
                return new Pattern[]{Pattern.compile(RX_GPT2, flags)};
        }
    }

    int vocabSize() {
        return tokens.length;
    }

    int idOf(String text) {
        Integer id = tokenToId.get(text);
        return id == null ? -1 : id;
    }

    boolean isEog(int id) {
        return eog.contains(id);
    }

    /** True si el id no debe muestrearse nunca: relleno/no usados y controles que no son fin de generacion. */
    boolean isHidden(int id) {
        if (id < 0 || id >= tokens.length) return true;
        int t = types[id];
        if (t == TYPE_UNUSED || t == TYPE_UNKNOWN) return true;
        if (t == TYPE_CONTROL) return !eog.contains(id);
        return tokens[id].startsWith("[PAD") && tokens[id].endsWith("]");
    }

    // ------------------------------------------------------------------ codificacion

    /**
     * Tokeniza texto. Con parseSpecial los tokens especiales presentes en el texto se reconocen
     * (solo para plantillas propias); con el texto del usuario debe ir a false.
     */
    int[] encode(String text, boolean addSpecial, boolean parseSpecial) {
        IntList out = new IntList(text.length() / 3 + 8);
        if (addSpecial && addBos && bos >= 0) out.add(bos);
        if (parseSpecial && specialIds.length > 0) {
            partition(text, out);
        } else {
            encodeFragment(text, out);
        }
        return out.toArray();
    }

    /** Divide el texto por tokens especiales (los mas largos primero) y tokeniza el resto. */
    private void partition(String text, IntList out) {
        List<Object> frags = new ArrayList<>();
        frags.add(text);
        for (int s = 0; s < specialIds.length; s++) {
            String st = specialTexts[s];
            List<Object> nf = new ArrayList<>(frags.size() + 4);
            for (Object o : frags) {
                if (!(o instanceof String)) {
                    nf.add(o);
                    continue;
                }
                String f = (String) o;
                int from = 0;
                while (true) {
                    int k = f.indexOf(st, from);
                    if (k < 0) {
                        if (from < f.length()) nf.add(f.substring(from));
                        break;
                    }
                    if (k > from) nf.add(f.substring(from, k));
                    nf.add(specialIds[s]);
                    from = k + st.length();
                }
            }
            frags = nf;
        }
        for (Object o : frags) {
            if (o instanceof Integer) out.add((Integer) o);
            else encodeFragment((String) o, out);
        }
    }

    /** Pre-tokeniza con las expresiones regulares (en secuencia) y aplica BPE a cada pieza. */
    private void encodeFragment(String text, IntList out) {
        if (text.isEmpty()) return;
        List<String> pieces = new ArrayList<>();
        pieces.add(text);
        for (Pattern p : prePatterns) {
            List<String> next = new ArrayList<>(pieces.size() * 2);
            for (String piece : pieces) {
                Matcher m = p.matcher(piece);
                int last = 0;
                while (m.find()) {
                    if (m.start() > last) next.add(piece.substring(last, m.start()));
                    if (m.end() > m.start()) next.add(piece.substring(m.start(), m.end()));
                    last = m.end();
                }
                if (last < piece.length()) next.add(piece.substring(last));
            }
            pieces = next;
        }
        for (String piece : pieces) bpe(piece, out);
    }

    /** BPE de una pieza: cada byte UTF-8 es un simbolo inicial; se fusionan por rango ascendente. */
    private void bpe(String piece, IntList out) {
        byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
        int n = bytes.length;
        if (n == 0) return;
        if (ignoreMerges) {
            char[] enc = new char[n];
            for (int i = 0; i < n; i++) enc[i] = BYTE_TO_CHAR[bytes[i] & 0xFF];
            Integer whole = tokenToId.get(new String(enc));
            if (whole != null) {
                out.add(whole);
                return;
            }
        }
        final int[] id = new int[n];
        final int[] next = new int[n];
        final int[] prev = new int[n];
        for (int i = 0; i < n; i++) {
            id[i] = byteCharIds[bytes[i] & 0xFF];
            next[i] = i + 1 < n ? i + 1 : -1;
            prev[i] = i - 1;
        }
        // Cola de prioridad: menor rango primero, despues menor indice izquierdo.
        PriorityQueue<long[]> pq = new PriorityQueue<>(Math.max(1, n), new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                if (a[0] != b[0]) return a[0] < b[0] ? -1 : 1;
                return a[1] < b[1] ? -1 : (a[1] > b[1] ? 1 : 0);
            }
        });
        for (int i = 0; i + 1 < n; i++) push(pq, id, i, i + 1);
        boolean[] alive = new boolean[n];
        Arrays.fill(alive, true);
        while (!pq.isEmpty()) {
            long[] e = pq.poll();
            int l = (int) e[1], r = (int) e[2];
            if (!alive[l] || !alive[r] || next[l] != r || id[l] != (int) e[3] || id[r] != (int) e[4]) continue;
            id[l] = (int) e[5];
            alive[r] = false;
            next[l] = next[r];
            if (next[r] >= 0) prev[next[r]] = l;
            if (prev[l] >= 0) push(pq, id, prev[l], l);
            if (next[l] >= 0) push(pq, id, l, next[l]);
        }
        for (int i = 0; i != -1; i = next[i]) {
            if (id[i] >= 0) out.add(id[i]);
        }
    }

    private void push(PriorityQueue<long[]> pq, int[] id, int l, int r) {
        if (id[l] < 0 || id[r] < 0) return;
        long v = merges.get(((long) id[l] << 32) | (id[r] & 0xFFFFFFFFL));
        if (v == LongLongMap.MISSING) return;
        pq.add(new long[]{v >>> 32, l, r, id[l], id[r], v & 0xFFFFFFFFL});
    }

    // ------------------------------------------------------------------ descodificacion

    /** Anade los bytes de la pieza del token id al flujo (nada para controles/desconocidos). */
    void pieceBytes(int id, ByteArrayOutputStream out) {
        if (id < 0 || id >= tokens.length) return;
        int t = types[id];
        if (t == TYPE_CONTROL || t == TYPE_UNKNOWN || t == TYPE_UNUSED) return;
        String s = tokens[id];
        if (t == TYPE_USER_DEFINED) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int b = c < 512 ? CHAR_TO_BYTE[c] : -1;
            if (b >= 0) {
                out.write(b);
            } else {
                byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(raw, 0, raw.length);
            }
        }
    }

    /** Descodifica una lista completa de ids a texto (sin tokens especiales). */
    String decode(int[] ids, int from, int to) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
        for (int i = from; i < to; i++) pieceBytes(ids[i], bo);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Texto crudo del token (para plantillas y pruebas). */
    String tokenText(int id) {
        return id >= 0 && id < tokens.length ? tokens[id] : "";
    }

    int tokenType(int id) {
        return id >= 0 && id < tokens.length ? types[id] : TYPE_UNUSED;
    }

    /**
     * Acumulador UTF-8 para la salida en streaming: solo entrega texto cuando los bytes forman
     * secuencias completas (un emoji o una n con tilde pueden repartirse en 2-3 tokens).
     */
    static final class Utf8Stream {
        private byte[] buf = new byte[64];
        private int len;

        /** Anade los bytes del token y devuelve el texto completo disponible ("" si aun no hay). */
        String push(Tokenizer tk, int id) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(16);
            tk.pieceBytes(id, bo);
            byte[] b = bo.toByteArray();
            if (len + b.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + b.length));
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
            int complete = completePrefix(buf, len);
            if (complete == 0) return "";
            String s = new String(buf, 0, complete, StandardCharsets.UTF_8);
            System.arraycopy(buf, complete, buf, 0, len - complete);
            len -= complete;
            return s;
        }

        /** Vacia lo pendiente (bytes incompletos se descodifican con sustitucion). */
        String flush() {
            if (len == 0) return "";
            String s = new String(buf, 0, len, StandardCharsets.UTF_8);
            len = 0;
            return s;
        }

        /** Longitud del prefijo que no termina en una secuencia multibyte incompleta. */
        static int completePrefix(byte[] b, int len) {
            int i = len - 1;
            int back = 0;
            while (i >= 0 && back < 3 && (b[i] & 0xC0) == 0x80) {
                i--;
                back++;
            }
            if (i < 0) return len;          // solo continuaciones sueltas: se entregan tal cual
            int lead = b[i] & 0xFF;
            int need;
            if (lead < 0x80) need = 1;
            else if ((lead & 0xE0) == 0xC0) need = 2;
            else if ((lead & 0xF0) == 0xE0) need = 3;
            else if ((lead & 0xF8) == 0xF0) need = 4;
            else return len;                // byte invalido: no esperar
            return (len - i) >= need ? len : i;
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** Lista de enteros sin boxing. */
    static final class IntList {
        int[] a;
        int n;

        IntList(int cap) {
            a = new int[Math.max(4, cap)];
        }

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    /** Mapa long → long de direccionamiento abierto (sin boxing) para las fusiones. */
    static final class LongLongMap {
        static final long MISSING = Long.MIN_VALUE;
        private long[] keys;
        private long[] vals;
        private boolean[] used;
        private int size;
        private int mask;

        LongLongMap(int expected) {
            int cap = 16;
            while (cap < expected * 2) cap <<= 1;
            keys = new long[cap];
            vals = new long[cap];
            used = new boolean[cap];
            mask = cap - 1;
        }

        private static int hash(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            return (int) k;
        }

        boolean contains(long k) {
            return get(k) != MISSING;
        }

        long get(long k) {
            int i = hash(k) & mask;
            while (used[i]) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & mask;
            }
            return MISSING;
        }

        void put(long k, long v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = hash(k) & mask;
            while (used[i]) {
                if (keys[i] == k) {
                    vals[i] = v;
                    return;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = k;
            vals[i] = v;
            size++;
        }

        private void grow() {
            long[] ok = keys, ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            vals = new long[ok.length * 2];
            used = new boolean[ok.length * 2];
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], ov[i]);
        }

        int size() {
            return size;
        }
    }
}
