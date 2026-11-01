package com.aurebesh.teclado;

import android.os.Build;

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

    private static String capitalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
