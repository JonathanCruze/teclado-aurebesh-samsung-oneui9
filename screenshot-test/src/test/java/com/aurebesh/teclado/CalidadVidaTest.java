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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mejoras de calidad de vida de la 2.8 vistas desde el servicio, con un editor falso y tecleando como en el telefono:
 * el espacio automatico tras elegir una sugerencia, la mayuscula tras los signos de apertura, los interrogativos con
 * tilde, el corrector que solo toca lo recien tecleado, intro con accion, sugerencias desde la primera letra, la
 * siguiente palabra tras una coma y los campos de contrasena.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class CalidadVidaTest {

    private final StringBuilder text = new StringBuilder();
    private final List<Integer> actions = new ArrayList<>();
    private int cursor = 0;
    private int lastReported = 0;
    private FlujoCorrectorTest.TestIME ime;
    private Application app;

    @Before
    public void setUp() throws Exception {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
        ime = Robolectric.buildService(FlujoCorrectorTest.TestIME.class).create().get();
        ime.ic = editor();
        ime.info = new EditorInfo();
        ime.info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        ime.onCreateInputView();
        newField();
    }

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
                        if (n.equals("performEditorAction")) { actions.add((Integer) a[0]); return true; }
                        if (n.equals("getCursorCapsMode")) return TextUtils.getCapsMode(text, cursor, (Integer) a[0]);
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) return true;
                        if (r == int.class) return 0;
                        return null;
                    }
                });
    }

    private Object field(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private AurebeshKeyboardView view() throws Exception {
        return (AurebeshKeyboardView) field(ime, AurebeshIME.class, "view");
    }

    private boolean shifted() throws Exception {
        return (Boolean) field(view(), AurebeshKeyboardView.class, "shift");
    }

    private void settle() throws Exception {
        if (cursor != lastReported) {
            ime.onUpdateSelection(lastReported, lastReported, cursor, cursor, -1, -1);
            lastReported = cursor;
        }
        for (int round = 0; round < 3; round++) {
            ((ExecutorService) field(ime, AurebeshIME.class, "worker")).submit(new Runnable() { @Override public void run() { } }).get();
            ((ExecutorService) field(ime, AurebeshIME.class, "suggester")).submit(new Runnable() { @Override public void run() { } }).get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void newField() throws Exception {
        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0, "");
    }

    /** Campo nuevo del tipo dado, con texto ya escrito (cursor al final). */
    private void newField(int inputType, int imeOptions, String already) throws Exception {
        text.setLength(0);
        text.append(already);
        cursor = text.length();
        lastReported = cursor;
        actions.clear();
        ime.info = new EditorInfo();
        ime.info.inputType = inputType;
        ime.info.imeOptions = imeOptions;
        ime.info.initialSelStart = cursor;
        ime.info.initialSelEnd = cursor;
        ime.onFinishInputView(true);
        ime.onStartInputView(ime.info, false);
        settle();
        settle();
    }

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

    private String chip(String word) throws Exception {
        ime.onSuggestion(word);
        settle();
        return text.toString();
    }

    private String delete() throws Exception {
        ime.onDelete();
        settle();
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> chips() throws Exception {
        return new ArrayList<>((List<String>) field(view(), AurebeshKeyboardView.class, "suggestions"));
    }

    private String highlighted() throws Exception {
        return (String) field(view(), AurebeshKeyboardView.class, "highlighted");
    }

    // ------------------------------------------------------------------ espacio automatico tras un chip

    /** La palabra elegida en la cinta lleva un espacio automatico: la puntuacion lo absorbe y un espacio no lo duplica. */
    @Test
    public void elSignoSePegaALaPalabraElegidaEnLaCinta() throws Exception {
        typed("hol");
        assertEquals("hola ", chip("hola"));
        assertEquals("hola,", more(","));

        typed("muchas ");
        chip("gracias");
        assertEquals("muchas gracias.", more("."));

        typed("hol");
        chip("hola");
        assertEquals("un solo espacio", "hola ", more(" "));
        assertEquals("y el segundo, seguido, pone el punto", "hola. ", more(" "));

        typed("hol");
        chip("hola");
        assertEquals("otra tecla conserva el espacio", "hola y", more("y"));

        typed("hol");
        chip("hola");
        assertEquals("los signos de interrogacion tambien se pegan", "hola?", more("?"));
    }

    /** Lo que no es un espacio automatico no se toca: el tecleado a mano, tras borrar, o un emoji sugerido. */
    @Test
    public void elEspacioTecleadoAManoNoSeAbsorbe() throws Exception {
        assertEquals("hola ,", typed("hola ,"));

        typed("hol");
        chip("hola");
        assertEquals("borrar quita solo el espacio", "hola", delete());
        assertEquals("hola ,", more(" ,"));

        typed("hol");
        chip("hola");
        more("y ");
        assertEquals("solo vale para la tecla siguiente", "hola y ,", more(","));

        typed("fiesta");
        String before = text.toString();
        ime.onSuggestion("🎉");
        settle();
        String withEmoji = text.toString();
        assertFalse(before.equals(withEmoji));
        assertEquals("el emoji no pierde nada con la coma", withEmoji + ",", more(","));
    }

    /** Una prediccion tocada con el punto pegado delante pone primero el espacio que falta, y va con mayuscula. */
    @Test
    public void laPrediccionTrasUnPuntoPegadoPoneSuEspacio() throws Exception {
        typed("Hola.");
        assertEquals("Hola. Como ", chip("como"));
        assertEquals("Hola. Como,", more(","));
    }

    /** El chip entre comillas conserva lo escrito con el mismo espacio automatico. */
    @Test
    public void elChipEntreComillasTambienLlevaEspacioAutomatico() throws Exception {
        typed("zorgblat");
        ime.onSuggestion("\"zorgblat\"");
        settle();
        assertEquals("zorgblat ", text.toString());
        assertEquals("zorgblat,", more(","));
    }

    /** Elegida en medio del texto, con un espacio o un signo ya detras: no se anade otro espacio. */
    @Test
    public void unChipEnMedioDelTextoNoDuplicaElEspacio() throws Exception {
        newField();
        text.append("hol mundo");
        cursor = 3;
        settle();
        assertEquals("hola mundo", chip("hola"));
        assertEquals("hola, mundo", more(","));

        newField();
        text.append("(hol)");
        cursor = 4;
        settle();
        assertEquals("(hola)", chip("hola"));
    }

    // ------------------------------------------------------------------ signos de cierre

    /** Parentesis, corchetes y comillas de cierre cierran la palabra como un separador, con su correccion y su deshacer. */
    @Test
    public void losSignosDeCierreCierranLaPalabra() throws Exception {
        assertEquals("(tambi\u00e9n)", typed("(tambien)"));
        assertEquals("borrar recupera lo escrito", "(tambien)", delete());
        assertEquals("dijo \"necesito\"", typed("dijo \"nesecito\""));
        assertEquals("\u00abnecesito\u00bb", typed("\u00abnesecito\u00bb"));
        assertEquals("un cierre suelto es un signo mas", "hola :)", typed("hola :)"));
        assertEquals("la comilla que abre no cierra nada", "\"", typed("\""));

        typed("(hol");
        chip("hola");
        assertEquals("y se pegan a la palabra elegida en la cinta", "(hola)", more(")"));

        assertTrue(AurebeshIME.oddQuotes("dijo \"hola"));
        assertFalse(AurebeshIME.oddQuotes("dijo \"hola\" y"));
        assertFalse("cada parrafo cuenta aparte", AurebeshIME.oddQuotes("dijo \"hola\nadios"));
        assertFalse(AurebeshIME.oddQuotes(null));
    }

    // ------------------------------------------------------------------ signos de apertura

    @Test
    public void losSignosDeAperturaEmpiezanFrase() {
        assertTrue(AurebeshIME.opensSentence("¿", false));
        assertTrue(AurebeshIME.opensSentence("Hola. ¿", false));
        assertTrue(AurebeshIME.opensSentence("Hola.\n¡", false));
        assertTrue(AurebeshIME.opensSentence("«", false));
        assertTrue(AurebeshIME.opensSentence("Vaya! ¡¿", false));
        assertTrue("tras el exclarrogativo", AurebeshIME.opensSentence("Que‽ ¿", false));
        assertFalse("tras una coma, en minuscula", AurebeshIME.opensSentence("Hola, ¿", false));
        assertFalse(AurebeshIME.opensSentence("hola ¿", false));
        assertFalse("sin espacio tras el punto", AurebeshIME.opensSentence("Hola.¿", false));
        assertFalse("un parentesis ya lo resuelve el editor", AurebeshIME.opensSentence("(", false));
        assertFalse("sin saber que habia antes, no", AurebeshIME.opensSentence("¿", true));
        assertFalse(AurebeshIME.opensSentence("", false));
        assertFalse(AurebeshIME.opensSentence(null, false));
    }

    /** Tras ¿ o ¡ a inicio de frase el teclado queda en mayuscula; tras una coma, no; y respeta el interruptor. */
    @Test
    public void mayusculaAutomaticaTrasLosSignosDeApertura() throws Exception {
        typed("¿");
        assertTrue("mayuscula tras ¿", shifted());
        typed("Hola. ¡");
        assertTrue("mayuscula tras ¡ a inicio de frase", shifted());
        typed("Hola, ¿");
        assertFalse("tras una coma sigue en minuscula", shifted());

        Prefs.get(app).edit().putBoolean(Prefs.AUTO_CAPS, false).apply();
        typed("¿");
        assertFalse("con las mayusculas automaticas apagadas, nada", shifted());
        Prefs.get(app).edit().remove(Prefs.AUTO_CAPS).apply();

        newField(InputType.TYPE_CLASS_TEXT, 0, "");
        more("¿");
        assertFalse("un campo que no pide mayusculas no las recibe", shifted());
    }

    /** Tras ¿ la cinta ofrece lo que mas se pregunta, con la caja que toque. */
    @Test
    public void trasLaInterrogacionSeOfrecenLosInterrogativos() throws Exception {
        typed("¿");
        assertEquals(java.util.Arrays.asList("Qué", "Cómo", "Cuándo"), chips());
        assertEquals("¿Qué ", chip("Qué"));

        typed("oye, ¿");
        assertEquals(java.util.Arrays.asList("qué", "cómo", "cuándo"), chips());
        assertEquals("oye, ¿cómo ", chip("cómo"));
    }

    /** "¿que", "¿como", "¿cuando"... llevan tilde ahi; se deshace con borrar y fuera de la pregunta no se tocan. */
    @Test
    public void losInterrogativosTrasLaAperturaLlevanTilde() throws Exception {
        assertEquals("¿qué ", typed("¿que "));
        assertEquals("¿Cómo ", typed("¿Como "));
        assertEquals("oye, ¿cuándo ", typed("oye, ¿cuando "));
        assertEquals("¿dónde?", typed("¿donde?"));

        typed("¿que");
        assertEquals("\"que\"", chips().get(0));
        assertEquals("qué", chips().get(1));
        assertEquals("qué", highlighted());
        more(" ");
        assertEquals("borrar justo despues recupera lo escrito", "¿que ", delete());

        assertEquals("fuera de la pregunta no se toca", "dijo que ", typed("dijo que "));
        assertEquals("tras la exclamacion tampoco", "¡que ", typed("¡que "));
        assertEquals("solo la palabra pegada al signo", "¿y como ", typed("¿y como "));
    }

    // ------------------------------------------------------------------ el corrector solo toca lo tecleado

    @Test
    public void elCorrectorNoTocaLoPegadoNiLoQueYaEstaba() throws Exception {
        assertEquals("control: tecleado si se corrige", "nos vemos con que ", typed("nos vemos con qeu "));

        newField();
        ime.insertText("nos vemos con qeu");
        settle();
        assertNull("sin pastilla resaltada en lo pegado", highlighted());
        assertEquals("nos vemos con qeu,", more(","));

        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0, "nesecito");
        assertEquals("lo que ya estaba en el campo se queda", "nesecito ", more(" "));

        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0, "nesecit");
        assertEquals("continuar a mano una palabra a medias si corrige", "necesito ", more("o "));

        typed("nesecito");
        delete();
        delete();
        assertEquals("borrar y reteclear sigue corrigiendo", "necesito ", more("to "));
    }

    /** Deshacer una correccion con borrar es definitivo: borrar el espacio y poner otro signo no la repite. */
    @Test
    public void unaCorreccionDeshechaNoVuelve() throws Exception {
        assertEquals("que ", typed("qeu "));
        assertEquals("qeu ", delete());
        assertEquals("qeu", delete());
        assertEquals("qeu,", more(","));
    }

    // ------------------------------------------------------------------ intro con accion

    @Test
    public void laAccionDelEditorSoloPoneTildes() throws Exception {
        int textField = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        newField(textField, EditorInfo.IME_ACTION_SEARCH, "");
        more("nesecito");
        ime.onEnter();
        settle();
        assertEquals("buscar no cambia la palabra por otra", "nesecito", text.toString());
        assertEquals(java.util.Arrays.asList(EditorInfo.IME_ACTION_SEARCH), actions);

        newField(textField, EditorInfo.IME_ACTION_SEND, "");
        more("cancion");
        ime.onEnter();
        settle();
        assertEquals("pero si le pone la tilde", "canción", text.toString());

        Prefs.addShortcut(app, "tqm", "te quiero mucho");
        settle();
        newField(textField, EditorInfo.IME_ACTION_SEND, "");
        more("tqm");
        ime.onEnter();
        settle();
        assertEquals("los atajos de texto siguen expandiendose", "te quiero mucho", text.toString());

        newField(textField | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0, "");
        more("nesecito");
        ime.onEnter();
        settle();
        assertEquals("el salto de linea corrige como cualquier separador", "necesito\n", text.toString());
    }

    // ------------------------------------------------------------------ cinta

    @Test
    public void sugerenciasDesdeLaPrimeraLetraConElContexto() throws Exception {
        typed("muchas g");
        assertEquals("gracias", chips().get(0));
        typed("buenos d");
        assertEquals("días", chips().get(0));
        typed("buenos di");
        assertEquals("sin contar tildes", "días", chips().get(0));

        typed("hola c");
        List<String> one = chips();
        assertFalse("con una letra ya hay sugerencias", one.isEmpty());
        for (String c : one) assertFalse("nunca entre comillas: " + c, c.startsWith("\""));
        assertNull("ni pastilla resaltada", highlighted());
        assertEquals("una letra suelta se queda tal cual", "hola c ", more(" "));
        assertEquals("y ", typed("y "));
        assertEquals("a ", typed("a "));
        assertEquals("que ", typed("qeu "));

        Dictionary es = Dictionary.get(app, "es");
        assertEquals(3, es.complete("c", 3).size());
        assertNull(es.suggest("c").correction);
        assertTrue(es.suggest("c").known);
        assertTrue("completions sigue pidiendo dos letras", es.completions("c", 3).isEmpty());
    }

    /** Los chips ensenan lo que se va a escribir: con la mayuscula de lo tecleado. */
    @Test
    public void losChipsLlevanLaCajaDeLoEscrito() throws Exception {
        typed("Hol");
        List<String> c = chips();
        assertTrue("Hola entre " + c, c.contains("Hola"));
        for (String s : c) {
            if (s.startsWith("\"") || AurebeshKeyboardView.isEmoji(s)) continue;
            assertTrue("con mayuscula: " + s, Character.isUpperCase(s.charAt(0)));
        }
        assertEquals("Hola ", chip("Hola"));

        typed("Tambien");
        assertEquals("También", highlighted());
        assertEquals("También ", more(" "));

        typed("hol");
        for (String s : chips()) {
            if (s.startsWith("\"") || AurebeshKeyboardView.isEmoji(s)) continue;
            assertTrue("en minuscula: " + s, Character.isLowerCase(s.charAt(0)));
        }
    }

    @Test
    public void laSiguientePalabraTambienTrasUnaComa() throws Exception {
        typed("muchas ");
        List<String> plain = chips();
        assertFalse(plain.isEmpty());
        typed("muchas, ");
        assertEquals(plain, chips());

        typed("muchas,");
        assertTrue("pegado a la coma, nada: " + chips(), chips().isEmpty());
        typed("son 1,000 ");
        assertTrue("entre cifras no hay palabra: " + chips(), chips().isEmpty());
        typed("vale; ");
        assertTrue("el punto y coma corta: " + chips(), chips().isEmpty());
    }

    // ------------------------------------------------------------------ campos

    @Test
    public void lasContrasenasLlevanFilaDeNumerosYNoPonenPuntos() throws Exception {
        int password = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        int normal = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        Prefs.get(app).edit().putString(Prefs.TOP_ROW, "none").apply();
        settle();
        newField(normal, 0, "");
        assertNull("sin fila de numeros, como eligio el usuario", view().keyFor("1"));
        newField(password, 0, "");
        assertNotNull("en una contrasena, siempre", view().keyFor("1"));
        assertEquals("a  ", more("a  "));
        newField(normal, 0, "");
        assertNull("y al salir vuelve la fila elegida", view().keyFor("1"));
        assertEquals("a. ", more("a  "));

        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0, "");
        assertEquals("en un correo el doble espacio no pone punto", "a  ", more("a  "));
        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 0, "");
        assertEquals("a  ", more("a  "));
    }

    /** Mantener intro abre los emojis en los campos normales; en contrasenas no (ni en el PIN, que lleva otro teclado). */
    @Test
    public void mantenerIntroSoloEnCamposNormales() throws Exception {
        newField();
        assertTrue((Boolean) field(view(), AurebeshKeyboardView.class, "enterEmoji"));
        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, "");
        assertFalse((Boolean) field(view(), AurebeshKeyboardView.class, "enterEmoji"));
    }

    /** La app vacia el campo al enviar con su boton: de la pagina de signos se vuelve sola a las letras. */
    @Test
    public void alVaciarseElCampoSeVuelveALasLetras() throws Exception {
        typed("vale");
        view().showSymbols();
        assertTrue(view().onSymbols());
        text.setLength(0);
        cursor = 0;
        ime.info.initialSelStart = 0;
        ime.info.initialSelEnd = 0;
        ime.onStartInputView(ime.info, true);
        assertFalse("campo vaciado: otra vez letras", view().onSymbols());
        assertNotNull(view().keyFor("q"));

        typed("vale");
        view().showSymbols();
        ime.onStartInputView(ime.info, true);
        assertTrue("con texto en el campo se queda en signos", view().onSymbols());

        typed("a");
        view().showSymbols();
        delete();
        ime.onStartInputView(ime.info, true);
        assertTrue("si lo vacio el propio usuario borrando, tambien se queda", view().onSymbols());
    }
}
