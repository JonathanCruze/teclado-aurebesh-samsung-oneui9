package com.aurebesh.teclado;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pantalla de ajustes 2.0: el tema Contraste existe y se guarda desde la fila de temas; los chips del sable
 * guardan Prefs.SABER; las letras latinas tienen tres modos (y migran el booleano antiguo); el buscador oculta
 * las secciones sin coincidencias, muestra la fila buscada sin distinguir acentos y lo restaura todo al vaciarse;
 * "Restablecer ajustes" no toca el diccionario personal ni los atajos; y "Exportar ajustes" produce un JSON con la
 * clave del tema y sin la clave del servidor propio (salvo con la casilla), que al importarse valida los tipos.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Settings2Test {

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
        Bigrams.forget(app);
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

    private static View byDescription(MainActivity a, String description) {
        for (View v : all(a)) {
            if (v.getContentDescription() != null && description.equals(v.getContentDescription().toString())) return v;
        }
        return null;
    }

    /** Visible en si misma y en todos sus ancestros (ocultar la fila o la seccion oculta su titulo). */
    private static boolean visibleUpToRoot(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /** Tarjeta de una seccion: la cabecera pulsable cuyo titulo es {@code title}, y de ahi su padre. */
    private static View sectionCard(MainActivity a, String title) {
        for (View v : all(a)) {
            if (!(v instanceof TextView) || !title.equals(((TextView) v).getText().toString())) continue;
            if (!(v.getParent() instanceof View) || !(v.getParent().getParent() instanceof View)) continue;
            View header = (View) v.getParent().getParent();
            if (header.hasOnClickListeners() && header.getParent() instanceof View) return (View) header.getParent();
        }
        return null;
    }

    /** Chip con el texto {@code label} en la misma fila que {@code chip}, o null. */
    private static TextView sibling(TextView chip, String label) {
        if (!(chip.getParent() instanceof ViewGroup)) return null;
        ViewGroup row = (ViewGroup) chip.getParent();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v instanceof TextView && label.equals(((TextView) v).getText().toString())) return (TextView) v;
        }
        return null;
    }

    private static EditText searchField(MainActivity a) {
        String hint = a.getString(R.string.aj_search_hint);
        for (View v : all(a)) {
            if (v instanceof EditText && hint.equals(String.valueOf(((EditText) v).getHint()))) return (EditText) v;
        }
        return null;
    }

    // ------------------------------------------------------------------ tema Contraste

    @Test
    public void contrastThemeExistsAndIsSavedFromTheThemeRow() {
        assertEquals(Themes.CONTRASTE, Themes.byId("contraste"));
        boolean listed = false;
        for (Themes.Theme t : Themes.ALL) if (t == Themes.CONTRASTE) listed = true;
        assertTrue("Contraste esta en Themes.ALL", listed);
        assertEquals("fondo negro", 0xFF000000, Themes.CONTRASTE.bg);
        assertEquals("teclas blancas", 0xFFFFFFFF, Themes.CONTRASTE.keyBg);
        assertEquals("borde negro de 2 dp", 2f, Themes.CONTRASTE.strokeDp, 0.001f);
        assertEquals("acento amarillo", 0xFFFFD600, Themes.CONTRASTE.accent);
        assertEquals("icono negro sobre el acento", 0xFF000000, Themes.CONTRASTE.onAccent);
        assertFalse(Themes.CONTRASTE.shadow);

        MainActivity a = create();
        View item = byDescription(a, a.getString(R.string.theme_contraste));
        assertNotNull("muestra Contraste en la fila de temas", item);
        item.performClick();
        assertEquals("contraste", Prefs.get(app).getString(Prefs.THEME, null));
        assertEquals(Themes.CONTRASTE.id, Prefs.theme(app));
        assertTrue(item.isSelected());
        ShadowLooper.shadowMainLooper().idle();   // el oyente de preferencias rehace los resumenes
        assertTrue("el resumen de Diseno nombra el tema", textContains(a, a.getString(R.string.theme_contraste) + " · "));
    }

    /** Algun TextView cuyo texto contiene {@code s} (resumenes compuestos). */
    private static boolean textContains(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && ((TextView) v).getText().toString().contains(s)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ sable

    @Test
    public void saberChipSavesPrefAndUpdatesSummary() {
        MainActivity a = create();
        assertEquals("azul de fabrica", "blue", Prefs.saber(app));
        String red = a.getString(R.string.saber_red) + " · " + a.getString(R.string.saber_red_who);
        View chip = byDescription(a, red);
        assertNotNull("chip Rojo · Sith", chip);
        chip.performClick();
        assertEquals("red", Prefs.get(app).getString(Prefs.SABER, null));
        assertEquals(Prefs.SABER_ARGB[5], Prefs.saberArgb(app));
        assertTrue(chip.isSelected());
        assertTrue("bajo los chips se dice de quien es", hasText(a, red));
        ShadowLooper.shadowMainLooper().idle();
        assertTrue("el resumen de Diseno lleva el sable",
                textContains(a, a.getString(R.string.aj_sum_saber, a.getString(R.string.saber_red).toLowerCase())));
        // Cambiado desde fuera (el IME), la pantalla sigue
        Prefs.get(app).edit().putString(Prefs.SABER, "purple").apply();
        ShadowLooper.shadowMainLooper().idle();
        assertFalse(chip.isSelected());
        View purple = byDescription(a, a.getString(R.string.saber_purple) + " · " + a.getString(R.string.saber_purple_who));
        assertNotNull(purple);
        assertTrue(purple.isSelected());
    }

    // ------------------------------------------------------------------ letras latinas: tres modos

    @Test
    public void hintModeChipsSaveTheModeAndMigrateTheOldBoolean() {
        Prefs.get(app).edit().putBoolean(Prefs.HINTS, true).apply();   // instalacion antigua: solo el booleano
        assertEquals("on", Prefs.hintMode(app));
        assertEquals(1, Prefs.hintModeInt(app));
        MainActivity a = create();
        TextView learning = textView(a, a.getString(R.string.aj_hint_learning));
        assertNotNull("chip Aprendiendo", learning);
        learning.performClick();
        assertEquals("learning", Prefs.get(app).getString(Prefs.HINT_MODE, null));
        assertEquals(2, Prefs.hintModeInt(app));
        assertTrue("el booleano antiguo sigue en paralelo", Prefs.hints(app));
        TextView never = sibling(learning, a.getString(R.string.aj_hint_off));   // "Nunca" tambien es un chip del dividido
        assertNotNull(never);
        never.performClick();
        assertEquals("off", Prefs.hintMode(app));
        assertFalse(Prefs.hints(app));
        // "Reiniciar progreso" borra los contadores del modo Aprendiendo
        Prefs.get(app).edit().putString(Prefs.HINT_COUNTS, "a 12\nb 40").apply();
        TextView reset = textView(a, a.getString(R.string.aj_hint_reset));
        assertNotNull(reset);
        reset.performClick();
        assertFalse(Prefs.get(app).contains(Prefs.HINT_COUNTS));
    }

    // ------------------------------------------------------------------ buscador

    @Test
    public void searchHidesSectionsWithoutMatchesAndShowsTheRow() {
        MainActivity a = create();
        EditText field = searchField(a);
        assertNotNull("campo Buscar ajuste", field);
        View design = sectionCard(a, a.getString(R.string.aj_sec_design));
        View touch = sectionCard(a, a.getString(R.string.aj_sec_touch));
        View more = sectionCard(a, a.getString(R.string.aj_sec_more));
        assertNotNull(design);
        assertNotNull(touch);
        assertNotNull("hay seccion Mas ajustes", more);
        TextView saber = textView(a, a.getString(R.string.aj_saber));
        TextView vibrate = textView(a, a.getString(R.string.opt_vibrate));
        assertNotNull(saber);
        assertNotNull(vibrate);
        assertFalse("Diseno empieza cerrada", visibleUpToRoot(saber));

        field.setText("sable");
        assertEquals(View.VISIBLE, design.getVisibility());
        assertEquals("sin coincidencias, la seccion se oculta", View.GONE, touch.getVisibility());
        assertEquals(View.GONE, more.getVisibility());
        assertTrue("la seccion con coincidencias se abre y la fila se ve", visibleUpToRoot(saber));
        assertFalse(visibleUpToRoot(vibrate));
        assertFalse("sin abrir Diseno de forma permanente", Prefs.ui(app).getBoolean("sec_design", false));

        field.setText("vibracion");   // sin acento: encuentra "Vibración al pulsar"
        assertEquals(View.GONE, design.getVisibility());
        assertEquals(View.VISIBLE, touch.getVisibility());
        assertTrue(visibleUpToRoot(vibrate));
        assertFalse(visibleUpToRoot(saber));

        field.setText("xyzzy");
        assertEquals(View.GONE, design.getVisibility());
        assertEquals(View.GONE, touch.getVisibility());
        assertTrue(hasText(a, a.getString(R.string.aj_search_none)));
        assertTrue(visibleUpToRoot(textView(a, a.getString(R.string.aj_search_none))));

        field.setText("");
        assertEquals(View.VISIBLE, design.getVisibility());
        assertEquals(View.VISIBLE, touch.getVisibility());
        assertEquals(View.VISIBLE, more.getVisibility());
        assertFalse("vacio restaura: Diseno vuelve a estar cerrada", visibleUpToRoot(saber));
        assertFalse(visibleUpToRoot(textView(a, a.getString(R.string.aj_search_none))));
        assertEquals("vibracion al pulsar", MainActivity.normalize("Vibración  AL pulsar "));
        assertEquals("diseno", MainActivity.normalize("Diseño"));
    }

    // ------------------------------------------------------------------ restablecer

    @Test
    public void resetKeepsLearnedWordsAndShortcuts() {
        Prefs.get(app).edit()
                .putString(Prefs.THEME, "contraste")
                .putString(Prefs.SABER, "red")
                .putString(Prefs.HINT_MODE, "learning")
                .putBoolean(Prefs.STARWARS, false)
                .putInt(Prefs.SOUND_VOLUME, 40)
                .putString(Prefs.EMOJI_RECENT, "😀")
                .putString(AiModels.REMOTE_KEY, "secreta")
                .apply();
        Prefs.addLearnedWord(app, "ahsoka");
        Prefs.addShortcut(app, "brb", "be right back");
        Prefs.bumpCandidate(app, "venator");
        MainActivity a = create();
        int n = a.resetSettings();
        assertTrue(n >= 5);
        assertFalse(Prefs.get(app).contains(Prefs.THEME));
        assertFalse(Prefs.get(app).contains(Prefs.SABER));
        assertFalse(Prefs.get(app).contains(Prefs.HINT_MODE));
        assertFalse(Prefs.get(app).contains(Prefs.STARWARS));
        assertFalse(Prefs.get(app).contains(Prefs.SOUND_VOLUME));
        assertTrue("el diccionario personal se conserva", Prefs.learnedWords(app).contains("ahsoka"));
        assertEquals("los atajos se conservan", "be right back", Prefs.shortcuts(app).get("brb"));
        assertTrue("las candidatas se conservan", Prefs.candidates(app).containsKey("venator"));
        assertEquals("los emojis recientes se conservan", "😀", Prefs.get(app).getString(Prefs.EMOJI_RECENT, null));
        assertEquals("las claves ai_* se conservan", "secreta", Prefs.get(app).getString(AiModels.REMOTE_KEY, null));
        // La lista blanca nunca incluye lo personal
        for (String k : MainActivity.RESET_KEYS) {
            assertFalse(k, Prefs.LEARNED.equals(k) || Prefs.CANDIDATES.equals(k) || Prefs.SHORTCUTS.equals(k)
                    || Prefs.EMOJI_RECENT.equals(k) || Prefs.CUSTOM_SOUND.equals(k) || k.startsWith("ai_"));
        }
        // "Borrar datos personales" hace lo contrario: lo personal se va, los ajustes se quedan
        Prefs.get(app).edit().putString(Prefs.THEME, "noche").apply();
        a.wipePersonalData();
        assertTrue(Prefs.learnedWords(app).isEmpty());
        assertTrue(Prefs.shortcuts(app).isEmpty());
        assertTrue(Prefs.candidates(app).isEmpty());
        assertFalse(Prefs.get(app).contains(Prefs.EMOJI_RECENT));
        assertEquals("noche", Prefs.get(app).getString(Prefs.THEME, null));
    }

    // ------------------------------------------------------------------ exportar e importar ajustes

    @Test
    public void exportProducesJsonWithThemeAndWithoutRemoteKey() throws Exception {
        Prefs.get(app).edit().putString(Prefs.THEME, "contraste").putString(Prefs.SABER, "green")
                .putInt(Prefs.SOUND_VOLUME, 55).putBoolean(Prefs.STARWARS, false).apply();
        AiModels.setRemote(app, "https://servidor.local/v1", "clave-secreta", "modelo");
        Prefs.addShortcut(app, "brb", "be right back");
        Prefs.addLearnedWord(app, "ahsoka");
        MainActivity a = create();

        String json = a.exportJson(false);
        JSONObject o = new JSONObject(json);
        assertEquals("aurebesh", o.getString("app"));
        JSONObject settings = o.getJSONObject("settings");
        assertEquals("contraste", settings.getString(Prefs.THEME));
        assertEquals("green", settings.getString(Prefs.SABER));
        assertEquals(55, settings.getInt(Prefs.SOUND_VOLUME));
        assertFalse(settings.getBoolean(Prefs.STARWARS));
        assertFalse("nunca la clave del servidor sin la casilla", json.contains(AiModels.REMOTE_KEY));
        assertFalse(json.contains("clave-secreta"));
        assertFalse(json.contains(AiModels.REMOTE_URL));
        assertFalse("el diccionario no va entre los ajustes", settings.has(Prefs.LEARNED));
        assertEquals("be right back", o.getJSONObject("shortcuts").getString("brb"));
        assertEquals("ahsoka", o.getJSONArray("learned").getString(0));
        assertTrue(o.has("pairs"));

        String withKey = a.exportJson(true);
        assertTrue("con la casilla, el servidor propio viaja", withKey.contains("clave-secreta"));
        assertEquals("https://servidor.local/v1", new JSONObject(withKey).getJSONObject("settings").getString(AiModels.REMOTE_URL));

        // Importar: lo exportado vuelve; lo de tipo equivocado o desconocido no entra; los enteros se acotan
        Prefs.get(app).edit().clear().apply();
        int n = a.importJson(json);
        assertTrue(n >= 4);
        assertEquals("contraste", Prefs.get(app).getString(Prefs.THEME, null));
        assertEquals(55, Prefs.soundVolume(app));
        assertEquals("be right back", Prefs.shortcuts(app).get("brb"));
        assertTrue(Prefs.learnedWords(app).contains("ahsoka"));
        assertNull("sin casilla no habia clave que importar", Prefs.get(app).getString(AiModels.REMOTE_KEY, null));

        Prefs.get(app).edit().clear().apply();
        String bad = "{\"app\":\"aurebesh\",\"settings\":{\"" + Prefs.THEME + "\":5,\"" + Prefs.HEIGHT_PCT + "\":\"alto\","
                + "\"" + Prefs.SOUND_VOLUME + "\":500,\"" + Prefs.LEARNED + "\":\"hack\",\"" + Prefs.STARWARS + "\":true}}";
        assertEquals(2, a.importJson(bad));
        assertFalse("un tema que no es texto no entra", Prefs.get(app).contains(Prefs.THEME));
        assertFalse(Prefs.get(app).contains(Prefs.HEIGHT_PCT));
        assertEquals("el volumen se acota a 100", 100, Prefs.soundVolume(app));
        assertFalse("una clave fuera de la lista blanca no entra", Prefs.get(app).contains(Prefs.LEARNED));
        assertTrue(Prefs.starWars(app));
        assertEquals(-1, a.importJson("esto no es json"));
        assertEquals(-1, a.importJson("{\"settings\":{}}"));

        // Diccionario personal como texto: una palabra por linea
        assertEquals(2, a.importWordList("Ahsoka\nVenator\n\ncon espacio no\n"));
        assertTrue(Prefs.learnedWords(app).contains("ahsoka"));
        assertTrue(Prefs.learnedWords(app).contains("venator"));
        assertEquals(MainActivity.TYPE_STRING, MainActivity.keyType(Prefs.THEME));
        assertEquals(MainActivity.TYPE_INT, MainActivity.keyType(Prefs.HEIGHT_PCT + "_wide"));
        assertEquals(MainActivity.TYPE_BOOL, MainActivity.keyType(Prefs.STARWARS));
        assertEquals(MainActivity.TYPE_NONE, MainActivity.keyType(Prefs.LEARNED));
    }
}
