package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Idioma de la app (menus del teclado y pantalla de ajustes): el del sistema o uno fijo. */
final class AppLocale {
    private AppLocale() {}

    static final String[] CHOICES = {"system", "es", "en", "de"};

    /**
     * Envuelve el contexto con el idioma elegido; con "system" lo devuelve tal cual. La Configuration de
     * sobreescritura lleva SOLO el idioma (y nada de orientacion, alto de pantalla, densidad ni escala de letra):
     * una copia completa de la configuracion del momento dejaba esos valores congelados, y un contexto creado en
     * vertical seguia creyendo la pantalla alta tras girar. Con el delta minimo el contexto sigue al base.
     */
    static Context wrap(Context base) {
        String lang = Prefs.appLang(base);
        if (lang == null || "system".equals(lang)) return base;
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration conf = new Configuration();
        conf.fontScale = 0f;             // "sin definir": que mande la escala de letra del sistema (por defecto seria 1)
        conf.setLocale(locale);
        return base.createConfigurationContext(conf);
    }

    /** Idioma efectivo ("es", "en", "de") para textos generados en codigo. */
    static String current(Context c) {
        String lang = Prefs.appLang(c);
        if (lang != null && !"system".equals(lang)) return lang;
        String sys = Locale.getDefault().getLanguage();
        return "en".equals(sys) || "de".equals(sys) ? sys : "es".equals(sys) ? "es" : "en";
    }
}
