package com.aurebesh.teclado;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Historial del portapapeles: los ultimos textos copiados, guardados en las preferencias de la app
 * (solo en este telefono). El mas reciente va primero.
 */
final class ClipHistory {
    private ClipHistory() {}

    static final String KEY = "clips";
    static final int MAX = 20;
    private static final int MAX_LENGTH = 5000;

    /** Elementos guardados, del mas reciente al mas antiguo; lista vacia si no hay o estan corruptos. */
    static List<String> items(Context c) {
        List<String> out = new ArrayList<>();
        String raw = Prefs.get(c).getString(KEY, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, null);
                if (s != null && !s.isEmpty()) out.add(s);
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    /** Anade un texto al principio; si ya estaba, lo sube; nunca mas de MAX elementos. */
    static void add(Context c, CharSequence text) {
        if (text == null) return;
        String s = text.toString();
        if (s.trim().isEmpty()) return;
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH);
        List<String> l = items(c);
        l.remove(s);
        l.add(0, s);
        while (l.size() > MAX) l.remove(l.size() - 1);
        save(c, l);
    }

    static void remove(Context c, String text) {
        if (text == null) return;
        List<String> l = items(c);
        if (l.remove(text)) save(c, l);
    }

    static void clear(Context c) {
        save(c, new ArrayList<String>());
    }

    private static void save(Context c, List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        Prefs.get(c).edit().putString(KEY, a.toString()).apply();
    }
}
