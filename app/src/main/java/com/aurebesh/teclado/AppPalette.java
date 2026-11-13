package com.aurebesh.teclado;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.TextView;

/**
 * Paleta de la pantalla de ajustes derivada del tema efectivo del teclado ({@link Prefs#theme}): la app entera se
 * viste con los colores del teclado en vez de con una paleta fija clara u oscura. Cada color de la app sale de un
 * campo del {@link Themes.Theme}: fondo de ventana y barras del sistema = bg; tarjeta = keyBg; superficie secundaria
 * (chips sin seleccionar, buscador, asa del panel) = specialBg; texto principal = glyph; texto secundario = hint;
 * acento (boton Activar, chips seleccionados, anillo de la muestra elegida, interruptores activos, iconos de categoria)
 * = accent con onAccent encima; separadores = hint al 28 %; pulsacion = pressed; borde de tarjeta = keyStroke.
 * <p>
 * Dos correcciones para que todo siga legible: si la tecla apenas se distingue del fondo (Datapad, Templo Sith: teclas
 * casi negras solo trazadas) la tarjeta se tiñe mezclando un 8 % del glifo sobre el fondo; y cada pareja texto/fondo
 * se comprueba (4,5:1 para el texto, 3:1 para iconos y acentos) y, si el tema no la da, el color se corrige hacia la
 * tinta del glifo (o hacia blanco o negro). Contraste (negro, blanco, amarillo) pasa por aqui: su amarillo sobre la
 * tarjeta blanca se oscurece para los iconos, pero el boton y los chips seleccionados siguen amarillos con texto negro.
 */
final class AppPalette {
    final String themeId;        // id del tema efectivo del que sale la paleta
    final Themes.Theme theme;
    final boolean dark;          // fondo oscuro: iconos claros en las barras del sistema
    final int bg;                // ventana, barra de estado y de navegacion, barra fina del titulo
    final int card;              // tarjetas, panel de vista previa, dialogos
    final int cardStroke;        // borde de tarjeta (0 = sin borde)
    final float cardStrokeDp;
    final int surface2;          // chips sin seleccionar, campo del buscador, asa del panel, tarjetas de automatico
    final int text;              // texto principal sobre tarjeta y superficie secundaria (>= 4,5:1)
    final int secondary;         // texto secundario sobre tarjeta y superficie secundaria (>= 4,5:1)
    final int onBg;              // texto principal directamente sobre el fondo: cabecera, titulos de pagina, barra fina
    final int onBgSecondary;     // texto secundario sobre el fondo: rotulos de grupo, pie de pagina
    final int accent;            // relleno de acento: boton Activar, chips seleccionados
    final int onAccent;          // texto e iconos sobre el acento
    final int accentInk;         // el acento como tinta sobre la tarjeta (iconos, anillo, interruptores): >= 3:1
    final int accentTile;        // caja del icono de categoria: acento muy transparente
    final int divider;           // separadores: hint al 28 %
    final int pressed;           // pulsacion (ondas)
    final int ripple;            // color de la onda sobre tarjeta
    final int thumbOff, trackOff;   // interruptor apagado
    final int trackSolid;        // pista del deslizador sin recorrer

    /** Paleta del tema efectivo (los automaticos ya resueltos con el modo oscuro del telefono). */
    static AppPalette of(Context c) {
        return new AppPalette(Themes.byId(Prefs.theme(c)));
    }

    AppPalette(Themes.Theme t) {
        theme = t;
        themeId = t.id;
        bg = t.bg;
        dark = contrast(0xFFFFFFFF, bg) >= contrast(0xFF000000, bg);

        // Tarjeta: la tecla; si apenas se distingue del fondo, el fondo teñido con el glifo, desde un 8 % y subiendo
        // hasta que la tarjeta se separe del fondo (1,10:1; Templo Sith no llegaba con el 8 %)
        boolean tinted = contrast(t.keyBg, t.bg) < 1.10;
        int tintedCard = t.keyBg;
        if (tinted) {
            float f = 0.08f;
            tintedCard = blend(t.bg, t.glyph, f);
            while (contrast(tintedCard, t.bg) < 1.10 && f < 0.40f) { f += 0.02f; tintedCard = blend(t.bg, t.glyph, f); }
        }
        card = tintedCard;
        cardStroke = t.keyStroke;
        cardStrokeDp = t.keyStroke == 0 ? 0f : Math.max(1f, Math.min(t.strokeDp, 2f));

        // Texto principal: el glifo, corregido si la tarjeta no le da 4,5:1
        text = ensureContrast(t.glyph, card, 4.5);

        // Superficie secundaria: teclas de funcion; si se confunde con la tarjeta, la tarjeta tenida con un 12 % de tinta
        int s2 = t.specialBg;
        if (contrast(s2, card) < 1.15) s2 = blend(card, text, 0.12f);
        surface2 = s2;

        // Texto secundario: la pista latina, oscurecida o aclarada hacia la tinta hasta dar 4,5:1 sobre la tarjeta
        secondary = toward(t.hint, text, 4.5, card, surface2);

        // Sobre el fondo (sin tarjeta) los mismos textos, corregidos aparte: en Contraste glifo y fondo son negros
        onBg = contrast(text, bg) >= 4.5 ? text : (isLight(bg) ? 0xFF000000 : 0xFFFFFFFF);   // blanco o negro del todo, no un gris justo
        onBgSecondary = toward(t.hint, onBg, 4.5, bg);

        // Acento y lo que va encima (3:1 como minimo; si el tema no lo da, blanco o negro segun el acento)
        accent = t.accent;
        onAccent = contrast(t.onAccent, accent) >= 3.0 ? t.onAccent : (contrast(0xFF000000, accent) >= contrast(0xFFFFFFFF, accent) ? 0xFF000000 : 0xFFFFFFFF);
        accentInk = toward(accent, text, 3.0, card, surface2);
        accentTile = alpha(accent, 0x2E);

        divider = alpha(t.hint, 0x47);
        pressed = t.pressed;
        ripple = alpha(t.pressed, 0x99);
        thumbOff = isLight(card) ? 0xFFFFFFFF : secondary;
        trackOff = blend(card, text, 0.25f);
        trackSolid = blend(card, text, 0.25f);
    }

    // ------------------------------------------------------------------ colores

    /** Luminancia relativa (WCAG) de un color opaco, de 0 (negro) a 1 (blanco). */
    static double luminance(int c) {
        return 0.2126 * channel((c >> 16) & 0xFF) + 0.7152 * channel((c >> 8) & 0xFF) + 0.0722 * channel(c & 0xFF);
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Razon de contraste WCAG entre dos colores opacos (1 a 21). */
    static double contrast(int a, int b) {
        double la = luminance(a) + 0.05, lb = luminance(b) + 0.05;
        return la > lb ? la / lb : lb / la;
    }

    /** True si sobre el color se lee mejor en negro que en blanco. */
    static boolean isLight(int c) {
        return contrast(0xFF000000, c) >= contrast(0xFFFFFFFF, c);
    }

    /** Mezcla lineal de {@code from} hacia {@code to} ({@code f} de 0 a 1), opaca. */
    static int blend(int from, int to, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** El mismo color con otra opacidad (0 a 255). */
    static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /**
     * {@code fg} si da al menos {@code min} de contraste sobre {@code bg}; si no, se mueve hacia negro o blanco (lo
     * que mas contraste de sobre el fondo) en pasos del 5 % hasta conseguirlo.
     */
    static int ensureContrast(int fg, int bg, double min) {
        if (contrast(fg, bg) >= min) return fg;
        int target = isLight(bg) ? 0xFF000000 : 0xFFFFFFFF;
        return toward(fg, target, min, bg);
    }

    /** Mueve {@code fg} hacia {@code target} en pasos del 5 % hasta dar {@code min} de contraste sobre todos los fondos. */
    static int toward(int fg, int target, double min, int... surfaces) {
        for (int step = 0; step <= 20; step++) {
            int c = blend(fg, target, step / 20f);
            boolean ok = true;
            for (int s : surfaces) if (contrast(c, s) < min) { ok = false; break; }
            if (ok) return c;
        }
        return target;
    }

    // ------------------------------------------------------------------ piezas de interfaz

    /** Tinte del boton del interruptor: acento encendido, blanco o texto secundario apagado. */
    ColorStateList switchThumb() {
        int[][] states = {{android.R.attr.state_checked}, {}};
        return new ColorStateList(states, new int[]{accentInk, thumbOff});
    }

    /** Tinte de la pista del interruptor: acento translucido encendido, tinta muy rebajada apagado. */
    ColorStateList switchTrack() {
        int[][] states = {{android.R.attr.state_checked}, {}};
        return new ColorStateList(states, new int[]{alpha(accent, 0x80), trackOff});
    }

    /** Tinte de botones de radio, casillas, deslizadores y subrayado de campos: el acento como tinta. */
    ColorStateList accentTint() {
        return ColorStateList.valueOf(accentInk);
    }

    /** Subrayado de un campo de texto: acento con el foco, texto secundario sin el. */
    ColorStateList fieldTint() {
        int[][] states = {{android.R.attr.state_focused}, {}};
        return new ColorStateList(states, new int[]{accentInk, secondary});
    }

    /** Fondo redondeado de tarjeta (con el borde del tema si lo tiene). */
    GradientDrawable cardDrawable(float radiusPx, float density) {
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(card);
        bgd.setCornerRadius(radiusPx);
        if (cardStroke != 0 && cardStrokeDp > 0) bgd.setStroke(Math.max(1, Math.round(cardStrokeDp * density)), cardStroke);
        return bgd;
    }

    /** Ventana con el fondo del tema y barras de estado y navegacion del mismo color, con iconos claros u oscuros segun el fondo. */
    void applyToWindow(Window w) {
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(bg));
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        View decor = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = decor.getWindowInsetsController();
            if (ic != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }
        // Tambien por banderas (API < 30 y como respaldo): iconos oscuros solo con fondo claro
        int flags = decor.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    /**
     * Constructor de dialogo con el tema de Material claro u oscuro segun la tarjeta (titulo y mensaje legibles sobre
     * ella) cuyo {@code show()} ademas viste el dialogo con {@link #style}: fondo de tarjeta y botones con el acento.
     */
    AlertDialog.Builder dialog(Context c) {
        int themeRes = isLight(card) ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert;
        return new AlertDialog.Builder(c, themeRes) {
            @Override public AlertDialog show() {
                AlertDialog d = super.show();
                style(d);
                return d;
            }
        };
    }

    /** Tras mostrar un dialogo: fondo redondeado del color de la tarjeta y botones con el acento (no el de Material). */
    void style(AlertDialog d) {
        if (d == null || d.getWindow() == null) return;
        float density = d.getContext().getResources().getDisplayMetrics().density;
        d.getWindow().setBackgroundDrawable(cardDrawable(16 * density, density));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = d.getButton(which);
            if (b != null) b.setTextColor(accentInk);
        }
        View message = d.findViewById(android.R.id.message);
        if (message instanceof TextView) ((TextView) message).setTextColor(text);
    }

}
