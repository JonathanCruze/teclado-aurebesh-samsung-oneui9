package com.aurebesh.teclado;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Muestra de un tema: su fondo, una tecla con el glifo aurek y la pista latina abajo a la derecha (como en el teclado).
 * Anillo cuando esta seleccionado. Con dos temas (automaticos Republica e Imperio) la muestra es doble: el de dia
 * arriba a la izquierda y el de noche abajo a la derecha, partidos en diagonal, y lleva un distintivo redondo con
 * una media luna en la esquina que dice "se ajusta con el sistema".
 */
final class ThemeSwatchView extends View {
    private final Themes.Theme theme;        // tema (o el de dia si es doble)
    private final Themes.Theme night;        // tema de noche de una muestra doble, o null
    private final int ring;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF key = new RectF();
    private final RectF badge = new RectF();
    private final Path diagonal = new Path();

    ThemeSwatchView(Context c, Themes.Theme theme, int ringColor) {
        this(c, theme, null, ringColor);
    }

    /** Muestra doble (automatico): {@code day} de dia y {@code night} de noche. */
    ThemeSwatchView(Context c, Themes.Theme day, Themes.Theme night, int ringColor) {
        super(c);
        this.theme = day;
        this.night = night;
        this.ring = ringColor;
        dp = getResources().getDisplayMetrics().density;
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        hintPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    }

    boolean isDual() { return night != null; }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Math.round(56 * dp);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (isSelected()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor(ring);
            box.set(dp, dp, w - dp, h - dp);
            c.drawRoundRect(box, 16 * dp, 16 * dp, paint);
        }
        box.set(5 * dp, 5 * dp, w - 5 * dp, h - 5 * dp);
        drawTheme(c, theme);
        if (night != null) {
            // Mitad inferior derecha con el tema de noche: misma tecla y mismo glifo, partidos por la diagonal
            diagonal.reset();
            diagonal.moveTo(box.right, box.top);
            diagonal.lineTo(box.right, box.bottom);
            diagonal.lineTo(box.left, box.bottom);
            diagonal.close();
            c.save();
            c.clipPath(diagonal);
            drawTheme(c, night);
            c.restore();
            drawBadge(c, w);
        }
    }

    /** Fondo, tecla en miniatura, glifo aurek y pista latina del tema dentro de {@link #box}. */
    private void drawTheme(Canvas c, Themes.Theme t) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(t.bg);
        c.drawRoundRect(box, 12 * dp, 12 * dp, paint);
        if (t.keyStroke != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp);
            paint.setColor(t.keyStroke);
            c.drawRoundRect(box, 12 * dp, 12 * dp, paint);
        }

        float kw = box.width() * 0.62f, kh = box.height() * 0.56f;
        float cx = box.centerX(), cy = box.centerY();
        key.set(cx - kw / 2, cy - kh / 2, cx + kw / 2, cy + kh / 2);
        float r = t.radiusDp * 0.6f * dp;
        if (t.shadow) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((t.glyph & 0x00FFFFFF) | 0x1A000000);
            c.drawRoundRect(key.left, key.top + dp, key.right, key.bottom + dp, r, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(t.keyBg);
        c.drawRoundRect(key, r, r, paint);
        if (t.keyStroke != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(t.strokeDp * dp);   // Contraste pide un borde mas grueso
            paint.setColor(t.keyStroke);
            c.drawRoundRect(key, r, r, paint);
        }
        // El glifo va algo a la izquierda y arriba del centro para dejar libre la esquina inferior derecha de la pista
        float gx = cx - kw * 0.08f, gy = cy - kh * 0.06f;
        if (t.glow != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor((t.glow & 0x00FFFFFF) | 0x40000000);
            Glyphs.drawCentered(c, "a", gx, gy, kh * 0.46f, kw * 0.6f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(t.glyph);
        Glyphs.drawCentered(c, "a", gx, gy, kh * 0.46f, kw * 0.6f, paint);
        // Pista latina en la esquina inferior derecha de la tecla, nunca en el centro (como en el teclado)
        hintPaint.setColor(t.hint);
        hintPaint.setTextSize(kh * 0.34f);
        c.drawText("a", key.right - 2 * dp, key.bottom - 2 * dp, hintPaint);
    }

    /** Distintivo de automatico: circulo del color de acento con una media luna (mitad clara, mitad oscura). */
    private void drawBadge(Canvas c, float w) {
        float r = 7 * dp, cx = w - r - dp, cy = r + dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ring);
        c.drawCircle(cx, cy, r, paint);
        float ir = 3.6f * dp;
        badge.set(cx - ir, cy - ir, cx + ir, cy + ir);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * dp);
        c.drawCircle(cx, cy, ir, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawArc(badge, 90f, 180f, true, paint);   // mitad izquierda clara: dia; la derecha queda del color del acento: noche
    }
}
