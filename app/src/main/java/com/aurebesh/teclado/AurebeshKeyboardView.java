package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Vista del teclado: distribucion, dibujo con temas, gestion del toque y escritura deslizando el dedo. */
public final class AurebeshKeyboardView extends View {

    interface Listener {
        void onText(String text);
        void onDelete();
        void onEnter();
        void onSwitchKeyboard();        // selector de teclados del sistema (mantener el espacio)
        void onSwitchLanguage();        // globo: siguiente idioma Aurebesh
        void onSuggestion(String word); // toque en una sugerencia de la barra
        void onToolbar(int tool);       // icono de la barra de herramientas (TOOL_*)
        void onHandSide(int mode);      // flecha del modo a una mano: 1 izquierda, 2 derecha
        void onPasteSuggestion();       // chip "Pegar" de lo recien copiado
        /**
         * Palabra reconocida al deslizar el dedo por las letras (ya con mayusculas segun shift/bloqueo) y
         * alternativas para la barra, de mejor a peor (puede estar vacia). Llega en el hilo principal.
         */
        void onGlide(String best, List<String> alternatives);
    }

    static final int CODE_TEXT = 0;
    static final int CODE_SHIFT = -1;
    static final int CODE_MODE = -2;
    static final int CODE_PAGE = -3;      // 1/2 <-> 2/2 en la pagina de signos
    static final int CODE_GAP = -10;      // hueco central del teclado dividido (no se dibuja ni se toca)
    static final int CODE_ENTER = -4;
    static final int CODE_DELETE = -5;
    static final int CODE_SPACE = -6;
    static final int CODE_GLOBE = -7;

    // Barra de herramientas (como la de Samsung Keyboard): a la derecha de las sugerencias, emojis y portapapeles
    static final int TOOL_EMOJI = 1;
    static final int TOOL_CLIPBOARD = 2;
    static final int TOOL_SETTINGS = 3;     // solo en la pagina ?123
    static final int TOOL_HAND = 4;         // modo a una mano (junto al portapapeles)
    static final int TOOL_AI = 5;           // inteligencia, a la izquierda de la barra
    private static final int[] TOOLS_LETTERS = {TOOL_EMOJI, TOOL_CLIPBOARD, TOOL_HAND};
    private static final int[] TOOLS_SYMBOLS = {TOOL_EMOJI, TOOL_CLIPBOARD, TOOL_HAND, TOOL_SETTINGS};

    static final class Key {
        final String output;
        final String glyph;
        final String label;
        final String[] alts;      // alternativas por pulsacion larga; la primera es la predeterminada
        final int code;
        final float weight;
        final RectF rect = new RectF();

        Key(String output, String glyph, String label, String[] alts, int code, float weight) {
            this.output = output; this.glyph = glyph; this.label = label;
            this.alts = alts == null || alts.length == 0 ? null : alts; this.code = code; this.weight = weight;
        }

        Key withFirstAlt(String first) {
            String[] a = new String[(alts == null ? 0 : alts.length) + 1];
            a[0] = first;
            if (alts != null) System.arraycopy(alts, 0, a, 1, alts.length);
            return new Key(output, glyph, label, a, code, weight);
        }
    }

    /** Alternativas de cada letra (acentos y variantes); la n~ y la c con cedilla incluidas. */
    private static final java.util.Map<String, String[]> LETTER_ALTS = new java.util.HashMap<>();
    static {
        LETTER_ALTS.put("a", new String[]{"\u00e1", "\u00e0", "\u00e4", "\u00e2", "\u00e3"});
        LETTER_ALTS.put("e", new String[]{"\u00e9", "\u00e8", "\u00eb", "\u00ea"});
        LETTER_ALTS.put("i", new String[]{"\u00ed", "\u00ec", "\u00ef", "\u00ee"});
        LETTER_ALTS.put("o", new String[]{"\u00f3", "\u00f2", "\u00f6", "\u00f4", "\u00f5"});
        LETTER_ALTS.put("u", new String[]{"\u00fa", "\u00f9", "\u00fc", "\u00fb"});
        LETTER_ALTS.put("n", new String[]{"\u00f1"});
        LETTER_ALTS.put("c", new String[]{"\u00e7"});
    }

    private Listener listener;
    private final List<List<Key>> letters = new ArrayList<>();
    private final List<List<Key>> symbols = new ArrayList<>();    // signos 1/2 (como Samsung Keyboard)
    private final List<List<Key>> symbols2 = new ArrayList<>();   // signos 2/2: digrafos, ¿ ¡ « » y mas simbolos
    private List<List<Key>> current;

    private boolean shift = false;
    private boolean capsLock = false;
    private long lastShiftTap = 0;
    private boolean autoShifted = false;

    private boolean showHints = false;
    private boolean vibrate = true;
    private boolean showStrip = false;
    private boolean useDigraphs = true;
    private boolean numberRow = true;
    private boolean sound = true;
    private float heightScale = 1f;

    // Menu de alternativas abierto por pulsacion larga
    private Key popupKey;
    private String[] popupAlts;
    private int popupIndex;
    private final RectF popupRect = new RectF();
    private float popupCell;
    private String language = "es";        // "es": con tecla n~ y , -> ?; "en": sin tecla n~ y , -> !; "de": QWERTZ con u" o" a" y , -> ;
    private String spaceLabel;             // nombre del idioma cuando hay mas de uno activo
    private boolean languageSwitch = false; // tecla del globo (solo con varios idiomas Aurebesh activos)
    private boolean suggestionBar = false;  // barra de sugerencias (autocorreccion)
    private int handMode = 0;               // 0 normal, 1 izquierda, 2 derecha
    private int splitMode = 0;              // teclado dividido: 0 automatico (pantalla ancha), 1 siempre, 2 nunca
    private boolean split = false;          // estado actual del dividido (dos mitades con hueco, como en los Fold)
    private final List<String> suggestions = new ArrayList<>();
    private final List<RectF> suggestionRects = new ArrayList<>();
    private final List<String> suggestionWords = new ArrayList<>();   // palabra de cada chip dibujado (puede saltarse alguno)
    private String highlighted;             // sugerencia que se aplicara sola (resaltada)
    private boolean toolbar = true;         // iconos sobre las teclas cuando no hay sugerencias
    private final RectF[] toolRects = new RectF[4];
    private final RectF sideRect = new RectF();     // flecha para cambiar de lado en modo a una mano
    private final RectF moreRect = new RectF();     // "..." que muestra los iconos sobre las sugerencias
    private final RectF aiRect = new RectF();       // boton de inteligencia a la izquierda de la barra
    private float stripLeft;                        // donde empiezan los chips (tras el boton de inteligencia)
    private boolean toolsOverChips = false;
    private final RectF pasteRect = new RectF();    // chip "Pegar" de lo recien copiado
    private Bitmap pasteThumb;
    private String pasteLabel;
    private String pasteAction = "Pegar";
    private int toolPressed = -1;
    private int toolPointer = -1;
    private boolean previewEnabled = true;
    private List<Glyphs.Token> stripTokens = new ArrayList<>();
    private Themes.Theme theme = Themes.DEFAULT;
    private CharSequence stripText = "";
    private int enterAction = 0;   // 0 = intro, 1 = buscar, 2 = enviar/ir, 3 = siguiente, 4 = listo

    private final float dp;
    private final float sp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path iconPath = new Path();

    private final SparseArray<Key> pressed = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Key longPressKey;
    private boolean longPressFired;
    private Key repeatKey;

    // ---- Escritura deslizando (glide). Constantes de ajuste del gesto y de la estela, en un solo sitio.
    /** Distancia desde el punto inicial, en anchos de tecla, a la que un toque pasa a ser trazo. */
    static final float GLIDE_SLOP_KEYS = 0.9f;
    /** Velocidad minima (dp/ms) al cruzar esa distancia; mas despacio es deslizar a la tecla vecina. */
    static final float GLIDE_MIN_SPEED = 0.10f;
    /** Si no se cruza la distancia en este tiempo (ms) desde el toque, es un toque para siempre. */
    static final long GLIDE_DETECT_WINDOW = 500;
    /** Longitud minima del trazo al soltar, en anchos de tecla; menos se trata como toque. */
    static final float GLIDE_MIN_LEN_KEYS = 1.2f;
    /** Los puntos a menos de esta distancia (dp) del anterior guardado se descartan. */
    static final float POINT_MIN_DIST_DP = 2f;
    /** Puntos maximos guardados; al llegar se diezma por dos. */
    static final int MAX_POINTS = 2048;
    /** Tiempo (ms) que tarda en desvanecerse cada tramo de la estela, y su grosor maximo (dp). */
    static final long TRAIL_MS = 600;
    static final float TRAIL_WIDTH_DP = 6f;

    private static final int GLIDE_NONE = 0, GLIDE_PENDING = 1, GLIDE_GLIDING = 2;
    private boolean glideEnabled = false;
    private boolean glideTrail = true;
    private int glideState = GLIDE_NONE;
    private int glidePointer = -1;
    private Key glideDownKey;
    private float glideDownX, glideDownY;
    private long glideDownT;
    private float[] strokeX = new float[256], strokeY = new float[256];
    private long[] strokeT = new long[256];
    private int strokeN = 0;
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile SwipeDecoder decoder;          // indice del idioma y la geometria actuales (se rehace si cambian)
    private Future<?> glideTask;                    // ultima tarea de reconocimiento (para pruebas)
    private static ExecutorService glideExecutor;

    public AurebeshKeyboardView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        glyphPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeWidth(2.5f * dp);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailGlowPaint.setStyle(Paint.Style.STROKE);
        trailGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        buildLayouts();
        current = letters;
        applyTheme();
    }

    void setListener(Listener l) { listener = l; }

    void applyPrefs(boolean hints, boolean vibrate, boolean strip, boolean digraphs, String themeId) {
        applyPrefs(hints, vibrate, strip, digraphs, themeId, true);
    }

    void applyPrefs(boolean hints, boolean vibrate, boolean strip, boolean digraphs, String themeId, boolean numbers) {
        applyPrefs(hints, vibrate, strip, digraphs, themeId, numbers, true, 1f);
    }

    void applyPrefs(boolean hints, boolean vibrate, boolean strip, boolean digraphs, String themeId, boolean numbers,
                    boolean sound, float heightScale) {
        this.sound = sound;
        if (this.heightScale != heightScale) {
            this.heightScale = heightScale;
            requestLayout();
        }
        showHints = hints;
        this.vibrate = vibrate;
        if (useDigraphs != digraphs) {
            useDigraphs = digraphs;
            stripTokens = Glyphs.tokenize(stripText.toString().replace('\n', ' '), useDigraphs);
        }
        theme = Themes.byId(themeId);
        applyTheme();
        if (numberRow != numbers) {
            numberRow = numbers;
            List<List<Key>> was = current;
            letters.clear();
            symbols.clear();
            symbols2.clear();
            buildLayouts();
            current = was == symbols ? symbols : was == symbols2 ? symbols2 : letters;
            requestLayout();
        }
        if (showStrip != strip) {
            showStrip = strip;
            requestLayout();
        }
        invalidate();
    }

    /** Sin burbuja de vista previa (campos de contrasena). */
    void setPreviewEnabled(boolean on) { previewEnabled = on; }

    /**
     * Escritura deslizando el dedo por las letras (solo en la pagina de letras; los toques siguen igual).
     * El reconocimiento corre en un hilo aparte y llega por {@link Listener#onGlide}.
     */
    public void setGlideEnabled(boolean on) {
        glideEnabled = on;
        if (!on && glideState == GLIDE_PENDING) resetGlide(true);
        if (on) warmGlide();
    }

    /** Estela del dedo mientras se desliza (por defecto si). */
    public void setGlideTrail(boolean on) {
        glideTrail = on;
        invalidate();
    }

    boolean isGlideEnabled() { return glideEnabled; }

    /** Abre la pantalla ?123 (campos numericos y de telefono). */
    void showSymbols() {
        current = symbols;
        requestLayout();
        invalidate();
    }

    private void applyTheme() {
        setBackgroundColor(theme.bg);
        glyphPaint.setColor(theme.glyph);
        iconPaint.setColor(theme.icon);
        textPaint.setColor(theme.glyph);
        hintPaint.setColor(theme.hint);
        glowPaint.setColor((theme.glow & 0x00FFFFFF) | 0x40000000);
        strokePaint.setColor(theme.keyStroke);
    }

    void setEnterAction(int action) { enterAction = action; invalidate(); }

    /** Distribucion por idioma ("es", "en" o "de"); reconstruye las teclas si cambia. */
    void setLanguage(String lang) {
        String l = "en".equals(lang) ? "en" : "de".equals(lang) ? "de" : "es";
        if (l.equals(language)) return;
        language = l;
        List<List<Key>> was = current;
        letters.clear();
        symbols.clear();
        symbols2.clear();
        buildLayouts();
        current = was == symbols ? symbols : was == symbols2 ? symbols2 : letters;
        requestLayout();
        invalidate();
        warmGlide();
    }

    /** Tecla del globo visible o no; reconstruye la fila inferior. */
    void setLanguageSwitch(boolean on) {
        if (languageSwitch == on) return;
        languageSwitch = on;
        rebuildLayouts();
    }

    void setSuggestionBar(boolean on) {
        if (suggestionBar == on) return;
        suggestionBar = on;
        requestLayout();
        invalidate();
    }

    void setSuggestions(List<String> words) { setSuggestions(words, null); }

    /** Chips de la barra; {@code highlight} es la que se aplicara sola al teclear un separador. */
    void setSuggestions(List<String> words, String highlight) {
        suggestions.clear();
        if (words != null) suggestions.addAll(words);
        if (suggestions.isEmpty()) toolsOverChips = false;
        highlighted = highlight;
        invalidate();
    }

    /** Chip "Pegar" con lo recien copiado (miniatura o fragmento de texto); null lo quita. */
    void setPasteSuggestion(Bitmap thumb, String label, String action) {
        pasteThumb = thumb;
        pasteLabel = label;
        if (action != null) pasteAction = action;
        invalidate();
    }

    boolean hasPasteSuggestion() { return pasteThumb != null || pasteLabel != null; }

    /** Barra de herramientas (emojis y portapapeles) a la derecha de las sugerencias. */
    void setToolbar(boolean on) {
        if (toolbar == on) return;
        toolbar = on;
        requestLayout();
        invalidate();
    }

    /** Teclado dividido en dos mitades (Galaxy Fold desplegado): 0 automatico, 1 siempre, 2 nunca. */
    void setSplitMode(int mode) {
        if (splitMode == mode) return;
        splitMode = mode;
        if (!updateSplit()) { requestLayout(); invalidate(); }
    }

    boolean isSplit() { return split; }

    /** Recalcula si toca dividir (modo, ancho de pantalla, una mano) y reconstruye las filas si cambia. */
    private boolean updateSplit() {
        boolean wide = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        boolean s = handMode == 0 && (splitMode == 1 || (splitMode == 0 && wide));
        if (s == split) return false;
        split = s;
        rebuildLayouts();
        return true;
    }

    /** Modo a una mano: 0 normal, 1 izquierda, 2 derecha. */
    void setHandMode(int mode) {
        if (handMode == mode) return;
        handMode = mode;
        if (updateSplit()) return;
        requestLayout();
        invalidate();
    }

    private void rebuildLayouts() {
        List<List<Key>> was = current;
        letters.clear();
        symbols.clear();
        symbols2.clear();
        buildLayouts();
        current = was == symbols ? symbols : was == symbols2 ? symbols2 : letters;
        requestLayout();
        invalidate();
    }

    void setSpaceLabel(String label) {
        spaceLabel = label;
        invalidate();
    }

    void setStripText(CharSequence text) {
        stripText = text == null ? "" : text;
        stripTokens = Glyphs.tokenize(stripText.toString().replace('\n', ' '), useDigraphs);
        invalidate();
    }

    void resetToLetters() {
        current = letters;
        capsLock = false;
        shift = false;
        requestLayout();
        invalidate();
    }

    void setAutoShift(boolean on) {
        if (capsLock) return;
        if (on) { shift = true; autoShifted = true; }
        else if (autoShifted) { shift = false; autoShifted = false; }
        invalidate();
    }

    // ------------------------------------------------------------------ distribucion

    private static Key letter(String c) { return new Key(c, c, c, LETTER_ALTS.get(c), CODE_TEXT, 1f); }

    /** Signo, con alternativas opcionales por pulsacion larga. */
    private static Key sym(String out, float w, String... alts) {
        return new Key(out, Glyphs.has(out) ? out : null, out, alts, CODE_TEXT, w);
    }
    private static Key special(String label, int code, float w) { return new Key(null, null, label, null, code, w); }

    private void row(List<List<Key>> layout, Key... keys) {
        List<Key> r = new ArrayList<>();
        for (Key k : keys) r.add(k);
        layout.add(r);
    }

    private void lettersRow(List<List<Key>> layout, String chars) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) r.add(letter(String.valueOf(chars.charAt(i))));
        layout.add(r);
    }

    private List<Key> digitsRow() {
        List<Key> nums = new ArrayList<>();
        for (char c = '1'; c <= '9'; c++) nums.add(sym(String.valueOf(c), 1f));
        nums.add(sym("0", 1f));
        return nums;
    }

    /** Tecla de letra del aleman: la vocal con diaeresis (o la eszett en la s) pasa a ser la primera alternativa. */
    private static Key germanLetter(String c) {
        Key k = letter(c);
        String first = "s".equals(c) ? "\u00df" : "a".equals(c) ? "\u00e4" : "o".equals(c) ? "\u00f6" : "u".equals(c) ? "\u00fc" : null;
        if (first == null) return k;
        List<String> a = new ArrayList<>();
        a.add(first);
        if (k.alts != null) for (String x : k.alts) if (!x.equals(first)) a.add(x);
        return new Key(c, c, c, a.toArray(new String[0]), CODE_TEXT, 1f);
    }

    /** Fila de letras del aleman (QWERTZ): las alternativas de a, o, u y s empiezan por ä, ö, ü y ß. */
    private void germanRow(List<List<Key>> layout, String chars) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) r.add(germanLetter(String.valueOf(chars.charAt(i))));
        layout.add(r);
    }

    private void buildLayouts() {
        boolean de = "de".equals(language);     // QWERTZ: qwertzuiopü / asdfghjklöä / yxcvbnm
        if (numberRow) letters.add(digitsRow());
        if (de) germanRow(letters, "qwertzuiop\u00fc"); else lettersRow(letters, "qwertyuiop");
        if (!numberRow) {                         // sin fila de numeros: q..p dan 1..0 en pulsacion larga
            List<Key> top = letters.get(0);
            for (int i = 0; i < 10 && i < top.size(); i++) top.set(i, top.get(i).withFirstAlt(String.valueOf((i + 1) % 10)));
        }
        if (de) germanRow(letters, "asdfghjkl\u00f6\u00e4"); else lettersRow(letters, "asdfghjkl");
        if ("es".equals(language)) letters.get(letters.size() - 1).add(new Key("ñ", "ñ", "ñ", null, CODE_TEXT, 1f));
        List<Key> r3 = new ArrayList<>();
        r3.add(special("", CODE_SHIFT, 1.5f));
        String z = de ? "yxcvbnm" : "zxcvbnm";
        for (int i = 0; i < z.length(); i++) r3.add(de ? germanLetter(String.valueOf(z.charAt(i))) : letter(String.valueOf(z.charAt(i))));
        r3.add(special("", CODE_DELETE, 1.5f));
        letters.add(r3);
        bottomRow(letters, "?123");

        // Signos 1/2: la misma distribucion que Samsung Keyboard (, ; : ? en la cuarta fila, . abajo)
        symbols.add(digitsRow());
        row(symbols, sym("+", 1f, "\u00b1"), sym("\u00d7", 1f), sym("\u00f7", 1f), sym("=", 1f, "\u2260"), sym("/", 1f, "\\"),
                sym("_", 1f), sym("<", 1f, "\u2264"), sym(">", 1f, "\u2265"), sym("[", 1f, "{"), sym("]", 1f, "}"));
        row(symbols, sym("!", 1f, "\u00a1"), sym("@", 1f), sym("#", 1f), sym("$", 1f, "\u20ac"), sym("%", 1f, "\u2030"),
                sym("^", 1f), sym("&", 1f), sym("*", 1f, "\u2022"), sym("(", 1f), sym(")", 1f));
        row(symbols, special("1/2", CODE_PAGE, 1.5f), sym("-", 1f, "\u2013"), sym("'", 1f, "\u2018"), sym("\"", 1f, "\u201c"),
                sym(":", 1f), sym(";", 1f), sym(",", 1f), sym("?", 1f, "\u00bf"), special("", CODE_DELETE, 1.5f));
        bottomRow(symbols, "abc");

        // Signos 2/2: digrafos Aurebesh, signos del espanol y simbolos menos frecuentes
        symbols2.add(digitsRow());
        List<Key> dig = new ArrayList<>();
        for (String d : Glyphs.DIGRAPHS) dig.add(new Key(d, d, d, null, CODE_TEXT, 1.25f));
        symbols2.add(dig);
        row(symbols2, sym("\u00bf", 1f), sym("\u00a1", 1f), sym("\u00ab", 1f), sym("\u00bb", 1f), sym("{", 1f), sym("}", 1f),
                sym("|", 1f), sym("\\", 1f), sym("~", 1f), sym("\u2022", 1f));
        row(symbols2, special("2/2", CODE_PAGE, 1.5f), sym("\u00a3", 1f), sym("\u20ac", 1f), sym("\u00a5", 1f), sym("\u00b0", 1f),
                sym("\u00a9", 1f), sym("\u00ae", 1f), sym("\u2122", 1f), special("", CODE_DELETE, 1.5f));
        bottomRow(symbols2, "abc");
    }

    private void bottomRow(List<List<Key>> layout, String modeLabel) {
        List<Key> r = new ArrayList<>();
        r.add(special(modeLabel, CODE_MODE, 1.5f));
        r.add(sym(",", 1f, "es".equals(language) ? "\u00bf" : "de".equals(language) ? ";" : "!"));
        float space = 5f;
        if (languageSwitch) { r.add(special("", CODE_GLOBE, 1f)); space -= 1f; }
        if (split) {                                       // dos mitades de 5,5 unidades, como Samsung Keyboard en el Fold
            r.add(special("", CODE_SPACE, space - 2f));    // ?123 (1,5) + , (1) [+ globo (1)] + espacio = 5,5
            r.add(special("", CODE_GAP, 0f));
            r.add(special("", CODE_SPACE, 3f));            // espacio (3) + . (1) + intro (1,5) = 5,5
        } else {
            r.add(special("", CODE_SPACE, space));
        }
        r.add(sym(".", 1f, "?"));
        r.add(special("", CODE_ENTER, 1.5f));
        layout.add(r);
    }

    /** Tecla de funcion del teclado actual con ese codigo (para pruebas). */
    Key keyFor(int code) {
        for (List<Key> row : current) for (Key k : row) if (k.code == code) return k;
        return null;
    }

    /** Tecla del teclado actual que escribe {@code output} (para pruebas). */
    Key keyFor(String output) {
        for (List<Key> row : current) for (Key k : row) if (output.equals(k.output)) return k;
        return null;
    }

    // ------------------------------------------------------------------ medidas

    private float stripHeight() { return toolbar || showStrip || suggestionBar ? 40 * dp : 4 * dp; }
    private boolean isDigitRow(List<List<Key>> layout, int ri) {
        return ri == 0 && (numberRow || layout == symbols || layout == symbols2);
    }

    /** En apaisado las filas son mas bajas para no tapar media pantalla. */
    private float rowHeight(List<List<Key>> layout, int ri) {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (land) return (isDigitRow(layout, ri) ? 30 : 38) * dp * Math.min(heightScale, 1f);
        return (isDigitRow(layout, ri) ? 46 : 58) * dp * heightScale;
    }

    private float keysHeight() {
        float h = 0;
        for (int i = 0; i < current.size(); i++) h += rowHeight(current, i);
        return h;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updateSplit();
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, (int) (stripHeight() + keysHeight() + 6 * dp));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutKeys(letters);
        layoutKeys(symbols);
        layoutKeys(symbols2);
        warmGlide();
    }

    private void layoutKeys(List<List<Key>> layout) {
        if (split) { layoutKeysSplit(layout); return; }
        float full = getWidth();
        float w = handMode == 0 ? full : full * 0.78f;        // a una mano: 78 % del ancho
        float offset = handMode == 2 ? full - w : 0;
        float pad = 4 * dp;
        float y = stripHeight();
        for (int ri = 0; ri < layout.size(); ri++) {
            List<Key> row = layout.get(ri);
            float rh = rowHeight(layout, ri);
            float total = 0;
            for (Key k : row) total += k.weight;
            float unit = (w - 2 * pad) / Math.max(10f, total);
            float x = offset + pad + ((w - 2 * pad) - unit * total) / 2f;
            for (Key k : row) {
                float kw = unit * k.weight;
                k.rect.set(x, y, x + kw, y + rh);
                x += kw;
            }
            y += rh;
        }
    }

    /**
     * Teclado dividido con la misma geometria que Samsung Keyboard en un Galaxy Z Fold desplegado:
     * cada mitad es una caja de 5,5 teclas (el 38 % del ancho) pegada a su borde; la fila q-t / y-p se
     * apoya en el borde exterior, la fila central (a-g / h-ñ) se corre media tecla hacia el hueco, y en
     * la tercera fila mayusculas y borrar (1,5 teclas) ocupan los bordes con z-v bajo s-f y b-m bajo j-l.
     * Las filas con una tecla CODE_GAP se parten ahi; el resto donde la suma de anchos llega a la mitad.
     */
    private void layoutKeysSplit(List<List<Key>> layout) {
        float full = getWidth();
        float pad = 4 * dp;
        float box = (full - 2 * pad) * 0.38f;
        float unit = box / 5.5f;
        float x0 = pad, x1 = full - pad - box;
        float y = stripHeight();
        int homeRow = layout == letters ? (numberRow ? 2 : 1) : -1;
        for (int ri = 0; ri < layout.size(); ri++) {
            List<Key> row = layout.get(ri);
            float rh = rowHeight(layout, ri);
            float total = 0;
            int cut = -1;
            for (int i = 0; i < row.size(); i++) {
                if (row.get(i).code == CODE_GAP) cut = i; else total += row.get(i).weight;
            }
            int leftEnd;
            if (cut >= 0) {
                leftEnd = cut;
            } else {                                        // corte donde la suma de anchos llega a la mitad
                float acc = 0;
                leftEnd = row.size();
                for (int i = 0; i < row.size(); i++) {
                    acc += row.get(i).weight;
                    if (acc >= total / 2f - 0.01f) { leftEnd = i + 1; break; }
                }
            }
            float totalL = 0, totalR = 0;
            for (int i = 0; i < row.size(); i++) {
                Key k = row.get(i);
                if (k.code == CODE_GAP) { k.rect.setEmpty(); continue; }
                if (i < leftEnd) totalL += k.weight; else totalR += k.weight;
            }
            float unitL = totalL > 0 ? Math.min(unit, box / totalL) : unit;
            float unitR = totalR > 0 ? Math.min(unit, box / totalR) : unit;
            boolean home = ri == homeRow;
            float xl = home ? x0 + box - unitL * totalL : x0;          // central: pegada al hueco; resto: al borde
            float xr = home ? x1 : x1 + box - unitR * totalR;
            for (int i = 0; i < row.size(); i++) {
                Key k = row.get(i);
                if (k.code == CODE_GAP) continue;
                if (i < leftEnd) {
                    float kw = unitL * k.weight;
                    k.rect.set(xl, y, xl + kw, y + rh);
                    xl += kw;
                } else {
                    float kw = unitR * k.weight;
                    k.rect.set(xr, y, xr + kw, y + rh);
                    xr += kw;
                }
            }
            y += rh;
        }
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas c) {
        if (toolbar || showStrip || suggestionBar) drawStrip(c);
        float inset = 3 * dp;
        float radius = theme.radiusDp * dp;
        for (int ri = 0; ri < current.size(); ri++) {
            for (Key k : current.get(ri)) {
                if (k.code == CODE_GAP) continue;
                tmp.set(k.rect.left + inset, k.rect.top + inset, k.rect.right - inset, k.rect.bottom - inset);
                boolean isPressed = pressed.indexOfValue(k) >= 0 || k == repeatKey;
                boolean function = k.code != CODE_TEXT && k.code != CODE_SPACE;
                boolean inkFilled = k.code == CODE_ENTER || (k.code == CODE_SHIFT && (shift || capsLock));
                Integer bg;
                if (isDigitRow(current, ri)) bg = null;       // fila de numeros: franja sin tecla
                else if (inkFilled) bg = theme.accent;
                else if (function) bg = theme.specialBg;
                else bg = theme.keyBg;
                if (isPressed) bg = theme.pressed;
                if (bg != null) {
                    if (theme.shadow && !isPressed && !inkFilled) {
                        fill.setColor((theme.glyph & 0x00FFFFFF) | 0x1A000000);   // sombra de 1 px
                        c.drawRoundRect(tmp.left, tmp.top + dp, tmp.right, tmp.bottom + dp, radius, radius, fill);
                    }
                    fill.setColor(bg);
                    c.drawRoundRect(tmp, radius, radius, fill);
                    if (theme.keyStroke != 0 && !inkFilled) c.drawRoundRect(tmp, radius, radius, strokePaint);
                }
                drawKeyFace(c, k, tmp, isDigitRow(current, ri));
            }
        }
        drawSideSwitch(c);
        if (glideTrail && strokeN > 1 && glideState != GLIDE_PENDING) drawTrail(c);
        for (int i = 0; i < pressed.size(); i++) {
            Key k = pressed.valueAt(i);
            if (glideState == GLIDE_GLIDING && pressed.keyAt(i) == glidePointer) continue;   // sin burbuja al trazar
            if (previewEnabled && k != null && k.code == CODE_TEXT && k != popupKey) drawPreview(c, k);
        }
        if (popupKey != null) drawPopup(c);
    }

    /**
     * Estela del trazo: un tramo por par de puntos, cada uno con la transparencia y el grosor de su edad
     * (la cola se afina y se desvanece en TRAIL_MS). Con halo en los temas con brillo. Se vuelve a dibujar
     * en cada fotograma mientras quede algun tramo visible.
     */
    private void drawTrail(Canvas c) {
        long now = SystemClock.uptimeMillis();
        int ink = theme.accent == theme.keyBg ? theme.glyph : theme.accent;
        boolean any = false;
        for (int i = strokeN - 1; i >= 1; i--) {
            long age = now - strokeT[i];
            if (age > TRAIL_MS) break;                       // los anteriores son mas viejos todavia
            if (age < 0) age = 0;
            float k = 1f - age / (float) TRAIL_MS;
            int alpha = (int) (255 * k);
            float width = TRAIL_WIDTH_DP * dp * (0.35f + 0.65f * k);
            if (theme.glow != 0) {
                trailGlowPaint.setColor((theme.glow & 0x00FFFFFF) | ((alpha / 3) << 24));
                trailGlowPaint.setStrokeWidth(width * 2.2f);
                c.drawLine(strokeX[i - 1], strokeY[i - 1], strokeX[i], strokeY[i], trailGlowPaint);
            }
            trailPaint.setColor((ink & 0x00FFFFFF) | (alpha << 24));
            trailPaint.setStrokeWidth(width);
            c.drawLine(strokeX[i - 1], strokeY[i - 1], strokeX[i], strokeY[i], trailPaint);
            any = true;
        }
        if (any) postInvalidateOnAnimation();
        else if (glideState == GLIDE_NONE) strokeN = 0;      // ya se desvanecio del todo
    }

    private void drawKeyFace(Canvas c, Key k, RectF r, boolean digitRow) {
        float cx = r.centerX();
        float cy = r.centerY();
        switch (k.code) {
            case CODE_TEXT:
                if (k.glyph != null) {
                    float h = r.height() * (digitRow ? 0.44f : 0.50f);
                    if (theme.glow != 0) Glyphs.drawCentered(c, k.glyph, cx, cy, h, r.width() * 0.74f, glowPaint);
                    Glyphs.drawCentered(c, k.glyph, cx, cy, h, r.width() * 0.74f, glyphPaint);
                    if (showHints) {
                        hintPaint.setTextSize(9 * sp);
                        hintPaint.setTextAlign(Paint.Align.LEFT);
                        String hint = shiftActive() ? k.label.toUpperCase() : k.label;
                        c.drawText(hint, r.left + 4 * dp, r.top + 10 * dp, hintPaint);
                        hintPaint.setTextAlign(Paint.Align.RIGHT);
                    }
                } else {
                    textPaint.setTextSize(19 * dp);
                    c.drawText(k.label, cx, cy + 7 * dp, textPaint);
                }
                boolean symbolKey = current != letters && k.glyph != null && !digitRow
                        && k.label.length() == 1 && !Character.isLetterOrDigit(k.label.charAt(0));
                if (symbolKey) {                                 // leyenda del signo en la pagina ?123
                    hintPaint.setTextSize(9 * sp);
                    c.drawText(k.label, r.right - 4 * dp, r.top + 10 * dp, hintPaint);
                }
                if (k.alts != null && !digitRow) {
                    String first = k.alts[0];
                    boolean digit = first.length() == 1 && Character.isDigit(first.charAt(0));
                    if (showHints || digit) {                    // los numeros ocultos siempre se insinuan
                        hintPaint.setTextSize(9 * sp);
                        if (symbolKey) {
                            hintPaint.setTextAlign(Paint.Align.LEFT);
                            c.drawText(first, r.left + 4 * dp, r.top + 10 * dp, hintPaint);
                            hintPaint.setTextAlign(Paint.Align.RIGHT);
                        } else {
                            c.drawText(first, r.right - 4 * dp, r.top + 10 * dp, hintPaint);
                        }
                    }
                }
                break;
            case CODE_MODE:
            case CODE_PAGE:
                // "?123" / "abc" / "1/2" escritos en Aurebesh pequeño
                drawMiniWord(c, k.label, cx, cy, 10 * dp);
                break;
            case CODE_SHIFT: drawShift(c, cx, cy); break;
            case CODE_DELETE: drawDelete(c, cx, cy); break;
            case CODE_GLOBE: drawGlobe(c, cx, cy); break;
            case CODE_ENTER: drawEnter(c, cx, cy); break;
            case CODE_SPACE:
                if (spaceLabel != null) {
                    hintPaint.setTextSize(11 * sp);
                    hintPaint.setTextAlign(Paint.Align.CENTER);
                    c.drawText(spaceLabel, cx, cy + 4 * dp, hintPaint);
                    hintPaint.setTextAlign(Paint.Align.RIGHT);
                }
                break;
        }
    }

    private void drawMiniWord(Canvas c, String word, float cx, float cy, float h) {
        List<Glyphs.Token> tokens = Glyphs.tokenize(word, false);
        float total = 0;
        for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, textPaint);
        float x = cx - total / 2f;
        for (Glyphs.Token t : tokens) x += Glyphs.drawToken(c, t, x, cy - h / 2f, h, glyphPaint, textPaint);
    }

    private void drawShift(Canvas c, float cx, float cy) {
        float s = 9 * dp;
        iconPath.reset();
        iconPath.moveTo(cx, cy - s);
        iconPath.lineTo(cx + s, cy);
        iconPath.lineTo(cx + s * 0.45f, cy);
        iconPath.lineTo(cx + s * 0.45f, cy + s * 0.9f);
        iconPath.lineTo(cx - s * 0.45f, cy + s * 0.9f);
        iconPath.lineTo(cx - s * 0.45f, cy);
        iconPath.lineTo(cx - s, cy);
        iconPath.close();
        boolean on = shift || capsLock;
        if (on) {
            fill.setColor(theme.onAccent);
            c.drawPath(iconPath, fill);
            if (capsLock) c.drawRect(cx - s * 0.45f, cy + s * 1.2f, cx + s * 0.45f, cy + s * 1.5f, fill);
        } else {
            iconPaint.setStrokeWidth(2.8f * dp);
            c.drawPath(iconPath, iconPaint);
        }
    }

    private void drawDelete(Canvas c, float cx, float cy) {
        float w = 11 * dp, h = 7 * dp;
        iconPath.reset();
        iconPath.moveTo(cx - w, cy);
        iconPath.lineTo(cx - w * 0.45f, cy - h);
        iconPath.lineTo(cx + w, cy - h);
        iconPath.lineTo(cx + w, cy + h);
        iconPath.lineTo(cx - w * 0.45f, cy + h);
        iconPath.close();
        iconPaint.setStrokeWidth(2.6f * dp);
        c.drawPath(iconPath, iconPaint);
        float x = cx + w * 0.2f, k = 2.6f * dp;
        c.drawLine(x - k, cy - k, x + k, cy + k, iconPaint);
        c.drawLine(x - k, cy + k, x + k, cy - k, iconPaint);
    }

    private void drawGlobe(Canvas c, float cx, float cy) {
        float r = 8 * dp;
        iconPaint.setStrokeWidth(2.2f * dp);
        c.drawCircle(cx, cy, r, iconPaint);                      // globo: circulo, ecuador y un meridiano
        c.drawLine(cx - r, cy, cx + r, cy, iconPaint);
        tmp.set(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r);
        c.drawOval(tmp, iconPaint);
    }

    /** Carita (emojis): circulo, dos ojos y sonrisa. */
    private void drawSmiley(Canvas c, float cx, float cy) {
        iconPaint.setStrokeWidth(2.2f * dp);
        c.drawCircle(cx, cy, 8 * dp, iconPaint);
        fill.setColor(theme.icon);
        c.drawCircle(cx - 3 * dp, cy - 2.6f * dp, 1.5f * dp, fill);
        c.drawCircle(cx + 3 * dp, cy - 2.6f * dp, 1.5f * dp, fill);
        tmp.set(cx - 4.6f * dp, cy - 3.2f * dp, cx + 4.6f * dp, cy + 4.6f * dp);
        c.drawArc(tmp, 25, 130, false, iconPaint);
    }

    /** Portapapeles: tablilla con pinza. */
    private void drawPaste(Canvas c, float cx, float cy) {
        iconPaint.setStrokeWidth(2.4f * dp);
        float w = 7 * dp, h = 9 * dp;
        tmp.set(cx - w, cy - h + 2 * dp, cx + w, cy + h);
        c.drawRoundRect(tmp, 2.5f * dp, 2.5f * dp, iconPaint);
        tmp.set(cx - 3.5f * dp, cy - h - dp, cx + 3.5f * dp, cy - h + 4 * dp);
        fill.setColor(theme.icon);
        c.drawRoundRect(tmp, 1.5f * dp, 1.5f * dp, fill);
        c.drawLine(cx - 3.5f * dp, cy + 0.5f * dp, cx + 3.5f * dp, cy + 0.5f * dp, iconPaint);
        c.drawLine(cx - 3.5f * dp, cy + 4.5f * dp, cx + 1.5f * dp, cy + 4.5f * dp, iconPaint);
    }

    private void drawEnter(Canvas c, float cx, float cy) {
        iconPaint.setStrokeWidth(3f * dp);
        iconPaint.setColor(theme.onAccent);
        try { drawEnterIcon(c, cx, cy); } finally { iconPaint.setColor(theme.icon); }
    }

    private void drawEnterIcon(Canvas c, float cx, float cy) {
        float s = 7 * dp;
        iconPath.reset();
        switch (enterAction) {
            case 1: // buscar
                c.drawCircle(cx - 2 * dp, cy - 2 * dp, s * 0.75f, iconPaint);
                c.drawLine(cx + 2 * dp, cy + 2 * dp, cx + s, cy + s, iconPaint);
                break;
            case 2: // enviar / ir
                iconPath.moveTo(cx - s, cy - s); iconPath.lineTo(cx + s, cy);
                iconPath.lineTo(cx - s, cy + s); iconPath.lineTo(cx - s * 0.5f, cy); iconPath.close();
                fill.setColor(theme.onAccent);
                c.drawPath(iconPath, fill);
                break;
            case 3: // siguiente
                c.drawLine(cx - s, cy, cx + s, cy, iconPaint);
                c.drawLine(cx + s * 0.2f, cy - s * 0.7f, cx + s, cy, iconPaint);
                c.drawLine(cx + s * 0.2f, cy + s * 0.7f, cx + s, cy, iconPaint);
                break;
            case 0: // intro
                iconPath.moveTo(cx + s, cy - s * 0.8f); iconPath.lineTo(cx + s, cy + s * 0.2f);
                iconPath.lineTo(cx - s, cy + s * 0.2f);
                c.drawPath(iconPath, iconPaint);
                c.drawLine(cx - s * 0.2f, cy - s * 0.5f, cx - s, cy + s * 0.2f, iconPaint);
                c.drawLine(cx - s * 0.2f, cy + s * 0.9f, cx - s, cy + s * 0.2f, iconPaint);
                break;
            default: // listo (check)
                iconPath.moveTo(cx - s, cy);
                iconPath.lineTo(cx - s * 0.3f, cy + s * 0.7f);
                iconPath.lineTo(cx + s, cy - s * 0.7f);
                c.drawPath(iconPath, iconPaint);
        }
    }

    /** Menu de alternativas sobre la tecla mantenida; la opcion bajo el dedo va resaltada. */
    private void drawPopup(Canvas c) {
        float r = 10 * dp;
        fill.setColor(0x40000000);
        c.drawRoundRect(popupRect.left, popupRect.top + 3 * dp, popupRect.right, popupRect.bottom + 3 * dp, r, r, fill);
        fill.setColor(theme.preview);
        c.drawRoundRect(popupRect, r, r, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(popupRect, r, r, strokePaint);
        for (int i = 0; i < popupAlts.length; i++) {
            float left = popupRect.left + i * popupCell;
            float cx = left + popupCell / 2f, cy = popupRect.centerY();
            boolean sel = i == popupIndex;
            if (sel) {
                tmp.set(left + 3 * dp, popupRect.top + 3 * dp, left + popupCell - 3 * dp, popupRect.bottom - 3 * dp);
                fill.setColor(theme.accent);
                c.drawRoundRect(tmp, 8 * dp, 8 * dp, fill);
            }
            String a = popupAlts[i];
            String shown = shiftActive() && current == letters ? a.toUpperCase() : a;
            if (Glyphs.has(a)) {
                glyphPaint.setColor(sel ? theme.onAccent : theme.previewText);
                Glyphs.drawCentered(c, a, cx, cy - 6 * dp, popupRect.height() * 0.42f, popupCell * 0.7f, glyphPaint);
                glyphPaint.setColor(theme.glyph);
                textPaint.setColor(sel ? theme.onAccent : theme.previewLatin);
                textPaint.setTextSize(10 * sp);
                c.drawText(shown, cx, popupRect.bottom - 7 * dp, textPaint);
            } else {
                textPaint.setColor(sel ? theme.onAccent : theme.previewText);
                textPaint.setTextSize(22 * dp);
                c.drawText(shown, cx, cy + 8 * dp, textPaint);
            }
            textPaint.setColor(theme.glyph);
        }
    }

    private void openPopup(Key k) {
        popupKey = k;
        popupAlts = k.alts;
        popupIndex = 0;
        popupCell = Math.max(k.rect.width(), 46 * dp);
        float h = 64 * dp;
        float total = popupCell * popupAlts.length;
        float left = k.rect.centerX() - popupCell / 2f;              // la primera opcion, sobre la tecla
        left = Math.max(2 * dp, Math.min(getWidth() - total - 2 * dp, left));
        popupRect.set(left, Math.max(0, k.rect.top - h - 2 * dp), left + total, Math.max(0, k.rect.top - h - 2 * dp) + h);
        invalidate();
    }

    private void commitPopup() {
        if (popupKey == null) return;
        String out = popupAlts[popupIndex];
        if (shiftActive() && current == letters) out = out.toUpperCase();
        if (listener != null) listener.onText(out);
        if (shift && !capsLock) { shift = false; autoShifted = false; }
        closePopup();
    }

    private void closePopup() {
        popupKey = null;
        popupAlts = null;
    }

    /** Celda i del menu abierto (para pruebas). */
    RectF popupCellRect(int i) {
        if (popupKey == null) return null;
        float left = popupRect.left + i * popupCell;
        return new RectF(left, popupRect.top, left + popupCell, popupRect.bottom);
    }

    private void drawPreview(Canvas c, Key k) {
        float w = Math.max(k.rect.width() * 1.3f, 56 * dp);
        float h = 74 * dp;
        float left = Math.max(2 * dp, Math.min(getWidth() - w - 2 * dp, k.rect.centerX() - w / 2f));
        float top = Math.max(0, k.rect.top - h + 6 * dp);
        tmp.set(left, top, left + w, top + h - 10 * dp);
        fill.setColor(0x40000000);
        c.drawRoundRect(tmp.left, tmp.top + 3 * dp, tmp.right, tmp.bottom + 3 * dp, 10 * dp, 10 * dp, fill);
        fill.setColor(theme.preview);
        c.drawRoundRect(tmp, 10 * dp, 10 * dp, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(tmp, 10 * dp, 10 * dp, strokePaint);
        if (k.glyph != null) {
            if (theme.glow != 0) Glyphs.drawCentered(c, k.glyph, tmp.centerX(), tmp.centerY() - 7 * dp, tmp.height() * 0.46f, tmp.width() * 0.72f, glowPaint);
            glyphPaint.setColor(theme.previewText);
            Glyphs.drawCentered(c, k.glyph, tmp.centerX(), tmp.centerY() - 7 * dp, tmp.height() * 0.46f, tmp.width() * 0.72f, glyphPaint);
            glyphPaint.setColor(theme.glyph);
            textPaint.setColor(theme.previewLatin);
            textPaint.setTextSize(11 * sp);
            String latin = shiftActive() ? k.label.toUpperCase() : k.label;
            c.drawText(latin, tmp.centerX(), tmp.bottom - 6 * dp, textPaint);
            textPaint.setColor(theme.glyph);
        } else {
            textPaint.setColor(theme.previewText);
            textPaint.setTextSize(26 * dp);
            c.drawText(k.label, tmp.centerX(), tmp.centerY() + 9 * dp, textPaint);
            textPaint.setColor(theme.glyph);
        }
    }

    private void drawStrip(Canvas c) {
        float h = 13.5f * dp;
        float top = (stripHeight() - h) / 2f;
        suggestionRects.clear();
        suggestionWords.clear();
        for (int i = 0; i < toolRects.length; i++) toolRects[i] = null;
        float full = getWidth(), hw = handMode == 0 ? full : full * 0.78f, off = handMode == 2 ? full - hw : 0;
        float right = off + hw - 12 * dp;
        boolean chips = !suggestions.isEmpty();
        moreRect.setEmpty();
        pasteRect.setEmpty();
        aiRect.setEmpty();
        stripLeft = toolbar ? drawAiButton(c) : off + 12 * dp;
        if (toolbar && (!chips || toolsOverChips)) right = drawToolbar(c);
        else if (toolbar) right = drawMore(c);
        if (chips) {
            drawSuggestions(c, h, top, right);
        } else {
            float left = stripLeft;
            if (hasPasteSuggestion()) left = drawPasteChip(c, right);
            if (showStrip) drawTranslation(c, h, top, left, right);
        }
        if (theme.keyStroke != 0) c.drawRect(0, stripHeight() - dp, getWidth(), stripHeight(), strokePaint);
    }

    /** Boton de inteligencia (estrella de cuatro puntas) a la izquierda de la barra. Devuelve donde empiezan los chips. */
    private float drawAiButton(Canvas c) {
        float off = handMode == 2 ? getWidth() * 0.22f : 0;    // modo a una mano derecha: la barra acompana a las teclas
        float cy = stripHeight() / 2f, cx = off + 6 * dp + 22 * dp;
        aiRect.set(off + 6 * dp, 0, off + 6 * dp + 44 * dp, stripHeight());
        fill.setColor(toolPressed == 9 ? theme.pressed : theme.specialBg);
        c.drawCircle(cx, cy, 16 * dp, fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, 16 * dp, strokePaint);
        fill.setColor(theme.icon);
        star(c, cx - 1.5f * dp, cy + 1.5f * dp, 7.5f * dp);
        star(c, cx + 5.5f * dp, cy - 5.5f * dp, 3.2f * dp);
        return aiRect.right + 4 * dp;
    }

    private void star(Canvas c, float cx, float cy, float r) {
        float k = r * 0.24f;
        iconPath.reset();
        iconPath.moveTo(cx, cy - r);
        iconPath.lineTo(cx + k, cy - k);
        iconPath.lineTo(cx + r, cy);
        iconPath.lineTo(cx + k, cy + k);
        iconPath.lineTo(cx, cy + r);
        iconPath.lineTo(cx - k, cy + k);
        iconPath.lineTo(cx - r, cy);
        iconPath.lineTo(cx - k, cy - k);
        iconPath.close();
        c.drawPath(iconPath, fill);
    }

    /** Boton "..." a la derecha mientras hay sugerencias: muestra los iconos de la barra. */
    private float drawMore(Canvas c) {
        float full = getWidth(), hw = handMode == 0 ? full : full * 0.78f, off = handMode == 2 ? full - hw : 0;
        float cy = stripHeight() / 2f, cx = off + hw - 6 * dp - 22 * dp;
        moreRect.set(off + hw - 50 * dp, 0, off + hw, stripHeight());
        fill.setColor(theme.specialBg);
        c.drawCircle(cx, cy, 16 * dp, fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, 16 * dp, strokePaint);
        fill.setColor(theme.icon);
        for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * 5 * dp, cy, 1.8f * dp, fill);
        return moreRect.left - 4 * dp;
    }

    /** Chip con lo recien copiado (miniatura o texto) y la palabra "Pegar". Devuelve su borde derecho. */
    private float drawPasteChip(Canvas c, float right) {
        float h = stripHeight() - 10 * dp, top = 5 * dp, x = stripLeft;
        textPaint.setTextSize(13 * sp);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        float actionW = textPaint.measureText(pasteAction);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float thumbW = pasteThumb != null ? h - 6 * dp : 0;
        float maxText = Math.max(0, right - x - actionW - thumbW - 40 * dp);
        String label = pasteLabel == null ? "" : TextUtils.ellipsize(pasteLabel.replace('\n', ' '), new android.text.TextPaint(textPaint), Math.min(maxText, 160 * dp), TextUtils.TruncateAt.END).toString();
        float labelW = label.isEmpty() ? 0 : textPaint.measureText(label) + 6 * dp;
        float w = 12 * dp + (thumbW > 0 ? thumbW + 8 * dp : 0) + labelW + actionW + 12 * dp;
        pasteRect.set(x, top, Math.min(right, x + w), top + h);
        fill.setColor(theme.keyBg);
        c.drawRoundRect(pasteRect, h / 2f, h / 2f, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(pasteRect, h / 2f, h / 2f, strokePaint);
        c.save();
        c.clipRect(pasteRect);
        float cx = pasteRect.left + 12 * dp, cy = pasteRect.centerY();
        if (pasteThumb != null) {
            tmp.set(cx, top + 3 * dp, cx + thumbW, top + 3 * dp + thumbW);
            c.save();
            iconPath.reset();
            iconPath.addRoundRect(tmp, 4 * dp, 4 * dp, Path.Direction.CW);
            c.clipPath(iconPath);
            c.drawBitmap(pasteThumb, null, tmp, fill);
            c.restore();
            cx += thumbW + 8 * dp;
        }
        if (!label.isEmpty()) {
            textPaint.setColor(theme.hint);
            c.drawText(label, cx, cy + 4.5f * dp, textPaint);
            cx += labelW;
        }
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(theme.accent == theme.glyph ? theme.glyph : theme.accent);
        c.drawText(pasteAction, cx, cy + 4.5f * dp, textPaint);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        textPaint.setColor(theme.glyph);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.restore();
        return pasteRect.right + 10 * dp;
    }

    private int[] tools() { return current != letters ? TOOLS_SYMBOLS : TOOLS_LETTERS; }

    /** Iconos redondos a la derecha: emojis, portapapeles, una mano y, en ?123, ajustes. Devuelve el limite izquierdo. */
    private float drawToolbar(Canvas c) {
        int[] ids = tools();
        float full = getWidth(), hw = handMode == 0 ? full : full * 0.78f, off = handMode == 2 ? full - hw : 0;
        float cell = 44 * dp, cy = stripHeight() / 2f;
        float x = off + hw - 6 * dp - cell * ids.length;
        float left = x;
        for (int i = 0; i < toolRects.length; i++) toolRects[i] = null;
        for (int i = 0; i < ids.length; i++) {
            toolRects[i] = new RectF(x, 0, x + cell, stripHeight());
            float cx = x + cell / 2f;
            boolean active = ids[i] == TOOL_HAND && handMode != 0;
            fill.setColor(toolPressed == i ? theme.pressed : active ? theme.accent : theme.specialBg);
            c.drawCircle(cx, cy, 16 * dp, fill);
            if (theme.keyStroke != 0 && !active) c.drawCircle(cx, cy, 16 * dp, strokePaint);
            if (active) iconPaint.setColor(theme.onAccent);
            switch (ids[i]) {
                case TOOL_EMOJI: drawSmiley(c, cx, cy); break;
                case TOOL_CLIPBOARD: drawPaste(c, cx, cy); break;
                case TOOL_HAND: drawHand(c, cx, cy, active); break;
                default: drawGear(c, cx, cy); break;
            }
            if (active) iconPaint.setColor(theme.icon);
            x += cell;
        }
        return left - 6 * dp;
    }

    /** Modo a una mano: teclado estrecho con una flecha de desplazamiento. */
    private void drawHand(Canvas c, float cx, float cy, boolean active) {
        iconPaint.setStrokeWidth(2.2f * dp);
        tmp.set(cx - 2 * dp, cy - 6 * dp, cx + 9 * dp, cy + 6 * dp);
        c.drawRoundRect(tmp, 2 * dp, 2 * dp, iconPaint);
        fill.setColor(active ? theme.onAccent : theme.icon);
        for (int i = 0; i < 2; i++) for (int j = 0; j < 3; j++) c.drawCircle(cx + 1 * dp + j * 2.5f * dp, cy - 2.5f * dp + i * 2.8f * dp, 0.8f * dp, fill);
        c.drawLine(cx - 9 * dp, cy, cx - 4.5f * dp, cy, iconPaint);
        c.drawLine(cx - 9 * dp, cy, cx - 6.5f * dp, cy - 2.5f * dp, iconPaint);
        c.drawLine(cx - 9 * dp, cy, cx - 6.5f * dp, cy + 2.5f * dp, iconPaint);
    }

    /** En modo a una mano, boton en el margen libre para pasar el teclado al otro lado. */
    private void drawSideSwitch(Canvas c) {
        sideRect.setEmpty();
        if (handMode == 0 || current.isEmpty()) return;
        float full = getWidth(), w = full * 0.78f;
        float marginLeft = handMode == 2 ? 0 : w, marginRight = handMode == 2 ? full - w : full;
        float cx = (marginLeft + marginRight) / 2f, cy = stripHeight() + keysHeight() / 2f;
        float r = 20 * dp;
        sideRect.set(cx - r, cy - r, cx + r, cy + r);
        fill.setColor(theme.specialBg);
        c.drawCircle(cx, cy, r, fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, r, strokePaint);
        iconPaint.setStrokeWidth(2.6f * dp);
        float d = handMode == 2 ? -1 : 1;           // apunta hacia el margen vacio
        float s = 7 * dp;
        c.drawLine(cx - d * s, cy, cx + d * s, cy, iconPaint);
        c.drawLine(cx + d * s, cy, cx + d * s * 0.35f, cy - s * 0.65f, iconPaint);
        c.drawLine(cx + d * s, cy, cx + d * s * 0.35f, cy + s * 0.65f, iconPaint);
    }

    /** Engranaje (ajustes): corona gruesa con ocho dientes y eje hueco. */
    private void drawGear(Canvas c, float cx, float cy) {
        iconPaint.setStrokeWidth(3.4f * dp);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + Math.PI / 8;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * 5.5f * dp, cy + dy * 5.5f * dp, cx + dx * 8.6f * dp, cy + dy * 8.6f * dp, iconPaint);
        }
        iconPaint.setStrokeWidth(3.2f * dp);
        c.drawCircle(cx, cy, 5.2f * dp, iconPaint);
        fill.setColor(theme.icon);
        c.drawCircle(cx, cy, 2.1f * dp, fill);
    }

    /** Sugerencias del corrector como chips, de izquierda a derecha hasta {@code right}; la que se aplicara sola va resaltada. */
    private void drawSuggestions(Canvas c, float h, float top, float right) {
        int n = suggestions.size();
        float[] widths = new float[n];
        boolean[] emojiAt = new boolean[n];
        List<List<Glyphs.Token>> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String word = suggestions.get(i);
            emojiAt[i] = isEmoji(word);
            List<Glyphs.Token> tk = showHints || emojiAt[i] ? null : Glyphs.tokenize(word, useDigraphs);
            tokens.add(tk);
            float tw = 0;
            if (tk != null) {
                for (Glyphs.Token t : tk) tw += Glyphs.tokenWidth(t, h, textPaint);
            } else {
                textPaint.setTextSize(emojiAt[i] ? 19 * sp : 14 * sp);
                tw = textPaint.measureText(word);
            }
            widths[i] = tw + (emojiAt[i] ? 16 : 22) * dp;
        }
        // Si no cabe todo, se sacrifican chips de palabra por el final (nunca el primero ni el resaltado) para que los emojis se vean
        boolean[] show = new boolean[n];
        java.util.Arrays.fill(show, true);
        float avail = right - 2 * dp - stripLeft;
        boolean anyEmoji = false;
        for (int i = 0; i < n; i++) anyEmoji |= emojiAt[i];
        while (true) {
            float total = 0;
            int shown = 0;
            for (int i = 0; i < n; i++) if (show[i]) { total += widths[i] + (shown > 0 ? 5 * dp : 0); shown++; }
            if (total <= avail || !anyEmoji) break;
            int drop = -1;
            for (int i = n - 1; i > 0; i--) {
                if (show[i] && !emojiAt[i] && !(highlighted != null && highlighted.equals(suggestions.get(i)))) { drop = i; break; }
            }
            if (drop < 0) break;
            show[drop] = false;
        }
        float x = stripLeft;
        for (int i = 0; i < n; i++) {
            if (!show[i]) continue;
            String word = suggestions.get(i);
            boolean emoji = emojiAt[i];
            RectF cell = new RectF(x, 5 * dp, x + widths[i], stripHeight() - 5 * dp);
            if (cell.right > right - 2 * dp) break;
            boolean hl = highlighted != null && highlighted.equals(word);
            fill.setColor(hl ? theme.accent : theme.specialBg);
            c.drawRoundRect(cell, 9 * dp, 9 * dp, fill);
            if (theme.keyStroke != 0 && !hl) c.drawRoundRect(cell, 9 * dp, 9 * dp, strokePaint);
            int ink = hl ? theme.onAccent : theme.glyph;
            glyphPaint.setColor(ink);
            textPaint.setColor(ink);
            textPaint.setTextAlign(Paint.Align.LEFT);
            float gx = cell.left + (emoji ? 8 : 11) * dp;
            List<Glyphs.Token> tk = tokens.get(i);
            if (tk != null) {
                for (Glyphs.Token t : tk) gx += Glyphs.drawToken(c, t, gx, top, h, glyphPaint, textPaint);
            } else {
                textPaint.setTextSize(emoji ? 19 * sp : 14 * sp);
                c.drawText(word, gx, cell.centerY() + (emoji ? 7 : 5) * dp, textPaint);
            }
            textPaint.setTextAlign(Paint.Align.CENTER);
            glyphPaint.setColor(theme.glyph);
            textPaint.setColor(theme.glyph);
            suggestionRects.add(cell);
            suggestionWords.add(word);
            x = cell.right + 5 * dp;
        }
    }

    /** Una sugerencia sin letras ni digitos es un emoji: se dibuja con la fuente del sistema. */
    static boolean isEmoji(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return false;
        return true;
    }

    /** Lo escrito, en Aurebesh, entre {@code left} y {@code right}; si no cabe se ve el final. */
    private void drawTranslation(Canvas c, float h, float top, float left, float right) {
        List<Glyphs.Token> tokens = stripTokens;
        float total = 0;
        for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, textPaint);
        float x = left;
        float maxW = right - left;
        if (maxW <= 0) return;
        c.save();
        c.clipRect(x, 0, x + maxW, stripHeight());
        int start = 0;
        if (total > maxW) {                                   // si no cabe, se omiten tokens enteros del principio
            float over = total - maxW, skipped = 0;
            while (start < tokens.size() && skipped < over) {
                skipped += Glyphs.tokenWidth(tokens.get(start), h, textPaint);
                start++;
            }
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = start; i < tokens.size(); i++) x += Glyphs.drawToken(c, tokens.get(i), x, top, h, glyphPaint, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.restore();
    }

    // ------------------------------------------------------------------ toque

    private Key findKey(float x, float y) {
        Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Key> row : current) {
            for (Key k : row) {
                if (k.code == CODE_GAP || k.rect.isEmpty()) continue;
                if (k.rect.contains(x, y)) return k;
                float dx = Math.max(0, Math.max(k.rect.left - x, x - k.rect.right));
                float dy = Math.max(0, Math.max(k.rect.top - y, y - k.rect.bottom));
                float d = dx * dx + dy * dy;
                if (d < bestDist) { bestDist = d; best = k; }
            }
        }
        return bestDist < (20 * dp) * (20 * dp) ? best : null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (glideState == GLIDE_GLIDING) finishGlide();          // un segundo dedo cierra el trazo en curso
                else if (glideState == GLIDE_PENDING) resetGlide(true);  // y el toque pendiente se suelta como toque normal
                for (int i = pressed.size() - 1; i >= 0; i--) {
                    Key prev = pressed.valueAt(i);
                    if (prev != null && prev == popupKey) commitPopup();
                    else if (prev != null && prev.code != CODE_DELETE && !(prev == longPressKey && longPressFired)) release(prev);
                    pressed.removeAt(i);
                }
                cancelTimers();
                if (e.getY(idx) < stripHeight()) {                       // barra: sugerencias, pegar o iconos
                    float tx = e.getX(idx), ty = e.getY(idx);
                    if (!aiRect.isEmpty() && aiRect.contains(tx, ty) && listener != null) {
                        feedback(special("", CODE_SPACE, 1f));
                        listener.onToolbar(TOOL_AI);
                        return true;
                    }
                    if (!pasteRect.isEmpty() && pasteRect.contains(tx, ty) && listener != null) {
                        feedback(special("", CODE_SPACE, 1f));
                        listener.onPasteSuggestion();
                        return true;
                    }
                    if (!moreRect.isEmpty() && moreRect.contains(tx, ty)) {
                        feedback(special("", CODE_SPACE, 1f));
                        toolsOverChips = true;
                        invalidate();
                        return true;
                    }
                    for (int i = 0; i < suggestionRects.size(); i++) {
                        if (suggestionRects.get(i).contains(tx, ty) && listener != null) {
                            feedback(special("", CODE_SPACE, 1f));
                            listener.onSuggestion(suggestionWords.get(i));
                            return true;
                        }
                    }
                    for (int i = 0; i < toolRects.length; i++) {
                        if (toolRects[i] != null && toolRects[i].contains(tx, ty)) {
                            toolPressed = i;
                            toolPointer = id;
                            feedback(special("", CODE_SPACE, 1f));
                            invalidate();
                            return true;
                        }
                    }
                    return true;
                }
                if (!sideRect.isEmpty() && sideRect.contains(e.getX(idx), e.getY(idx))) {   // cambiar de lado
                    feedback(special("", CODE_SPACE, 1f));
                    if (listener != null) listener.onHandSide(handMode == 2 ? 1 : 2);
                    return true;
                }
                Key k = findKey(e.getX(idx), e.getY(idx));
                if (k == null) return true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                pressed.put(id, k);
                feedback(k);
                if (k.code == CODE_DELETE) {
                    if (listener != null) listener.onDelete();
                    startRepeat(k);
                } else {
                    startLongPress(k);
                }
                if (glideEnabled && glidePointer < 0 && glideKey(k)) {   // puede ser el principio de un trazo
                    glideState = GLIDE_PENDING;
                    glidePointer = id;
                    glideDownKey = k;
                    glideDownX = e.getX(idx);
                    glideDownY = e.getY(idx);
                    glideDownT = e.getEventTime();
                    strokeN = 0;
                    strokeAdd(glideDownX, glideDownY, glideDownT);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    Key old = pressed.get(pid);
                    if (old == null) continue;
                    if (pid == glidePointer && glideState != GLIDE_NONE) {
                        glideMove(e, i);                              // acumula puntos y decide toque o trazo
                        if (glideState == GLIDE_GLIDING) {
                            Key under = findKey(e.getX(i), e.getY(i));
                            if (under != null && under != old) pressed.put(pid, under);   // resalta la tecla bajo el dedo
                            postInvalidateOnAnimation();
                            continue;
                        }
                    }
                    if (popupKey != null && old == popupKey) {       // deslizar por el menu
                        int ni = (int) Math.floor((e.getX(i) - popupRect.left) / popupCell);
                        ni = Math.max(0, Math.min(popupAlts.length - 1, ni));
                        if (ni != popupIndex) { popupIndex = ni; invalidate(); }
                        continue;
                    }
                    if (old.code == CODE_DELETE || old.code == CODE_SPACE) continue;
                    Key k = findKey(e.getX(i), e.getY(i));
                    if (k != null && k != old && !longPressFired) {
                        pressed.put(pid, k);
                        startLongPress(k);
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (toolPressed >= 0 && id == toolPointer) {
                    int t = toolPressed;
                    toolPressed = -1;
                    toolPointer = -1;
                    invalidate();
                    int[] ids = tools();
                    if (toolRects[t] != null && t < ids.length && toolRects[t].contains(e.getX(idx), e.getY(idx)) && listener != null) {
                        listener.onToolbar(ids[t]);
                    }
                    return true;
                }
                if (id == glidePointer && glideState == GLIDE_GLIDING) {
                    strokeAdd(e.getX(idx), e.getY(idx), e.getEventTime());
                    finishGlide();
                    invalidate();
                    return true;
                }
                if (id == glidePointer) resetGlide(true);            // pendiente: fue un toque normal
                Key k = pressed.get(id);
                pressed.remove(id);
                if (k != null && k == popupKey) {
                    commitPopup();
                    cancelTimers();
                    invalidate();
                    return true;
                }
                boolean consumed = k != null && k == longPressKey && longPressFired;
                cancelTimers();
                if (k != null && !consumed && k.code != CODE_DELETE) release(k);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                toolPressed = -1;
                toolPointer = -1;
                resetGlide(true);
                pressed.clear();
                closePopup();
                cancelTimers();
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    // ------------------------------------------------------------------ escritura deslizando

    /** Solo las teclas de una letra de la pagina de letras pueden empezar o terminar un trazo. */
    private boolean glideKey(Key k) {
        return k != null && current == letters && k.code == CODE_TEXT && k.output != null
                && k.output.length() == 1 && Character.isLetter(k.output.charAt(0));
    }

    private void strokeAdd(float x, float y, long t) {
        if (strokeN > 0) {
            float dx = x - strokeX[strokeN - 1], dy = y - strokeY[strokeN - 1], min = POINT_MIN_DIST_DP * dp;
            if (dx * dx + dy * dy < min * min) return;
        }
        if (strokeN == strokeX.length) {
            if (strokeN >= MAX_POINTS) {                          // diezmar por dos conservando los tiempos
                int m = 0;
                for (int i = 0; i < strokeN; i += 2) { strokeX[m] = strokeX[i]; strokeY[m] = strokeY[i]; strokeT[m] = strokeT[i]; m++; }
                strokeN = m;
            } else {
                int cap = strokeX.length * 2;
                strokeX = java.util.Arrays.copyOf(strokeX, cap);
                strokeY = java.util.Arrays.copyOf(strokeY, cap);
                strokeT = java.util.Arrays.copyOf(strokeT, cap);
            }
        }
        strokeX[strokeN] = x; strokeY[strokeN] = y; strokeT[strokeN] = t;
        strokeN++;
    }

    /** Puntos del movimiento (con el historial del evento) y paso de PENDIENTE a TRAZO o a toque. */
    private void glideMove(MotionEvent e, int i) {
        int h = e.getHistorySize();
        for (int j = 0; j < h; j++) strokeAdd(e.getHistoricalX(i, j), e.getHistoricalY(i, j), e.getHistoricalEventTime(j));
        float x = e.getX(i), y = e.getY(i);
        long t = e.getEventTime();
        strokeAdd(x, y, t);
        if (glideState != GLIDE_PENDING) return;
        if (popupKey != null || longPressFired) { resetGlide(true); return; }   // ya se abrio el menu de alternativas
        float dx = x - glideDownX, dy = y - glideDownY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        long dt = Math.max(1, t - glideDownT);
        if (dist > GLIDE_SLOP_KEYS * glideDownKey.rect.width() && dist / dt > GLIDE_MIN_SPEED * dp) {
            glideState = GLIDE_GLIDING;
            handler.removeCallbacks(longPressRunnable);          // sin menu de alternativas mientras se traza
            longPressKey = null;
            longPressFired = false;
        } else if (dt > GLIDE_DETECT_WINDOW) {
            resetGlide(true);                                    // toque (y deslizar a la vecina, como siempre)
        }
    }

    private void resetGlide(boolean clearStroke) {
        glideState = GLIDE_NONE;
        glidePointer = -1;
        glideDownKey = null;
        if (clearStroke) strokeN = 0;
    }

    /**
     * Fin del trazo: recorta los puntos finales fuera de las letras (acabar en el espacio es habitual),
     * cancela si acaba en borrar, trata como toque los trazos demasiado cortos y manda el resto al
     * reconocedor en segundo plano. La estela sigue en pantalla hasta desvanecerse.
     */
    private void finishGlide() {
        int id = glidePointer;
        Key downKey = glideDownKey;
        pressed.remove(id);
        resetGlide(false);
        cancelTimers();
        int n = strokeN;
        Key last = n > 0 ? findKey(strokeX[n - 1], strokeY[n - 1]) : null;
        if (downKey == null || n < 2 || (last != null && last.code == CODE_DELETE)) return;
        while (n > 2 && !glideKey(findKey(strokeX[n - 1], strokeY[n - 1]))) n--;
        float len = 0;
        for (int i = 1; i < n; i++) {
            float dx = strokeX[i] - strokeX[i - 1], dy = strokeY[i] - strokeY[i - 1];
            len += (float) Math.sqrt(dx * dx + dy * dy);
        }
        Key under = findKey(strokeX[n - 1], strokeY[n - 1]);
        Key tapKey = glideKey(under) ? under : downKey;         // la tecla bajo el dedo al soltar, si es de letra
        if (len < GLIDE_MIN_LEN_KEYS * downKey.rect.width()) {   // un tiron corto: toque en la tecla bajo el dedo
            strokeN = 0;
            release(tapKey);
            return;
        }
        int caseMode = capsLock ? 2 : shift ? 1 : 0;
        if (shift && !capsLock) { shift = false; autoShifted = false; }
        SwipeDecoder.Geometry geo = glideGeometry();
        if (geo == null || listener == null) return;
        final float[] x = java.util.Arrays.copyOf(strokeX, n), y = java.util.Arrays.copyOf(strokeY, n);
        final long[] t = java.util.Arrays.copyOf(strokeT, n);
        boolean shortStroke = len < 2.5f * downKey.rect.width();
        glideTask = glideExecutor().submit(new GlideJob(getContext().getApplicationContext(), language, geo, x, y, t, caseMode,
                shortStroke ? tapKey : null));
    }

    /**
     * Prepara el reconocedor en segundo plano (diccionario e indice) para que el primer trazo tras abrir el
     * teclado, girar o cambiar de idioma no espere.
     */
    private void warmGlide() {
        if (!glideEnabled) return;
        final SwipeDecoder.Geometry g = glideGeometry();
        if (g == null) return;
        SwipeDecoder dec = decoder;
        if (dec != null && dec.geometry().sameAs(g) && dec.language().equals(language)) return;
        final Context ctx = getContext().getApplicationContext();
        final String lang = language;
        glideExecutor().submit(new Runnable() {
            @Override public void run() {
                try {
                    Dictionary d = Dictionary.get(ctx, lang);
                    SwipeDecoder cur = decoder;
                    if (cur == null || !cur.matches(d, g)) decoder = new SwipeDecoder(d, g);
                } catch (Exception ignored) {
                    // sin diccionario no hay deslizado; se reintenta con el primer trazo
                }
            }
        });
    }

    /** Reconocimiento en el hilo de trabajo: carga el diccionario, (re)construye el indice si hace falta y decodifica. */
    private final class GlideJob implements Runnable {
        private final Context ctx;
        private final String lang;
        private final SwipeDecoder.Geometry geo;
        private final float[] x, y;
        private final long[] t;
        private final int caseMode;
        private final Key fallback;          // tecla que se escribe si un trazo corto no da ninguna palabra

        GlideJob(Context ctx, String lang, SwipeDecoder.Geometry geo, float[] x, float[] y, long[] t, int caseMode, Key fallback) {
            this.ctx = ctx; this.lang = lang; this.geo = geo; this.x = x; this.y = y; this.t = t; this.caseMode = caseMode;
            this.fallback = fallback;
        }

        @Override public void run() {
            List<String> words;
            try {
                Dictionary d = Dictionary.get(ctx, lang);
                SwipeDecoder dec = decoder;
                if (dec == null || !dec.matches(d, geo)) {
                    dec = new SwipeDecoder(d, geo);
                    decoder = dec;
                }
                words = dec.decode(x, y, t, x.length);
            } catch (Exception ex) {
                words = Collections.emptyList();            // sin diccionario no hay deslizado; el teclado sigue
            }
            final List<String> result = words;
            handler.post(new Runnable() {
                @Override public void run() { deliverGlide(result, caseMode, fallback); }
            });
        }
    }

    private void deliverGlide(List<String> words, int caseMode, Key fallback) {
        if (words.isEmpty()) {
            if (fallback != null && listener != null) {           // trazo corto sin palabra: cuenta como toque
                if (caseMode == 1) { shift = true; autoShifted = false; }
                release(fallback);
                invalidate();
                return;
            }
            if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);   // nada reconocido
            return;
        }
        if (listener == null) return;
        List<String> cased = new ArrayList<>(words.size());
        for (String w : words) cased.add(applyCase(w, caseMode));
        listener.onGlide(cased.get(0), new ArrayList<>(cased.subList(1, cased.size())));
    }

    /** 0 tal cual, 1 inicial mayuscula (shift), 2 todo mayusculas (bloqueo). */
    private static String applyCase(String w, int caseMode) {
        if (caseMode == 2) return w.toUpperCase(java.util.Locale.ROOT);
        if (caseMode == 1 && !w.isEmpty()) return w.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + w.substring(1);
        return w;
    }

    private static synchronized ExecutorService glideExecutor() {
        if (glideExecutor == null) {
            glideExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "glide");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return glideExecutor;
    }

    /** Ultima tarea de reconocimiento enviada (para pruebas: esperar a que termine). */
    Future<?> lastGlideTask() { return glideTask; }

    /**
     * Instantanea de las teclas de letras (sin la fila de numeros ni la inferior) con sus centros en px y el
     * ancho y alto de una tecla, para el reconocedor; null si la vista aun no tiene medidas.
     */
    SwipeDecoder.Geometry glideGeometry() {
        if (getWidth() == 0 || letters.isEmpty()) return null;
        List<Key> ks = new ArrayList<>();
        float keyW = 0, keyH = 0;
        for (int ri = 0; ri < letters.size() - 1; ri++) {
            if (isDigitRow(letters, ri)) continue;
            if (keyH == 0) keyH = rowHeight(letters, ri);
            for (Key k : letters.get(ri)) {
                if (k.code != CODE_TEXT || k.output == null || k.output.length() != 1 || !Character.isLetter(k.output.charAt(0))) continue;
                ks.add(k);
                if (keyW == 0 && k.weight == 1f) keyW = k.rect.width();
            }
        }
        if (ks.isEmpty() || keyW <= 0) return null;
        char[] letter = new char[ks.size()];
        float[] cx = new float[ks.size()], cy = new float[ks.size()];
        for (int i = 0; i < ks.size(); i++) {
            Key k = ks.get(i);
            letter[i] = k.output.charAt(0);
            cx[i] = k.rect.centerX();
            cy[i] = k.rect.centerY();
        }
        return new SwipeDecoder.Geometry(letter, cx, cy, keyW, keyH);
    }

    private boolean shiftActive() { return shift || capsLock; }

    private void release(Key k) {
        if (listener == null) return;
        switch (k.code) {
            case CODE_TEXT: {
                String out = shiftActive() && current == letters ? k.output.toUpperCase() : k.output;
                listener.onText(out);
                if (shift && !capsLock) { shift = false; autoShifted = false; }
                break;
            }
            case CODE_SPACE: listener.onText(" "); break;
            case CODE_ENTER: listener.onEnter(); break;
            case CODE_GLOBE: listener.onSwitchLanguage(); break;
            case CODE_SHIFT: {
                long now = System.currentTimeMillis();
                if (capsLock) { capsLock = false; shift = false; }
                else if (now - lastShiftTap < 350) { capsLock = true; shift = true; }
                else shift = !shift;
                autoShifted = false;
                lastShiftTap = now;
                break;
            }
            case CODE_MODE:
                current = current == letters ? symbols : letters;
                requestLayout();
                break;
            case CODE_PAGE:
                current = current == symbols ? symbols2 : symbols;
                requestLayout();
                break;
        }
        invalidate();
    }

    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            Key k = longPressKey;
            if (k == null || listener == null) return;
            if (k.code == CODE_SPACE) {
                longPressFired = true;
                feedback(k);
                listener.onSwitchKeyboard();
            } else if (k.code == CODE_TEXT && k.alts != null) {
                longPressFired = true;
                feedback(k);
                openPopup(k);
                if (glideState == GLIDE_PENDING) resetGlide(true);   // con el menu abierto ya no hay trazo
            }
            invalidate();
        }
    };

    private final Runnable repeatRunnable = new Runnable() {
        @Override public void run() {
            if (repeatKey == null || listener == null) return;
            listener.onDelete();
            handler.postDelayed(this, 60);
        }
    };

    private void startLongPress(Key k) {
        handler.removeCallbacks(longPressRunnable);
        longPressKey = k;
        longPressFired = false;
        handler.postDelayed(longPressRunnable, k.code == CODE_SPACE ? 600 : 380);
    }

    private void startRepeat(Key k) {
        repeatKey = k;
        handler.postDelayed(repeatRunnable, 420);
    }

    private void cancelTimers() {
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(repeatRunnable);
        longPressKey = null;
        longPressFired = false;
        repeatKey = null;
    }

    private void feedback(Key k) {
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (sound) KeySound.get(getContext()).play(k.code);   // clic del sistema o sonido personalizado
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelTimers();
        closePopup();
        resetGlide(true);
        pressed.clear();
    }
}
