package com.aurebesh.teclado;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * Panel de edicion de texto (como el de Samsung Keyboard): cruceta de flechas con repeticion, Seleccionar (las
 * flechas extienden la seleccion), Seleccionar todo, Cortar, Copiar, Pegar, Deshacer, Rehacer, Inicio y Fin.
 */
final class EditTray extends LinearLayout {
    interface Host {
        /** Flecha: keyCode es KEYCODE_DPAD_LEFT/RIGHT/UP/DOWN; select = con mayusculas (extiende la seleccion). */
        void onEditMove(int keyCode, boolean select);
        /** Accion del menu contextual: android.R.id.selectAll / cut / copy / paste / undo / redo. */
        void onEditAction(int actionId);
        /** Inicio (end=false) o fin (end=true) del texto. */
        void onEditHome(boolean end, boolean select);
        void onEditClose();
    }

    private final Host host;

    EditTray(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
    }

    void show() { }

    void applyTheme(Themes.Theme t) { }

    void setFeedback(boolean vibrate, boolean sound) { }

    /** Numero de caracteres seleccionados (para la cabecera), -1 si no se sabe. */
    void setSelection(int count) { }
}
