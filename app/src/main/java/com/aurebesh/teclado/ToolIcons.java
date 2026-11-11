package com.aurebesh.teclado;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Iconos de las herramientas de la cinta (TOOL_* de {@link AurebeshKeyboardView}) dibujados en Canvas, para
 * que la cinta y la cuadricula "..." compartan el mismo dibujo. {@code size} es el alto del icono en px;
 * {@code p} lleva el color (se usa en relleno y en trazo de 2 px segun el icono).
 */
final class ToolIcons {
    private ToolIcons() {}

    static void draw(Canvas c, int tool, float cx, float cy, float size, Paint p) {
        // Provisional: un circulo; cada icono se dibuja con su forma propia en la vista.
        Paint.Style was = p.getStyle();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.12f);
        c.drawCircle(cx, cy, size * 0.4f, p);
        p.setStyle(was);
    }
}
