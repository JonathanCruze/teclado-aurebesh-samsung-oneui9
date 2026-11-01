package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Idioma de la app (menus del teclado y pantalla de ajustes): el del sistema o uno fijo. */
final class AppLocale {
    private AppLocale() {}

    static final String[] CHOICES = {"system", "es", "en", "de"};

    /** Envuelve el contexto con el idioma elegido; con "system" lo devuelve tal cual. */
    static Context wrap(Context base) {
        String lang = Prefs.appLang(base);
        if (lang == null || "system".equals(lang)) return base;
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration conf = new Configuration(base.getResources().getConfiguration());
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
