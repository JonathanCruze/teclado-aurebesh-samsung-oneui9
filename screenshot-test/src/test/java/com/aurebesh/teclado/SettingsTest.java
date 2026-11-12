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
 * Pantalla de ajustes (1.10): la actividad se crea con las secciones como One UI, el tema Automatico se guarda
 * como "auto" y se resuelve con el modo oscuro, los atajos de texto van y vienen desde Prefs, el deslizador de
 * alto guarda el porcentaje al soltar, en un telefono no hay seccion Plegables (el dividido va en Diseno) y los campos
 * de prueba son uno de texto normal y uno de PIN numerico (ya no hay campo de contrasena de texto). Desde 2.1 Diseno
 * lleva el interruptor "Caracteres alternativos" (Prefs.ALT_CHARS, activo de fabrica).
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

    // ------------------------------------------------------------------ secciones

    @Test
    public void activityShowsTheNewSections() {
        MainActivity a = create();
        int[] titles = {R.string.try_title, R.string.aj_sec_design, R.string.aj_sec_size, R.string.aj_sec_smart,
                R.string.aj_sec_touch, R.string.aj_sec_clipboard, R.string.ai_section, R.string.aj_sec_help};
        for (int res : titles) assertTrue("falta la seccion " + a.getString(res), hasText(a, a.getString(res)));
        // La fila "Ir a" existe y el resumen de Ayuda cuenta las filas de gestos
        assertTrue(hasText(a, a.getString(R.string.nav_go)));
        assertTrue(hasText(a, a.getString(R.string.sum_gestures, 18)));
    }

    @Test
    public void phoneHasNoFoldSectionButKeepsSplitRowInDesign() {
        Prefs.ui(app).edit().putBoolean("sec_design", true).apply();   // Diseno abierta: solo asi se ve su contenido
        MainActivity a = create();
        assertFalse("en un telefono no hay seccion Plegables", hasText(a, a.getString(R.string.fold_section)));
        assertTrue("el dividido va dentro de Diseno", hasText(a, a.getString(R.string.opt_split)));
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
        assertTrue(hasText(a, a.getString(R.string.fold_section)));
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

    @Test
    public void sectionStateLivesInUiPrefsNotKeyboardPrefs() {
        MainActivity a = create();
        View header = null;
        for (View v : all(a)) {                                // el primer "Diseno" es el chip de "Ir a"; la cabecera cuelga de una fila pulsable
            if (!(v instanceof TextView) || !a.getString(R.string.aj_sec_design).equals(((TextView) v).getText().toString())) continue;
            if (!(v.getParent() instanceof View) || !(v.getParent().getParent() instanceof View)) continue;
            View h = (View) v.getParent().getParent();
            if (h.hasOnClickListeners()) { header = h; break; }
        }
        assertNotNull(header);
        header.performClick();
        assertTrue(Prefs.ui(app).getBoolean("sec_design", false));
        assertFalse("las claves sec_* no deben despertar al teclado", Prefs.get(app).contains("sec_design"));
        assertFalse(Prefs.affectsKeyboard("sec_design"));
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
        Prefs.ui(app).edit().putBoolean("sec_design", true).apply();   // Diseno abierta
        MainActivity a = create();
        String title = a.getString(R.string.alt_chars_title);
        assertTrue("la fila Caracteres alternativos esta en Diseno", hasText(a, title));
        assertTrue("con su subtitulo", hasText(a, a.getString(R.string.alt_chars_sub)));
        assertTrue("y su nota", hasText(a, a.getString(R.string.alt_chars_hint)));
        Switch sw = switchFor(a, title);
        assertNotNull("interruptor de caracteres alternativos", sw);
        assertTrue("la fila se ve con Diseno abierta", visibleUpToRoot(sw));
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
        Switch sw2 = switchFor(b, title);
        assertNotNull(sw2);
        assertFalse("refleja la preferencia apagada", sw2.isChecked());

        // Restablecer, Exportar e Importar la tratan como las demas booleanas de Diseno
        boolean listed = false;
        for (String k : MainActivity.RESET_KEYS) if (Prefs.ALT_CHARS.equals(k)) listed = true;
        assertTrue("en la lista blanca de Restablecer y Exportar", listed);
        assertEquals(MainActivity.TYPE_BOOL, MainActivity.keyType(Prefs.ALT_CHARS));
    }

    // ------------------------------------------------------------------ tema Automatico

    @Test
    public void automaticThemeIsSavedAsAutoAndFollowsNightMode() {
        MainActivity a = create();
        View auto = byDescription(a, a.getString(R.string.aj_theme_auto));
        assertNotNull("muestra Automatico en la fila de temas", auto);
        auto.performClick();
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
        // Un tema fijo no depende del modo
        Prefs.get(app).edit().putString(Prefs.THEME, Themes.ARENA.id).apply();
        assertEquals(Themes.ARENA.id, Prefs.theme(nightContext));
    }

    // ------------------------------------------------------------------ atajos de texto

    @Test
    public void shortcutsCanBeAddedAndRemovedThroughPrefs() {
        assertTrue(Prefs.shortcuts(app).isEmpty());
        Prefs.addShortcut(app, "Ahr", "ahora vuelvo");
        assertEquals("la clave se guarda en minusculas", "ahora vuelvo", Prefs.shortcuts(app).get("ahr"));
        assertEquals(1, Prefs.shortcuts(app).size());

        MainActivity a = create();
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
