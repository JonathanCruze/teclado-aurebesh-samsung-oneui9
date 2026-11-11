package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gestos del teclado (1.10): menu de alternativas con la letra base y cancelacion, puntuacion al mantener el
 * punto, mayusculas sostenida con dos dedos, deslizar sobre el espacio, soltar fuera, borrar acelerado, modo a
 * una mano en pantallas anchas, teclado numerico y campos de correo y web.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GestureTest {

    private static final int W = 1080;

    /** Oyente que apunta todo lo que llega de la vista. */
    static final class Recorder implements AurebeshKeyboardView.Listener {
        final StringBuilder typed = new StringBuilder();
        final List<String> events = new ArrayList<>();
        int deletes, deleteWords, langSwitches, cursorMoves;

        public void onText(String s) { typed.append(s); events.add("text:" + s); }
        public void onDelete() { deletes++; events.add("del"); }
        public void onEnter() { events.add("enter"); }
        public void onSwitchKeyboard() { events.add("switchkb"); }
        public void onSwitchLanguage() { langSwitches++; events.add("lang"); }
        public void onSuggestion(String w) { events.add("sug:" + w); }
        public void onToolbar(int t) { typed.append("<tool").append(t).append(">"); }
        public void onHandSide(int m) { typed.append("<side").append(m).append(">"); }
        public void onPasteSuggestion() { events.add("paste"); }
        public void onGlide(String best, List<String> alts) { events.add("glide:" + best); }
        public void onCursorMove(int steps, boolean select) { cursorMoves += steps; events.add("cur" + steps + (select ? "s" : "")); }
        public void onDeleteWord() { deleteWords++; events.add("delword"); }
        public void onHide() { events.add("hide"); }
        public void onResize(int heightPct, int bottomDp) { events.add("resize" + heightPct + "," + bottomDp); }

        void reset() { typed.setLength(0); events.clear(); deletes = deleteWords = langSwitches = cursorMoves = 0; }
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

    private static void event(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void down(View v, float x, float y) { event(v, MotionEvent.ACTION_DOWN, x, y); }
    private static void move(View v, float x, float y) { event(v, MotionEvent.ACTION_MOVE, x, y); }
    private static void up(View v, float x, float y) { event(v, MotionEvent.ACTION_UP, x, y); }
    private static void tap(View v, float x, float y) { down(v, x, y); up(v, x, y); }
    private static void tap(View v, AurebeshKeyboardView.Key k) { tap(v, k.rect.centerX(), k.rect.centerY()); }

    /** Evento con varios dedos; {@code actionIndex} es el dedo que baja o sube (ACTION_POINTER_DOWN / _UP). */
    private static void multi(View v, int action, int actionIndex, float[] xs, float[] ys) {
        int n = xs.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = ys[i];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        long t = SystemClock.uptimeMillis();
        int a = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        MotionEvent e = MotionEvent.obtain(t, t, a, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void idle(long ms) { ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(ms)); }

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

    @Test
    public void accentsEverywhere() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        // Pulsacion larga: o da o o o o o o oe, c da c con cedilla, s da eszett
        String o = Arrays.toString(kb.keyFor("o").alts);
        for (String ch : new String[]{"\u00f3", "\u00f2", "\u00f6", "\u00f4", "\u00f5", "\u00f8", "\u0153"}) check(o.contains(ch), "falta " + ch + " en la o: " + o);
        check("\u00e7".equals(kb.keyFor("c").alts[0]), "c con cedilla");
        check("\u00df".equals(kb.keyFor("s").alts[0]), "eszett en la s");
        // Pagina 3/3: todas las letras con acento a la vista; shift las escribe en mayuscula
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        layout(kb, W);
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_PAGE));
        layout(kb, W);
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_PAGE));
        layout(kb, W);
        for (String ch : new String[]{"\u00e0", "\u00e2", "\u00e4", "\u00e7", "\u00f2", "\u00f4", "\u00f6", "\u00fc", "\u00df"}) check(kb.keyFor(ch) != null, "pagina 3/3 sin " + ch);
        rec.reset();
        tap(kb, kb.keyFor("\u00f6"));
        check(rec.typed.toString(), "\u00f6", "escribe o con dieresis");
        save(render(kb, W), "gestos_acentos.png");
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_PAGE));
        layout(kb, W);
        check(kb.keyFor("\u00f6") == null && kb.keyFor("+") != null, "3/3 vuelve a la pagina 1");
    }

    @Test
    public void popupBaseLetterAndCancel() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);

        // Mantener "e": acentos y, de ultima celda, la propia e; soltar ahi escribe "e"
        AurebeshKeyboardView.Key e = kb.keyFor("e");
        down(kb, e.rect.centerX(), e.rect.centerY());
        idle(450);
        String[] opts = kb.popupOptions();
        check(opts != null, "menu de la e abierto");
        check(Arrays.asList(opts).toString(), "[é, è, ë, ê, e]", "opciones de la e");
        save(render(kb, W), "gestos_menu_e.png");
        RectF last = kb.popupCellRect(opts.length - 1);
        move(kb, last.centerX(), last.centerY());
        up(kb, last.centerX(), last.centerY());
        check(rec.typed.toString(), "e", "letra base desde el menu");
        rec.reset();

        // Mantener "a" y bajar el dedo mas de media fila por debajo de la tecla: el menu se cierra y no se escribe nada
        AurebeshKeyboardView.Key a = kb.keyFor("a");
        down(kb, a.rect.centerX(), a.rect.centerY());
        idle(450);
        check(kb.popupOptions() != null, "menu de la a abierto");
        float farY = a.rect.bottom + a.rect.height();
        move(kb, a.rect.centerX(), farY);
        check(kb.popupOptions() == null, "menu cerrado al alejarse");
        up(kb, a.rect.centerX(), farY);
        check(rec.typed.toString(), "", "alejarse del menu no escribe");
        rec.reset();

        // Alejarse mas de una tecla por el lado tambien cancela
        AurebeshKeyboardView.Key o = kb.keyFor("o");
        down(kb, o.rect.centerX(), o.rect.centerY());
        idle(450);
        RectF first = kb.popupCellRect(0);
        move(kb, first.left - o.rect.width() * 3f, first.centerY());
        up(kb, first.left - o.rect.width() * 3f, first.centerY());
        check(rec.typed.toString(), "", "alejarse de lado no escribe");
        rec.reset();

        // Un toque normal sigue escribiendo
        tap(kb, e);
        check(rec.typed.toString(), "e", "toque normal");
    }

    @Test
    public void punctuationOnHold() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);

        // Espanol: el punto ofrece ? ¿ ! ¡ : ; - " ' @ (diez celdas: dos filas en un telefono); soltar sin mover da ?
        AurebeshKeyboardView.Key dot = kb.keyFor(".");
        down(kb, dot.rect.centerX(), dot.rect.centerY());
        idle(450);
        String[] opts = kb.popupOptions();
        check(opts != null && opts.length == 10, "diez alternativas del punto");
        check(opts[0], "?", "primera alternativa del punto");
        check(opts[1], "¿", "segunda alternativa del punto");
        check(opts[9], "@", "ultima alternativa del punto");
        RectF c0 = kb.popupCellRect(0), c5 = kb.popupCellRect(5);
        check(c5.bottom <= c0.top + 1f, "segunda fila del menu por encima de la primera");
        near("misma columna en las dos filas", c5.left, c0.left);
        save(render(kb, W), "gestos_menu_punto.png");
        up(kb, dot.rect.centerX(), dot.rect.centerY());
        check(rec.typed.toString(), "?", "mantener el punto da ?");
        rec.reset();

        // Deslizar hasta la celda de ! (indice 2) y soltar
        down(kb, dot.rect.centerX(), dot.rect.centerY());
        idle(450);
        RectF c2 = kb.popupCellRect(2);
        move(kb, c2.centerX(), c2.centerY());
        up(kb, c2.centerX(), c2.centerY());
        check(rec.typed.toString(), "!", "celda de la exclamacion");
        rec.reset();

        // Y una celda de la fila de arriba (indice 7: ")
        down(kb, dot.rect.centerX(), dot.rect.centerY());
        idle(450);
        RectF c7 = kb.popupCellRect(7);
        move(kb, c7.centerX(), c7.centerY());
        up(kb, c7.centerX(), c7.centerY());
        check(rec.typed.toString(), "\"", "celda de la fila superior");
        rec.reset();

        // La coma en espanol: ¿ ¡ ; : y al soltar sin mover ¿
        AurebeshKeyboardView.Key comma = kb.keyFor(",");
        down(kb, comma.rect.centerX(), comma.rect.centerY());
        idle(450);
        check(Arrays.asList(kb.popupOptions()).toString(), "[¿, ¡, ;, :]", "alternativas de la coma");
        up(kb, comma.rect.centerX(), comma.rect.centerY());
        check(rec.typed.toString(), "¿", "mantener la coma da ¿");
        rec.reset();

        // Ingles y aleman
        kb.setLanguage("en");
        layout(kb, W);
        dot = kb.keyFor(".");
        down(kb, dot.rect.centerX(), dot.rect.centerY());
        idle(450);
        check(Arrays.asList(kb.popupOptions()).toString(), "[?, !, ,, :, ;, -, \", ', @]", "punto en ingles");
        up(kb, dot.rect.centerX(), dot.rect.centerY());
        comma = kb.keyFor(",");
        down(kb, comma.rect.centerX(), comma.rect.centerY());
        idle(450);
        check(Arrays.asList(kb.popupOptions()).toString(), "[!]", "coma en ingles");
        up(kb, comma.rect.centerX(), comma.rect.centerY());
        kb.setLanguage("de");
        layout(kb, W);
        dot = kb.keyFor(".");
        down(kb, dot.rect.centerX(), dot.rect.centerY());
        idle(450);
        check(Arrays.asList(kb.popupOptions()).toString(), "[?, !, :, ;, -, \", ', @]", "punto en aleman");
        up(kb, dot.rect.centerX(), dot.rect.centerY());
        check(rec.typed.toString(), "?!?", "lo escrito al mantener en ingles y aleman");
        // Tocar el punto y la coma sigue escribiendo el signo
        rec.reset();
        tap(kb, kb.keyFor("."));
        tap(kb, kb.keyFor(","));
        check(rec.typed.toString(), ".,", "toque normal del punto y la coma");
    }

    @Test
    public void shiftHeldWithTwoFingers() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key shift = kb.keyFor(AurebeshKeyboardView.CODE_SHIFT);
        AurebeshKeyboardView.Key h = kb.keyFor("h"), o = kb.keyFor("o"), l = kb.keyFor("l");
        float sx = shift.rect.centerX(), sy = shift.rect.centerY();

        // Dedo 0 mantiene mayusculas; dedo 1 teclea h y o; al soltar mayusculas no queda activa
        down(kb, sx, sy);
        multi(kb, MotionEvent.ACTION_POINTER_DOWN, 1, new float[]{sx, h.rect.centerX()}, new float[]{sy, h.rect.centerY()});
        multi(kb, MotionEvent.ACTION_POINTER_UP, 1, new float[]{sx, h.rect.centerX()}, new float[]{sy, h.rect.centerY()});
        multi(kb, MotionEvent.ACTION_POINTER_DOWN, 1, new float[]{sx, o.rect.centerX()}, new float[]{sy, o.rect.centerY()});
        multi(kb, MotionEvent.ACTION_POINTER_UP, 1, new float[]{sx, o.rect.centerX()}, new float[]{sy, o.rect.centerY()});
        check(rec.typed.toString(), "HO", "letras con mayusculas sostenida");
        kb.setAutoShift(false);                         // el IME no puede apagarla mientras se mantiene
        up(kb, sx, sy);
        tap(kb, l);
        check(rec.typed.toString(), "HOl", "al soltar mayusculas no conmuta");
        rec.reset();

        // Mantener mayusculas sin teclear nada y soltar: conmuta como un toque normal
        down(kb, sx, sy);
        up(kb, sx, sy);
        tap(kb, h);
        tap(kb, o);
        check(rec.typed.toString(), "Ho", "toque de mayusculas: una letra");
        rec.reset();

        // Deslizar desde mayusculas hasta una letra: esa letra en mayuscula, una sola
        down(kb, sx, sy);
        move(kb, h.rect.centerX(), h.rect.centerY());
        up(kb, h.rect.centerX(), h.rect.centerY());
        tap(kb, o);
        check(rec.typed.toString(), "Ho", "deslizar desde mayusculas");
    }

    @Test
    public void spaceSlide() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key space = kb.keyFor(AurebeshKeyboardView.CODE_SPACE);
        float cx = space.rect.centerX(), cy = space.rect.centerY();

        // Modo cursor: 5 pasos a la derecha, 2 de vuelta; al soltar no hay espacio
        kb.setSpaceSlide(AurebeshKeyboardView.SPACE_CURSOR);
        save(render(kb, W), "gestos_espacio_chevrones.png");
        down(kb, cx, cy);
        move(kb, cx + 5 * 12 * dp + 3, cy);
        check(rec.cursorMoves == 5, "cinco pasos de cursor: " + rec.events);
        move(kb, cx + 3 * 12 * dp + 3, cy);
        check(rec.cursorMoves == 3, "dos pasos de vuelta: " + rec.events);
        idle(1000);                                      // la pulsacion larga del espacio quedo cancelada
        up(kb, cx + 3 * 12 * dp + 3, cy);
        check(rec.typed.toString(), "", "deslizar el espacio no escribe espacio");
        check(!rec.events.contains("switchkb"), "sin selector de teclados tras deslizar");
        rec.reset();

        // Con mayusculas activa los pasos van con seleccion
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_SHIFT));
        down(kb, cx, cy);
        move(kb, cx - 2 * 12 * dp - 3, cy);
        up(kb, cx - 2 * 12 * dp - 3, cy);
        check(rec.events.toString(), "[cur-1s, cur-1s]", "pasos con seleccion");
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_SHIFT));
        rec.reset();

        // Un toque normal sigue escribiendo el espacio
        tap(kb, space);
        check(rec.typed.toString(), " ", "toque en el espacio");
        rec.reset();

        // Modo idioma: pasada una tecla de ancho cambia de idioma una vez por gesto, sin escribir espacio
        kb.setSpaceSlide(AurebeshKeyboardView.SPACE_LANG);
        float unit = kb.keyFor("q").rect.width();
        down(kb, cx, cy);
        move(kb, cx + unit * 0.5f, cy);
        check(rec.langSwitches == 0, "media tecla no cambia de idioma");
        move(kb, cx + unit * 1.3f, cy);
        move(kb, cx + unit * 2.5f, cy);
        up(kb, cx + unit * 2.5f, cy);
        check(rec.langSwitches == 1, "un cambio de idioma por gesto: " + rec.langSwitches);
        check(rec.typed.toString(), "", "cambiar de idioma no escribe espacio");
        check(rec.cursorMoves == 0, "sin pasos de cursor en modo idioma");
        rec.reset();

        // Sin gesto: deslizar no hace nada y al soltar se escribe el espacio como siempre
        kb.setSpaceSlide(AurebeshKeyboardView.SPACE_NONE);
        down(kb, cx, cy);
        move(kb, cx + unit * 2f, cy);
        up(kb, cx + unit * 2f, cy);
        check(rec.typed.toString(), " ", "modo sin gesto");
        check(rec.langSwitches == 0 && rec.cursorMoves == 0, "modo sin gesto no mueve nada");
    }

    @Test
    public void releaseOutsideDoesNotType() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key h = kb.keyFor("h"), j = kb.keyFor("j");

        // Subir hasta la cinta y soltar
        down(kb, h.rect.centerX(), h.rect.centerY());
        move(kb, h.rect.centerX(), 2 * dp);
        up(kb, h.rect.centerX(), 2 * dp);
        check(rec.typed.toString(), "", "soltar en la cinta no escribe");

        // Bajar por debajo del teclado y soltar
        float below = kb.getMeasuredHeight() + 40 * dp;
        down(kb, j.rect.centerX(), j.rect.centerY());
        move(kb, j.rect.centerX(), below);
        up(kb, j.rect.centerX(), below);
        check(rec.typed.toString(), "", "soltar bajo el teclado no escribe");

        // Volver a las teclas despues de salir no revive la pulsacion
        down(kb, h.rect.centerX(), h.rect.centerY());
        move(kb, h.rect.centerX(), 2 * dp);
        move(kb, j.rect.centerX(), j.rect.centerY());
        up(kb, j.rect.centerX(), j.rect.centerY());
        check(rec.typed.toString(), "", "volver tras salir no escribe");

        // Deslizar a la tecla vecina sigue corrigiendo el toque
        down(kb, h.rect.centerX(), h.rect.centerY());
        move(kb, j.rect.centerX(), j.rect.centerY());
        up(kb, j.rect.centerX(), j.rect.centerY());
        check(rec.typed.toString(), "j", "deslizar a la vecina");
    }

    @Test
    public void deleteAccelerates() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key del = kb.keyFor(AurebeshKeyboardView.CODE_DELETE);
        down(kb, del.rect.centerX(), del.rect.centerY());
        check(rec.deletes == 1, "borrar al pulsar");
        idle(1000);
        check(rec.deletes > 8 && rec.deleteWords == 0, "repeticion por letras al segundo: " + rec.deletes);
        idle(1600);
        check(rec.deletes >= 30, "treinta letras antes de pasar a palabras: " + rec.deletes);
        check(rec.deleteWords >= 1, "borrar palabras pasados ~2 s: " + rec.deleteWords);
        int words = rec.deleteWords, letters = rec.deletes;
        idle(600);
        check(rec.deleteWords >= words + 3 && rec.deletes == letters, "cada 150 ms una palabra, sin letras sueltas");
        up(kb, del.rect.centerX(), del.rect.centerY());
        words = rec.deleteWords;
        idle(1000);
        check(rec.deleteWords == words, "al soltar se para");
    }

    @Test
    public void oneHandOnWideScreen() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        int wide = 2176;
        AurebeshKeyboardView kb = view(app, wide);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        kb.setSplitMode(2);
        kb.setHandMode(2);
        save(render(kb, wide), "gestos_una_mano_fold.png");
        float km = kb.keyMargin();
        check(!kb.isSplit(), "a una mano no se divide");
        near("Fold a una mano: caja de 400 dp pegada a la derecha", kb.keyFor("q").rect.left, wide - 400 * dp + km);
        near("Fold a una mano: p al margen derecho", kb.keyFor("p").rect.right, wide - km);
        // La cinta acompana a la caja: el icono de una mano al borde derecho y la inteligencia al principio de la caja
        tap(kb, wide - 28 * dp, 20 * dp);
        tap(kb, wide - 400 * dp + 28 * dp, 20 * dp);
        check(rec.typed.toString(), "<tool4><tool5>", "cinta sobre la caja: " + rec.typed);
        rec.reset();
        kb.setHandMode(1);
        layout(kb, wide);
        near("Fold a una mano izq.: q al margen", kb.keyFor("q").rect.left, km);
        near("Fold a una mano izq.: p al borde de la caja", kb.keyFor("p").rect.right, 400 * dp - km);
        // En un telefono sigue siendo el 78 % del ancho
        AurebeshKeyboardView phone = view(app, W);
        phone.setHandMode(1);
        layout(phone, W);
        near("telefono a una mano: 78 %", phone.keyFor("p").rect.right, 0.78f * W - phone.keyMargin());
    }

    @Test
    public void numpadPhoneAndDecimal() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);

        // Telefono: + * # y pausa, borrar e intro; cuatro filas iguales de 58 dp
        kb.showNumpad(true, false, false);
        layout(kb, W);
        check(kb.isNumpad(), "numerico activo");
        for (String k : new String[]{"1", "5", "0", "*", "#", "+", ","}) check(kb.keyFor(k) != null, "telefono sin " + k);
        check(kb.keyFor(AurebeshKeyboardView.CODE_DELETE) != null && kb.keyFor(AurebeshKeyboardView.CODE_ENTER) != null, "borrar e intro");
        near("fila de 58 dp", kb.keyFor("1").rect.height(), 58 * dp);
        near("la primera fila no es la franja de numeros", kb.keyFor("1").rect.height(), kb.keyFor("4").rect.height());
        check(kb.keyFor("1").rect.width() > 1.3f * kb.keyFor("1").rect.height(), "teclas anchas: 4 columnas llenan el ancho");
        save(render(kb, W), "gestos_numpad_telefono.png");
        tap(kb, kb.keyFor("5"));
        tap(kb, kb.keyFor("#"));
        tap(kb, kb.keyFor(","));
        check(rec.typed.toString(), "5#,", "teclear en el numerico");
        rec.reset();
        tap(kb, W - 28 * dp, 20 * dp);                 // la cinta es la de ?123: el ultimo icono es ajustes
        check(rec.typed.toString(), "<tool3>", "cinta del numerico: " + rec.typed);
        rec.reset();

        // Decimal con signo: separador del idioma (coma en espanol), − y +, sin * ni #; intro alto
        kb.showNumpad(false, true, true);
        layout(kb, W);
        check(kb.keyFor(",") != null && kb.keyFor("-") != null && kb.keyFor("+") != null, "decimal con signo");
        check(kb.keyFor("*") == null && kb.keyFor("#") == null, "sin teclas de telefono");
        near("intro de dos filas", kb.keyFor(AurebeshKeyboardView.CODE_ENTER).rect.height(), 2 * kb.keyFor("1").rect.height());
        save(render(kb, W), "gestos_numpad_decimal.png");
        tap(kb, kb.keyFor("3"));
        tap(kb, kb.keyFor(","));
        tap(kb, kb.keyFor("1"));
        check(rec.typed.toString(), "3,1", "decimal");
        rec.reset();

        // Entero sin signo: ni separador ni signos; en ingles el separador decimal es el punto
        kb.showNumpad(false, false, false);
        layout(kb, W);
        check(kb.keyFor(",") == null && kb.keyFor(".") == null && kb.keyFor("-") == null, "entero sin signo");
        kb.setLanguage("en");
        kb.showNumpad(false, true, false);
        layout(kb, W);
        check(kb.keyFor(".") != null && kb.keyFor(",") == null, "separador decimal ingles");
        kb.setLanguage("es");

        // Volver a letras
        kb.resetToLetters();
        layout(kb, W);
        check(!kb.isNumpad() && kb.keyFor("q") != null, "vuelta a letras");
    }

    @Test
    public void emailAndUrlFields() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);

        kb.setFieldVariant(AurebeshKeyboardView.FIELD_EMAIL);
        layout(kb, W);
        AurebeshKeyboardView.Key at = kb.keyFor("@"), com = kb.keyFor(".com");
        check(at != null && com != null, "correo: @ y .com");
        check(kb.keyFor(",") == null, "correo: la coma deja sitio a @");
        AurebeshKeyboardView.Key space = kb.keyFor(AurebeshKeyboardView.CODE_SPACE);
        check(space.rect.width() < 4 * at.rect.width(), "el espacio cede ancho a .com");
        near(".com de tecla y media", com.rect.width(), 1.5f * at.rect.width());
        save(render(kb, W), "gestos_correo.png");
        tap(kb, at);
        tap(kb, com);
        check(rec.typed.toString(), "@.com", "teclear @ y .com");
        rec.reset();
        down(kb, com.rect.centerX(), com.rect.centerY());
        idle(450);
        check(Arrays.asList(kb.popupOptions()).toString(), "[.es, .mx, .org, .net]", "dominios al mantener .com");
        up(kb, com.rect.centerX(), com.rect.centerY());
        check(rec.typed.toString(), ".es", "primer dominio");
        rec.reset();
        down(kb, at.rect.centerX(), at.rect.centerY());
        idle(450);
        check(Arrays.asList(kb.popupOptions()).toString(), "[,, ¿]", "coma y apertura al mantener @");
        up(kb, at.rect.centerX(), at.rect.centerY());
        check(rec.typed.toString(), ",", "coma desde @");
        rec.reset();

        kb.setFieldVariant(AurebeshKeyboardView.FIELD_URL);
        layout(kb, W);
        check(kb.keyFor("/") != null && kb.keyFor(".com") != null && kb.keyFor("@") == null, "web: / y .com");
        save(render(kb, W), "gestos_web.png");

        kb.setFieldVariant(AurebeshKeyboardView.FIELD_NONE);
        layout(kb, W);
        check(kb.keyFor(",") != null && kb.keyFor(".com") == null && kb.keyFor("/") == null, "campo normal");
        tap(kb, kb.keyFor(","));
        check(rec.typed.toString(), ",", "coma de vuelta");
    }
}
