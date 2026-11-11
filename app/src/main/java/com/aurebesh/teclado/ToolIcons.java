package com.aurebesh.teclado;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Iconos de las herramientas de la cinta (TOOL_* de {@link AurebeshKeyboardView}) dibujados en Canvas, para
 * que la cinta y la cuadricula "..." compartan el mismo dibujo. {@code size} es el alto del icono en px (en la
 * cinta, 20 dp) y todas las medidas son relativas a el; {@code p} lleva el color del llamante y se usa tanto en
 * relleno como en trazo (unos 0,11 de size), devolviendose con el estilo, grosor y remates que tenia. Solo se
 * dibuja desde el hilo principal (comparte una Path y un RectF temporales).
 */
final class ToolIcons {
    private ToolIcons() {}

    private static final Path PATH = new Path();
    private static final RectF RECT = new RectF();

    static void draw(Canvas c, int tool, float cx, float cy, float size, Paint p) {
        Paint.Style style = p.getStyle();
        float width = p.getStrokeWidth();
        Paint.Cap cap = p.getStrokeCap();
        Paint.Join join = p.getStrokeJoin();
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        stroke(p, size * 0.11f);
        switch (tool) {
            case AurebeshKeyboardView.TOOL_EMOJI: smiley(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_CLIPBOARD: clipboard(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_HAND: hand(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_RESIZE: resize(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_SETTINGS: gear(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_AI: sparkle(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_EDIT: edit(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_TRANSLATE: translate(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_SPLIT: split(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_STRIP: strip(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_LANG: globe(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_HIDE: hide(c, cx, cy, size, p); break;
            case AurebeshKeyboardView.TOOL_MORE: more(c, cx, cy, size, p); break;
            default: c.drawCircle(cx, cy, size * 0.4f, p);          // herramienta desconocida: un circulo
        }
        p.setStyle(style);
        p.setStrokeWidth(width);
        p.setStrokeCap(cap);
        p.setStrokeJoin(join);
    }

    private static void stroke(Paint p, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
    }

    private static void fill(Paint p) { p.setStyle(Paint.Style.FILL); }

    /** Carita (emojis): circulo, dos ojos y sonrisa. */
    private static void smiley(Canvas c, float cx, float cy, float s, Paint p) {
        c.drawCircle(cx, cy, s * 0.4f, p);
        RECT.set(cx - s * 0.23f, cy - s * 0.16f, cx + s * 0.23f, cy + s * 0.23f);
        c.drawArc(RECT, 25, 130, false, p);
        fill(p);
        c.drawCircle(cx - s * 0.15f, cy - s * 0.13f, s * 0.075f, p);
        c.drawCircle(cx + s * 0.15f, cy - s * 0.13f, s * 0.075f, p);
    }

    /** Portapapeles: tablilla con pinza y dos lineas de texto. */
    private static void clipboard(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.12f);
        float w = s * 0.35f, h = s * 0.45f;
        RECT.set(cx - w, cy - h + s * 0.1f, cx + w, cy + h);
        c.drawRoundRect(RECT, s * 0.125f, s * 0.125f, p);
        c.drawLine(cx - s * 0.175f, cy + s * 0.025f, cx + s * 0.175f, cy + s * 0.025f, p);
        c.drawLine(cx - s * 0.175f, cy + s * 0.225f, cx + s * 0.075f, cy + s * 0.225f, p);
        fill(p);
        RECT.set(cx - s * 0.175f, cy - h - s * 0.05f, cx + s * 0.175f, cy - h + s * 0.2f);
        c.drawRoundRect(RECT, s * 0.075f, s * 0.075f, p);
    }

    /** Modo a una mano: teclado estrecho con puntos y una flecha de desplazamiento. */
    private static void hand(Canvas c, float cx, float cy, float s, Paint p) {
        RECT.set(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.45f, cy + s * 0.3f);
        c.drawRoundRect(RECT, s * 0.1f, s * 0.1f, p);
        c.drawLine(cx - s * 0.45f, cy, cx - s * 0.225f, cy, p);
        c.drawLine(cx - s * 0.45f, cy, cx - s * 0.325f, cy - s * 0.125f, p);
        c.drawLine(cx - s * 0.45f, cy, cx - s * 0.325f, cy + s * 0.125f, p);
        fill(p);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 3; j++) c.drawCircle(cx + s * 0.05f + j * s * 0.125f, cy - s * 0.125f + i * s * 0.14f, s * 0.04f, p);
        }
    }

    /** Tamano y posicion: flecha vertical de dos puntas entre dos topes. */
    private static void resize(Canvas c, float cx, float cy, float s, Paint p) {
        float h = s * 0.4f, a = s * 0.15f, w = s * 0.3f;
        c.drawLine(cx - w, cy - h, cx + w, cy - h, p);                 // tope superior
        c.drawLine(cx - w, cy + h, cx + w, cy + h, p);                 // tope inferior
        float y0 = cy - h + s * 0.15f, y1 = cy + h - s * 0.15f;
        c.drawLine(cx, y0, cx, y1, p);                                 // asta
        c.drawLine(cx, y0, cx - a, y0 + a, p);                         // punta de arriba
        c.drawLine(cx, y0, cx + a, y0 + a, p);
        c.drawLine(cx, y1, cx - a, y1 - a, p);                         // punta de abajo
        c.drawLine(cx, y1, cx + a, y1 - a, p);
    }

    /** Engranaje (ajustes): corona gruesa con ocho dientes y eje. */
    private static void gear(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.17f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + Math.PI / 8;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * s * 0.275f, cy + dy * s * 0.275f, cx + dx * s * 0.43f, cy + dy * s * 0.43f, p);
        }
        stroke(p, s * 0.16f);
        c.drawCircle(cx, cy, s * 0.26f, p);
        fill(p);
        c.drawCircle(cx, cy, s * 0.105f, p);
    }

    /** Inteligencia: estrella de cuatro puntas grande y otra pequena. */
    private static void sparkle(Canvas c, float cx, float cy, float s, Paint p) {
        fill(p);
        star(c, cx - s * 0.075f, cy + s * 0.075f, s * 0.375f, p);
        star(c, cx + s * 0.275f, cy - s * 0.275f, s * 0.16f, p);
    }

    private static void star(Canvas c, float cx, float cy, float r, Paint p) {
        float k = r * 0.24f;
        PATH.rewind();
        PATH.moveTo(cx, cy - r);
        PATH.lineTo(cx + k, cy - k);
        PATH.lineTo(cx + r, cy);
        PATH.lineTo(cx + k, cy + k);
        PATH.lineTo(cx, cy + r);
        PATH.lineTo(cx - k, cy + k);
        PATH.lineTo(cx - r, cy);
        PATH.lineTo(cx - k, cy - k);
        PATH.close();
        c.drawPath(PATH, p);
    }

    /** Edicion de texto: cursor en I a la izquierda y cruceta de flechas a la derecha. */
    private static void edit(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.1f);
        float x0 = cx - s * 0.24f;
        c.drawLine(x0, cy - s * 0.42f, x0, cy + s * 0.42f, p);
        c.drawLine(x0 - s * 0.12f, cy - s * 0.42f, x0 + s * 0.12f, cy - s * 0.42f, p);
        c.drawLine(x0 - s * 0.12f, cy + s * 0.42f, x0 + s * 0.12f, cy + s * 0.42f, p);
        float x1 = cx + s * 0.24f, arm = s * 0.22f, tip = s * 0.08f;
        c.drawLine(x1 - arm, cy, x1 + arm, cy, p);
        c.drawLine(x1, cy - arm, x1, cy + arm, p);
        c.drawLine(x1 - arm, cy, x1 - arm + tip, cy - tip, p);          // puntas de las cuatro flechas
        c.drawLine(x1 - arm, cy, x1 - arm + tip, cy + tip, p);
        c.drawLine(x1 + arm, cy, x1 + arm - tip, cy - tip, p);
        c.drawLine(x1 + arm, cy, x1 + arm - tip, cy + tip, p);
        c.drawLine(x1, cy - arm, x1 - tip, cy - arm + tip, p);
        c.drawLine(x1, cy - arm, x1 + tip, cy - arm + tip, p);
        c.drawLine(x1, cy + arm, x1 - tip, cy + arm - tip, p);
        c.drawLine(x1, cy + arm, x1 + tip, cy + arm - tip, p);
    }

    /** Traducir: dos burbujas, una con una A y otra con lineas de otro texto. */
    private static void translate(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.1f);
        RECT.set(cx - s * 0.46f, cy - s * 0.46f, cx + s * 0.06f, cy - s * 0.04f);     // burbuja de arriba a la izquierda
        c.drawRoundRect(RECT, s * 0.1f, s * 0.1f, p);
        c.drawLine(cx - s * 0.36f, cy - s * 0.04f, cx - s * 0.42f, cy + s * 0.08f, p);  // su rabillo
        c.drawLine(cx - s * 0.42f, cy + s * 0.08f, cx - s * 0.26f, cy - s * 0.04f, p);
        PATH.rewind();                                                                  // la A
        PATH.moveTo(cx - s * 0.32f, cy - s * 0.12f);
        PATH.lineTo(cx - s * 0.2f, cy - s * 0.38f);
        PATH.lineTo(cx - s * 0.08f, cy - s * 0.12f);
        c.drawPath(PATH, p);
        c.drawLine(cx - s * 0.27f, cy - s * 0.21f, cx - s * 0.13f, cy - s * 0.21f, p);
        RECT.set(cx - s * 0.06f, cy + s * 0.04f, cx + s * 0.46f, cy + s * 0.46f);     // burbuja de abajo a la derecha
        c.drawRoundRect(RECT, s * 0.1f, s * 0.1f, p);
        c.drawLine(cx + s * 0.36f, cy + s * 0.04f, cx + s * 0.42f, cy - s * 0.08f, p);
        c.drawLine(cx + s * 0.42f, cy - s * 0.08f, cx + s * 0.26f, cy + s * 0.04f, p);
        c.drawLine(cx + s * 0.06f, cy + s * 0.19f, cx + s * 0.34f, cy + s * 0.19f, p);  // lineas de texto
        c.drawLine(cx + s * 0.06f, cy + s * 0.31f, cx + s * 0.24f, cy + s * 0.31f, p);
    }

    /** Teclado dividido: dos medias cajas con una fila de teclas insinuada. */
    private static void split(Canvas c, float cx, float cy, float s, Paint p) {
        RECT.set(cx - s * 0.46f, cy - s * 0.28f, cx - s * 0.07f, cy + s * 0.28f);
        c.drawRoundRect(RECT, s * 0.08f, s * 0.08f, p);
        RECT.set(cx + s * 0.07f, cy - s * 0.28f, cx + s * 0.46f, cy + s * 0.28f);
        c.drawRoundRect(RECT, s * 0.08f, s * 0.08f, p);
        c.drawLine(cx - s * 0.36f, cy - s * 0.09f, cx - s * 0.17f, cy - s * 0.09f, p);
        c.drawLine(cx - s * 0.36f, cy + s * 0.09f, cx - s * 0.17f, cy + s * 0.09f, p);
        c.drawLine(cx + s * 0.17f, cy - s * 0.09f, cx + s * 0.36f, cy - s * 0.09f, p);
        c.drawLine(cx + s * 0.17f, cy + s * 0.09f, cx + s * 0.36f, cy + s * 0.09f, p);
    }

    /** Cinta de lo escrito: tres lineas cortas de texto. */
    private static void strip(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.12f);
        c.drawLine(cx - s * 0.4f, cy - s * 0.25f, cx + s * 0.4f, cy - s * 0.25f, p);
        c.drawLine(cx - s * 0.4f, cy, cx + s * 0.15f, cy, p);
        c.drawLine(cx - s * 0.4f, cy + s * 0.25f, cx + s * 0.3f, cy + s * 0.25f, p);
    }

    /** Idioma: globo con ecuador y un meridiano. */
    private static void globe(Canvas c, float cx, float cy, float s, Paint p) {
        float r = s * 0.4f;
        c.drawCircle(cx, cy, r, p);
        c.drawLine(cx - r, cy, cx + r, cy, p);
        RECT.set(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r);
        c.drawOval(RECT, p);
    }

    /** Ocultar el teclado: un ∨ dentro de una caja. */
    private static void hide(Canvas c, float cx, float cy, float s, Paint p) {
        RECT.set(cx - s * 0.44f, cy - s * 0.32f, cx + s * 0.44f, cy + s * 0.32f);
        c.drawRoundRect(RECT, s * 0.1f, s * 0.1f, p);
        PATH.rewind();
        PATH.moveTo(cx - s * 0.16f, cy - s * 0.07f);
        PATH.lineTo(cx, cy + s * 0.09f);
        PATH.lineTo(cx + s * 0.16f, cy - s * 0.07f);
        c.drawPath(PATH, p);
    }

    /** "...": tres puntos. */
    private static void more(Canvas c, float cx, float cy, float s, Paint p) {
        fill(p);
        for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.25f, cy, s * 0.09f, p);
    }
}
