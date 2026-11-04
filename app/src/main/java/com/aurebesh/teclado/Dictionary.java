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
 */
final class Dictionary {

    /** Resultado para la palabra que se esta escribiendo. */
    static final class Result {
        final List<String> suggestions = new ArrayList<>();   // mejores primero, en minusculas
        String correction;      // se aplica al teclear un separador; null si no procede
        boolean known;          // la palabra escrita existe tal cual
    }

    private static final Map<String, Dictionary> CACHE = new HashMap<>();
    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_PREFIX_SCAN = 4000;
    private static final int MAX_LEN = 24;
    private static final double ED2_CUTOFF = 2.6;      // coste maximo de la busqueda a dos ediciones

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

    /** Diccionario ya cargado, o null. */
    static synchronized Dictionary cached(String lang) {
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
    }

    /** Palabras aprendidas del usuario: se aceptan como conocidas y se completan. */
    void setPersonal(Collection<String> words) {
        personal = words == null ? Collections.<String>emptySet() : new HashSet<>(words);
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

    boolean contains(String w) { return rank.containsKey(w) || personal.contains(w); }

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

    private double score(String word, double penalty) {
        return Math.log(rank.get(word) + 1) + penalty;
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
        res.known = rank.containsKey(w) || learned;

        HashMap<String, Double> cand = new HashMap<>();
        String acc = learned ? null : resolve(w);
        boolean accentFix = acc != null && !acc.equals(w);
        if (accentFix) put(cand, acc, score(acc, 0));
        if (!res.known) {
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
            if (!res.known) { if (bestScore <= limit(n)) res.correction = best; }
            else if (accentFix && best.equals(acc)) res.correction = acc;
        }

        for (String s : completions(w, MAX_SUGGESTIONS)) put(cand, s, score(s, 0.5));
        for (String p : personal) if (p.startsWith(w) && !p.equals(w)) put(cand, p, 1.0);
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
     * distancia ponderada (los mismos costes que las variantes a una edicion) y corte temprano.
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
    }

    /** Anota la variante si existe (o su forma con tildes), con puntuacion ln(rango) + penalizacion. */
    private void offer(Map<String, Double> cand, String variant, double penalty) {
        String r = resolve(variant);
        if (r == null) return;
        put(cand, r, score(r, penalty));
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
