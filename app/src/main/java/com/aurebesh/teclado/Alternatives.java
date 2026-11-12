package com.aurebesh.teclado;

import java.util.HashMap;
import java.util.Map;

/**
 * Alternativas de pulsacion larga de las letras, por idioma del teclado. Cada idioma ofrece solo lo que tiene
 * sentido en el: en espanol las vocales con tilde (y dieresis en la u), sin c con cedilla ni eszett; en ingles
 * ademas la n con virgulilla y la c con cedilla; en aleman la eszett en la s y los acentos franceses en las
 * vocales (las diereses tienen tecla propia). Lo que no esta aqui sigue en la pagina de acentos 3/3.
 * La primera alternativa es la que sale al soltar sin mover; {@link #hint} dice cual se insinua en la tecla.
 */
final class Alternatives {
    private Alternatives() {}

    private static final Map<String, String[]> ES = new HashMap<>();
    private static final Map<String, String[]> EN = new HashMap<>();
    private static final Map<String, String[]> DE = new HashMap<>();

    static {
        ES.put("a", new String[]{"\u00e1", "\u00e0", "\u00e2", "\u00e4", "\u00e3"});
        ES.put("e", new String[]{"\u00e9", "\u00e8", "\u00ea", "\u00eb"});
        ES.put("i", new String[]{"\u00ed", "\u00ec", "\u00ee", "\u00ef"});
        ES.put("o", new String[]{"\u00f3", "\u00f2", "\u00f4", "\u00f6", "\u00f5"});
        ES.put("u", new String[]{"\u00fa", "\u00fc", "\u00f9", "\u00fb"});

        EN.put("a", new String[]{"\u00e1", "\u00e0", "\u00e2", "\u00e4", "\u00e3", "\u00e5", "\u00e6"});
        EN.put("e", new String[]{"\u00e9", "\u00e8", "\u00ea", "\u00eb"});
        EN.put("i", new String[]{"\u00ed", "\u00ec", "\u00ee", "\u00ef"});
        EN.put("o", new String[]{"\u00f3", "\u00f2", "\u00f4", "\u00f6", "\u00f5", "\u00f8", "\u0153"});
        EN.put("u", new String[]{"\u00fa", "\u00f9", "\u00fb", "\u00fc"});
        EN.put("n", new String[]{"\u00f1"});
        EN.put("c", new String[]{"\u00e7"});

        DE.put("a", new String[]{"\u00e1", "\u00e0", "\u00e2", "\u00e6"});
        DE.put("e", new String[]{"\u00e9", "\u00e8", "\u00ea", "\u00eb"});
        DE.put("i", new String[]{"\u00ed", "\u00ec", "\u00ee", "\u00ef"});
        DE.put("o", new String[]{"\u00f3", "\u00f2", "\u00f4", "\u00f8", "\u0153"});
        DE.put("u", new String[]{"\u00fa", "\u00f9", "\u00fb"});
        DE.put("s", new String[]{"\u00df"});
        DE.put("n", new String[]{"\u00f1"});
        DE.put("c", new String[]{"\u00e7"});
    }

    private static Map<String, String[]> table(String lang) {
        return "en".equals(lang) ? EN : "de".equals(lang) ? DE : ES;
    }

    /** Alternativas de la letra (en minusculas) en ese idioma, la principal primero; null si no tiene. */
    static String[] alts(String lang, String letter) {
        return letter == null ? null : table(lang).get(letter);
    }

    /**
     * Alternativa que se insinua en la cara de la tecla, o null si ninguna: en espanol la vocal con tilde,
     * en aleman la eszett de la s; en ingles nada (los acentos son ocasionales y la tecla queda limpia).
     */
    static String hint(String lang, String letter) {
        if (letter == null) return null;
        if ("en".equals(lang)) return null;
        if ("de".equals(lang)) return "s".equals(letter) ? "\u00df" : null;
        String[] a = ES.get(letter);
        return a == null ? null : a[0];
    }
}
