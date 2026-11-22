package com.aurebesh.teclado;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;
import java.util.Set;

/**
 * Tabla del alfabeto: una celda por letra con su simbolo Aurebesh, la letra normal debajo y, si lo tiene, el nombre
 * de la letra (aurek, besh...). Las letras que ya se leen sin ayuda (modo Aprendiendo) llevan la celda tenida del
 * color de acento. Las columnas salen del ancho: celdas de al menos 52 dp.
 */
final class AlphabetView extends View {
    /** Nombres de las letras y de las letras dobles del Aurebesh. */
    private static final String[][] NAMES = {
            {"a", "aurek"}, {"b", "besh"}, {"c", "cresh"}, {"d", "dorn"}, {"e", "esk"}, {"f", "forn"}, {"g", "grek"},
            {"h", "herf"}, {"i", "isk"}, {"j", "jenth"}, {"k", "krill"}, {"l", "leth"}, {"m", "mern"}, {"n", "nern"},
            {"o", "osk"}, {"p", "peth"}, {"q", "qek"}, {"r", "resh"}, {"s", "senth"}, {"t", "trill"}, {"u", "usk"},
            {"v", "vev"}, {"w", "wesk"}, {"x", "xesh"}, {"y", "yirt"}, {"z", "zerek"},
            {"ch", "cherek"}, {"ae", "enth"}, {"eo", "onith"}, {"kh", "krenth"}, {"ng", "nen"}, {"oo", "orenth"},
            {"sh", "shen"}, {"th", "thesh"}};

    private final String[] items;
    private Set<String> known;
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tileColor, knownColor;
    private final float dp;
    private final RectF box = new RectF();
    private int cols = 6;
    private float cellW, cellH;

    /** Letras que ya se leen sin ayuda (se tiñen); null o vacio = ninguna. */
    void setKnown(Set<String> known) {
        this.known = known;
        invalidate();
    }

    /** Letras marcadas ahora mismo (para pruebas). */
    Set<String> known() { return known; }

    AlphabetView(Context c, String[] items, Set<String> known, int ink, int secondary, int tileColor, int knownColor) {
        super(c);
        this.items = items;
        this.known = known;
        this.tileColor = tileColor;
        this.knownColor = knownColor;
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        glyph.setColor(ink);
        letter.setColor(ink);
        letter.setTextAlign(Paint.Align.CENTER);
        letter.setTextSize(14 * sp);
        letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setColor(secondary);
        name.setTextAlign(Paint.Align.CENTER);
        name.setTextSize(10 * sp);
        cellH = 46 * dp + letter.getTextSize() + name.getTextSize() + 14 * dp;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        StringBuilder spoken = new StringBuilder();
        for (String it : items) {
            if (spoken.length() > 0) spoken.append(", ");
            spoken.append(it);
            String n = nameOf(it);
            if (n != null) spoken.append(' ').append(n);
        }
        setContentDescription(spoken.toString());
    }

    /** Nombre Aurebesh de la letra o letra doble, o null si no lo tiene (numeros, enye, letras creadas para el teclado). */
    static String nameOf(String item) {
        for (String[] n : NAMES) if (n[0].equals(item)) return n[1];
        return null;
    }

    int columns() { return cols; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        cols = Math.max(4, Math.min(items.length, (int) (w / (52 * dp))));
        cellW = w / (float) cols;
        int rows = (items.length + cols - 1) / cols;
        setMeasuredDimension(w, (int) Math.ceil(rows * cellH));
    }

    @Override
    protected void onDraw(Canvas c) {
        float gap = 3 * dp;
        for (int i = 0; i < items.length; i++) {
            String it = items[i];
            float left = (i % cols) * cellW, top = (i / cols) * cellH;
            box.set(left + gap, top + gap, left + cellW - gap, top + cellH - gap);
            tile.setColor(known != null && known.contains(it) ? knownColor : tileColor);
            c.drawRoundRect(box, 10 * dp, 10 * dp, tile);
            float cx = left + cellW / 2f;
            String key = Glyphs.has(it) ? it : Glyphs.glyphKey(it);
            if (key != null) Glyphs.drawCentered(c, key, cx, top + gap + 25 * dp, 22 * dp, cellW - 2 * gap - 10 * dp, glyph);
            float base = top + gap + 44 * dp + letter.getTextSize();
            c.drawText(it.length() == 1 && Character.isLetter(it.charAt(0)) ? it.toUpperCase(Locale.ROOT) + " " + it : it, cx, base, letter);
            String n = nameOf(it);
            if (n != null) c.drawText(n, cx, base + name.getTextSize() + 3 * dp, name);
        }
    }
}
