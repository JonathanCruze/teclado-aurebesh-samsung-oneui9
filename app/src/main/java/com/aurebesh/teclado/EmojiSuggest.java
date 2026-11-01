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
 */
final class EmojiSuggest {
    private EmojiSuggest() {}

    private static final int PER_WORD = 4;
    private static Map<String, List<String>> index;

    /** Hasta {@code max} emojis para la palabra (vacio si no hay ninguno). */
    static List<String> lookup(Context c, String word, int max) {
        List<String> out = new ArrayList<>();
        if (word == null || word.length() < 2) return out;
        List<String> hits = load(c).get(Dictionary.deaccent(word.toLowerCase(Locale.ROOT)));
        if (hits == null) return out;
        for (String e : hits) {
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    private static synchronized Map<String, List<String>> load(Context c) {
        if (index != null) return index;
        Map<String, List<String>> names = new HashMap<>();     // palabra -> emojis cuyo nombre es esa palabra
        Map<String, List<String>> keys = new HashMap<>();      // palabra -> emojis con esa palabra clave
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
        for (Map.Entry<String, List<String>> e : names.entrySet()) merged.put(e.getKey(), new ArrayList<>(e.getValue()));
        for (Map.Entry<String, List<String>> e : keys.entrySet()) {
            List<String> list = merged.get(e.getKey());
            if (list == null) { list = new ArrayList<>(); merged.put(e.getKey(), list); }
            for (String emoji : e.getValue()) {
                if (list.size() >= PER_WORD) break;
                if (!list.contains(emoji)) list.add(emoji);
            }
        }
        index = merged;
        return merged;
    }

    private static void add(Map<String, List<String>> map, String word, String emoji) {
        List<String> list = map.get(word);
        if (list == null) { list = new ArrayList<>(); map.put(word, list); }
        if (list.size() < PER_WORD && !list.contains(emoji)) list.add(emoji);
    }
}
