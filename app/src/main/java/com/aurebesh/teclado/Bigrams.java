package com.aurebesh.teclado;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tabla de bigramas por idioma (res/raw/bigrams_es.txt, bigrams_en.txt, bigrams_de.txt): para cada palabra
 * previa, sus seguidoras mas frecuentes. Da la prediccion de la siguiente palabra y un contexto al autocorrector
 * (una correccion que suele seguir a la palabra anterior recibe una bonificacion). Ademas aprende, solo en el
 * telefono y sin salir de las preferencias privadas de la app, las parejas de palabras que el propio usuario
 * escribe; esas parejas mandan sobre la tabla integrada.
 *
 * <p>Formato del recurso: una linea por palabra previa, {@code w1<TAB>w2 n w2 n ...} (n = veces), con
 * {@code <s>} como inicio de frase. Las lineas que empiezan por {@code #} son comentarios.
 */
final class Bigrams {

    /** Palabra previa que representa el inicio de frase. */
    static final String START = "<s>";
    /** Clave de preferencias con las parejas aprendidas: lineas "previa palabra veces". */
    static final String PREF_KEY = "user_bigrams";
    /** Parejas aprendidas que se conservan como mucho. */
    static final int MAX_USER_PAIRS = 2000;
    /** Bonificacion maxima que devuelve {@link #bonus}. */
    static final double MAX_BONUS = 2.5;

    private static final int MAX_WORD = 30;
    private static final double MIN_KNOWN_BONUS = 0.25;      // cualquier pareja conocida vale al menos esto
    private static final double LOG_RANGE = 3.0;             // una cuota de 1/1000 ya no bonifica mas que el minimo
    private static final double USER_BASE = 2.0;             // bonificacion de una pareja aprendida una vez
    private static final double USER_STEP = 0.1;             // y cuanto crece con cada repeticion

    private static final Map<String, Bigrams> CACHE = new HashMap<>();

    /** Parejas aprendidas, compartidas por todos los idiomas: "previa palabra" -> veces, la mas antigua primero. */
    private static final Object USER_LOCK = new Object();
    private static LinkedHashMap<String, Integer> userPairs;              // null hasta leer las preferencias
    private static final HashMap<String, HashMap<String, Integer>> userByPrev = new HashMap<>();

    /** Seguidoras de una palabra previa, de mas a menos frecuente. */
    private static final class Entry {
        final String[] words;
        final int[] counts;
        final int total;

        Entry(String[] words, int[] counts) {
            this.words = words;
            this.counts = counts;
            int t = 0;
            for (int n : counts) t += n;
            this.total = Math.max(1, t);
        }
    }

    final String lang;
    private final HashMap<String, Entry> table = new HashMap<>();

    /**
     * Tabla del idioma ("es", "en" o "de"; cualquier otro valor usa la de espanol). Se carga la primera vez
     * y se guarda en memoria; seguro desde varios hilos.
     */
    static synchronized Bigrams get(Context c, String lang) {
        String l = normalizeLang(lang);
        loadUser(c);
        Bigrams b = CACHE.get(l);
        if (b == null) {
            b = new Bigrams(c.getApplicationContext(), l);
            CACHE.put(l, b);
        }
        return b;
    }

    /** Tabla ya cargada, o null (para no bloquear el hilo principal con la carga). */
    static synchronized Bigrams cached(String lang) {
        return CACHE.get(normalizeLang(lang));
    }

    private static String normalizeLang(String lang) {
        if ("en".equals(lang) || "de".equals(lang)) return lang;
        return "es";
    }

    private Bigrams(Context c, String lang) {
        this.lang = lang;
        int res;
        if ("en".equals(lang)) res = R.raw.bigrams_en;
        else if ("de".equals(lang)) res = R.raw.bigrams_de;
        else res = R.raw.bigrams_es;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), "UTF-8"), 65536);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String prev = line.substring(0, tab);
                    String[] parts = line.substring(tab + 1).split(" ");
                    int n = parts.length / 2;
                    if (n == 0) continue;
                    String[] words = new String[n];
                    int[] counts = new int[n];
                    int k = 0;
                    for (int i = 0; i + 1 < parts.length; i += 2) {
                        try {
                            counts[k] = Integer.parseInt(parts[i + 1]);
                            words[k] = parts[i];
                            k++;
                        } catch (NumberFormatException ignored) {
                            // linea mal formada: se omite esa seguidora
                        }
                    }
                    if (k < n) {
                        String[] w2 = new String[k];
                        int[] c2 = new int[k];
                        System.arraycopy(words, 0, w2, 0, k);
                        System.arraycopy(counts, 0, c2, 0, k);
                        words = w2;
                        counts = c2;
                    }
                    if (k > 0) table.put(prev, new Entry(words, counts));
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // sin tabla: la prediccion simplemente no propone nada
        }
    }

    /** Numero de palabras previas de la tabla integrada. */
    int size() { return table.size(); }

    /** Clave de una pareja previa + palabra; null o vacio como previa es el inicio de frase. */
    private static String key(String prev) {
        if (prev == null) return START;
        String p = prev.trim().toLowerCase(Locale.ROOT);
        return p.isEmpty() ? START : p;
    }

    /**
     * Palabras que suelen seguir a {@code prev} (null o vacio = inicio de frase), mejores primero y como mucho
     * {@code max}. Primero las parejas aprendidas del usuario (de mas a menos repetida) y despues las de la
     * tabla integrada; nunca se proponen las palabras vetadas por el diccionario.
     */
    List<String> next(String prev, int max) {
        List<String> out = new ArrayList<>();
        if (max <= 0) return out;
        String p = key(prev);
        List<Map.Entry<String, Integer>> learned = userFollowers(p);
        if (learned != null) {
            Collections.sort(learned, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            for (Map.Entry<String, Integer> e : learned) {
                if (out.size() >= max) return out;
                if (!Dictionary.noSuggest(e.getKey())) out.add(e.getKey());
            }
        }
        Entry e = table.get(p);
        if (e != null) {
            for (String w : e.words) {
                if (out.size() >= max) break;
                if (!out.contains(w) && !Dictionary.noSuggest(w)) out.add(w);
            }
        }
        return out;
    }

    /**
     * Bonificacion de contexto para {@code candidate} tras {@code prev}: 0 si la pareja es desconocida y, si
     * no, un valor en [0, 2.5] proporcional al logaritmo de la cuota de la seguidora entre las seguidoras de
     * {@code prev} (la mas habitual se acerca a 2.5). Las parejas aprendidas valen 2.0 o mas. El autocorrector
     * la resta de su puntuacion (menor es mejor).
     */
    double bonus(String prev, String candidate) {
        if (candidate == null) return 0;
        String w = candidate.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return 0;
        String p = key(prev);
        double best = 0;
        Integer learned = userCount(p, w);
        if (learned != null) best = Math.min(MAX_BONUS, USER_BASE + USER_STEP * (learned - 1));
        Entry e = table.get(p);
        if (e != null) {
            for (int i = 0; i < e.words.length; i++) {
                if (e.words[i].equals(w)) {
                    double share = e.counts[i] / (double) e.total;
                    double v = MAX_BONUS * (1 + Math.log10(share) / LOG_RANGE);
                    v = Math.max(MIN_KNOWN_BONUS, Math.min(MAX_BONUS, v));
                    if (v > best) best = v;
                    break;
                }
            }
        }
        return best;
    }

    // ---- parejas aprendidas del usuario (privadas: solo en las preferencias de la app)

    /**
     * Aprende que {@code word} siguio a {@code prev} (null o vacio = inicio de frase). Se guarda en las
     * preferencias bajo {@link #PREF_KEY} como lineas "previa palabra veces", con un tope de
     * {@link #MAX_USER_PAIRS} parejas: al llenarse se descarta la menos repetida (y, en empate, la mas antigua).
     */
    void learn(Context c, String prev, String word) {
        if (word == null) return;
        String w = word.trim().toLowerCase(Locale.ROOT);
        String p = key(prev);
        if (w.isEmpty() || w.length() > MAX_WORD || p.length() > MAX_WORD || hasSpace(w) || hasSpace(p)) return;
        synchronized (USER_LOCK) {
            loadUser(c);
            String k = p + ' ' + w;
            Integer n = userPairs.remove(k);
            int count = n == null ? 1 : n + 1;
            userPairs.put(k, count);                      // la mas reciente queda al final
            HashMap<String, Integer> m = userByPrev.get(p);
            if (m == null) {
                m = new HashMap<>();
                userByPrev.put(p, m);
            }
            m.put(w, count);
            while (userPairs.size() > MAX_USER_PAIRS) {
                String victim = null;
                int min = Integer.MAX_VALUE;
                for (Map.Entry<String, Integer> e : userPairs.entrySet()) {
                    if (e.getValue() < min) {
                        min = e.getValue();
                        victim = e.getKey();
                    }
                }
                if (victim == null) break;
                userPairs.remove(victim);
                int sp = victim.indexOf(' ');
                HashMap<String, Integer> vm = userByPrev.get(victim.substring(0, sp));
                if (vm != null) {
                    vm.remove(victim.substring(sp + 1));
                    if (vm.isEmpty()) userByPrev.remove(victim.substring(0, sp));
                }
            }
            saveUser(c);
        }
    }

    /** Olvida todas las parejas aprendidas (memoria y preferencias). */
    static void forget(Context c) {
        synchronized (USER_LOCK) {
            userPairs = new LinkedHashMap<>();
            userByPrev.clear();
            Prefs.get(c).edit().remove(PREF_KEY).apply();
        }
    }

    /** Numero de parejas aprendidas guardadas. */
    static int userPairCount(Context c) {
        synchronized (USER_LOCK) {
            loadUser(c);
            return userPairs.size();
        }
    }

    private static boolean hasSpace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return true;
        return false;
    }

    private static void loadUser(Context c) {
        synchronized (USER_LOCK) {
            if (userPairs != null) return;
            userPairs = new LinkedHashMap<>();
            userByPrev.clear();
            SharedPreferences sp = Prefs.get(c);
            String raw = sp.getString(PREF_KEY, "");
            for (String line : raw.split("\n")) {
                String[] parts = line.split(" ");
                if (parts.length != 3) continue;
                int n;
                try {
                    n = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (n <= 0 || parts[0].isEmpty() || parts[1].isEmpty()) continue;
                userPairs.put(parts[0] + ' ' + parts[1], n);
                HashMap<String, Integer> m = userByPrev.get(parts[0]);
                if (m == null) {
                    m = new HashMap<>();
                    userByPrev.put(parts[0], m);
                }
                m.put(parts[1], n);
            }
        }
    }

    private static void saveUser(Context c) {
        StringBuilder b = new StringBuilder(userPairs.size() * 16);
        for (Map.Entry<String, Integer> e : userPairs.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append(' ').append(e.getValue());
        }
        Prefs.get(c).edit().putString(PREF_KEY, b.toString()).apply();
    }

    /** Copia de las seguidoras aprendidas de una previa, o null si no hay ninguna. */
    private static List<Map.Entry<String, Integer>> userFollowers(String prev) {
        synchronized (USER_LOCK) {
            if (userPairs == null) return null;
            HashMap<String, Integer> m = userByPrev.get(prev);
            if (m == null || m.isEmpty()) return null;
            return new ArrayList<>(m.entrySet());
        }
    }

    private static Integer userCount(String prev, String word) {
        synchronized (USER_LOCK) {
            if (userPairs == null) return null;
            HashMap<String, Integer> m = userByPrev.get(prev);
            return m == null ? null : m.get(word);
        }
    }
}
