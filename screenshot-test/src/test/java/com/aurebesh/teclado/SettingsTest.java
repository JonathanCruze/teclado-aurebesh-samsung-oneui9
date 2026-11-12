package com.aurebesh.teclado;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pantalla de ajustes (2.2, estilo Ajustes de One UI): la pantalla principal lleva la tarjeta "Pruebalo" y una lista
 * con una fila por categoria (titulo, resumen de una linea y ›) que abre su pagina dentro de la misma actividad; cada
 * pagina se construye al abrirla. Los temas automaticos (Republica, Imperio y Templos) se guardan como "auto", "auto_imperio" y "auto_templos"
 * y se resuelven con el modo oscuro; los atajos de texto van y vienen desde Prefs; el deslizador de alto (pagina
 * Tamano y distribucion) guarda el porcentaje al soltar; en un telefono no hay Plegables (el dividido va en Tamano);
 * la pagina abierta sobrevive al giro y el boton atras la cierra; y los campos de prueba son uno de texto normal y uno
 * de PIN numerico. "Caracteres alternativos" (Prefs.ALT_CHARS, activo de fabrica) vive en Idiomas y teclas.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsTest {

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
    }

    private static MainActivity create() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    /** Todas las vistas del arbol de la actividad, en orden de profundidad. */
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

    /** Primer TextView cuyo texto es exactamente {@code s}, o null. */
    private static TextView textView(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && s.equals(((TextView) v).getText().toString())) return (TextView) v;
        }
        return null;
    }

    private static boolean hasText(MainActivity a, String s) { return textView(a, s) != null; }

    /** Algun TextView cuyo texto contiene {@code s} (subtitulos compuestos de varias lineas). */
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

    /** Primera vista pulsable cuya descripcion accesible empieza por {@code prefix} (las filas de categoria y las tarjetas de tema). */
    static View clickableStartingWith(MainActivity a, String prefix) {
        for (View v : all(a)) {
            if (v.getContentDescription() == null || !v.hasOnClickListeners()) continue;
            if (v.getContentDescription().toString().startsWith(prefix)) return v;
        }
        return null;
    }

    /** Fila de la lista principal de la categoria con ese titulo (descripcion "Titulo. resumen"). */
    static View categoryRow(MainActivity a, int titleRes) {
        return clickableStartingWith(a, a.getString(titleRes) + ".");
    }

    /** Abre la pagina de esa categoria tocando su fila de la lista principal y comprueba que queda abierta. */
    static void openCategory(MainActivity a, int titleRes, String pageKey) {
        View row = categoryRow(a, titleRes);
        assertNotNull("fila de categoria " + a.getString(titleRes), row);
        row.performClick();
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("la pagina abierta", pageKey, a.openPageKey());
    }

    // ------------------------------------------------------------------ secciones

    @Test
    public void activityShowsTheCategoryList() {
        MainActivity a = create();
        // Pantalla principal: "Pruebalo", el titulo de la lista y una fila por categoria, en este orden
        assertTrue("falta la tarjeta Pruebalo", hasText(a, a.getString(R.string.try_title)));
        assertTrue("falta el titulo de la lista", hasText(a, a.getString(R.string.cat_list_title)));
        int[] titles = {R.string.cat_keys, R.string.cat_style, R.string.cat_size, R.string.aj_sec_smart, R.string.cat_touch,
                R.string.aj_sec_clipboard, R.string.ai_section, R.string.cat_privacy, R.string.aj_sec_help};
        String[] keys = {MainActivity.PAGE_KEYS, MainActivity.PAGE_STYLE, MainActivity.PAGE_SIZE, MainActivity.PAGE_SMART,
                MainActivity.PAGE_TOUCH, MainActivity.PAGE_CLIPBOARD, MainActivity.PAGE_AI, MainActivity.PAGE_PRIVACY, MainActivity.PAGE_HELP};
        List<View> order = all(a);
        int last = -1;
        for (int i = 0; i < titles.length; i++) {
            String title = a.getString(titles[i]);
            assertTrue("falta la categoria " + title, hasText(a, title));
            View row = categoryRow(a, titles[i]);
            assertNotNull("fila pulsable de " + title, row);
            assertTrue("se lee como boton", row.isClickable() || row.hasOnClickListeners());
            int at = order.indexOf(row);
            assertTrue("las categorias van en orden: " + title, at > last);
            last = at;
        }
        // Las secciones antiguas ya no son titulos: ni Diseno ni Mas ajustes ni los chips "Ir a"
        assertFalse(hasText(a, a.getString(R.string.aj_sec_design)));
        assertFalse(hasText(a, a.getString(R.string.nav_go)));
        // El resumen de Ayuda cuenta las filas de gestos y el de Idiomas y teclas dice la fila superior
        assertTrue(hasText(a, a.getString(R.string.sum_gestures, 18)));
        assertTrue(textContains(a, a.getString(R.string.sum_top_row, a.getString(R.string.top_row_numbers).toLowerCase())));
        // La descripcion accesible de cada fila es "Titulo. resumen"
        View help = categoryRow(a, R.string.aj_sec_help);
        assertEquals(a.getString(R.string.aj_sec_help) + ". " + a.getString(R.string.sum_gestures, 18), help.getContentDescription().toString());
        // Ninguna pagina esta abierta ni construida hasta que se toca su fila
        assertEquals(null, a.openPageKey());
        assertFalse("las filas de las paginas no se construyen de antemano", hasText(a, a.getString(R.string.aj_saber)));
        for (int i = 0; i < titles.length; i++) {
            openCategory(a, titles[i], keys[i]);
            assertTrue("la barra de la pagina lleva su titulo", hasText(a, a.getString(titles[i])));
            a.onBackPressed();
            assertEquals("atras cierra la pagina", null, a.openPageKey());
        }
        openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        assertTrue("con Estilo abierta, sus filas estan en el arbol", hasText(a, a.getString(R.string.aj_saber)));
        openCategory(a, R.string.cat_size, MainActivity.PAGE_SIZE);
        assertFalse("solo la pagina abierta cuelga de la columna", hasText(a, a.getString(R.string.aj_saber)));
        a.onBackPressed();
        // El extra "section" del intent abre la pagina pedida (el engrane del teclado manda "ai"; se aceptan las antiguas)
        assertEquals(MainActivity.PAGE_AI, MainActivity.pageKeyFor("ai"));
        assertEquals(MainActivity.PAGE_STYLE, MainActivity.pageKeyFor("design"));
        assertEquals(MainActivity.PAGE_SIZE, MainActivity.pageKeyFor("fold"));
        assertEquals(null, MainActivity.pageKeyFor("xyzzy"));
        android.content.Intent intent = new android.content.Intent(app, MainActivity.class).putExtra("section", "ai");
        MainActivity b = Robolectric.buildActivity(MainActivity.class, intent).setup().get();
        assertEquals("el engrane abre Inteligencia", MainActivity.PAGE_AI, b.openPageKey());
    }

    @Test
    public void phoneHasNoFoldSectionButKeepsSplitRowInSize() {
        MainActivity a = create();
        openCategory(a, R.string.cat_size, MainActivity.PAGE_SIZE);   // las paginas se construyen al abrirlas
        assertFalse("en un telefono no hay Plegables", hasText(a, a.getString(R.string.fold_section)));
        assertTrue("el dividido va dentro de Tamano y distribucion", hasText(a, a.getString(R.string.opt_split)));
        assertTrue("con la linea de deteccion de la pantalla", textContains(a, a.getString(R.string.fold_detected_phone, 411)));
        // "Ancho de las mitades" solo se ve con el dividido en Siempre
        TextView width = textView(a, a.getString(R.string.aj_split_width));
        assertNotNull(width);
        assertFalse(visibleUpToRoot(width));
        Prefs.get(app).edit().putString(Prefs.SPLIT, "on").apply();
        ShadowLooper.shadowMainLooper().idle();
        assertTrue(visibleUpToRoot(width));
    }

    @Test
    @Config(qualifiers = "es-rES-sw800dp-w800dp-h1200dp-xxhdpi")
    public void wideScreenHasFoldSection() {
        MainActivity a = create();
        assertTrue(DeviceInfo.wideScreen(a));
        assertFalse("Plegables no es una categoria propia", categoryRow(a, R.string.fold_section) != null);
        openCategory(a, R.string.cat_size, MainActivity.PAGE_SIZE);
        assertTrue("Plegables va dentro de Tamano y distribucion", hasText(a, a.getString(R.string.fold_section)));
        assertTrue(hasText(a, a.getString(R.string.aj_split_width)));
        assertTrue(textContains(a, a.getString(R.string.fold_detected_wide, 800)));   // va en el subtitulo de la fila de dividido
    }

    /** Visible en si misma y en todos sus ancestros (ocultar la fila oculta su titulo). */
    private static boolean visibleUpToRoot(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /** La pagina abierta no se guarda en preferencias (ya no hay claves sec_*): sobrevive al giro por el estado de la actividad. */
    @Test
    public void openPageSurvivesRotationWithoutTouchingPrefs() {
        Prefs.ui(app).edit().putBoolean("sec_design", true).apply();   // resto de una instalacion antigua
        org.robolectric.android.controller.ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        assertFalse("abrir una pagina no escribe en las preferencias del teclado", Prefs.get(app).contains("sec_design"));
        assertFalse("ni en las de la pantalla (las claves sec_* se limpian)", Prefs.ui(app).contains("sec_design"));
        assertFalse(Prefs.affectsKeyboard("sec_design"));
        // Giro: el estado guardado reabre la misma pagina en la actividad nueva
        android.os.Bundle state = new android.os.Bundle();
        c.saveInstanceState(state);
        MainActivity b = Robolectric.buildActivity(MainActivity.class).setup(state).get();
        assertEquals("la pagina abierta sobrevive al giro", MainActivity.PAGE_STYLE, b.openPageKey());
        assertTrue(hasText(b, a.getString(R.string.aj_saber)));
        b.onBackPressed();
        assertEquals(null, b.openPageKey());
        assertTrue("de vuelta en la lista", hasText(b, a.getString(R.string.cat_list_title)));
    }

    // ------------------------------------------------------------------ caracteres alternativos

    /** Primer interruptor cuya descripcion empieza por el titulo de su fila (optionRow encadena titulo, subtitulo y nota). */
    private static Switch switchFor(MainActivity a, String title) {
        for (View v : all(a)) {
            if (v instanceof Switch && String.valueOf(v.getContentDescription()).startsWith(title)) return (Switch) v;
        }
        return null;
    }

    @Test
    public void altCharsSwitchReflectsAndTogglesThePreference() {
        MainActivity a = create();
        openCategory(a, R.string.cat_keys, MainActivity.PAGE_KEYS);
        String title = a.getString(R.string.alt_chars_title);
        assertTrue("la fila Caracteres alternativos esta en Idiomas y teclas", hasText(a, title));
        assertTrue("con su subtitulo", hasText(a, a.getString(R.string.alt_chars_sub)));
        assertTrue("y su nota", hasText(a, a.getString(R.string.alt_chars_hint)));
        Switch sw = switchFor(a, title);
        assertNotNull("interruptor de caracteres alternativos", sw);
        assertTrue("la fila se ve con la pagina abierta", visibleUpToRoot(sw));
        assertTrue("activo de fabrica", Prefs.altChars(app));
        assertTrue("el interruptor refleja la preferencia (activa)", sw.isChecked());
        String spoken = sw.getContentDescription().toString();
        assertTrue("la descripcion lleva el subtitulo", spoken.contains(a.getString(R.string.alt_chars_sub)));
        assertTrue("la descripcion lleva la nota", spoken.contains(a.getString(R.string.alt_chars_hint)));

        sw.performClick();
        assertFalse("al tocarlo se apaga la preferencia", Prefs.altChars(app));
        assertFalse(Prefs.get(app).getBoolean(Prefs.ALT_CHARS, true));
        assertFalse(sw.isChecked());
        assertTrue("la clave despierta al teclado abierto", Prefs.affectsKeyboard(Prefs.ALT_CHARS));
        sw.performClick();
        assertTrue("otro toque la vuelve a encender", Prefs.altChars(app));
        assertTrue(sw.isChecked());

        // Tocar la fila entera tambien conmuta el interruptor
        View row = (View) sw.getParent();
        assertTrue("la fila es pulsable", row.hasOnClickListeners());
        row.performClick();
        assertFalse("tocar la fila apaga la preferencia", Prefs.altChars(app));
        assertFalse(sw.isChecked());

        // Con la preferencia apagada de antemano, la actividad arranca con el interruptor apagado
        Prefs.get(app).edit().putBoolean(Prefs.ALT_CHARS, false).apply();
        MainActivity b = create();
        openCategory(b, R.string.cat_keys, MainActivity.PAGE_KEYS);
        Switch sw2 = switchFor(b, title);
        assertNotNull(sw2);
        assertFalse("refleja la preferencia apagada", sw2.isChecked());

        // Restablecer, Exportar e Importar la tratan como las demas booleanas
        boolean listed = false;
        for (String k : MainActivity.RESET_KEYS) if (Prefs.ALT_CHARS.equals(k)) listed = true;
        assertTrue("en la lista blanca de Restablecer y Exportar", listed);
        assertEquals(MainActivity.TYPE_BOOL, MainActivity.keyType(Prefs.ALT_CHARS));
    }

    // ------------------------------------------------------------------ tema Automatico

    @Test
    public void automaticThemeIsSavedAsAutoAndFollowsNightMode() {
        MainActivity a = create();
        openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        // Grupo de automaticos con tres tarjetas (Republica, Imperio y Templos) y, debajo, los ocho fijos
        assertTrue(hasText(a, a.getString(R.string.theme_auto_group)));
        assertTrue(hasText(a, a.getString(R.string.theme_fixed_group)));
        assertFalse("el chip Automatico antiguo ya no esta", byDescription(a, a.getString(R.string.aj_theme_auto)) != null);
        View auto = clickableStartingWith(a, a.getString(R.string.theme_auto_republic) + ". " + a.getString(R.string.theme_auto_republic_sub));
        assertNotNull("tarjeta Republica", auto);
        assertTrue("se lee como automatico", auto.getContentDescription().toString().endsWith(a.getString(R.string.aj_theme_auto_badge)));
        auto.performClick();
        assertTrue(auto.isSelected());
        assertEquals("auto", Prefs.get(app).getString(Prefs.THEME, null));
        assertEquals(Prefs.THEME_AUTO, Prefs.themePref(app));
        // De dia (los calificadores no llevan "night") Automatico es Estelar
        assertEquals(Themes.ESTELAR.id, Prefs.theme(app));
        // De noche es Noche: un contexto con uiMode nocturno sobre las mismas preferencias
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context nightContext = app.createConfigurationContext(night);
        assertEquals(Themes.NOCHE.id, Prefs.theme(nightContext));
        assertEquals(Prefs.THEME_AUTO, Prefs.themePref(nightContext));
        // Imperio: Arena de dia e Imperial de noche
        View imperio = clickableStartingWith(a, a.getString(R.string.theme_auto_imperio) + ". " + a.getString(R.string.theme_auto_imperio_sub));
        assertNotNull("tarjeta Imperio", imperio);
        imperio.performClick();
        assertTrue(imperio.isSelected());
        assertFalse("Republica deja de estar seleccionada", auto.isSelected());
        assertEquals(Prefs.THEME_AUTO_IMPERIO, Prefs.get(app).getString(Prefs.THEME, null));
        assertEquals(Themes.ARENA.id, Prefs.theme(app));
        assertEquals(Themes.IMPERIAL.id, Prefs.theme(nightContext));
        ShadowLooper.shadowMainLooper().idle();
        assertTrue("el resumen de Estilo nombra el automatico", textContains(a, a.getString(R.string.theme_auto_imperio)));
        // Templos: Santuario Jedi de dia y Templo Sith de noche
        View templos = clickableStartingWith(a, a.getString(R.string.theme_auto_templos) + ". " + a.getString(R.string.theme_auto_templos_sub));
        assertNotNull("tarjeta Templos", templos);
        assertTrue(templos.getContentDescription().toString().endsWith(a.getString(R.string.aj_theme_auto_badge)));
        templos.performClick();
        assertTrue(templos.isSelected());
        assertFalse(imperio.isSelected());
        assertEquals(Prefs.THEME_AUTO_TEMPLOS, Prefs.get(app).getString(Prefs.THEME, null));
        assertEquals(Themes.SANTUARIO.id, Prefs.theme(app));
        assertEquals(Themes.SITH.id, Prefs.theme(nightContext));
        ShadowLooper.shadowMainLooper().idle();
        assertTrue(textContains(a, a.getString(R.string.theme_auto_templos)));
        // Un tema fijo no depende del modo; su muestra se selecciona y las automaticas no
        View arena = byDescription(a, a.getString(R.string.theme_arena));
        assertNotNull("muestra Arena entre los fijos", arena);
        arena.performClick();
        assertEquals(Themes.ARENA.id, Prefs.theme(nightContext));
        assertTrue(arena.isSelected());
        assertFalse(templos.isSelected());
        assertEquals("ocho fijos", 8, Themes.ALL.length);
        for (Themes.Theme t : Themes.ALL) assertNotNull("muestra fija " + t.id, byDescription(a, a.getString(t.name)));
        // Estelar y Noche se llaman ahora Nucleo y Borde Exterior (ids sin cambiar)
        assertEquals("Núcleo", a.getString(R.string.theme_estelar));
        assertEquals("Borde Exterior", a.getString(R.string.theme_noche));
        assertEquals("estelar", Themes.ESTELAR.id);
        assertEquals("noche", Themes.NOCHE.id);
    }

    // ------------------------------------------------------------------ atajos de texto

    @Test
    public void shortcutsCanBeAddedAndRemovedThroughPrefs() {
        assertTrue(Prefs.shortcuts(app).isEmpty());
        Prefs.addShortcut(app, "Ahr", "ahora vuelvo");
        assertEquals("la clave se guarda en minusculas", "ahora vuelvo", Prefs.shortcuts(app).get("ahr"));
        assertEquals(1, Prefs.shortcuts(app).size());

        MainActivity a = create();
        openCategory(a, R.string.aj_sec_smart, MainActivity.PAGE_SMART);
        assertTrue("la fila muestra el contador", hasText(a, a.getString(R.string.aj_shortcuts_row, 1)));

        Prefs.removeShortcut(app, "ahr");
        assertTrue(Prefs.shortcuts(app).isEmpty());
        ShadowLooper.shadowMainLooper().idle();   // el oyente de preferencias actualiza el contador
        assertTrue(hasText(a, a.getString(R.string.aj_shortcuts_row, 0)));

        // Validacion de la clave del formulario: solo letras, de 1 a 20
        assertTrue(MainActivity.validShortcutKey("ahr"));
        assertTrue(MainActivity.validShortcutKey("mañana"));
        assertFalse(MainActivity.validShortcutKey("a1"));
        assertFalse(MainActivity.validShortcutKey("ah r"));
        assertFalse(MainActivity.validShortcutKey(""));
        assertFalse(MainActivity.validShortcutKey("abcdefghijklmnopqrstu"));
    }

    // ------------------------------------------------------------------ deslizador de alto

    @Test
    public void heightSliderSavesPercentOnRelease() {
        MainActivity a = create();
        openCategory(a, R.string.cat_size, MainActivity.PAGE_SIZE);
        assertEquals(100, Prefs.heightPct(app));
        SeekBar bar = null;
        for (View v : all(a)) {
            if (v instanceof SeekBar && a.getString(R.string.aj_height).equals(String.valueOf(v.getContentDescription()))) bar = (SeekBar) v;
        }
        assertNotNull("deslizador de alto", bar);
        assertEquals("de 70 a 140: setenta pasos", 70, bar.getMax());
        assertEquals(30, bar.getProgress());   // 100 %

        SeekBar.OnSeekBarChangeListener l = Shadows.shadowOf(bar).getOnSeekBarChangeListener();
        assertNotNull(l);
        l.onStartTrackingTouch(bar);
        bar.setProgress(50);
        l.onProgressChanged(bar, 50, true);
        assertEquals("mientras se arrastra no se guarda", 100, Prefs.heightPct(app));
        l.onStopTrackingTouch(bar);
        assertEquals(120, Prefs.heightPct(app));
        assertTrue("la etiqueta muestra el valor", hasText(a, "120 %") || hasText(a, "120 %"));   // espacio normal o duro

        // Sin arrastre (teclado o accesibilidad) se guarda al momento
        l.onProgressChanged(bar, 10, true);
        assertEquals(80, Prefs.heightPct(app));

        // Migracion desde los tamanos antiguos cuando no hay porcentaje guardado
        Prefs.get(app).edit().clear().putString(Prefs.HEIGHT, "compact").apply();
        assertEquals(85, Prefs.heightPct(app));
        Prefs.get(app).edit().putString(Prefs.HEIGHT, "xlarge").apply();
        assertEquals(130, Prefs.heightPct(app));
    }

    // ------------------------------------------------------------------ campos de prueba: texto normal y PIN numerico

    /**
     * "Con el teclado del sistema" ofrece un campo de texto normal y uno de PIN numerico (TYPE_CLASS_NUMBER con
     * TYPE_NUMBER_VARIATION_PASSWORD, accion Listo y enmascarado); el antiguo campo de contrasena de texto ya no
     * existe. El unico EditText de texto enmascarado que queda en la app es la clave del servidor de inteligencia,
     * que es una credencial y no un campo de prueba.
     */
    @Test
    public void tryFieldsHaveNumericPinInsteadOfPassword() {
        MainActivity a = create();
        EditText plain = null, pin = null;
        int numericPins = 0;
        List<EditText> maskedText = new ArrayList<>();
        for (View v : all(a)) {
            if (!(v instanceof EditText)) continue;
            EditText e = (EditText) v;
            int cls = e.getInputType() & InputType.TYPE_MASK_CLASS;
            int variation = e.getInputType() & InputType.TYPE_MASK_VARIATION;
            if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) { pin = e; numericPins++; }
            if (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) maskedText.add(e);
            if (a.getString(R.string.real_text_hint).equals(String.valueOf(e.getHint()))) plain = e;
        }
        assertNotNull("campo de texto normal", plain);
        assertNotNull("campo de PIN numerico", pin);
        assertEquals("un solo campo de PIN", 1, numericPins);
        assertEquals(a.getString(R.string.real_pin_hint), String.valueOf(pin.getHint()));
        assertEquals("el PIN termina con Listo", EditorInfo.IME_ACTION_DONE, pin.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        assertTrue("el PIN va enmascarado", pin.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertSame("el PIN va junto al campo normal", plain.getParent(), pin.getParent());
        // En la caja de prueba solo estan esos dos campos: ninguno de contrasena de texto
        ViewGroup box = (ViewGroup) plain.getParent();
        int fields = 0;
        for (int i = 0; i < box.getChildCount(); i++) if (box.getChildAt(i) instanceof EditText) fields++;
        assertEquals("campos de prueba: texto normal y PIN", 2, fields);
        assertEquals(InputType.TYPE_CLASS_TEXT, plain.getInputType() & InputType.TYPE_MASK_CLASS);
        // Fuera de ella, el unico EditText de texto enmascarado es la clave del servidor de inteligencia
        for (EditText e : maskedText) {
            assertEquals("campo de contrasena de texto inesperado", a.getString(R.string.ai_key_optional), String.valueOf(e.getHint()));
        }
        // El subtitulo de la seccion habla del PIN numerico
        assertTrue(hasText(a, a.getString(R.string.real_sub)));
    }
}
