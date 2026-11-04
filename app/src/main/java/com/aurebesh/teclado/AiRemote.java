package com.aurebesh.teclado;

import com.aurebesh.teclado.llm.ChatMessage;
import com.aurebesh.teclado.llm.LlmEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente minimo de un servidor compatible con la API de OpenAI (Ollama, LM Studio, llama.cpp):
 * {@code POST {base}/chat/completions} con {@code stream:true} leido como SSE, y
 * {@code GET {base}/models} para la lista de modelos. La cabecera Authorization solo se envia si
 * hay clave. Tiempos de espera de 60 s. Solo se habla con la direccion que configuro el usuario.
 */
final class AiRemote {

    private static final int TIMEOUT_MS = 60000;
    static final String[] STOP = {"<|im_end|>", "<|im_start|>", "<|endoftext|>", "\n\n\n"};

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpURLConnection conn;

    /** Corta la peticion en curso (se puede llamar desde cualquier hilo). */
    void cancel() {
        cancelled.set(true);
        final HttpURLConnection h = conn;
        if (h != null) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { try { h.disconnect(); } catch (Exception ignored) { } }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    boolean isCancelled() { return cancelled.get(); }

    /**
     * Envia la conversacion y va entregando los trozos de texto al oyente; devuelve el texto
     * completo. El oyente puede devolver false para parar.
     */
    String chat(String baseUrl, String key, String model, List<ChatMessage> messages, LlmEngine.Options o,
                LlmEngine.TokenListener listener) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model == null || model.trim().isEmpty() ? "default" : model.trim());
            JSONArray msgs = new JSONArray();
            for (ChatMessage m : messages) {
                JSONObject j = new JSONObject();
                j.put("role", m.role);
                j.put("content", m.content);
                msgs.put(j);
            }
            body.put("messages", msgs);
            body.put("stream", true);
            body.put("temperature", o.temperature);
            body.put("top_p", o.topP);
            body.put("max_tokens", o.maxTokens);
            JSONArray stop = new JSONArray();
            for (String s : STOP) stop.put(s);
            body.put("stop", stop);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection h = open(endpoint(baseUrl, "chat/completions"), key);
        conn = h;
        StringBuilder full = new StringBuilder();
        try {
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("Accept", "text/event-stream");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = h.getOutputStream();
            out.write(payload);
            out.close();
            int code = h.getResponseCode();
            if (code / 100 != 2) throw new IOException(httpError(h, code));
            String type = h.getContentType();
            InputStream in = h.getInputStream();
            if (type != null && type.contains("application/json") && !type.contains("stream")) {
                // El servidor ignoro stream=true: respuesta completa en un solo JSON.
                String text = readAll(in);
                String piece = parseMessage(text);
                if (piece != null && !piece.isEmpty()) {
                    full.append(piece);
                    listener.onToken(piece);
                }
                return full.toString();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancelled.get()) break;
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (data.equals("[DONE]")) break;
                        if (data.isEmpty()) continue;
                        String piece = parseDelta(data);
                        if (piece == null || piece.isEmpty()) continue;
                        full.append(piece);
                        if (!listener.onToken(piece)) break;
                    } else if (line.startsWith("{")) {
                        // Ollama nativo u otro servidor que emite NDJSON sin prefijo
                        String piece = parseDelta(line);
                        if (piece == null) piece = parseMessage(line);
                        if (piece == null || piece.isEmpty()) continue;
                        full.append(piece);
                        if (!listener.onToken(piece)) break;
                    }
                }
            } finally {
                try { r.close(); } catch (IOException ignored) { }
            }
            return full.toString();
        } catch (IOException e) {
            if (cancelled.get()) return full.toString();
            throw e;
        } finally {
            conn = null;
            try { h.disconnect(); } catch (Exception ignored) { }
        }
    }

    /** Lista de modelos del servidor ({@code GET {base}/models}, campo data[].id). */
    static List<String> models(String baseUrl, String key) throws IOException {
        HttpURLConnection h = open(endpoint(baseUrl, "models"), key);
        try {
            h.setRequestMethod("GET");
            int code = h.getResponseCode();
            if (code / 100 != 2) throw new IOException(httpError(h, code));
            String text = readAll(h.getInputStream());
            List<String> out = new ArrayList<>();
            try {
                JSONObject j = new JSONObject(text);
                JSONArray data = j.optJSONArray("data");
                if (data == null) data = j.optJSONArray("models");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject m = data.optJSONObject(i);
                        if (m == null) continue;
                        String id = m.optString("id", m.optString("name", ""));
                        if (!id.isEmpty()) out.add(id);
                    }
                }
            } catch (JSONException e) {
                throw new IOException("JSON");
            }
            return out;
        } finally {
            h.disconnect();
        }
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Normaliza la direccion base escrita por el usuario: anade http:// si falta el esquema,
     * quita barras finales y un "/chat/completions" pegado, y anade "/v1" si no hay ruta.
     */
    static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return u;
        if (!u.contains("://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions")) u = u.substring(0, u.length() - "/chat/completions".length());
        if (u.endsWith("/models")) u = u.substring(0, u.length() - "/models".length());
        int scheme = u.indexOf("://") + 3;
        int slash = u.indexOf('/', scheme);
        if (slash < 0) u = u + "/v1";
        return u;
    }

    static String endpoint(String baseUrl, String path) {
        return normalize(baseUrl) + "/" + path;
    }

    private static HttpURLConnection open(String url, String key) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(TIMEOUT_MS);
        h.setReadTimeout(TIMEOUT_MS);
        h.setUseCaches(false);
        h.setRequestProperty("User-Agent", "TecladoAurebesh");
        if (key != null && !key.trim().isEmpty()) h.setRequestProperty("Authorization", "Bearer " + key.trim());
        return h;
    }

    private static String httpError(HttpURLConnection h, int code) {
        String msg = "HTTP " + code;
        try {
            InputStream err = h.getErrorStream();
            if (err != null) {
                String body = readAll(err);
                try {
                    JSONObject j = new JSONObject(body);
                    JSONObject e = j.optJSONObject("error");
                    String m = e != null ? e.optString("message", "") : j.optString("error", "");
                    if (!m.isEmpty()) msg += " · " + (m.length() > 80 ? m.substring(0, 80) : m);
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return msg;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Texto de choices[0].delta.content de un evento SSE, o null si no lo hay. */
    static String parseDelta(String json) {
        try {
            JSONObject j = new JSONObject(json);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                // Ollama nativo: {"message":{"content":"..."},"done":false}
                JSONObject m = j.optJSONObject("message");
                return m != null ? m.optString("content", "") : null;
            }
            JSONObject c = choices.optJSONObject(0);
            if (c == null) return null;
            JSONObject delta = c.optJSONObject("delta");
            if (delta != null) {
                if (delta.isNull("content")) return "";
                return delta.optString("content", "");
            }
            String text = c.optString("text", null);
            return text;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Texto de choices[0].message.content de una respuesta completa, o null. */
    static String parseMessage(String json) {
        try {
            JSONObject j = new JSONObject(json);
            JSONArray choices = j.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c = choices.optJSONObject(0);
                JSONObject m = c == null ? null : c.optJSONObject("message");
                if (m != null) return m.optString("content", "");
            }
            JSONObject m = j.optJSONObject("message");
            return m != null ? m.optString("content", "") : null;
        } catch (JSONException e) {
            return null;
        }
    }
}
