package com.aurebesh.teclado;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Dibuja glifos Aurebesh y convierte texto latino en una secuencia de glifos. */
final class Glyphs {
    private Glyphs() {}

    static final String[] DIGRAPHS = {"ch", "ae", "eo", "kh", "ng", "oo", "sh", "th"};

    private static final HashMap<String, Path> PATHS = new HashMap<>();
    private static final HashMap<String, Float> WIDTHS = new HashMap<>();
    private static final HashMap<String, Float> LSB = new HashMap<>();
    private static final HashMap<String, Float> ADV = new HashMap<>();
    private static final Matrix MATRIX = new Matrix();
    private static final Path TMP = new Path();

    static {
        for (int i = 0; i < GlyphData.KEYS.length; i++) {
            String[] f = GlyphData.DATA[i].split(";");
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            for (int k = 3; k < f.length; k++) {
                String[] pts = f[k].split(" ");
                for (int j = 0; j < pts.length; j++) {
                    int comma = pts[j].indexOf(',');
                    float x = Float.parseFloat(pts[j].substring(0, comma));
                    float y = Float.parseFloat(pts[j].substring(comma + 1));
                    if (j == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
            }
            PATHS.put(GlyphData.KEYS[i], path);
            WIDTHS.put(GlyphData.KEYS[i], Float.parseFloat(f[0]));
            LSB.put(GlyphData.KEYS[i], Float.parseFloat(f[1]));
            ADV.put(GlyphData.KEYS[i], Float.parseFloat(f[2]));
        }
    }

    static boolean has(String key) {
        return key != null && PATHS.containsKey(key);
    }

    /** Ancho del glifo en unidades (alto de letra = 10). */
    static float width(String key) {
        Float w = WIDTHS.get(key);
        return w == null ? 0 : w;
    }

    /** Avance (anchura tipografica) y margen izquierdo del glifo, en unidades. */
    static float advance(String key) {
        Float a = ADV.get(key);
        return a == null ? width(key) + 0.8f : a;
    }

    static float lsb(String key) {
        Float l = LSB.get(key);
        return l == null ? 0 : l;
    }

    /** Dibuja el glifo con la caja de letra [top, top + h] y el borde izquierdo en x. */
    static void draw(Canvas c, String key, float x, float top, float h, Paint p) {
        Path path = PATHS.get(key);
        if (path == null) return;
        float s = h / 10f;
        MATRIX.setScale(s, s);
        MATRIX.postTranslate(x, top);
        path.transform(MATRIX, TMP);
        TMP.setFillType(Path.FillType.EVEN_ODD);
        c.drawPath(TMP, p);
    }

    /** Dibuja el glifo centrado horizontalmente en cx y verticalmente en cy, cabiendo en maxW. */
    static void drawCentered(Canvas c, String key, float cx, float cy, float h, float maxW, Paint p) {
        float w = width(key);
        if (w <= 0) return;
        float scaledW = w * h / 10f;
        if (scaledW > maxW) {
            h = h * maxW / scaledW;
            scaledW = maxW;
        }
        draw(c, key, cx - scaledW / 2f, cy - h / 2f, h, p);
    }

    /** Token de texto: glifo Aurebesh (glyph != null) o caracter literal sin glifo. */
    static final class Token {
        final String glyph;
        final String literal;
        Token(String glyph, String literal) { this.glyph = glyph; this.literal = literal; }
        boolean isSpace() { return glyph == null && " ".equals(literal); }
        boolean isNewline() { return glyph == null && "\n".equals(literal); }
    }

    private static final java.util.regex.Pattern MARKS =
            java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * Convierte texto latino en glifos. Los caracteres con glifo propio (n con virgulilla, vocales
     * con tilde) se conservan; el resto pierde los diacriticos. Si useDigraphs, "ch", "sh", "th"...
     * usan su glifo propio.
     */
    static List<Token> tokenize(CharSequence text, boolean useDigraphs) {
        String raw = Normalizer.normalize(text.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        List<String> chars = new ArrayList<>();
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            chars.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        List<String> base = new ArrayList<>(chars.size());
        for (String ch : chars) base.add(stripMarks(ch));

        List<Token> out = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            if (useDigraphs && i + 1 < chars.size()
                    && chars.get(i).equals(base.get(i)) && chars.get(i + 1).equals(base.get(i + 1))
                    && isDigraph(base.get(i) + base.get(i + 1))) {
                out.add(new Token(base.get(i) + base.get(i + 1), null));
                i++;
                continue;
            }
            String ch = chars.get(i);
            String b = base.get(i);
            if (b.equals("\t")) b = " ";
            if (has(ch)) out.add(new Token(ch, null));
            else if (has(b)) out.add(new Token(b, null));
            else out.add(new Token(null, b));
        }
        return out;
    }

    private static String stripMarks(String ch) {
        String d = MARKS.matcher(Normalizer.normalize(ch, Normalizer.Form.NFD)).replaceAll("");
        return d.isEmpty() ? ch : d;
    }

    private static boolean isDigraph(String two) {
        for (String d : DIGRAPHS) if (d.equals(two)) return true;
        return false;
    }

    static final float LATIN_GAP = 0.8f;    // unidades tras un caracter latino sin glifo

    /** Ancho de un token en pixeles para una altura de letra h (metricas de la fuente). */
    static float tokenWidth(Token t, float h, Paint textPaint) {
        if (t.glyph != null) return advance(t.glyph) * h / 10f;
        if (t.isSpace()) return GlyphData.SPACE_ADV * h / 10f;
        if (t.isNewline()) return 0;
        textPaint.setTextSize(h * 1.15f);
        return textPaint.measureText(t.literal) + LATIN_GAP * h / 10f;
    }

    /** Dibuja un token con su origen en x; devuelve el avance. */
    static float drawToken(Canvas c, Token t, float x, float top, float h, Paint glyphPaint, Paint textPaint) {
        if (t.glyph != null) {
            draw(c, t.glyph, x + lsb(t.glyph) * h / 10f, top, h, glyphPaint);
        } else if (!t.isSpace() && !t.isNewline()) {
            textPaint.setTextSize(h * 1.15f);
            c.drawText(t.literal, x, top + h, textPaint);
        }
        return tokenWidth(t, h, textPaint);
    }
}
