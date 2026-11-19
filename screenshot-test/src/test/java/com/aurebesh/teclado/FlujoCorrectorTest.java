package com.aurebesh.teclado;

import android.app.Application;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * El corrector de punta a punta: el servicio del teclado con un editor falso, tecleando letra a letra como en el
 * telefono (sugerencias, autocorreccion al pulsar espacio, contexto de la palabra anterior, palabras aprendidas, lexico
 * Star Wars). Cubre lo que las pruebas de Dictionary.suggest() no ven: lo que el servicio hace con su resultado.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class FlujoCorrectorTest {

    public static class TestIME extends AurebeshIME {
        InputConnection ic;
        EditorInfo info;
        @Override public InputConnection getCurrentInputConnection() { return ic; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return info; }
    }

    private final StringBuilder text = new StringBuilder();
    private int cursor = 0;
    private int lastReported = 0;
    private TestIME ime;
    private Application app;

    @Before
    public void setUp() throws Exception {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
        start();
    }

    /** Arranca el servicio del teclado (como al encender el telefono o actualizar la app) con un campo nuevo. */
    private void start() throws Exception {
        ime = Robolectric.buildService(TestIME.class).create().get();
        ime.ic = editor();
        ime.info = new EditorInfo();
        ime.info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        ime.onCreateInputView();
        newField();
    }

    /** Editor falso: un texto con cursor al final que atiende lo que el servicio le pide. */
    private InputConnection editor() {
        return (InputConnection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{InputConnection.class},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method m, Object[] a) {
                        String n = m.getName();
                        if (n.equals("getTextBeforeCursor")) return text.substring(Math.max(0, cursor - (Integer) a[0]), cursor);
                        if (n.equals("getTextAfterCursor")) return text.substring(cursor, Math.min(text.length(), cursor + (Integer) a[0]));
                        if (n.equals("getSelectedText")) return null;
                        if (n.equals("commitText")) {
                            String s = a[0].toString();
                            text.insert(cursor, s);
                            cursor += s.length();
                            return true;
                        }
                        if (n.equals("deleteSurroundingText")) {
                            int b = Math.min((Integer) a[0], cursor), af = Math.min((Integer) a[1], text.length() - cursor);
                            text.delete(cursor, cursor + af);
                            text.delete(cursor - b, cursor);
                            cursor -= b;
                            return true;
                        }
                        if (n.equals("getCursorCapsMode")) return TextUtils.getCapsMode(text, cursor, (Integer) a[0]);
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) return true;
                        if (r == int.class) return 0;
                        return null;
                    }
                });
    }

    private Object field(String name) throws Exception {
        Field f = AurebeshIME.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(ime);
    }

    /** Deja terminar la carga, las sugerencias y lo encolado en el hilo principal. */
    private void settle() throws Exception {
        if (cursor != lastReported) {
            ime.onUpdateSelection(lastReported, lastReported, cursor, cursor, -1, -1);
            lastReported = cursor;
        }
        for (int round = 0; round < 3; round++) {
            ((ExecutorService) field("worker")).submit(new Runnable() { @Override public void run() { } }).get();
            ((ExecutorService) field("suggester")).submit(new Runnable() { @Override public void run() { } }).get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void newField() throws Exception {
        text.setLength(0);
        cursor = 0;
        lastReported = 0;
        ime.info = new EditorInfo();
        ime.info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        ime.onFinishInputView(true);
        ime.onStartInputView(ime.info, false);
        settle();
        settle();
    }

    /** Teclea letra a letra en un campo nuevo y devuelve lo que queda escrito. */
    private String typed(String s) throws Exception {
        newField();
        return more(s);
    }

    private String more(String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            ime.onText(String.valueOf(s.charAt(i)));
            settle();
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> chips() throws Exception {
        Field v = AurebeshIME.class.getDeclaredField("view");
        v.setAccessible(true);
        Field s = AurebeshKeyboardView.class.getDeclaredField("suggestions");
        s.setAccessible(true);
        return (List<String>) s.get(v.get(ime));
    }

    /**
     * La correccion que solo pone o quita tildes no la cambia el contexto por otra palabra: "como asi" es "como así",
     * no "como si" por mucho que "como si" sea un par frecuente; ni "no habria" es "no había", ni "me dió" es "me dijo".
     */
    @Test
    public void elContextoNoCambiaUnaCorreccionDeTildePorOtraPalabra() throws Exception {
        assertEquals("como así ", typed("como asi "));
        assertEquals("pero así ", typed("pero asi "));
        assertEquals("ya están ", typed("ya estan "));
        assertEquals("no habría ", typed("no habria "));
        assertEquals("ese tío ", typed("ese tio "));
        assertEquals("me dio ", typed("me dió "));
        assertEquals("algo ahí ", typed("algo ahi "));
        assertTrue(AurebeshIME.sameLetters("asi", "así"));
        assertTrue(AurebeshIME.sameLetters("Porfavor", "por favor"));
        assertFalse(AurebeshIME.sameLetters("asi", "si"));
        // El contexto sigue eligiendo entre palabras distintas cuando la errata es de letras
        assertEquals("por favor ", typed("porfavor "));
    }

    /** Las palabras que el usuario tenia aprendidas y la lista ampliada trae ahora en su cola siguen corrigiendose hacia ellas. */
    @Test
    public void lasAprendidasDeLaColaSiguenSiendoDestinoDeCorreccion() throws Exception {
        Prefs.addLearnedWord(app, "platicamos");
        Prefs.addLearnedWord(app, "agendar");
        settle();
        assertEquals("ayer platicamos hay que agendar ", typed("ayer platicmos hay que agendr "));
    }

    /** Lexico Star Wars en espanol tras ampliar el diccionario: Padmé, Leia y las entradas que la lista trae en su cola. */
    @Test
    public void lexicoStarWarsEnEspanolTrasAmpliarElDiccionario() throws Exception {
        assertEquals("y Padmé ", typed("y Padme "));
        assertEquals("la princesa Leia ", typed("la princesa Leia "));
        assertEquals("los separatista ", typed("los separatsta "));
        assertEquals("los inquisidores ", typed("los inquisidors "));
        Dictionary es = Dictionary.get(app, "es");
        es.setStarWars(true);
        assertTrue("padmé en minusculas se reconoce", es.suggest("padmé").known);
        assertEquals("padmé", es.suggest("padme").correction);
        assertEquals("leía", es.suggest("leia").correction);
        assertEquals("depa sigue en minusculas: es palabra de la lista", null, es.canonical("depa"));
        assertEquals(null, es.canonical("dogma"));
    }

    /**
     * Quien actualiza: la version anterior aprendia sola por repeticion "alomejor" o "sinembargo"; al arrancar la nueva
     * salen una vez de las aprendidas y la tabla las separa. Las demas aprendidas se quedan, y la que el usuario guarde
     * despues a proposito se respeta (la migracion no se repite).
     */
    @Test
    public void lasPalabrasJuntasAprendidasPorLaVersionAnteriorSeSeparanAlActualizar() throws Exception {
        Prefs.addLearnedWord(app, "alomejor");
        Prefs.addLearnedWord(app, "sinembargo");
        Prefs.addLearnedWord(app, "chambita");
        Prefs.ui(app).edit().clear().apply();            // como recien actualizada: migracion pendiente
        start();
        assertFalse(Prefs.learnedWords(app).contains("alomejor"));
        assertFalse(Prefs.learnedWords(app).contains("sinembargo"));
        assertTrue("las demas aprendidas siguen", Prefs.learnedWords(app).contains("chambita"));
        assertEquals("voy a lo mejor, sin embargo no ", typed("voy alomejor, sinembargo no "));
        // Guardada a proposito despues de actualizar: se respeta y no se vuelve a quitar
        Prefs.addLearnedWord(app, "alomejor");
        start();
        assertTrue(Prefs.learnedWords(app).contains("alomejor"));
        assertEquals("voy alomejor ", typed("voy alomejor "));
    }

    /** Las familias vetadas tampoco se proponen como palabra siguiente, aunque el par lo haya aprendido el teclado. */
    @Test
    public void lasVetadasNoSalenComoPalabraSiguiente() throws Exception {
        typed("me chingaste ");
        ime.onEnter();
        settle();
        typed("me ");
        List<String> c = chips();
        assertFalse("chips tras 'me ': " + c, c != null && c.contains("chingaste"));
    }

    /**
     * Una palabra que la lista no trae, escrita con una tilde que puede ser suya (apellidos, gentilicios, palabras
     * raras), se deja como esta: antes "Jaén" pasaba a "Jane" y "leonés" a "leones". La tilde que no puede ser suya
     * se sigue quitando o poniendo en su sitio.
     */
    @Test
    public void laTildeQuePuedeSerSuyaSeRespetaYLaImposibleSeQuita() throws Exception {
        for (String w : new String[]{"Ja\u00e9n", "leon\u00e9s", "D\u00edez", "Mac\u00edas", "Zo\u00e9", "de\u00e1n", "subsecretar\u00eda",
                "P\u00e1ez", "bong\u00f3s", "bord\u00f3n", "cup\u00e9", "pal\u00e9"}) {
            assertEquals(w, "voy a " + w + " ", typed("voy a " + w + " "));
        }
        String[][] wrong = {{"di\u00f3", "dio"}, {"fu\u00e9", "fue"}, {"ex\u00e1men", "examen"}, {"desp\u00faes", "despu\u00e9s"},
                {"cr\u00e9es", "crees"}, {"bi\u00e9n", "bien"}, {"fu\u00ed", "fui"}, {"vi\u00f3", "vio"}};
        for (String[] c : wrong) assertEquals(c[0], "ya " + c[1] + " ", typed("ya " + c[0] + " "));
        // la regla, por casos
        for (String w : new String[]{"ja\u00e9n", "leon\u00e9s", "d\u00edez", "mac\u00edas", "p\u00e1ez", "le\u00eda", "c\u00e1mara", "\u00e1rbol", "trav\u00e9s"}) {
            assertTrue(w, Dictionary.tildePosible(w));
        }
        for (String w : new String[]{"di\u00f3", "fu\u00e9", "fu\u00ed", "bi\u00e9n", "ex\u00e1men", "cr\u00e9es", "d\u00edme", "gui\u00f3n", "c\u00e1m\u00e1ra"}) {
            assertFalse(w, Dictionary.tildePosible(w));
        }
    }

    /** Una palabra guardada por el usuario se completa y se corrige hacia ella aunque su familia este vetada. */
    @Test
    public void laAprendidaDeFamiliaVetadaSigueSiendoDelUsuario() throws Exception {
        Prefs.addLearnedWord(app, "chingaste");
        settle();
        assertEquals("te chingaste ", typed("te chingast "));
    }

    /** Las entradas del lexico que la lista trae en su cola siguen corrigiendose ("milenario", del Halcon). */
    @Test
    public void lasEntradasDelLexicoDeLaColaSiguenCorrigiendose() throws Exception {
        assertEquals("un culto milenario ", typed("un culto milenaro "));
    }
}
