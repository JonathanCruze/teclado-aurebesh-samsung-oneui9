package com.aurebesh.teclado;

/**
 * Temas del teclado. Cada tema cambia la construccion de la tecla (relleno, borde, esquina,
 * sombra, halo), no solo la paleta, para que se distingan de un vistazo.
 */
final class Themes {
    private Themes() {}

    static final class Theme {
        final String id;          // clave guardada en preferencias
        final int name;           // nombre visible (recurso)
        final int bg;             // fondo del teclado
        final int keyBg;          // tecla de letra
        final int keyStroke;      // borde de tecla (0 = sin borde)
        final int glyph;          // tinta de los glifos
        final int glow;           // halo alrededor del glifo (0 = ninguno)
        final int icon;           // iconos de las teclas de funcion
        final int accent;         // relleno de Enter y de mayusculas activas
        final int onAccent;       // icono sobre el acento
        final int specialBg;      // teclas de funcion
        final int spaceBg;        // barra espaciadora
        final int pressed;        // tecla pulsada
        final int preview;        // fondo de la burbuja
        final int previewText;    // glifo de la burbuja
        final int previewLatin;   // letra latina de la burbuja
        final int hint;           // letra latina pequena en la tecla
        final float radiusDp;     // esquina de tecla
        final boolean shadow;     // sombra de 1 px bajo la tecla
        final float strokeDp;     // grosor del borde de tecla en dp (1 salvo que el tema pida otro)

        Theme(String id, int name, int bg, int keyBg, int keyStroke, int glyph, int glow, int icon,
              int accent, int onAccent, int specialBg, int spaceBg, int pressed, int preview,
              int previewText, int previewLatin, int hint, float radiusDp, boolean shadow) {
            this(id, name, bg, keyBg, keyStroke, glyph, glow, icon, accent, onAccent, specialBg, spaceBg, pressed,
                    preview, previewText, previewLatin, hint, radiusDp, shadow, 1f);
        }

        Theme(String id, int name, int bg, int keyBg, int keyStroke, int glyph, int glow, int icon,
              int accent, int onAccent, int specialBg, int spaceBg, int pressed, int preview,
              int previewText, int previewLatin, int hint, float radiusDp, boolean shadow, float strokeDp) {
            this.id = id; this.name = name; this.bg = bg; this.keyBg = keyBg; this.keyStroke = keyStroke;
            this.glyph = glyph; this.glow = glow; this.icon = icon; this.accent = accent; this.onAccent = onAccent;
            this.specialBg = specialBg; this.spaceBg = spaceBg; this.pressed = pressed; this.preview = preview;
            this.previewText = previewText; this.previewLatin = previewLatin; this.hint = hint;
            this.radiusDp = radiusDp; this.shadow = shadow; this.strokeDp = strokeDp;
        }
    }

    /** Acero mate, teclas blancas con sombra, tinta azul noche. El acento es la propia tinta. */
    static final Theme ESTELAR = new Theme("estelar", R.string.theme_estelar,
            0xFFE3E8EE, 0xFFFFFFFF, 0, 0xFF1B2838, 0, 0xFF3E4C5B,
            0xFF1B2838, 0xFFFFFFFF, 0xFFCBD3DC, 0xFFFFFFFF, 0xFFB9C3CE, 0xFF1B2838,
            0xFFFFFFFF, 0xFFB9C3CE, 0xFF7A8794, 7f, true);

    /** Oscuro neutro; cian apagado solo en Enter y mayusculas. */
    static final Theme NOCHE = new Theme("noche", R.string.theme_noche,
            0xFF0E1419, 0xFF18222B, 0, 0xFFDCE6EE, 0, 0xFFA7B4C0,
            0xFF6FB6CF, 0xFF0E1419, 0xFF131B22, 0xFF18222B, 0xFF2C3A46, 0xFF24303A,
            0xFFFFFFFF, 0xFF8FA0AE, 0xFF6B7987, 7f, false);

    /** Pantalla de datapad: fondo casi negro, teclas solo trazadas, glifos azul luminoso con halo. */
    static final Theme DATAPAD = new Theme("datapad", R.string.theme_datapad,
            0xFF060A0E, 0xFF0A1219, 0xFF1C4556, 0xFF7FD3F0, 0xFF7FD3F0, 0xFF5FAFC9,
            0xFF7FD3F0, 0xFF06202A, 0xFF0C1920, 0xFF0A1219, 0xFF16384A, 0xFF0E2430,
            0xFFA8E8FB, 0xFF3F8AA3, 0xFF2F6E84, 3f, false);

    /** Grafito, glifos blancos, esquinas duras y un solo rojo apagado. */
    static final Theme IMPERIAL = new Theme("imperial", R.string.theme_imperial,
            0xFF1C1F23, 0xFF2B3035, 0, 0xFFF2F4F6, 0, 0xFFC7CCD2,
            0xFFB2302F, 0xFFFFFFFF, 0xFF24282C, 0xFF2B3035, 0xFF3F454C, 0xFF3A3F45,
            0xFFFFFFFF, 0xFF9AA2AA, 0xFF7E868E, 4f, false);

    /** Arena de Tatooine: tonos calidos, teclas hueso redondeadas, acento naranja de atardecer. */
    static final Theme ARENA = new Theme("arena", R.string.theme_arena,
            0xFFE6DCC8, 0xFFF8F2E7, 0, 0xFF3A2C1E, 0, 0xFF6B5A48,
            0xFFC8541F, 0xFFFFF4EA, 0xFFD6C9AF, 0xFFF8F2E7, 0xFFC9BBA0, 0xFF3A2C1E,
            0xFFFFF4EA, 0xFFB8A78E, 0xFF9A8A72, 10f, true);

    /**
     * Contraste (accesibilidad): fondo negro, teclas blancas con borde negro de 2 dp, glifo negro, acento amarillo
     * con icono negro. Sin halo ni sombra y esquinas de 4 dp, para que cada tecla se recorte sin matices.
     */
    static final Theme CONTRASTE = new Theme("contraste", R.string.theme_contraste,
            0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0, 0xFF000000,
            0xFFFFD600, 0xFF000000, 0xFFE6E6E6, 0xFFFFFFFF, 0xFFBDBDBD, 0xFFFFFFFF,
            0xFF000000, 0xFF000000, 0xFF000000, 4f, false, 2f);

    static final Theme[] ALL = {ESTELAR, NOCHE, DATAPAD, IMPERIAL, ARENA, CONTRASTE};
    static final Theme DEFAULT = ESTELAR;

    static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return DEFAULT;
    }
}
