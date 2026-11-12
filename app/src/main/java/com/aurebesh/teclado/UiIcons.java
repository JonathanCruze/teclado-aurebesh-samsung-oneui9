package com.aurebesh.teclado;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Iconos de la pantalla de ajustes dibujados en Canvas (ningun caracter Unicode suelto): los de las nueve
 * categorias, la flecha atras, la lupa, el aspa de borrar y los chevrones. Los que ya existen en la cinta del
 * teclado (portapapeles, inteligencia, tamano) se piden a {@link ToolIcons} para que la app y el teclado
 * compartan el mismo dibujo. Todas las medidas son relativas al lado {@code s} del icono; el trazo es redondeado
 * y de unos 0,09 s, como los iconos de linea de One UI.
 */
final class UiIcons {
    private UiIcons() {}

    static final int KEYS = 1, STYLE = 2, SIZE = 3, SMART = 4, TOUCH = 5, CLIPBOARD = 6, AI = 7, PRIVACY = 8, HELP = 9,
            BACK = 10, SEARCH = 11, CLEAR = 12, CHEVRON_RIGHT = 13, CHEVRON_UP = 14, CHEVRON_DOWN = 15;

    private static final Path PATH = new Path();
    private static final RectF RECT = new RectF();

    /** Drawable cuadrado de {@code sizePx} de lado con el icono pedido en ese color (para ImageView o fondos). */
    static Drawable drawable(int kind, int color, int sizePx) {
        return new Icon(kind, color, sizePx);
    }

    /** Dibuja el icono centrado en (cx, cy) con lado s; devuelve el Paint con el estilo y grosor que tenia. */
    static void draw(Canvas c, int kind, float cx, float cy, float s, Paint p) {
        Paint.Style style = p.getStyle();
        float width = p.getStrokeWidth();
        Paint.Cap cap = p.getStrokeCap();
        Paint.Join join = p.getStrokeJoin();
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.09f);
        switch (kind) {
            case KEYS: keyboard(c, cx, cy, s, p); break;
            case STYLE: halfMoon(c, cx, cy, s, p); break;
            case SIZE: ToolIcons.draw(c, AurebeshKeyboardView.TOOL_RESIZE, cx, cy, s * 0.95f, p); break;
            case SMART: pencil(c, cx, cy, s, p); break;
            case TOUCH: target(c, cx, cy, s, p); break;
            case CLIPBOARD: ToolIcons.draw(c, AurebeshKeyboardView.TOOL_CLIPBOARD, cx, cy, s * 0.95f, p); break;
            case AI: ToolIcons.draw(c, AurebeshKeyboardView.TOOL_AI, cx, cy, s * 0.95f, p); break;
            case PRIVACY: shield(c, cx, cy, s, p); break;
            case HELP: question(c, cx, cy, s, p); break;
            case BACK: back(c, cx, cy, s, p); break;
            case SEARCH: lens(c, cx, cy, s, p); break;
            case CLEAR: cross(c, cx, cy, s, p); break;
            case CHEVRON_RIGHT: chevron(c, cx, cy, s, p, 0); break;
            case CHEVRON_UP: chevron(c, cx, cy, s, p, 1); break;
            case CHEVRON_DOWN: chevron(c, cx, cy, s, p, 2); break;
            default: c.drawCircle(cx, cy, s * 0.35f, p);
        }
        p.setStyle(style);
        p.setStrokeWidth(width);
        p.setStrokeCap(cap);
        p.setStrokeJoin(join);
    }

    /** Teclado: caja redondeada con dos filas de puntos y la barra espaciadora. */
    private static void keyboard(Canvas c, float cx, float cy, float s, Paint p) {
        RECT.set(cx - s * 0.44f, cy - s * 0.3f, cx + s * 0.44f, cy + s * 0.3f);
        c.drawRoundRect(RECT, s * 0.1f, s * 0.1f, p);
        c.drawLine(cx - s * 0.2f, cy + s * 0.15f, cx + s * 0.2f, cy + s * 0.15f, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 4; j++) c.drawCircle(cx - s * 0.24f + j * s * 0.16f, cy - s * 0.15f + i * s * 0.15f, s * 0.045f, p);
        }
    }

    /** Estilo: circulo con la mitad derecha rellena (claro y oscuro). */
    private static void halfMoon(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawCircle(cx, cy, s * 0.38f, p);
        p.setStyle(Paint.Style.FILL);
        RECT.set(cx - s * 0.38f, cy - s * 0.38f, cx + s * 0.38f, cy + s * 0.38f);
        c.drawArc(RECT, -90f, 180f, true, p);
    }

    /** Escritura inteligente: lapiz inclinado con la punta abajo a la izquierda. */
    private static void pencil(Canvas c, float cx, float cy, float s, Paint p) {
        float a = s * 0.3f;
        PATH.rewind();
        PATH.moveTo(cx - a + s * 0.12f, cy + a - s * 0.12f);
        PATH.lineTo(cx + a - s * 0.06f, cy - a + s * 0.06f);
        PATH.lineTo(cx + a + s * 0.06f, cy - a + s * 0.18f);
        PATH.lineTo(cx - a + s * 0.24f, cy + a);
        PATH.close();
        c.drawPath(PATH, p);
        c.drawLine(cx + a - s * 0.18f, cy - a + s * 0.18f, cx + a - s * 0.06f, cy - a + s * 0.3f, p);   // anillo del lapiz
        p.setStyle(Paint.Style.FILL);
        PATH.rewind();                                                                              // punta
        PATH.moveTo(cx - a + s * 0.1f, cy + a - s * 0.1f);
        PATH.lineTo(cx - a + s * 0.22f, cy + a + s * 0.02f);
        PATH.lineTo(cx - a - s * 0.06f, cy + a + s * 0.06f);
        PATH.close();
        c.drawPath(PATH, p);
    }

    /** Deslizar y tocar: diana de dos anillos con el punto central. */
    private static void target(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawCircle(cx, cy, s * 0.4f, p);
        c.drawCircle(cx, cy, s * 0.22f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, s * 0.06f, p);
    }

    /** Privacidad: escudo con una marca de verificacion. */
    private static void shield(Canvas c, float cx, float cy, float s, Paint p) {
        PATH.rewind();
        PATH.moveTo(cx, cy - s * 0.42f);
        PATH.lineTo(cx + s * 0.34f, cy - s * 0.3f);
        PATH.lineTo(cx + s * 0.34f, cy);
        PATH.quadTo(cx + s * 0.34f, cy + s * 0.28f, cx, cy + s * 0.42f);
        PATH.quadTo(cx - s * 0.34f, cy + s * 0.28f, cx - s * 0.34f, cy);
        PATH.lineTo(cx - s * 0.34f, cy - s * 0.3f);
        PATH.close();
        c.drawPath(PATH, p);
        PATH.rewind();
        PATH.moveTo(cx - s * 0.14f, cy);
        PATH.lineTo(cx - s * 0.03f, cy + s * 0.11f);
        PATH.lineTo(cx + s * 0.16f, cy - s * 0.1f);
        c.drawPath(PATH, p);
    }

    /** Ayuda: circulo con un signo de interrogacion (arco, asta y punto). */
    private static void question(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawCircle(cx, cy, s * 0.4f, p);
        RECT.set(cx - s * 0.13f, cy - s * 0.25f, cx + s * 0.13f, cy + s * 0.01f);
        c.drawArc(RECT, 190f, 230f, false, p);
        c.drawLine(cx, cy + s * 0.01f, cx, cy + s * 0.1f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy + s * 0.24f, s * 0.055f, p);
    }

    /** Flecha atras. */
    private static void back(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawLine(cx + s * 0.36f, cy, cx - s * 0.34f, cy, p);
        c.drawLine(cx - s * 0.34f, cy, cx - s * 0.04f, cy - s * 0.3f, p);
        c.drawLine(cx - s * 0.34f, cy, cx - s * 0.04f, cy + s * 0.3f, p);
    }

    /** Lupa. */
    private static void lens(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawCircle(cx - s * 0.07f, cy - s * 0.07f, s * 0.27f, p);
        c.drawLine(cx + s * 0.13f, cy + s * 0.13f, cx + s * 0.36f, cy + s * 0.36f, p);
    }

    /** Aspa (borrar). */
    private static void cross(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawLine(cx - s * 0.28f, cy - s * 0.28f, cx + s * 0.28f, cy + s * 0.28f, p);
        c.drawLine(cx + s * 0.28f, cy - s * 0.28f, cx - s * 0.28f, cy + s * 0.28f, p);
    }

    /** Chevron: 0 a la derecha, 1 arriba, 2 abajo. */
    private static void chevron(Canvas c, float cx, float cy, float s, Paint p, int dir) {
        float a = s * 0.26f, b = s * 0.13f;
        PATH.rewind();
        if (dir == 0) {
            PATH.moveTo(cx - b, cy - a); PATH.lineTo(cx + b, cy); PATH.lineTo(cx - b, cy + a);
        } else if (dir == 1) {
            PATH.moveTo(cx - a, cy + b); PATH.lineTo(cx, cy - b); PATH.lineTo(cx + a, cy + b);
        } else {
            PATH.moveTo(cx - a, cy - b); PATH.lineTo(cx, cy + b); PATH.lineTo(cx + a, cy - b);
        }
        c.drawPath(PATH, p);
    }

    /** Drawable cuadrado con un icono; el color se cambia con setTint o se fija al crearlo. */
    static final class Icon extends Drawable {
        private final int kind;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int kind, int color, int size) {
            this.kind = kind;
            this.size = size;
            paint.setColor(color);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0) return;
            UiIcons.draw(c, kind, b.exactCenterX(), b.exactCenterY(), s, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
        @Override public void setTint(int color) { paint.setColor(color); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
