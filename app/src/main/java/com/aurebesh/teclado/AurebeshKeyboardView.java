package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Vista del teclado: distribucion, dibujo con temas y gestion del toque. */
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
    }

    static final int CODE_TEXT = 0;
    static final int CODE_SHIFT = -1;
    static final int CODE_MODE = -2;
    static final int CODE_PAGE = -3;      // 1/2 <-> 2/2 en la pagina de signos
    static final int CODE_ENTER = -4;
    static final int CODE_DELETE = -5;
    static final int CODE_SPACE = -6;
    static final int CODE_GLOBE = -7;

    // Barra de herramientas (como la de Samsung Keyboard): a la derecha de las sugerencias, emojis y portapapeles
    static final int TOOL_EMOJI = 1;
    static final int TOOL_CLIPBOARD = 2;
    static final int TOOL_SETTINGS = 3;     // solo en la pagina ?123
    static final int TOOL_HAND = 4;         // modo a una mano (junto al portapapeles)
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
    private String language = "es";        // "es": con tecla n~ y , -> ?; "en": sin tecla n~ y , -> !
    private String spaceLabel;             // nombre del idioma cuando hay mas de uno activo
    private boolean languageSwitch = false; // tecla del globo (solo con varios idiomas Aurebesh activos)
    private boolean suggestionBar = false;  // barra de sugerencias (autocorreccion)
    private int handMode = 0;               // 0 normal, 1 izquierda, 2 derecha
    private final List<String> suggestions = new ArrayList<>();
    private final List<RectF> suggestionRects = new ArrayList<>();
    private String highlighted;             // sugerencia que se aplicara sola (resaltada)
    private boolean toolbar = true;         // iconos sobre las teclas cuando no hay sugerencias
    private final RectF[] toolRects = new RectF[4];
    private final RectF sideRect = new RectF();     // flecha para cambiar de lado en modo a una mano
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

    /** Distribucion por idioma ("es" o "en"); reconstruye las teclas si cambia. */
    void setLanguage(String lang) {
        String l = "en".equals(lang) ? "en" : "es";
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
        highlighted = highlight;
        invalidate();
    }

    /** Barra de herramientas (emojis y portapapeles) a la derecha de las sugerencias. */
    void setToolbar(boolean on) {
        if (toolbar == on) return;
        toolbar = on;
        requestLayout();
        invalidate();
    }

    /** Modo a una mano: 0 normal, 1 izquierda, 2 derecha. */
    void setHandMode(int mode) {
        if (handMode == mode) return;
        handMode = mode;
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

    private void buildLayouts() {
        if (numberRow) letters.add(digitsRow());
        lettersRow(letters, "qwertyuiop");
        if (!numberRow) {                         // sin fila de numeros: q..p dan 1..0 en pulsacion larga
            List<Key> top = letters.get(0);
            for (int i = 0; i < 10 && i < top.size(); i++) top.set(i, top.get(i).withFirstAlt(String.valueOf((i + 1) % 10)));
        }
        lettersRow(letters, "asdfghjkl");
        if ("es".equals(language)) letters.get(letters.size() - 1).add(new Key("ñ", "ñ", "ñ", null, CODE_TEXT, 1f));
        List<Key> r3 = new ArrayList<>();
        r3.add(special("", CODE_SHIFT, 1.5f));
        String z = "zxcvbnm";
        for (int i = 0; i < z.length(); i++) r3.add(letter(String.valueOf(z.charAt(i))));
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
        r.add(sym(",", 1f, "es".equals(language) ? "\u00bf" : "!"));
        float space = 5f;
        if (languageSwitch) { r.add(special("", CODE_GLOBE, 1f)); space -= 1f; }
        r.add(special("", CODE_SPACE, space));
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
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, (int) (stripHeight() + keysHeight() + 6 * dp));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutKeys(letters);
        layoutKeys(symbols);
        layoutKeys(symbols2);
    }

    private void layoutKeys(List<List<Key>> layout) {
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

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas c) {
        if (toolbar || showStrip || suggestionBar) drawStrip(c);
        float inset = 3 * dp;
        float radius = theme.radiusDp * dp;
        for (int ri = 0; ri < current.size(); ri++) {
            for (Key k : current.get(ri)) {
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
        for (int i = 0; i < pressed.size(); i++) {
            Key k = pressed.valueAt(i);
            if (previewEnabled && k != null && k.code == CODE_TEXT && k != popupKey) drawPreview(c, k);
        }
        if (popupKey != null) drawPopup(c);
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
        for (int i = 0; i < toolRects.length; i++) toolRects[i] = null;
        float right = getWidth() - 12 * dp;
        if (toolbar) right = drawToolbar(c);
        if (!suggestions.isEmpty()) drawSuggestions(c, h, top, right);
        else if (showStrip) drawTranslation(c, h, top, 12 * dp, right);
        if (theme.keyStroke != 0) c.drawRect(0, stripHeight() - dp, getWidth(), stripHeight(), strokePaint);
    }

    private int[] tools() { return current != letters ? TOOLS_SYMBOLS : TOOLS_LETTERS; }

    /** Iconos redondos a la derecha: emojis, portapapeles, una mano y, en ?123, ajustes. Devuelve el limite izquierdo. */
    private float drawToolbar(Canvas c) {
        int[] ids = tools();
        float cell = 44 * dp, cy = stripHeight() / 2f;
        float x = getWidth() - 6 * dp - cell * ids.length;
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
        float x = 8 * dp;
        for (int i = 0; i < suggestions.size(); i++) {
            String word = suggestions.get(i);
            boolean emoji = isEmoji(word);
            List<Glyphs.Token> tk = showHints || emoji ? null : Glyphs.tokenize(word, useDigraphs);
            float tw = 0;
            if (tk != null) {
                for (Glyphs.Token t : tk) tw += Glyphs.tokenWidth(t, h, textPaint);
            } else {
                textPaint.setTextSize(emoji ? 19 * sp : 14 * sp);
                tw = textPaint.measureText(word);
            }
            RectF cell = new RectF(x, 5 * dp, x + tw + 24 * dp, stripHeight() - 5 * dp);
            if (cell.right > right - 2 * dp) break;
            boolean hl = highlighted != null && highlighted.equals(word);
            fill.setColor(hl ? theme.accent : theme.specialBg);
            c.drawRoundRect(cell, 9 * dp, 9 * dp, fill);
            if (theme.keyStroke != 0 && !hl) c.drawRoundRect(cell, 9 * dp, 9 * dp, strokePaint);
            int ink = hl ? theme.onAccent : theme.glyph;
            glyphPaint.setColor(ink);
            textPaint.setColor(ink);
            textPaint.setTextAlign(Paint.Align.LEFT);
            float gx = cell.left + 12 * dp;
            if (tk != null) {
                for (Glyphs.Token t : tk) gx += Glyphs.drawToken(c, t, gx, top, h, glyphPaint, textPaint);
            } else {
                c.drawText(word, gx, cell.centerY() + (emoji ? 7 : 5) * dp, textPaint);
            }
            textPaint.setTextAlign(Paint.Align.CENTER);
            glyphPaint.setColor(theme.glyph);
            textPaint.setColor(theme.glyph);
            suggestionRects.add(cell);
            x = cell.right + 6 * dp;
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
        if (total > maxW) x -= total - maxW;
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (Glyphs.Token t : tokens) x += Glyphs.drawToken(c, t, x, top, h, glyphPaint, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.restore();
    }

    // ------------------------------------------------------------------ toque

    private Key findKey(float x, float y) {
        Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Key> row : current) {
            for (Key k : row) {
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
                for (int i = pressed.size() - 1; i >= 0; i--) {
                    Key prev = pressed.valueAt(i);
                    if (prev != null && prev == popupKey) commitPopup();
                    else if (prev != null && prev.code != CODE_DELETE && !(prev == longPressKey && longPressFired)) release(prev);
                    pressed.removeAt(i);
                }
                cancelTimers();
                if (e.getY(idx) < stripHeight()) {                       // barra: sugerencias o iconos
                    float tx = e.getX(idx), ty = e.getY(idx);
                    for (int i = 0; i < suggestionRects.size(); i++) {
                        if (suggestionRects.get(i).contains(tx, ty) && listener != null) {
                            feedback(special("", CODE_SPACE, 1f));
                            listener.onSuggestion(suggestions.get(i));
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
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    Key old = pressed.get(pid);
                    if (old == null) continue;
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
                pressed.clear();
                closePopup();
                cancelTimers();
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
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
        pressed.clear();
    }
}
