package com.aurebesh.teclado;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Muestra de un tema: su fondo, una tecla y el glifo aurek. Anillo cuando esta seleccionado. */
final class ThemeSwatchView extends View {
    private final Themes.Theme theme;
    private final int ring;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    ThemeSwatchView(Context c, Themes.Theme theme, int ringColor) {
        super(c);
        this.theme = theme;
        this.ring = ringColor;
        dp = getResources().getDisplayMetrics().density;
    }

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
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.bg);
        c.drawRoundRect(box, 12 * dp, 12 * dp, paint);
        if (theme.keyStroke != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp);
            paint.setColor(theme.keyStroke);
            c.drawRoundRect(box, 12 * dp, 12 * dp, paint);
        }

        // Tecla en miniatura con el glifo aurek
        float kw = box.width() * 0.62f, kh = box.height() * 0.56f;
        float cx = box.centerX(), cy = box.centerY();
        RectF key = new RectF(cx - kw / 2, cy - kh / 2, cx + kw / 2, cy + kh / 2);
        float r = theme.radiusDp * 0.6f * dp;
        if (theme.shadow) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((theme.glyph & 0x00FFFFFF) | 0x1A000000);
            c.drawRoundRect(key.left, key.top + dp, key.right, key.bottom + dp, r, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.keyBg);
        c.drawRoundRect(key, r, r, paint);
        if (theme.keyStroke != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(theme.strokeDp * dp);   // Contraste pide un borde mas grueso
            paint.setColor(theme.keyStroke);
            c.drawRoundRect(key, r, r, paint);
        }
        if (theme.glow != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor((theme.glow & 0x00FFFFFF) | 0x40000000);
            Glyphs.drawCentered(c, "a", cx, cy, kh * 0.5f, kw * 0.72f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.glyph);
        Glyphs.drawCentered(c, "a", cx, cy, kh * 0.5f, kw * 0.72f, paint);
    }
}
