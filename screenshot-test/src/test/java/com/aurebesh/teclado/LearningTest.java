package com.aurebesh.teclado;

import android.app.Application;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Politica del diccionario personal ({@link Learning}): una palabra desconocida entra solo tras {@link Prefs#learnUses}
 * usos en mensajes distintos (un uso como mucho por sesion de campo o por envio), nunca si lleva cifras, es una sigla,
 * va con mayusculas intercaladas o parece una errata de una palabra frecuente (salvo que el usuario rechace la
 * correccion a proposito); los candidatos viven en su archivo propio y se olvidan a los 30 dias sin verse.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class LearningTest {

    private static final long T0 = 1_700_000_000_000L;          // un instante cualquiera (ms de pared)
    private static final long DAY = 24L * 60 * 60 * 1000;

    private Application app;
    private Dictionary es;
    private boolean starWarsBefore;

    @Before
    public void setUp() throws IOException {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Learning.clear(app);
        es = Dictionary.get(app, "es");
        es.setPersonal(null);
        starWarsBefore = es.starWars();
    }

    @After
    public void tearDown() {
        es.setPersonal(null);                          // el diccionario se comparte entre pruebas
        es.setStarWars(starWarsBefore);
        Learning.clear(app);
    }

    /** Una instancia nueva con el reloj fijado (como el servicio al arrancar). */
    private Learning fresh(long now) {
        Learning l = new Learning(app);
        l.setClock(now);
        return l;
    }

    /** Una sesion de campo completa en la que la palabra se cierra una vez; devuelve si con ella se aprende. */
    private boolean session(Learning l, String word, int uses) {
        l.beginSession();
        boolean learned = l.note(es, word, uses, false);
        l.endSession();
        return learned;
    }

    /** Palabra inventada numero i, solo letras y lejos de cualquier palabra del idioma ("zzqaa", "zzqab"...). */
    private static String invented(int i) {
        return "zzq" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
    }

    // ------------------------------------------------------------------ usos en mensajes distintos

    @Test
    public void newWordTypedOnceIsNotLearned() {
        Learning l = fresh(T0);
        assertFalse(session(l, "xochitl", Prefs.learnUses(app)));   // 3 por defecto
        assertEquals(1, l.uses("xochitl"));
        assertFalse(es.isPersonal("xochitl"));
    }

    @Test
    public void learnedAfterThreeDistinctSessionsByDefault() {
        assertEquals(3, Prefs.learnUses(app));
        Learning l = fresh(T0);
        assertFalse(session(l, "Xochitl", 3));           // con mayuscula inicial: cuenta igual, en minusculas
        assertFalse(session(l, "xochitl", 3));
        assertEquals(2, l.uses("xochitl"));
        assertTrue(session(l, "xochitl", 3));            // tercer mensaje: entra y deja de ser candidata
        assertEquals(0, l.uses("xochitl"));
    }

    @Test
    public void learnUsesPreferenceChangesTheThreshold() {
        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 2).apply();
        assertEquals(2, Prefs.learnUses(app));
        Learning l = fresh(T0);
        assertFalse(session(l, "aurebesh", Prefs.learnUses(app)));
        assertTrue(session(l, "aurebesh", Prefs.learnUses(app)));

        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 5).apply();
        assertEquals(5, Prefs.learnUses(app));
        Learning five = fresh(T0);
        for (int i = 0; i < 4; i++) assertFalse("uso " + (i + 1), session(five, "xochitl", Prefs.learnUses(app)));
        assertTrue(session(five, "xochitl", Prefs.learnUses(app)));

        Prefs.get(app).edit().putInt(Prefs.LEARN_USES, 4).apply();   // valor fuera del contrato: vuelve al 3
        assertEquals(3, Prefs.learnUses(app));
    }

    @Test
    public void twoUsesInTheSameSessionCountOnce() {
        Learning l = fresh(T0);
        l.beginSession();
        assertFalse(l.note(es, "xochitl", 2, false));
        assertFalse(l.note(es, "xochitl", 2, false));     // misma sesion, misma palabra: no suma
        assertFalse(l.note(es, "Xochitl", 2, false));
        assertEquals(1, l.uses("xochitl"));
        l.newMessage();                                   // intro o accion: otro mensaje en el mismo campo
        assertTrue(l.note(es, "xochitl", 2, false));
        l.endSession();
        assertEquals(0, l.uses("xochitl"));
    }

    @Test
    public void sendingAMessageOpensANewUseWithinTheSameField() {
        Learning l = fresh(T0);
        l.beginSession();
        assertFalse(l.note(es, "aurebesh", 3, false));
        l.newMessage();
        assertFalse(l.note(es, "aurebesh", 3, false));
        l.newMessage();
        assertTrue(l.note(es, "aurebesh", 3, false));
        l.endSession();
    }

    // ------------------------------------------------------------------ erratas de palabras frecuentes

    @Test
    public void typoOfFrequentWordIsNotLearnedByRepetition() {
        assertTrue(Learning.looksLikeTypo(es, "holq"));           // "hola" con una tecla cambiada
        assertTrue(Learning.looksLikeTypo(es, "hoal"));           // dos letras invertidas
        assertTrue(Learning.looksLikeTypo(es, "holaa"));          // una letra de mas
        assertTrue(Learning.looksLikeTypo(es, "cancion"));        // la misma sin tilde
        assertTrue(Learning.looksLikeTypo(es, "hóla"));           // con una tilde de mas
        assertFalse(Learning.looksLikeTypo(es, "xochitl"));
        assertFalse(Learning.looksLikeTypo(es, "aurebesh"));
        Learning l = fresh(T0);
        for (int i = 0; i < 5; i++) assertFalse(session(l, "holq", 2));
        assertEquals(0, l.uses("holq"));                          // ni siquiera se anota
        assertEquals(0, l.pendingCount());
    }

    @Test
    public void rejectingTheCorrectionCountsEvenForATypo() {
        // deshacer la autocorreccion (retroceso) cuenta como uso aunque parezca errata, con el mismo tope por mensaje
        Learning l = fresh(T0);
        l.beginSession();
        assertFalse(l.note(es, "holq", 2, true));
        assertFalse(l.note(es, "holq", 2, true));
        assertEquals(1, l.uses("holq"));
        l.endSession();
        l.beginSession();
        assertTrue(l.note(es, "holq", 2, true));
        l.endSession();
        // y el chip de la palabra original la aprende al instante: es admisible aunque parezca errata
        assertTrue(Learning.eligible(es, "holq"));
    }

    // ------------------------------------------------------------------ requisitos de forma

    @Test
    public void digitsAcronymsAndSymbolsNeverQualify() {
        assertFalse(Learning.wellFormed("abc1"));
        assertFalse(Learning.wellFormed("3er"));
        assertFalse(Learning.wellFormed("NASA"));                 // sigla
        assertFalse(Learning.wellFormed("iOS"));
        assertFalse(Learning.wellFormed("iPhone"));               // mayusculas intercaladas
        assertFalse(Learning.wellFormed("McFly"));
        assertFalse(Learning.wellFormed("ab"));                   // menos de tres letras
        assertFalse(Learning.wellFormed("a'b"));                  // apostrofo interior pero solo dos letras
        assertFalse(Learning.wellFormed("'hola"));                // apostrofo en los bordes
        assertFalse(Learning.wellFormed("hola'"));
        assertFalse(Learning.wellFormed("ho''la"));
        assertFalse(Learning.wellFormed("x@y.com"));
        assertFalse(Learning.wellFormed("www"));                  // tres letras iguales seguidas
        assertFalse(Learning.wellFormed("holaaa"));
        assertFalse(Learning.wellFormed("hola mundo"));
        assertFalse(Learning.wellFormed(null));
        assertFalse(Learning.wellFormed("abcdefghijklmnopqrstuvwxyzabcde"));   // mas de 30
        assertTrue(Learning.wellFormed("Xochitl"));
        assertTrue(Learning.wellFormed("señorón"));
        assertTrue(Learning.wellFormed("groß"));
        assertTrue(Learning.wellFormed("über"));
        assertTrue(Learning.wellFormed("o'neil"));
        assertTrue(Learning.wellFormed("rock'n'roll"));
        // las que no pasan la forma no entran aunque se repitan
        Learning l = fresh(T0);
        for (int i = 0; i < 4; i++) {
            assertFalse(session(l, "NASA", 2));
            assertFalse(session(l, "abc1", 2));
        }
        assertEquals(0, l.pendingCount());
    }

    @Test
    public void knownWordsAndStarWarsLexiconAreNotCandidates() {
        assertFalse(Learning.eligible(es, "hola"));               // del idioma
        es.setPersonal(java.util.Arrays.asList("aurebesh"));
        assertFalse(Learning.eligible(es, "aurebesh"));           // ya aprendida
        es.setPersonal(null);
        es.setStarWars(false);
        assertFalse(Learning.eligible(es, "Coruscant"));          // lexico, aunque este apagado
        assertFalse(Learning.eligible(es, "coruscant"));
        es.setStarWars(true);
        assertFalse(Learning.eligible(es, "coruscant"));
        assertFalse(Learning.eligible(null, "xochitl"));          // sin diccionario no se decide
        assertTrue(Learning.eligible(es, "xochitl"));
        Learning l = fresh(T0);
        assertFalse(session(l, "hola", 1));
        assertEquals(0, l.pendingCount());
    }

    // ------------------------------------------------------------------ archivo propio y purga

    @Test
    public void candidatesLiveInTheirOwnFileAndSurviveReload() {
        Learning l = fresh(T0);
        assertFalse(session(l, "xochitl", 3));
        File f = new File(app.getFilesDir(), Learning.FILE);
        assertTrue("archivo propio tras cerrar la sesion", f.exists());
        assertTrue("no toca las preferencias", Prefs.get(app).getString(Prefs.CANDIDATES, "").isEmpty());
        assertTrue(Prefs.learnedWords(app).isEmpty());

        Learning again = fresh(T0 + DAY);                          // otro arranque del servicio
        assertEquals(1, again.uses("xochitl"));
        assertFalse(session(again, "xochitl", 3));
        assertEquals(2, again.uses("xochitl"));

        Learning.clear(app);
        assertFalse(f.exists());
        assertEquals(0, fresh(T0 + DAY).uses("xochitl"));
    }

    @Test
    public void candidatesUnseenForThirtyDaysAreForgotten() {
        Learning l = fresh(T0);
        assertFalse(session(l, "xochitl", 3));                    // vista el dia 0
        l.setClock(T0 + 20 * DAY);
        assertFalse(session(l, "aurebesh", 3));                   // vista el dia 20
        assertEquals(1, l.uses("xochitl"));

        Learning later = fresh(T0 + 31 * DAY);                    // 31 dias despues
        later.beginSession();
        assertEquals("sin verse en 30 dias se olvida", 0, later.uses("xochitl"));
        assertEquals("la vista hace 11 dias sigue", 1, later.uses("aurebesh"));
        assertEquals(1, later.pendingCount());
        later.endSession();

        // y la cuenta vuelve a empezar desde cero para la olvidada
        assertFalse(session(later, "xochitl", 3));
        assertEquals(1, later.uses("xochitl"));
    }

    @Test
    public void onlyTheMostRecentCandidatesAreKept() {
        Learning l = fresh(T0);
        int extra = 5;
        for (int i = 0; i < Learning.MAX_PENDING + extra; i++) {
            l.setClock(T0 + i);
            l.beginSession();
            assertFalse(l.note(es, invented(i), 5, false));
            l.endSession();
        }
        assertEquals(Learning.MAX_PENDING, l.pendingCount());
        assertEquals(0, l.uses(invented(0)));                                   // las primeras se cayeron
        assertEquals(0, l.uses(invented(extra - 1)));
        assertEquals(1, l.uses(invented(extra)));                               // de ahi en adelante siguen
        assertEquals(1, l.uses(invented(Learning.MAX_PENDING + extra - 1)));    // la ultima tambien
    }
}
