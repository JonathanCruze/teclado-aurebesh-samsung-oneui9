package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tamano y posicion (1.10): filas por clase de alto de la pantalla (tapa del Z Flip, telefono apaisado, normal),
 * margen inferior con el boton ∨ de ocultar, tope de ancho de tecla en pantallas anchas, dividido automatico solo
 * entre 600 y 999 dp, y el modo de ajuste con tiradores y botones Listo / Restablecer.
 * Con el calificador xxhdpi de Robolectric la densidad es 3: 1080 px son 360 dp y 2176 px (Fold) son 725 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SizeTest {

    private static final int W = 1080;
    private static final String PHONE = "es-rES-w411dp-h914dp-xxhdpi";

    /** Oyente que apunta todo lo que llega de la vista. */
    static final class Recorder implements AurebeshKeyboardView.Listener {
        final StringBuilder typed = new StringBuilder();
        final List<String> events = new ArrayList<>();

        public void onText(String s) { typed.append(s); events.add("text:" + s); }
        public void onDelete() { events.add("del"); }
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

        void reset() { typed.setLength(0); events.clear(); }
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

    /** Arrastre vertical desde el centro de {@code from}: bajar, moverse en pasos de 6 dp y soltar a {@code dyDp} dp. */
    private static void dragVertical(AurebeshKeyboardView kb, int w, RectF from, float dyDp, float dp) {
        float x = from.centerX(), y = from.centerY();
        down(kb, x, y);
        int steps = Math.max(1, Math.round(Math.abs(dyDp) / 6f));
        for (int i = 1; i <= steps; i++) {
            move(kb, x, y + dyDp * dp * i / steps);
            layout(kb, w);                          // como en el dispositivo: cada cambio vuelve a medir la vista
        }
        up(kb, x, y + dyDp * dp);
        layout(kb, w);
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void check(String got, String want, String what) {
        if (!want.equals(got)) throw new AssertionError(what + ": " + got + " (esperado " + want + ")");
    }

    private static void near(String what, float actual, float expected) {
        if (Math.abs(actual - expected) > 2.5f) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }

    /** Teclado con la fila de numeros activada, sin vibracion ni sonido, al 100 %, ya medido a {@code w} px. */
    private static AurebeshKeyboardView view(Application app, int w) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);
        kb.setEnterAction(4);
        layout(kb, w);
        return kb;
    }

    @Test
    public void rowsByScreenHeight() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;

        // Tapa del Z Flip (360 x 374 dp): filas de 34 dp, cinta de 32 dp y sin fila de numeros aunque este activada
        RuntimeEnvironment.setQualifiers("es-rES-w360dp-h374dp-xxhdpi");
        try {
            AurebeshKeyboardView kb = view(app, W);
            kb.setListener(new Recorder());                  // sin oyente las teclas de funcion no hacen nada (como en el IME real siempre lo hay)
            save(render(kb, W), "tamano_flip_tapa.png");
            check(kb.keyFor("1") == null, "tapa del Flip: sin fila de numeros");
            AurebeshKeyboardView.Key q = kb.keyFor("q");
            check(q.alts != null && "1".equals(q.alts[0]), "tapa del Flip: q da 1 al mantener");
            near("tapa del Flip: filas de 34 dp", q.rect.height(), 34 * dp);
            near("tapa del Flip: cinta de 32 dp", q.rect.top, 32 * dp);
            near("tapa del Flip: alto total", kb.getMeasuredHeight(), (32 + 4 * 34 + 6) * dp);
            // En ?123 la fila de digitos sigue (no hay otra forma de escribir numeros), a 28 dp
            tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
            layout(kb, W);
            AurebeshKeyboardView.Key one = kb.keyFor("1");
            check(one != null, "tapa del Flip: digitos en ?123");
            near("tapa del Flip: fila de digitos de 28 dp", one.rect.height(), 28 * dp);
            save(render(kb, W), "tamano_flip_tapa_simbolos.png");
            // El alto elegido no amplia en pantallas bajas (130 % sigue en 34 dp) pero si reduce
            kb.setHeightPct(130);
            layout(kb, W);
            near("tapa del Flip: el 130 % no amplia", kb.keyFor("+").rect.height(), 34 * dp);
            kb.setHeightPct(85);
            layout(kb, W);
            near("tapa del Flip: el 85 % reduce", kb.keyFor("+").rect.height(), 34 * 0.85f * dp);
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }

        // Telefono apaisado con 500 dp de alto: 38/30 dp como hasta ahora, con fila de numeros y cinta de 40 dp
        RuntimeEnvironment.setQualifiers("es-rES-w914dp-h500dp-land-xxhdpi");
        try {
            AurebeshKeyboardView land = view(app, 2340);
            save(render(land, 2340), "tamano_apaisado_500dp.png");
            AurebeshKeyboardView.Key one = land.keyFor("1");
            check(one != null, "apaisado: fila de numeros");
            near("apaisado: digitos de 30 dp", one.rect.height(), 30 * dp);
            near("apaisado: filas de 38 dp", land.keyFor("q").rect.height(), 38 * dp);
            near("apaisado: cinta de 40 dp", one.rect.top, 40 * dp);
            land.setHeightPct(130);
            layout(land, 2340);
            near("apaisado: el 130 % no amplia", land.keyFor("q").rect.height(), 38 * dp);
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }

        // Pantalla normal: 58/46 dp y el alto elegido entero, tambien por encima del 100 %
        AurebeshKeyboardView kb = view(app, W);
        near("normal: filas de 58 dp", kb.keyFor("q").rect.height(), 58 * dp);
        near("normal: digitos de 46 dp", kb.keyFor("1").rect.height(), 46 * dp);
        kb.setHeightPct(130);
        layout(kb, W);
        check(kb.heightPct() == 130, "heightPct tras setHeightPct: " + kb.heightPct());
        near("normal: 130 %", kb.keyFor("q").rect.height(), 58 * 1.3f * dp);
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 0.85f);   // applyPrefs sigue mandando
        layout(kb, W);
        check(kb.heightPct() == 85, "heightPct tras applyPrefs: " + kb.heightPct());
    }

    @Test
    public void bottomMarginAndHideButton() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        int base = kb.getMeasuredHeight();                   // cinta + filas + 6 dp

        kb.setBottomPad(10 * dp);
        render(kb, W);
        near("margen de 10 dp en vez de los 6 fijos", kb.getMeasuredHeight(), base - 6 * dp + 10 * dp);
        check(kb.hideButtonRect().isEmpty(), "con 10 dp no hay boton ∨");
        check(kb.bottomDp() == 10, "bottomDp: " + kb.bottomDp());

        kb.setBottomPad(30 * dp);
        save(render(kb, W), "tamano_margen_30.png");
        near("margen de 30 dp", kb.getMeasuredHeight(), base - 6 * dp + 30 * dp);
        RectF hide = kb.hideButtonRect();
        check(!hide.isEmpty(), "con 30 dp hay boton ∨");
        float keysBottom = kb.keyFor(".").rect.bottom;
        check(hide.left < 50 * dp && hide.top >= keysBottom - 1 && hide.bottom <= kb.getMeasuredHeight() + 1,
                "el ∨ va a la izquierda de la banda inferior: " + hide);
        tap(kb, hide.centerX(), hide.centerY());
        check(rec.events.toString(), "[hide]", "toque en ∨");
        check(rec.typed.length() == 0, "el ∨ no escribe: " + rec.typed);
        // El centro y la derecha de la banda quedan libres: tocar ahi no oculta ni escribe
        tap(kb, W / 2f, kb.getMeasuredHeight() - 4 * dp);
        tap(kb, W - 20 * dp, kb.getMeasuredHeight() - 4 * dp);
        check(rec.events.size() == 1 && rec.typed.length() == 0, "banda libre: " + rec.events + " " + rec.typed);

        // Sin margen vuelven los 6 dp y desaparece el ∨
        kb.setBottomPad(0);
        render(kb, W);
        near("sin margen: 6 dp", kb.getMeasuredHeight(), base);
        check(kb.hideButtonRect().isEmpty(), "sin margen no hay ∨");
    }

    @Test
    public void keyWidthCap() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;

        // Fold desplegado sin dividir (2176 px = 725 dp): (725 - 2 x 36) / 10 = 65 dp, por debajo del tope: como hasta ahora
        AurebeshKeyboardView fold = view(app, 2176);
        fold.setSplitMode(2);
        layout(fold, 2176);
        float km = fold.keyMargin();
        near("Fold sin dividir: sin tope", fold.keyFor("q").rect.width(), (2176 - 2 * km) / 10f);
        check(fold.keyFor("q").rect.width() < 72 * dp, "Fold: por debajo de 72 dp");
        near("Fold sin dividir: q al margen", fold.keyFor("q").rect.left, km);

        // Tableta apaisada (2560 px = 853 dp): tope de 72 dp por tecla y bloque centrado
        AurebeshKeyboardView tab = view(app, 2560);
        tab.setSplitMode(2);
        save(render(tab, 2560), "tamano_tope_72dp.png");
        AurebeshKeyboardView.Key q = tab.keyFor("q"), p = tab.keyFor("p");
        AurebeshKeyboardView.Key shift = tab.keyFor(AurebeshKeyboardView.CODE_SHIFT);
        near("tableta: tecla de 72 dp", q.rect.width(), 72 * dp);
        near("tableta: bloque centrado", q.rect.left, (2560 - 10 * 72 * dp) / 2f);
        near("tableta: p simetrica", 2560 - p.rect.right, q.rect.left);
        near("tableta: mayusculas alineada con q", shift.rect.left, q.rect.left);
        near("tableta: mayusculas de 1,5 teclas", shift.rect.width(), 1.5f * 72 * dp);
        near("tableta: ?123 alineado", tab.keyFor(AurebeshKeyboardView.CODE_MODE).rect.left, q.rect.left);

        // Dividido: el ancho de cada mitad es el del contrato (setSplitBox) y la geometria de 1.9.2 sigue
        AurebeshKeyboardView split = view(app, 2176);
        split.setSplitMode(1);
        layout(split, 2176);
        float sm = split.keyMargin();
        near("dividido 37,5 %: borde interior izquierdo al 42,5 %", split.keyFor("g").rect.right, 2176 * 0.425f);
        near("dividido 37,5 %: borde interior derecho al 57,5 %", split.keyFor("h").rect.left, 2176 * 0.575f);
        split.setSplitBox(0.42f);
        layout(split, 2176);
        near("dividido 42 %: mitad de 0,42 del ancho", split.keyFor("g").rect.right, sm + 2176 * 0.42f);
        near("dividido 42 %: tecla de 0,42 / 5,5", split.keyFor("q").rect.width(), 2176 * 0.42f / 5.5f);
        near("dividido 42 %: p al margen derecho", split.keyFor("p").rect.right, 2176 - sm);
        split.setSplitBox(0.33f);
        layout(split, 2176);
        near("dividido 33 %: mitad de 0,33 del ancho", split.keyFor("g").rect.right, sm + 2176 * 0.33f);
        near("dividido 33 %: h pegada a su mitad", split.keyFor("h").rect.left, 2176 - sm - 2176 * 0.33f);
        split.setSplitBox(0.375f);

        // 3000 px (1000 dp) dividido: manda el tope de 64 dp y cada mitad sigue pegada a su borde
        AurebeshKeyboardView wide = view(app, 3000);
        wide.setSplitMode(1);
        save(render(wide, 3000), "tamano_dividido_tope.png");
        float wm = wide.keyMargin();
        check(3000 * 0.375f / 5.5f > 64 * dp, "a 1000 dp la mitad del 37,5 % superaria los 64 dp por tecla");
        near("tope dividido: tecla de 64 dp", wide.keyFor("q").rect.width(), 64 * dp);
        near("tope dividido: q al margen", wide.keyFor("q").rect.left, wm);
        near("tope dividido: p al margen derecho", wide.keyFor("p").rect.right, 3000 - wm);
        near("tope dividido: mitad de 5,5 teclas", wide.keyFor("g").rect.right, wm + 5.5f * 64 * dp);
        near("tope dividido: h pegada al hueco", wide.keyFor("h").rect.left, 3000 - wm - 5.5f * 64 * dp);
        near("tope dividido: y media tecla hacia fuera", wide.keyFor("y").rect.left, 3000 - wm - 5f * 64 * dp);
        near("tope dividido: a escalonada", wide.keyFor("a").rect.left, wm + 32 * dp);
    }

    @Test
    public void autoSplitByScreenWidth() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();

        // Tableta apaisada o DeX (1000 dp de ancho): "automatico" no divide; "siempre" si
        RuntimeEnvironment.setQualifiers("es-rES-sw600dp-w1000dp-h600dp-land-xxhdpi");
        try {
            AurebeshKeyboardView tab = view(app, 3000);
            check(!tab.isSplit(), "tableta de 1000 dp: automatico no divide");
            check(tab.keyFor("t").rect.right > tab.keyFor("y").rect.left - 5, "tableta: sin hueco central");
            tab.setSplitMode(1);
            layout(tab, 3000);
            check(tab.isSplit(), "tableta: siempre divide");
            tab.setSplitMode(0);
            layout(tab, 3000);
            check(!tab.isSplit(), "tableta: de vuelta a automatico no divide");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }

        // Fold desplegado (ancho menor de 600 dp o mas y menos de 1000 dp): automatico divide
        RuntimeEnvironment.setQualifiers("es-rES-sw600dp-w900dp-h600dp-land-xxhdpi");
        try {
            AurebeshKeyboardView fold = view(app, 2700);
            check(fold.isSplit(), "Fold desplegado: automatico divide");
            check(fold.keyFor("t").rect.right < fold.keyFor("y").rect.left - 60, "Fold: hueco central");
            fold.setSplitMode(2);
            layout(fold, 2700);
            check(!fold.isSplit(), "Fold: nunca");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }

        // Telefono: automatico no divide
        AurebeshKeyboardView phone = view(app, W);
        check(!phone.isSplit(), "telefono: automatico no divide");
    }

    @Test
    public void resizeMode() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        float rows100 = (46 + 4 * 58) * dp;                  // filas al 100 % con la fila de numeros

        // El icono de tamano va en la cinta de ?123, antes del engrane, y abre el modo aunque el oyente no lo haga
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        kb.setToolbarItems(new int[]{AurebeshKeyboardView.TOOL_RESIZE});   // en la cinta de fabrica el tamano vive en la cuadricula "..."
        layout(kb, W);
        render(kb, W);                                       // los rectangulos de la cinta se fijan al dibujar
        tap(kb, W - 72 * dp, 20 * dp);
        check(rec.typed.toString(), "<tool" + AurebeshKeyboardView.TOOL_RESIZE + ">", "icono de tamano");
        check(kb.isResizeMode(), "el icono abre el modo de ajuste");
        rec.reset();
        save(render(kb, W), "tamano_ajuste.png");

        // En el modo nada escribe: ni las teclas, ni la cinta, ni ?123
        tap(kb, kb.keyFor("1"));
        tap(kb, W - 28 * dp, 20 * dp);
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        check(rec.typed.length() == 0 && rec.events.isEmpty(), "en el modo de ajuste nada escribe: " + rec.events + rec.typed);
        check(kb.keyFor("1") != null, "sigue en ?123");

        // Cancelar desde fuera devuelve a las letras lo que habia al entrar y permite teclear
        kb.setResizeMode(false);
        check(!kb.isResizeMode() && kb.heightPct() == 100 && kb.bottomDp() == 0, "cancelado sin cambios");
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        layout(kb, W);
        check(kb.keyFor("q") != null, "de vuelta a las letras");
        kb.setResizeMode(true);
        render(kb, W);

        // Tirador superior: 60 dp hacia arriba agranda el alto en proporcion al alto de las filas al 100 %
        RectF top = kb.resizeHandleRect(true);
        check(!top.isEmpty() && top.top < 40 * dp + 10 * dp, "tirador superior en el borde de arriba: " + top);
        dragVertical(kb, W, top, -60, dp);
        int pct = kb.heightPct();
        int want = Math.round((rows100 + 60 * dp) / rows100 * 100f);
        check(pct == want, "alto tras arrastrar: " + pct + " (esperado " + want + ")");
        near("filas mas altas en vivo", kb.keyFor("q").rect.height(), 58 * dp * pct / 100f);
        near("alto total en vivo", kb.getMeasuredHeight(), 40 * dp + rows100 * pct / 100f + 6 * dp);
        check(kb.isResizeMode() && rec.events.isEmpty(), "arrastrar no sale del modo ni avisa");

        // Tirador inferior: 30 dp hacia arriba ponen 30 dp de margen
        render(kb, W);
        RectF bottom = kb.resizeHandleRect(false);
        check(!bottom.isEmpty() && bottom.bottom > kb.keyFor(".").rect.bottom - 1, "tirador inferior en el borde de abajo: " + bottom);
        dragVertical(kb, W, bottom, -30, dp);
        check(kb.bottomDp() == 30, "margen tras arrastrar: " + kb.bottomDp());
        near("alto total con margen", kb.getMeasuredHeight(), 40 * dp + rows100 * pct / 100f + 30 * dp);
        save(render(kb, W), "tamano_ajuste_arrastrado.png");

        // Restablecer: 100 % y 0 dp sin salir del modo
        RectF reset = kb.resizeButtonRect(false);
        check(!reset.isEmpty(), "boton Restablecer");
        tap(kb, reset.centerX(), reset.centerY());
        layout(kb, W);
        check(kb.heightPct() == 100 && kb.bottomDp() == 0 && kb.isResizeMode(), "restablecer: " + kb.heightPct() + " " + kb.bottomDp());

        // Otro arrastre y Listo: onResize con lo elegido, fuera del modo y con los valores puestos
        render(kb, W);
        dragVertical(kb, W, kb.resizeHandleRect(true), -30, dp);
        int pct2 = kb.heightPct();
        check(pct2 > 100 && pct2 < pct, "segundo arrastre: " + pct2);
        render(kb, W);
        RectF done = kb.resizeButtonRect(true);
        check(!done.isEmpty() && done.left > reset.right, "boton Listo a la derecha de Restablecer");
        tap(kb, done.centerX(), done.centerY());
        check(rec.events.toString(), "[resize" + pct2 + ",0]", "Listo avisa con alto y margen");
        check(!kb.isResizeMode(), "Listo sale del modo");
        check(kb.resizeButtonRect(true).isEmpty() && kb.resizeHandleRect(true).isEmpty(), "zonas vacias fuera del modo");
        layout(kb, W);
        near("los valores elegidos se quedan", kb.keyFor("q").rect.height(), 58 * dp * pct2 / 100f);
        rec.reset();
        tap(kb, kb.keyFor("q"));
        check(rec.typed.toString(), "q", "tras Listo se vuelve a teclear");

        // Cancelar desde fuera tras arrastrar devuelve lo que habia al entrar
        kb.setResizeMode(true);
        render(kb, W);
        dragVertical(kb, W, kb.resizeHandleRect(false), -20, dp);
        check(kb.bottomDp() == 20, "arrastre antes de cancelar: " + kb.bottomDp());
        kb.setResizeMode(false);
        layout(kb, W);
        check(kb.heightPct() == pct2 && kb.bottomDp() == 0, "cancelar restaura lo que habia al entrar");

        // Limites: 70-140 % y 0-40 dp por mucho que se arrastre
        kb.setResizeMode(true);
        render(kb, W);
        dragVertical(kb, W, kb.resizeHandleRect(true), -400, dp);
        check(kb.heightPct() == 140, "tope del 140 %: " + kb.heightPct());
        render(kb, W);
        dragVertical(kb, W, kb.resizeHandleRect(true), 400, dp);
        check(kb.heightPct() == 70, "minimo del 70 %: " + kb.heightPct());
        render(kb, W);
        dragVertical(kb, W, kb.resizeHandleRect(false), -200, dp);
        check(kb.bottomDp() == 40, "tope de 40 dp: " + kb.bottomDp());
        kb.setResizeMode(false);
    }
}
