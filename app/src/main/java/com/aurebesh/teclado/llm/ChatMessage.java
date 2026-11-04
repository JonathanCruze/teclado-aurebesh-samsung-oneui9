package com.aurebesh.teclado.llm;

/** Un mensaje de la conversacion: role es "system", "user" o "assistant". */
public final class ChatMessage {
    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role == null ? "user" : role;
        this.content = content == null ? "" : content;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
