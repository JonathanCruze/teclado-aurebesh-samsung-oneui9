package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * La app para quien llega (2.8): la guia de tres pasos y su estado, el orden de la principal antes y despues de elegir
 * el teclado, "¿Sabes leer Aurebesh?" como el mismo ajuste que la letra normal en las teclas (encendida de fabrica en
 * instalaciones nuevas, intacta en las que ya existian), la pagina Aprender Aurebesh con la tabla del alfabeto, la
 * ayuda con preguntas rapidas y "Acerca de", "¿Es seguro?" a la vista, los resumenes cortos y sin jerga en los tres
 * idiomas y el margen inferior con nombres en vez de dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AmigableTest {

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, null);
    }

    private static MainActivity create() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private void selectKeyboard() {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, app.getPackageName() + "/.AurebeshIME");
    }

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

    private static boolean shown(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /** Primer TextView visible (el y sus ancestros) con exactamente ese texto, o null. */
    private static TextView visibleText(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && s.equals(((TextView) v).getText().toString()) && shown(v)) return (TextView) v;
        }
        return null;
    }

    private static TextView sibling(TextView chip, String label) {
        ViewGroup row = (ViewGroup) chip.getParent();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v instanceof TextView && label.equals(((TextView) v).getText().toString())) return (TextView) v;
        }
        return null;
    }

    private static int indexOf(MainActivity a, String text) {
        List<View> order = all(a);
        TextView t = visibleText(a, text);
        return t == null ? -1 : order.indexOf(t);
    }

    private static void layout(MainActivity a, int h) {
        View root = a.getWindow().getDecorView();
        for (int i = 0; i < 2; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, h);
            ShadowLooper.shadowMainLooper().idle();
        }
    }

    private static void save(MainActivity a, int h, String name) throws Exception {
        layout(a, h);
        Bitmap b = Bitmap.createBitmap(1080, h, Bitmap.Config.ARGB_8888);
        a.getWindow().getDecorView().draw(new Canvas(b));
        File out = new File(System.getProperty("shots.dir", "shots"), name);
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    // ------------------------------------------------------------------ guia de tres pasos y orden de la principal

    @Test
    public void sinActivarLaPrincipalEmpiezaPorLosTresPasos() throws Exception {
        Prefs.migrate(app);
        Prefs.ui(app).edit().putBoolean("preview_open", false).apply();   // el panel plegado deja ver la pantalla en la captura
        MainActivity a = create();
        assertNotNull(visibleText(a, a.getString(R.string.setup_title)));
        // El paso en curso es el primero: su explicacion, su boton y el aviso de Android explicado antes de que salga
        assertNotNull(visibleText(a, a.getString(R.string.setup_1_body)));
        assertNotNull("boton del paso 1", visibleText(a, a.getString(R.string.setup_1_button)));
        assertNotNull("el aviso de Android, explicado", visibleText(a, a.getString(R.string.setup_1_note)));
        assertNotNull(visibleText(a, a.getString(R.string.setup_2_title)));
        assertNull("el boton del paso 2 espera a su turno", visibleText(a, a.getString(R.string.setup_2_button)));
        assertNotNull("como volver al teclado de antes", visibleText(a, a.getString(R.string.setup_back_hint)));
        assertNull(visibleText(a, a.getString(R.string.top_status_done)));
        // Orden: pasos, Pruebalo, nivel de lectura, seguridad y, despues, la lista de ajustes
        int steps = indexOf(a, a.getString(R.string.setup_title)), tryIt = indexOf(a, a.getString(R.string.try_title));
        int level = indexOf(a, a.getString(R.string.level_title)), safe = indexOf(a, a.getString(R.string.safe_title));
        int list = indexOf(a, a.getString(R.string.cat_list_title)), lang = indexOf(a, a.getString(R.string.top_lang_title));
        assertTrue(steps >= 0 && steps < tryIt && tryIt < level && level < safe && safe < list && list < lang);
        save(a, 2340, "amigable_1_tres_pasos.png");
    }

    @Test
    public void conElTecladoElegidoLaListaSubeYLaTarjetaSeQuedaEnActivado() throws Exception {
        Prefs.migrate(app);
        Prefs.ui(app).edit().putBoolean("preview_open", false).apply();
        selectKeyboard();
        MainActivity a = create();
        assertNotNull(visibleText(a, a.getString(R.string.top_status_done)));
        assertNotNull(visibleText(a, a.getString(R.string.setup_done_body)));
        assertNotNull("boton para cambiar de teclado", visibleText(a, a.getString(R.string.setup_done_change)));
        assertNull("los pasos ya no se ven", visibleText(a, a.getString(R.string.setup_title)));
        assertNull(visibleText(a, a.getString(R.string.setup_1_button)));
        int done = indexOf(a, a.getString(R.string.top_status_done)), list = indexOf(a, a.getString(R.string.cat_list_title));
        int level = indexOf(a, a.getString(R.string.level_title)), tryIt = indexOf(a, a.getString(R.string.try_title));
        int safe = indexOf(a, a.getString(R.string.safe_title));
        assertTrue("la lista de ajustes va justo despues del estado", done >= 0 && done < list && list < level && level < tryIt && tryIt < safe);
        save(a, 2340, "amigable_2_activado.png");
        // Al dejar de ser el teclado elegido (se vuelve de los ajustes del telefono), los pasos reaparecen arriba
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, "com.otro/.Teclado");
        a.onWindowFocusChanged(true);
        assertNotNull(visibleText(a, a.getString(R.string.setup_title)));
        assertTrue(indexOf(a, a.getString(R.string.try_title)) < indexOf(a, a.getString(R.string.cat_list_title)));
    }

    // ------------------------------------------------------------------ ¿Sabes leer Aurebesh?

    @Test
    public void instalacionNuevaMuestraLaLetraNormalYLasExistentesConservanLoSuyo() {
        // Nueva: nada guardado -> la letra normal viene encendida
        Prefs.migrate(app);
        assertEquals("on", Prefs.hintMode(app));
        assertTrue(Prefs.hints(app));
        assertEquals(1, Prefs.hintModeInt(app));
        // Ya en uso (habia pasado la migracion de la 2.6) sin haber tocado el ajuste: se queda sin letra
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().putInt("migrated", 1).apply();
        Prefs.migrate(app);
        assertEquals("off", Prefs.hintMode(app));
        assertFalse(Prefs.hints(app));
        // En uso desde antes de la 2.6 (otros ajustes guardados, sin marca de migracion): igual
        Prefs.get(app).edit().clear().putBoolean(Prefs.VIBRATE, false).apply();
        Prefs.ui(app).edit().clear().apply();
        Prefs.migrate(app);
        assertEquals("off", Prefs.hintMode(app));
        // Quien ya habia elegido conserva su eleccion
        Prefs.get(app).edit().clear().putString(Prefs.HINT_MODE, "learning").apply();
        Prefs.ui(app).edit().clear().apply();
        Prefs.migrate(app);
        assertEquals("learning", Prefs.hintMode(app));
        Prefs.get(app).edit().clear().putBoolean(Prefs.HINTS, true).apply();
        Prefs.ui(app).edit().clear().apply();
        Prefs.migrate(app);
        assertEquals("on", Prefs.hintMode(app));
    }

    @Test
    public void elNivelDeLecturaEsElAjusteDeLaLetraNormal() {
        Prefs.migrate(app);
        MainActivity a = create();
        TextView no = visibleText(a, a.getString(R.string.level_no));
        assertNotNull("chip No", no);
        assertTrue("de fabrica, No: cada tecla lleva su letra", no.isSelected());
        assertNotNull(visibleText(a, a.getString(R.string.level_no_sub)));
        TextView yes = sibling(no, a.getString(R.string.level_yes)), learning = sibling(no, a.getString(R.string.level_learning));
        yes.performClick();
        assertEquals("off", Prefs.hintMode(app));
        assertFalse(Prefs.hints(app));
        assertNotNull("la frase cambia con la opcion", visibleText(a, a.getString(R.string.level_yes_sub)));
        learning.performClick();
        assertEquals("learning", Prefs.hintMode(app));
        assertNotNull(visibleText(a, a.getString(R.string.level_learning_sub)));
        // El enlace abre Aprender Aurebesh, donde el mismo ajuste sale como Siempre / Aprendiendo / Nunca
        ((View) visibleText(a, a.getString(R.string.level_link)).getParent()).performClick();
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(MainActivity.PAGE_LEARN, a.openPageKey());
        TextView never = visibleText(a, a.getString(R.string.aj_hint_off));
        TextView pageLearning = sibling(never, a.getString(R.string.aj_hint_learning));
        assertTrue("la pagina marca lo elegido en la principal", pageLearning.isSelected());
        sibling(never, a.getString(R.string.aj_hint_on)).performClick();
        assertEquals("on", Prefs.hintMode(app));
        a.closePage(false);
        assertTrue("y la principal, lo elegido en la pagina", no.isSelected());
    }

    // ------------------------------------------------------------------ Aprender Aurebesh

    @Test
    public void aprenderAurebeshTraeLaTablaDelAlfabetoConLasLetrasAprendidas() throws Exception {
        Prefs.migrate(app);
        Prefs.ui(app).edit().putBoolean("preview_open", false).apply();
        Prefs.get(app).edit().putString(Prefs.HINT_MODE, "learning").putString(Prefs.HINT_COUNTS, "a 40\ne 41\nq 3").apply();
        MainActivity a = create();
        // El resumen de la fila dice cuantas letras se leen ya sin ayuda
        assertNotNull(visibleText(a, a.getString(R.string.hint_learned_count, 2)));
        SettingsTest.openCategory(a, R.string.cat_learn, MainActivity.PAGE_LEARN);
        assertNotNull(visibleText(a, a.getString(R.string.opt_hints)));
        assertNotNull(visibleText(a, a.getString(R.string.immersive_title)));
        assertNotNull(visibleText(a, a.getString(R.string.aj_hint_reset)));
        List<AlphabetView> tables = new ArrayList<>();
        for (View v : all(a)) if (v instanceof AlphabetView && shown(v)) tables.add((AlphabetView) v);
        assertEquals("letras, letras dobles, las creadas para el teclado y numeros", 4, tables.size());
        String letters = tables.get(0).getContentDescription().toString();
        assertTrue(letters, letters.startsWith("a aurek, b besh, c cresh"));
        assertTrue(letters.endsWith("z zerek"));
        assertEquals("ch cherek, ae enth, eo onith, kh krenth, ng nen, oo orenth, sh shen, th thesh", tables.get(1).getContentDescription().toString());
        assertEquals("ñ, ll, rr, qu, gu", tables.get(2).getContentDescription().toString());
        assertEquals("aurek", AlphabetView.nameOf("a"));
        assertNull(AlphabetView.nameOf("ñ"));
        save(a, 4700, "amigable_3_aprender.png");
        // Las letras aprendidas llevan la celda del color de acento: la de la a difiere de la de la b
        Bitmap b = Bitmap.createBitmap(1080, 4700, Bitmap.Config.ARGB_8888);
        a.getWindow().getDecorView().draw(new Canvas(b));
        AlphabetView t = tables.get(0);
        int[] at = new int[2];
        t.getLocationInWindow(at);
        float cell = t.getWidth() / (float) t.columns();
        int y = at[1] + Math.round(8 * a.getResources().getDisplayMetrics().density);
        assertTrue("la celda de la a (aprendida) no es como la de la b",
                b.getPixel(at[0] + Math.round(cell * 0.5f), y) != b.getPixel(at[0] + Math.round(cell * 1.5f), y));
        // "Volver a mostrar todas las letras": la tabla deja de marcarlas en el acto, sin rehacer la pantalla
        assertEquals(2, t.known().size());
        ((View) visibleText(a, a.getString(R.string.aj_hint_reset))).performClick();
        assertTrue("tras el reinicio no queda ninguna marcada", t.known() == null || t.known().isEmpty());
        // En otro idioma no hay enye ni letras dobles del espanol
        Prefs.setLanguage(app, "en");
        MainActivity en = create();
        SettingsTest.openCategory(en, R.string.cat_learn, MainActivity.PAGE_LEARN);
        int n = 0;
        for (View v : all(en)) if (v instanceof AlphabetView && shown(v)) n++;
        assertEquals(3, n);
    }

    // ------------------------------------------------------------------ ayuda, seguridad y acerca de

    @Test
    public void laAyudaRespondeLoQueSePreguntaQuienLlega() throws Exception {
        Prefs.migrate(app);
        Prefs.ui(app).edit().putBoolean("preview_open", false).apply();
        MainActivity a = create();
        // "¿Es seguro?" esta en la principal, con su enlace a Privacidad y datos; el pie ya no lleva las licencias
        assertNotNull(visibleText(a, a.getString(R.string.safe_body)));
        assertNull("las licencias salen del pie", visibleText(a, a.getString(R.string.credits)));
        assertNotNull(visibleText(a, a.getString(R.string.footer, a.getString(R.string.version))));
        ((View) visibleText(a, a.getString(R.string.safe_link)).getParent()).performClick();
        ShadowLooper.shadowMainLooper().idle();
        assertEquals(MainActivity.PAGE_PRIVACY, a.openPageKey());
        assertNotNull(visibleText(a, a.getString(R.string.privacy_safe_title)));
        assertTrue("lo que tranquiliza va antes que restablecer",
                indexOf(a, a.getString(R.string.privacy_safe_title)) < indexOf(a, a.getString(R.string.aj_reset)));
        assertTrue("borrar los datos, antes que restablecer", indexOf(a, a.getString(R.string.aj_wipe)) < indexOf(a, a.getString(R.string.aj_reset)));
        a.closePage(false);
        SettingsTest.openCategory(a, R.string.aj_sec_help, MainActivity.PAGE_HELP);
        for (int q : new int[]{R.string.faq_what_q, R.string.faq_back_q, R.string.faq_remove_q, R.string.safe_title, R.string.faq_warn_q, R.string.faq_flip_q}) {
            assertNotNull(a.getString(q), visibleText(a, a.getString(q)));
        }
        assertNotNull("boton para cambiar de teclado desde la ayuda", visibleText(a, a.getString(R.string.faq_back_button)));
        assertTrue("las preguntas van antes que los trucos",
                indexOf(a, a.getString(R.string.faq_what_q)) < indexOf(a, a.getString(R.string.help_tricks_group)));
        assertNotNull(visibleText(a, a.getString(R.string.gesture_accent)));
        assertNotNull("Acerca de lleva las licencias", visibleText(a, a.getString(R.string.credits)));
        assertNotNull(visibleText(a, a.getString(R.string.about_version, a.getString(R.string.version))));
        save(a, 2340, "amigable_4_ayuda.png");
        // El buscador encuentra la pregunta del aviso de aplicacion danina y lleva a Ayuda
        a.closePage(false);
        a.search("dañina");
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(50));
        assertNotNull(visibleText(a, a.getString(R.string.faq_warn_q)));
        // Y las demas por sus palabras naturales, aunque no esten tal cual en la pregunta
        a.search("quitar");
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(50));
        assertNotNull(visibleText(a, a.getString(R.string.faq_remove_q)));
        a.search("volver");
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(50));
        assertNotNull(visibleText(a, a.getString(R.string.faq_back_q)));
        a.search("pasos");
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(50));
        assertNotNull(visibleText(a, a.getString(R.string.faq_what_q)));
    }

    // ------------------------------------------------------------------ textos: resumenes cortos y sin jerga

    @Test
    public void resumenesCortosYSinJergaEnLosTresIdiomas() {
        String[] jerga = {"glifo", "dígrafo", "digraph", "glyph", "letra latina", "latin letter", "lateinisch", " dp"};
        int[] visibles = {R.string.sum_learn_page, R.string.sum_smart_desc, R.string.sum_touch_desc, R.string.sum_clipboard_desc,
                R.string.sum_privacy_desc, R.string.sum_help_desc, R.string.sum_ai_none, R.string.setup_1_body, R.string.setup_1_note,
                R.string.setup_2_body, R.string.level_no_sub, R.string.level_learning_sub, R.string.level_yes_sub, R.string.safe_body,
                R.string.opt_hints, R.string.aj_hint_mode_sub, R.string.top_row_sub, R.string.opt_digraphs, R.string.opt_digraphs_sub,
                R.string.es_digraphs_title, R.string.es_digraphs_sub, R.string.alt_chars_sub, R.string.alt_chars_hint, R.string.aj_preview_sub,
                R.string.aj_bottom_sub, R.string.aj_gesture_hide_result, R.string.fold_detected_phone, R.string.gesture_symbols_result,
                R.string.immersive_sub, R.string.faq_what_a};
        for (String lang : new String[]{"es", "en", "de"}) {
            Prefs.migrate(app);
            Prefs.setLanguage(app, lang);
            MainActivity a = create();
            // Cada resumen de la lista cabe sin cortarse: como mucho 34 caracteres
            for (View v : all(a)) {
                if (!(v instanceof TextView) || v.getContentDescription() != null) continue;
                View row = v.getParent() instanceof View && ((View) v.getParent()).getParent() instanceof View ? (View) ((View) v.getParent()).getParent() : null;
                if (row == null || row.getContentDescription() == null || !row.hasOnClickListeners()) continue;
                String title = ((TextView) ((ViewGroup) v.getParent()).getChildAt(0)).getText().toString();
                if (v == ((ViewGroup) v.getParent()).getChildAt(0) || !row.getContentDescription().toString().startsWith(title + ". ")) continue;
                String sum = ((TextView) v).getText().toString();
                assertTrue("[" + lang + "] resumen largo: " + sum, sum.length() <= 34);
                assertEquals("[" + lang + "] el resumen empieza con mayuscula: " + sum, sum.substring(0, 1).toUpperCase(), sum.substring(0, 1));
            }
            for (int id : visibles) {
                String s = a.getString(id).toLowerCase();
                for (String j : jerga) assertFalse("[" + lang + "] jerga \"" + j + "\" en: " + s, s.contains(j));
            }
            // El margen inferior se elige con palabras
            SettingsTest.openCategory(a, R.string.cat_size, MainActivity.PAGE_SIZE);
            for (int id : new int[]{R.string.margin_0, R.string.margin_12, R.string.margin_24, R.string.margin_36}) {
                assertNotNull("[" + lang + "] " + a.getString(id), visibleText(a, a.getString(id)));
            }
            assertNull(visibleText(a, "24 dp"));
            Prefs.get(app).edit().clear().apply();
            Prefs.ui(app).edit().clear().apply();
        }
    }

    @Test
    public void elMargenConNombreSeGuardaYSaleEnElResumen() {
        Prefs.migrate(app);
        MainActivity a = create();
        SettingsTest.openCategory(a, R.string.cat_size, MainActivity.PAGE_SIZE);
        visibleText(a, a.getString(R.string.margin_24)).performClick();
        assertEquals(24, Prefs.bottomDp(app));
        a.closePage(false);
        String sum = a.getString(R.string.sum_size_height, 100) + " · " + a.getString(R.string.sum_margin_24);
        assertNotNull(sum, visibleText(a, sum));
        Prefs.setBottomDp(app, 17);   // un valor de los tiradores, fuera de los cuatro botones
        ShadowLooper.shadowMainLooper().idle();
        assertNotNull(visibleText(a, a.getString(R.string.sum_size_height, 100) + " · " + a.getString(R.string.sum_margin_custom)));
    }
}
