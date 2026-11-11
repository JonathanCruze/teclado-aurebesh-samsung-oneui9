package com.aurebesh.teclado;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Diccionario de frecuencia por idioma (res/raw/words_es.txt, words_en.txt y words_de.txt: una palabra
 * por linea, de mas a menos frecuente). Da autocorreccion a una edicion, restauracion de tildes y completado
 * de palabras. Todo ocurre en el telefono, sin internet.
 * <p>
 * Lleva ademas el lexico Star Wars (res/raw/starwars_words.txt, una entrada por linea en su forma canonica:
 * personajes, especies, planetas, naves y conceptos, con sus variantes en espanol y aleman; los nombres comunes de
 * la saga van en minuscula, como "bantha" o "lightsaber"), compartido por los tres idiomas y activable con
 * {@link #setStarWars}: sus entradas se aceptan como conocidas, se completan y se corrige hacia ellas como si fueran
 * palabras aprendidas; {@link #canonical} devuelve la caja propia de cada una. Las entradas que son ademas palabras
 * normales del idioma ("orden", "fuerza", "ghost") siguen las reglas de su diccionario, y las que en minusculas
 * serian un error evidente de una palabra frecuente ("rael" -> "real", "hoth" -> "both") solo cuentan escritas con
 * su mayuscula, igual que las de menos de cuatro letras.
 */
final class Dictionary {

    /** Resultado para la palabra que se esta escribiendo. */
    static final class Result {
        final List<String> suggestions = new ArrayList<>();   // mejores primero, en minusculas
        String correction;      // se aplica al teclear un separador; null si no procede
        boolean known;          // la palabra escrita existe tal cual
    }

    private static final Map<String, Dictionary> CACHE = new java.util.concurrent.ConcurrentHashMap<>();   // lectura sin bloqueo
    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_PREFIX_SCAN = 4000;
    private static final int MAX_LEN = 24;
    private static final double ED2_CUTOFF = 2.6;      // coste maximo de la busqueda a dos ediciones
    /**
     * Puntuacion base de una palabra aprendida del usuario: como una de frecuencia media (rango ~1500), asi
     * gana a los candidatos raros del diccionario pero no a las palabras muy frecuentes.
     */
    static final double PERSONAL_SCORE = Math.log(1500);
    private static final double MAX_EDIT = 2.5;        // coste maximo de una sola edicion (letra cambiada por otra cualquiera)
    /**
     * Longitud desde la que una entrada del lexico Star Wars cuenta siempre. Las mas cortas (Rey, Poe, Gon, AT) solo
     * se reconocen escritas con su caja canonica y nunca sirven de correccion: "gon" o "poe" en minusculas siguen
     * siendo los errores de "con" y "por" de toda la vida.
     */
    private static final int SW_MIN = 4;
    /**
     * Puntuacion de una entrada larga del lexico Star Wars (mas de cinco letras): algo mejor que una aprendida
     * ({@link #PERSONAL_SCORE}), porque parecerse a "palpatine" o "coruscant" rara vez es casualidad.
     */
    static final double SW_LONG_SCORE = Math.log(800);
    /** Coste maximo hacia una entrada larga del lexico: dos errores caros ("palatino" -> "palpatine"). */
    private static final double SW_ED2_CUTOFF = 4.0;
    /**
     * Puntuacion (ln del rango mas el coste de la edicion) por debajo de la cual una entrada del lexico escrita en
     * minusculas se considera un error evidente de una palabra del idioma y no se reconoce sin su mayuscula: una
     * tecla vecina o dos letras cambiadas de una de las dos mil mas frecuentes, o errores mas caros de otras aun mas
     * frecuentes ("rael" -> "real", "hoth" -> "both", "kamino" -> "camino"). Vease {@link #swCaseOnly}.
     */
    private static final double SW_CASE_LIMIT = Math.log(2000) + 0.6;
    /**
     * Entradas del lexico que las listas de frecuencia tambien traen (salen de subtitulos de peliculas, y las de la
     * saga estan entre ellas) pero que no son palabras normales de ningun idioma del teclado: se tratan como las
     * demas del lexico, con su caja ("anakin" -> "Anakin"). Las que si lo son ("orden", "hunter", "rose", "macht",
     * "fett") siguen las reglas del diccionario del idioma. Al final, los sustantivos alemanes propios de la saga.
     */
    private static final Set<String> SW_NAMES = new HashSet<>(Arrays.asList(
            "anakin", "skywalker", "luke", "leia", "vader", "darth", "yoda", "kenobi", "jedi", "sith", "ahsoka",
            "chewbacca", "chewie", "lando", "jabba", "dooku", "jango", "naboo", "kanan", "bridger", "pryce", "quinlan",
            "biggs", "finn", "ezra", "lars", "owen", "gideon", "beckett", "dexter", "gregor", "galen", "sabine",
            "morgan", "jesse", "cody", "hera", "satine", "merrin", "elsbeth", "artoo", "reva", "padawan",
            "lichtschwert", "todesstern", "hyperraum", "hyperantrieb", "droiden"));

    /** Nunca se proponen ni se usan como correccion (si se escriben, se respetan). */
    private static final Set<String> NO_SUGGEST = new HashSet<>(Arrays.asList(
            "puta", "puto", "putas", "putos", "mierda", "joder", "coño", "cabron", "cabrón", "carajo",
            "pendejo", "pendeja", "chingar", "chingada", "chingado", "verga", "culo", "culero", "mamón",
            "pinche", "maricon", "maricón", "polla", "gilipollas", "zorra", "perra",
            "fuck", "fucking", "fucked", "fucker", "shit", "shitty", "bitch", "asshole", "cunt", "dick",
            "cock", "pussy", "bastard", "whore", "slut", "nigger", "faggot"));

    /** Contracciones inglesas: la lista de frecuencia no lleva apostrofos y la primera pagina del teclado tampoco. */
    private static final Map<String, String> CONTRACTIONS = new HashMap<>();
    static {
        String[] pairs = {
                "im", "I'm", "ive", "I've", "dont", "don't", "doesnt", "doesn't", "didnt", "didn't",
                "cant", "can't", "couldnt", "couldn't", "wont", "won't", "wouldnt", "wouldn't", "shouldnt", "shouldn't",
                "isnt", "isn't", "arent", "aren't", "wasnt", "wasn't", "werent", "weren't", "hasnt", "hasn't",
                "havent", "haven't", "hadnt", "hadn't", "youre", "you're", "youve", "you've", "youll", "you'll",
                "youd", "you'd", "theyre", "they're", "theyve", "they've", "theyll", "they'll", "weve", "we've",
                "thats", "that's", "whats", "what's", "hes", "he's", "shes", "she's", "aint", "ain't",
                "wheres", "where's", "theres", "there's", "heres", "here's", "whos", "who's", "hows", "how's",
                "itll", "it'll", "thatll", "that'll"};
        for (int i = 0; i + 1 < pairs.length; i += 2) CONTRACTIONS.put(pairs[i], pairs[i + 1]);
    }

    /** Pares que suenan igual o parecido: cambiar uno por otro es un error frecuente (s/z, b/v, c/k...). */
    private static final Set<String> PHONETIC = new HashSet<>(Arrays.asList(
            "sz", "sc", "cz", "bv", "gj", "yi", "ck", "qk", "ks", "cq", "zs"));

    /**
     * Filas de letras de cada idioma, tal como las dibuja el teclado: QWERTY con ñ en espanol, QWERTY en
     * ingles y QWERTZ con ü ö ä en aleman. De ellas salen las teclas vecinas de cada letra.
     */
    private static String[] rows(String lang) {
        if ("de".equals(lang)) return new String[]{"qwertzuiopü", "asdfghjklöä", "yxcvbnm"};
        if ("en".equals(lang)) return new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        return new String[]{"qwertyuiop", "asdfghjklñ", "zxcvbnm"};
    }

    /** Teclas vecinas por idioma: cambiar una letra por una contigua es el error mas probable. */
    private static final Map<String, Map<Character, String>> NEIGHBORS = new HashMap<>();

    /** Letras vecinas de cada letra en la distribucion del idioma (se calcula una vez por idioma). */
    private static synchronized Map<Character, String> neighbors(String lang) {
        Map<Character, String> map = NEIGHBORS.get(lang);
        if (map != null) return map;
        map = new HashMap<>();
        String[] rows = rows(lang);
        float[] offset = {0f, 0.5f, 1.5f};
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                StringBuilder nb = new StringBuilder();
                float x = i + offset[r];
                for (int r2 = Math.max(0, r - 1); r2 <= Math.min(rows.length - 1, r + 1); r2++) {
                    for (int j = 0; j < rows[r2].length(); j++) {
                        if (r2 == r && j == i) continue;
                        if (Math.abs(j + offset[r2] - x) <= 1.01f) nb.append(rows[r2].charAt(j));
                    }
                }
                map.put(rows[r].charAt(i), nb.toString());
            }
        }
        NEIGHBORS.put(lang, map);
        return map;
    }

    /** Idioma admitido ("es", "en" o "de"); cualquier otro valor se trata como espanol. */
    private static String normalize(String lang) {
        return "en".equals(lang) ? "en" : "de".equals(lang) ? "de" : "es";
    }

    final String lang;
    private final String alphabet;
    private final Map<Character, String> neighbors;                      // teclas vecinas en la distribucion del idioma
    private final boolean foldUmlaut;                                    // ü -> u al comparar (no en aleman: es letra propia)
    private final HashMap<String, Integer> rank = new HashMap<>();
    private final HashMap<String, String> accented = new HashMap<>();   // sin tildes -> forma con tildes mas frecuente
    private final String[] sorted;                                       // orden alfabetico, para completar
    private final String[] byRank;                                       // por frecuencia (indice = rango), para el deslizado
    private final String[][] byLength = new String[MAX_LEN + 3][];        // palabras por longitud, en orden de frecuencia
    private volatile Set<String> personal = Collections.emptySet();      // palabras aprendidas del usuario
    private volatile Map<String, String> personalPlain = Collections.emptyMap();   // forma sin tildes -> palabra aprendida
    // ---- lexico Star Wars: el mismo archivo para los tres idiomas, cargado una vez con cada instancia
    private volatile boolean starWars;                                   // activo (Prefs.STARWARS); apagado hasta que lo fije el IME
    private final HashMap<String, String> swCanon = new HashMap<>();     // minusculas -> forma canonica ("coruscant" -> "Coruscant")
    private final HashMap<String, String> swPlain = new HashMap<>();     // sin tildes -> entrada en minusculas ("padme" -> "padmé")
    private final String[] swWords;                                      // entradas en minusculas, en el orden del archivo
    private final String[] swFolded;                                     // las mismas en la forma de comparacion ({@link #fold})
    /**
     * Entradas que en minusculas serian un error evidente de una palabra frecuente de este idioma ({@link #SW_CASE_LIMIT}):
     * solo cuentan escritas con su caja ("Hoth", "Rael", "Kamino") y nunca se completan ni sirven de correccion.
     * Depende del idioma: "hoth" se reconoce en espanol, pero en ingles es "both" mal escrito.
     */
    private final HashSet<String> swCaseOnly = new HashSet<>();

    /** Carga (o recupera de la cache) el diccionario de un idioma. Puede llamarse en segundo plano. */
    static synchronized Dictionary get(Context c, String lang) throws IOException {
        String l = normalize(lang);
        Dictionary d = CACHE.get(l);
        if (d == null) {
            d = new Dictionary(c.getApplicationContext(), l);
            CACHE.put(l, d);
        }
        return d;
    }

    /** Diccionario ya cargado, o null (sin bloquear aunque otro hilo este cargando). */
    static Dictionary cached(String lang) {
        return CACHE.get(normalize(lang));
    }

    private Dictionary(Context c, String lang) throws IOException {
        this.lang = lang;
        boolean de = "de".equals(lang);
        alphabet = "en".equals(lang) ? "abcdefghijklmnopqrstuvwxyz"
                : de ? "abcdefghijklmnopqrstuvwxyzäöüß" : "abcdefghijklmnopqrstuvwxyzñ";
        neighbors = neighbors(lang);
        foldUmlaut = !de;
        int res = "en".equals(lang) ? R.raw.words_en : de ? R.raw.words_de : R.raw.words_es;
        List<String> all = new ArrayList<>(40000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String w = line.trim();
                if (w.isEmpty() || rank.containsKey(w)) continue;
                rank.put(w, all.size());
                all.add(w);
                String plain = fold(w);
                if (!plain.equals(w) && !accented.containsKey(plain)) accented.put(plain, w);
            }
        } finally {
            r.close();
        }
        byRank = all.toArray(new String[0]);
        sorted = byRank.clone();
        Arrays.sort(sorted);
        List<List<String>> buckets = new ArrayList<>();
        for (int i = 0; i < byLength.length; i++) buckets.add(new ArrayList<String>());
        for (String w : all) if (w.length() < byLength.length) buckets.get(w.length()).add(w);
        for (int i = 0; i < byLength.length; i++) byLength[i] = buckets.get(i).toArray(new String[0]);

        List<String> saga = new ArrayList<>(800);
        r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(R.raw.starwars_words), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String w = line.trim();
                if (w.length() < 2) continue;
                String lower = w.toLowerCase(Locale.ROOT);
                if (swCanon.containsKey(lower)) continue;
                swCanon.put(lower, w);
                saga.add(lower);
                String plain = fold(lower);
                if (!plain.equals(lower) && !swPlain.containsKey(plain)) swPlain.put(plain, lower);
            }
        } finally {
            r.close();
        }
        swWords = saga.toArray(new String[0]);
        swFolded = new String[swWords.length];
        for (int i = 0; i < swWords.length; i++) swFolded[i] = fold(swWords[i]);
        // Entradas con mayuscula que en minusculas corregiria el diccionario del idioma sin dudar: las variantes a una
        // edicion de suggest(), con el lexico aun apagado (solo cuentan las palabras del idioma y no hay aprendidas)
        HashMap<String, Double> cand = new HashMap<>();
        for (String w : swWords) {
            if (w.length() < SW_MIN || rank.containsKey(w) || w.equals(swCanon.get(w))) continue;
            cand.clear();
            edits(w, cand);
            double best = Double.MAX_VALUE;
            for (Map.Entry<String, Double> e : cand.entrySet()) {
                if (!e.getKey().equals(w) && !NO_SUGGEST.contains(e.getKey()) && e.getValue() < best) best = e.getValue();
            }
            if (best <= SW_CASE_LIMIT) swCaseOnly.add(w);
        }
    }

    /**
     * Palabras aprendidas del usuario: se aceptan como conocidas, se completan y tambien se corrige hacia ellas
     * (como entradas de frecuencia media, {@link #PERSONAL_SCORE}, con la misma distancia ponderada).
     */
    void setPersonal(Collection<String> words) {
        HashSet<String> set = new HashSet<>();
        HashMap<String, String> plain = new HashMap<>();
        if (words != null) {
            for (String w : words) {
                if (w == null || w.isEmpty()) continue;
                set.add(w);
                String f = fold(w);
                if (!plain.containsKey(f)) plain.put(f, w);
            }
        }
        personalPlain = plain;
        personal = set;
    }

    /** La palabra esta entre las aprendidas del usuario (se comparan en minusculas, como se guardan). */
    boolean isPersonal(String w) {
        return w != null && personal.contains(w.toLowerCase(Locale.ROOT));
    }

    /** Palabra aprendida igual a la forma dada o a su forma sin tildes (el usuario pudo escribirla sin ellas), o null. */
    private String personalWord(String v) {
        Set<String> set = personal;
        if (set.isEmpty()) return null;
        if (set.contains(v)) return v;
        return personalPlain.get(fold(v));
    }

    // ---- lexico Star Wars

    /** Activa o desactiva el lexico Star Wars en conocidas, completados, correcciones y {@link #canonical}. */
    void setStarWars(boolean on) { starWars = on; }

    boolean starWars() { return starWars; }

    /**
     * Forma canonica de una entrada del lexico Star Wars ("coruscant" -> "Coruscant"), o null si no lo es, si el
     * lexico esta desactivado, si la entrada va en minusculas ("bantha", "lightsaber": se respeta la caja de lo
     * escrito) o si la palabra es ademas una palabra normal del idioma ("orden", "fuerza", "ghost", "wing"; en
     * espanol tambien "leia", que es "leía" sin tilde): esas no cambian de forma.
     */
    String canonical(String lower) {
        if (!starWars || lower == null) return null;
        String c = swCanon.get(lower);
        if (c == null || c.equals(lower) || ordinary(lower)) return null;
        return c;
    }

    /**
     * Palabra normal del idioma: esta en su diccionario (y no es uno de los nombres de {@link #SW_NAMES}) o es la
     * forma sin tilde de una ("leia" es "leía" mal escrita en espanol). Sigue las reglas del diccionario, no las
     * del lexico, aunque figure en el.
     */
    private boolean ordinary(String w) {
        if (rank.containsKey(w) && !SW_NAMES.contains(w)) return true;
        String acc = accented.get(fold(w));
        return acc != null && !acc.equals(w);
    }

    /** Entrada del lexico activo, de {@link #SW_MIN} letras o mas, que no es palabra normal del idioma. */
    private boolean lexicon(String w) {
        return starWars && w.length() >= SW_MIN && swCanon.containsKey(w) && !ordinary(w);
    }

    /**
     * Entrada del lexico que cuenta escrita de cualquier forma, se completa y sirve de correccion: las de
     * {@link #lexicon} salvo las que en minusculas serian un error evidente ({@link #swCaseOnly}).
     */
    private boolean saga(String w) {
        return lexicon(w) && !swCaseOnly.contains(w);
    }

    /** Entrada del lexico ({@link #saga}) igual a la forma dada o a su forma sin tildes ("padme" -> "padmé"), o null. */
    private String swWord(String v) {
        if (!starWars) return null;
        String s = swCanon.containsKey(v) ? v : swPlain.get(fold(v));
        return s != null && saga(s) ? s : null;
    }

    /**
     * Lo escrito es una entrada del lexico activo que no es palabra normal del idioma: tal cual o con tildes de mas
     * ("ahsóka" se respeta); las cortas ("Rey", "Poe", "AT") y las que en minusculas serian otra palabra ("Hoth" en
     * ingles) solo con su caja canonica; tambien su plural regular ("ewoks", "wookiees", "Hutts").
     */
    private boolean swKnown(String typed, String w) {
        if (!starWars) return false;
        String canon = swCanon.get(w);
        if (canon == null && !w.equals(fold(w))) canon = swCanon.get(fold(w));
        if (canon != null) {
            String entry = canon.toLowerCase(Locale.ROOT);
            return saga(entry) || (!ordinary(entry) && canon.equals(typed));
        }
        int n = w.length();
        if (n <= SW_MIN || w.charAt(n - 1) != 's') return false;
        String stem = w.substring(0, n - 1);
        String sc = swCanon.get(stem);
        return sc != null && (saga(stem) || (!ordinary(stem) && typed.startsWith(sc)));
    }

    /**
     * Quita las tildes (á é í ó ú). La ñ y las letras alemanas ä ö ü ß se conservan: son letras propias
     * del teclado, no una letra base con acento. Independiente del idioma.
     */
    static String deaccent(String s) {
        return strip(s, false);
    }

    /** Forma de comparacion de este diccionario: sin tildes y, salvo en aleman, con la ü plegada en u. */
    private String fold(String s) {
        return strip(s, foldUmlaut);
    }

    private static String strip(String s, boolean foldUmlaut) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char p = plain(ch, foldUmlaut);
            if (p != ch && b == null) {
                b = new StringBuilder(s.length());
                b.append(s, 0, i);
            }
            if (b != null) b.append(p);
        }
        return b == null ? s : b.toString();
    }

    private static char plain(char ch, boolean foldUmlaut) {
        switch (ch) {
            case 'á': return 'a';
            case 'é': return 'e';
            case 'í': return 'i';
            case 'ó': return 'o';
            case 'ú': return 'u';
            case 'ü': return foldUmlaut ? 'u' : ch;
            default: return ch;
        }
    }

    /** Palabra del idioma, aprendida o del lexico Star Wars activo (estas, aunque exijan su caja: no se aprenden). */
    boolean contains(String w) {
        return rank.containsKey(w) || personal.contains(w) || lexicon(w);
    }

    int size() { return sorted.length; }

    // ---- acceso por rango (lo usa SwipeDecoder para construir su indice; no cambia nada de lo anterior)

    /** Palabra con ese rango de frecuencia: 0 es la mas frecuente y {@link #size()} - 1 la ultima. */
    String word(int rank) { return byRank[rank]; }

    /** Rango de frecuencia de una palabra (tal cual aparece en la lista, con tildes), o -1 si no esta. */
    int rankOf(String w) {
        Integer r = rank.get(w);
        return r == null ? -1 : r;
    }

    /** Palabras aprendidas del usuario (instantanea de solo lectura). */
    Set<String> personal() { return personal; }

    /** Contraccion inglesa para una forma sin apostrofo ("dont" -> "don't"), o null; en espanol siempre null. */
    String contraction(String w) { return "en".equals(lang) ? CONTRACTIONS.get(w) : null; }

    /** Palabras que nunca se proponen (ni como correccion ni como resultado del deslizado). */
    static boolean noSuggest(String w) { return NO_SUGGEST.contains(w); }

    /**
     * Entrada del diccionario para una forma escrita: la misma palabra o, si su forma con tildes
     * es mucho mas frecuente (seis veces o mas), esa forma. Null si no hay nada parecido.
     */
    private String resolve(String v) {
        Integer own = rank.get(v);
        String acc = accented.get(fold(v));
        if (acc != null && !acc.equals(v)) {
            int ar = rank.get(acc);
            if (own == null || own >= 6 * (ar + 1)) return acc;
        }
        return own == null ? null : v;
    }

    /** Puntuacion maxima (ln del rango mas la penalizacion) para corregir sin preguntar, por longitud. */
    private static double limit(int n) {
        return n <= 2 ? 5.7 : n == 3 ? 7.3 : n == 4 ? 8.7 : 9.6;
    }

    /**
     * ln(rango) de una palabra del diccionario, o {@link #PERSONAL_SCORE} si es aprendida, mas la penalizacion. Una
     * entrada del lexico Star Wars ({@link #saga}) puntua al menos como una aprendida ({@link #SW_LONG_SCORE} si es
     * larga) aunque figure en la cola del diccionario del idioma ("anakin", "skywalker"): asi no la tapan palabras raras.
     */
    private double score(String word, double penalty) {
        Integer r = rank.get(word);
        double base = r == null ? PERSONAL_SCORE : Math.log(r + 1);
        if (saga(word)) {
            double s = word.length() > 5 ? SW_LONG_SCORE : PERSONAL_SCORE;
            if (s < base) base = s;
        }
        return base + penalty;
    }

    /** Sugerencias y correccion para lo que se lleva escrito de una palabra. */
    Result suggest(String typed) {
        Result res = new Result();
        String w = typed.toLowerCase(Locale.ROOT);
        int n = w.length();
        if (n == 0 || n > 24) return res;
        if ("en".equals(lang)) {
            if (w.equals("i")) { res.correction = "I"; return res; }
            String c = CONTRACTIONS.get(w);
            if (c != null) {
                res.correction = c;
                res.suggestions.add(c);
                return res;
            }
            if (w.indexOf('\'') >= 0) {              // "don't" se respeta tal cual
                res.known = rank.containsKey(w.replace("'", ""));
                return res;
            }
        }
        if (n < 2 || n > MAX_LEN) return res;
        boolean learned = personal.contains(w);
        if (!learned && !w.equals(fold(w))) {              // escrita con tildes y aprendida sin ellas: se respeta tal cual
            String plainLearned = personalPlain.get(fold(w));
            learned = plainLearned != null && plainLearned.equals(fold(w));
        }
        boolean inLexicon = swKnown(typed, w);             // del lexico Star Wars, si esta activo
        res.known = rank.containsKey(w) || learned || inLexicon;

        HashMap<String, Double> cand = new HashMap<>();
        String acc = learned || inLexicon ? null : resolve(w);
        boolean accentFix = acc != null && !acc.equals(w);
        if (accentFix) put(cand, acc, score(acc, 0));
        if (!res.known) {
            String pacc = personalWord(w);               // aprendida con tildes de lo escrito sin ellas
            if (pacc != null && !pacc.equals(w)) put(cand, pacc, score(pacc, 0));
            String sacc = swWord(w);                     // entrada del lexico con tildes ("padme" -> "padmé")
            if (sacc != null && !sacc.equals(w)) put(cand, sacc, score(sacc, 0));
            edits(w, cand);
            if (n >= 5) scan(w, cand);                   // tambien a dos ediciones
        }

        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map.Entry<String, Double> e : cand.entrySet()) {
            if (e.getKey().equals(w) || NO_SUGGEST.contains(e.getKey())) continue;
            if (e.getValue() < bestScore) { bestScore = e.getValue(); best = e.getKey(); }
        }
        if (best != null) {
            if (!res.known) {
                // una palabra aprendida a una sola edicion de lo escrito se corrige aunque el limite por longitud
                // la dejara fuera: el usuario la guardo a proposito
                boolean personalEdit = n >= 3 && !rank.containsKey(best) && personal.contains(best)
                        && bestScore - PERSONAL_SCORE <= MAX_EDIT;
                // lo mismo para una entrada del lexico Star Wars; las largas admiten hasta dos errores caros
                // ("palatino" -> "palpatine"): pocas palabras normales se les parecen tanto
                boolean sagaEdit = n >= 3 && saga(best)
                        && bestScore - score(best, 0) <= (best.length() > 5 ? SW_ED2_CUTOFF : MAX_EDIT);
                if (bestScore <= limit(n) || personalEdit || sagaEdit) res.correction = best;
            }
            else if (accentFix && best.equals(acc)) res.correction = acc;
        }

        for (String s : completions(w, MAX_SUGGESTIONS)) put(cand, s, score(s, 0.5));
        for (String p : personal) if (p.startsWith(w) && !p.equals(w)) put(cand, p, 1.0);
        if (starWars) {                                  // entradas del lexico que empiezan asi: como mucho dos, las mas cortas, con
            double pen = n >= 5 ? 0.5 : n == 4 ? 1.2 : 2.0;   // prefijos cortos por detras de las palabras corrientes ("man" no trae Mandalore)
            String a = null, b = null;
            for (String s : swWords) {
                if (!s.startsWith(w) || s.equals(w) || !saga(s)) continue;
                if (a == null || s.length() < a.length()) { b = a; a = s; }
                else if (b == null || s.length() < b.length()) b = s;
            }
            if (a != null) put(cand, a, PERSONAL_SCORE + pen + 0.1 * (a.length() - n));   // cuantas menos letras falten, mejor
            if (b != null) put(cand, b, PERSONAL_SCORE + pen + 0.1 * (b.length() - n));
        }
        fuzzyCompletions(w, cand);
        List<Map.Entry<String, Double>> list = new ArrayList<>(cand.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Double>>() {
            @Override public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(a.getValue(), b.getValue());
            }
        });
        if (res.correction != null) res.suggestions.add(res.correction);
        for (Map.Entry<String, Double> e : list) {
            if (res.suggestions.size() >= MAX_SUGGESTIONS) break;
            String s = e.getKey();
            if (s.equals(w) || NO_SUGGEST.contains(s) || res.suggestions.contains(s)) continue;
            res.suggestions.add(s);
        }
        return res;
    }

    /**
     * Variantes a una edicion con la penalizacion de cada tipo de error: letra repetida de mas (barata),
     * letra de mas, dos letras invertidas, letra cambiada por una vecina o de sonido parecido (barata)
     * o por otra cualquiera (cara), letra de menos (barata si era una doble).
     */
    private void edits(String w, Map<String, Double> cand) {
        int n = w.length();
        for (int i = 0; i < n; i++) {
            boolean doubled = (i > 0 && w.charAt(i) == w.charAt(i - 1)) || (i + 1 < n && w.charAt(i) == w.charAt(i + 1));
            offer(cand, w.substring(0, i) + w.substring(i + 1), doubled ? 0.4 : 2.0);
        }
        for (int i = 0; i + 1 < n; i++) {
            if (w.charAt(i) == w.charAt(i + 1)) continue;
            offer(cand, w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2), 0.6);
        }
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            String nb = neighbors.get(c);
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                if (ch == c) continue;
                boolean close = (nb != null && nb.indexOf(ch) >= 0)
                        || PHONETIC.contains("" + c + ch) || PHONETIC.contains("" + ch + c);
                offer(cand, w.substring(0, i) + ch + w.substring(i + 1), close ? 0.4 : 2.5);
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                boolean doubled = (i > 0 && w.charAt(i - 1) == ch) || (i < n && w.charAt(i) == ch);
                offer(cand, w.substring(0, i) + ch + w.substring(i), doubled ? 0.3 : 1.0);
            }
        }
    }

    /**
     * Busqueda a dos ediciones: palabras de longitud parecida que empiezan por la misma letra, con la
     * distancia ponderada (los mismos costes que las variantes a una edicion) y corte temprano. Las palabras
     * aprendidas del usuario (como mucho unos cientos) y el lexico Star Wars (unas setecientas entradas) se
     * recorren con el mismo criterio; hacia las entradas largas del lexico se admite un coste mayor.
     */
    private void scan(String w, Map<String, Double> cand) {
        String a = fold(w);
        char first = a.charAt(0);
        int n = a.length();
        double[][] dp = new double[n + 1][n + 4];
        for (int len = Math.max(2, n - 2); len <= Math.min(byLength.length - 1, n + 2); len++) {
            for (String word : byLength[len]) {
                if (plain(word.charAt(0), foldUmlaut) != first) continue;
                String b = fold(word);
                double cost = distance(a, b, ED2_CUTOFF, dp);
                if (cost <= 0 || cost > ED2_CUTOFF) continue;
                put(cand, word, score(word, cost));
            }
        }
        for (String p : personal) {
            int len = p.length();
            if (len < 2 || len < n - 2 || len > n + 2 || plain(p.charAt(0), foldUmlaut) != first) continue;
            double cost = distance(a, fold(p), ED2_CUTOFF, dp);
            if (cost <= 0 || cost > ED2_CUTOFF) continue;
            put(cand, p, score(p, cost));
        }
        if (!starWars) return;
        for (int i = 0; i < swWords.length; i++) {
            String s = swWords[i];
            int len = s.length();
            if (len < n - 2 || len > n + 2 || swFolded[i].charAt(0) != first || !saga(s)) continue;
            double cutoff = len > 5 ? SW_ED2_CUTOFF : ED2_CUTOFF;
            double cost = distance(a, swFolded[i], cutoff, dp);
            if (cost <= 0 || cost > cutoff) continue;
            put(cand, s, score(s, cost));
        }
    }

    private boolean close(char x, char y) {
        String nb = neighbors.get(x);
        return (nb != null && nb.indexOf(y) >= 0) || PHONETIC.contains("" + x + y) || PHONETIC.contains("" + y + x);
    }

    private static double deleteCost(String a, int i) {
        boolean doubled = (i > 0 && a.charAt(i) == a.charAt(i - 1)) || (i + 1 < a.length() && a.charAt(i) == a.charAt(i + 1));
        return doubled ? 0.4 : 2.0;
    }

    private static double insertCost(String b, int j) {
        boolean doubled = (j > 0 && b.charAt(j) == b.charAt(j - 1)) || (j + 1 < b.length() && b.charAt(j) == b.charAt(j + 1));
        return doubled ? 0.3 : 1.0;
    }

    /**
     * Distancia de edicion ponderada entre lo escrito {@code a} y la palabra {@code b} (ambas sin tildes):
     * Damerau-Levenshtein con el coste de cada tipo de error. Devuelve MAX_VALUE si supera el corte.
     */
    private double distance(String a, String b, double cutoff, double[][] d) {
        int n = a.length(), m = b.length();
        d[0][0] = 0;
        for (int j = 1; j <= m; j++) d[0][j] = d[0][j - 1] + insertCost(b, j - 1);
        for (int i = 1; i <= n; i++) {
            d[i][0] = d[i - 1][0] + deleteCost(a, i - 1);
            double rowMin = d[i][0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                double best = d[i - 1][j - 1] + (ca == cb ? 0 : close(ca, cb) ? 0.4 : 2.5);
                best = Math.min(best, d[i - 1][j] + deleteCost(a, i - 1));
                best = Math.min(best, d[i][j - 1] + insertCost(b, j - 1));
                if (i > 1 && j > 1 && ca != cb && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    best = Math.min(best, d[i - 2][j - 2] + 0.6);
                }
                d[i][j] = best;
                if (best < rowMin) rowMin = best;
            }
            if (rowMin > cutoff) return Double.MAX_VALUE;
        }
        return d[n][m];
    }

    /** Completados aunque el prefijo lleve un error: variantes a una edicion que sean prefijo de alguna palabra. */
    private void fuzzyCompletions(String w, Map<String, Double> cand) {
        int n = w.length();
        if (n < 3) return;
        HashSet<String> variants = new HashSet<>();
        for (int i = 0; i < n; i++) variants.add(w.substring(0, i) + w.substring(i + 1));
        for (int i = 0; i + 1 < n; i++) variants.add(w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2));
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                if (close(w.charAt(i), ch)) variants.add(w.substring(0, i) + ch + w.substring(i + 1));
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int a = 0; a < alphabet.length(); a++) variants.add(w.substring(0, i) + alphabet.charAt(a) + w.substring(i));
        }
        for (String v : variants) {
            if (v.length() < 3 || v.equals(w)) continue;
            for (String s : completions(v, 2)) put(cand, s, score(s, 1.2));
        }
        for (String p : personal) {                      // aprendidas que extienden alguna variante
            if (p.equals(w)) continue;
            for (int len = Math.max(3, n - 1); len <= n + 1 && len < p.length(); len++) {
                if (variants.contains(p.substring(0, len))) { put(cand, p, score(p, 1.2)); break; }
            }
        }
        if (!starWars) return;
        // Entradas del lexico Star Wars que extienden una variante de igual longitud o una letra mas larga (no la de
        // una letra de menos: para una palabra completa como "hola" abriria todas las que empiezan por "hol"), con
        // mas penalizacion que las aprendidas porque son muchas mas
        for (String s : swWords) {
            if (s.length() <= n) continue;
            for (int len = n; len <= n + 1 && len < s.length(); len++) {
                if (variants.contains(s.substring(0, len))) { if (saga(s)) put(cand, s, score(s, 2.0)); break; }
            }
        }
    }

    /**
     * Anota la variante si existe (o su forma con tildes), con puntuacion ln(rango) + penalizacion; si es una
     * palabra aprendida o del lexico Star Wars, con {@link #PERSONAL_SCORE} (o {@link #SW_LONG_SCORE}) + penalizacion.
     */
    private void offer(Map<String, Double> cand, String variant, double penalty) {
        String r = resolve(variant);
        if (r != null) put(cand, r, score(r, penalty));
        String p = personalWord(variant);
        if (p != null && !p.equals(r)) put(cand, p, score(p, penalty));
        String s = swWord(variant);
        if (s != null && !s.equals(r) && !s.equals(p)) put(cand, s, score(s, penalty));
    }

    private static void put(Map<String, Double> cand, String word, double score) {
        Double old = cand.get(word);
        if (old == null || score < old) cand.put(word, score);
    }

    /** Palabras mas frecuentes que empiezan por el prefijo (busqueda binaria en la lista ordenada). */
    List<String> completions(String prefix, int max) {
        List<String> out = new ArrayList<>();
        if (prefix.length() < 2) return out;
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid].compareTo(prefix) < 0) lo = mid + 1; else hi = mid;
        }
        String[] best = new String[max];
        int[] bestRank = new int[max];
        Arrays.fill(bestRank, Integer.MAX_VALUE);
        int scanned = 0;
        for (int i = lo; i < sorted.length && sorted[i].startsWith(prefix) && scanned < MAX_PREFIX_SCAN; i++, scanned++) {
            String s = sorted[i];
            if (s.equals(prefix) || NO_SUGGEST.contains(s)) continue;
            int r = rank.get(s);
            for (int j = 0; j < max; j++) {
                if (r < bestRank[j]) {
                    for (int k = max - 1; k > j; k--) { best[k] = best[k - 1]; bestRank[k] = bestRank[k - 1]; }
                    best[j] = s;
                    bestRank[j] = r;
                    break;
                }
            }
        }
        for (String s : best) if (s != null) out.add(s);
        return out;
    }
}
