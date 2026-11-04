package com.aurebesh.teclado;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Logica pura del servicio del teclado ({@link AurebeshIME}) que no necesita un editor: la caja que conservan las
 * sustituciones (autocorreccion y atajos de texto), que es una sigla y la mayuscula inicial de las predicciones; y el
 * contrato de {@link Prefs} del que depende la escritura inteligente (atajos, palabras candidatas y la lista blanca de
 * claves que el oyente de preferencias del servicio atiende).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class ImeLogicTest {

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
    }

    // ------------------------------------------------------------------ caja de las sustituciones

    @Test
    public void matchCaseKeepsWhatWasTyped() {
        assertEquals("be right back", AurebeshIME.matchCase("brb", "be right back"));
        assertEquals("Be right back", AurebeshIME.matchCase("Brb", "be right back"));
        assertEquals("BE RIGHT BACK", AurebeshIME.matchCase("BRB", "be right back"));
        assertEquals("the", AurebeshIME.matchCase("teh", "the"));
        assertEquals("The", AurebeshIME.matchCase("Teh", "the"));
        // una sola letra en mayuscula no es "todo en mayusculas": solo capitaliza
        assertEquals("A", AurebeshIME.matchCase("Q", "a"));
        assertEquals("", AurebeshIME.matchCase("algo", ""));
        assertEquals("x", AurebeshIME.matchCase("", "x"));
    }

    @Test
    public void acronymsAreLeftAlone() {
        assertTrue(AurebeshIME.looksLikeAcronym("NASA"));
        assertTrue(AurebeshIME.looksLikeAcronym("iOS"));
        assertFalse(AurebeshIME.looksLikeAcronym("Hola"));
        assertFalse(AurebeshIME.looksLikeAcronym("iPhone"));
        assertFalse(AurebeshIME.looksLikeAcronym("hola"));
    }

    @Test
    public void capitalizeOnlyLowercaseStart() {
        assertEquals("Hola", AurebeshIME.capitalize("hola"));
        assertEquals("Hola", AurebeshIME.capitalize("Hola"));
        assertEquals("NASA", AurebeshIME.capitalize("NASA"));
        assertEquals("", AurebeshIME.capitalize(""));
        assertNull(AurebeshIME.capitalize(null));
        assertEquals("Ñu", AurebeshIME.capitalize("ñu"));
    }

    // ------------------------------------------------------------------ atajos de texto

    @Test
    public void shortcutsRoundTrip() {
        assertTrue(Prefs.shortcuts(app).isEmpty());
        Prefs.addShortcut(app, " BRB ", "be right back");
        Prefs.addShortcut(app, "omw", "on my way");
        LinkedHashMap<String, String> all = Prefs.shortcuts(app);
        assertEquals(2, all.size());
        assertEquals("be right back", all.get("brb"));            // la clave se guarda en minusculas y sin espacios
        assertEquals("on my way", all.get("omw"));
        // la expansion que ve el usuario conserva la caja de lo que escribio
        assertEquals("Be right back", AurebeshIME.matchCase("Brb", all.get("brb")));
        Prefs.addShortcut(app, "brb", "vuelvo enseguida");         // redefinir sustituye
        assertEquals("vuelvo enseguida", Prefs.shortcuts(app).get("brb"));
        Prefs.removeShortcut(app, "brb");
        assertNull(Prefs.shortcuts(app).get("brb"));
        assertEquals(1, Prefs.shortcuts(app).size());
        // saltos de linea y tabuladores en la expansion no rompen el formato "clave\tvalor"
        Prefs.addShortcut(app, "dir", "calle\tMayor\n1");
        assertEquals("calle Mayor 1", Prefs.shortcuts(app).get("dir"));
    }

    @Test
    public void shortcutsRejectBadKeys() {
        Prefs.addShortcut(app, "", "algo");
        Prefs.addShortcut(app, "x", "   ");
        Prefs.addShortcut(app, "unaclavedemasiadolargaparaunatajo", "algo");
        assertTrue(Prefs.shortcuts(app).isEmpty());
    }

    // ------------------------------------------------------------------ palabras candidatas (aprender al repetirse)

    @Test
    public void candidatesLearnOnSecondSight() {
        assertEquals(1, Prefs.bumpCandidate(app, "Xochitl"));
        assertEquals(1, (int) Prefs.candidates(app).get("xochitl"));   // en minusculas
        assertEquals(1, Prefs.bumpCandidate(app, "otra"));
        assertEquals(2, Prefs.bumpCandidate(app, "xochitl"));          // segunda aparicion: el IME la aprende y la quita
        Prefs.removeCandidate(app, "xochitl");
        assertNull(Prefs.candidates(app).get("xochitl"));
        assertEquals(1, (int) Prefs.candidates(app).get("otra"));
        Prefs.clearCandidates(app);
        assertTrue(Prefs.candidates(app).isEmpty());
    }

    @Test
    public void candidatesKeepTheMostRecent() {
        for (int i = 0; i < 205; i++) Prefs.bumpCandidate(app, "palabra" + i);
        LinkedHashMap<String, Integer> all = Prefs.candidates(app);
        assertEquals(200, all.size());
        assertNull(all.get("palabra0"));
        assertEquals(1, (int) all.get("palabra204"));
    }

    // ------------------------------------------------------------------ lista blanca del oyente de preferencias

    @Test
    public void listenerWhitelist() {
        // lo que cambia el teclado en pantalla
        assertTrue(Prefs.affectsKeyboard(Prefs.AUTO_REPLACE));
        assertTrue(Prefs.affectsKeyboard(Prefs.SHORTCUTS));
        assertTrue(Prefs.affectsKeyboard(Prefs.HEIGHT_PCT + "_wide"));
        assertTrue(Prefs.affectsKeyboard(Prefs.APP_LANG));
        // lo que el servicio ignora: historial, emojis recientes, bigramas, candidatas y la pantalla de ajustes
        assertFalse(Prefs.affectsKeyboard(ClipHistory.KEY));
        assertFalse(Prefs.affectsKeyboard(Prefs.EMOJI_RECENT));
        assertFalse(Prefs.affectsKeyboard(Bigrams.PREF_KEY));
        assertFalse(Prefs.affectsKeyboard(Prefs.CANDIDATES));
        assertFalse(Prefs.affectsKeyboard("sec_abierta"));
        assertFalse(Prefs.affectsKeyboard(null));
    }
}
