package com.aurebesh.teclado;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Lo que encontro la revision independiente de la 2.8 en el flujo de texto, cada caso con su secuencia: chips con el
 * cursor dentro de una palabra, rechazos de correccion, erratas con mayuscula, la letra pegada al punto, nombres propios
 * y gemelas con tilde.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class RevisionServicioTest extends ArnesServicio {

    /** Tocar dentro de una palabra y volver al final: los chips siguen respondiendo. */
    @Test
    public void elChipRespondeAlVolverAlFinal() throws Exception {
        newField(CHAT, 0, "");
        more("hola esta");
        moveTo(7);
        moveTo(9);
        assertEquals("est\u00e1", chips().get(0));
        assertEquals("hola est\u00e1 ", chip("est\u00e1"));

        // Tras pasar por dentro de una palabra, una errata tecleada despues se corrige y su chip original la recupera
        newField(CHAT, 0, "hola esta noche y nesec");
        moveTo(7);
        moveTo(text.length());
        assertEquals("hola esta noche y necesito ", more("ito "));
        assertEquals("hola esta noche y nesecito ", chip("\"nesecito\""));
    }

    /** Dentro de A, al final de B y otra vez dentro de A: la cinta ensena los chips de A, no los de B. */
    @Test
    public void losChipsSonLosDeLaPalabraDelCursor() throws Exception {
        newField(CHAT, 0, "hola esta noche y nesecit");
        moveTo(7);
        moveTo(text.length());
        assertTrue("al final, los de la errata: " + chips(), chips().contains("necesito") || chips().contains("necesita"));
        moveTo(7);
        assertEquals("est\u00e1", chips().get(0));
        assertFalse("nada de la otra palabra: " + chips(), chips().toString().contains("neces"));
        assertEquals("un chip que no es de esta palabra no la toca", "hola esta noche y nesecit", chip("necesito"));
        moveTo(8);
        assertEquals("hola est\u00e1 noche y nesecit", chip("est\u00e1"));
    }

    /** Un solo borrar tras la correccion (para cambiar el espacio por una coma) no deja la errata aprendida ni sin corregir. */
    @Test
    public void unBorrarNoEnsenaLaErrata() throws Exception {
        newField(CHAT, EditorInfo.IME_ACTION_SEND, "");
        assertEquals("yo tambi\u00e9n ", more("yo tambien "));
        delete();
        delete();
        more(", claro");
        enter();
        for (int i = 2; i <= 3; i++) {
            newField(CHAT, EditorInfo.IME_ACTION_SEND, "");
            assertEquals("yo tambi\u00e9n voy ", more("yo tambien voy "));
            enter();
        }
        assertFalse("aprendida sola: " + Prefs.learnedWords(app), Prefs.learnedWords(app).contains("tambien"));

        newField(CHAT, EditorInfo.IME_ACTION_SEND, "");
        more("eso qeu ");
        delete();                                   // retroceso por accidente
        more("dices ");
        enter();
        newField(CHAT, EditorInfo.IME_ACTION_SEND, "");
        assertEquals("lo que sea ", more("lo qeu sea "));
        assertEquals("[]", Prefs.learnedWords(app).toString());
    }

    /** Mayuscula puesta a mano tras un emoji, dos puntos o un guion de lista: es un inicio de frase y la errata se corrige. */
    @Test
    public void laErrataConMayusculaTrasUnEmojiSeCorrige() throws Exception {
        for (int i = 1; i <= 3; i++) {
            newField(CHAT, EditorInfo.IME_ACTION_SEND, "");
            more("ya llegue ");
            ime.onEmoji("\ud83d\ude0a");
            settle();
            String out = more(" Nesecito que abras ");
            assertTrue(out, out.endsWith(" Necesito que abras "));
            enter();
        }
        assertEquals("[]", Prefs.learnedWords(app).toString());
        assertEquals("pendientes:\n- Necesito ", typed("pendientes:\n- Nesecito "));
        assertEquals("hola Juan: Necesito ", typed("hola Juan: Nesecito "));
        // Y un nombre de verdad a mitad de frase se respeta, sin aprenderse solo aunque se repita
        for (int i = 0; i < 4; i++) assertEquals("ya voy con Xola ", typed("ya voy con Xola "));
        assertEquals("[]", Prefs.learnedWords(app).toString());
    }

    /** Letra pulsada por error tras el punto y borrada despues: la correccion vuelve. */
    @Test
    public void laLetraPorErrorTrasElPuntoNoPierdeLaCorreccion() throws Exception {
        newField(CHAT, 0, "");
        assertEquals("yo necesito.", more("yo nesecito."));
        assertEquals("yo nesecito.n", more("n"));
        assertEquals("yo necesito.", delete());
        assertEquals("yo necesito. ", more(" "));

        newField(CHAT, 0, "");
        more("yo tambien.b");
        delete();
        assertEquals("yo tambi\u00e9n. pero no ", more(" pero no "));

        // Si la direccion sigue, no se repone nada; y borrar despues va caracter a caracter
        newField(CHAT, 0, "");
        assertEquals("entra a facebook.co", more("entra a facebook.co"));
        assertEquals("entra a facebook.c", delete());
        assertEquals("entra a facebook.", delete());
        assertEquals("entra a facebook", delete());
    }

    /** Arreglar la palabra rechazada con el chip de palabra entera (cursor dentro) le quita el rechazo. */
    @Test
    public void arreglarDesdeDentroQuitaElRechazo() throws Exception {
        newField(CHAT, 0, "");
        more("eso qeu ");
        delete();
        more("dices ");
        moveTo(5);
        assertEquals("eso que dices ", chip("que"));
        moveTo(text.length());
        assertEquals("eso que dices y que ", more("y qeu "));
    }

    /** Ningun chip repite lo que ya esta escrito. */
    @Test
    public void ningunChipEsLaPropiaPalabra() throws Exception {
        for (String w : new String[]{"Jos\u00e9", "M\u00e9xico", "Sof\u00eda", "Jes\u00fas", "est\u00e1", "hola"}) {
            newField(CHAT, 0, "voy con " + w);
            assertFalse(w + ": " + chips(), chips().contains(w));
            moveTo(text.length() - 2);
            assertFalse("dentro de " + w + ": " + chips(), chips().contains(w));
        }
    }

    /** Intro con accion en un campo que no pide mayusculas no cambia la caja de un nombre; la tilde si la pone. */
    @Test
    public void laAccionNoCambiaLaCajaEnCamposSinMayusculas() throws Exception {
        newField(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT, "");
        more("juan");
        assertEquals("juan", enter());
        newField(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT, "");
        more("cancion");
        assertEquals("canci\u00f3n", enter());
        newField(NORMAL, EditorInfo.IME_ACTION_SEND, "");
        more("ya vi a juan");
        assertEquals("en un campo de frases, si", "ya vi a Juan", enter());
    }

    /** El rechazo pendiente no se pierde por poner un emoji detras, ni por pegar. */
    @Test
    public void elRechazoSobreviveAUnEmoji() throws Exception {
        newField(CHAT, 0, "");
        assertEquals("con sola ", more("con xola "));
        assertEquals("con xola ", delete());
        ime.onEmoji("\ud83d\ude00");
        settle();
        String out = more(" y xola ");
        assertTrue(out, out.endsWith(" y xola "));
    }

    /** Deslizar un nombre de caja propia tras un punto no le sube la inicial. */
    @Test
    public void deslizarIphoneTrasUnPunto() throws Exception {
        newField(CHAT, 0, "");
        glide("hola", "hola");
        more(".");
        assertEquals("hola. iPhone ", glide("iphone", "iphone"));
        newField(CHAT, 0, "");
        glide("hola", "hola");
        more(".");
        assertEquals("una palabra corriente si la lleva", "hola. Como ", glide("como", "como"));
    }

    /** Pares con guion: la caja de nombres no toca solo una mitad. */
    @Test
    public void losParesConGuionNoQuedanAMedias() throws Exception {
        assertEquals("el mexico-canada ", typed("el mexico-canada "));
        assertEquals("juan-carlos ", typed("juan-carlos "));
        assertEquals("vuelo cancun-miami ", typed("vuelo cancun-miami "));
        assertEquals("sin guion, los dos", "Juan y Carlos ", typed("juan y carlos "));
    }

    /** Un nombre con mayuscula a proposito no se cambia por su variante de la lista ni por una entrada del lexico. */
    @Test
    public void elNombreAPropositoNoSeCambiaPorOtroNombre() throws Exception {
        assertEquals("vi a Melisa ", typed("vi a Melisa "));
        assertEquals("vi a Jonatan ", typed("vi a Jonatan "));
        typed("vi a Melisa");
        assertTrue("la variante, a un toque: " + chips(), chips().contains("Melissa"));
        assertNull(highlighted());
        // Nombres mexicanos que el lexico Star Wars (encendido de fabrica) se llevaba por delante
        assertEquals("voy a Obreg\u00f3n ", typed("voy a Obreg\u00f3n "));
        assertEquals("voy a Obreg\u00f3n ", typed("voy a obregon "));
        assertEquals("calle Z\u00e1rate ", typed("calle Z\u00e1rate "));
        assertEquals("voy a Bacalar ", typed("voy a bacalar "));
        assertEquals("vi a Brayan ", typed("vi a Brayan "));
        assertEquals("con mayuscula a proposito, ni el lexico", "en Curuscant ", typed("en Curuscant "));
        assertEquals("en minusculas el lexico sigue corrigiendo", "en Coruscant ", typed("en curuscant "));
    }

    /** "Volver a mostrar todas las letras" desde la app llega al teclado vivo aunque tenga pulsaciones sin guardar. */
    @Test
    @SuppressWarnings("unchecked")
    public void elReinicioDeLasLetrasLlegaAlTecladoVivo() throws Exception {
        Prefs.get(app).edit().putString(Prefs.HINT_MODE, "learning").putString(Prefs.HINT_COUNTS, "a 40\ne 12").apply();
        settle();
        newField();
        java.util.Map<String, Integer> counts = (java.util.Map<String, Integer>) field(view(), AurebeshKeyboardView.class, "hintCounts");
        assertEquals(Integer.valueOf(40), counts.get("a"));
        counts.put("e", 30);                                     // pulsaciones que el teclado aun no ha guardado
        setField(view(), AurebeshKeyboardView.class, "hintDirty", true);
        Prefs.get(app).edit().remove(Prefs.HINT_COUNTS).apply();   // el boton de la app
        settle();
        assertTrue("el teclado vivo suelta sus contadores: " + counts, counts.isEmpty());
        newField();                                              // al abrir el campo siguiente no los vuelve a guardar
        assertNull(Prefs.get(app).getString(Prefs.HINT_COUNTS, null));
        assertTrue(((java.util.Map<String, Integer>) field(view(), AurebeshKeyboardView.class, "hintCounts")).isEmpty());
    }

    /** La gemela con tilde no ofrece la falta que el propio corrector deshace, ni formas raras o de otro pais. */
    @Test
    public void lasGemelasSoloOfrecenLecturasLegitimas() throws Exception {
        Dictionary es = Dictionary.get(app, "es");
        for (String w : new String[]{"l\u00ednea", "m\u00edo", "n\u00famero", "c\u00e1llate", "ten\u00edas", "ojal\u00e1", "p\u00e1gina"}) {
            assertNull(w, es.twin(w));
        }
        for (String w : "video plato conde chloe futbol sello castigo".split(" ")) assertNull(w, es.twin(w));
        assertEquals(Arrays.asList("qu\u00e9", "\u00e9l", "s\u00e9", "m\u00ed", "s\u00ed", "c\u00f3mo", "t\u00fa", "est\u00e1", "esta", "si", "que"),
                Arrays.asList(es.twin("que"), es.twin("el"), es.twin("se"), es.twin("mi"), es.twin("si"), es.twin("como"),
                        es.twin("tu"), es.twin("esta"), es.twin("est\u00e1"), es.twin("s\u00ed"), es.twin("qu\u00e9")));
        assertEquals("habl\u00f3", es.twin("hablo"));
        assertEquals("lleg\u00f3", es.twin("llego"));
        assertEquals("cambi\u00f3", es.twin("cambio"));
        newField(CHAT, 0, "");
        more("la l\u00ednea");
        assertFalse("chips: " + chips(), chips().contains("linea"));
        newField(CHAT, 0, "");
        more("un video");
        assertFalse("chips: " + chips(), chips().contains("v\u00eddeo"));
    }

    /** Los nombres que el teclado habia aprendido solo en versiones anteriores salen del diccionario personal al actualizar. */
    @Test
    public void laMigracionDevuelveSuMayusculaALosNombresAprendidos() throws Exception {
        Prefs.get(app).edit().putString(Prefs.LEARNED, "guadalajara\nplaticamos\ntiktok\nlalo\nmexico\nagendar").apply();
        Prefs.ui(app).edit().putInt("migrated", 2).apply();
        Prefs.migrate(app);
        assertEquals(new java.util.HashSet<>(Arrays.asList("platicamos", "agendar")), Prefs.learnedWords(app));
        settle();
        assertEquals("voy a Guadalajara con Lalo ", typed("voy a guadalajara con lalo "));
        // Solo una vez: lo que se guarde despues (rechazo repetido de la mayuscula) se queda
        Prefs.addLearnedWord(app, "tiktok");
        Prefs.migrate(app);
        assertTrue(Prefs.learnedWords(app).contains("tiktok"));
        settle();
        assertEquals("en tiktok ", typed("en tiktok "));
    }
}
