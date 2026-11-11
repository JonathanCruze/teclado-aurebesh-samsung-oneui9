package com.aurebesh.teclado;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * Cuadricula "..." con todas las herramientas de la cinta (como la de Samsung Keyboard): tocar una la ejecuta;
 * en modo "Editar" se eligen y ordenan las que van en la cinta. Ocupa el sitio de las teclas, como las demas bandejas.
 */
final class ToolsTray extends LinearLayout {
    interface Host {
        void onToolsPick(int tool);            // ejecutar una herramienta (ids TOOL_*)
        void onToolsOrder(int[] barItems);     // nuevo orden/eleccion de la cinta (se guarda en Prefs.TOOLBAR_ITEMS)
        void onToolsClose();
    }

    private final Host host;

    ToolsTray(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
    }

    /** Muestra la cuadricula: barItems es la cinta actual, allTools el catalogo y available que herramientas tienen sentido ahora. */
    void show(int[] barItems, int[] allTools, boolean[] available) { }

    void applyTheme(Themes.Theme t) { }

    void setFeedback(boolean vibrate, boolean sound) { }
}
