package com.aurebesh.teclado;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.HashSet;
import java.util.Set;

/**
 * Detecta capturas de pantalla nuevas para ofrecer el chip "Pegar" del teclado.
 *
 * <p>Mientras esta activo (entre {@link #start()} y {@link #stop()}) escucha los cambios de
 * {@code MediaStore.Images} con un {@link ContentObserver} en un hilo propio; cada aviso se agrupa
 * (400 ms) y provoca una sola consulta: la imagen mas reciente anadida en los ultimos 90 s cuya ruta
 * contenga la carpeta "Screenshots" (Pixel: Pictures/Screenshots, Samsung: DCIM/Screenshots). Si es
 * nueva, se copia al almacenamiento privado de la app con {@link ClipHistory#addImage} y se avisa al
 * {@link Listener} en el hilo principal.</p>
 *
 * <p>Todo ocurre en el telefono: solo se lee la carpeta de capturas y nada sale del dispositivo. Sin
 * permiso de fotos (o si el sistema lo revoca en caliente) la clase no hace nada.</p>
 *
 * <p>Los nombres de permisos y columnas de API 29+ se escriben como literales para que compile con
 * cualquier android.jar de API 28 o superior.</p>
 */
final class ScreenshotWatcher {

    /** Receptor de capturas nuevas; se llama siempre en el hilo principal. */
    interface Listener {
        void onScreenshot(ClipHistory.Entry entry);
    }

    /** Ventana por defecto: una captura cuenta como "reciente" si se anadio hace menos de esto. */
    static final long RECENT_MS = 90_000L;
    /** Agrupa los avisos del MediaStore (insert con is_pending=1 + update a 0) en una sola consulta. */
    static final long DEBOUNCE_MS = 400L;
    /** Reintento unico si al consultar la fila aun estaba pendiente (is_pending=1 se oculta en API 29+). */
    static final long RETRY_MS = 1500L;
    /** Codigo que la pantalla de ajustes usa en requestPermissions(). */
    static final int REQUEST_CODE = 77;

    static final String PERM_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    static final String PERM_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    static final String PERM_LEGACY = "android.permission.READ_EXTERNAL_STORAGE";

    /** Clave en Prefs del ultimo _id de MediaStore copiado, para no repetir la misma captura. */
    static final String PREF_LAST_ID = "shots_last_id";

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final long MAX_BYTES = 8L * 1024 * 1024;   // coherente con ClipHistory.addImage

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Long> seen = new HashSet<>();

    private HandlerThread thread;
    private Handler bg;
    private ContentObserver observer;
    private boolean registered;
    private volatile long lastTakenAt;

    private final Runnable check = new Runnable() {
        @Override public void run() { deliver(RECENT_MS, true); }
    };
    private final Runnable retry = new Runnable() {
        @Override public void run() { deliver(RECENT_MS, false); }
    };

    ScreenshotWatcher(Context c, Listener l) {
        this.context = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        this.listener = l;
    }

    // ------------------------------------------------------------------ permisos

    /**
     * Nivel de acceso a las fotos: 2 = todas, 1 = parcial ("Seleccionar fotos" de Android 14+,
     * inutil para detectar capturas nuevas), 0 = ninguno.
     */
    static int mediaAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted(c, PERM_IMAGES)) return 2;
            if (Build.VERSION.SDK_INT >= 34 && granted(c, PERM_VISUAL_USER_SELECTED)) return 1;
            return 0;
        }
        return granted(c, PERM_LEGACY) ? 2 : 0;
    }

    /**
     * true si la app puede leer imagenes del MediaStore: READ_MEDIA_IMAGES en API 33+ (o el acceso
     * parcial READ_MEDIA_VISUAL_USER_SELECTED de Android 14), READ_EXTERNAL_STORAGE antes.
     */
    static boolean hasPermission(Context c) {
        return mediaAccess(c) > 0;
    }

    /** Permisos que debe pedir la Activity; en Android 14+ ambos a la vez para que el dialogo ofrezca "Permitir todo". */
    static String[] permissionsToRequest() {
        if (Build.VERSION.SDK_INT >= 34) return new String[]{PERM_IMAGES, PERM_VISUAL_USER_SELECTED};
        if (Build.VERSION.SDK_INT >= 33) return new String[]{PERM_IMAGES};
        return new String[]{PERM_LEGACY};
    }

    private static boolean granted(Context c, String perm) {
        try {
            return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    /**
     * Empieza a escuchar el MediaStore. No hace nada si la opcion esta apagada en Prefs o falta el
     * permiso. Llamar con el teclado en pantalla (InputMethodService.onWindowShown).
     */
    void start() {
        if (registered) return;
        if (!Prefs.screenshots(context) || !hasPermission(context)) return;
        if (thread == null) {
            thread = new HandlerThread("aurebesh-shots");
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        observer = new ContentObserver(bg) {
            @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
            @Override public void onChange(boolean selfChange, Uri uri) {
                Handler h = bg;
                if (h == null) return;
                h.removeCallbacks(check);
                h.removeCallbacks(retry);
                h.postDelayed(check, DEBOUNCE_MS);
            }
        };
        try {
            context.getContentResolver().registerContentObserver(IMAGES, true, observer);
            registered = true;
        } catch (RuntimeException e) {
            observer = null;
        }
    }

    /** Deja de escuchar y libera el hilo. Llamar en onWindowHidden y onDestroy. */
    void stop() {
        if (bg != null) {
            bg.removeCallbacks(check);
            bg.removeCallbacks(retry);
        }
        if (registered && observer != null) {
            try { context.getContentResolver().unregisterContentObserver(observer); } catch (RuntimeException ignored) {}
        }
        registered = false;
        observer = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            bg = null;
        }
    }

    boolean isRunning() { return registered; }

    /** Reloj de pared (ms) de la ultima captura entregada (segun date_added), o 0 si ninguna. */
    long lastTakenAt() { return lastTakenAt; }

    // ------------------------------------------------------------------ consultas

    /**
     * Consulta unica: devuelve la captura de los ultimos {@code maxAgeMs} ms si existe y aun no se
     * habia entregado (en esta sesion o en una anterior), ya copiada a files/clips/; null en caso
     * contrario, sin permiso o si el MediaStore no responde. Hace E/S de archivo: llamar desde un
     * hilo de fondo (por ejemplo el worker del IME al abrir el teclado) y pasar el resultado al
     * hilo principal.
     */
    ClipHistory.Entry checkRecent(long maxAgeMs) {
        if (!Prefs.screenshots(context) || !hasPermission(context)) return null;
        long[] found = queryNewest(context, maxAgeMs);
        if (found == null) return null;
        return copyIfNew(found);
    }

    /** Consulta en el hilo de fondo y avisa al listener en el principal. */
    private void deliver(long maxAgeMs, boolean allowRetry) {
        if (!hasPermission(context)) return;
        long[] found = queryNewest(context, maxAgeMs);
        if (found == null) {
            if (allowRetry && bg != null) bg.postDelayed(retry, RETRY_MS);   // aun is_pending=1
            return;
        }
        final ClipHistory.Entry e = copyIfNew(found);
        if (e == null || listener == null) return;
        main.post(new Runnable() {
            @Override public void run() { listener.onScreenshot(e); }
        });
    }

    /** Copia la imagen {_id, date_added} si no se habia visto; marca el id como visto. */
    private ClipHistory.Entry copyIfNew(long[] found) {
        long id = found[0];
        synchronized (seen) {
            if (seen.contains(id) || id == Prefs.get(context).getLong(PREF_LAST_ID, -1L)) return null;
            seen.add(id);
        }
        Uri item = ContentUris.withAppendedId(IMAGES, id);
        String mime = null;
        try { mime = context.getContentResolver().getType(item); } catch (RuntimeException ignored) {}
        if (mime == null || !mime.startsWith("image/")) mime = "image/png";
        ClipHistory.Entry e;
        try {
            e = ClipHistory.addImage(context, item, mime);
        } catch (RuntimeException ex) {
            e = null;                                     // permiso revocado o proveedor caido
        }
        if (e == null) return null;                       // > 8 MB o ilegible
        Prefs.get(context).edit().putLong(PREF_LAST_ID, id).apply();
        lastTakenAt = found[1] * 1000L;
        return e;
    }

    /**
     * {_id, date_added} de la captura mas reciente anadida en los ultimos {@code maxAgeMs} ms cuya
     * ruta contenga "Screenshots" (LIKE de SQLite: sin distinguir mayusculas), o null si no hay,
     * pesa mas de 8 MB o la consulta falla.
     */
    static long[] queryNewest(Context c, long maxAgeMs) {
        boolean q = Build.VERSION.SDK_INT >= 29;
        String pathCol = q ? "relative_path" : "_data";    // MediaColumns.RELATIVE_PATH (29) / DATA
        String[] proj = {"_id", "date_added", "_size"};
        long since = System.currentTimeMillis() / 1000L - Math.max(1L, maxAgeMs / 1000L);   // date_added en segundos
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                pathCol + " LIKE ? AND date_added >= ? AND mime_type LIKE 'image/%'");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{q ? "%Screenshots/%" : "%/Screenshots/%", String.valueOf(since)});
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC, _id DESC");
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);   // nunca "LIMIT 1" en el sortOrder: MediaProvider lo rechaza
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(IMAGES, proj, args, null);
            if (cur == null || !cur.moveToFirst()) return null;
            long size = cur.isNull(2) ? 0 : cur.getLong(2);
            if (size > MAX_BYTES) return null;
            return new long[]{cur.getLong(0), cur.getLong(1)};
        } catch (RuntimeException e) {
            return null;                                   // SecurityException, columna desconocida, proveedor sin respuesta
        } finally {
            if (cur != null) {
                try { cur.close(); } catch (RuntimeException ignored) {}
            }
        }
    }
}
