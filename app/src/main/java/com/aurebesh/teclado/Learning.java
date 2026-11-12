package com.aurebesh.teclado;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Politica del diccionario personal: cuando una palabra desconocida pasa a ser del usuario. Todo ocurre en el telefono.
 *
 * <p>Una palabra desconocida se aprende solo tras {@link Prefs#learnUses} usos (2, 3 o 5) en <b>mensajes distintos</b>:
 * cuenta como mucho un uso por sesion de campo ({@link #beginSession}, de onStartInputView a onFinishInputView) o por
 * envio ({@link #newMessage}: intro o accion del editor). Los candidatos viven con su cuenta y la fecha del ultimo uso
 * en un archivo de texto propio ({@link #FILE}, lineas {@code palabra usos ultimaVezMs}) y se olvidan los que lleven
 * {@link #PURGE_MS} sin verse.
 *
 * <p>Requisitos de forma ({@link #wellFormed}): tres letras o mas, solo letras (con tildes, dieresis, ñ, ß) y apostrofo
 * interior; nada de digitos, mayusculas intercaladas ni siglas, ni tres letras iguales seguidas ("nooo", "www"). Ademas
 * la palabra no puede estar ya en el diccionario del idioma ni en el lexico Star Wars ({@link #eligible}).
 *
 * <p>Una palabra que parece una errata de otra conocida frecuente ({@link #looksLikeTypo}: a una edicion de una entrada
 * con rango menor que {@link #FREQUENT_RANK}, o la misma sin tilde) no se aprende por repeticion; solo cuenta cuando el
 * usuario rechaza a proposito la correccion ({@code force} en {@link #note}), y se aprende al instante solo desde el chip
 * de la palabra original, que esta fuera de esta clase.
 */
final class Learning {

    /** Archivo de texto propio con los candidatos: una linea por palabra, {@code palabra usos ultimaVezMs}. */
    static final String FILE = "aurebesh_pending";
    /** Un candidato que no se ve en este tiempo se olvida. */
    static final long PURGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** Rango por debajo del cual una palabra del idioma cuenta como frecuente para el filtro de erratas. */
    static final int FREQUENT_RANK = 15000;
    /** Candidatos que se conservan como mucho (los mas recientes). */
    static final int MAX_PENDING = 300;
    static final int MIN_LEN = 3;
    static final int MAX_LEN = 30;

    private final Context ctx;
    private LinkedHashMap<String, long[]> pending;        // palabra -> {usos, ultima vez (ms de pared)}, la mas antigua primero; null hasta leer
    private final HashSet<String> counted = new HashSet<>();   // palabras ya contadas en el mensaje actual
    private boolean dirty;                                // hay cambios sin volcar al archivo
    private long clock = -1;                              // reloj inyectado por las pruebas; -1 = reloj de pared

    Learning(Context c) {
        ctx = c.getApplicationContext();
    }

    /** Fija el reloj (ms de pared) para las pruebas; un valor negativo vuelve al reloj del sistema. */
    void setClock(long ms) { clock = ms; }

    private long now() { return clock >= 0 ? clock : System.currentTimeMillis(); }

    // ------------------------------------------------------------------ sesiones y mensajes

    /** Empieza una sesion de campo: lo contado hasta ahora ya no bloquea, y se olvidan los candidatos caducados. */
    void beginSession() {
        counted.clear();
        load();
        purge();
    }

    /** El usuario envio el mensaje (intro o accion): las mismas palabras pueden contar otra vez. */
    void newMessage() {
        counted.clear();
        save();
    }

    /** Termina la sesion de campo: lo pendiente va al archivo. */
    void endSession() {
        counted.clear();
        save();
    }

    // ------------------------------------------------------------------ politica

    /**
     * Forma admisible para aprenderse: de {@link #MIN_LEN} a {@link #MAX_LEN} letras (cualquier letra Unicode: tildes,
     * dieresis, ñ, ß), con apostrofos solo en el interior y nunca seguidos; sin digitos ni simbolos (direcciones,
     * correos y codigos quedan fuera), sin mayusculas despues de la primera letra ("iPhone", "NASA", "iOS") y sin tres
     * letras iguales seguidas ("holaaa", "www").
     */
    static boolean wellFormed(String w) {
        if (w == null) return false;
        int n = w.length();
        if (n < MIN_LEN || n > MAX_LEN) return false;
        int letters = 0;
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            if (c == '\'') {
                if (i == 0 || i == n - 1 || w.charAt(i - 1) == '\'') return false;
                continue;
            }
            if (!Character.isLetter(c)) return false;
            if (i > 0 && Character.isUpperCase(c)) return false;
            if (i >= 2 && w.charAt(i - 1) == c && w.charAt(i - 2) == c) return false;
            letters++;
        }
        return letters >= MIN_LEN;
    }

    /**
     * La palabra puede entrar en el diccionario personal: bien formada y desconocida para el diccionario del idioma, las
     * aprendidas y el lexico Star Wars (este cuente o no en la correccion). Sin diccionario cargado no se decide nada.
     */
    static boolean eligible(Dictionary dict, String typed) {
        if (dict == null || !wellFormed(typed)) return false;
        String lw = typed.toLowerCase(Locale.ROOT);
        return !dict.contains(lw) && !dict.inLexicon(lw);
    }

    /**
     * Parece una errata de una palabra conocida frecuente: a una edicion (letra de mas, de menos, cambiada o dos
     * invertidas) de una entrada con rango menor que {@link #FREQUENT_RANK}, o la misma palabra sin tilde (o con una de
     * mas). Esas no se aprenden por repeticion.
     */
    static boolean looksLikeTypo(Dictionary dict, String lw) {
        return dict != null && dict.nearFrequent(lw, FREQUENT_RANK);
    }

    /**
     * Anota un uso de una palabra desconocida cerrada tal cual y dice si con este alcanza los {@code uses} necesarios
     * (entonces se quita de los candidatos y el que llama la aprende). Como mucho un uso por sesion o mensaje. Con
     * {@code force} el usuario rechazo a proposito la correccion de esa palabra: cuenta aunque parezca una errata.
     */
    boolean note(Dictionary dict, String typed, int uses, boolean force) {
        if (!eligible(dict, typed)) return false;
        String lw = typed.toLowerCase(Locale.ROOT);
        if (!force && looksLikeTypo(dict, lw)) return false;
        if (!counted.add(lw)) return false;               // ya contada en este mensaje
        load();
        long[] e = pending.remove(lw);
        long count = (e == null ? 0 : e[0]) + 1;
        if (count >= Math.max(1, uses)) {
            dirty = true;
            save();
            return true;
        }
        pending.put(lw, new long[]{count, now()});       // la mas reciente al final
        while (pending.size() > MAX_PENDING) {
            Iterator<String> it = pending.keySet().iterator();
            it.next();
            it.remove();
        }
        dirty = true;
        return false;
    }

    /** Usos anotados de una palabra (0 si no es candidata). */
    int uses(String lw) {
        load();
        long[] e = pending.get(lw == null ? "" : lw.toLowerCase(Locale.ROOT));
        return e == null ? 0 : (int) e[0];
    }

    /** Candidatos pendientes (instantanea para la pantalla de datos o las pruebas). */
    int pendingCount() {
        load();
        return pending.size();
    }

    /** Borra todos los candidatos (junto con el diccionario personal, al borrar lo personal). */
    static void clear(Context c) {
        File f = file(c);
        if (f.exists()) f.delete();
    }

    // ------------------------------------------------------------------ archivo

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private void purge() {
        long limit = now() - PURGE_MS;
        Iterator<Map.Entry<String, long[]>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue()[1] < limit) {
                it.remove();
                dirty = true;
            }
        }
    }

    private void load() {
        if (pending != null) return;
        pending = new LinkedHashMap<>();
        File f = file(ctx);
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length != 3 || p[0].isEmpty()) continue;
                try {
                    pending.put(p[0], new long[]{Long.parseLong(p[1]), Long.parseLong(p[2])});
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) {
            // un archivo ilegible se trata como vacio
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { }
        }
    }

    /** Vuelca los candidatos si hay cambios: primero a un temporal y luego en su sitio, para no dejarlo a medias. */
    private void save() {
        if (!dirty || pending == null) return;
        File f = file(ctx);
        File tmp = new File(f.getPath() + ".tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            for (Map.Entry<String, long[]> e : pending.entrySet()) {
                w.write(e.getKey());
                w.write(' ');
                w.write(Long.toString(e.getValue()[0]));
                w.write(' ');
                w.write(Long.toString(e.getValue()[1]));
                w.write('\n');
            }
            w.close();
            w = null;
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            dirty = false;
        } catch (IOException ignored) {
            // sin sitio o sin permiso: se reintenta en el siguiente volcado
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) { }
        }
    }
}
