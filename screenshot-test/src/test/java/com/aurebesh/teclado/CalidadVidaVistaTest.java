package com.aurebesh.teclado;

import android.app.Application;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mejoras de calidad de vida de la 2.8 en la vista del teclado: mantener una tecla con dos pulgares, el bloqueo de
 * mayusculas que pide dos toques seguidos, la vuelta sola de la pagina de signos a las letras y mantener intro para
 * abrir los emojis.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CalidadVidaVistaTest {

    private static final int W = 1080;

    private static void layout(View v, int w) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
    }

    private static void event(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void down(View v, AurebeshKeyboardView.Key k) { event(v, MotionEvent.ACTION_DOWN, k.rect.centerX(), k.rect.centerY()); }
    private static void up(View v, AurebeshKeyboardView.Key k) { event(v, MotionEvent.ACTION_UP, k.rect.centerX(), k.rect.centerY()); }
    private static void tap(View v, AurebeshKeyboardView.Key k) { down(v, k); up(v, k); }

    /** Evento con dos dedos (ids 0 y 1); {@code actionIndex} es el que baja o sube. */
    private static void two(View v, int action, int actionIndex, AurebeshKeyboardView.Key a, AurebeshKeyboardView.Key b) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        AurebeshKeyboardView.Key[] keys = {a, b};
        for (int i = 0; i < 2; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = keys[i].rect.centerX();
            coords[i].y = keys[i].rect.centerY();
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        long t = SystemClock.uptimeMillis();
        int act = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        MotionEvent e = MotionEvent.obtain(t, t, act, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    /** Un solo dedo con el id dado (el que queda tras levantar el otro). */
    private static void single(View v, int action, int pointerId, AurebeshKeyboardView.Key k) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        props[0].id = pointerId;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coords[0].x = k.rect.centerX();
        coords[0].y = k.rect.centerY();
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void idle(long ms) { ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(ms)); }

    private AurebeshKeyboardView view() {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);
        kb.setEnterAction(4);
        layout(kb, W);
        return kb;
    }

    /** A dos pulgares: el dedo que llega escribe la tecla del otro, y al levantarse ese otro no le quita su menu. */
    @Test
    public void mantenerUnaTeclaConElOtroPulgarLevantandose() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key s = kb.keyFor("s"), o = kb.keyFor("o");
        down(kb, s);
        two(kb, MotionEvent.ACTION_POINTER_DOWN, 1, s, o);
        assertEquals("s", rec.typed.toString());
        two(kb, MotionEvent.ACTION_POINTER_UP, 0, s, o);
        idle(600);
        assertNotNull("el menu de la o se abre aunque el otro dedo se levantara", kb.popupOptions());
        single(kb, MotionEvent.ACTION_UP, 1, o);
        assertEquals("se escribe una sola alternativa", 2, rec.typed.length());
    }

    /** Lo mismo con borrar: sigue repitiendo aunque se levante el pulgar que escribio la ultima letra. */
    @Test
    public void borrarSigueRepitiendoConElOtroPulgarLevantandose() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key a = kb.keyFor("a"), del = kb.keyFor(AurebeshKeyboardView.CODE_DELETE);
        down(kb, a);
        two(kb, MotionEvent.ACTION_POINTER_DOWN, 1, a, del);
        assertEquals("a", rec.typed.toString());
        assertEquals(1, rec.deletes);
        two(kb, MotionEvent.ACTION_POINTER_UP, 0, a, del);
        idle(1000);
        assertTrue("borrar repite: " + rec.deletes, rec.deletes > 8);
        single(kb, MotionEvent.ACTION_UP, 1, del);
        int n = rec.deletes;
        idle(500);
        assertEquals("y se para al soltar", n, rec.deletes);
    }

    /** Mayusculas, letra, mayusculas, letra tecleadas deprisa: dos mayusculas sueltas, no un bloqueo. */
    @Test
    public void elBloqueoPideDosToquesSeguidosDeMayusculas() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key shift = kb.keyFor(AurebeshKeyboardView.CODE_SHIFT);
        tap(kb, shift);
        tap(kb, kb.keyFor("o"));
        tap(kb, shift);
        tap(kb, kb.keyFor("k"));
        tap(kb, kb.keyFor("a"));
        assertEquals("OKa", rec.typed.toString());
        rec.reset();
        tap(kb, shift);
        tap(kb, shift);
        tap(kb, kb.keyFor("o"));
        tap(kb, kb.keyFor("k"));
        assertEquals("dos toques seguidos siguen bloqueando", "OK", rec.typed.toString());
    }

    /** Un signo y un espacio (o intro) devuelven a las letras; un espacio nada mas entrar, no. */
    @Test
    public void laPaginaDeSignosVuelveSolaALasLetras() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        assertTrue(kb.onSymbols());
        tap(kb, kb.keyFor("$"));
        assertTrue("tras el signo sigue en signos", kb.onSymbols());
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_SPACE));
        assertEquals("$ ", rec.typed.toString());
        assertFalse("signo + espacio: letras", kb.onSymbols());
        assertNotNull(kb.keyFor("q"));

        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_SPACE));
        assertTrue("un espacio nada mas entrar se queda en signos", kb.onSymbols());
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        assertFalse(kb.onSymbols());

        // Segunda pagina: tambien vuelve; y varios signos seguidos no salen de la pagina hasta el espacio
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_PAGE));
        tap(kb, kb.keyFor("€"));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_PAGE));
        tap(kb, kb.keyFor("$"));
        assertTrue(kb.onSymbols());
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_SPACE));
        assertFalse(kb.onSymbols());

        // Intro tras un signo tambien vuelve
        rec.reset();
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        tap(kb, kb.keyFor("$"));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_ENTER));
        assertTrue(rec.events.contains("enter"));
        assertFalse(kb.onSymbols());

        // Entrar, escribir un signo, salir y volver a entrar: el espacio ya no cuenta
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        tap(kb, kb.keyFor("$"));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_SPACE));
        assertTrue(kb.onSymbols());
    }

    /** El teclado numerico no cambia de pagina por escribir cifras e intro. */
    @Test
    public void elTecladoNumericoNoSeVa() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        kb.showNumpad(false, false, false);
        layout(kb, W);
        tap(kb, kb.keyFor("5"));
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_ENTER));
        assertTrue(kb.isNumpad());
        assertEquals("5", rec.typed.toString());
    }

    /** Mantener intro abre los emojis sin enviar nada; el toque corto sigue siendo intro. */
    @Test
    public void mantenerIntroAbreLosEmojis() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        kb.setEnterEmoji(true);
        AurebeshKeyboardView.Key enter = kb.keyFor(AurebeshKeyboardView.CODE_ENTER);

        tap(kb, enter);
        assertEquals("[enter]", rec.events.toString());
        assertEquals("", rec.typed.toString());
        rec.reset();

        down(kb, enter);
        idle(400);
        assertEquals("a los 400 ms aun no (380 + 220)", "", rec.typed.toString());
        idle(260);
        assertEquals("<tool" + AurebeshKeyboardView.TOOL_EMOJI + ">", rec.typed.toString());
        up(kb, enter);
        assertFalse("al soltar no se envia intro", rec.events.contains("enter"));
        rec.reset();

        // Tras abrirlos, el toque siguiente funciona normal
        tap(kb, kb.keyFor("a"));
        assertEquals("a", rec.typed.toString());
        rec.reset();

        // Sin la bandera (teclados reducidos de las bandejas): mantener no abre nada y al soltar hay intro
        kb.setEnterEmoji(false);
        down(kb, enter);
        idle(700);
        up(kb, enter);
        assertEquals("", rec.typed.toString());
        assertEquals("[enter]", rec.events.toString());
        rec.reset();

        // En el teclado numerico tampoco
        kb.setEnterEmoji(true);
        kb.showNumpad(false, false, false);
        layout(kb, W);
        AurebeshKeyboardView.Key numEnter = kb.keyFor(AurebeshKeyboardView.CODE_ENTER);
        down(kb, numEnter);
        idle(700);
        up(kb, numEnter);
        assertEquals("", rec.typed.toString());
        assertEquals("[enter]", rec.events.toString());
    }

    /** En una contrasena la fila de numeros va siempre; al salir vuelve la del usuario. */
    @Test
    public void filaDeNumerosEnContrasenas() {
        AurebeshKeyboardView kb = view();
        kb.setTopRow("digraphs");
        layout(kb, W);
        assertNull(kb.keyFor("1"));
        assertNotNull(kb.keyFor("ch"));
        kb.setPasswordField(true);
        layout(kb, W);
        assertNotNull(kb.keyFor("1"));
        assertNull("las letras dobles ceden el sitio", kb.keyFor("ch"));
        kb.setPasswordField(false);
        layout(kb, W);
        assertNull(kb.keyFor("1"));
        assertNotNull(kb.keyFor("ch"));
        assertEquals("la preferencia no cambia", "digraphs", kb.topRow());
    }

    /** A una mano: bajo la flecha de cambiar de lado, un boton devuelve el teclado completo. */
    @Test
    public void botonDeTecladoCompletoEnModoAUnaMano() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        draw(kb);
        assertTrue("sin modo a una mano no hay boton", kb.fullButtonRect().isEmpty());

        kb.setHandMode(2);
        draw(kb);
        android.graphics.RectF side = kb.sideButtonRect(), full = kb.fullButtonRect();
        assertFalse(side.isEmpty());
        assertFalse(full.isEmpty());
        assertTrue("debajo de la flecha, sin tocarla", full.top > side.bottom);
        assertEquals("centrados en el mismo margen", side.centerX(), full.centerX(), 0.5f);
        for (java.util.List<AurebeshKeyboardView.Key> row : kb.currentRows()) {
            for (AurebeshKeyboardView.Key k : row) {
                assertFalse("no pisa la tecla " + k.label, android.graphics.RectF.intersects(full, k.rect));
            }
        }
        event(kb, MotionEvent.ACTION_DOWN, full.centerX(), full.centerY());
        event(kb, MotionEvent.ACTION_UP, full.centerX(), full.centerY());
        assertEquals("<tool" + AurebeshKeyboardView.TOOL_HAND + ">", rec.typed.toString());
        rec.reset();
        event(kb, MotionEvent.ACTION_DOWN, side.centerX(), side.centerY());
        event(kb, MotionEvent.ACTION_UP, side.centerX(), side.centerY());
        assertEquals("la flecha sigue cambiando de lado", "<side1>", rec.typed.toString());

        // Lector de pantalla: el nodo existe, se llama "Teclado completo" y su clic hace lo mismo
        rec.reset();
        assertEquals(AurebeshKeyboardView.A11Y_FULL, kb.a11yIdAt(full.centerX(), full.centerY()));
        Application app = (Application) RuntimeEnvironment.getApplication();
        android.view.accessibility.AccessibilityNodeProvider p = kb.getAccessibilityNodeProvider();
        android.view.accessibility.AccessibilityNodeInfo node = p.createAccessibilityNodeInfo(AurebeshKeyboardView.A11Y_FULL);
        assertNotNull(node);
        assertEquals(app.getString(R.string.a11y_full_keyboard), String.valueOf(node.getContentDescription()));
        assertTrue(p.performAction(AurebeshKeyboardView.A11Y_FULL, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals("<tool" + AurebeshKeyboardView.TOOL_HAND + ">", rec.typed.toString());

        kb.setHandMode(0);
        draw(kb);
        assertTrue(kb.fullButtonRect().isEmpty());
    }

    private static void draw(View v) {
        layout(v, W);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(W, v.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(b));
    }

    /** El dedo que rueda unos pocos dp al levantarse sigue en su tecla; pasado el margen, la vecina. */
    @Test
    public void histeresisEntreTeclasVecinas() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        float dp = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        AurebeshKeyboardView.Key a = kb.keyFor("a"), s = kb.keyFor("s");
        float y = a.rect.centerY();
        event(kb, MotionEvent.ACTION_DOWN, a.rect.right - 2 * dp, y);
        event(kb, MotionEvent.ACTION_MOVE, a.rect.right + 3 * dp, y);
        event(kb, MotionEvent.ACTION_UP, a.rect.right + 3 * dp, y);
        assertEquals("a", rec.typed.toString());
        rec.reset();
        event(kb, MotionEvent.ACTION_DOWN, a.rect.right - 2 * dp, y);
        event(kb, MotionEvent.ACTION_MOVE, s.rect.centerX(), y);
        event(kb, MotionEvent.ACTION_UP, s.rect.centerX(), y);
        assertEquals("deslizar de verdad a la vecina la escribe", "s", rec.typed.toString());
        rec.reset();

        // Mantener la o con el dedo temblando sobre la frontera: el menu se abre igual
        kb.setGlideEnabled(false);
        AurebeshKeyboardView.Key o = kb.keyFor("o");
        float ox = o.rect.right - 1 * dp, oy = o.rect.centerY();
        event(kb, MotionEvent.ACTION_DOWN, ox, oy);
        for (int i = 0; i < 6; i++) {
            idle(60);
            event(kb, MotionEvent.ACTION_MOVE, ox + (i % 2 == 0 ? 3 : -3) * dp, oy);
        }
        idle(100);
        assertNotNull("el temporizador no se reinicia en cada cruce", kb.popupOptions());
        event(kb, MotionEvent.ACTION_UP, ox, oy);
    }

    /** Intro solo se ejecuta si el dedo bajo en ella: llegar desde el punto no envia nada ni abre los emojis. */
    @Test
    public void introNoSeActivaLlegandoDesdeOtraTecla() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        kb.setEnterEmoji(true);
        AurebeshKeyboardView.Key dot = kb.keyFor("."), enter = kb.keyFor(AurebeshKeyboardView.CODE_ENTER);
        down(kb, dot);
        event(kb, MotionEvent.ACTION_MOVE, enter.rect.centerX(), enter.rect.centerY());
        idle(1000);
        up(kb, enter);
        assertEquals("", rec.typed.toString());
        assertTrue("ni intro ni nada: " + rec.events, rec.events.isEmpty());
        tap(kb, enter);
        assertEquals("el toque en intro sigue igual", "[enter]", rec.events.toString());
    }

    /** Los dominios de ".com" siguen al pais del telefono: en Mexico, ".mx" bajo el dedo y ".com.mx" al lado. */
    @Test
    public void dominiosSegunElPais() {
        assertEquals("[.mx, .com.mx, .org, .net]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("es", "MX")));
        assertEquals("[.es, .mx, .org, .net]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("es", "ES")));
        assertEquals("[.de, .at, .org, .net]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("de", "DE")));
        assertEquals("[.at, .de, .org, .net]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("de", "AT")));
        assertEquals("el pais manda sobre el idioma del teclado", "[.mx, .com.mx, .org, .net]",
                java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("en", "MX")));
        assertEquals("[.org, .net, .edu, .gov]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("en", "US")));
        assertEquals("[.es, .mx, .org, .net]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("es", "AR")));
        assertEquals("[.de, .at, .org, .net]", java.util.Arrays.toString(AurebeshKeyboardView.domainAlts("de", "")));
    }

    @Test
    @Config(sdk = 35, qualifiers = "es-rMX-w411dp-h914dp-xxhdpi")
    public void enMexicoElPrimerDominioEsMx() {
        AurebeshKeyboardView kb = view();
        GestureTest.Recorder rec = new GestureTest.Recorder();
        kb.setListener(rec);
        kb.setFieldVariant(AurebeshKeyboardView.FIELD_EMAIL);
        layout(kb, W);
        AurebeshKeyboardView.Key com = kb.keyFor(".com");
        down(kb, com);
        idle(450);
        java.util.List<String> shown = java.util.Arrays.asList(kb.popupOptions());   // en el orden en que se dibujan (en espejo junto al borde)
        assertEquals(4, shown.size());
        assertTrue(shown.toString(), shown.containsAll(java.util.Arrays.asList(".mx", ".com.mx", ".org", ".net")));
        assertEquals(".mx bajo el dedo", ".mx", shown.get(kb.popupSelectedCell()));
        up(kb, com);
        assertEquals(".mx", rec.typed.toString());
    }
}
