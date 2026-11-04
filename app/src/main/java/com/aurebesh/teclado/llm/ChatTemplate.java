package com.aurebesh.teclado.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plantillas de chat construidas a mano (no se interpreta Jinja). Se detecta la familia por el
 * contenido de tokenizer.chat_template y la arquitectura:
 *   - ChatML (Qwen2.5, SmolLM2): &lt;|im_start|&gt;rol\ncontenido&lt;|im_end|&gt;\n ... &lt;|im_start|&gt;assistant\n
 *   - ChatML de Qwen3 (plantilla con enable_thinking): igual y se anade el bloque de pensamiento
 *     vacio "&lt;think&gt;\n\n&lt;/think&gt;\n\n", que es lo que emite la plantilla oficial con enable_thinking=false
 *   - Llama 3 (&lt;|start_header_id|&gt;): por completitud.
 * El resultado es una lista de piezas; las marcas se tokenizan como especiales y el contenido del
 * usuario nunca (asi un usuario que escriba "&lt;|im_end|&gt;" no inyecta un token de control).
 */
final class ChatTemplate {

    static final int CHATML = 0, CHATML_QWEN3 = 1, LLAMA3 = 2;

    /** Una pieza del prompt: texto literal o marca especial. */
    static final class Piece {
        final String text;
        final boolean special;

        Piece(String text, boolean special) {
            this.text = text;
            this.special = special;
        }
    }

    final int kind;
    final String defaultSystem;

    ChatTemplate(int kind, String defaultSystem) {
        this.kind = kind;
        this.defaultSystem = defaultSystem;
    }

    /** Elige la plantilla a partir de los metadatos del modelo. */
    static ChatTemplate detect(String arch, String jinja, Tokenizer tk) throws IOException {
        String t = jinja == null ? "" : jinja;
        boolean hasImStart = tk.idOf("<|im_start|>") >= 0;
        if (t.contains("<|im_sep|>")) throw new IOException("Plantilla de chat no admitida (<|im_sep|>)");
        if (t.contains("<|im_start|>") || (t.isEmpty() && hasImStart)) {
            if ("qwen3".equals(arch) || t.contains("enable_thinking")) return new ChatTemplate(CHATML_QWEN3, null);
            String system = null;
            if (t.contains("named SmolLM")) system = "You are a helpful AI assistant named SmolLM, trained by Hugging Face";
            else if (t.contains("You are Qwen, created by Alibaba Cloud")) system = "You are Qwen, created by Alibaba Cloud. You are a helpful assistant.";
            return new ChatTemplate(CHATML, system);
        }
        if (t.contains("<|start_header_id|>") || (t.isEmpty() && tk.idOf("<|start_header_id|>") >= 0)) {
            return new ChatTemplate(LLAMA3, null);
        }
        if (!t.isEmpty()) {
            throw new IOException("Plantilla de chat no admitida: " + t.substring(0, Math.min(40, t.length())).replace('\n', ' '));
        }
        // Sin plantilla en los metadatos: ChatML, que es lo que usan los modelos admitidos
        if ("qwen3".equals(arch)) return new ChatTemplate(CHATML_QWEN3, null);
        return new ChatTemplate(CHATML, null);
    }

    String name() {
        switch (kind) {
            case CHATML_QWEN3: return "ChatML (Qwen3, sin pensamiento)";
            case LLAMA3: return "Llama 3";
            default: return "ChatML";
        }
    }

    /** Construye las piezas del prompt para los mensajes dados; termina con la cabecera del asistente. */
    List<Piece> build(List<ChatMessage> messages) {
        List<Piece> out = new ArrayList<>();
        boolean hasSystem = false;
        for (ChatMessage m : messages) if ("system".equals(m.role)) hasSystem = true;
        if (kind == LLAMA3) {
            out.add(new Piece("<|begin_of_text|>", true));
            if (!hasSystem && defaultSystem != null) llama3Message(out, "system", defaultSystem);
            for (ChatMessage m : messages) llama3Message(out, m.role, m.content);
            out.add(new Piece("<|start_header_id|>", true));
            out.add(new Piece("assistant", false));
            out.add(new Piece("<|end_header_id|>", true));
            out.add(new Piece("\n\n", false));
            return out;
        }
        if (!hasSystem && defaultSystem != null) chatmlMessage(out, "system", defaultSystem);
        for (ChatMessage m : messages) chatmlMessage(out, m.role, m.content);
        out.add(new Piece("<|im_start|>", true));
        out.add(new Piece("assistant\n", false));
        if (kind == CHATML_QWEN3) {
            out.add(new Piece("<think>", true));
            out.add(new Piece("\n\n", false));
            out.add(new Piece("</think>", true));
            out.add(new Piece("\n\n", false));
        }
        return out;
    }

    private static void chatmlMessage(List<Piece> out, String role, String content) {
        out.add(new Piece("<|im_start|>", true));
        out.add(new Piece(role + "\n" + content, false));
        out.add(new Piece("<|im_end|>", true));
        out.add(new Piece("\n", false));
    }

    private static void llama3Message(List<Piece> out, String role, String content) {
        out.add(new Piece("<|start_header_id|>", true));
        out.add(new Piece(role, false));
        out.add(new Piece("<|end_header_id|>", true));
        out.add(new Piece("\n\n" + content, false));
        out.add(new Piece("<|eot_id|>", true));
    }

    /** El prompt como texto plano (para depuracion y pruebas). */
    static String text(List<Piece> pieces) {
        StringBuilder sb = new StringBuilder();
        for (Piece p : pieces) sb.append(p.text);
        return sb.toString();
    }

    /** Tokeniza las piezas: las marcas como especiales, el resto como texto del usuario. */
    static int[] tokenize(List<Piece> pieces, Tokenizer tk) {
        Tokenizer.IntList ids = new Tokenizer.IntList(256);
        for (Piece p : pieces) {
            if (p.special) {
                int id = tk.idOf(p.text);
                if (id >= 0) {
                    ids.add(id);
                    continue;
                }
            }
            for (int id : tk.encode(p.text, false, false)) ids.add(id);
        }
        return ids.toArray();
    }
}
