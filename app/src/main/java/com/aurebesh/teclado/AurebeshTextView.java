package com.aurebesh.teclado;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Muestra cualquier texto renderizado en Aurebesh, con ajuste de linea por palabras. */
public final class AurebeshTextView extends View {
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CharSequence content = "";
    private float letterH;
    private boolean digraphs = true;

    public AurebeshTextView(Context c, float letterHeightDp, int color) {
        super(c);
        letterH = letterHeightDp * getResources().getDisplayMetrics().density;
        glyph.setColor(color);
        text.setColor(color);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    public void setText(CharSequence s) {
        content = s == null ? "" : s;
        requestLayout();
        invalidate();
    }

    public void setDigraphs(boolean on) {
        digraphs = on;
        requestLayout();
        invalidate();
    }

    private float lineH() { return letterH * 1.6f; }

    /** Divide los tokens en lineas que caben en el ancho disponible. */
    private List<List<Glyphs.Token>> lines(float maxW) {
        List<Glyphs.Token> tokens = Glyphs.tokenize(content, digraphs);
        List<List<Glyphs.Token>> lines = new ArrayList<>();
        List<Glyphs.Token> line = new ArrayList<>();
        List<Glyphs.Token> word = new ArrayList<>();
        float lineW = 0, wordW = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            Glyphs.Token t = i < tokens.size() ? tokens.get(i) : null;
            if (t == null || t.isSpace() || t.isNewline()) {
                if (lineW + wordW > maxW && !line.isEmpty()) {
                    lines.add(line);
                    line = new ArrayList<>();
                    lineW = 0;
                }
                line.addAll(word);
                lineW += wordW;
                word.clear();
                wordW = 0;
                if (t != null && t.isNewline()) {
                    lines.add(line);
                    line = new ArrayList<>();
                    lineW = 0;
                } else if (t != null) {
                    line.add(t);
                    lineW += Glyphs.tokenWidth(t, letterH, text);
                }
            } else {
                word.add(t);
                wordW += Glyphs.tokenWidth(t, letterH, text);
            }
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add(line);
        return lines;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float avail = w - getPaddingLeft() - getPaddingRight();
        int n = avail > 0 ? lines(avail).size() : 1;
        int h = (int) (n * lineH() + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float avail = getWidth() - getPaddingLeft() - getPaddingRight();
        float y = getPaddingTop() + (lineH() - letterH) / 2f;
        for (List<Glyphs.Token> line : lines(avail)) {
            float x = getPaddingLeft();
            for (Glyphs.Token t : line) x += Glyphs.drawToken(c, t, x, y, letterH, glyph, text);
            y += lineH();
        }
    }
}
