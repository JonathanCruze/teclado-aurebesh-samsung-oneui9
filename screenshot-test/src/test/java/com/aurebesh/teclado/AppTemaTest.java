package com.aurebesh.teclado;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * La app de ajustes se viste con el tema efectivo del teclado (AppPalette sobre Prefs.theme) en vez de con la paleta
 * fija clara u oscura: el fondo de la ventana, la columna principal y las barras de estado y navegacion son el bg del
 * tema; las tarjetas son la tecla (o el fondo tenido si la tecla apenas se distingue, como en Datapad y Templo Sith)
 * con el canto del tema; los titulos sobre el fondo llevan la tinta del glifo; todo texto visible da 4,5:1 con lo que
 * tiene detras (3:1 sobre el acento); los automaticos siguen el modo oscuro del telefono y un tema fijo no; y cambiar
 * el tema con la pantalla abierta la rehace una sola vez conservando la pagina y lo escrito en el panel. Capturas
 * app_tema_<id>.png de la principal para los ocho temas y app_tema_auto_noche.png (Templos de noche).
 * Con el calificador xxhdpi de Robolectric la densidad es 3: 1080 px son 360 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppTemaTest {

    private static final int W = 1080;
    private static final int PHONE_H = 2340;
    /** Tolerancia (suma de diferencias RGB) al comparar un pixel con un color del tema. */
    private static final int TOL = 24;

    /** Las nueve paginas, en el orden de la lista, y sus titulos. */
    private static final String[] PAGES = {MainActivity.PAGE_KEYS, MainActivity.PAGE_STYLE, MainActivity.PAGE_SIZE,
            MainActivity.PAGE_SMART, MainActivity.PAGE_TOUCH, MainActivity.PAGE_CLIPBOARD, MainActivity.PAGE_AI,
            MainActivity.PAGE_PRIVACY, MainActivity.PAGE_HELP};
    private static final int[] PAGE_TITLES = {R.string.cat_keys, R.string.cat_style, R.string.cat_size, R.string.aj_sec_smart,
            R.string.cat_touch, R.string.aj_sec_clipboard, R.string.ai_section, R.string.cat_privacy, R.string.aj_sec_help};

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
    }

    // ------------------------------------------------------------------ ayudas: actividad y arbol de vistas

    private static ActivityController<MainActivity> controller() {
        return Robolectric.buildActivity(MainActivity.class).setup();
    }

    private static MainActivity create() { return controller().get(); }

    private static void idle() { ShadowLooper.shadowMainLooper().idle(); }

    /** Deja pasar las animaciones cortas de entrada de pagina (180 ms) y lo que haya encolado. */
    private static void settle() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(400));
        idle();
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static List<View> all(Activity a) {
        List<View> out = new ArrayList<>();
        collect(a.getWindow().getDecorView(), out);
        return out;
    }

    /** Todos los TextView cuyo texto es exactamente {@code s}. */
    private static List<TextView> textViews(Activity a, String s) {
        List<TextView> out = new ArrayList<>();
        for (View v : all(a)) if (v instanceof TextView && s.equals(((TextView) v).getText().toString())) out.add((TextView) v);
        return out;
    }

    private static TextView textView(Activity a, String s) {
        List<TextView> l = textViews(a, s);
        return l.isEmpty() ? null : l.get(0);
    }

    private static View byDescription(Activity a, String description) {
        for (View v : all(a)) {
            if (v.getContentDescription() != null && description.equals(v.getContentDescription().toString())) return v;
        }
        return null;
    }

    private static boolean visibleUpToRoot(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /** Alfa efectiva: el producto de la suya y la de todos sus ancestros. */
    private static float effectiveAlpha(View v) {
        float a = 1f;
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) a *= p.getAlpha();
        return a;
    }

    private static FrameLayout content(Activity a) { return (FrameLayout) a.findViewById(android.R.id.content); }

    /** El ScrollView de la pantalla principal (primer hijo del contenido de la ventana). */
    private static ScrollView homeScroll(Activity a) { return (ScrollView) content(a).getChildAt(0); }

    /** El panel de vista previa: la columna que contiene la copia del teclado. */
    private static View panelOf(Activity a) {
        for (View v : all(a)) if (v instanceof AurebeshKeyboardView) return (View) v.getParent();
        throw new AssertionError("panel de vista previa");
    }

    /** El campo de una linea del panel (pista "Escribe algo..."). */
    private static EditText panelField(Activity a) {
        for (View v : all(a)) {
            if (v instanceof EditText && a.getString(R.string.try_hint).equals(String.valueOf(((EditText) v).getHint()))) return (EditText) v;
        }
        throw new AssertionError("campo del panel");
    }

    /** La tarjeta (primer ancestro, ella incluida, con un fondo de color opaco) que contiene la vista. */
    private static View cardOf(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (Color.alpha(solidColor(p.getBackground())) == 255) return p;
        }
        throw new AssertionError("tarjeta de " + v);
    }

    /** Posicion de la vista dentro del decor (sumando margenes, traslaciones y desplazamientos). */
    private static int[] positionInDecor(View v, View decor) {
        int x = 0, y = 0;
        View p = v;
        while (p != null && p != decor) {
            x += p.getLeft() + Math.round(p.getTranslationX());
            y += p.getTop() + Math.round(p.getTranslationY());
            ViewGroup parent = (ViewGroup) p.getParent();
            x -= parent.getScrollX();
            y -= parent.getScrollY();
            p = parent;
        }
        return new int[]{x, y};
    }

    /** Mide y dispone la actividad entera a tamano de pantalla, dos veces: la segunda recoge el relleno que pide el panel. */
    private static void layoutActivity(Activity a) {
        View root = a.getWindow().getDecorView();
        for (int i = 0; i < 2; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(PHONE_H, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, W, PHONE_H);
            idle();
        }
    }

    private static Bitmap snapshot(Activity a) {
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(W, PHONE_H, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }

    private static void save(Bitmap b, String name) throws Exception {
        File out = new File(System.getProperty("shots.dir", "shots"), name);
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("SHOT " + out.getAbsolutePath() + " " + b.getWidth() + "x" + b.getHeight());
    }

    // ------------------------------------------------------------------ ayudas: colores

    private static String hex(int c) { return String.format(Locale.ROOT, "#%08X", c); }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static void assertColorNear(String what, int expected, int actual) {
        assertTrue(what + ": " + hex(actual) + " (esperado " + hex(expected) + ")", Color.alpha(actual) == 255 && dist(expected, actual) <= TOL);
    }

    /**
     * Color plano de un fondo: ColorDrawable, GradientDrawable (su relleno), el contenido de una RippleDrawable (no su
     * mascara), o lo que envuelven InsetDrawable y LayerDrawable. 0 si no hay relleno (transparente o desconocido).
     */
    private static int solidColor(Drawable d) {
        if (d == null) return 0;
        if (d instanceof ColorDrawable) return ((ColorDrawable) d).getColor();
        if (d instanceof GradientDrawable) {
            android.content.res.ColorStateList cs = ((GradientDrawable) d).getColor();
            return cs == null ? 0 : cs.getDefaultColor();
        }
        if (d instanceof InsetDrawable) return solidColor(((InsetDrawable) d).getDrawable());
        if (d instanceof RippleDrawable) {
            RippleDrawable r = (RippleDrawable) d;
            for (int i = 0; i < r.getNumberOfLayers(); i++) {
                if (r.getId(i) == android.R.id.mask) continue;
                int c = solidColor(r.getDrawable(i));
                if (c != 0) return c;
            }
            return 0;
        }
        if (d instanceof LayerDrawable) {
            LayerDrawable l = (LayerDrawable) d;
            for (int i = l.getNumberOfLayers() - 1; i >= 0; i--) {
                int c = solidColor(l.getDrawable(i));
                if (c != 0) return c;
            }
            return 0;
        }
        return 0;
    }

    /** {@code top} (con alfa) compuesto sobre {@code under} (opaco). */
    private static int over(int top, int under) {
        return AppPalette.blend(under | 0xFF000000, top | 0xFF000000, Color.alpha(top) / 255f);
    }

    /**
     * Color opaco que hay detras de una vista: el primer fondo opaco de ella o de sus ancestros, con los fondos
     * translucidos intermedios compuestos encima. 0 si no se llega a ninguno.
     */
    private static int backgroundBehind(View v) {
        List<Integer> layers = new ArrayList<>();
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            int c = solidColor(p.getBackground());
            if (Color.alpha(c) == 255) {
                int out = c;
                for (int i = layers.size() - 1; i >= 0; i--) out = over(layers.get(i), out);
                return out;
            }
            if (Color.alpha(c) > 0) layers.add(c);
        }
        return 0;
    }

    /** Color del fondo de la ventana (el decor lo puede envolver en un InsetDrawable). */
    private static int windowColor(Activity a) {
        int c = solidColor(a.getWindow().getDecorView().getBackground());
        assertEquals("el fondo de la ventana es un color opaco", 255, Color.alpha(c));
        return c;
    }

    private static String abbreviate(CharSequence s) {
        String t = s.toString().replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
    }

    /**
     * Textos visibles bajo el contenido de la ventana cuyo contraste con lo que tienen detras no llega al minimo:
     * {@code minText} en general y {@code minOnAccent} cuando el fondo es el acento (chips y botones principales).
     * Un campo vacio se juzga por su pista.
     */
    private static List<String> poorContrast(Activity a, AppPalette pal, double minText, double minOnAccent, int[] checked) {
        List<String> bad = new ArrayList<>();
        List<View> views = new ArrayList<>();
        collect(content(a), views);
        for (View v : views) {
            if (!(v instanceof TextView) || !visibleUpToRoot(v) || effectiveAlpha(v) < 0.99f) continue;
            TextView t = (TextView) v;
            CharSequence s = t.getText();
            int fg;
            if (s != null && s.length() > 0) {
                fg = t.getCurrentTextColor();
            } else if (t.getHint() != null && t.getHint().length() > 0) {
                s = "(pista) " + t.getHint();
                fg = t.getCurrentHintTextColor();
            } else {
                continue;
            }
            checked[0]++;
            int bg = backgroundBehind(t);
            if (bg == 0) { bad.add("sin fondo conocido: " + abbreviate(s)); continue; }
            if (Color.alpha(fg) < 255) fg = over(fg, bg);
            boolean onAccent = (bg & 0xFFFFFF) == (pal.accent & 0xFFFFFF);
            double min = onAccent ? minOnAccent : minText;
            double c = AppPalette.contrast(fg, bg);
            if (c < min) bad.add(String.format(Locale.ROOT, "%.2f:1 %s sobre %s: %s", c, hex(fg), hex(bg), abbreviate(s)));
        }
        return bad;
    }

    /**
     * Abre cada pagina y recoge los textos con poco contraste de la principal y de las nueve paginas; exige haber
     * examinado un buen numero de textos (la principal, las filas de cada pagina y el panel), para que un fallo al
     * resolver fondos no pase por "todo legible".
     */
    private static List<String> poorContrastEverywhere(MainActivity a, AppPalette pal, double minText, double minOnAccent) {
        List<String> bad = new ArrayList<>();
        int[] checked = {0};
        for (String s : poorContrast(a, pal, minText, minOnAccent, checked)) bad.add("principal: " + s);
        int home = checked[0];
        assertTrue("[" + pal.themeId + "] textos examinados en la principal: " + home, home >= 20);
        for (int i = 0; i < PAGES.length; i++) {
            SettingsTest.openCategory(a, PAGE_TITLES[i], PAGES[i]);
            settle();
            int before = checked[0];
            for (String s : poorContrast(a, pal, minText, minOnAccent, checked)) bad.add(PAGES[i] + ": " + s);
            assertTrue("[" + pal.themeId + "] textos examinados en " + PAGES[i] + ": " + (checked[0] - before), checked[0] - before >= 5);
            a.onBackPressed();
            settle();
        }
        assertTrue("[" + pal.themeId + "] textos examinados en total: " + checked[0], checked[0] >= 150);
        return bad;
    }

    /** Comprobaciones comunes de una actividad vestida con {@code t}: ventana, columna, barras del sistema y titulos. */
    private static AppPalette assertDressed(MainActivity a, Themes.Theme t) {
        AppPalette pal = new AppPalette(t);
        assertEquals(t.id, pal.themeId);
        assertEquals("[" + t.id + "] el tema efectivo es el pedido", t.id, Prefs.theme(a));
        assertEquals("[" + t.id + "] fondo de la ventana", hex(t.bg), hex(windowColor(a)));
        assertEquals("[" + t.id + "] fondo de la columna principal", hex(t.bg), hex(solidColor(homeScroll(a).getBackground())));
        assertEquals("[" + t.id + "] barra de estado del color del fondo", hex(t.bg), hex(a.getWindow().getStatusBarColor()));
        assertEquals("[" + t.id + "] barra de navegacion del color del fondo", hex(t.bg), hex(a.getWindow().getNavigationBarColor()));
        int flags = a.getWindow().getDecorView().getSystemUiVisibility();
        boolean lightIcons = (flags & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0;
        assertEquals("[" + t.id + "] iconos oscuros de la barra de estado solo con fondo claro", !pal.dark, lightIcons);
        assertEquals("[" + t.id + "] iconos de la barra de navegacion a juego", lightIcons, (flags & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0);
        List<TextView> titles = textViews(a, a.getString(R.string.app_name));
        assertEquals("[" + t.id + "] titulo grande y barra fina", 2, titles.size());
        for (TextView title : titles) assertEquals("[" + t.id + "] el titulo lleva la tinta sobre el fondo", hex(pal.onBg), hex(title.getCurrentTextColor()));
        assertTrue("[" + t.id + "] el titulo contrasta con el fondo", AppPalette.contrast(pal.onBg, t.bg) >= 4.5);
        return pal;
    }

    // ------------------------------------------------------------------ (a) Templo Sith: ventana, columna y titulo

    @Test
    public void sithVisteVentanaColumnaYTituloConSusColores() {
        Prefs.get(app).edit().putString(Prefs.THEME, Themes.SITH.id).apply();
        MainActivity a = create();
        assertEquals(hex(Themes.SITH.bg), hex(windowColor(a)));
        assertEquals(hex(Themes.SITH.bg), hex(solidColor(homeScroll(a).getBackground())));
        List<TextView> titles = textViews(a, a.getString(R.string.app_name));
        assertFalse("titulo de la app", titles.isEmpty());
        for (TextView title : titles) assertEquals("el titulo usa la tinta del glifo de Sith", hex(Themes.SITH.glyph), hex(title.getCurrentTextColor()));
        assertEquals("la paleta deja el glifo tal cual sobre el fondo", hex(Themes.SITH.glyph), hex(new AppPalette(Themes.SITH).onBg));
        // Nada de la paleta fija: ni el gris claro de colors.xml ni el morado de Material
        assertFalse(windowColor(a) == 0xFFE3E8EE);
        AppPalette pal = assertDressed(a, Themes.SITH);
        assertTrue("fondo oscuro: iconos claros en las barras", pal.dark);
        // Texto principal (glifo) sobre la tarjeta tenida y secundario legibles
        assertTrue(AppPalette.contrast(pal.text, pal.card) >= 4.5);
        assertTrue(AppPalette.contrast(pal.secondary, pal.card) >= 4.5);
        TextView tryTitle = textView(a, a.getString(R.string.try_title));
        assertNotNull(tryTitle);
        assertEquals(hex(pal.text), hex(tryTitle.getCurrentTextColor()));
        assertEquals("la tarjeta Pruebalo lleva el color de tarjeta de la paleta", hex(pal.card), hex(solidColor(cardOf(tryTitle).getBackground())));
    }

    // ------------------------------------------------------------------ (b) tarjetas: se distinguen del fondo y llevan el canto del tema

    @Test
    public void datapadYLosDemasTemasDistinguenLasTarjetasDelFondoYLlevanSuCanto() throws Exception {
        for (Themes.Theme t : Themes.ALL) {
            Prefs.get(app).edit().putString(Prefs.THEME, t.id).apply();
            MainActivity a = create();
            AppPalette pal = new AppPalette(t);
            TextView topTitle = textView(a, a.getString(R.string.top_lang_title));
            TextView tryTitle = textView(a, a.getString(R.string.try_title));
            assertNotNull(topTitle);
            assertNotNull(tryTitle);
            View[] cards = {cardOf(topTitle), cardOf(tryTitle)};
            for (View card : cards) {
                int color = solidColor(card.getBackground());
                assertEquals("[" + t.id + "] color de tarjeta de la paleta", hex(pal.card), hex(color));
                assertTrue("[" + t.id + "] la tarjeta no es el fondo", color != t.bg);
                assertTrue("[" + t.id + "] la tarjeta se separa del fondo (" + dist(color, t.bg) + " de diferencia RGB)", dist(color, t.bg) >= 16);
                assertTrue("[" + t.id + "] la tarjeta es un GradientDrawable (esquinas y canto)", card.getBackground() instanceof GradientDrawable);
            }
            if (t == Themes.DATAPAD) {
                // Datapad: teclas solo trazadas, casi del fondo; la tarjeta se tiñe (8 % del glifo) hasta distinguirse y lleva canto
                assertTrue(AppPalette.contrast(t.keyBg, t.bg) < 1.10);
                assertTrue(pal.card != t.keyBg);
                assertTrue("[datapad] la tarjeta tenida se distingue del fondo: " + AppPalette.contrast(pal.card, t.bg),
                        AppPalette.contrast(pal.card, t.bg) >= 1.10);
                assertEquals("[datapad] canto del tema", hex(t.keyStroke), hex(pal.cardStroke));
                assertTrue(pal.cardStrokeDp >= 1f);
            } else if (t == Themes.SITH) {
                // Sith: tambien tenida; su rojo apenas aclara el negro (1,07:1), la tarjeta se reconoce sobre todo por el canto rojo
                assertTrue(AppPalette.contrast(t.keyBg, t.bg) < 1.10);
                assertTrue(pal.card != t.keyBg);
                assertEquals(hex(t.keyStroke), hex(pal.cardStroke));
            } else {
                assertEquals("[" + t.id + "] la tarjeta es la tecla", hex(t.keyBg), hex(pal.card));
            }
            // El canto: en la captura, la fila de pixeles bajo el borde superior de la tarjeta es keyStroke (o la tarjeta si no hay canto)
            layoutActivity(a);
            Bitmap shot = snapshot(a);
            View decor = a.getWindow().getDecorView();
            int panelTop = positionInDecor(panelOf(a), decor)[1];
            int checked = 0;
            for (View card : cards) {
                int[] at = positionInDecor(card, decor);
                int x = at[0] + card.getWidth() / 2;
                int yEdge = at[1] + 1;
                if (yEdge + 12 >= panelTop) continue;   // tapada por el panel: no se puede mirar
                int edge = shot.getPixel(x, yEdge);
                int inside = shot.getPixel(x, at[1] + Math.max(6, Math.round(pal.cardStrokeDp * 3)) + 6);
                if (t.keyStroke != 0) {
                    assertTrue("[" + t.id + "] canto de " + pal.cardStrokeDp + " dp en el borde de la tarjeta", pal.cardStrokeDp >= 1f);
                    assertColorNear("[" + t.id + "] el borde de la tarjeta lleva el canto del tema", t.keyStroke, edge);
                } else {
                    assertEquals("[" + t.id + "] sin canto en el tema no hay borde", 0f, pal.cardStrokeDp, 0f);
                    assertColorNear("[" + t.id + "] sin canto el borde es la propia tarjeta", pal.card, edge);
                }
                assertColorNear("[" + t.id + "] dentro del borde esta la tarjeta", pal.card, inside);
                checked++;
            }
            assertTrue("[" + t.id + "] al menos una tarjeta a la vista", checked >= 1);
        }
    }

    // ------------------------------------------------------------------ (c) Contraste: todo texto legible

    @Test
    public void contrasteDaCuatroYMedioEnTodosLosTextosDeTodasLasPaginas() {
        Prefs.get(app).edit().putString(Prefs.THEME, Themes.CONTRASTE.id).apply();
        MainActivity a = create();
        AppPalette pal = assertDressed(a, Themes.CONTRASTE);
        // Glifo y fondo son negros: el titulo sobre el fondo pasa a blanco (no un gris justo)
        assertEquals(hex(0xFFFFFFFF), hex(pal.onBg));
        assertTrue(AppPalette.contrast(pal.onBg, Themes.CONTRASTE.bg) >= 4.5);
        assertTrue(AppPalette.contrast(pal.onBgSecondary, Themes.CONTRASTE.bg) >= 4.5);
        // Sobre la tarjeta blanca el texto sigue negro
        assertEquals(hex(Themes.CONTRASTE.glyph), hex(pal.text));
        assertTrue(AppPalette.contrast(pal.text, pal.card) >= 4.5);
        assertTrue(AppPalette.contrast(pal.secondary, pal.card) >= 4.5);
        assertTrue(AppPalette.contrast(pal.secondary, pal.surface2) >= 4.5);
        // Acento amarillo con texto negro, y como tinta sobre la tarjeta oscurecido hasta 3:1
        assertEquals(hex(Themes.CONTRASTE.onAccent), hex(pal.onAccent));
        assertTrue(AppPalette.contrast(pal.onAccent, pal.accent) >= 4.5);
        assertTrue(AppPalette.contrast(pal.accentInk, pal.card) >= 3.0);
        // Y en la pantalla: ningun texto visible, en la principal ni en las nueve paginas, baja de 4,5:1
        List<String> bad = poorContrastEverywhere(a, pal, 4.5, 4.5);
        assertTrue("textos con poco contraste en Contraste: " + bad, bad.isEmpty());
    }

    // ------------------------------------------------------------------ (d) automaticos de noche

    @Test
    @Config(qualifiers = "es-rES-w411dp-h914dp-night-xxhdpi")
    public void automaticoDeNocheVisteLaAppConElTemaNocturno() throws Exception {
        assertEquals("el calificador night pone el modo oscuro", Configuration.UI_MODE_NIGHT_YES,
                app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
        for (String pref : Themes.AUTOS) {
            Themes.Theme night = Themes.autoNight(pref);
            Prefs.get(app).edit().putString(Prefs.THEME, pref).apply();
            assertEquals(pref, Prefs.themePref(app));
            assertEquals("[" + pref + "] de noche el efectivo es el nocturno", night.id, Prefs.theme(app));
            MainActivity a = create();
            AppPalette pal = assertDressed(a, night);
            assertTrue("[" + pref + "] los nocturnos son oscuros", pal.dark);
            assertTrue("[" + pref + "] no es el tema de dia", windowColor(a) != Themes.autoDay(pref).bg);
            if (Prefs.THEME_AUTO_TEMPLOS.equals(pref)) {
                assertEquals(Themes.SITH.id, pal.themeId);
                layoutActivity(a);
                save(snapshot(a), "app_tema_auto_noche.png");
            }
        }
        // Un tema fijo no sigue el modo oscuro: la app ya no cambia con la noche por su cuenta
        Prefs.get(app).edit().putString(Prefs.THEME, Themes.ESTELAR.id).apply();
        MainActivity fixed = create();
        assertDressed(fixed, Themes.ESTELAR);
        assertFalse(new AppPalette(Themes.ESTELAR).dark);
    }

    // ------------------------------------------------------------------ (e) cambio de tema en vivo

    @Test
    public void cambiarElTemaConLaPantallaAbiertaLaRehaceUnaVezYConservaLaPagina() throws Exception {
        ActivityController<MainActivity> c = controller();
        MainActivity a = c.get();
        assertDressed(a, Themes.ESTELAR);
        SettingsTest.openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        settle();
        panelField(a).setText("hola");
        idle();

        // El mismo tema efectivo no rehace nada: Republica de dia es Nucleo, y Nucleo otra vez tampoco
        View republic = SettingsTest.clickableStartingWith(a, a.getString(R.string.theme_auto_republic) + ". ");
        assertNotNull(republic);
        republic.performClick();
        idle();
        assertEquals(Prefs.THEME_AUTO, Prefs.themePref(app));
        assertEquals(Themes.ESTELAR.id, Prefs.theme(app));
        assertSame("mismo tema efectivo: la actividad sigue", a, c.get());
        assertFalse(a.isDestroyed());
        byDescription(a, a.getString(R.string.theme_estelar)).performClick();
        idle();
        assertSame(a, c.get());
        // Otra preferencia cualquiera tampoco
        Prefs.get(app).edit().putString(Prefs.SABER, "red").apply();
        idle();
        assertSame(a, c.get());

        // Templo Sith desde la muestra: la actividad se rehace una vez, vestida de Sith, con Estilo abierta y el texto del panel
        View sith = byDescription(a, a.getString(R.string.theme_sith));
        assertNotNull(sith);
        sith.performClick();
        assertEquals(Themes.SITH.id, Prefs.get(app).getString(Prefs.THEME, null));
        assertSame("el recreate queda encolado hasta el siguiente ciclo del hilo principal", a, c.get());
        idle();
        MainActivity b = c.get();
        assertNotSame("la actividad se rehizo", a, b);
        assertTrue("la antigua queda destruida", a.isDestroyed());
        assertFalse(b.isDestroyed());
        assertEquals("la pagina abierta se conserva", MainActivity.PAGE_STYLE, b.openPageKey());
        assertEquals("lo escrito en el panel se conserva", "hola", panelField(b).getText().toString());
        assertDressed(b, Themes.SITH);
        View sithB = byDescription(b, b.getString(R.string.theme_sith));
        assertNotNull(sithB);
        assertTrue("la muestra de Sith aparece elegida en la pantalla nueva", sithB.isSelected());
        assertFalse(byDescription(b, b.getString(R.string.theme_estelar)).isSelected());
        TextView pageTitle = textView(b, b.getString(R.string.cat_style));
        assertNotNull("titulo de la pagina Estilo", pageTitle);
        assertEquals("el titulo de pagina va con la tinta sobre el fondo", hex(new AppPalette(Themes.SITH).onBg), hex(pageTitle.getCurrentTextColor()));
        layoutActivity(b);
        save(snapshot(b), "app_tema_vivo.png");

        // Elegir Sith otra vez no rehace; cambiar la preferencia desde fuera (el teclado) a Datapad si, conservando la pagina
        sithB.performClick();
        idle();
        assertSame(b, c.get());
        Prefs.get(app).edit().putString(Prefs.THEME, Themes.DATAPAD.id).apply();
        idle();
        MainActivity d = c.get();
        assertNotSame(b, d);
        assertTrue(b.isDestroyed());
        assertEquals(MainActivity.PAGE_STYLE, d.openPageKey());
        assertDressed(d, Themes.DATAPAD);
        // Atras cierra la pagina en la actividad viva y la principal sigue vestida de Datapad
        d.onBackPressed();
        assertEquals(null, d.openPageKey());
        assertEquals(hex(Themes.DATAPAD.bg), hex(solidColor(homeScroll(d).getBackground())));
    }

    // ------------------------------------------------------------------ (f) los ocho temas: barras del sistema, legibilidad y capturas

    @Test
    public void cadaTemaVisteLaAppEnteraConTextosLegiblesYSeCaptura() throws Exception {
        for (Themes.Theme t : Themes.ALL) {
            Prefs.get(app).edit().putString(Prefs.THEME, t.id).apply();
            MainActivity a = create();
            AppPalette pal = assertDressed(a, t);
            // Garantias de la paleta sobre la que se pinta todo
            assertTrue("[" + t.id + "] texto sobre tarjeta", AppPalette.contrast(pal.text, pal.card) >= 4.5);
            assertTrue("[" + t.id + "] secundario sobre tarjeta", AppPalette.contrast(pal.secondary, pal.card) >= 4.5);
            assertTrue("[" + t.id + "] secundario sobre superficie secundaria", AppPalette.contrast(pal.secondary, pal.surface2) >= 4.5);
            assertTrue("[" + t.id + "] texto sobre superficie secundaria", AppPalette.contrast(pal.text, pal.surface2) >= 4.5);
            assertTrue("[" + t.id + "] secundario sobre el fondo", AppPalette.contrast(pal.onBgSecondary, t.bg) >= 4.5);
            assertTrue("[" + t.id + "] sobre el acento", AppPalette.contrast(pal.onAccent, pal.accent) >= 3.0);
            assertTrue("[" + t.id + "] acento como tinta sobre tarjeta", AppPalette.contrast(pal.accentInk, pal.card) >= 3.0);
            assertTrue("[" + t.id + "] la superficie secundaria se distingue de la tarjeta", AppPalette.contrast(pal.surface2, pal.card) >= 1.10);
            assertEquals("[" + t.id + "] el acento es el del tema", hex(t.accent), hex(pal.accent));
            // En la pantalla: principal y nueve paginas, 4,5:1 en general y 3:1 sobre el acento
            List<String> bad = poorContrastEverywhere(a, pal, 4.5, 3.0);
            assertTrue("[" + t.id + "] textos con poco contraste: " + bad, bad.isEmpty());
            layoutActivity(a);
            Bitmap shot = snapshot(a);
            assertColorNear("[" + t.id + "] la esquina de la captura es el fondo del tema", t.bg, shot.getPixel(4, 4));
            save(shot, "app_tema_" + t.id + ".png");
        }
    }
}
