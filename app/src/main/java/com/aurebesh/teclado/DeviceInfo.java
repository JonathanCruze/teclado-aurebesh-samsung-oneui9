package com.aurebesh.teclado;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.util.Locale;

/**
 * Datos del dispositivo: fabricante y version de One UI. El teclado esta optimizado y probado en
 * Samsung One UI 9.0; en cualquier otro dispositivo o version se avisa de que es experimental.
 */
final class DeviceInfo {
    private DeviceInfo() {}

    /** Solo pruebas: valor simulado de SEM_PLATFORM_INT (0 = no es One UI). */
    static Integer semOverride;

    static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * Version de One UI ("9.0", "6.1"...) o null si no es One UI. Samsung publica el campo
     * Build.VERSION.SEM_PLATFORM_INT (One UI = (valor - 90000) / 10000, decimal en las centenas).
     */
    static String oneUi() {
        int value;
        if (semOverride != null) {
            value = semOverride;
        } else {
            try {
                value = Build.VERSION.class.getField("SEM_PLATFORM_INT").getInt(null);
            } catch (Exception e) {
                return null;
            }
        }
        if (value < 90000) return null;
        int v = value - 90000;
        return (v / 10000) + "." + ((v % 10000) / 100);
    }

    /** Samsung con One UI 9.x: la combinacion en la que se probo el teclado. */
    static boolean optimized() {
        String v = oneUi();
        return isSamsung() && v != null && v.startsWith("9.");
    }

    /** Descripcion corta del telefono para la pantalla de ajustes. */
    static String label() {
        String release = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
        String v = oneUi();
        if (v != null) return "Samsung One UI " + v + " \u00b7 Android " + release;
        String maker = capitalize(Build.MANUFACTURER);
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String device = (maker + " " + model).trim();
        if (device.isEmpty()) device = "Android";
        return device + " \u00b7 Android " + release;
    }

    /** RAM total en GB segun el sistema (suele quedar algo por debajo de la cifra comercial), o 0 si falla. */
    static float totalRamGb(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0f;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024f * 1024f * 1024f);
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    /** RAM "comercial" (11,2 GB medidos -> 12), o 0 si no se sabe. */
    static int ramGbLabel(Context c) {
        float g = totalRamGb(c);
        if (g <= 0f) return 0;
        int[] sizes = {2, 3, 4, 6, 8, 12, 16, 24, 32};
        for (int sz : sizes) if (g <= sz + 0.3f) return sz;
        return Math.round(g);
    }

    /**
     * Familia Galaxy a partir del numero de modelo (SM-F9xx = Z Fold, SM-F7xx = Z Flip, SM-S9xx = S,
     * SM-A = A, SM-X/T = Tab) con el numero entre parentesis; en otros fabricantes, marca y modelo.
     */
    static String family() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String m = model.toUpperCase(Locale.ROOT);
        if (!isSamsung() || !m.startsWith("SM-")) {
            String d = (capitalize(Build.MANUFACTURER) + " " + model).trim();
            return d.isEmpty() ? "Android" : d;
        }
        String code = m.substring(3);
        String fam;
        if (code.startsWith("F9")) fam = "Galaxy Z Fold";
        else if (code.startsWith("F7")) fam = "Galaxy Z Flip";
        else if (code.startsWith("S9")) fam = "Galaxy S";
        else if (code.startsWith("N9")) fam = "Galaxy Note";
        else if (code.startsWith("A")) fam = "Galaxy A";
        else if (code.startsWith("M")) fam = "Galaxy M";
        else if (code.startsWith("X") || code.startsWith("T") || code.startsWith("P")) fam = "Galaxy Tab";
        else fam = "Galaxy";
        return fam + " (" + model + ")";
    }

    /** Ancho minimo de la pantalla actual en dp (>= 600 en un Fold desplegado o una tableta). */
    static int smallestWidthDp(Context c) {
        return c.getResources().getConfiguration().smallestScreenWidthDp;
    }

    static boolean wideScreen(Context c) {
        return smallestWidthDp(c) >= 600;
    }

    private static String capitalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
