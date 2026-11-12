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
 *
 * <p>El oyente ({@link Callback}) se puede cambiar en marcha con {@link #attach}/{@link #detach}: el servicio
 * comparte un unico runner entre las vistas que va rehaciendo (giro, modo noche, idioma de la app), y el panel
 * nuevo retoma la generacion en curso con su fase y texto acumulados; si termino sin nadie escuchando, el final
 * queda guardado y se entrega al siguiente que se enganche.
 */
final class AiRunner {

    /** Acciones del panel. */
    enum Action {
        FIX, STYLE_ORIGINAL, STYLE_FORMAL, STYLE_RESPECTFUL, STYLE_FRIENDLY, STYLE_CUSTOM,
        SUMMARY, BULLETS, TABLE, COMPOSE, TRANSLATE;

        boolean isStyle() { return this == STYLE_ORIGINAL || this == STYLE_FORMAL || this == STYLE_RESPECTFUL
                || this == STYLE_FRIENDLY || this == STYLE_CUSTOM; }
        boolean isRewrite() { return this == FIX || isStyle() || this == TRANSLATE; }
    }

    /** Peticion: accion, texto fuente y, segun la accion, tono, encargo del Redactor o idioma destino. */
    static final class Request {
        final Action action;
        final String source;      // texto del campo o la seleccion (puede ser vacio)
        String tone;              // STYLE_CUSTOM
        String prompt;            // COMPOSE
        String target;            // TRANSLATE: "es", "en", "de" u otro idioma escrito por el usuario
        String lang = "es";       // idioma del teclado: "es", "en" o "de"
        boolean useContext = true; // COMPOSE: usar el texto del campo como contexto
        int retries = 0;          // cada reintento sube la temperatura

        Request(Action action, String source) {
            this.action = action;
            this.source = source == null ? "" : source;
        }

        Request copyForRetry() {
            Request r = new Request(action, source);
            r.tone = tone; r.prompt = prompt; r.target = target; r.lang = lang; r.useContext = useContext;
            r.retries = retries + 1;
            return r;
        }
    }

    /**
     * Tipo de fallo, para que el panel ofrezca la salida adecuada: TIMEOUT (tardo demasiado o el texto es
     * demasiado largo: acortarlo ayuda), MEMORY (no cabe en memoria: un modelo mas pequeno), SERVER (servidor
     * propio inalcanzable), MODEL (el archivo no sirve o fallo al cargar: elegir otro), NO_MODEL (no hay ninguno
     * instalado) y WEAK (el modelo respondio pero el resultado no vale: reintentar).
     */
    enum ErrorKind { TIMEOUT, MEMORY, SERVER, MODEL, NO_MODEL, WEAK }

    /** Avisos al panel. Siempre en el hilo principal. */
    interface Callback {
        void onPhase(CharSequence label);                        // "Cargando el modelo…", "Conectando…", "Leyendo… 45 %"
        void onPiece(String soFar, int tokens, long elapsedMs);  // texto acumulado (sin limpiar), ritmo
        void onDone(String text, boolean weak);                  // texto limpio; weak = parece mal, sugerir reintento
        void onCancelled(String partialText);                    // texto parcial limpio (puede ser vacio)
        void onError(CharSequence message);                      // compatibilidad: el runner llama siempre a la version con tipo
        /** Error con su tipo; por defecto delega en {@link #onError(CharSequence)} para los oyentes antiguos. */
        default void onError(ErrorKind kind, CharSequence message) { onError(message); }
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
            t.setPriority(Thread.NORM_PRIORITY);    // es el trabajador 0 del motor
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

    // Oyente actual y memoria de la generacion para reengancharlo; solo se tocan en el hilo principal
    private static final int END_NONE = 0, END_DONE = 1, END_CANCELLED = 2, END_ERROR = 3;
    private Callback listener;
    private Request request;            // peticion en curso (o la ultima lanzada)
    private CharSequence lastPhase;     // ultima fase avisada
    private String lastPiece;           // ultimo texto acumulado avisado
    private int lastTokens;
    private long lastElapsed;
    private int ending = END_NONE;      // final que nadie recibio (no habia oyente)
    private String endText;
    private boolean endWeak;
    private CharSequence endError;
    private ErrorKind endKind;

    AiRunner(Context c) { app = c.getApplicationContext(); }

    boolean isBusy() { return busy; }

    /** Posiciones de la cache KV reutilizadas por la ultima generacion local (0 si no hubo o fue remota); diagnostico y pruebas. */
    int lastReused() {
        LlmEngine e = engine;
        return e == null ? 0 : e.lastReused();
    }

    // ------------------------------------------------------------------ oyente

    /**
     * Engancha (o sustituye) al oyente. Si hay una generacion en vuelo le repite al momento la ultima fase y el
     * texto acumulado, y seguira recibiendo las piezas y el final; si la generacion termino sin que nadie
     * escuchara, le entrega ese final ahora. Devuelve true si habia algo que retomar. Hilo principal.
     */
    boolean attach(Callback cb) {
        listener = cb;
        if (cb == null) return false;
        if (busy) {
            if (lastPhase != null) cb.onPhase(lastPhase);
            if (lastPiece != null) cb.onPiece(lastPiece, lastTokens, lastElapsed);
            return true;
        }
        int kind = ending;
        ending = END_NONE;
        if (kind == END_DONE) cb.onDone(endText, endWeak);
        else if (kind == END_CANCELLED) cb.onCancelled(endText);
        else if (kind == END_ERROR) cb.onError(endKind, endError);
        return kind != END_NONE;
    }

    /**
     * Suelta al oyente si sigue siendo este (la vista se rehace): la generacion continua sin avisos y su final
     * espera al siguiente {@link #attach}. Un oyente que ya fue sustituido no toca nada. Hilo principal.
     */
    void detach(Callback cb) {
        if (listener == cb) listener = null;
    }

    /** Hay quien escucha ahora mismo. */
    boolean hasListener() { return listener != null; }

    /** Hay una generacion en vuelo, o un final sin entregar que recogera el siguiente oyente. */
    boolean hasPending() { return busy || ending != END_NONE; }

    /** Peticion en curso o ultima lanzada (null si nunca hubo). */
    Request request() { return request; }

    private void emitPhase(CharSequence label) {
        lastPhase = label;
        if (listener != null) listener.onPhase(label);
    }

    private void emitPiece(String soFar, int tokens, long elapsedMs) {
        lastPiece = soFar; lastTokens = tokens; lastElapsed = elapsedMs;
        if (listener != null) listener.onPiece(soFar, tokens, elapsedMs);
    }

    private void emitDone(String text, boolean weak) {
        if (listener != null) { listener.onDone(text, weak); return; }
        ending = END_DONE; endText = text; endWeak = weak;
    }

    private void emitCancelled(String partial) {
        if (listener != null) { listener.onCancelled(partial); return; }
        ending = END_CANCELLED; endText = partial;
    }

    private void emitError(ErrorKind kind, CharSequence message) {
        if (listener != null) { listener.onError(kind, message); return; }
        ending = END_ERROR; endError = message; endKind = kind;
    }

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
                System.gc();                       // libera el mapeo anterior antes de otro modelo
            }
        }
    }

    // ------------------------------------------------------------------ ejecucion

    /** Lanza la peticion con {@code cb} como oyente; cancela la anterior si seguia en marcha. Hilo principal. */
    void run(final Request req, final Callback cb) {
        cancel();
        final int id = seq.incrementAndGet();
        final AtomicBoolean flag = new AtomicBoolean(false);
        cancelled = flag;
        busy = true;
        listener = cb;
        request = req;
        lastPhase = null;
        lastPiece = null;
        ending = END_NONE;
        final boolean useRemote = isRemote();
        final Runnable watchdog = new Runnable() {
            @Override public void run() {
                if (seq.get() != id || flag.get()) return;
                flag.set(true);
                AiRemote r = remote;
                if (r != null) r.cancel();
                busy = false;
                emitError(ErrorKind.TIMEOUT, app.getString(R.string.ai_err_timeout));
            }
        };
        main.postDelayed(watchdog, useRemote ? REMOTE_TOTAL_MS : LOCAL_TOTAL_MS);
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                } catch (Throwable ignored) {
                }
                work(req, id, flag, useRemote, watchdog);
            }
        });
    }

    private boolean current(int id, AtomicBoolean flag) { return seq.get() == id && !flag.get(); }

    /** Temperatura de la segunda pasada de Traducir (traductor profesional en ingles). */
    static final float RETRY_TEMPERATURE = 0.45f;

    private void work(final Request req, final int id, final AtomicBoolean flag,
                      boolean useRemote, final Runnable watchdog) {
        // Idioma del mensaje: el del teclado (Redactor), el del destino (Traducir) o el detectado en el texto
        String textLang = req.action == Action.COMPOSE ? req.lang
                : req.action == Action.TRANSLATE ? promptLang(req) : detectLanguage(req.source, req.lang);
        String source = truncate(req.source, useRemote ? REMOTE_MAX_CHARS : LOCAL_MAX_CHARS);
        List<ChatMessage> messages = messages(req, source, textLang);
        LlmEngine.Options o = options(req.action, source.length(), req.retries);
        Attempt at = generate(req, id, flag, useRemote, messages, o, app.getString(R.string.ai_reading));
        if (at == null || seq.get() != id) return;      // ya se aviso del error, u otra peticion tomo el relevo
        if (flag.get()) { finishCancelled(id, at.raw, req, watchdog); return; }
        int untranslatedRes = 0;
        if (at.error == null && req.action == Action.TRANSLATE && untranslated(req, at.text(), source)) {
            // El modelo devolvio el original (o siguio en su idioma): una segunda pasada, mas caliente y con un
            // traductor profesional en ingles; si tampoco traduce, error propio en vez de un resultado inutil.
            // Mismo vigilante total que la primera pasada y misma cancelacion (id y bandera).
            restart(id, flag, app.getString(R.string.ia4_translating_again));
            LlmEngine.Options again = options(req.action, source.length(), req.retries);
            again.temperature = Math.max(RETRY_TEMPERATURE, o.temperature);
            at = generate(req, id, flag, useRemote, retryMessages(req, source), again,
                    app.getString(R.string.ia4_translating_again));
            if (at == null || seq.get() != id) return;
            if (flag.get()) { finishCancelled(id, at.raw, req, watchdog); return; }
            if (at.error == null && untranslated(req, at.text(), source)) untranslatedRes = R.string.ai_err_untranslated;
        }
        final String text = at.text();
        final CharSequence err = at.error;
        final ErrorKind kind = at.kind;
        final int weakRes = untranslatedRes;
        main.post(new Runnable() {
            @Override public void run() {
                main.removeCallbacks(watchdog);
                if (seq.get() != id) return;
                busy = false;
                if (err != null) { emitError(kind, err); return; }
                if (weakRes != 0) { emitError(ErrorKind.WEAK, app.getString(weakRes)); return; }
                Cleaned c = cleanChecked(req.action, text, req.source);
                if (c.errorRes != 0) { emitError(ErrorKind.WEAK, app.getString(c.errorRes)); return; }
                emitDone(c.text, c.weak);
            }
        });
    }

    /** Una pasada de generacion: texto crudo acumulado, respuesta completa del motor y error, si lo hubo. */
    private static final class Attempt {
        final StringBuilder raw = new StringBuilder();
        String result;
        CharSequence error;
        ErrorKind kind;
        /** Texto completo: la respuesta del motor, o lo acumulado si es mas largo (o no hubo respuesta). */
        String text() { return result != null && result.length() >= raw.length() ? result : raw.toString(); }
    }

    /**
     * Una pasada de generacion en el hilo de trabajo: manda los mensajes al motor (local o remoto), reparte fases y
     * trozos al hilo principal y devuelve lo generado con su error, si lo hubo; quien llama decide el final (cancelado,
     * error, segunda pasada o resultado). Devuelve null si ya se aviso del final (el modelo no cargo).
     * {@code readingLabel} es la fase mientras el motor local lee el prompt.
     */
    private Attempt generate(final Request req, final int id, final AtomicBoolean flag, boolean useRemote,
                             List<ChatMessage> messages, LlmEngine.Options o, final CharSequence readingLabel) {
        final Attempt at = new Attempt();
        final StringBuilder raw = at.raw;
        final long start = System.currentTimeMillis();
        final int[] tokens = {0};
        final long[] lastPost = {0};
        final boolean[] firstToken = {false};
        final Runnable firstWatch = new Runnable() {
            @Override public void run() {
                if (!current(id, flag) || firstToken[0]) return;
                flag.set(true);
                busy = false;
                emitError(ErrorKind.TIMEOUT, app.getString(R.string.ai_err_timeout));
            }
        };
        // Progreso real de la lectura del prompt (entre trozos del motor): "Leyendo el texto… 45 %". Mientras el
        // modelo avanza, el aviso de "tarda demasiado" vuelve a contar desde cero; cancelar tambien para la lectura.
        final int[] lastPct = {-1};
        LlmEngine.PromptListener progress = new LlmEngine.PromptListener() {
            @Override public boolean onPrompt(int done, int total) {
                if (!current(id, flag)) return false;
                final int pct = total <= 0 ? 100 : (int) Math.min(100L, 100L * done / total);
                if (pct == lastPct[0]) return true;
                lastPct[0] = pct;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!current(id, flag)) return;
                        if (!firstToken[0]) {                   // sigue leyendo: el aviso vuelve a contar desde ahora
                            main.removeCallbacks(firstWatch);
                            main.postDelayed(firstWatch, LOCAL_FIRST_MS);
                        }
                        if (lastPiece == null) emitPhase(app.getString(R.string.ia3_reading_pct, pct));   // aun no escribe
                    }
                });
                return true;
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
                        @Override public void run() { if (current(id, flag)) emitPiece(s, t, e); }
                    });
                }
                return true;
            }
        };
        String result = null;
        CharSequence error = null;
        ErrorKind errKind = null;
        try {
            if (useRemote) {
                phase(id, flag, app.getString(R.string.ai_connecting));
                AiRemote r = new AiRemote();
                remote = r;
                try {
                    result = r.chat(AiModels.remoteUrl(app), AiModels.remoteKey(app), AiModels.remoteModel(app),
                            messages, o, listener);
                } finally {
                    remote = null;
                }
            } else {
                LlmEngine e = ensureEngine(id, flag);
                if (e == null) return null;         // ya se aviso del error o se cancelo
                if (!current(id, flag)) return at;  // cancelado antes de empezar: quien llama avisa
                phase(id, flag, readingLabel);
                main.postDelayed(firstWatch, LOCAL_FIRST_MS);
                if (e.architecture() != null && e.architecture().toLowerCase(Locale.ROOT).contains("qwen3")) {
                    messages = noThink(messages);
                }
                result = e.generate(messages, o, listener, progress);
            }
        } catch (IOException ex) {
            if (!flag.get()) {
                String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                if (useRemote) {
                    errKind = ErrorKind.SERVER;
                    error = app.getString(R.string.ai_err_server, m);
                } else if (tooLong(m)) {
                    // El motor rechazo el texto por largo: no es culpa del archivo; acortar (o la seleccion) ayuda
                    errKind = m.contains("memoria") ? ErrorKind.MEMORY : ErrorKind.TIMEOUT;
                    error = m;
                } else {
                    errKind = ErrorKind.MODEL;
                    error = badModel(ex);
                }
            }
        } catch (OutOfMemoryError oom) {
            closeEngine();
            errKind = ErrorKind.MEMORY;
            error = app.getString(R.string.ai_err_memory);
        } catch (RuntimeException ex) {
            if (!flag.get()) {
                errKind = useRemote ? ErrorKind.SERVER : ErrorKind.MODEL;
                error = useRemote ? app.getString(R.string.ai_err_server, ex.getClass().getSimpleName()) : badModel(ex);
            }
        } finally {
            main.removeCallbacks(firstWatch);
        }
        at.result = result;
        at.error = error;
        at.kind = errKind;
        return at;
    }

    /** Segunda pasada: vacia en el panel el texto de la primera, avisa de la fase y deja ver otra vez el progreso de lectura. */
    private void restart(final int id, final AtomicBoolean flag, final CharSequence label) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!current(id, flag)) return;
                emitPiece("", 0, 0);
                lastPiece = null;
                emitPhase(label);
            }
        });
    }

    /** El motor rechazo el prompt por tamano (contexto o memoria), no por el archivo del modelo. */
    private static boolean tooLong(String engineMessage) {
        return engineMessage != null && engineMessage.startsWith("El texto es demasiado largo");
    }

    private void finishCancelled(final int id, StringBuilder raw, Request req, final Runnable watchdog) {
        final String partial = clean(req.action, raw.toString(), req.source);
        main.post(new Runnable() {
            @Override public void run() {
                main.removeCallbacks(watchdog);
                if (seq.get() != id) return;
                busy = false;
                emitCancelled(partial);
            }
        });
    }

    private void phase(final int id, final AtomicBoolean flag, final CharSequence label) {
        main.post(new Runnable() {
            @Override public void run() { if (current(id, flag)) emitPhase(label); }
        });
    }

    /** Carga el motor si hace falta (o si cambio el modelo elegido). Devuelve null tras avisar del error. */
    private LlmEngine ensureEngine(final int id, final AtomicBoolean flag) {
        File f = AiModels.selected(app);
        if (f == null) {
            fail(id, flag, ErrorKind.NO_MODEL, app.getString(R.string.ai_err_no_model));
            return null;
        }
        synchronized (engineLock) {
            if (engine != null && f.getAbsolutePath().equals(engineFile)) return engine;
            closeEngine();
            phase(id, flag, app.getString(R.string.ai_loading_model));
            try {
                int cpus = Runtime.getRuntime().availableProcessors();
                int threads = Math.max(1, Math.min(6, cpus - 2));   // reparto dinamico: mas hilos, mas rapido aun con nucleos lentos
                engine = LlmEngine.load(f, threads);
                engineFile = f.getAbsolutePath();
                return engine;
            } catch (IOException e) {
                boolean mapFail = e.getMessage() != null && e.getMessage().startsWith("MAPFAIL");
                if (mapFail) fail(id, flag, ErrorKind.MEMORY, app.getString(R.string.ai_err_memory));
                else fail(id, flag, ErrorKind.MODEL, badModel(e));
            } catch (OutOfMemoryError e) {
                fail(id, flag, ErrorKind.MEMORY, app.getString(R.string.ai_err_memory));
            } catch (RuntimeException e) {
                fail(id, flag, ErrorKind.MODEL, badModel(e));
            }
            return null;
        }
    }

    /**
     * "Este archivo no es un modelo GGUF admitido" mas el motivo concreto (el mensaje del motor o, si
     * fue un error de programacion, la clase y el mensaje): asi el usuario puede contarlo tal cual.
     */
    private CharSequence badModel(Throwable t) {
        return app.getString(R.string.ai_err_model_bad) + "\n" + describe(t);
    }

    /** Motivo legible de un fallo: mensaje del motor, o clase + mensaje si no lo trae. */
    static String describe(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (t instanceof IOException && m != null && !m.isEmpty()) return m;
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private void fail(final int id, final AtomicBoolean flag, final ErrorKind kind, final CharSequence msg) {
        main.post(new Runnable() {
            @Override public void run() {
                if (seq.get() != id) return;
                flag.set(true);
                busy = false;
                emitError(kind, msg);
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

    /** Idioma del texto solo si esta claro ("es", "en" o "de"); null si no se puede decir. */
    static String detectedLanguage(String text) {
        String a = detectLanguage(text, "es");
        return a.equals(detectLanguage(text, "en")) ? a : null;
    }

    /** Mismo texto sin contar mayusculas, espacios ni la puntuacion final. */
    static boolean sameText(String a, String b) {
        return a != null && b != null && !a.trim().isEmpty() && loose(a).equals(loose(b));
    }

    private static String loose(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim().replaceAll("[\\p{Punct}¡¿…«»“”‘’\\s]+$", "");
    }

    /**
     * Traducir: el modelo no tradujo. El resultado limpio es el original (sin contar mayusculas, espacios ni
     * puntuacion final) o sigue en el idioma del original cuando el destino es otro de los tres. Un texto que ya
     * estaba en el idioma destino no cuenta: no habia nada que traducir.
     */
    static boolean untranslated(Request req, String rawText, String source) {
        Cleaned c = cleanChecked(Action.TRANSLATE, rawText, source);
        if (c.errorRes != 0 || c.text.trim().isEmpty()) return false;
        String target = targetCode(req.target);
        String srcLang = detectedLanguage(source);
        if (target != null && target.equals(srcLang)) return false;
        if (sameText(c.text, source)) return true;
        return target != null && srcLang != null && srcLang.equals(detectedLanguage(c.text));
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

    /** Traducir: el mismo papel sin la regla de conservar el idioma del original. */
    static String translatorPrompt(String lang) {
        if ("en".equals(lang)) return "You are a translator. Reply only with the translation: no explanations, no greetings, "
                + "no quotes, no titles or notes.";
        if ("de".equals(lang)) return "Du bist ein Übersetzer. Antworte nur mit der Übersetzung: ohne Erklärungen, ohne "
                + "Begrüßung, ohne Anführungszeichen, ohne Titel oder Anmerkungen.";
        return "Eres un traductor. Respondes únicamente con la traducción: sin explicaciones, sin saludos, sin comillas, "
                + "sin títulos ni notas.";
    }

    /**
     * Segunda pasada de Traducir: traductor profesional, en ingles (el idioma en que mejor obedece un modelo pequeno),
     * que solo emite la traduccion.
     */
    static String retryTranslatorPrompt() {
        return "You are a professional translator. You output only the translation of the text you receive, written in "
                + "the requested language, and nothing else: no explanations, no greetings, no quotes, no notes, and "
                + "never the original text.";
    }

    /** Mensajes de la segunda pasada de Traducir: sistema e instruccion en ingles, mismo destino y mismo texto. */
    static List<ChatMessage> retryMessages(Request req, String source) {
        List<ChatMessage> out = new ArrayList<>(2);
        out.add(new ChatMessage("system", retryTranslatorPrompt()));
        out.add(new ChatMessage("user", userPrompt(req, source, "en")));
        return out;
    }

    /** Codigo del idioma destino si es uno de los tres de la interfaz ("es", "en", "de"); null si es otro o no hay. */
    static String targetCode(String target) {
        if (target == null) return null;
        String t = target.trim().toLowerCase(Locale.ROOT);
        return "es".equals(t) || "en".equals(t) || "de".equals(t) ? t : null;
    }

    /**
     * Idioma en que se escribe el mensaje de Traducir: el del destino cuando es uno de los tres (un modelo pequeno
     * responde mejor en el idioma en que se le habla); para otro idioma, el del teclado.
     */
    static String promptLang(Request req) {
        String code = targetCode(req.target);
        if (code != null) return code;
        return "en".equals(req.lang) ? "en" : "de".equals(req.lang) ? "de" : "es";
    }

    /** Mensajes (system + user) para la peticion; el texto fuente va al final, sin comillas. */
    static List<ChatMessage> messages(Request req, String source, String textLang) {
        List<ChatMessage> out = new ArrayList<>(2);
        out.add(new ChatMessage("system", req.action == Action.TRANSLATE ? translatorPrompt(textLang) : systemPrompt(textLang)));
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
            case TRANSLATE: {
                String code = targetCode(req.target);
                String other = sanitize(req.target, 40);
                if (code == null && other.isEmpty()) code = promptLang(req);
                String into = en ? ("es".equals(code) ? "Spanish" : "en".equals(code) ? "English" : "de".equals(code) ? "German" : other)
                        : de ? ("es".equals(code) ? "ins Spanische" : "en".equals(code) ? "ins Englische"
                        : "de".equals(code) ? "ins Deutsche" : "in die Sprache „" + other + "“")
                        : ("es".equals(code) ? "español" : "en".equals(code) ? "inglés" : "de".equals(code) ? "alemán" : other);
                // Ejemplo de una linea en el idioma del mensaje; solo con un destino de los tres (hay que saber escribirlo)
                String exSrc = "en".equals(code) ? "Buenos días" : "Good morning";
                String exOut = "en".equals(code) ? "Good morning" : "es".equals(code) ? "Buenos días" : "Guten Morgen";
                String example = code == null ? ""
                        : en ? " Example:\nText: " + exSrc + "\nTranslation: " + exOut
                        : de ? " Beispiel:\nText: " + exSrc + "\nÜbersetzung: " + exOut
                        : " Ejemplo:\nTexto: " + exSrc + "\nTraducción: " + exOut;
                return (en ? "Translate the following text into " + into + ". Keep the meaning, the tone and the line breaks. "
                        + "Reply only with the translation: no quotes, no comments, no explanations."
                        : de ? "Übersetze den folgenden Text " + into + ". Behalte Bedeutung, Ton und Zeilenumbrüche bei. "
                        + "Antworte nur mit der Übersetzung: ohne Anführungszeichen, ohne Kommentare, ohne Erklärungen."
                        : "Traduce el siguiente texto al " + into + ". Mantén el significado, el tono y los saltos de línea. "
                        + "Responde solo con la traducción: sin comillas, sin comentarios, sin explicaciones.") + example + tail;
            }
            case BULLETS:
                // Lista de verdad: titulo corto con dos puntos y un elemento por linea con "- "; el ejemplo fija el formato
                return (en ? "Turn the following text into a list. First line: a short title that sums up what the list is "
                        + "about, ending with a colon. Below it, one item per line, each starting with \"- \" (dash and space). "
                        + "Separate the items even if they come in a single sentence joined by commas or \"and\". "
                        + "Format example:\nWeekend plans:\n- Go to the movies\n- Dinner with the family\n"
                        + "Write nothing before or after the list."
                        : de ? "Wandle den folgenden Text in eine Liste um. Erste Zeile: ein kurzer Titel, der zusammenfasst, "
                        + "worum es in der Liste geht, mit Doppelpunkt am Ende. Darunter ein Element pro Zeile, jedes beginnt "
                        + "mit \"- \" (Bindestrich und Leerzeichen). Trenne die Elemente auch dann, wenn sie in einem einzigen "
                        + "Satz durch Kommas oder \"und\" verbunden sind. Formatbeispiel:\nPläne fürs Wochenende:\n"
                        + "- Ins Kino gehen\n- Abendessen mit der Familie\nSchreibe nichts vor oder nach der Liste."
                        : "Convierte el siguiente texto en una lista. Primera línea: un título corto que resuma de qué va la "
                        + "lista, terminado en dos puntos. Debajo, un elemento por línea, cada uno empezando con \"- \" (guion "
                        + "y espacio). Separa los elementos aunque vengan en una sola frase unidos por comas o por \"y\". "
                        + "Ejemplo de formato:\nPlanes del fin de semana:\n- Ir al cine\n- Cenar con la familia\n"
                        + "No escribas nada antes ni después de la lista.") + tail;
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
                // Titulo, "- " y salto por elemento: algo mas de margen que el texto original
                o.temperature = 0.20f; o.repeatPenalty = 1.10f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.6) + 40, 400);
                break;
            case TABLE:
                o.temperature = 0.15f; o.repeatPenalty = 1.05f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.8) + 40, 400);
                break;
            case TRANSLATE:
                // Otro idioma puede ocupar bastantes mas tokens que el original (aleman, idiomas sin espacios)
                o.temperature = 0.20f; o.repeatPenalty = 1.05f;
                o.maxTokens = Math.min((int) Math.ceil(srcTokens * 1.8) + 32, 500);
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

    // (?u): mayusculas con acentos y dieresis ("Übersetzung:", "Traducción:") tambien cuentan
    private static final Pattern LABEL_LINE = Pattern.compile(
            "(?iu)^(texto|text|resultado|result|resumen|summary|tabla|table|lista|list|aquí (está|tienes)|here (is|are)|hier (ist|sind)|"
            + "corrected|corregido|korrigiert|zusammenfassung|tabelle|sure|claro|por supuesto|certainly|of course|respuesta|answer|antwort|"
            + "traducci[oó]n|translation|translated|übersetzung|übersetzt)[^\\n]{0,60}:$");
    private static final Pattern INLINE_PREFIX = Pattern.compile(
            "(?iu)^((texto corregido|corrected text|texto reescrito|rewritten text|resumen|summary|zusammenfassung|korrigierter text|"
            + "tabla|table|tabelle|respuesta|answer|antwort)|"
            + "(traducci[oó]n|translation|texto traducido|translated text|übersetzung|übersetzter text)( [^:\\n]{1,25})?)\\s*:\\s*");
    /** Parrafo o linea final de aclaracion que un traductor suele colar ("Nota: ...", "This translation keeps..."). */
    private static final Pattern TRAILING_NOTE = Pattern.compile(
            "(?isu)^[(\\[]?(nota|note|notes|hinweis|anmerkung|translation note|nota de traducción|please note|ten en cuenta|"
            + "bitte beachte|(this|the|my) translation (keeps|maintains|preserves|retains|captures|conveys|reflects|uses|is|has)|"
            + "(esta|la) traducción (mantiene|conserva|respeta|refleja|usa|es|está|tiene)|"
            + "(diese|die) übersetzung (behält|bewahrt|erhält|gibt|ist|hat|verwendet)|"
            + "i hope|let me know|feel free|if you (need|want|have|would)|espero que|si necesitas|si quieres|si deseas|avísame|"
            + "ich hoffe|lass es mich wissen|wenn du|bei fragen)\\b.*");
    /** Traducir: interjeccion sola en su linea ("Sure!", "¡Claro!") delante de la traduccion o de su etiqueta. */
    private static final Pattern CHATTY_LINE = Pattern.compile(
            "(?iu)^¡?(sure|certainly|of course|absolutely|claro|por supuesto|desde luego|gerne?|natürlich|klar|sicher)[!.,:]?[ \\t]*\\n");
    /**
     * Traducir: preambulo charlatan hasta sus dos puntos (o su salto de linea), "Sure! Here is the translation of "..."
     * into English:", saltando las comillas interiores: el original citado puede llevar dos puntos.
     */
    private static final Pattern CHATTY_PREAMBLE = Pattern.compile(
            "(?iu)^(?:¡?(?:sure|certainly|of course|absolutely|claro|por supuesto|desde luego|gerne?|natürlich|klar|sicher)[!.,:]?\\s*)?"
            + "(?:here(?:'s| is| are| you go)|this is|the following is|below is|(?:the|this) translation (?:of|into|to|is)|"
            + "aquí (?:tienes|está|están|va|te dejo)|esta es|a continuación|la traducción (?:de|del|al|es)|"
            + "hier (?:ist|sind|hast du)|das ist|die übersetzung (?:von|des|ins|lautet))\\b"
            + "(?:\"[^\"\\n]*\"|“[^”\\n]*”|«[^»\\n]*»|„[^“\\n]*“|[^:\\n\"“«„])*(?::\\s*|[ \\t]*\\n\\s*)");
    /**
     * Vinetas: linea inicial que es preambulo y no titulo ("Aquí tienes la lista:", "Lista de puntos:"); las demas lineas
     * terminadas en dos puntos ("Lista de compras de mamá:") son el titulo que se pide y se conservan.
     */
    private static final Pattern BULLETS_LABEL = Pattern.compile(
            "(?iu)^(aquí (está|tienes)|here (is|are)|hier (ist|sind)|sure|claro|por supuesto|certainly|of course|respuesta|answer|"
            + "antwort|resultado|result|lista de (puntos|viñetas|vinetas)|bullet(ed)? (list|points)|aufzählung|stichpunkte)[^\\n]{0,60}:$");
    /** Vineta o numero al principio de un elemento de lista. */
    private static final Pattern BULLET_MARK = Pattern.compile("^([-*·–—•▪◦●]|\\d{1,2}[.)])\\s*");
    /** Separadores de elementos dentro de una misma linea: vinetas sueltas, " - " y punto y coma. */
    private static final Pattern INLINE_SEP = Pattern.compile("\\s*[•·▪◦●]\\s*|\\s+[-–—]\\s+|;\\s*");
    /** Conjuncion que une los dos ultimos elementos de una enumeracion ("Pan y Tortillas"). */
    private static final Pattern LAST_CONJ = Pattern.compile("(?iu)^(.+?)\\s+(y|e|and|und|o|or|oder)\\s+(.+)$");
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
        // 3a. Traducir: interjeccion y preambulo charlatan ("Sure! Here is the translation of "..." into English:")
        if (a == Action.TRANSLATE) {
            s = CHATTY_LINE.matcher(s).replaceFirst("").trim();
            Matcher cm = CHATTY_PREAMBLE.matcher(s);
            if (cm.find() && cm.end() < s.length()) s = s.substring(cm.end()).trim();
        }
        // 3. Linea de etiqueta inicial y prefijos
        int nl = s.indexOf('\n');
        if (nl > 0 && nl <= 60) {
            String first = s.substring(0, nl).trim();
            Pattern label = a == Action.BULLETS ? BULLETS_LABEL : LABEL_LINE;    // Vinetas: el titulo pedido no es etiqueta
            if (label.matcher(first).matches()) s = s.substring(nl + 1).trim();
        }
        Matcher pm = INLINE_PREFIX.matcher(s);
        if (pm.find()) s = s.substring(pm.end()).trim();
        // 3b. Traducir: fuera los parrafos (o la linea) finales de aclaracion, y si queda un bloque entre comillas
        // seguido solo de una aclaracion ("Hello" This translation keeps...), nos quedamos con el (antes de las comillas)
        if (a == Action.TRANSLATE) {
            s = stripTrailingNotes(s);
            if (!quoted(source)) {
                String inner = quotedBlock(s);
                if (inner != null) s = stripTrailingNotes(inner);
            }
        }
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
                s = bulletList(s);
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
                // Ortografia, tonos y traduccion: longitud comparada con el original
                int src = source == null ? 0 : source.trim().length();
                if (s.isEmpty()) errorRes = R.string.ai_weak_result;
                else if (src > 0 && (s.length() > 3 * src + 20 || (a == Action.FIX && s.length() < 0.3 * src))) weak = true;
                else if (a == Action.TRANSLATE && src > 0 && sameText(s, source)) weak = true;   // no tradujo nada
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

    /** Quita del final los parrafos de aclaracion y, despues, la ultima linea si tambien lo es; siempre queda algo delante. */
    static String stripTrailingNotes(String s) {
        int cut = s.lastIndexOf("\n\n");
        while (cut > 0 && TRAILING_NOTE.matcher(s.substring(cut + 2).trim()).matches()) {
            s = s.substring(0, cut).trim();
            cut = s.lastIndexOf("\n\n");
        }
        int nl = s.lastIndexOf('\n');
        if (nl > 0 && TRAILING_NOTE.matcher(s.substring(nl + 1).trim()).matches()) s = s.substring(0, nl).trim();
        return s;
    }

    /**
     * Texto entre la comilla inicial y su pareja cuando lo que sigue es nada o solo una aclaracion ("Hello" This
     * translation keeps...); null si no empieza por comilla, la pareja no aparece o despues sigue mas texto normal.
     */
    static String quotedBlock(String s) {
        if (s == null || s.length() < 2) return null;
        char f = s.charAt(0);
        char close = f == '"' ? '"' : f == '“' ? '”' : f == '«' ? '»' : f == '„' ? '“' : f == '\'' ? '\'' : 0;
        if (close == 0) return null;
        int end = s.indexOf(close, 1);
        if (end < 0) return null;
        String rest = s.substring(end + 1).trim();
        if (!rest.isEmpty() && !TRAILING_NOTE.matcher(rest).matches()) return null;
        return s.substring(1, end).trim();
    }

    /**
     * Lista de verdad a partir de lo que devolvio el modelo: titulo (primera linea terminada en dos puntos, o sin vineta
     * seguida de lineas con vineta) y un elemento por linea con "- ". Si todo vino en una linea se parte por las vinetas,
     * los " - ", los ";" o las comas (y la conjuncion final). Sin lineas en blanco; si no hay titulo no se inventa.
     */
    static String bulletList(String s) {
        List<String> lines = new ArrayList<>();
        for (String line : s.split("\n")) {
            String t = line.replace("**", "").trim();     // negritas Markdown fuera
            if (!t.isEmpty()) lines.add(t);
        }
        if (lines.isEmpty()) return "";
        String title = null;
        List<String> items = new ArrayList<>();
        if (lines.size() == 1) {
            // Todo en una linea: "Lista de compras: Huevo, Leche, Pan y Tortillas" o "• Huevo • Leche • Pan"
            String only = lines.get(0);
            boolean separators = INLINE_SEP.matcher(only).find();
            if (!BULLET_MARK.matcher(only).find()) {
                int colon = only.indexOf(": ");
                if (colon > 0 && colon <= 60 && !INLINE_SEP.matcher(only.substring(0, colon)).find()) {
                    title = only.substring(0, colon + 1).trim();
                    if (BULLETS_LABEL.matcher(title).matches()) title = null;   // "Aquí tienes la lista:" no es titulo
                    only = only.substring(colon + 1).trim();
                } else if (only.endsWith(":") && !separators) {
                    return "";                           // solo un titulo, sin elementos
                }
            }
            items.addAll(splitItems(only, true));
        } else {
            String first = lines.get(0);
            boolean othersMarked = false;
            for (int i = 1; i < lines.size(); i++) if (BULLET_MARK.matcher(lines.get(i)).find()) { othersMarked = true; break; }
            int from = 0;
            if (first.endsWith(":") || (!BULLET_MARK.matcher(first).find() && othersMarked)) {
                String t = BULLET_MARK.matcher(first).replaceFirst("").replaceAll("[.:;,]+$", "").trim();
                if (!t.isEmpty()) { title = BULLETS_LABEL.matcher(t + ":").matches() ? null : t + ":"; from = 1; }
            }
            for (int i = from; i < lines.size(); i++) items.addAll(splitItems(lines.get(i), false));
        }
        if (items.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        if (title != null) b.append(title);
        for (String it : items) {
            if (b.length() > 0) b.append('\n');
            b.append("- ").append(it);
        }
        return b.toString();
    }

    /**
     * Elementos de una linea de lista: fuera la vineta inicial; partida por vinetas sueltas, " - " o ";", por "1. ... 2. ..."
     * si la linea venia numerada y, si era la unica linea, por comas (la conjuncion final separa los dos ultimos).
     */
    private static List<String> splitItems(String line, boolean single) {
        List<String> out = new ArrayList<>();
        String t = BULLET_MARK.matcher(line).replaceFirst("").trim();
        String[] parts;
        if (INLINE_SEP.matcher(t).find()) parts = INLINE_SEP.split(t);
        else if (line.matches("^\\d{1,2}[.)]\\s.*") && t.matches(".*\\s\\d{1,2}[.)]\\s.*")) parts = t.split("\\s+\\d{1,2}[.)]\\s+");
        else if (single && t.contains(", ")) parts = t.split(",\\s+");
        else parts = new String[]{t};
        boolean enumeration = single && parts.length > 1 && t.contains(", ");
        for (int i = 0; i < parts.length; i++) {
            String p = BULLET_MARK.matcher(parts[i].trim()).replaceFirst("").trim();
            if (single && parts.length > 1) p = p.replaceAll("\\.+$", "");     // el punto final de la frase enumerada
            p = p.replaceAll("[,;]+$", "").replaceAll("(?iu)\\s+(y|e|and|und)$", "").trim();
            if (p.isEmpty()) continue;
            Matcher m = enumeration && i == parts.length - 1 ? LAST_CONJ.matcher(p) : null;
            if (m != null && m.matches()) {
                out.add(m.group(1).trim());
                out.add(m.group(3).trim());
            } else {
                out.add(p);
            }
        }
        return out;
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
