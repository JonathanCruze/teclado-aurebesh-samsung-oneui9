package com.aurebesh.teclado;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Diccionario de frecuencia por idioma (res/raw/words_es.txt y words_en.txt: una palabra por linea,
 * de mas a menos frecuente). Da autocorreccion a una edicion, restauracion de tildes y completado
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

    /** Teclas vecinas en QWERTY: cambiar una letra por una contigua es el error mas probable. */
    private static final Map<Character, String> NEIGHBORS = new HashMap<>();
    static {
        String[] rows = {"qwertyuiop", "asdfghjklñ", "zxcvbnm"};
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
                NEIGHBORS.put(rows[r].charAt(i), nb.toString());
            }
        }
    }

    final String lang;
    private final String alphabet;
    private final HashMap<String, Integer> rank = new HashMap<>();
    private final HashMap<String, String> accented = new HashMap<>();   // sin tildes -> forma con tildes mas frecuente
    private final String[] sorted;                                       // orden alfabetico, para completar

    /** Carga (o recupera de la cache) el diccionario de un idioma. Puede llamarse en segundo plano. */
    static synchronized Dictionary get(Context c, String lang) throws IOException {
        String l = "en".equals(lang) ? "en" : "es";
        Dictionary d = CACHE.get(l);
        if (d == null) {
            d = new Dictionary(c.getApplicationContext(), l);
            CACHE.put(l, d);
        }
        return d;
    }

    /** Diccionario ya cargado, o null. */
    static synchronized Dictionary cached(String lang) {
        return CACHE.get("en".equals(lang) ? "en" : "es");
    }

    private Dictionary(Context c, String lang) throws IOException {
        this.lang = lang;
        alphabet = "en".equals(lang) ? "abcdefghijklmnopqrstuvwxyz" : "abcdefghijklmnopqrstuvwxyzñ";
        int res = "en".equals(lang) ? R.raw.words_en : R.raw.words_es;
        List<String> all = new ArrayList<>(40000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String w = line.trim();
                if (w.isEmpty() || rank.containsKey(w)) continue;
                rank.put(w, all.size());
                all.add(w);
                String plain = deaccent(w);
                if (!plain.equals(w) && !accented.containsKey(plain)) accented.put(plain, w);
            }
        } finally {
            r.close();
        }
        sorted = all.toArray(new String[0]);
        Arrays.sort(sorted);
    }

    /** Quita las tildes y la dieresis (la ñ se conserva: es una letra propia del teclado). */
    static String deaccent(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char p = plain(ch);
            if (p != ch && b == null) {
                b = new StringBuilder(s.length());
                b.append(s, 0, i);
            }
            if (b != null) b.append(p);
        }
        return b == null ? s : b.toString();
    }

    private static char plain(char ch) {
        switch (ch) {
            case 'á': return 'a';
            case 'é': return 'e';
            case 'í': return 'i';
            case 'ó': return 'o';
            case 'ú':
            case 'ü': return 'u';
            default: return ch;
        }
    }

    boolean contains(String w) { return rank.containsKey(w); }

    int size() { return sorted.length; }

    /**
     * Entrada del diccionario para una forma escrita: la misma palabra o, si su forma con tildes
     * es mucho mas frecuente (seis veces o mas), esa forma. Null si no hay nada parecido.
     */
    private String resolve(String v) {
        Integer own = rank.get(v);
        String acc = accented.get(deaccent(v));
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
        if (n < 2) return res;
        res.known = rank.containsKey(w);

        HashMap<String, Double> cand = new HashMap<>();
        String acc = resolve(w);
        boolean accentFix = acc != null && !acc.equals(w);
        if (accentFix) put(cand, acc, score(acc, 0));
        if (!res.known) edits(w, cand);

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
            String nb = NEIGHBORS.get(c);
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
