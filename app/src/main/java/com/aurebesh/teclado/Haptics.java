package com.aurebesh.teclado;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Respuesta haptica de las bandejas (emojis, portapapeles) con las mismas preferencias que las teclas:
 * nada si "Vibracion al pulsar" esta apagada; con el nivel del sistema, performHapticFeedback de la vista;
 * con suave/media/fuerte, un pulso propio del vibrador (6/10/16 ms a 70/150/255), el mismo que usa
 * AurebeshKeyboardView para las teclas. Las preferencias se leen en cada toque, asi que un cambio hecho en
 * la app se nota al instante sin que la bandeja tenga que enterarse.
 */
final class Haptics {
    private Haptics() {}

    /** Duracion (ms) y amplitud (1-255) del pulso propio por nivel 1..3 (suave, media, fuerte). */
    private static final int[] PULSE_MS = {0, 6, 10, 16};
    private static final int[] PULSE_AMP = {0, 70, 150, 255};

    /** Pulsacion: tecla de la bandeja, celda de emoji, fila o chip del portapapeles, botones. */
    static void tap(View v) {
        // KEYBOARD_PRESS (API 27) vale lo mismo que KEYBOARD_TAP (3); asi compila con cualquier android.jar
        feedback(v, HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** Tic ligero: repeticion de borrar, cambio de pestana o de elemento al deslizar. */
    static void tick(View v) {
        feedback(v, HapticFeedbackConstants.CLOCK_TICK);
    }

    private static void feedback(View v, int constant) {
        if (v == null) return;
        Context c = v.getContext();
        if (!Prefs.vibrate(c)) return;
        int level = Math.min(Prefs.vibrationLevel(c), 3);
        if (level > 0 && oneShot(c, PULSE_MS[level], PULSE_AMP[level])) return;
        v.performHapticFeedback(constant);
    }

    /** Pulso del vibrador; false si no hay vibrador o fallo (sin permiso), para caer en la respuesta del sistema. */
    private static boolean oneShot(Context c, int ms, int amplitude) {
        try {
            Vibrator vib = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return false;
            int amp = vib.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE;
            vib.vibrate(VibrationEffect.createOneShot(ms, amp));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
