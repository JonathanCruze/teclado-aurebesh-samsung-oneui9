package com.aurebesh.teclado;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import com.aurebesh.teclado.llm.LlmEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registro y almacenamiento de los modelos de IA locales (archivos GGUF) y de los ajustes del
 * motor. Los modelos viven en {@code <filesDir>/models/}; la descarga se hace con
 * HttpURLConnection en un hilo aparte, se puede cancelar y se reanuda con la cabecera Range si
 * quedo un archivo parcial. Un GGUF propio se importa copiando el flujo del documento elegido.
 *
 * <p>Preferencias (en {@link Prefs#get}): {@link #MODEL} (nombre de archivo del modelo elegido),
 * {@link #MODE} ("local" o "remote"), {@link #REMOTE_URL}, {@link #REMOTE_KEY} y
 * {@link #REMOTE_MODEL} del servidor propio, y {@link #LAST_TONE} (ultimo tono personalizado).
 */
final class AiModels {
    private AiModels() {}

    static final String MODEL = "ai_model";
    static final String MODE = "ai_mode";
    static final String REMOTE_URL = "ai_remote_url";
    static final String REMOTE_KEY = "ai_remote_key";
    static final String REMOTE_MODEL = "ai_remote_model";
    static final String LAST_TONE = "ai_last_tone";
    static final String REMOTE_WARNED = "ai_remote_warned";

    static final String MODE_LOCAL = "local";
    static final String MODE_REMOTE = "remote";

    /** Modelo predefinido: nombre visible, archivo, URL de descarga y tamano aproximado. */
    static final class Preset {
        final String id;          // identificador estable
        final String name;        // nombre completo visible
        final String shortName;   // nombre corto para la etiqueta del panel
        final String file;        // nombre del archivo en models/
        final String url;         // URL de descarga (Hugging Face, resolve/main)
        final long size;          // tamano aproximado en bytes (sirve para el progreso y el espacio)
        final int note;           // recurso: nota breve ("Mejor en espanol"...)
        final String license;
        final boolean recommended;

        Preset(String id, String name, String shortName, String file, String url, long sizeMb, int note,
               String license, boolean recommended) {
            this.id = id; this.name = name; this.shortName = shortName; this.file = file; this.url = url;
            this.size = sizeMb * 1024L * 1024L; this.note = note; this.license = license;
            this.recommended = recommended;
        }
    }

    static final Preset[] PRESETS = {
            new Preset("qwen2.5-0.5b-q4", "Qwen2.5 0.5B Instruct · Q4_0", "Qwen2.5 0.5B",
                    "qwen2.5-0.5b-instruct-q4_0.gguf",
                    "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
                    352, R.string.ai_note_spanish, "Apache-2.0", true),
            new Preset("smollm2-135m-q8", "SmolLM2 135M Instruct · Q8_0", "SmolLM2 135M",
                    "smollm2-135m-instruct-q8_0.gguf",
                    "https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct-GGUF/resolve/main/smollm2-135m-instruct-q8_0.gguf",
                    145, R.string.ai_note_fastest, "Apache-2.0", false),
            new Preset("qwen2.5-0.5b-q8", "Qwen2.5 0.5B Instruct · Q8_0", "Qwen2.5 0.5B Q8",
                    "qwen2.5-0.5b-instruct-q8_0.gguf",
                    "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
                    531, R.string.ai_note_quality, "Apache-2.0", false),
            new Preset("qwen3-0.6b-q8", "Qwen3 0.6B · Q8_0", "Qwen3 0.6B",
                    "Qwen3-0.6B-Q8_0.gguf",
                    "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
                    639, R.string.ai_note_newer, "Apache-2.0", false),
            new Preset("smollm2-360m-q8", "SmolLM2 360M Instruct · Q8_0", "SmolLM2 360M",
                    "smollm2-360m-instruct-q8_0.gguf",
                    "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
                    386, R.string.ai_note_english, "Apache-2.0", false),
    };

    static final Preset DEFAULT = PRESETS[0];

    /** Gama del telefono por RAM: 0 alta (12 GB o mas), 1 media (8 GB), 2 baja (6 GB o menos). */
    static int tier(Context c) {
        float g = DeviceInfo.totalRamGb(c);
        if (g <= 0f) return 1;
        if (g >= 10.5f) return 0;
        if (g >= 7f) return 1;
        return 2;
    }

    /** Modelo recomendado: Q4_0 en todas las gamas (el mas rapido en este motor); en gama alta cabe tambien Q8_0. */
    static Preset recommended(Context c) {
        return DEFAULT;
    }

    /** Alternativa de mas calidad que se sugiere solo en gama alta, o null. */
    static Preset qualityOption(Context c) {
        return tier(c) == 0 ? PRESETS[1] : null;
    }

    /** Fila de la lista de modelos: un predefinido (instalado o no) o un archivo importado. */
    static final class Entry {
        final Preset preset;      // null si es un archivo importado
        final String file;        // nombre del archivo en models/
        final String name;
        final long size;          // bytes reales si esta instalado, aproximados si no
        final boolean installed;
        final boolean selected;
        Entry(Preset preset, String file, String name, long size, boolean installed, boolean selected) {
            this.preset = preset; this.file = file; this.name = name; this.size = size;
            this.installed = installed; this.selected = selected;
        }
    }

    /** Avisos de una descarga o importacion. Se llaman siempre en el hilo principal. */
    interface Listener {
        void onProgress(long done, long total);     // total puede ser -1 si no se conoce
        void onFinished(File file);
        void onFailed(int messageRes, String detail); // detail puede ser null
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, Download> ACTIVE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ carpeta y seleccion

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static Preset preset(String id) {
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }

    static Preset presetByFile(String file) {
        for (Preset p : PRESETS) if (p.file.equals(file)) return p;
        return null;
    }

    /** Nombre del archivo del modelo elegido, o null. */
    static String selectedFile(Context c) { return Prefs.get(c).getString(MODEL, null); }

    /** Archivo del modelo elegido si existe; si no hay eleccion valida, el predefinido por defecto si esta instalado. */
    static File selected(Context c) {
        String name = selectedFile(c);
        if (name != null) {
            File f = new File(dir(c), name);
            if (f.isFile()) return f;
        }
        File def = new File(dir(c), DEFAULT.file);
        if (def.isFile()) return def;
        // cualquier modelo instalado
        File[] all = dir(c).listFiles();
        if (all != null) for (File f : all) if (f.isFile() && f.getName().endsWith(".gguf")) return f;
        return null;
    }

    static void select(Context c, String file) { Prefs.get(c).edit().putString(MODEL, file).apply(); }

    static String mode(Context c) {
        return MODE_REMOTE.equals(Prefs.get(c).getString(MODE, MODE_LOCAL)) ? MODE_REMOTE : MODE_LOCAL;
    }

    static void setMode(Context c, String mode) {
        Prefs.get(c).edit().putString(MODE, MODE_REMOTE.equals(mode) ? MODE_REMOTE : MODE_LOCAL).apply();
    }

    static boolean remote(Context c) { return MODE_REMOTE.equals(mode(c)); }
    static String remoteUrl(Context c) { return Prefs.get(c).getString(REMOTE_URL, ""); }
    static String remoteKey(Context c) { return Prefs.get(c).getString(REMOTE_KEY, ""); }
    static String remoteModel(Context c) { return Prefs.get(c).getString(REMOTE_MODEL, ""); }

    static void setRemote(Context c, String url, String key, String model) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        if (url != null) e.putString(REMOTE_URL, url.trim());
        if (key != null) e.putString(REMOTE_KEY, key.trim());
        if (model != null) e.putString(REMOTE_MODEL, model.trim());
        e.apply();
    }

    static boolean localAvailable(Context c) { return selected(c) != null; }
    static boolean remoteConfigured(Context c) { return !remoteUrl(c).trim().isEmpty(); }

    /** Hay con que trabajar: un modelo local instalado (modo local) o un servidor configurado (modo remoto). */
    static boolean available(Context c) {
        return remote(c) ? remoteConfigured(c) : localAvailable(c);
    }

    /** Nombre corto del motor en uso para la etiqueta del panel, o null si no hay ninguno. */
    static String engineShortName(Context c) {
        if (remote(c)) {
            String url = remoteUrl(c).trim();
            if (url.isEmpty()) return null;
            try {
                String host = new URL(AiRemote.normalize(url)).getHost();
                return host == null || host.isEmpty() ? url : host;
            } catch (Exception e) {
                return url;
            }
        }
        File f = selected(c);
        if (f == null) return null;
        Preset p = presetByFile(f.getName());
        return p != null ? p.shortName : stripExt(f.getName());
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Lista: los predefinidos en orden y despues los archivos importados por nombre. */
    static List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        File d = dir(c);
        String sel = selectedFile(c);
        File selFile = selected(c);
        String selName = selFile == null ? sel : selFile.getName();
        for (Preset p : PRESETS) {
            File f = new File(d, p.file);
            boolean inst = f.isFile();
            out.add(new Entry(p, p.file, p.name, inst ? f.length() : p.size, inst, inst && p.file.equals(selName)));
        }
        File[] all = d.listFiles();
        if (all != null) {
            java.util.Arrays.sort(all);
            for (File f : all) {
                if (!f.isFile() || !f.getName().endsWith(".gguf") || presetByFile(f.getName()) != null) continue;
                out.add(new Entry(null, f.getName(), stripExt(f.getName()), f.length(), true, f.getName().equals(selName)));
            }
        }
        return out;
    }

    /** Borra un modelo instalado; si era el elegido, deja la eleccion vacia. */
    static boolean delete(Context c, String file) {
        Download d = ACTIVE.get(file);
        if (d != null) d.cancel();
        File f = new File(dir(c), file);
        new File(dir(c), file + ".part").delete();
        boolean ok = !f.exists() || f.delete();
        if (file.equals(selectedFile(c))) Prefs.get(c).edit().remove(MODEL).apply();
        return ok;
    }

    /** "352 MB", "1,2 GB"... */
    static String sizeLabel(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1000L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format(Locale.getDefault(), "%d MB", Math.round(bytes / (1024.0 * 1024)));
    }

    // ------------------------------------------------------------------ descarga

    /** Descarga en curso de un predefinido (clave: nombre de archivo) o null. */
    static Download active(String file) { return ACTIVE.get(file); }

    static boolean anyActive() { return !ACTIVE.isEmpty(); }

    /**
     * Empieza (o reanuda) la descarga de un predefinido. Si ya hay una en curso para ese archivo,
     * la devuelve con el nuevo oyente.
     */
    static Download download(Context c, Preset p, Listener l) {
        Download d = ACTIVE.get(p.file);
        if (d != null) {
            d.listener = l;
            return d;
        }
        d = new Download(c.getApplicationContext(), p, l);
        ACTIVE.put(p.file, d);
        d.start();
        return d;
    }

    /** Descarga con reanudacion, cancelacion y verificacion del archivo. */
    static final class Download {
        final Preset preset;
        volatile Listener listener;
        volatile long done, total = -1;
        private final Context app;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection conn;
        private long lastPost;

        Download(Context app, Preset p, Listener l) { this.app = app; this.preset = p; this.listener = l; }

        boolean isCancelled() { return cancelled.get(); }

        int percent() { return total <= 0 ? 0 : (int) Math.min(100, done * 100 / total); }

        void cancel() {
            cancelled.set(true);
            final HttpURLConnection h = conn;
            if (h != null) new Thread(new Runnable() {
                @Override public void run() { try { h.disconnect(); } catch (Exception ignored) { } }
            }).start();
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { work(); }
            }, "ai-download");
            t.setDaemon(true);
            t.start();
        }

        private void work() {
            File dir = dir(app);
            File part = new File(dir, preset.file + ".part");
            File dest = new File(dir, preset.file);
            int failRes = R.string.ai_download_failed;
            String detail = null;
            try {
                long have = part.isFile() ? part.length() : 0;
                if (dir.getUsableSpace() < Math.max(0, preset.size - have) + 32L * 1024 * 1024) {
                    fail(R.string.ai_no_space, sizeLabel(preset.size - have));
                    return;
                }
                HttpURLConnection h = (HttpURLConnection) new URL(preset.url).openConnection();
                h.setConnectTimeout(20000);
                h.setReadTimeout(30000);
                h.setInstanceFollowRedirects(true);
                h.setRequestProperty("User-Agent", "TecladoAurebesh");
                if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
                conn = h;
                int code = h.getResponseCode();
                boolean append;
                if (code == 206 && have > 0) {
                    append = true;
                } else if (code == 200) {
                    append = false;
                    have = 0;
                } else {
                    detail = "HTTP " + code;
                    throw new IOException(detail);
                }
                long len = h.getContentLengthLong();
                total = len >= 0 ? have + len : (preset.size > 0 ? preset.size : -1);
                done = have;
                InputStream in = h.getInputStream();
                OutputStream out = new FileOutputStream(part, append);
                byte[] buf = new byte[64 * 1024];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled.get()) break;
                        out.write(buf, 0, n);
                        done += n;
                        progress(false);
                    }
                } finally {
                    try { out.close(); } catch (IOException ignored) { }
                    try { in.close(); } catch (IOException ignored) { }
                    h.disconnect();
                    conn = null;
                }
                if (cancelled.get()) {
                    ACTIVE.remove(preset.file);
                    return;         // el .part se conserva para reanudar
                }
                if (len >= 0 && done != have + len) throw new IOException("short read");
                progress(true);
                if (!LlmEngine.looksLikeGguf(part)) {
                    part.delete();
                    fail(R.string.ai_verify_failed, null);
                    return;
                }
                dest.delete();
                if (!part.renameTo(dest)) throw new IOException("rename");
                // Si no habia eleccion, el primer modelo instalado pasa a ser el elegido.
                if (selectedFile(app) == null) select(app, dest.getName());
                final File f = dest;
                ACTIVE.remove(preset.file);
                MAIN.post(new Runnable() {
                    @Override public void run() { Listener l = listener; if (l != null) l.onFinished(f); }
                });
            } catch (IOException e) {
                if (cancelled.get()) { ACTIVE.remove(preset.file); return; }
                fail(failRes, detail != null ? detail : e.getClass().getSimpleName());
            }
        }

        private void progress(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastPost < 250) return;
            lastPost = now;
            final long d = done, t = total;
            MAIN.post(new Runnable() {
                @Override public void run() { Listener l = listener; if (l != null) l.onProgress(d, t); }
            });
        }

        private void fail(final int res, final String detail) {
            ACTIVE.remove(preset.file);
            MAIN.post(new Runnable() {
                @Override public void run() { Listener l = listener; if (l != null) l.onFailed(res, detail); }
            });
        }
    }

    // ------------------------------------------------------------------ importar

    /**
     * Copia el documento elegido (ACTION_OPEN_DOCUMENT) a models/ con su nombre visible, comprueba
     * la cabecera GGUF y que el motor lo acepte, y lo deja elegido. Avisa por el oyente.
     */
    static void importGguf(final Context c, final Uri uri, final Listener l) {
        final Context app = c.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { doImport(app, uri, l); }
        }, "ai-import");
        t.setDaemon(true);
        t.start();
    }

    private static void doImport(Context app, Uri uri, final Listener l) {
        ContentResolver cr = app.getContentResolver();
        String name = displayName(cr, uri);
        if (name == null || name.trim().isEmpty()) name = "modelo.gguf";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) name += ".gguf";
        long size = -1;
        Cursor cur = null;
        try {
            cur = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) size = cur.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        File dir = dir(app);
        File part = new File(dir, name + ".part");
        final File dest = new File(dir, name);
        try {
            if (size > 0 && dir.getUsableSpace() < size + 32L * 1024 * 1024) {
                post(l, R.string.ai_no_space, sizeLabel(size));
                return;
            }
            InputStream in = cr.openInputStream(uri);
            if (in == null) throw new IOException("open");
            OutputStream out = new FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            long done = 0, last = 0;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 250) {
                        last = now;
                        final long d = done, t = size;
                        MAIN.post(new Runnable() {
                            @Override public void run() { if (l != null) l.onProgress(d, t); }
                        });
                    }
                }
            } finally {
                try { out.close(); } catch (IOException ignored) { }
                try { in.close(); } catch (IOException ignored) { }
            }
            if (!LlmEngine.looksLikeGguf(part)) {
                part.delete();
                post(l, R.string.ai_err_model_bad, null);
                return;
            }
            // El motor valida arquitectura y cuantizacion al cargar (solo lee cabecera y metadatos).
            try {
                LlmEngine e = LlmEngine.load(part, 1);
                e.close();
            } catch (IOException bad) {
                part.delete();
                post(l, R.string.ai_err_model_bad, bad.getMessage());
                return;
            }
            dest.delete();
            if (!part.renameTo(dest)) throw new IOException("rename");
            select(app, dest.getName());
            MAIN.post(new Runnable() {
                @Override public void run() { if (l != null) l.onFinished(dest); }
            });
        } catch (IOException e) {
            part.delete();
            post(l, R.string.ai_import_failed, e.getMessage());
        }
    }

    private static void post(final Listener l, final int res, final String detail) {
        MAIN.post(new Runnable() {
            @Override public void run() { if (l != null) l.onFailed(res, detail); }
        });
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        Cursor cur = null;
        try {
            cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String last = uri.getLastPathSegment();
        if (last != null) {
            int slash = last.lastIndexOf('/');
            return slash >= 0 ? last.substring(slash + 1) : last;
        }
        return null;
    }
}
