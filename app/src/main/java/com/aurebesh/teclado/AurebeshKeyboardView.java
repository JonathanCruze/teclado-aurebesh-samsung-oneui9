package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioManager;
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
        void onSwitchKeyboard();
    }

    static final int CODE_TEXT = 0;
    static final int CODE_SHIFT = -1;
    static final int CODE_MODE = -2;
    static final int CODE_ENTER = -4;
    static final int CODE_DELETE = -5;
    static final int CODE_SPACE = -6;
    static final int CODE_GLOBE = -7;

    static final class Key {
        final String output;
        final String glyph;
        final String label;
        final String alt;
        final int code;
        final float weight;
        final RectF rect = new RectF();

        Key(String output, String glyph, String label, String alt, int code, float weight) {
            this.output = output; this.glyph = glyph; this.label = label;
            this.alt = alt; this.code = code; this.weight = weight;
        }
    }

    private Listener listener;
    private final List<List<Key>> letters = new ArrayList<>();
    private final List<List<Key>> symbols = new ArrayList<>();
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
    private AudioManager audio;
    private String language = "es";        // "es": con tecla n~ y , -> ?; "en": sin tecla n~ y , -> !
    private String spaceLabel;             // nombre del idioma cuando hay mas de uno activo
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
            boolean onSymbols = current == symbols;
            letters.clear();
            symbols.clear();
            buildLayouts();
            current = onSymbols ? symbols : letters;
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
        boolean onSymbols = current == symbols;
        letters.clear();
        symbols.clear();
        buildLayouts();
        current = onSymbols ? symbols : letters;
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

    private static Key letter(String c, String alt) { return new Key(c, c, c, alt, CODE_TEXT, 1f); }
    private static Key sym(String out, float w) { return sym(out, w, null); }

    /** Signo con alternativa por pulsacion larga. */
    private static Key sym(String out, float w, String alt) {
        return new Key(out, Glyphs.has(out) ? out : null, out, alt, CODE_TEXT, w);
    }
    private static Key special(String label, int code, float w) { return new Key(null, null, label, null, code, w); }

    private void row(List<List<Key>> layout, Key... keys) {
        List<Key> r = new ArrayList<>();
        for (Key k : keys) r.add(k);
        layout.add(r);
    }

    private void lettersRow(List<List<Key>> layout, String chars, String alts) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) {
            String a = alts.charAt(i) == '.' ? null : String.valueOf(alts.charAt(i));
            r.add(letter(String.valueOf(chars.charAt(i)), a));
        }
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
        lettersRow(letters, "qwertyuiop", "..é...úíó.");
        lettersRow(letters, "asdfghjkl", "á........");
        if ("es".equals(language)) letters.get(letters.size() - 1).add(new Key("ñ", "ñ", "ñ", null, CODE_TEXT, 1f));
        List<Key> r3 = new ArrayList<>();
        r3.add(special("", CODE_SHIFT, 1.5f));
        String z = "zxcvbnm";
        for (int i = 0; i < z.length(); i++) {
            String c = String.valueOf(z.charAt(i));
            r3.add(letter(c, c.equals("n") ? "ñ" : null));
        }
        r3.add(special("", CODE_DELETE, 1.5f));
        letters.add(r3);
        bottomRow(letters, "?123");

        symbols.add(digitsRow());
        List<Key> dig = new ArrayList<>();
        for (String d : Glyphs.DIGRAPHS) dig.add(new Key(d, d, d, null, CODE_TEXT, 1.25f));
        symbols.add(dig);
        row(symbols, sym("-", 1f, "_"), sym("?", 1f, "\u00bf"), sym("!", 1f, "\u00a1"), sym(":", 1f), sym(";", 1f),
                sym("\"", 1f, "\u00ab"), sym("'", 1f, "\u00bb"), sym("(", 1f, "["), sym(")", 1f, "]"), sym("/", 1f, "\\"));
        row(symbols, sym("@", 1f), sym("#", 1f, "~"), sym("$", 1f, "\u20ac"), sym("&", 1f, "|"), sym("+", 1f),
                sym("=", 1f), sym("*", 1f), sym("%", 1f), special("", CODE_DELETE, 2f));
        bottomRow(symbols, "abc");
    }

    private void bottomRow(List<List<Key>> layout, String modeLabel) {
        row(layout, special(modeLabel, CODE_MODE, 1.5f), sym(",", 1f, "es".equals(language) ? "\u00bf" : "!"), special("", CODE_GLOBE, 1f),
                special("", CODE_SPACE, 4f), sym(".", 1f, "?"), special("", CODE_ENTER, 1.5f));
    }

    /** Tecla del teclado actual que escribe {@code output} (para pruebas). */
    Key keyFor(String output) {
        for (List<Key> row : current) for (Key k : row) if (output.equals(k.output)) return k;
        return null;
    }

    // ------------------------------------------------------------------ medidas

    private float stripHeight() { return showStrip ? 36 * dp : 4 * dp; }
    private boolean isDigitRow(List<List<Key>> layout, int ri) {
        return ri == 0 && (numberRow || layout == symbols);
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
    }

    private void layoutKeys(List<List<Key>> layout) {
        float w = getWidth();
        float pad = 4 * dp;
        float y = stripHeight();
        for (int ri = 0; ri < layout.size(); ri++) {
            List<Key> row = layout.get(ri);
            float rh = rowHeight(layout, ri);
            float total = 0;
            for (Key k : row) total += k.weight;
            float unit = (w - 2 * pad) / Math.max(10f, total);
            float x = pad + ((w - 2 * pad) - unit * total) / 2f;
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
        if (showStrip) drawStrip(c);
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
        for (int i = 0; i < pressed.size(); i++) {
            Key k = pressed.valueAt(i);
            if (previewEnabled && k != null && k.code == CODE_TEXT) drawPreview(c, k);
        }
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
                if (showHints && k.alt != null && !digitRow) {  // alternativa por pulsacion larga (solo con pistas)
                    hintPaint.setTextSize(9 * sp);
                    c.drawText(k.alt, r.right - 4 * dp, r.top + 10 * dp, hintPaint);
                }
                break;
            case CODE_MODE:
                // "?123" / "abc" escritos en Aurebesh pequeño
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
            iconPaint.setStrokeWidth(2 * dp);
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
        iconPaint.setStrokeWidth(1.8f * dp);
        c.drawPath(iconPath, iconPaint);
        float x = cx + w * 0.2f, k = 2.6f * dp;
        c.drawLine(x - k, cy - k, x + k, cy + k, iconPaint);
        c.drawLine(x - k, cy + k, x + k, cy - k, iconPaint);
    }

    private void drawGlobe(Canvas c, float cx, float cy) {
        float r = 8 * dp;
        iconPaint.setStrokeWidth(1.6f * dp);
        c.drawCircle(cx, cy, r, iconPaint);
        c.drawLine(cx - r, cy, cx + r, cy, iconPaint);
        c.drawLine(cx, cy - r, cx, cy + r, iconPaint);
        tmp.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r);
        c.drawOval(tmp, iconPaint);
        tmp.set(cx - r, cy - r * 0.45f, cx + r, cy + r * 0.45f);
        c.drawOval(tmp, iconPaint);
    }

    private void drawEnter(Canvas c, float cx, float cy) {
        iconPaint.setStrokeWidth(2.2f * dp);
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
        float h = 15 * dp;
        float top = (stripHeight() - h) / 2f;
        List<Glyphs.Token> tokens = stripTokens;
        float total = 0;
        for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, textPaint);
        float x = 12 * dp;
        float maxW = getWidth() - 24 * dp;
        c.save();
        c.clipRect(x, 0, x + maxW, stripHeight());
        if (total > maxW) x -= total - maxW;
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (Glyphs.Token t : tokens) x += Glyphs.drawToken(c, t, x, top, h, glyphPaint, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.restore();
        if (theme.keyStroke != 0) c.drawRect(0, stripHeight() - dp, getWidth(), stripHeight(), strokePaint);
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
                    if (prev != null && prev.code != CODE_DELETE && !(prev == longPressKey && longPressFired)) release(prev);
                    pressed.removeAt(i);
                }
                cancelTimers();
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
                    if (old == null || old.code == CODE_DELETE || old.code == CODE_SPACE) continue;
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
                Key k = pressed.get(id);
                pressed.remove(id);
                boolean consumed = k != null && k == longPressKey && longPressFired;
                cancelTimers();
                if (k != null && !consumed && k.code != CODE_DELETE) release(k);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed.clear();
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
            case CODE_GLOBE: listener.onSwitchKeyboard(); break;
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
            } else if (k.code == CODE_TEXT && k.alt != null) {
                longPressFired = true;
                feedback(k);
                listener.onText(shiftActive() ? k.alt.toUpperCase() : k.alt);
                if (shift && !capsLock) { shift = false; autoShifted = false; }
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
        if (!sound) return;
        if (audio == null) audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        int fx = k.code == CODE_DELETE ? AudioManager.FX_KEYPRESS_DELETE
                : k.code == CODE_ENTER ? AudioManager.FX_KEYPRESS_RETURN
                : k.code == CODE_SPACE ? AudioManager.FX_KEYPRESS_SPACEBAR
                : AudioManager.FX_KEYPRESS_STANDARD;
        audio.playSoundEffect(fx, 0.5f);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelTimers();
        pressed.clear();
    }
}
