package com.aurebesh.teclado;

import android.app.Application;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Escritura inteligente que aprende del usuario, lado de datos: el autocorrector tambien corrige hacia las
 * palabras aprendidas ({@link Dictionary#setPersonal}) sin que una palabra frecuente deje de ganar; las parejas
 * de palabras aprendidas ({@link Bigrams}) viven en su propio archivo de preferencias, se migran una vez desde
 * el principal y se escriben de forma diferida ({@link Bigrams#flush}); y las palabras conversacionales de
 * emoji_extra.txt (hola, jajaja, hallo...) tienen emoji por delante de las palabras clave de Unicode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class SmartTypingTest {

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Bigrams.unload();
        Bigrams.forget(app);
        Prefs.get(app).edit().clear().apply();
        Bigrams.store(app).edit().clear().apply();
    }

    @After
    public void tearDown() {
        Dictionary es = Dictionary.cached("es");
        if (es != null) es.setPersonal(null);          // el diccionario se comparte entre pruebas
        Bigrams.flush(app);
    }

    private static String emoji(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    // ------------------------------------------------------------------ diccionario personal

    @Test
    public void correctsTowardsPersonalWord() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        es.setPersonal(null);
        Dictionary.Result before = es.suggest("aurebsh");
        assertFalse(before.known);
        assertFalse("sin aprenderla no se propone: " + before.correction, "aurebesh".equals(before.correction));

        es.setPersonal(Arrays.asList("aurebesh"));
        Dictionary.Result r = es.suggest("aurebsh");            // una letra de menos
        assertFalse(r.known);
        assertEquals("aurebesh", r.correction);
        assertEquals("aurebesh", r.suggestions.get(0));
        assertEquals("aurebesh", es.suggest("aurebhs").correction);   // dos letras invertidas
        assertEquals("aurebesh", es.suggest("aurwbesh").correction);  // tecla vecina
        assertEquals("aurebesh", es.suggest("aurbsh").correction);    // dos ediciones (busqueda larga)
        // la palabra aprendida escrita tal cual es conocida y no se toca
        Dictionary.Result known = es.suggest("aurebesh");
        assertTrue(known.known);
        assertNull(known.correction);
        // completado con un error en el prefijo
        assertTrue(es.suggest("aurebs").suggestions.toString(), es.suggest("aurebs").suggestions.contains("aurebesh"));
        // y el completado directo sigue como antes
        assertTrue(es.suggest("aure").suggestions.contains("aurebesh"));
    }

    @Test
    public void accentedFormOfLearnedPlainWordIsKept() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        es.setPersonal(Arrays.asList("chevere"));             // aprendida sin tilde
        Dictionary.Result r = es.suggest("ch\u00e9vere");       // escrita con tilde: se respeta, no se le quita
        assertTrue("la forma con tilde se considera conocida", r.known);
        assertNull("sin correccion: " + r.correction, r.correction);
        es.setPersonal(null);
    }

    @Test
    public void frequentWordStillBeatsPersonalWord() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        es.setPersonal(Arrays.asList("holqs"));                // aprendida a una edicion de lo escrito
        Dictionary.Result r = es.suggest("holq");
        assertEquals("hola", r.correction);                     // la frecuente gana (puntuacion media de la personal)
        assertTrue(r.suggestions.toString(), r.suggestions.contains("holqs"));
        // lo que ya cubre la prueba de capturas no cambia
        assertEquals("hola", es.suggest("holla").correction);
        assertEquals("canción", es.suggest("cancion").correction);
        assertNull(es.suggest("hola").correction);
    }

    @Test
    public void personalWordWithAccentIsRestored() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        es.setPersonal(Arrays.asList("zylandé"));
        Dictionary.Result r = es.suggest("zylande");
        assertFalse(r.known);
        assertEquals("zylandé", r.correction);
    }

    @Test
    public void isPersonalFollowsSetPersonal() throws IOException {
        Dictionary es = Dictionary.get(app, "es");
        assertFalse(es.isPersonal("aurebesh"));
        es.setPersonal(Collections.singleton("aurebesh"));
        assertTrue(es.isPersonal("aurebesh"));
        assertTrue(es.isPersonal("Aurebesh"));                  // se compara en minusculas
        assertTrue(es.contains("aurebesh"));
        assertFalse(es.isPersonal("hola"));                     // del diccionario, no aprendida
        assertFalse(es.isPersonal(null));
        es.setPersonal(null);
        assertFalse(es.isPersonal("aurebesh"));
        assertFalse(es.contains("aurebesh"));
    }

    // ------------------------------------------------------------------ parejas aprendidas

    @Test
    public void learnedPairsMigrateToOwnFile() {
        Prefs.get(app).edit().putString(Bigrams.PREF_KEY, "muchas naves 3").apply();   // como las guardaba 1.9
        Bigrams.unload();
        Bigrams es = Bigrams.get(app, "es");
        assertEquals("naves", es.next("muchas", 1).get(0));
        assertEquals(1, Bigrams.userPairCount(app));
        assertFalse("la clave antigua se borra", Prefs.get(app).contains(Bigrams.PREF_KEY));
        String stored = Bigrams.store(app).getString(Bigrams.PREF_KEY, "");
        assertTrue(stored, stored.contains("muchas naves 3"));
        // la migracion no se repite: lo que haya en el archivo antiguo despues ya no cuenta
        Bigrams.unload();
        Prefs.get(app).edit().putString(Bigrams.PREF_KEY, "zzvieja zzpareja 9").apply();
        assertEquals(1, Bigrams.userPairCount(app));
        assertTrue(Bigrams.get(app, "es").next("zzvieja", 1).isEmpty());
        assertTrue("sigue en el archivo antiguo sin tocar", Prefs.get(app).contains(Bigrams.PREF_KEY));
        Prefs.get(app).edit().remove(Bigrams.PREF_KEY).apply();
    }

    @Test
    public void learnedPairsAreWrittenDeferredAndFlushed() {
        Bigrams es = Bigrams.get(app, "es");
        es.learn(app, "muchas", "naves");
        es.learn(app, "muchas", "naves");
        Bigrams.flush(app);
        String stored = Bigrams.store(app).getString(Bigrams.PREF_KEY, "");
        assertTrue(stored, stored.contains("muchas naves 2"));
        assertFalse("nada en el archivo principal", Prefs.get(app).contains(Bigrams.PREF_KEY));
        assertEquals("naves", es.next("muchas", 1).get(0));
        // justo despues de un volcado la escritura espera: el archivo no cambia hasta el siguiente flush
        es.learn(app, "muchas", "naves");
        assertTrue(Bigrams.store(app).getString(Bigrams.PREF_KEY, "").contains("muchas naves 2"));
        assertEquals("naves", Bigrams.get(app, "en").next("muchas", 1).get(0));   // en memoria ya cuenta
        Bigrams.flush(app);
        assertTrue(Bigrams.store(app).getString(Bigrams.PREF_KEY, "").contains("muchas naves 3"));
        // y si nadie llama a flush, el volcado programado llega solo pasado el intervalo
        es.learn(app, "muchas", "naves");
        assertTrue(Bigrams.store(app).getString(Bigrams.PREF_KEY, "").contains("muchas naves 3"));
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(Bigrams.SAVE_INTERVAL_MS + 100));
        assertTrue(Bigrams.store(app).getString(Bigrams.PREF_KEY, "").contains("muchas naves 4"));
        // olvidar limpia el archivo propio
        Bigrams.forget(app);
        assertEquals("", Bigrams.store(app).getString(Bigrams.PREF_KEY, ""));
        assertTrue(es.next("muchas", 5).isEmpty() || !es.next("muchas", 5).get(0).equals("naves"));
    }

    @Test
    public void learnedPairsSurviveReload() {
        Bigrams es = Bigrams.get(app, "es");
        es.learn(app, "zzprev", "zzword");
        Bigrams.flush(app);
        Bigrams.unload();
        assertEquals("zzword", Bigrams.get(app, "es").next("zzprev", 1).get(0));
    }

    // ------------------------------------------------------------------ emojis conversacionales

    @Test
    public void conversationalWordsHaveEmoji() {
        String wave = emoji(0x1F44B), joy = emoji(0x1F602), thumbs = emoji(0x1F44D);
        assertEquals(wave, EmojiSuggest.lookup(app, "hola", 2).get(0));
        assertEquals(wave, EmojiSuggest.lookup(app, "Hallo", 1).get(0));
        assertEquals(wave, EmojiSuggest.lookup(app, "adiós", 1).get(0));
        assertEquals(wave, EmojiSuggest.lookup(app, "adios", 1).get(0));
        assertEquals(joy, EmojiSuggest.lookup(app, "jajajaja", 1).get(0));
        assertEquals(joy, EmojiSuggest.lookup(app, "jaja", 1).get(0));
        assertEquals(joy, EmojiSuggest.lookup(app, "JAJAJAJ", 1).get(0));
        assertEquals(joy, EmojiSuggest.lookup(app, "jejeje", 1).get(0));
        assertEquals(joy, EmojiSuggest.lookup(app, "hahaha", 1).get(0));
        assertEquals(joy, EmojiSuggest.lookup(app, "lol", 1).get(0));
        assertEquals(thumbs, EmojiSuggest.lookup(app, "ok", 1).get(0));
        assertEquals(thumbs, EmojiSuggest.lookup(app, "okay", 1).get(0));
        assertEquals(emoji(0x1F4BC), EmojiSuggest.lookup(app, "work", 1).get(0));      // maletin, no gotas de sudor
        assertEquals(emoji(0x1F37A), EmojiSuggest.lookup(app, "bier", 1).get(0));
        assertEquals(emoji(0x1F319), EmojiSuggest.lookup(app, "noches", 1).get(0));
        assertTrue(EmojiSuggest.lookup(app, "hola", 4).size() >= 2);
        assertTrue(EmojiSuggest.lookup(app, "xyzzyq", 2).isEmpty());
        assertTrue(EmojiSuggest.lookup(app, "j", 2).isEmpty());
    }

    @Test
    public void unicodeKeywordsStayBehindOwnEmoji() {
        List<String> dog = EmojiSuggest.lookup(app, "perro", 4);
        assertEquals(emoji(0x1F436), dog.get(0));                  // cara de perro primero
        assertTrue(dog.toString(), dog.contains(emoji(0x1F415)));  // y el perro de Unicode detras
        assertTrue(dog.size() <= 4);
        assertTrue(EmojiSuggest.lookup(app, "pizza", 2).contains(emoji(0x1F355)));
        assertFalse(EmojiSuggest.lookup(app, "corazon", 3).isEmpty());
    }

    @Test
    public void laughsCollapseToBaseForm() {
        assertEquals("jaja", EmojiSuggest.normalizeLaugh("jaja"));
        assertEquals("jaja", EmojiSuggest.normalizeLaugh("jajajaja"));
        assertEquals("jaja", EmojiSuggest.normalizeLaugh("jajajaj"));
        assertEquals("jeje", EmojiSuggest.normalizeLaugh("jejejeje"));
        assertEquals("haha", EmojiSuggest.normalizeLaugh("hahahaha"));
        assertEquals("hehe", EmojiSuggest.normalizeLaugh("hehehe"));
        assertEquals("ja", EmojiSuggest.normalizeLaugh("ja"));
        assertEquals("jaj", EmojiSuggest.normalizeLaugh("jaj"));
        assertEquals("jamon", EmojiSuggest.normalizeLaugh("jamon"));
        assertEquals("jajajax", EmojiSuggest.normalizeLaugh("jajajax"));
        assertEquals("hallo", EmojiSuggest.normalizeLaugh("hallo"));
        assertEquals("hehehen", EmojiSuggest.normalizeLaugh("hehehen"));
    }
}
