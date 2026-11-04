package com.aurebesh.teclado.llm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de inferencia local en Java puro para modelos GGUF pequenos (llama, qwen2, qwen3).
 * Todo ocurre en el telefono: el archivo se mapea en memoria, los pesos se leen fila a fila y se
 * multiplican contra activaciones int8; no hay red ni recogida de datos de ningun tipo.
 *
 * Uso: {@code LlmEngine e = LlmEngine.load(archivo, hilos); e.generate(mensajes, opciones, oyente); e.close();}
 * Las llamadas a generate se serializan (una generacion a la vez por motor).
 */
public final class LlmEngine {

    /** Limite de contexto en tiempo de ejecucion (tokens de prompt + respuesta). */
    public static final int MAX_CONTEXT = 2048;

    /** Parametros de generacion. */
    public static final class Options {
        public int maxTokens = 256;
        public float temperature = 0.3f;
        public float topP = 0.9f;
        public float repeatPenalty = 1.1f;
        /** 0 = semilla aleatoria. */
        public long seed = 0;
    }

    /** Recibe cada pieza de texto a medida que se genera; devolver false cancela. */
    public interface TokenListener {
        boolean onToken(String piece);
    }

    private final Model model;
    private final Tokenizer tokenizer;
    private final ChatTemplate template;
    private final Workers workers;
    private final boolean[] hidden;
    private final boolean[] eogMask;      // fin de generacion: sin penalizacion de repeticion
    private final int thinkStart, thinkEnd;
    private final long fileSize;
    private final String generalName;
    private boolean closed;

    private LlmEngine(Model model, Tokenizer tokenizer, ChatTemplate template, Workers workers, long fileSize, String name) {
        this.model = model;
        this.tokenizer = tokenizer;
        this.template = template;
        this.workers = workers;
        this.fileSize = fileSize;
        this.generalName = name;
        hidden = new boolean[model.nVocab];
        eogMask = new boolean[model.nVocab];
        for (int i = 0; i < model.nVocab; i++) {
            hidden[i] = tokenizer.isHidden(i);
            eogMask[i] = tokenizer.isEog(i);
        }
        thinkStart = tokenizer.idOf("<think>");
        thinkEnd = tokenizer.idOf("</think>");
    }

    /**
     * Abre un archivo GGUF: lee cabecera y metadatos, mapea los tensores y valida arquitectura
     * (llama, qwen2, qwen3) y tipos de cuantizacion. Lanza IOException con un mensaje claro si no sirve.
     *
     * @param threads hilos de calculo (1-8; el llamante trabaja como uno de ellos)
     */
    public static LlmEngine load(File gguf, int threads) throws IOException {
        if (gguf == null || !gguf.isFile()) throw new IOException("No existe el archivo del modelo");
        if (!looksLikeGguf(gguf)) throw new IOException("El archivo no es un modelo GGUF");
        int th = Math.max(1, Math.min(8, threads));
        Gguf g = new Gguf(gguf, true);
        Workers w = null;
        try {
            Tokenizer tk = new Tokenizer(g);
            w = new Workers(th);
            Model m = new Model(g, w);
            if (tk.vocabSize() > m.nVocab) throw new IOException("El vocabulario no cabe en token_embd");
            ChatTemplate ct = ChatTemplate.detect(m.arch, g.getString("tokenizer.chat_template", null), tk);
            return new LlmEngine(m, tk, ct, w, gguf.length(), g.getString("general.name", gguf.getName()));
        } catch (IOException | RuntimeException e) {
            if (w != null) w.shutdown();
            g.close();
            throw e;
        }
    }

    /** Comprobacion barata (magia "GGUF" y version 2/3) sin leer el archivo entero. */
    public static boolean looksLikeGguf(File f) {
        return Gguf.looksLikeGguf(f);
    }

    /** "llama", "qwen2" o "qwen3". */
    public String architecture() {
        return model.arch;
    }

    /** Numero de parametros del modelo (suma de elementos de todos los tensores). */
    public long parameterCount() {
        return model.parameterCount();
    }

    /** Resumen legible: nombre, arquitectura, dimensiones, tipos de cuantizacion y tamano. */
    public String info() {
        Map<String, Integer> types = new LinkedHashMap<>();
        for (Gguf.Tensor t : model.gguf.tensorList) {
            String n = Quant.name(t.type);
            Integer c = types.get(n);
            types.put(n, c == null ? 1 : c + 1);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(generalName).append('\n');
        sb.append("Arquitectura: ").append(model.arch)
                .append(", ").append(model.nLayer).append(" capas, n_embd ").append(model.nEmbd)
                .append(", ").append(model.nHead).append(" cabezas / ").append(model.nHeadKv).append(" kv")
                .append(", head_dim ").append(model.headDim).append(", n_ff ").append(model.nFf)
                .append(", vocabulario ").append(model.nVocab)
                .append(", contexto ").append(model.nCtxTrain).append('\n');
        sb.append("Parametros: ").append(formatCount(parameterCount()))
                .append(" · Archivo: ").append(fileSize / (1024 * 1024)).append(" MB")
                .append(" · Hilos: ").append(workers.size()).append('\n');
        sb.append("Tensores: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : types.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getValue()).append(" ").append(e.getKey());
        }
        sb.append('\n');
        sb.append("Tokenizador: BPE '").append(tokenizer.pre).append("', ").append(tokenizer.vocabSize()).append(" tokens")
                .append(" · Plantilla: ").append(template.name())
                .append(" · Fin: ").append(tokenizer.eog.toString());
        return sb.toString();
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000_000L) return String.format(java.util.Locale.ROOT, "%.2f B", n / 1e9);
        if (n >= 1_000_000L) return String.format(java.util.Locale.ROOT, "%.0f M", n / 1e6);
        return Long.toString(n);
    }

    /**
     * Genera la respuesta del asistente a la conversacion: aplica la plantilla de chat del modelo,
     * entrega las piezas de texto descodificadas al oyente a medida que salen y devuelve el texto
     * completo. Si el oyente devuelve false se detiene y se devuelve lo generado hasta entonces.
     */
    public synchronized String generate(List<ChatMessage> messages, Options o, TokenListener l) throws IOException {
        if (closed) throw new IOException("Motor cerrado");
        if (o == null) o = new Options();
        List<ChatTemplate.Piece> pieces = template.build(messages == null ? new ArrayList<ChatMessage>() : messages);
        int[] prompt = ChatTemplate.tokenize(pieces, tokenizer);
        return generateTokens(prompt, o, l);
    }

    /** Prompt como texto (para depuracion): lo que ve el modelo antes de tokenizar. */
    String promptText(List<ChatMessage> messages) {
        return ChatTemplate.text(template.build(messages));
    }

    /** Generacion a partir de ids ya tokenizados (compartido con las pruebas). */
    String generateTokens(int[] prompt, Options o, TokenListener l) throws IOException {
        int ctx = Math.min(MAX_CONTEXT, model.nCtxTrain);
        if (prompt.length == 0) throw new IOException("Prompt vacio");
        if (prompt.length + 8 > ctx) {
            throw new IOException("El texto es demasiado largo para el modelo (" + prompt.length + " tokens, maximo "
                    + (ctx - 8) + ")");
        }
        int maxNew = Math.max(1, Math.min(o.maxTokens, ctx - prompt.length));
        // Tope por la memoria realmente disponible: la cache KV ocupa 8 bytes por token, capa y columna
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        long perToken = 8L * model.nLayer * model.kvStride;
        int ctxMem = (int) Math.min(Integer.MAX_VALUE, free / 2 / Math.max(1L, perToken));
        if (prompt.length + 8 > ctxMem) throw new IOException("El texto es demasiado largo para la memoria disponible");
        maxNew = Math.max(1, Math.min(maxNew, ctxMem - prompt.length));
        int[] history = new int[prompt.length + maxNew];
        System.arraycopy(prompt, 0, history, 0, prompt.length);
        int histLen = prompt.length;
        Sampler sampler = new Sampler(o.seed);
        Tokenizer.Utf8Stream utf8 = new Tokenizer.Utf8Stream();
        StringBuilder text = new StringBuilder();
        boolean thinking = false;
        try {
            model.reset(prompt.length + maxNew);
            float[] logits = model.forward(prompt, 0, prompt.length, true);
            for (int i = 0; i < maxNew; i++) {
                int id = sampler.sample(logits, hidden, eogMask, history, histLen, o.temperature, o.topP, o.repeatPenalty);
                if (tokenizer.isEog(id)) break;
                history[histLen++] = id;
                if (id == thinkStart && thinkStart >= 0) {
                    thinking = true;
                } else if (id == thinkEnd && thinkEnd >= 0) {
                    thinking = false;
                } else if (!thinking) {
                    String piece = utf8.push(tokenizer, id);
                    if (!piece.isEmpty()) {
                        text.append(piece);
                        if (l != null && !l.onToken(piece)) break;
                    }
                }
                if (i + 1 < maxNew) logits = model.forward(history, histLen - 1, 1, true);
            }
            String tail = utf8.flush();
            if (!tail.isEmpty() && !thinking) {
                text.append(tail);
                if (l != null) l.onToken(tail);
            }
        } finally {
            model.releaseCache();
        }
        return text.toString();
    }

    /** Tokeniza texto de usuario (sin especiales); util para contar tokens antes de generar. */
    public int countTokens(String text) {
        return tokenizer.encode(text == null ? "" : text, false, false).length;
    }

    /** Libera el mapeo del archivo y los hilos de trabajo. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        workers.shutdown();
        model.close();
    }

    // Acceso para las pruebas del mismo paquete
    Model model() {
        return model;
    }

    Tokenizer tokenizer() {
        return tokenizer;
    }

    ChatTemplate template() {
        return template;
    }

    @Override
    public String toString() {
        return "LlmEngine[" + model.arch + ", " + Arrays.toString(new int[]{model.nLayer, model.nEmbd}) + "]";
    }
}
