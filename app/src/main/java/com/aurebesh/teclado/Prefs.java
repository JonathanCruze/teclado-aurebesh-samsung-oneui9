package com.aurebesh.teclado;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    static final String NAME = "aurebesh";
    static final String HINTS = "show_latin_hints";
    static final String VIBRATE = "vibrate";
    static final String DIGRAPHS = "digraphs";
    static final String STRIP = "show_strip";
    static final String THEME = "theme";
    static final String NUMBERS = "number_row";
    static final String SOUND = "sound";
    static final String HEIGHT = "keyboard_height";
    static final String AUTOCORRECT = "autocorrect";
    static final String HAND = "hand_mode";
    static final String HAND_LAST = "hand_last";   // ultimo lado usado por el boton de la barra
    static final String TOOLBAR = "toolbar";
    static final String EMOJI_RECENT = "emoji_recent";
    static final String CUSTOM_SOUND = "custom_sound";    // nombre del archivo de sonido elegido
    static final String LEARN = "learn_words";
    static final String DOUBLE_SPACE = "double_space";
    static final String LEARNED = "learned_words";        // palabras aprendidas, separadas por salto de linea
    static final String APP_LANG = "app_lang";     // idioma de la app: "system", "es", "en", "de"
    static final String GLIDE = "glide";           // deslizar para escribir
    static final String SCREENSHOTS = "screenshots"; // sugerir capturas de pantalla recientes
    static final String SPLIT = "split_keyboard";   // teclado dividido: "auto", "on", "off"
    private static final String LEGACY_DARK = "dark_theme";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean hints(Context c) { return get(c).getBoolean(HINTS, false); }
    static boolean vibrate(Context c) { return get(c).getBoolean(VIBRATE, true); }
    static boolean digraphs(Context c) { return get(c).getBoolean(DIGRAPHS, true); }
    static String theme(Context c) {
        String t = get(c).getString(THEME, null);
        if (t == null) t = get(c).getBoolean(LEGACY_DARK, false) ? Themes.NOCHE.id : Themes.DEFAULT.id;
        return t;
    }
    static boolean numbers(Context c) { return get(c).getBoolean(NUMBERS, true); }
    static boolean sound(Context c) { return get(c).getBoolean(SOUND, true); }
    static String height(Context c) { return get(c).getString(HEIGHT, "normal"); }
    static float heightScale(Context c) {
        switch (height(c)) {
            case "compact": return 0.85f;
            case "large": return 1.15f;
            case "xlarge": return 1.3f;
            default: return 1f;
        }
    }
    static boolean autocorrect(Context c) { return get(c).getBoolean(AUTOCORRECT, true); }
    static String hand(Context c) { return get(c).getString(HAND, "none"); }
    /** Alterna el modo a una mano desde la barra: apagado <-> ultimo lado (derecha por defecto). */
    static void toggleHand(Context c) {
        String h = hand(c);
        SharedPreferences.Editor e = get(c).edit();
        if ("none".equals(h)) e.putString(HAND, get(c).getString(HAND_LAST, "right"));
        else e.putString(HAND_LAST, h).putString(HAND, "none");
        e.apply();
    }
    static void setHand(Context c, String side) { get(c).edit().putString(HAND, side).putString(HAND_LAST, side).apply(); }
    static int handMode(Context c) {
        switch (hand(c)) {
            case "left": return 1;
            case "right": return 2;
            default: return 0;
        }
    }
    static String appLang(Context c) { return get(c).getString(APP_LANG, "system"); }
    static boolean glide(Context c) { return get(c).getBoolean(GLIDE, true); }
    static String splitPref(Context c) { return get(c).getString(SPLIT, "auto"); }
    static int splitMode(Context c) {
        switch (splitPref(c)) {
            case "on": return 1;
            case "off": return 2;
            default: return 0;
        }
    }
    static boolean screenshots(Context c) { return get(c).getBoolean(SCREENSHOTS, false); }
    static boolean strip(Context c) { return get(c).getBoolean(STRIP, false); }
    static boolean toolbar(Context c) { return get(c).getBoolean(TOOLBAR, true); }
    /** Nombre visible del sonido personalizado, o null si suena el clic del sistema. */
    static String customSound(Context c) { return get(c).getString(CUSTOM_SOUND, null); }
    static boolean learn(Context c) { return get(c).getBoolean(LEARN, true); }
    static boolean doubleSpace(Context c) { return get(c).getBoolean(DOUBLE_SPACE, true); }

    /** Palabras que el usuario decidio conservar (en minusculas, tal como se guardan). */
    static java.util.Set<String> learnedWords(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String w : get(c).getString(LEARNED, "").split("\n")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Guarda una palabra aprendida (la mas reciente primero; como mucho 500). */
    static void addLearnedWord(Context c, String w) {
        if (w == null) return;
        String word = w.trim().toLowerCase(java.util.Locale.ROOT);
        if (word.isEmpty() || word.length() > 30) return;
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String x : get(c).getString(LEARNED, "").split("\n")) {
            if (!x.isEmpty() && !x.equals(word)) words.add(x);
        }
        words.add(0, word);
        while (words.size() > 500) words.remove(words.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String x : words) {
            if (b.length() > 0) b.append('\n');
            b.append(x);
        }
        get(c).edit().putString(LEARNED, b.toString()).apply();
    }

    static void clearLearnedWords(Context c) { get(c).edit().remove(LEARNED).apply(); }

    /** Emojis usados recientemente, el ultimo primero (se guardan separados por espacios). */
    static java.util.List<String> recentEmoji(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String e : get(c).getString(EMOJI_RECENT, "").split(" ")) {
            if (!e.isEmpty()) out.add(e);
        }
        return out;
    }

    static void addRecentEmoji(Context c, String e) {
        if (e == null || e.trim().isEmpty()) return;
        java.util.List<String> recent = recentEmoji(c);
        recent.remove(e);
        recent.add(0, e);
        while (recent.size() > 32) recent.remove(recent.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String r : recent) {
            if (b.length() > 0) b.append(' ');
            b.append(r);
        }
        get(c).edit().putString(EMOJI_RECENT, b.toString()).apply();
    }
}
