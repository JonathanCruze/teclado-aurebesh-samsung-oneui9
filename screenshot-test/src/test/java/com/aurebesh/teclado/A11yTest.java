package com.aurebesh.teclado;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Accesibilidad del teclado (2.0): nodos virtuales para el lector de pantalla con nombres en el idioma de la app,
 * clic por accesibilidad que escribe como un toque, exploracion tactil (hover) que selecciona la tecla bajo el dedo
 * y tolerancia de las filas compactas (por debajo de 48 dp) tambien al explorar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class A11yTest {

    private static final int W = 1080;
    private static final String PHONE = "es-rES-w411dp-h914dp-xxhdpi";

    /** Oyente que apunta todo lo que llega de la vista. */
    static final class Recorder implements AurebeshKeyboardView.Listener {
        final StringBuilder typed = new StringBuilder();
        final List<String> events = new ArrayList<>();
        int deletes;

        public void onText(String s) { typed.append(s); events.add("text:" + s); }
        public void onDelete() { deletes++; events.add("del"); }
        public void onEnter() { events.add("enter"); }
        public void onSwitchKeyboard() { events.add("switchkb"); }
        public void onSwitchLanguage() { events.add("lang"); }
        public void onSuggestion(String w) { events.add("sug:" + w); }
        public void onToolbar(int t) { typed.append("<tool").append(t).append(">"); }
        public void onHandSide(int m) { typed.append("<side").append(m).append(">"); }
        public void onPasteSuggestion() { events.add("paste"); }
        public void onGlide(String best, List<String> alts) { events.add("glide:" + best); }
        public void onCursorMove(int steps, boolean select) { events.add("cur" + steps + (select ? "s" : "")); }
        public void onDeleteWord() { events.add("delword"); }
        public void onHide() { events.add("hide"); }
        public void onResize(int heightPct, int bottomDp) { events.add("resize" + heightPct + "," + bottomDp); }
    }

    private static void layout(View v, int w) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
    }

    private static Bitmap render(View v, int w) {
        layout(v, w);
        Bitmap b = Bitmap.createBitmap(w, v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    private static void save(Bitmap b, String name) throws Exception {
        File out = new File(System.getProperty("shots.dir", "shots"), name);
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("SHOT " + out.getAbsolutePath() + " " + b.getWidth() + "x" + b.getHeight());
    }

    /** Evento de hover (exploracion tactil) sobre la vista, como lo manda el sistema con TalkBack. */
    private static void hover(AurebeshKeyboardView v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.dispatchHoverEvent(e);
        e.recycle();
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void check(String got, String want, String what) {
        if (!want.equals(got)) throw new AssertionError(what + ": " + got + " (esperado " + want + ")");
    }

    private static void near(String what, float actual, float expected) {
        if (Math.abs(actual - expected) > 2.5f) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }

    private AurebeshKeyboardView view(Application app, int w) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);
        kb.setEnterAction(4);
        layout(kb, w);
        return kb;
    }

    /** Nombre que lee el lector de pantalla para un nodo; "<sin nodo>" si el proveedor no lo da. */
    private static String desc(AccessibilityNodeProvider p, int id) {
        AccessibilityNodeInfo n = p.createAccessibilityNodeInfo(id);
        return n == null ? "<sin nodo>" : String.valueOf(n.getContentDescription());
    }

    private static boolean click(AccessibilityNodeProvider p, int id) {
        return p.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null);
    }

    /** Recorre la cinta de izquierda a derecha y devuelve la x del centro del primer punto que cae en ese nodo (-1 si no esta). */
    private static float stripX(AurebeshKeyboardView kb, int id, float dp) {
        float y = 20 * dp;
        for (float x = 0; x < kb.getWidth(); x += dp) if (kb.a11yIdAt(x, y) == id) return x + 2 * dp;
        return -1;
    }

    private static final int EMOJI = AurebeshKeyboardView.A11Y_TOOL + AurebeshKeyboardView.TOOL_EMOJI;

    @Test
    public void nodesWithLocalizedNames() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        kb.setListener(new Recorder());
        save(render(kb, W), "a11y_teclado.png");                  // dibujar coloca los iconos de la cinta
        AccessibilityNodeProvider p = kb.getAccessibilityNodeProvider();
        check(p != null, "la vista tiene proveedor de nodos de accesibilidad");

        AccessibilityNodeInfo host = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
        check(host != null, "nodo raiz");
        int keys = 0;
        for (int code : new int[]{AurebeshKeyboardView.CODE_SPACE, AurebeshKeyboardView.CODE_SHIFT, AurebeshKeyboardView.CODE_DELETE,
                AurebeshKeyboardView.CODE_ENTER, AurebeshKeyboardView.CODE_MODE}) if (kb.keyFor(code) != null) keys++;
        check(host.getChildCount() > 30 + keys, "la raiz tiene un hijo por tecla y por boton de la cinta: " + host.getChildCount());

        // Ids estables: fila * 100 + columna dentro de la pagina (con fila de numeros, la q esta en la fila 1)
        AurebeshKeyboardView.Key q = kb.keyFor("q");
        int qId = kb.a11yIdOf(q);
        check(qId == 100, "id de la q = 1 * 100 + 0: " + qId);
        check(kb.a11yIdOf(kb.keyFor("a")) == 200, "id de la a = 2 * 100 + 0");
        check(kb.a11yIdOf(kb.keyFor("s")) == 201, "id de la s = 2 * 100 + 1");
        AccessibilityNodeInfo qn = p.createAccessibilityNodeInfo(qId);
        check(qn != null, "nodo de la q");
        check(String.valueOf(qn.getContentDescription()), "q", "nombre de la q");
        check(String.valueOf(qn.getClassName()), "android.widget.Button", "la q es un boton");
        check((qn.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0, "la q admite ACTION_CLICK");
        check(qn.isClickable() && qn.isEnabled() && qn.isVisibleToUser(), "la q es pulsable, activa y visible");
        Rect b = new Rect();
        qn.getBoundsInParent(b);
        near("zona de la q (izquierda)", b.left, q.rect.left);
        near("zona de la q (derecha)", b.right, q.rect.right);
        near("zona de la q (arriba)", b.top, q.rect.top);
        near("zona de la q (abajo)", b.bottom, q.rect.bottom);
        Rect s = new Rect();
        qn.getBoundsInScreen(s);
        check(s.width() == b.width() && s.height() == b.height(), "la zona en pantalla mide lo mismo que en la vista");

        // Teclas de funcion y cinta, en espanol
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_SPACE))), "Espacio", "espacio en espanol");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_DELETE))), "Borrar", "borrar en espanol");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_SHIFT))), "Mayúsculas", "mayusculas apagadas");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_ENTER))), "Listo", "intro con accion Listo");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_MODE))), "Símbolos", "?123 se llama Simbolos");
        check(desc(p, kb.a11yIdOf(kb.keyFor("ñ"))), "ñ", "la eñe");
        check(desc(p, EMOJI), "Emojis", "icono de emojis en espanol");
        check(desc(p, AurebeshKeyboardView.A11Y_AI), "Inteligencia", "boton de inteligencia");
        check(desc(p, AurebeshKeyboardView.A11Y_TOOL + AurebeshKeyboardView.TOOL_MORE), "Más herramientas", "el ... de la cinta");
        check(stripX(kb, EMOJI, dp) > 0, "el icono de emojis esta en la cinta");
        check(p.createAccessibilityNodeInfo(AurebeshKeyboardView.A11Y_PASTE) == null, "sin chip Pegar no hay nodo Pegar");
        check(p.createAccessibilityNodeInfo(12345) == null, "un id desconocido no da nodo");

        // Nombre del idioma en el espacio e intro segun la accion del campo
        kb.setSpaceLabel("Español");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_SPACE))), "Espacio, Español", "espacio con idioma");
        kb.setEnterAction(1);
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_ENTER))), "Buscar", "intro con accion Buscar");

        // Las mismas teclas en ingles y en aleman
        RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h914dp-xxhdpi");
        try {
            AurebeshKeyboardView en = view(app, W);
            en.setLanguage("en");
            render(en, W);
            AccessibilityNodeProvider pe = en.getAccessibilityNodeProvider();
            check(desc(pe, en.a11yIdOf(en.keyFor(AurebeshKeyboardView.CODE_SPACE))), "Space", "espacio en ingles");
            check(desc(pe, en.a11yIdOf(en.keyFor(AurebeshKeyboardView.CODE_DELETE))), "Delete", "borrar en ingles");
            check(desc(pe, en.a11yIdOf(en.keyFor(AurebeshKeyboardView.CODE_SHIFT))), "Shift", "mayusculas en ingles");
            check(desc(pe, EMOJI), "Emoji", "emojis en ingles");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }
        RuntimeEnvironment.setQualifiers("de-rDE-w411dp-h914dp-xxhdpi");
        try {
            AurebeshKeyboardView de = view(app, W);
            de.setLanguage("de");
            render(de, W);
            AccessibilityNodeProvider pd = de.getAccessibilityNodeProvider();
            check(desc(pd, de.a11yIdOf(de.keyFor(AurebeshKeyboardView.CODE_SPACE))), "Leertaste", "espacio en aleman");
            check(desc(pd, de.a11yIdOf(de.keyFor(AurebeshKeyboardView.CODE_DELETE))), "Löschen", "borrar en aleman");
            check(desc(pd, EMOJI), "Emojis", "emojis en aleman");
            check(desc(pd, de.a11yIdOf(de.keyFor("z"))), "z", "QWERTZ: la z existe");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }
    }

    @Test
    public void clickActsLikeATap() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        render(kb, W);
        AccessibilityNodeProvider p = kb.getAccessibilityNodeProvider();
        int qId = kb.a11yIdOf(kb.keyFor("q"));

        check(click(p, qId), "ACTION_CLICK en la q se acepta");
        check(rec.typed.toString(), "q", "ACTION_CLICK en la q escribe q");

        // Mayusculas: cambia su estado y la caja de las letras que se leen; la siguiente letra sale en mayuscula
        int shiftId = kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_SHIFT));
        check(click(p, shiftId), "clic en mayusculas");
        check(desc(p, shiftId), "Mayúsculas, activadas", "mayusculas encendidas");
        check(p.createAccessibilityNodeInfo(shiftId).isChecked(), "mayusculas marcada");
        check(desc(p, qId), "Q", "con mayusculas la q se lee Q");
        check(click(p, qId), "clic en la Q");
        check(rec.typed.toString(), "qQ", "la segunda letra sale en mayuscula");
        check(desc(p, shiftId), "Mayúsculas", "mayusculas se apaga tras la letra");

        // Borrar, espacio, intro y la cinta
        check(click(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_DELETE))), "clic en borrar");
        check(rec.deletes == 1, "borrar borra una vez");
        check(click(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_SPACE))), "clic en el espacio");
        check(rec.typed.toString(), "qQ ", "el espacio escribe un espacio");
        check(click(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_ENTER))), "clic en intro");
        check(rec.events.contains("enter"), "intro llega al oyente");
        check(click(p, EMOJI), "clic en emojis");
        check(rec.typed.toString().endsWith("<tool" + AurebeshKeyboardView.TOOL_EMOJI + ">"), "emojis abre la bandeja: " + rec.typed);
        check(click(p, AurebeshKeyboardView.A11Y_AI), "clic en inteligencia");
        check(rec.typed.toString().endsWith("<tool" + AurebeshKeyboardView.TOOL_AI + ">"), "inteligencia llega como herramienta");

        // ?123 cambia de pagina: los nodos son los de la nueva pagina y la tecla pasa a llamarse Letras
        check(click(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_MODE))), "clic en ?123");
        layout(kb, W);
        check(kb.keyFor("+") != null, "pagina de signos abierta");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_MODE))), "Letras", "abc se llama Letras");
        check(desc(p, kb.a11yIdOf(kb.keyFor(AurebeshKeyboardView.CODE_PAGE))), "Más símbolos, 1/3", "pagina de signos con su numero");
        check(desc(p, kb.a11yIdOf(kb.keyFor("+"))), "+", "el signo mas se lee tal cual");
        check(click(p, kb.a11yIdOf(kb.keyFor("+"))), "clic en +");
        check(rec.typed.toString().endsWith("+"), "el + se escribe");

        // Foco de accesibilidad: se recuerda y el nodo lo refleja
        int plusId = kb.a11yIdOf(kb.keyFor("+"));
        check(p.performAction(plusId, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null), "foco en el +");
        check(kb.a11yFocused() == plusId, "la vista recuerda el foco");
        check(p.createAccessibilityNodeInfo(plusId).isAccessibilityFocused(), "el nodo del + esta enfocado");
        check(p.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) != null, "findFocus devuelve el enfocado");
        check(p.performAction(plusId, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null), "quitar el foco");
        check(kb.a11yFocused() == AurebeshKeyboardView.A11Y_NONE, "sin foco");
        check(!click(p, 12345), "un id desconocido no hace nada");
        // En el modo de ajuste nada escribe, tampoco por accesibilidad
        int before = rec.typed.length();
        kb.setResizeMode(true);
        check(!click(p, plusId), "en el modo de ajuste el clic se rechaza");
        check(rec.typed.length() == before, "y no escribe");
        kb.setResizeMode(false);
    }

    @Test
    public void hoverSelectsKeyUnderFinger() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        render(kb, W);
        AurebeshKeyboardView.Key a = kb.keyFor("a"), q = kb.keyFor("q");

        // Sin exploracion tactil el hover no toca los nodos
        hover(kb, MotionEvent.ACTION_HOVER_ENTER, a.rect.centerX(), a.rect.centerY());
        check(kb.a11yHovered() == AurebeshKeyboardView.A11Y_NONE, "sin TalkBack el hover no selecciona nada");

        AccessibilityManager am = (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
        ShadowAccessibilityManager sam = Shadows.shadowOf(am);
        sam.setEnabled(true);
        sam.setTouchExplorationEnabled(true);
        try {
            hover(kb, MotionEvent.ACTION_HOVER_ENTER, a.rect.centerX(), a.rect.centerY());
            check(kb.a11yHovered() == kb.a11yIdOf(a), "hover sobre la a selecciona su nodo: " + kb.a11yHovered());
            hover(kb, MotionEvent.ACTION_HOVER_MOVE, q.rect.centerX(), q.rect.centerY());
            check(kb.a11yHovered() == kb.a11yIdOf(q), "al pasar a la q cambia el nodo");
            float ex = stripX(kb, EMOJI, dp);
            check(ex > 0, "icono de emojis en la cinta");
            hover(kb, MotionEvent.ACTION_HOVER_MOVE, ex, 20 * dp);
            check(kb.a11yHovered() == EMOJI, "hover sobre el icono de emojis selecciona su nodo");
            hover(kb, MotionEvent.ACTION_HOVER_MOVE, stripX(kb, AurebeshKeyboardView.A11Y_AI, dp), 20 * dp);
            check(kb.a11yHovered() == AurebeshKeyboardView.A11Y_AI, "hover sobre inteligencia");
            hover(kb, MotionEvent.ACTION_HOVER_EXIT, ex, 20 * dp);
            check(kb.a11yHovered() == AurebeshKeyboardView.A11Y_NONE, "al levantar el dedo no queda nada seleccionado");
            check(rec.typed.length() == 0 && rec.events.isEmpty(), "explorar no escribe ni acciona nada");
        } finally {
            sam.setTouchExplorationEnabled(false);
            sam.setEnabled(false);
        }
    }

    @Test
    public void compactRowsKeepTolerance() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        // Tapa del Z Flip: filas de 34 dp, por debajo de los 48 dp recomendados; la tolerancia del toque vale tambien al explorar
        RuntimeEnvironment.setQualifiers("es-rES-w360dp-h374dp-xxhdpi");
        try {
            AurebeshKeyboardView kb = view(app, W);
            kb.setListener(new Recorder());
            render(kb, W);
            AurebeshKeyboardView.Key q = kb.keyFor("q"), dot = kb.keyFor("."), m = kb.keyFor("m");
            check(q.rect.height() < 48 * dp, "clase compacta: filas por debajo de 48 dp");
            check(kb.a11yIdAt(dot.rect.centerX(), dot.rect.bottom + 4 * dp) == kb.a11yIdOf(dot), "4 dp bajo el punto sigue siendo el punto");
            check(kb.a11yIdAt(q.rect.left - 10 * dp, q.rect.centerY()) == kb.a11yIdOf(q), "el margen izquierdo lleva a la q");
            check(kb.a11yIdAt(m.rect.centerX(), m.rect.top + dp) == kb.a11yIdOf(m), "dentro de la m, la m");
            check(kb.a11yIdAt(q.rect.centerX(), q.rect.top - dp) == AurebeshKeyboardView.A11Y_NONE
                    || kb.a11yIdAt(q.rect.centerX(), q.rect.top - dp) >= AurebeshKeyboardView.A11Y_AI, "sobre la cinta no hay tecla");
            // Todas las teclas de la pagina tienen nodo con nombre, tambien en la cinta de 32 dp
            AccessibilityNodeProvider p = kb.getAccessibilityNodeProvider();
            for (String k : Arrays.asList("q", "p", "a", "l", "m", ",", ".", "ñ")) {
                int id = kb.a11yIdOf(kb.keyFor(k));
                check(id != AurebeshKeyboardView.A11Y_NONE, "la " + k + " tiene id");
                check(desc(p, id), k, "nombre de la " + k);
            }
            check(desc(p, EMOJI), "Emojis", "cinta compacta: icono de emojis con nombre");
            AccessibilityNodeInfo host = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
            check(host.getChildCount() >= 4 * 7 + 2, "la raiz compacta tiene todas las teclas: " + host.getChildCount());
            save(render(kb, W), "a11y_compacto.png");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }
    }

    @Test
    public void stripNodesFollowSuggestionsAndPaste() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        kb.setSuggestionBar(true);
        kb.setSuggestions(Arrays.asList("hola", "hora", "hoja"), "hola");
        render(kb, W);
        AccessibilityNodeProvider p = kb.getAccessibilityNodeProvider();
        int s0 = AurebeshKeyboardView.A11Y_SUGGESTION;
        check(desc(p, s0), "hola, autocorrección", "la sugerencia resaltada avisa de que se aplica sola");
        check(desc(p, s0 + 1), "hora", "segunda sugerencia");
        check(desc(p, s0 + 2), "hoja", "tercera sugerencia");
        check(p.createAccessibilityNodeInfo(s0 + 3) == null, "no hay cuarta sugerencia");
        check(click(p, s0 + 1), "clic en una sugerencia");
        check(rec.events.contains("sug:hora"), "la sugerencia llega al oyente");
        // Con chips la cinta se pliega en "...": existe ese nodo y el de emojis no
        check(desc(p, AurebeshKeyboardView.A11Y_MORE), "Más herramientas", "el ... sobre las sugerencias");
        check(p.createAccessibilityNodeInfo(EMOJI) == null, "con sugerencias los iconos no estan");
        check(click(p, AurebeshKeyboardView.A11Y_MORE), "clic en ...");
        render(kb, W);
        check(p.createAccessibilityNodeInfo(EMOJI) != null, "tras ... vuelven los iconos");

        // Chip Pegar con texto y con imagen
        kb.setSuggestions(null);
        kb.setPasteSuggestion(null, "texto copiado", "Pegar");
        render(kb, W);
        check(desc(p, AurebeshKeyboardView.A11Y_PASTE), "Pegar, texto copiado", "chip Pegar con su texto");
        check(click(p, AurebeshKeyboardView.A11Y_PASTE), "clic en Pegar");
        check(rec.events.contains("paste"), "Pegar llega al oyente");
        Bitmap thumb = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        kb.setPasteSuggestion(thumb, null, "Pegar");
        render(kb, W);
        check(desc(p, AurebeshKeyboardView.A11Y_PASTE), "Pegar la imagen copiada", "chip Pegar con imagen");
        kb.setPasteSuggestion(null, null, null);
        render(kb, W);
        check(p.createAccessibilityNodeInfo(AurebeshKeyboardView.A11Y_PASTE) == null, "sin nada copiado no hay nodo Pegar");

        // Modo a una mano: boton para cambiar de lado, con herramienta marcada como activa
        kb.setHandMode(1);
        render(kb, W);
        check(desc(p, AurebeshKeyboardView.A11Y_SIDE), "Mover el teclado a la derecha", "flecha del modo a una mano izquierda");
        check(desc(p, AurebeshKeyboardView.A11Y_TOOL + AurebeshKeyboardView.TOOL_HAND), "Una mano, activado", "una mano en marcha");
        check(click(p, AurebeshKeyboardView.A11Y_SIDE), "clic en la flecha");
        check(rec.typed.toString().endsWith("<side2>"), "cambiar al lado derecho: " + rec.typed);
        kb.setHandMode(0);
        // Boton de ocultar en la banda inferior (margen >= 24 dp)
        kb.setBottomPad(30 * app.getResources().getDisplayMetrics().density);
        render(kb, W);
        check(desc(p, AurebeshKeyboardView.A11Y_HIDE), "Ocultar teclado", "boton de ocultar");
        check(click(p, AurebeshKeyboardView.A11Y_HIDE), "clic en ocultar");
        check(rec.events.contains("hide"), "ocultar llega al oyente");
    }
}
