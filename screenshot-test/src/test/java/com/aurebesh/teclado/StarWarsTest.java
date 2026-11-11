package com.aurebesh.teclado;

import android.app.Application;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Lexico Star Wars del diccionario ({@link Dictionary#setStarWars}): con el lexico activo sus entradas se aceptan
 * como conocidas, se completan y se corrige hacia ellas a una y dos ediciones (curuscant -> coruscant, palatino ->
 * palpatine) en cualquier idioma; {@link Dictionary#canonical} devuelve la caja propia ("Coruscant") salvo para las
 * entradas que son ademas palabras normales del idioma ("fuerza", "orden", "ghost"); las entradas cortas solo
 * cuentan escritas con su caja; desactivado, nada de esto ocurre; y el archivo de palabras no tiene lineas vacias
 * ni repetidas. Todo local, sin red.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class StarWarsTest {

    private static final String[] LANGS = {"es", "en", "de"};

    private Application app;

    @Before
    public void setUp() throws IOException {
        app = (Application) RuntimeEnvironment.getApplication();
        for (String l : LANGS) {
            Dictionary d = Dictionary.get(app, l);
            d.setPersonal(null);
            d.setStarWars(true);
        }
    }

    @After
    public void tearDown() {
        for (String l : LANGS) {                           // los diccionarios se comparten entre pruebas: se dejan como estaban
            Dictionary d = Dictionary.cached(l);
            if (d != null) { d.setStarWars(false); d.setPersonal(null); }
        }
    }

    // ------------------------------------------------------------------ correccion

    @Test
    public void correctsCoruscantInSpanishAndEnglish() throws IOException {
        for (String l : new String[]{"es", "en"}) {
            Dictionary d = Dictionary.get(app, l);
            assertTrue(l, d.starWars());
            Dictionary.Result r = d.suggest("curuscant");       // una letra cambiada (u por o, no vecinas)
            assertFalse(l, r.known);
            assertEquals(l, "coruscant", r.correction);
            assertEquals(l, "coruscant", r.suggestions.get(0));
            assertEquals(l, "coruscant", d.suggest("Curuscant").correction);   // la caja de lo escrito no cambia nada
            assertEquals(l, "Coruscant", d.canonical("coruscant"));
            assertTrue(l, d.contains("coruscant"));
            Dictionary.Result known = d.suggest("coruscant");    // escrita bien: conocida y sin tocar
            assertTrue(l, known.known);
            assertNull(l, known.correction);
        }
    }

    @Test
    public void correctsPalpatineAtTwoEdits() throws IOException {
        for (String l : new String[]{"en", "es"}) {
            Dictionary d = Dictionary.get(app, l);
            Dictionary.Result r = d.suggest("palatino");         // una letra de menos y otra cambiada: dos ediciones caras
            assertFalse(l, r.known);
            assertEquals(l + " " + r.suggestions, "palpatine", r.correction);
            assertEquals(l, "Palpatine", d.canonical("palpatine"));
        }
    }

    @Test
    public void restoresAccentOfSagaWord() throws IOException {
        Dictionary en = Dictionary.get(app, "en");
        Dictionary.Result r = en.suggest("padme");
        assertFalse(r.known);
        assertEquals("padmé", r.correction);
        assertEquals("Padmé", en.canonical("padmé"));
        assertTrue(en.suggest("padmé").known);
    }

    @Test
    public void frequentWordsStillWin() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        assertEquals("hola", es.suggest("holq").correction);     // como siempre, aunque exista "holdo"
        assertEquals("hola", es.suggest("holla").correction);
        assertEquals("canción", es.suggest("cancion").correction);
        assertNull(es.suggest("hola").correction);
        assertTrue(es.suggest("hol").suggestions.contains("hola"));
    }

    // ------------------------------------------------------------------ conocidas y completados

    @Test
    public void sagaWordsAreKnownAndCompleted() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        assertTrue(es.suggest("ahsoka").known);
        Dictionary.Result tantive = es.suggest("tantive");
        assertTrue(tantive.known);
        assertNull(tantive.correction);
        assertEquals("Tantive", es.canonical("tantive"));
        assertTrue(es.contains("tantive"));
        assertTrue(es.suggest("kyber").known);
        assertTrue(es.suggest("twilek").known);                  // Twi'lek sin apostrofo
        assertTrue("plural regular de una entrada", es.suggest("jedis").known);
        assertTrue(es.suggest("wookiees").known);
        assertTrue("escrita con tildes de mas se respeta", es.suggest("ahsóka").known);
        // completado por prefijo y con un error en el prefijo
        assertTrue(es.suggest("corus").suggestions.toString(), es.suggest("corus").suggestions.contains("coruscant"));
        assertTrue(es.suggest("kyb").suggestions.toString(), es.suggest("kyb").suggestions.contains("kyber"));
        assertTrue(es.suggest("coruz").suggestions.toString(), es.suggest("coruz").suggestions.contains("coruscant"));
        // en aleman tambien
        Dictionary de = Dictionary.get(app, "de");
        assertTrue(de.suggest("sturmtruppler").known);
        assertEquals("Sturmtruppler", de.canonical("sturmtruppler"));
        assertEquals("Coruscant", de.canonical("coruscant"));
    }

    @Test
    public void ordinaryWordsKeepTheirForm() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        assertNull(es.canonical("fuerza"));                      // "Fuerza" esta en el lexico pero es palabra normal
        assertNull(es.canonical("orden"));
        assertNull(es.canonical("rey"));
        assertTrue(es.suggest("fuerza").known);
        assertNull(es.suggest("fuerza").correction);
        Dictionary en = Dictionary.get(app, "en");
        assertNull(en.canonical("ghost"));
        assertNull(en.canonical("wing"));
        assertNull(en.canonical("force"));
        assertNull(en.canonical("hola"));                        // ni siquiera es del lexico
        assertNull(en.canonical(null));
        Dictionary de = Dictionary.get(app, "de");
        assertNull(de.canonical("macht"));
        assertNull(de.canonical("imperium"));
    }

    @Test
    public void shortNamesOnlyCountWithTheirCase() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        assertTrue(es.suggest("Maz").known);                     // nombre de tres letras con su caja
        Dictionary.Result lower = es.suggest("maz");             // en minusculas sigue siendo un error de "mas"
        assertFalse(lower.known);
        assertNotNull(lower.correction);
        assertNotEquals("maz", lower.correction);
        assertTrue(es.suggest("Nal").known);
        Dictionary.Result nal = es.suggest("nal");
        assertFalse(nal.known);
        assertNotNull(nal.correction);
        assertFalse(es.contains("nal"));                         // nunca se aprende ni se propone como correccion
        assertTrue(es.suggest("AT").known);
        assertFalse(es.suggest("at").known);
    }

    // ------------------------------------------------------------------ desactivado

    @Test
    public void disabledLexiconChangesNothing() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        es.setStarWars(false);
        assertFalse(es.starWars());
        Dictionary.Result r = es.suggest("curuscant");
        assertFalse(r.known);
        assertNotEquals("coruscant", r.correction);
        assertFalse(r.suggestions.contains("coruscant"));
        assertNull(es.canonical("coruscant"));
        assertFalse(es.contains("coruscant"));
        assertFalse(es.suggest("tantive").known);
        assertFalse(es.suggest("corus").suggestions.contains("coruscant"));
        es.setStarWars(true);                                    // y al volver a activarlo, todo vuelve
        assertEquals("coruscant", es.suggest("curuscant").correction);
        assertEquals("Coruscant", es.canonical("coruscant"));
    }

    // ------------------------------------------------------------------ archivo de palabras

    @Test
    public void wordFileHasNoBlankOrRepeatedLines() throws IOException {
        Set<String> seen = new HashSet<>();
        Set<String> lower = new HashSet<>();
        int count = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(
                app.getResources().openRawResource(R.raw.starwars_words), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                count++;
                assertFalse("linea vacia en la " + count, line.trim().isEmpty());
                assertEquals("espacios sobrantes en la linea " + count, line.trim(), line);
                for (int i = 0; i < line.length(); i++) {
                    assertTrue("caracter que no es letra en '" + line + "'", Character.isLetter(line.charAt(i)));
                }
                assertTrue("repetida: " + line, seen.add(line));
                assertTrue("repetida salvo mayusculas: " + line, lower.add(line.toLowerCase(Locale.ROOT)));
            }
        } finally {
            r.close();
        }
        assertTrue("solo " + count + " entradas", count >= 450);
    }
}
