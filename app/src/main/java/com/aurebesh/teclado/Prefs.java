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
    static final String KEYBOARD_LANG = "keyboard_lang"; // idioma del teclado elegido en la app ("es"/"en"/"de"); vacio = el subtipo activo
    // --- 1.10: gestos, escritura inteligente, tamano y posicion, toque y respuesta
    static final String SPACE_SLIDE = "space_slide";     // deslizar sobre el espacio: "auto" (idioma si hay varios, si no cursor), "cursor", "lang", "none"
    static final String AUTO_REPLACE = "auto_replace";   // sustitucion automatica al teclear separador (los chips siguen con AUTOCORRECT)
    static final String AUTO_CAPS = "auto_caps";         // mayuscula automatica al inicio de frase
    static final String PREVIEW = "key_preview";         // burbuja al pulsar
    static final String LONGPRESS = "long_press";        // retardo de pulsacion larga: "short" 250, "medium" 380, "long" 550 ms
    static final String VIBRATION = "vibration_level";   // "system", "soft", "medium", "strong"
    static final String SOUND_VOLUME = "sound_volume";   // 0-100
    static final String HEIGHT_PCT = "keyboard_height_pct"; // 70-140 (sustituye a HEIGHT; por pantalla en plegables)
    static final String BOTTOM_DP = "bottom_dp";         // margen inferior en dp, 0-40 (por pantalla en plegables)
    static final String SPLIT_WIDTH = "split_width";     // ancho de cada mitad del dividido: "narrow" 0,33, "normal" 0,375, "wide" 0,42
    static final String SHORTCUTS = "text_shortcuts";    // atajos de texto: lineas "clave\tvalor"
    static final String CANDIDATES = "candidate_words";  // palabras desconocidas vistas: lineas "palabra n" (se aprenden al repetirse)
    static final String THEME_AUTO = "auto";             // valor de THEME: sigue el modo oscuro del telefono
    private static final String LEGACY_DARK = "dark_theme";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean hints(Context c) { return get(c).getBoolean(HINTS, false); }
    static boolean vibrate(Context c) { return get(c).getBoolean(VIBRATE, true); }
    static boolean digraphs(Context c) { return get(c).getBoolean(DIGRAPHS, true); }
    /** Tema efectivo; "auto" se resuelve con el modo oscuro del telefono (Estelar de dia, Noche de noche). */
    static String theme(Context c) {
        String t = themePref(c);
        if (THEME_AUTO.equals(t)) {
            int night = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES ? Themes.NOCHE.id : Themes.DEFAULT.id;
        }
        return t;
    }
    /** Valor guardado del tema, incluido "auto". */
    static String themePref(Context c) {
        String t = get(c).getString(THEME, null);
        if (t == null) t = get(c).getBoolean(LEGACY_DARK, false) ? Themes.NOCHE.id : Themes.DEFAULT.id;
        return t;
    }
    /** Preferencias de la pantalla de ajustes que no afectan al teclado (secciones abiertas...). */
    static SharedPreferences ui(Context c) {
        return c.getSharedPreferences(NAME + "_ui", Context.MODE_PRIVATE);
    }
    /** Clave distinta para la pantalla interior de un plegable (>= 600 dp) y para la tapa o un telefono. */
    static String perScreen(Context c, String base) {
        return DeviceInfo.wideScreen(c) ? base + "_wide" : base;
    }
    static boolean numbers(Context c) { return get(c).getBoolean(NUMBERS, true); }
    static boolean sound(Context c) { return get(c).getBoolean(SOUND, true); }
    static String height(Context c) { return get(c).getString(HEIGHT, "normal"); }
    /** Alto del teclado en % (70-140), por pantalla en plegables; migra los tamanos antiguos (compacto 85... extra 130). */
    static int heightPct(Context c) {
        SharedPreferences p = get(c);
        String key = perScreen(c, HEIGHT_PCT);
        if (p.contains(key)) return clamp(p.getInt(key, 100), 70, 140);
        if (p.contains(HEIGHT_PCT)) return clamp(p.getInt(HEIGHT_PCT, 100), 70, 140);
        switch (height(c)) {
            case "compact": return 85;
            case "large": return 115;
            case "xlarge": return 130;
            default: return 100;
        }
    }
    static void setHeightPct(Context c, int pct) { get(c).edit().putInt(perScreen(c, HEIGHT_PCT), clamp(pct, 70, 140)).apply(); }
    static float heightScale(Context c) { return heightPct(c) / 100f; }
    /** Margen inferior bajo la ultima fila, en dp (0-40), por pantalla en plegables. */
    static int bottomDp(Context c) {
        SharedPreferences p = get(c);
        String key = perScreen(c, BOTTOM_DP);
        return clamp(p.getInt(p.contains(key) ? key : BOTTOM_DP, 0), 0, 40);
    }
    static void setBottomDp(Context c, int dp) { get(c).edit().putInt(perScreen(c, BOTTOM_DP), clamp(dp, 0, 40)).apply(); }
    static String splitWidth(Context c) { return get(c).getString(SPLIT_WIDTH, "normal"); }
    /** Fraccion del ancho que ocupa cada mitad del teclado dividido. */
    static float splitBox(Context c) {
        switch (splitWidth(c)) {
            case "narrow": return 0.33f;
            case "wide": return 0.42f;
            default: return 0.375f;
        }
    }
    static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
    /** Texto predictivo (chips de la barra). */
    static boolean autocorrect(Context c) { return get(c).getBoolean(AUTOCORRECT, true); }
    /** Sustitucion automatica al teclear espacio o puntuacion. */
    static boolean autoReplace(Context c) { return get(c).getBoolean(AUTO_REPLACE, true); }
    static boolean autoCaps(Context c) { return get(c).getBoolean(AUTO_CAPS, true); }
    static boolean preview(Context c) { return get(c).getBoolean(PREVIEW, true); }
    static String spaceSlide(Context c) { return get(c).getString(SPACE_SLIDE, "auto"); }
    static String longPress(Context c) { return get(c).getString(LONGPRESS, "medium"); }
    static int longPressMs(Context c) {
        switch (longPress(c)) {
            case "short": return 250;
            case "long": return 550;
            default: return 380;
        }
    }
    static String vibration(Context c) { return get(c).getString(VIBRATION, "system"); }
    /** Nivel de vibracion: 0 sistema, 1 suave, 2 media, 3 fuerte. */
    static int vibrationLevel(Context c) {
        switch (vibration(c)) {
            case "soft": return 1;
            case "medium": return 2;
            case "strong": return 3;
            default: return 0;
        }
    }
    static int soundVolume(Context c) { return clamp(get(c).getInt(SOUND_VOLUME, 100), 0, 100); }
    static String hand(Context c) { return get(c).getString(perScreen(c, HAND), "none"); }
    /** Alterna el modo a una mano desde la barra: apagado <-> ultimo lado (derecha por defecto). */
    static void toggleHand(Context c) {
        String h = hand(c);
        SharedPreferences.Editor e = get(c).edit();
        if ("none".equals(h)) e.putString(perScreen(c, HAND), get(c).getString(HAND_LAST, "right"));
        else e.putString(HAND_LAST, h).putString(perScreen(c, HAND), "none");
        e.apply();
    }
    static void setHand(Context c, String side) { get(c).edit().putString(perScreen(c, HAND), side).putString(HAND_LAST, side).apply(); }

    /** Claves de preferencias que cambian el teclado en pantalla (el IME ignora el resto). */
    static boolean affectsKeyboard(String key) {
        if (key == null) return false;
        if (key.startsWith("sec_")) return false;
        switch (key) {
            case HINTS: case VIBRATE: case DIGRAPHS: case STRIP: case THEME: case NUMBERS: case SOUND: case HEIGHT:
            case AUTOCORRECT: case HAND: case TOOLBAR: case LEARN: case DOUBLE_SPACE: case GLIDE: case SCREENSHOTS:
            case SPLIT: case CUSTOM_SOUND: case LEARNED: case APP_LANG: case KEYBOARD_LANG:
            case SPACE_SLIDE: case AUTO_REPLACE: case AUTO_CAPS: case PREVIEW: case LONGPRESS: case VIBRATION:
            case SOUND_VOLUME: case HEIGHT_PCT: case BOTTOM_DP: case SPLIT_WIDTH: case SHORTCUTS:
            case AiModels.MODEL: case AiModels.MODE: case AiModels.REMOTE_URL: case AiModels.REMOTE_MODEL:
                return true;
            default:
                return key.endsWith("_wide") && affectsKeyboard(key.substring(0, key.length() - 5));
        }
    }

    // ------------------------------------------------------------------ atajos de texto

    /** Atajos de texto (clave en minusculas -> expansion), en el orden guardado. */
    static java.util.LinkedHashMap<String, String> shortcuts(Context c) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : get(c).getString(SHORTCUTS, "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) continue;
            out.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return out;
    }

    static void addShortcut(Context c, String key, String expansion) {
        if (key == null || expansion == null) return;
        String k = key.trim().toLowerCase(java.util.Locale.ROOT);
        String v = expansion.trim();
        if (k.isEmpty() || v.isEmpty() || k.length() > 20 || v.length() > 500) return;
        java.util.LinkedHashMap<String, String> all = shortcuts(c);
        all.remove(k);
        all.put(k, v);
        saveShortcuts(c, all);
    }

    static void removeShortcut(Context c, String key) {
        java.util.LinkedHashMap<String, String> all = shortcuts(c);
        if (all.remove(key) != null) saveShortcuts(c, all);
    }

    private static void saveShortcuts(Context c, java.util.Map<String, String> all) {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : all.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append('\t').append(e.getValue().replace('\n', ' ').replace('\t', ' '));
        }
        get(c).edit().putString(SHORTCUTS, b.toString()).apply();
    }

    // ------------------------------------------------------------------ palabras candidatas (aprender al repetirse)

    /** Suma una aparicion de una palabra desconocida y devuelve cuantas lleva (tope de 200 palabras, las mas recientes). */
    static int bumpCandidate(Context c, String w) {
        if (w == null) return 0;
        String word = w.trim().toLowerCase(java.util.Locale.ROOT);
        if (word.isEmpty() || word.length() > 30) return 0;
        java.util.LinkedHashMap<String, Integer> all = candidates(c);
        Integer n = all.remove(word);
        int count = (n == null ? 0 : n) + 1;
        all.put(word, count);
        while (all.size() > 200) all.remove(all.keySet().iterator().next());
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : all.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append(' ').append(e.getValue());
        }
        get(c).edit().putString(CANDIDATES, b.toString()).apply();
        return count;
    }

    static java.util.LinkedHashMap<String, Integer> candidates(Context c) {
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String line : get(c).getString(CANDIDATES, "").split("\n")) {
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) continue;
            try { out.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void removeCandidate(Context c, String w) {
        java.util.LinkedHashMap<String, Integer> all = candidates(c);
        if (all.remove(w) == null) return;
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : all.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append(' ').append(e.getValue());
        }
        get(c).edit().putString(CANDIDATES, b.toString()).apply();
    }

    static void clearCandidates(Context c) { get(c).edit().remove(CANDIDATES).apply(); }

    /** Quita una palabra aprendida (diccionario personal editable). */
    static void removeLearnedWord(Context c, String w) {
        if (w == null) return;
        String word = w.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> words = new java.util.ArrayList<>();
        boolean found = false;
        for (String x : get(c).getString(LEARNED, "").split("\n")) {
            if (x.isEmpty()) continue;
            if (x.equals(word)) { found = true; continue; }
            words.add(x);
        }
        if (!found) return;
        StringBuilder b = new StringBuilder();
        for (String x : words) {
            if (b.length() > 0) b.append('\n');
            b.append(x);
        }
        get(c).edit().putString(LEARNED, b.toString()).apply();
    }
    static int handMode(Context c) {
        switch (hand(c)) {
            case "left": return 1;
            case "right": return 2;
            default: return 0;
        }
    }
    static String appLang(Context c) { return get(c).getString(APP_LANG, "system"); }
    /** Idioma del teclado fijado desde la app, o null para seguir el subtipo activo del sistema. */
    static String keyboardLang(Context c) {
        String l = get(c).getString(KEYBOARD_LANG, "");
        return "es".equals(l) || "en".equals(l) || "de".equals(l) ? l : null;
    }
    /** Fija a la vez el idioma de la app y el del teclado (iconos de la parte superior de la app). */
    static void setLanguage(Context c, String lang) {
        get(c).edit().putString(APP_LANG, lang).putString(KEYBOARD_LANG, lang).apply();
    }
    static void clearKeyboardLang(Context c) { get(c).edit().remove(KEYBOARD_LANG).apply(); }
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
