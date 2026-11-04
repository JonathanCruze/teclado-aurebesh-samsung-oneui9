package com.aurebesh.teclado;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Prediccion de la siguiente palabra y contexto para el autocorrector ({@link Bigrams}): las tablas integradas
 * de espanol, ingles y aleman proponen seguidoras sensatas, las parejas aprendidas del usuario mandan sobre la
 * tabla y se guardan en las preferencias privadas, y la bonificacion de contexto distingue una pareja habitual
 * de una desconocida.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class PredictionTest {

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Bigrams.forget(app);
    }

    @Test
    public void spanishFollowers() {
        Bigrams es = Bigrams.get(app, "es");
        assertTrue("tabla es cargada", es.size() > 1000);
        List<String> next = es.next("muchas", 15);
        assertTrue("muchas -> gracias: " + next, next.contains("gracias"));
        assertTrue(next.size() <= 15);
        // mayusculas y espacios no importan
        assertEquals(next, es.next(" Muchas ", 15));
    }

    @Test
    public void englishFollowers() {
        Bigrams en = Bigrams.get(app, "en");
        List<String> next = en.next("thank", 5);
        assertTrue("thank -> you: " + next, next.contains("you"));
        assertEquals("you", next.get(0));
    }

    @Test
    public void germanFollowers() {
        Bigrams de = Bigrams.get(app, "de");
        assertTrue(de.size() > 1000);
        assertTrue(de.next("vielen", 5).contains("dank"));
    }

    @Test
    public void sentenceStart() {
        Bigrams es = Bigrams.get(app, "es");
        assertFalse(es.next("<s>", 5).isEmpty());
        // null y vacio equivalen al inicio de frase
        assertEquals(es.next("<s>", 5), es.next(null, 5));
        assertEquals(es.next("<s>", 5), es.next("", 5));
        assertTrue(es.next("palabrainexistentexyz", 5).isEmpty());
        assertTrue(es.next("muchas", 0).isEmpty());
    }

    @Test
    public void learnedPairsComeFirst() {
        Bigrams es = Bigrams.get(app, "es");
        List<String> before = es.next("muchas", 5);
        assertFalse(before.contains("naves"));
        es.learn(app, "muchas", "naves");
        List<String> after = es.next("muchas", 5);
        assertEquals("naves", after.get(0));
        assertTrue("las de la tabla siguen detras", after.contains("gracias"));
        // la pareja aprendida bonifica mas que cualquiera de la tabla y queda en las preferencias privadas
        assertTrue(es.bonus("muchas", "naves") >= es.bonus("muchas", "gracias"));
        Bigrams.flush(app);
        String stored = Bigrams.store(app).getString(Bigrams.PREF_KEY, "");
        assertTrue(stored, stored.contains("muchas naves 1"));
        es.learn(app, "muchas", "naves");
        Bigrams.flush(app);
        assertTrue(Bigrams.store(app).getString(Bigrams.PREF_KEY, "").contains("muchas naves 2"));
        // la misma pareja la ve la tabla de otro idioma (las aprendidas son del usuario, no del idioma)
        assertEquals("naves", Bigrams.get(app, "en").next("muchas", 1).get(0));

        Bigrams.forget(app);
        assertFalse(es.next("muchas", 5).contains("naves"));
        assertEquals("", Prefs.get(app).getString(Bigrams.PREF_KEY, ""));
    }

    @Test
    public void learnedPairsAreCapped() {
        Bigrams es = Bigrams.get(app, "es");
        es.learn(app, "zzprev", "frecuente");
        es.learn(app, "zzprev", "frecuente");
        for (int i = 0; i < Bigrams.MAX_USER_PAIRS + 50; i++) {
            es.learn(app, "p" + i, "w" + i);
        }
        assertEquals(Bigrams.MAX_USER_PAIRS, Bigrams.userPairCount(app));
        // la pareja repetida sobrevive; las mas antiguas vistas una sola vez se descartan
        assertTrue(es.next("zzprev", 1).contains("frecuente"));
        assertTrue(es.next("p0", 1).isEmpty());
        assertFalse(es.next("p" + (Bigrams.MAX_USER_PAIRS + 49), 1).isEmpty());
    }

    @Test
    public void contextBonus() {
        Bigrams es = Bigrams.get(app, "es");
        double known = es.bonus("muchas", "gracias");
        double unknown = es.bonus("muchas", "gracia");
        assertTrue(known + " > " + unknown, known > unknown);
        assertEquals(0.0, unknown, 0.0);
        assertTrue(known > 0 && known <= Bigrams.MAX_BONUS);
        // la seguidora mas frecuente de "de" roza el maximo; cualquier pareja conocida vale algo
        double top = es.bonus("de", "la");
        assertTrue(String.valueOf(top), top > 1.5 && top <= Bigrams.MAX_BONUS);
        assertTrue(es.bonus("de", "forma") > 0);
        assertTrue(es.bonus("de", "la") > es.bonus("de", "forma"));
        assertEquals(0.0, es.bonus("muchas", null), 0.0);
        assertEquals(0.0, es.bonus(null, "xyzzyinexistente"), 0.0);
        assertTrue(es.bonus(null, "el") > 0);
    }
}
