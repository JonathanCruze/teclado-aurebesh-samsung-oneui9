package com.aurebesh.teclado;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.aurebesh.teclado.llm.ChatMessage;
import com.aurebesh.teclado.llm.LlmEngine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ejecuta las acciones del panel de IA: construye el mensaje para cada accion en el idioma del
 * texto (es/en/de), lo manda al modelo local (motor GGUF cargado una vez y guardado en memoria
 * mientras vive el teclado) o al servidor del usuario, entrega los trozos al hilo principal
 * mientras se generan y limpia la salida (vallas de codigo, comillas, preambulos tipo "Aqui tienes").
 * Las peticiones van en serie por un unico hilo; una nueva cancela la anterior.
 */
final class AiRunner {

    /** Acciones del panel. */
    enum Action {
        FIX, STYLE_ORIGINAL, STYLE_FORMAL, STYLE_RESPECTFUL, STYLE_FRIENDLY, STYLE_CUSTOM,
        SUMMARY, BULLETS, TABLE, COMPOSE;

        boolean isStyle() { return this == STYLE_ORIGINAL || this == STYLE_FORMAL || this == STYLE_RESPECTFUL
                || this == STYLE_FRIENDLY || this == STYLE_CUSTOM; }
        boolean isRewrite() { return this == FIX || isStyle(); }
    }

    /** Peticion: accion, texto fuente y, segun la accion, tono o encargo del Redactor. */
    static final class Request {
        final Action action;
        final String source;      // texto del campo o la seleccion (puede ser vacio)
        String tone;              // STYLE_CUSTOM
        String prompt;            // COMPOSE
        String lang = "es";       // idioma del teclado: "es", "en" o "de"
        boolean useContext = true; // COMPOSE: usar el texto del campo como contexto
        int retries = 0;          // cada reintento sube la temperatura

        Request(Action action, String source) {
            this.action = action;
            this.source = source == null ? "" : source;
        }

        Request copyForRetry() {
            Request r = new Request(action, source);
            r.tone = tone; r.prompt = prompt; r.lang = lang; r.useContext = useContext;
            r.retries = retries + 1;
            return r;
        }
    }

    /** Avisos al panel. Siempre en el hilo principal. */
    interface Callback {
        void onPhase(CharSequence label);                        // "Cargando el modelo…", "Conectando…", "Leyendo…"
        void onPiece(String soFar, int tokens, long elapsedMs);  // texto acumulado (sin limpiar), ritmo
        void onDone(String text, boolean weak);                  // texto limpio; weak = parece mal, sugerir reintento
        void onCancelled(String partialText);                    // texto parcial limpio (puede ser vacio)
        void onError(CharSequence message);
    }

    static final int LOCAL_MAX_CHARS = 1800;
    static final int REMOTE_MAX_CHARS = 8000;
    private static final long LOCAL_TOTAL_MS = 180000, LOCAL_FIRST_MS = 90000, REMOTE_TOTAL_MS = 120000;
    private static final String[] STOP = AiRemote.STOP;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ai-runner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        }
    });
    private final AtomicInteger seq = new AtomicInteger();
    private volatile AtomicBoolean cancelled = new AtomicBoolean(true);
    private volatile AiRemote remote;
    private volatile boolean busy;

    private LlmEngine engine;           // solo lo toca el hilo de trabajo
    private String engineFile;
    private final Object engineLock = new Object();

    AiRunner(Context c) { app = c.getApplicationContext(); }

    boolean isBusy() { return busy; }

    /** Hay motor disponible segun las preferencias actuales. */
    boolean available() { return AiModels.available(app); }

    boolean isRemote() { return AiModels.remote(app); }

    /** Etiqueta del motor: "Local · Qwen2.5 0.5B", "Servidor · 192.168.1.20" o "Sin modelo". */
    String engineLabel() {
        String name = AiModels.engineShortName(app);
        if (name == null) return app.getString(R.string.ai_badge_none);
        return app.getString(isRemote() ? R.string.ai_badge_remote : R.string.ai_badge_local, name);
    }

    int maxChars() { return isRemote() ? REMOTE_MAX_CHARS : LOCAL_MAX_CHARS; }

    /** Para la generacion en curso (si la hay). El panel recibira onCancelled. */
    void cancel() {
        cancelled.set(true);
        AiRemote r = remote;
        if (r != null) r.cancel();
    }

    /** Libera el modelo cargado y el hilo de trabajo (al destruir el servicio). */
    void release() {
        cancel();
        exec.execute(new Runnable() {
            @Override public void run() { closeEngine(); }
        });
        exec.shutdown();
    }

    /** Descarga el modelo de memoria pero deja el hilo listo (poca memoria, teclado oculto mucho tiempo). */
    void unloadModel() {
        if (busy) return;
        exec.execute(new Runnable() {
            @Override public void run() { closeEngine(); }
        });
    }

    private void closeEngine() {
        synchronized (engineLock) {
            if (engine != null) {
                try { engine.close(); } catch (Exception ignored) { }
                engine = null;
                engineFile = null;
            }
        }
    }

    // ------------------------------------------------------------------ ejecucion

    /** Lanza la peticion; cancela la anterior si seguia en marcha. */
    void run(final Request req, final Callback cb) {
        cancel();
        final int id = seq.incrementAndGet();
        final AtomicBoolean flag = new AtomicBoolean(false);
        cancelled = flag;
        busy = true;
        final boolean useRemote = isRemote();
        final Runnable watchdog = new Runnable() {
            @Override public void run() {
                if (seq.get() != id || flag.get()) return;
                flag.set(true);
                AiRemote r = remote;
                if (r != null) r.cancel();
                busy = false;
                cb.onError(app.getString(R.string.ai_err_timeout));
            }
        };
        main.postDelayed(watchdog, useRemote ? REMOTE_TOTAL_MS : LOCAL_TOTAL_MS);
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                work(req, cb, id, flag, useRemote, watchdog);
            }
        });
    }

    private boolean current(int id, AtomicBoolean flag) { return seq.get() == id && !flag.get(); }

    private void work(final Request req, final Callback cb, final int id, final AtomicBoolean flag,
                      boolean useRemote, final Runnable watchdog) {
        final StringBuilder raw = new StringBuilder();
        final long start = System.currentTimeMillis();
        final int[] tokens = {0};
        final long[] lastPost = {0};
        final boolean[] firstToken = {false};
        String textLang = req.action == Action.COMPOSE ? req.lang : detectLanguage(req.source, req.lang);
        String source = truncate(req.source, useRemote ? REMOTE_MAX_CHARS : LOCAL_MAX_CHARS);
        List<ChatMessage> messages = messages(req, source, textLang);
        LlmEngine.Options o = options(req.action, source.length(), req.retries);
        final Runnable firstWatch = new Runnable() {
            @Override public void run() {
                if (!current(id, flag) || firstToken[0]) return;
                flag.set(true);
                busy = false;
                cb.onError(app.getString(R.string.ai_err_timeout));
            }
        };
        LlmEngine.TokenListener listener = new LlmEngine.TokenListener() {
            @Override public boolean onToken(String piece) {
                if (!current(id, flag)) return false;
                if (!firstToken[0]) {
                    firstToken[0] = true;
                    main.removeCallbacks(firstWatch);
                }
                raw.append(piece);
                tokens[0]++;
                if (hitsStop(raw)) return false;
                long now = System.currentTimeMillis();
                if (now - lastPost[0] >= 80) {
                    lastPost[0] = now;
                    final String s = stripStops(raw.toString());
                    final int t = tokens[0];
                    final long e = now - start;
                    main.post(new Runnable() {
                        @Override public void run() { if (current(id, flag)) cb.onPiece(s, t, e); }
                    });
                }
                return true;
            }
        };
        String result = null;
        CharSequence error = null;
        try {
            if (useRemote) {
                phase(cb, id, flag, app.getString(R.string.ai_connecting));
                AiRemote r = new AiRemote();
                remote = r;
                try {
                    result = r.chat(AiModels.remoteUrl(app), AiModels.remoteKey(app), AiModels.remoteModel(app),
                            messages, o, listener);
                } finally {
                    remote = null;
                }
            } else {
                LlmEngine e = ensureEngine(cb, id, flag);
                if (e == null) return;              // ya se aviso del error o se cancelo
                if (!current(id, flag)) { finishCancelled(cb, id, raw, req, watchdog); return; }
                phase(cb, id, flag, app.getString(R.string.ai_reading));
                main.postDelayed(firstWatch, LOCAL_FIRST_MS);
                if (e.architecture() != null && e.architecture().toLowerCase(Locale.ROOT).contains("qwen3")) {
                    messages = noThink(messages);
                }
                result = e.generate(messages, o, listener);
            }
        } catch (IOException ex) {
            if (!flag.get()) {
                String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                error = useRemote ? app.getString(R.string.ai_err_server, m) : app.getString(R.string.ai_err_model_bad);
            }
        } catch (OutOfMemoryError oom) {
            closeEngine();
            error = app.getString(R.string.ai_err_memory);
        } catch (RuntimeException ex) {
            if (!flag.get()) error = useRemote
                    ? app.getString(R.string.ai_err_server, ex.getClass().getSimpleName())
                    : app.getString(R.string.ai_err_model_bad);
        } finally {
            main.removeCallbacks(firstWatch);
        }
        if (seq.get() != id) return;                // otra peticion tomo el relevo
        if (flag.get()) { finishCancelled(cb, id, raw, req, watchdog); return; }
        final String text = result != null && result.length() >= raw.length() ? result : raw.toString();
        final CharSequence err = error;
        main.post(new Runnable() {
            @Override public void run() {
                main.removeCallbacks(watchdog);
                if (seq.get() != id) return;
                busy = false;
                if (err != null) { cb.onError(err); return; }
                Cleaned c = cleanChecked(req.action, text, req.source);
                if (c.errorRes != 0) { cb.onError(app.getString(c.errorRes)); return; }
                cb.onDone(c.text, c.weak);
            }
        });
    }

    private void finishCancelled(final Callback cb, final int id, StringBuilder raw, Request req, final Runnable watchdog) {
        final String partial = clean(req.action, raw.toString(), req.source);
        main.post(new Runnable() {
            @Override public void run() {
                main.removeCallbacks(watchdog);
                if (seq.get() != id) return;
                busy = false;
                cb.onCancelled(partial);
            }
        });
    }

    private void phase(final Callback cb, final int id, final AtomicBoolean flag, final CharSequence label) {
        main.post(new Runnable() {
            @Override public void run() { if (current(id, flag)) cb.onPhase(label); }
        });
    }

    /** Carga el motor si hace falta (o si cambio el modelo elegido). Devuelve null tras avisar del error. */
    private LlmEngine ensureEngine(final Callback cb, final int id, final AtomicBoolean flag) {
        File f = AiModels.selected(app);
        if (f == null) {
            fail(cb, id, flag, app.getString(R.string.ai_err_no_model));
            return null;
        }
        synchronized (engineLock) {
            if (engine != null && f.getAbsolutePath().equals(engineFile)) return engine;
            closeEngine();
            phase(cb, id, flag, app.getString(R.string.ai_loading_model));
            try {
                int cpus = Runtime.getRuntime().availableProcessors();
                int threads = Math.max(1, Math.min(4, cpus / 2));
                engine = LlmEngine.load(f, threads);
                engineFile = f.getAbsolutePath();
                return engine;
            } catch (IOException e) {
                fail(cb, id, flag, app.getString(R.string.ai_err_model_bad));
            } catch (OutOfMemoryError e) {
                fail(cb, id, flag, app.getString(R.string.ai_err_memory));
            }
            return null;
        }
    }

    private void fail(final Callback cb, final int id, final AtomicBoolean flag, final CharSequence msg) {
        main.post(new Runnable() {
            @Override public void run() {
                if (seq.get() != id) return;
                flag.set(true);
                busy = false;
                cb.onError(msg);
            }
        });
    }

    /** Qwen3: desactiva el razonamiento previo anadiendo /no_think al mensaje del usuario. */
    private static List<ChatMessage> noThink(List<ChatMessage> in) {
        List<ChatMessage> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            ChatMessage m = in.get(i);
            if (i == in.size() - 1 && "user".equals(m.role)) out.add(new ChatMessage(m.role, m.content + " /no_think"));
            else out.add(m);
        }
        return out;
    }

    private static boolean hitsStop(StringBuilder b) {
        int from = Math.max(0, b.length() - 16);
        String tail = b.substring(from);
        for (String s : STOP) if (tail.contains(s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ texto fuente

    /** Recorta al limite, en el ultimo final de frase anterior si lo hay. */
    static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        String cut = s.substring(0, max);
        int best = -1;
        for (int i = cut.length() - 1; i >= max / 2; i--) {
            char ch = cut.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') { best = i; break; }
        }
        return best > 0 ? cut.substring(0, best + 1) : cut;
    }

    private static final Set<String> ES = new HashSet<>(Arrays.asList(("de la que el en y los se del las por un para con una su al es lo "
            + "como más pero sus le ya o este sí porque esta entre cuando muy sin sobre también me hasta hay donde quien "
            + "desde todo nos durante todos uno les ni contra otros ese eso ante ellos e esto mí antes algunos").split(" ")));
    private static final Set<String> EN = new HashSet<>(Arrays.asList(("the and to of a in is it you that he was for on are with "
            + "as i his they be at one have this from or had by not word but what some we can out other were all there "
            + "when up use your how said an each she which do their time if will way about many then").split(" ")));
    private static final Set<String> DE = new HashSet<>(Arrays.asList(("der die und in den von zu das mit sich des auf für "
            + "ist im dem nicht ein eine als auch es an werden aus er hat dass sie nach wird bei einer um am sind noch wie "
            + "einem über einen so zum war haben nur oder aber vor zur bis mehr durch man sein wurde sei ich wir ihr").split(" ")));

    /** Idioma del texto por palabras frecuentes ("es", "en" o "de"); si no esta claro, el del teclado. */
    static String detectLanguage(String text, String fallback) {
        String fb = "en".equals(fallback) ? "en" : "de".equals(fallback) ? "de" : "es";
        if (text == null || text.isEmpty()) return fb;
        int es = 0, en = 0, de = 0;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            if (ES.contains(w)) es++;
            if (EN.contains(w)) en++;
            if (DE.contains(w)) de++;
        }
        int top = Math.max(es, Math.max(en, de));
        if (top < 3) return fb;
        if (es == top && en != top && de != top) return "es";
        if (en == top && es != top && de != top) return "en";
        if (de == top && es != top && en != top) return "de";
        return fb;
    }

    // ------------------------------------------------------------------ mensajes

    static String systemPrompt(String lang) {
        if ("en".equals(lang)) return "You are a writing assistant. Reply only with the requested text: no explanations, "
                + "no greetings, no quotes, no titles or notes. Keep the language of the original text.";
        if ("de".equals(lang)) return "Du bist ein Schreibassistent. Antworte nur mit dem gewünschten Text: ohne "
                + "Erklärungen, ohne Begrüßung, ohne Anführungszeichen, ohne Titel oder Anmerkungen. Behalte die Sprache "
                + "des Originaltexts bei.";
        return "Eres un asistente de escritura. Respondes únicamente con el texto pedido: sin explicaciones, sin saludos, "
                + "sin comillas, sin títulos ni notas. Conservas el idioma del texto original.";
    }

    /** Mensajes (system + user) para la peticion; el texto fuente va al final, sin comillas. */
    static List<ChatMessage> messages(Request req, String source, String textLang) {
        List<ChatMessage> out = new ArrayList<>(2);
        out.add(new ChatMessage("system", systemPrompt(textLang)));
        out.add(new ChatMessage("user", userPrompt(req, source, textLang)));
        return out;
    }

    static String userPrompt(Request req, String source, String lang) {
        boolean en = "en".equals(lang), de = "de".equals(lang);
        String textLabel = en ? "Text:\n" : de ? "Text:\n" : "Texto:\n";
        String tail = "\n\n" + textLabel + source;
        switch (req.action) {
            case FIX:
            case STYLE_ORIGINAL:
                return (en ? "Fix the spelling, punctuation and grammar of the following text. Do not change its meaning, "
                        + "tone, order or formatting. Return only the corrected text."
                        : de ? "Korrigiere Rechtschreibung, Zeichensetzung und Grammatik des folgenden Texts. Ändere weder "
                        + "Bedeutung, Ton, Reihenfolge noch Format. Gib nur den korrigierten Text zurück."
                        : "Corrige la ortografía, los acentos, la puntuación y la gramática del siguiente texto. No cambies "
                        + "el significado, el tono, el orden ni el formato. Devuelve solo el texto corregido.") + tail;
            case STYLE_FORMAL:
                return (en ? "Rewrite the following text in a formal, professional tone, also fixing spelling. Keep the "
                        + "meaning and a similar length. Return only the rewritten text."
                        : de ? "Formuliere den folgenden Text in einem formellen, professionellen Ton um und korrigiere dabei "
                        + "die Rechtschreibung. Behalte Bedeutung und ähnliche Länge bei. Gib nur den neuen Text zurück."
                        : "Reescribe el siguiente texto con un tono formal y profesional, corrigiendo también la ortografía. "
                        + "Mantén el significado y una longitud parecida. Devuelve solo el texto reescrito.") + tail;
            case STYLE_RESPECTFUL:
                return (en ? "Rewrite the following text in a respectful, polite tone, without offensive words, also fixing "
                        + "spelling. Keep the meaning and a similar length. Return only the rewritten text."
                        : de ? "Formuliere den folgenden Text in einem respektvollen, höflichen Ton ohne beleidigende Wörter um "
                        + "und korrigiere dabei die Rechtschreibung. Behalte Bedeutung und ähnliche Länge bei. Gib nur den "
                        + "neuen Text zurück."
                        : "Reescribe el siguiente texto con un tono respetuoso y cortés, sin palabras ofensivas, corrigiendo "
                        + "también la ortografía. Mantén el significado y una longitud parecida. Devuelve solo el texto "
                        + "reescrito.") + tail;
            case STYLE_FRIENDLY:
                return (en ? "Rewrite the following text in a friendly, warm tone, also fixing spelling. Keep the meaning and "
                        + "a similar length. Return only the rewritten text."
                        : de ? "Formuliere den folgenden Text in einem freundlichen, warmen Ton um und korrigiere dabei die "
                        + "Rechtschreibung. Behalte Bedeutung und ähnliche Länge bei. Gib nur den neuen Text zurück."
                        : "Reescribe el siguiente texto con un tono amigable y cercano, corrigiendo también la ortografía. "
                        + "Mantén el significado y una longitud parecida. Devuelve solo el texto reescrito.") + tail;
            case STYLE_CUSTOM: {
                String tone = sanitize(req.tone, 60);
                return (en ? "Rewrite the following text in this tone: " + tone + ". Keep the meaning and a similar length. "
                        + "Return only the rewritten text."
                        : de ? "Formuliere den folgenden Text in diesem Ton um: " + tone + ". Behalte Bedeutung und ähnliche "
                        + "Länge bei. Gib nur den neuen Text zurück."
                        : "Reescribe el siguiente texto con este tono: " + tone + ". Mantén el significado y una longitud "
                        + "parecida. Devuelve solo el texto reescrito.") + tail;
            }
            case SUMMARY: {
                int n = sentenceBudget(source.length());
                return (en ? "Summarize the following text in at most " + n + " sentence(s), in the same language. Return "
                        + "only the summary."
                        : de ? "Fasse den folgenden Text in höchstens " + n + " Satz/Sätzen zusammen, in derselben Sprache. "
                        + "Gib nur die Zusammenfassung zurück."
                        : "Resume el siguiente texto en " + n + " frase(s) como máximo, en el mismo idioma. Devuelve solo "
                        + "el resumen.") + tail;
            }
            case BULLETS:
                return (en ? "Turn the following text into a bulleted list. Each bullet starts with \"• \" and holds one idea "
                        + "in a short sentence. Write nothing before or after the list."
                        : de ? "Wandle den folgenden Text in eine Aufzählung um. Jeder Punkt beginnt mit \"• \" und enthält "
                        + "einen Gedanken in einem kurzen Satz. Schreibe nichts vor oder nach der Liste."
                        : "Convierte el siguiente texto en una lista de puntos. Cada punto empieza con \"• \" y contiene una "
                        + "sola idea en una frase corta. No escribas nada antes ni después de la lista.") + tail;
            case TABLE:
                return (en ? "Organize the information in the following text as a Markdown table: a header row, a dash "
                        + "separator row and one row per item, columns separated by \"|\". Return only the table."
                        : de ? "Ordne die Informationen des folgenden Texts als Markdown-Tabelle: eine Kopfzeile, eine "
                        + "Trennzeile mit Bindestrichen und eine Zeile pro Element, Spalten getrennt durch \"|\". Gib nur die "
                        + "Tabelle zurück."
                        : "Organiza la información del siguiente texto en una tabla Markdown: una fila de encabezados, una "
                        + "fila separadora con guiones y una fila por elemento, columnas separadas por \"|\". Devuelve solo "
                        + "la tabla.") + tail;
            case COMPOSE:
            default: {
                String ask = sanitize(req.prompt, 300);
                String idioma = en ? "English" : de ? "Deutsch" : "español";
                boolean ctx = req.useContext && !source.trim().isEmpty();
                String head = en ? "Write a short text in " + idioma + " based on this request: " + ask + "."
                        : de ? "Schreibe einen kurzen Text auf " + idioma + " zu dieser Bitte: " + ask + "."
                        : "Escribe un texto breve en " + idioma + " a partir de esta petición: " + ask + ".";
                if (!ctx) {
                    return head + (en ? " Write only the final text, ready to send."
                            : de ? " Schreibe nur den endgültigen Text, bereit zum Senden."
                            : " Escribe solo el texto final, listo para enviar.");
                }
                return head + (en ? " Use the following text as context. Write only the final text, ready to send.\n\nContext:\n"
                        : de ? " Nutze den folgenden Text als Kontext. Schreibe nur den endgültigen Text, bereit zum Senden.\n\nKontext:\n"
                        : " Usa el siguiente texto como contexto. Escribe solo el texto final, listo para enviar.\n\nContexto:\n")
                        + source;
            }
        }
    }

    static int sentenceBudget(int chars) { return chars < 300 ? 1 : chars < 900 ? 2 : 3; }

    /** Tono o encargo del usuario: sin saltos de linea, comillas ni marcas de plantilla; recortado. */
    static String sanitize(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("<\\|[^|>]{0,40}\\|>", " ").replace("\n", " ").replace("\r", " ").replace("\"", "")
                .replace("<|", "").replace("|>", "").replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    /** Parametros de generacion por accion (ver especificacion); los reintentos suben la temperatura. */
    static LlmEngine.Options options(Action a, int srcChars, int retries) {
        LlmEngine.Options o = new LlmEngine.Options();
        int srcTokens = Math.max(8, (int) Math.ceil(srcChars / 3.5));
        switch (a) {
            case FIX:
            case STYLE_ORIGINAL:
                o.temperature = 0.15f; o.repeatPenalty = 1.05f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.3) + 16, 400);
                break;
            case STYLE_FORMAL: case STYLE_RESPECTFUL: case STYLE_FRIENDLY: case STYLE_CUSTOM:
                o.temperature = 0.35f; o.repeatPenalty = 1.10f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.5) + 24, 450);
                break;
            case SUMMARY:
                o.temperature = 0.30f; o.repeatPenalty = 1.10f;
                o.maxTokens = Math.min(60 * sentenceBudget(srcChars) + 20, 200);
                break;
            case BULLETS:
                o.temperature = 0.20f; o.repeatPenalty = 1.10f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.4) + 24, 350);
                break;
            case TABLE:
                o.temperature = 0.15f; o.repeatPenalty = 1.05f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.8) + 40, 400);
                break;
            case COMPOSE:
            default:
                o.temperature = 0.70f; o.topP = 0.95f; o.repeatPenalty = 1.15f; o.maxTokens = 220;
                break;
        }
        o.topP = a == Action.COMPOSE ? 0.95f : 0.90f;
        o.temperature = Math.min(0.9f, o.temperature + 0.15f * retries);
        o.seed = System.nanoTime();
        return o;
    }

    // ------------------------------------------------------------------ limpieza

    /** Resultado de la limpieza con comprobaciones por accion. */
    static final class Cleaned {
        final String text; final boolean weak; final int errorRes;
        Cleaned(String text, boolean weak, int errorRes) { this.text = text; this.weak = weak; this.errorRes = errorRes; }
    }

    private static final Pattern LABEL_LINE = Pattern.compile(
            "(?i)^(texto|text|resultado|result|resumen|summary|tabla|table|lista|list|aquí (está|tienes)|here (is|are)|hier (ist|sind)|"
            + "corrected|corregido|korrigiert|zusammenfassung|tabelle|sure|claro|por supuesto|certainly|of course|respuesta|answer|antwort)[^\\n]{0,60}:$");
    private static final Pattern INLINE_PREFIX = Pattern.compile(
            "(?i)^(texto corregido|corrected text|texto reescrito|rewritten text|resumen|summary|zusammenfassung|korrigierter text|"
            + "tabla|table|tabelle|respuesta|answer|antwort)\\s*:\\s*");
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?(</think>|$)");

    /** Limpieza general sin comprobaciones (para resultados parciales). */
    static String clean(Action a, String raw, String source) {
        return cleanChecked(a, raw, source).text;
    }

    static Cleaned cleanChecked(Action a, String raw, String source) {
        if (raw == null) raw = "";
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        // 1. Marcas de plantilla y razonamiento previo
        s = THINK.matcher(s).replaceAll("");
        s = stripStops(s);
        s = s.replaceAll("<\\|[^|>]{1,40}\\|>", "");
        // 2. Vallas de codigo (se conserva el contenido)
        s = s.replaceAll("(?m)^```[A-Za-z0-9_-]*[ \\t]*\\n?", "");
        s = s.replace("```", "");
        s = s.trim();
        // 3. Linea de etiqueta inicial y prefijos
        int nl = s.indexOf('\n');
        if (nl > 0 && nl <= 60) {
            String first = s.substring(0, nl).trim();
            if (LABEL_LINE.matcher(first).matches()) s = s.substring(nl + 1).trim();
        }
        Matcher pm = INLINE_PREFIX.matcher(s);
        if (pm.find()) s = s.substring(pm.end()).trim();
        // 4. Comillas exteriores (si el original no venia entrecomillado)
        s = s.trim();
        if (!quoted(source) && s.length() >= 2) {
            char f = s.charAt(0), l = s.charAt(s.length() - 1);
            if ((f == '"' && l == '"') || (f == '“' && l == '”') || (f == '«' && l == '»')
                    || (f == '\'' && l == '\'') || (f == '„' && l == '“')) {
                String inner = s.substring(1, s.length() - 1);
                if (inner.indexOf(f) < 0 || f == '„') s = inner.trim();
            }
        }
        // 5. Saltos de linea
        s = s.replaceAll("\n{3,}", "\n\n").trim();
        // 6. Por accion
        boolean weak = false;
        int errorRes = 0;
        switch (a) {
            case BULLETS: {
                StringBuilder b = new StringBuilder();
                for (String line : s.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    t = t.replaceFirst("^([-*·–—•]|\\d{1,2}[.)])\\s*", "");
                    if (t.isEmpty()) continue;
                    if (b.length() > 0) b.append('\n');
                    b.append("• ").append(t);
                }
                s = b.toString();
                if (s.isEmpty()) errorRes = R.string.ai_weak_result;
                break;
            }
            case TABLE: {
                List<String> rows = new ArrayList<>();
                for (String line : s.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("|")) rows.add(t);
                    else if (!rows.isEmpty()) break;
                }
                if (rows.size() < 2) { errorRes = R.string.ai_err_table; break; }
                int cols = columns(rows.get(0));
                if (!rows.get(1).matches("^\\|?(\\s*:?-{2,}:?\\s*\\|)+\\s*:?-*:?\\s*\\|?$")) {
                    StringBuilder sep = new StringBuilder("|");
                    for (int i = 0; i < cols; i++) sep.append("---|");
                    rows.add(1, sep.toString());
                }
                StringBuilder b = new StringBuilder();
                for (String r : rows) {
                    if (b.length() > 0) b.append('\n');
                    b.append(r);
                }
                s = b.toString();
                break;
            }
            case SUMMARY: {
                if (!s.isEmpty() && ".!?…\"»)".indexOf(s.charAt(s.length() - 1)) < 0) {
                    int cut = Math.max(s.lastIndexOf(". "), Math.max(s.lastIndexOf("! "), s.lastIndexOf("? ")));
                    int cutNl = Math.max(s.lastIndexOf(".\n"), Math.max(s.lastIndexOf("!\n"), s.lastIndexOf("?\n")));
                    cut = Math.max(cut, cutNl);
                    if (cut >= 10) s = s.substring(0, cut + 1);   // quita la frase colgante
                    else s = s + ".";
                }
                if (s.isEmpty()) errorRes = R.string.ai_weak_result;
                break;
            }
            case COMPOSE:
                if (s.isEmpty()) errorRes = R.string.ai_weak_result;
                break;
            default: {
                // Ortografia y tonos: longitud comparada con el original
                int src = source == null ? 0 : source.trim().length();
                if (s.isEmpty()) errorRes = R.string.ai_weak_result;
                else if (src > 0 && (s.length() > 3 * src + 20 || (a == Action.FIX && s.length() < 0.3 * src))) weak = true;
                // 7. Espacio o salto final igual que el original
                if (source != null && !source.isEmpty() && errorRes == 0) {
                    String trail = trailingWs(source);
                    s = s.replaceAll("\\s+$", "") + trail;
                }
                break;
            }
        }
        return new Cleaned(s, weak, errorRes);
    }

    static String stripStops(String s) {
        int cut = s.length();
        for (String stop : STOP) {
            int i = s.indexOf(stop);
            if (i >= 0 && i < cut) cut = i;
        }
        return cut < s.length() ? s.substring(0, cut) : s;
    }

    private static boolean quoted(String source) {
        if (source == null) return false;
        String t = source.trim();
        if (t.length() < 2) return false;
        char f = t.charAt(0), l = t.charAt(t.length() - 1);
        return (f == '"' && l == '"') || (f == '“' && l == '”') || (f == '«' && l == '»') || (f == '\'' && l == '\'');
    }

    private static String trailingWs(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(i);
    }

    private static int columns(String row) {
        String t = row.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        return Math.max(1, t.split("\\|", -1).length);
    }
}
