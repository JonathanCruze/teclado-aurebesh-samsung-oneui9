package com.aurebesh.teclado;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Lote 2.4: el panel de vista previa anclado abajo (presente en la principal y en las paginas, plegable, recuerda su
 * estado en Prefs.ui, una sola copia del teclado y relleno inferior para que nada quede tapado; la tarjeta "Pruebalo"
 * ya no lleva el teclado); el automatico Templos (Santuario Jedi de dia, Templo Sith de noche) y la pagina Estilo con
 * tres tarjetas automaticas y ocho fijas en filas de cuatro (sin Pazaak; Estelar y Noche se llaman Nucleo y Borde
 * Exterior); los signos de copyright y registrado con glifo propio y aire entre la letra interior y el marco; y el
 * selector de aprendizaje (2, 3 o 5 usos) que guarda Prefs.LEARN_USES como entero.
 * Con el calificador xxhdpi de Robolectric la densidad es 3: 1080 px son 360 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Lote24Test {

    private static final int W = 1080;
    private static final int PHONE_H = 2340;
    private static final String UI_PANEL_OPEN = "preview_open";
    /** Aire minimo (en unidades de glifo, alto de letra = 10) entre la letra interior de (c) y (R) y su marco. */
    private static final float BADGE_AIR = 0.8f;

    private Application app;
    private float dp;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        dp = app.getResources().getDisplayMetrics().density;
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
    }

    // ------------------------------------------------------------------ ayudas

    private static MainActivity create() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private static void idle() { ShadowLooper.shadowMainLooper().idle(); }

    private static List<View> all(MainActivity a) {
        List<View> out = new ArrayList<>();
        collect(a.getWindow().getDecorView(), out);
        return out;
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static TextView textView(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && s.equals(((TextView) v).getText().toString())) return (TextView) v;
        }
        return null;
    }

    private static boolean hasText(MainActivity a, String s) { return textView(a, s) != null; }

    private static boolean textContains(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && ((TextView) v).getText().toString().contains(s)) return true;
        }
        return false;
    }

    private static View byDescription(MainActivity a, String description) {
        for (View v : all(a)) {
            if (v.getContentDescription() != null && description.equals(v.getContentDescription().toString())) return v;
        }
        return null;
    }

    /** Visible en si misma y en todos sus ancestros. */
    private static boolean visibleUpToRoot(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    private static boolean isDescendant(View v, View ancestor) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == ancestor) return true;
        return false;
    }

    /** Mide y dispone la actividad entera a tamano de pantalla, dos veces: la segunda recoge el relleno que pide el panel. */
    private static void layoutActivity(MainActivity a) {
        View root = a.getWindow().getDecorView();
        for (int i = 0; i < 2; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(PHONE_H, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, W, PHONE_H);
            idle();
        }
    }

    private static Bitmap snapshot(MainActivity a) {
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

    private static FrameLayout content(MainActivity a) { return (FrameLayout) a.findViewById(android.R.id.content); }

    /** La unica copia del teclado de la actividad. */
    private static AurebeshKeyboardView previewOf(MainActivity a) {
        AurebeshKeyboardView found = null;
        int n = 0;
        for (View v : all(a)) if (v instanceof AurebeshKeyboardView) { found = (AurebeshKeyboardView) v; n++; }
        assertEquals("una sola copia del teclado en la app", 1, n);
        return found;
    }

    /** El panel: la columna que contiene la copia del teclado, colgada del contenedor de contenido. */
    private static ViewGroup panelOf(MainActivity a) {
        ViewGroup panel = (ViewGroup) previewOf(a).getParent();
        assertSame("el panel cuelga directamente del contenido de la ventana", content(a), panel.getParent());
        return panel;
    }

    /** Columna de la pantalla principal (dentro del primer ScrollView del contenido). */
    private static ViewGroup homeColumn(MainActivity a) {
        ScrollView scroll = (ScrollView) content(a).getChildAt(0);
        return (ViewGroup) scroll.getChildAt(0);
    }

    /** Columna de la pagina abierta (el ScrollView que no cuelga directamente del contenido). */
    private static ViewGroup pageContent(MainActivity a) {
        for (View v : all(a)) {
            if (v instanceof ScrollView && v.getParent() != content(a) && isDescendant(v, content(a))) return (ViewGroup) ((ScrollView) v).getChildAt(0);
        }
        throw new AssertionError("columna de la pagina");
    }

    private static void event(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void tap(View v, float x, float y) { event(v, MotionEvent.ACTION_DOWN, x, y); event(v, MotionEvent.ACTION_UP, x, y); }

    private static void layout(View v, int w) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
    }

    private static Bitmap render(View v, int w) {
        layout(v, w);
        Bitmap b = Bitmap.createBitmap(w, v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** Pixeles del rectangulo cercanos a {@code color}. */
    private static int colorCount(Bitmap b, RectF r, int color, int tol) {
        int n = 0;
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int px = b.getPixel(x, y);
            if (Color.alpha(px) >= 128 && dist(px, color) <= tol) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ (a) panel de vista previa

    @Test
    public void panelDeVistaPreviaFijoEnPrincipalYPaginas() throws Exception {
        MainActivity a = create();
        layoutActivity(a);
        FrameLayout content = content(a);
        AurebeshKeyboardView preview = previewOf(a);
        ViewGroup panel = panelOf(a);

        // Anclado abajo del contenido de la ventana, fuera de la columna desplazable
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        assertEquals("gravedad inferior", Gravity.BOTTOM, lp.gravity & Gravity.BOTTOM);
        assertEquals("el panel toca el borde inferior", content.getHeight(), panel.getBottom());
        assertTrue("el panel tiene alto", panel.getHeight() > 0);
        assertFalse("la copia del teclado no vive en la columna de la principal", isDescendant(preview, homeColumn(a)));
        // Desplegado de fabrica: la copia del teclado y el campo de una linea a la vista
        assertTrue(a.isPanelOpen());
        assertEquals(View.VISIBLE, preview.getVisibility());
        EditText field = null;
        for (View v : all(a)) if (v instanceof EditText && a.getString(R.string.try_hint).equals(String.valueOf(((EditText) v).getHint()))) field = (EditText) v;
        assertNotNull("campo del panel", field);
        assertTrue("el campo esta en el panel", isDescendant(field, panel));
        assertTrue(visibleUpToRoot(field));
        assertFalse("el campo del panel escribe con la copia, no con el teclado del sistema", field.getShowSoftInputOnFocus());
        assertNotNull("chevron para plegar", byDescription(a, a.getString(R.string.ux_preview_collapse)));
        assertFalse("plegado no hay rotulo a la vista", visibleUpToRoot(textView(a, a.getString(R.string.ux_preview_title))));
        // La tarjeta "Pruebalo" queda con su titulo, el subtitulo de los campos reales y sin copia del teclado
        assertTrue(hasText(a, a.getString(R.string.try_title)));
        assertTrue(hasText(a, a.getString(R.string.real_sub)));
        TextView tryTitle = textView(a, a.getString(R.string.try_title));
        assertTrue("Pruebalo esta en la columna de la principal", isDescendant(tryTitle, homeColumn(a)));
        // El contenido deja sitio al panel: relleno inferior >= alto del panel
        int openHeight = panel.getHeight();
        assertTrue("relleno inferior de la principal (" + homeColumn(a).getPaddingBottom() + ") >= panel (" + openHeight + ")",
                homeColumn(a).getPaddingBottom() >= openHeight);
        save(snapshot(a), "lote24_panel_principal.png");

        // Escribir con la copia llega al campo
        AurebeshKeyboardView.Key h = preview.keyFor("h");
        assertNotNull(h);
        tap(preview, h.rect.centerX(), h.rect.centerY());
        assertEquals("h", field.getText().toString().toLowerCase(java.util.Locale.ROOT));
        assertNotNull("con texto aparece el aspa", byDescription(a, a.getString(R.string.ux_preview_clear)));
        assertTrue(visibleUpToRoot(byDescription(a, a.getString(R.string.ux_preview_clear))));

        // En una pagina el panel sigue, es la misma instancia y la pagina tambien deja sitio
        SettingsTest.openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        layoutActivity(a);
        assertSame("la copia del teclado es la misma tras abrir una pagina", preview, previewOf(a));
        assertSame(panel, panelOf(a));
        assertTrue(visibleUpToRoot(preview));
        assertEquals("h", field.getText().toString().toLowerCase(java.util.Locale.ROOT));
        assertTrue("relleno inferior de la pagina >= panel", pageContent(a).getPaddingBottom() >= panel.getHeight());
        save(snapshot(a), "lote24_panel_estilo.png");
        a.onBackPressed();
        layoutActivity(a);
        assertSame("y la misma al volver", preview, previewOf(a));

        // Plegar: queda la barra fina con el rotulo, la copia se esconde, el estado se guarda y el relleno baja
        View chevron = byDescription(a, a.getString(R.string.ux_preview_collapse));
        assertNotNull(chevron);
        chevron.performClick();
        layoutActivity(a);
        assertFalse(a.isPanelOpen());
        assertEquals(View.GONE, preview.getVisibility());
        assertFalse("plegado, el campo se esconde", visibleUpToRoot(field));
        assertTrue("plegado, se ve el rotulo", visibleUpToRoot(textView(a, a.getString(R.string.ux_preview_title))));
        assertTrue(visibleUpToRoot(textView(a, a.getString(R.string.ux_preview_hint))));
        assertNull(byDescription(a, a.getString(R.string.ux_preview_collapse)));
        assertNotNull(byDescription(a, a.getString(R.string.ux_preview_expand)));
        assertFalse("plegado se guarda en Prefs.ui", Prefs.ui(app).getBoolean(UI_PANEL_OPEN, true));
        int closedHeight = panel.getHeight();
        assertTrue("la barra plegada es mas baja (" + closedHeight + " < " + openHeight + ")", closedHeight > 0 && closedHeight < openHeight);
        assertTrue("barra fina: " + closedHeight, closedHeight <= Math.round(96 * dp));
        assertTrue("el relleno sigue cubriendo el panel", homeColumn(a).getPaddingBottom() >= closedHeight);
        assertTrue("y ya no reserva el alto del teclado", homeColumn(a).getPaddingBottom() < openHeight);
        save(snapshot(a), "lote24_panel_plegado.png");
        // Desplegar desde el asa
        byDescription(a, a.getString(R.string.ux_preview_expand)).performClick();
        layoutActivity(a);
        assertTrue(a.isPanelOpen());
        assertEquals(View.VISIBLE, preview.getVisibility());
        assertTrue(Prefs.ui(app).getBoolean(UI_PANEL_OPEN, false));
        assertEquals("el texto sobrevive al plegado", "h", field.getText().toString().toLowerCase(java.util.Locale.ROOT));

        // El estado se recuerda en la siguiente apertura de la app
        a.setPanelOpen(false);
        assertFalse(Prefs.ui(app).getBoolean(UI_PANEL_OPEN, true));
        MainActivity b = create();
        layoutActivity(b);
        assertFalse("la nueva actividad arranca plegada", b.isPanelOpen());
        assertEquals(View.GONE, previewOf(b).getVisibility());
        assertFalse("no toca las preferencias del teclado", Prefs.get(app).contains(UI_PANEL_OPEN));
        b.togglePanel();
        assertTrue(b.isPanelOpen());
        assertTrue(Prefs.ui(app).getBoolean(UI_PANEL_OPEN, false));
        assertEquals(View.VISIBLE, previewOf(b).getVisibility());
        b.setPanelOpen(true);   // sin cambio: sigue abierto
        assertTrue(b.isPanelOpen());
    }

    @Test
    public void elCampoDelPanelPasaAAurebeshConTodoEnAurebesh() {
        MainActivity a = create();
        layoutActivity(a);
        ViewGroup panel = panelOf(a);
        EditText field = null;
        AurebeshTextView aurebesh = null;
        for (View v : all(a)) {
            if (!isDescendant(v, panel)) continue;
            if (v instanceof EditText) field = (EditText) v;
            if (v instanceof AurebeshTextView) aurebesh = (AurebeshTextView) v;
        }
        assertNotNull(field);
        assertNotNull("campo en Aurebesh dentro del panel", aurebesh);
        assertEquals("en latino el campo Aurebesh no se ve", View.GONE, aurebesh.getVisibility());
        assertEquals(View.VISIBLE, field.getVisibility());
        field.setText("hola mundo");
        Prefs.get(app).edit().putBoolean(Prefs.STRIP, true).apply();
        idle();
        layoutActivity(a);
        assertEquals("con Todo en Aurebesh se ve el campo Aurebesh", View.VISIBLE, aurebesh.getVisibility());
        assertTrue("y el latino se retira (sigue guardando el texto)", field.getVisibility() != View.VISIBLE);
        assertEquals("hola mundo", field.getText().toString());
        assertEquals("el campo Aurebesh se lee con el texto", "hola mundo", String.valueOf(aurebesh.getContentDescription()));
        assertEquals(1f, aurebesh.getAlpha(), 0.01f);
        field.setText("");
        assertEquals("sin texto, la pista atenuada", a.getString(R.string.try_hint), String.valueOf(aurebesh.getContentDescription()));
        assertTrue(aurebesh.getAlpha() < 0.5f);
        Prefs.get(app).edit().putBoolean(Prefs.STRIP, false).apply();
        idle();
        assertEquals(View.GONE, aurebesh.getVisibility());
        assertEquals(View.VISIBLE, field.getVisibility());
    }

    // ------------------------------------------------------------------ (b) automatico Templos y pagina Estilo

    @Test
    public void temaAutomaticoTemplosYPaginaEstiloConTresYOcho() throws Exception {
        // Contrato de Themes
        assertEquals(3, Themes.AUTOS.length);
        assertEquals(8, Themes.ALL.length);
        assertSame(Themes.CONTRASTE, Themes.ALL[Themes.ALL.length - 1]);
        assertTrue(Themes.isAuto(Prefs.THEME_AUTO_TEMPLOS));
        assertEquals("auto_templos", Prefs.THEME_AUTO_TEMPLOS);
        assertSame(Themes.SANTUARIO, Themes.autoDay(Prefs.THEME_AUTO_TEMPLOS));
        assertSame(Themes.SITH, Themes.autoNight(Prefs.THEME_AUTO_TEMPLOS));
        assertEquals(R.string.theme_auto_templos, Themes.autoName(Prefs.THEME_AUTO_TEMPLOS));
        assertEquals(R.string.theme_auto_templos_sub, Themes.autoSub(Prefs.THEME_AUTO_TEMPLOS));
        assertSame(Themes.SANTUARIO, Themes.byId("santuario"));
        assertSame(Themes.SITH, Themes.byId("sith"));
        assertSame("Pazaak ya no existe: cae al tema por defecto", Themes.DEFAULT, Themes.byId("pazaak"));
        assertEquals("Santuario Jedi", app.getString(R.string.theme_santuario));
        assertEquals("Templo Sith", app.getString(R.string.theme_sith));
        assertEquals("Núcleo", app.getString(R.string.theme_estelar));
        assertEquals("Borde Exterior", app.getString(R.string.theme_noche));
        assertEquals("Templos", app.getString(R.string.theme_auto_templos));
        // Las dos paletas se distinguen entre si y de sus vecinas
        assertTrue(Themes.SANTUARIO.bg != Themes.ARENA.bg && Themes.SANTUARIO.keyStroke != 0 && Themes.SANTUARIO.accent != Themes.ARENA.accent);
        assertTrue(Themes.SITH.bg != Themes.IMPERIAL.bg && Themes.SITH.keyStroke != 0 && Themes.SITH.bg != Themes.NOCHE.bg);

        // Prefs.theme resuelve Templos con el uiMode
        Prefs.get(app).edit().putString(Prefs.THEME, Prefs.THEME_AUTO_TEMPLOS).apply();
        assertEquals(Prefs.THEME_AUTO_TEMPLOS, Prefs.themePref(app));
        assertEquals("Templos de dia", Themes.SANTUARIO.id, Prefs.theme(app));
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context nightContext = app.createConfigurationContext(night);
        assertEquals("Templos de noche (contexto)", Themes.SITH.id, Prefs.theme(nightContext));
        RuntimeEnvironment.setQualifiers("+night");
        assertEquals("Templos de noche (calificador)", Themes.SITH.id, Prefs.theme(app));
        RuntimeEnvironment.setQualifiers("es-rES-w411dp-h914dp-xxhdpi");
        assertEquals(Themes.SANTUARIO.id, Prefs.theme(app));

        // Pagina Estilo: tres tarjetas automaticas en una fila y ocho fijas en dos filas de cuatro
        MainActivity a = create();
        SettingsTest.openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        View republic = SettingsTest.clickableStartingWith(a, a.getString(R.string.theme_auto_republic) + ". ");
        View imperio = SettingsTest.clickableStartingWith(a, a.getString(R.string.theme_auto_imperio) + ". ");
        View templos = SettingsTest.clickableStartingWith(a, a.getString(R.string.theme_auto_templos) + ". " + a.getString(R.string.theme_auto_templos_sub));
        assertNotNull(republic);
        assertNotNull(imperio);
        assertNotNull("tarjeta Templos", templos);
        assertTrue(templos.getContentDescription().toString().endsWith(a.getString(R.string.aj_theme_auto_badge)));
        ViewGroup autoRow = (ViewGroup) templos.getParent();
        assertSame("las tres tarjetas comparten fila", autoRow, republic.getParent());
        assertSame(autoRow, imperio.getParent());
        assertEquals("tres automaticos y nada mas en la fila", 3, autoRow.getChildCount());
        assertEquals("en un telefono van lado a lado", LinearLayout.HORIZONTAL, ((LinearLayout) autoRow).getOrientation());
        assertSame(republic, autoRow.getChildAt(0));
        assertSame(imperio, autoRow.getChildAt(1));
        assertSame(templos, autoRow.getChildAt(2));
        assertTrue("Templos (guardado) aparece seleccionado", templos.isSelected());
        assertFalse(republic.isSelected() || imperio.isSelected());
        assertFalse("sin Pazaak", textContains(a, "Pazaak"));
        assertNull(byDescription(a, "Pazaak"));

        List<ViewGroup> rows = new ArrayList<>();
        for (int i = 0; i < Themes.ALL.length; i++) {
            Themes.Theme t = Themes.ALL[i];
            View item = byDescription(a, a.getString(t.name));
            assertNotNull("muestra fija " + t.id, item);
            assertFalse("con un automatico ningun fijo esta seleccionado", item.isSelected());
            ViewGroup row = (ViewGroup) item.getParent();
            if (!rows.contains(row)) rows.add(row);
            assertEquals("fila " + (i / 4) + " para " + t.id, rows.get(i / 4), row);
            assertSame("posicion " + (i % 4) + " en su fila", item, row.getChildAt(i % 4));
        }
        assertEquals("ocho fijos en dos filas", 2, rows.size());
        assertEquals(4, rows.get(0).getChildCount());
        assertEquals(4, rows.get(1).getChildCount());
        assertSame("las dos filas van en la misma columna, una tras otra", rows.get(0).getParent(), rows.get(1).getParent());
        for (ViewGroup row : rows) {
            for (int i = 0; i < 4; i++) {
                View item = row.getChildAt(i);
                assertTrue("cada fijo lleva su muestra", item instanceof ViewGroup && ((ViewGroup) item).getChildAt(0) instanceof ThemeSwatchView);
            }
        }
        // Tocar un fijo: la tarjeta Templos se deselecciona; tocar Templos la recupera y cambia la copia del teclado
        View sith = byDescription(a, a.getString(R.string.theme_sith));
        sith.performClick();
        assertEquals(Themes.SITH.id, Prefs.get(app).getString(Prefs.THEME, null));
        assertTrue(sith.isSelected());
        assertFalse(templos.isSelected());
        assertEquals(Themes.SITH.id, Prefs.theme(app));
        templos.performClick();
        assertEquals(Prefs.THEME_AUTO_TEMPLOS, Prefs.get(app).getString(Prefs.THEME, null));
        assertTrue(templos.isSelected());
        assertFalse(sith.isSelected());
        idle();
        assertTrue("el resumen de Estilo nombra Templos", textContains(a, a.getString(R.string.theme_auto_templos)));
        layoutActivity(a);
        save(snapshot(a), "lote24_estilo_templos.png");
    }

    // ------------------------------------------------------------------ (c) copyright y registrado

    /** Poligonos de un glifo de GlyphData como cajas (minX, minY, maxX, maxY), en unidades. */
    private static List<RectF> polygonBoxes(String key) {
        int idx = -1;
        for (int i = 0; i < GlyphData.KEYS.length; i++) if (GlyphData.KEYS[i].equals(key)) idx = i;
        assertTrue("glifo " + key + " en GlyphData", idx >= 0);
        String[] f = GlyphData.DATA[idx].split(";");
        List<RectF> out = new ArrayList<>();
        for (int k = 3; k < f.length; k++) {
            RectF r = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            for (String pt : f[k].split(" ")) {
                int comma = pt.indexOf(',');
                float x = Float.parseFloat(pt.substring(0, comma)), y = Float.parseFloat(pt.substring(comma + 1));
                r.left = Math.min(r.left, x); r.right = Math.max(r.right, x);
                r.top = Math.min(r.top, y); r.bottom = Math.max(r.bottom, y);
            }
            out.add(r);
        }
        return out;
    }

    private static String frameOf(String key) {
        int idx = -1;
        for (int i = 0; i < GlyphData.KEYS.length; i++) if (GlyphData.KEYS[i].equals(key)) idx = i;
        String[] f = GlyphData.DATA[idx].split(";");
        return f[3] + ";" + f[4];
    }

    /** Caja de la tinta (en unidades) de un glifo dibujado con Glyphs a 100 px por unidad, dentro de {@code within}. */
    private static RectF inkBox(String key, RectF within) {
        float s = 100f;
        int w = Math.round(Glyphs.width(key) * s) + 2, h = Math.round(10 * s) + 2;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        Glyphs.draw(new Canvas(b), key, 0, 0, 10 * s, p);
        int x0 = Math.round(within.left * s), x1 = Math.round(within.right * s), y0 = Math.round(within.top * s), y1 = Math.round(within.bottom * s);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
            if (Color.alpha(b.getPixel(x, y)) < 128) continue;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        assertTrue("hay tinta dentro de " + within + " en " + key, maxX >= 0);
        return new RectF(minX / s, minY / s, (maxX + 1) / s, (maxY + 1) / s);
    }

    @Test
    public void copyrightYRegistradoConGlifoPropioYAire() throws Exception {
        String copy = "©", reg = "®";
        assertTrue(Glyphs.has(copy));
        assertTrue(Glyphs.has(reg));
        assertEquals("el signo se representa con su propio glifo", copy, Glyphs.glyphKey(copy));
        assertEquals(reg, Glyphs.glyphKey(reg));
        // Mismo marco para los dos (cuadrado redondeado con su anillo), distinto de la c y la r sueltas
        assertEquals("los dos signos comparten marco", frameOf(copy), frameOf(reg));
        assertTrue("el marco crecio para dejar aire (antes 6,16 unidades)", Glyphs.width(copy) > 7f && Glyphs.width(copy) < 9f);
        assertEquals("mismo ancho de caja", Glyphs.width(copy), Glyphs.width(reg), 0.001f);

        for (String key : new String[]{copy, reg}) {
            List<RectF> boxes = polygonBoxes(key);
            assertTrue(key + ": marco (dos contornos) y letra", boxes.size() >= 3);
            RectF outer = boxes.get(0), inner = boxes.get(1);
            assertTrue(key + ": el anillo exterior envuelve al interior", outer.contains(inner) && outer.width() > inner.width() + 1f);
            assertTrue(key + ": el marco es casi cuadrado", Math.abs(outer.width() - outer.height()) < 0.2f);
            float ring = inner.left - outer.left;
            assertTrue(key + ": anillo de un trazo (" + ring + ")", ring > 0.4f && ring < 1.2f);
            // La letra interior (resto de poligonos) deja aire >= 0,8 por los cuatro lados respecto al borde interior del marco
            RectF letter = new RectF(boxes.get(2));
            for (int i = 3; i < boxes.size(); i++) letter.union(boxes.get(i));
            assertTrue(key + ": la letra queda dentro del hueco", inner.contains(letter));
            float left = letter.left - inner.left, right = inner.right - letter.right, top = letter.top - inner.top, bottom = inner.bottom - letter.bottom;
            String air = String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f/%.2f", left, right, top, bottom);
            assertTrue(key + ": aire izq/der/arriba/abajo = " + air, left >= BADGE_AIR && right >= BADGE_AIR && top >= BADGE_AIR && bottom >= BADGE_AIR);
            assertTrue(key + ": la letra tiene cuerpo", letter.width() > 1.5f && letter.height() > 1.5f);
            // Y en el render (Glyphs.draw, relleno par-impar) la tinta dentro del hueco es solo la letra, con ese mismo aire
            // (se sondea por bandas centrales, porque las esquinas redondeadas del anillo entran en la caja del hueco)
            RectF hBand = new RectF(inner.left + 0.2f, inner.centerY() - 0.3f * inner.height(), inner.right - 0.2f, inner.centerY() + 0.3f * inner.height());
            RectF vBand = new RectF(inner.centerX() - 0.3f * inner.width(), inner.top + 0.2f, inner.centerX() + 0.3f * inner.width(), inner.bottom - 0.2f);
            RectF inkH = inkBox(key, hBand), inkV = inkBox(key, vBand);
            assertTrue(key + ": tinta de la letra en el render " + inkH + " / " + inkV + " vs " + letter, Math.abs(inkH.left - letter.left) < 0.1f
                    && Math.abs(inkH.right - letter.right) < 0.1f && Math.abs(inkV.top - letter.top) < 0.1f && Math.abs(inkV.bottom - letter.bottom) < 0.1f);
            RectF frameInk = inkBox(key, new RectF(outer.left, outer.top, outer.right, outer.bottom));
            assertTrue(key + ": el marco se pinta entero", frameInk.width() > outer.width() - 0.1f && frameInk.height() > outer.height() - 0.1f);
            // El anillo del marco es tinta y el hueco entre marco y letra no
            float s = 100f;
            Bitmap b = Bitmap.createBitmap(Math.round(Glyphs.width(key) * s) + 2, Math.round(10 * s) + 2, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            Glyphs.draw(new Canvas(b), key, 0, 0, 10 * s, p);
            int ringX = Math.round((outer.left + inner.left) / 2f * s), midY = Math.round(outer.centerY() * s);
            assertTrue(key + ": el anillo es tinta", Color.alpha(b.getPixel(ringX, midY)) >= 128);
            int gapX = Math.round((inner.left + letter.left) / 2f * s);
            assertTrue(key + ": entre el marco y la letra hay aire", Color.alpha(b.getPixel(gapX, midY)) < 128);
        }

        // En la tecla real (segunda pagina de signos) los dos signos existen y pintan tinta del glifo en la cara
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        Themes.Theme t = Themes.ESTELAR;
        kb.applyPrefs(false, false, false, true, t.id, true, false, 1f);
        kb.setEnterAction(4);
        kb.setListener(new SizeTest.Recorder());
        layout(kb, W);
        kb.showSymbols();
        layout(kb, W);
        AurebeshKeyboardView.Key page = kb.keyFor(AurebeshKeyboardView.CODE_PAGE);
        assertNotNull(page);
        tap(kb, page.rect.centerX(), page.rect.centerY());
        Bitmap shot = render(kb, W);
        AurebeshKeyboardView.Key kc = kb.keyFor(copy), kr = kb.keyFor(reg);
        assertNotNull("tecla (c) en la pagina 2", kc);
        assertNotNull("tecla (R) en la pagina 2", kr);
        assertEquals(copy, kc.glyph);
        assertEquals(reg, kr.glyph);
        for (AurebeshKeyboardView.Key k : new AurebeshKeyboardView.Key[]{kc, kr}) {
            RectF face = new RectF(k.rect.left + 3 * dp, k.rect.top + 7.25f * dp, k.rect.right - 3 * dp, k.rect.bottom - 7.25f * dp);
            RectF middle = new RectF(face.centerX() - 0.3f * face.width(), face.centerY() - 0.3f * face.height(),
                    face.centerX() + 0.3f * face.width(), face.centerY() + 0.3f * face.height());
            int ink = colorCount(shot, middle, t.glyph, 90);
            assertTrue("tinta del glifo en la tecla " + k.output + ": " + ink, ink > 40);
            // El centro de la cara (dentro del marco, entre letra y anillo hay aire) no es tinta maciza
            int keyBg = colorCount(shot, middle, t.keyBg, 24);
            assertTrue("la tecla " + k.output + " no es una mancha: fondo visible dentro del marco", keyBg > ink / 4);
        }
        save(Bitmap.createBitmap(shot, Math.round(Math.min(kc.rect.left, kr.rect.left)), Math.round(kc.rect.top),
                Math.round(Math.max(kc.rect.right, kr.rect.right) - Math.min(kc.rect.left, kr.rect.left)), Math.round(kc.rect.height())), "lote24_copyright_teclas.png");
    }

    // ------------------------------------------------------------------ (d) selector de aprendizaje

    @Test
    public void selectorDeAprendizajeGuardaDosTresOCincoUsos() throws Exception {
        // Prefs.learnUses: 3 por defecto y solo acepta 2, 3 o 5
        assertEquals(3, Prefs.learnUses(app));
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 2).apply();
        assertEquals(2, Prefs.learnUses(app));
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 5).apply();
        assertEquals(5, Prefs.learnUses(app));
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 4).apply();
        assertEquals("un valor fuera de 2/3/5 vuelve a 3", 3, Prefs.learnUses(app));
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 0).apply();
        assertEquals(3, Prefs.learnUses(app));
        Prefs.get(app).edit().remove(Prefs.LEARN_USES).apply();
        assertTrue("cambia el teclado (el IME recarga)", Prefs.affectsKeyboard(Prefs.LEARN_USES));
        assertEquals("entero al importar", MainActivity.TYPE_INT, MainActivity.keyType(Prefs.LEARN_USES));
        boolean inReset = false;
        for (String k : MainActivity.RESET_KEYS) if (Prefs.LEARN_USES.equals(k)) inReset = true;
        assertTrue("Restablecer ajustes lo devuelve a 3", inReset);

        // Pagina Escritura inteligente: la fila con sus tres chips, 3 usos seleccionado de fabrica
        MainActivity a = create();
        SettingsTest.openCategory(a, R.string.aj_sec_smart, MainActivity.PAGE_SMART);
        assertTrue(hasText(a, a.getString(R.string.learn_uses_title)));
        assertTrue(hasText(a, a.getString(R.string.learn_uses_sub)));
        TextView two = textView(a, a.getString(R.string.learn_uses_2));
        TextView three = textView(a, a.getString(R.string.learn_uses_3));
        TextView five = textView(a, a.getString(R.string.learn_uses_5));
        assertNotNull(two);
        assertNotNull(three);
        assertNotNull(five);
        assertSame("los tres chips van en la misma fila", two.getParent(), three.getParent());
        assertSame(two.getParent(), five.getParent());
        assertEquals(3, ((ViewGroup) two.getParent()).getChildCount());
        assertTrue("3 usos de fabrica", three.isSelected());
        assertFalse(two.isSelected() || five.isSelected());
        View learnRow = null;
        for (View p = two; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p instanceof ViewGroup && hasTextWithin((ViewGroup) p, a.getString(R.string.learn_uses_title))) { learnRow = p; break; }
        }
        assertNotNull("los chips cuelgan de la fila Aprender palabras nuevas", learnRow);
        assertTrue("la fila vive en la pagina abierta", isDescendant(learnRow, pageContent(a)));

        two.performClick();
        assertEquals("se guarda como entero", 2, Prefs.get(app).getInt(Prefs.LEARN_USES, -1));
        assertEquals(2, Prefs.learnUses(app));
        assertTrue(two.isSelected());
        assertFalse(three.isSelected());
        five.performClick();
        assertEquals(5, Prefs.get(app).getInt(Prefs.LEARN_USES, -1));
        assertEquals(5, Prefs.learnUses(app));
        assertTrue(five.isSelected());
        assertFalse(two.isSelected());
        // Cambiado desde fuera, la pantalla sigue
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 3).apply();
        idle();
        assertTrue(three.isSelected());
        assertFalse(five.isSelected());

        // Exportar / importar: viaja como entero y al importar se acota a 2/3/5
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 5).apply();
        String json = a.exportJson(false);
        assertEquals(5, new org.json.JSONObject(json).getJSONObject("settings").getInt(Prefs.LEARN_USES));
        Prefs.get(app).edit().clear().apply();
        assertTrue(a.importJson(json) >= 1);
        assertEquals(5, Prefs.learnUses(app));
        assertEquals(1, a.importJson("{\"app\":\"aurebesh\",\"settings\":{\"" + Prefs.LEARN_USES + "\":4}}"));
        assertEquals("4 se acota a 3", 3, Prefs.get(app).getInt(Prefs.LEARN_USES, -1));
        assertEquals(1, a.importJson("{\"app\":\"aurebesh\",\"settings\":{\"" + Prefs.LEARN_USES + "\":2}}"));
        assertEquals(2, Prefs.learnUses(app));
        assertEquals("una cadena no es un entero: no entra", 0, a.importJson("{\"app\":\"aurebesh\",\"settings\":{\"" + Prefs.LEARN_USES + "\":\"5\"}}"));
        assertEquals(2, Prefs.learnUses(app));
    }

    private static boolean hasTextWithin(ViewGroup g, String s) {
        List<View> out = new ArrayList<>();
        collect(g, out);
        for (View v : out) if (v instanceof TextView && s.equals(((TextView) v).getText().toString())) return true;
        return false;
    }
}
