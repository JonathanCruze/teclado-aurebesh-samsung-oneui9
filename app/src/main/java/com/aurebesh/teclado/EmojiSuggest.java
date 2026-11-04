package com.aurebesh.teclado;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emojis sugeridos por palabra ("perro" -> perro, cara de perro): indice de las palabras clave de
 * una sola palabra de emoji_names.txt (espanol e ingles, sin tildes), en el orden de Unicode.
 * Los emojis cuyo nombre es exactamente la palabra van primero.
 *
 * <p>Por delante de todo eso va emoji_extra.txt: palabras conversacionales en espanol, ingles y aleman
 * (hola, jaja, danke, work...) que en las anotaciones de Unicode no tienen emoji o tienen uno pobre, con
 * los emojis elegidos a mano; sus emojis se proponen primero. Las risas (jajaja, jejeje, hahaha, hehehe,
 * de cualquier longitud) se reducen a su forma base antes de buscar.
 */
final class EmojiSuggest {
    private EmojiSuggest() {}

    private static final int PER_WORD = 4;
    private static Map<String, List<String>> index;

    /** Hasta {@code max} emojis para la palabra (vacio si no hay ninguno). */
    static List<String> lookup(Context c, String word, int max) {
        List<String> out = new ArrayList<>();
        if (word == null || word.length() < 2) return out;
        String key = normalizeLaugh(Dictionary.deaccent(word.toLowerCase(Locale.ROOT)));
        List<String> hits = load(c).get(key);
        if (hits == null) return out;
        for (String e : hits) {
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    /**
     * Risas de cualquier longitud a su forma base: "jajajaja" y "jajaj" -> "jaja", "jejeje" -> "jeje",
     * "hahaha" -> "haha", "hehehe" -> "hehe". Cualquier otra palabra se devuelve tal cual.
     */
    static String normalizeLaugh(String w) {
        int n = w.length();
        if (n < 4) return w;
        char c = w.charAt(0), v = w.charAt(1);
        if ((c != 'j' && c != 'h') || (v != 'a' && v != 'e')) return w;
        int i = 0;
        while (i + 1 < n && w.charAt(i) == c && w.charAt(i + 1) == v) i += 2;
        if (i < 4) return w;
        if (i == n || (i == n - 1 && w.charAt(i) == c)) return new String(new char[]{c, v, c, v});
        return w;
    }

    private static synchronized Map<String, List<String>> load(Context c) {
        if (index != null) return index;
        Map<String, List<String>> extra = new HashMap<>();     // palabra -> emojis elegidos a mano (emoji_extra.txt)
        Map<String, List<String>> names = new HashMap<>();     // palabra -> emojis cuyo nombre es esa palabra
        Map<String, List<String>> keys = new HashMap<>();      // palabra -> emojis con esa palabra clave
        loadExtra(c, extra);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getResources().openRawResource(R.raw.emoji_names), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] f = line.split("\t");
                    if (f.length < 2) continue;
                    for (int k = 1; k < f.length; k++) {
                        String[] parts = f[k].split("\\|");
                        for (int p = 0; p < parts.length; p++) {
                            String item = parts[p].trim().toLowerCase(Locale.ROOT);
                            if (item.isEmpty() || item.indexOf(' ') >= 0 || item.indexOf(':') >= 0) continue;
                            add(p == 0 ? names : keys, Dictionary.deaccent(item), f[0]);
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // sin datos no hay sugerencias de emoji
        }
        Map<String, List<String>> merged = new HashMap<>();
        for (Map.Entry<String, List<String>> e : extra.entrySet()) merged.put(e.getKey(), new ArrayList<>(e.getValue()));
        append(merged, names);
        append(merged, keys);
        index = merged;
        return merged;
    }

    /** Lee emoji_extra.txt: lineas "palabra<TAB>emoji emoji ..."; las que empiezan por # son comentarios. */
    private static void loadExtra(Context c, Map<String, List<String>> extra) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getResources().openRawResource(R.raw.emoji_extra), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String word = Dictionary.deaccent(line.substring(0, tab).trim().toLowerCase(Locale.ROOT));
                    if (word.isEmpty() || word.indexOf(' ') >= 0) continue;
                    for (String emoji : line.substring(tab + 1).trim().split(" +")) {
                        if (!emoji.isEmpty()) add(extra, word, emoji);
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // sin el archivo extra quedan las palabras clave de Unicode
        }
    }

    /** Anade a cada lista de {@code merged} los emojis de {@code from} que falten, hasta PER_WORD por palabra. */
    private static void append(Map<String, List<String>> merged, Map<String, List<String>> from) {
        for (Map.Entry<String, List<String>> e : from.entrySet()) {
            List<String> list = merged.get(e.getKey());
            if (list == null) { list = new ArrayList<>(); merged.put(e.getKey(), list); }
            for (String emoji : e.getValue()) {
                if (list.size() >= PER_WORD) break;
                if (!list.contains(emoji)) list.add(emoji);
            }
        }
    }

    private static void add(Map<String, List<String>> map, String word, String emoji) {
        List<String> list = map.get(word);
        if (list == null) { list = new ArrayList<>(); map.put(word, list); }
        if (list.size() < PER_WORD && !list.contains(emoji)) list.add(emoji);
    }
}
