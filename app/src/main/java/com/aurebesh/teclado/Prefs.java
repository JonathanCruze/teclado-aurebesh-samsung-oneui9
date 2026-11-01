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
    static boolean strip(Context c) { return get(c).getBoolean(STRIP, false); }
}
