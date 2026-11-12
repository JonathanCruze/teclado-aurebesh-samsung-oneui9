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
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vista 2.0: estela de sable de luz al deslizar (curva continua, color en vivo, se desvanece), banda unica de
 * sugerencias con celdas contiguas, cinta personalizable con "..." al final y modo aprendiendo de las letras latinas.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SaberStripTest {

    private static final int W = 1080;

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
        return snapshot(v, w);
    }

    /** Dibuja la vista tal cual esta (sin volver a medir: a mitad de un gesto). */
    private static Bitmap snapshot(View v, int w) {
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
        event(v, action, x, y, t, t);
    }

    private static void event(View v, int action, float x, float y, long down, long t) {
        MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void down(View v, float x, float y) { event(v, MotionEvent.ACTION_DOWN, x, y); }
    private static void up(View v, float x, float y) { event(v, MotionEvent.ACTION_UP, x, y); }
    private static void tap(View v, float x, float y) { down(v, x, y); up(v, x, y); }
    private static void tap(View v, AurebeshKeyboardView.Key k) { tap(v, k.rect.centerX(), k.rect.centerY()); }

    private static void idle(long ms) { ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(ms)); }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void check(String got, String want, String what) {
        if (!want.equals(got)) throw new AssertionError(what + ": " + got + " (esperado " + want + ")");
    }

    private static void near(String what, float actual, float expected) {
        if (Math.abs(actual - expected) > 2.5f) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }

    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int green(int c) { return (c >> 8) & 0xFF; }
    private static int blue(int c) { return c & 0xFF; }

    private static int pixel(Bitmap b, float x, float y) { return b.getPixel(Math.round(x), Math.round(y)); }

    /** Los dos mapas son identicos dentro del rectangulo. */
    private static boolean same(Bitmap a, Bitmap b, RectF r) {
        for (int y = (int) r.top; y < (int) r.bottom; y++) {
            for (int x = (int) r.left; x < (int) r.right; x++) if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
        }
        return true;
    }

    private AurebeshKeyboardView view(Application app, int w) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);
        kb.setEnterAction(4);
        layout(kb, w);
        return kb;
    }

    @Test
    public void saberTrail() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        kb.setGlideEnabled(true);
        check(kb.saber() == Prefs.SABER_ARGB[0], "el sable por defecto es el azul");
        kb.setSaber(Prefs.SABER_ARGB[1]);                                  // verde (Luke, Qui-Gon)
        check(kb.saber() == Prefs.SABER_ARGB[1], "setSaber cambia el color");
        Bitmap before = render(kb, W);

        // Trazo recto de la a a la l por la fila central, deprisa (mas de 0,1 dp/ms): a la tercera muestra ya es trazo
        AurebeshKeyboardView.Key a = kb.keyFor("a"), l = kb.keyFor("l");
        float y = a.rect.centerY(), x0 = a.rect.centerX(), x1 = l.rect.centerX();
        long base = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, x0, y, base, base);
        int steps = 24;
        for (int i = 1; i <= steps; i++) event(kb, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y, base, base + i * 16L);
        Bitmap mid = snapshot(kb, W);
        save(mid, "sable_verde.png");
        // Extremo delantero: destello blanco sobre la tecla bajo el dedo
        int front = pixel(mid, x1, y);
        check(red(front) > 200 && green(front) > 200 && blue(front) > 200, "destello blanco en el extremo: " + Integer.toHexString(front));
        // La hoja, a 2 dp del eje (fuera del nucleo blanco de 2,5 dp), es verde en varios puntos del trazo: continua, sin huecos
        for (int i = 2; i <= 8; i++) {
            float x = x0 + (x1 - x0) * i / 10f;
            int c = pixel(mid, x, y + 2 * dp);
            check(green(c) > red(c) + 30 && green(c) > blue(c) + 30, "hoja verde en " + i + "/10: " + Integer.toHexString(c));
        }
        // El halo, a 7 dp del eje (fuera de la hoja de 6,5 dp), tine la tecla de verde sin taparla
        int halo = pixel(mid, (x0 + x1) / 2f, y + 7 * dp);
        int plain = pixel(before, (x0 + x1) / 2f, y + 7 * dp);
        check(halo != plain && green(halo) >= red(halo), "halo translucido: " + Integer.toHexString(halo) + " vs " + Integer.toHexString(plain));
        // Cambiar el color en vivo: el mismo trazo pasa a rojo (Sith)
        kb.setSaber(Prefs.SABER_ARGB[5]);
        Bitmap red = snapshot(kb, W);
        int c = pixel(red, (x0 + x1) / 2f, y + 2 * dp);
        check(red(c) > green(c) + 30 && red(c) > blue(c) + 30, "hoja roja tras setSaber: " + Integer.toHexString(c));
        // Al soltar la estela se desvanece sola: pasado TRAIL_MS no queda nada (ni destello)
        event(kb, MotionEvent.ACTION_UP, x1, y, base, base + (steps + 1) * 16L);
        idle(AurebeshKeyboardView.TRAIL_MS + (steps + 1) * 16L + 200);     // el destello se apaga TRAIL_MS tras el ultimo punto
        Bitmap after = snapshot(kb, W);
        check(pixel(after, (x0 + x1) / 2f, y + 2 * dp) == pixel(before, (x0 + x1) / 2f, y + 2 * dp), "la hoja se desvanecio");
        check(pixel(after, x1, y) == pixel(before, x1, y), "el destello se apago");
        // Sin estela no se dibuja nada aunque haya trazo
        kb.setGlideTrail(false);
        base = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, x0, y, base, base);
        for (int i = 1; i <= steps; i++) event(kb, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y, base, base + i * 16L);
        Bitmap none = snapshot(kb, W);
        check(pixel(none, (x0 + x1) / 2f, y + 2 * dp) == pixel(before, (x0 + x1) / 2f, y + 2 * dp), "sin estela");
        event(kb, MotionEvent.ACTION_UP, x1, y, base, base + (steps + 1) * 16L);
    }

    @Test
    public void suggestionBand() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        kb.setSuggestionBar(true);
        kb.setSuggestions(Arrays.asList("\"ola\"", "hola", "sola"), "hola");         // cortas: caben las tres a partes iguales
        Bitmap b = render(kb, W);
        save(b, "sugerencias_banda.png");
        List<RectF> cells = kb.suggestionRects();
        RectF band = kb.suggestionBandRect();
        check(!band.isEmpty(), "hay banda");
        check(cells.size() == 3, "tres celdas: " + cells.size());
        // Una sola banda de stripLeft (tras el boton de inteligencia) al "..." de la derecha, tan alta como los botones
        check(band.left > 44 * dp && band.left < 70 * dp, "la banda empieza tras la inteligencia: " + band.left / dp);
        check(band.right > W - 62 * dp && band.right < W - 48 * dp, "la banda termina antes del ...: " + (W - band.right) / dp);
        near("banda de 32 dp", band.height(), 32 * dp);
        // Celdas contiguas que cubren la banda entera y se reparten el ancho a partes iguales
        near("la primera celda empieza en la banda", cells.get(0).left, band.left);
        near("la ultima celda termina en la banda", cells.get(2).right, band.right);
        for (int i = 1; i < cells.size(); i++) near("celdas contiguas " + i, cells.get(i).left, cells.get(i - 1).right);
        for (RectF r : cells) {
            near("celda tan alta como la banda (arriba)", r.top, band.top);
            near("celda tan alta como la banda (abajo)", r.bottom, band.bottom);
        }
        near("a partes iguales", cells.get(0).width(), cells.get(1).width());
        near("a partes iguales (2)", cells.get(1).width(), cells.get(2).width());
        // Un solo fondo: la celda normal es specialBg; solo la resaltada lleva su pastilla de acento; entre celdas, una linea
        check(pixel(b, cells.get(0).centerX(), band.top + 2 * dp) == Themes.ESTELAR.specialBg, "fondo de la banda");
        check(pixel(b, cells.get(2).centerX(), band.top + 2 * dp) == Themes.ESTELAR.specialBg, "fondo de la banda (3)");
        check(pixel(b, cells.get(1).centerX(), band.top + 5 * dp) == Themes.ESTELAR.accent, "pastilla de acento en la resaltada");
        check(pixel(b, cells.get(0).centerX(), band.top + 5 * dp) == Themes.ESTELAR.specialBg, "sin pastilla en las demas");
        check(pixel(b, cells.get(2).left, band.centerY()) != Themes.ESTELAR.specialBg, "linea divisoria entre celdas");
        // Toda la celda responde al toque, hasta sus bordes
        tap(kb, cells.get(0).centerX(), band.centerY());
        tap(kb, cells.get(1).left + 2 * dp, band.centerY());
        tap(kb, cells.get(2).right - 2 * dp, band.bottom - 2 * dp);
        check(rec.events.toString(), "[sug:\"ola\", sug:hola, sug:sola]", "toques en las celdas");
        rec.reset();

        // Con emojis: tambien contiguas y cerrando la banda; los emojis conservan su tamano (celda no mas estrecha que la palabra corta)
        kb.setSuggestions(Arrays.asList("sol", "sal", "🐶", "🐕"), null);        // palabras cortas: caben las cuatro con los emojis a 19 sp
        b = render(kb, W);
        save(b, "sugerencias_banda_emoji.png");
        cells = kb.suggestionRects();
        band = kb.suggestionBandRect();
        check(cells.size() == 4, "cuatro celdas con emojis: " + cells.size());
        near("primera celda en la banda", cells.get(0).left, band.left);
        near("ultima celda cierra la banda", cells.get(3).right, band.right);
        for (int i = 1; i < cells.size(); i++) near("contiguas con emojis " + i, cells.get(i).left, cells.get(i - 1).right);
        tap(kb, cells.get(2).centerX(), band.centerY());
        check(rec.events.toString(), "[sug:🐶]", "toque en el emoji");
        rec.reset();

        // Sin sugerencias no hay banda ni celdas
        kb.setSuggestions(null, null);
        render(kb, W);
        check(kb.suggestionRects().isEmpty() && kb.suggestionBandRect().isEmpty(), "sin sugerencias, sin banda");
    }

    @Test
    public void customToolbarWithMore() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        check(Arrays.equals(kb.toolbarItems(), AurebeshKeyboardView.DEFAULT_TOOLBAR), "sin orden propio, la cinta de fabrica");

        // Orden personalizado [portapapeles, emojis]: a la derecha, en ese orden, y "..." siempre el ultimo
        kb.setToolbarItems(new int[]{AurebeshKeyboardView.TOOL_CLIPBOARD, AurebeshKeyboardView.TOOL_EMOJI});
        check(Arrays.equals(kb.toolbarItems(), new int[]{2, 1}), "orden guardado");
        save(render(kb, W), "cinta_personalizada.png");
        tap(kb, W - 28 * dp, 20 * dp);                 // "..." -> onToolbar(TOOL_MORE)
        tap(kb, W - 72 * dp, 20 * dp);                 // emojis
        tap(kb, W - 116 * dp, 20 * dp);                // portapapeles
        tap(kb, W - 160 * dp, 20 * dp);                // nada
        check(rec.typed.toString(), "<tool14><tool1><tool2>", "cinta personalizada: " + rec.typed);
        check(AurebeshKeyboardView.TOOL_MORE == 14, "TOOL_MORE es 14");
        rec.reset();

        // En ?123 ya no se anaden tamano y ajustes a la fuerza: el mismo orden del usuario
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        layout(kb, W);
        render(kb, W);
        tap(kb, W - 28 * dp, 20 * dp);
        tap(kb, W - 72 * dp, 20 * dp);
        tap(kb, W - 116 * dp, 20 * dp);
        tap(kb, W - 160 * dp, 20 * dp);
        check(rec.typed.toString(), "<tool14><tool1><tool2>", "cinta en ?123: " + rec.typed);
        rec.reset();
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        layout(kb, W);

        // Filtrado: dividir no tiene sentido en un telefono (menos de 600 dp); tamano siempre puede ir
        kb.setToolbarItems(new int[]{AurebeshKeyboardView.TOOL_SPLIT, AurebeshKeyboardView.TOOL_HAND, AurebeshKeyboardView.TOOL_RESIZE});
        render(kb, W);
        tap(kb, W - 28 * dp, 20 * dp);                 // "..."
        tap(kb, W - 116 * dp, 20 * dp);                // una mano (dividir no esta)
        tap(kb, W - 160 * dp, 20 * dp);                // nada
        tap(kb, W - 72 * dp, 20 * dp);                 // tamano, el ultimo: abre el modo de ajuste y ya nada mas responde
        check(rec.typed.toString(), "<tool14><tool4><tool6>", "dividir filtrado en telefono: " + rec.typed);
        check(kb.isResizeMode(), "el icono de tamano abre el modo de ajuste");
        kb.setResizeMode(false);
        rec.reset();
        boolean[] avail = kb.availableTools();
        for (int i = 0; i < AurebeshKeyboardView.ALL_TOOLS.length; i++) {
            int id = AurebeshKeyboardView.ALL_TOOLS[i];
            boolean want = id != AurebeshKeyboardView.TOOL_SPLIT && id != AurebeshKeyboardView.TOOL_LANG;   // un idioma: sin globo
            check(avail[i] == want, "disponible " + id + ": " + avail[i]);
        }

        // Si no caben todos (pantalla estrecha de 274 dp con las once): los primeros tres y "..."
        AurebeshKeyboardView narrow = view(app, 720);
        narrow.setListener(rec);
        narrow.setToolbarItems(AurebeshKeyboardView.ALL_TOOLS);
        save(render(narrow, 720), "cinta_estrecha.png");
        tap(narrow, 720 - 28 * dp, 20 * dp);
        tap(narrow, 720 - 72 * dp, 20 * dp);
        tap(narrow, 720 - 116 * dp, 20 * dp);
        tap(narrow, 720 - 160 * dp, 20 * dp);
        tap(narrow, 720 - 204 * dp, 20 * dp);          // 240 dp de ancho: 36 dp desde el borde es el boton de inteligencia (6-50 dp)
        check(rec.typed.toString(), "<tool14><tool4><tool2><tool1><tool5>", "cinta estrecha: " + rec.typed);   // de derecha a izquierda
        rec.reset();

        // Con sugerencias, "..." sigue mostrando los iconos sobre los chips; el ultimo de estos es el "..." de la cuadricula
        kb.setToolbarItems(null);
        kb.setSuggestionBar(true);
        kb.setSuggestions(Arrays.asList("hola", "holas"), null);
        render(kb, W);
        tap(kb, W - 28 * dp, 20 * dp);
        check(rec.typed.length() == 0, "el ... sobre los chips solo destapa los iconos");
        render(kb, W);
        tap(kb, W - 28 * dp, 20 * dp);
        check(rec.typed.toString(), "<tool14>", "el ... de la cinta abre la cuadricula: " + rec.typed);
        rec.reset();
        tap(kb, W - 72 * dp, 20 * dp);
        check(rec.typed.toString(), "<tool" + AurebeshKeyboardView.DEFAULT_TOOLBAR[AurebeshKeyboardView.DEFAULT_TOOLBAR.length - 1] + ">",
                "el ultimo de la cinta de fabrica antes del ...: " + rec.typed);
    }

    @Test
    public void learningHintsFade() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Prefs.get(app).edit().remove(Prefs.HINT_COUNTS).apply();
        AurebeshKeyboardView kb = view(app, W);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        AurebeshKeyboardView.Key a = kb.keyFor("a");
        // Esquina inferior derecha de la cara (la celda menos 3 dp a los lados y 7,25 dp arriba y abajo) donde va la
        // pista latina (a 4 dp del borde, 9 sp), lejos del glifo centrado y de la alternativa de arriba a la derecha
        float faceRight = a.rect.right - 3 * dp, faceBottom = a.rect.bottom - 7.25f * dp;
        RectF box = new RectF(faceRight - 16 * dp, faceBottom - 14 * dp, faceRight - 3 * dp, faceBottom - 2 * dp);

        kb.setHintMode(0);
        Bitmap never = render(kb, W);
        kb.setHintMode(1);
        Bitmap always = render(kb, W);
        check(!same(always, never, box), "en 'siempre' se ve la pista de la a");
        kb.setHintMode(2);
        check(kb.hintMode() == 2, "modo aprendiendo");
        check(kb.hintAlpha("a") == 1f && kb.hintCount("a") == 0, "sin pulsaciones, pista entera");
        Bitmap learning = render(kb, W);
        save(learning, "aprendiendo_inicio.png");
        check(same(learning, always, box), "al empezar a aprender la pista esta entera");

        // 25 pulsaciones: se guarda ("a 25") y la pista va a 1 - 25/40
        for (int i = 0; i < 25; i++) tap(kb, a);
        check(kb.hintCount("a") == 25, "25 pulsaciones: " + kb.hintCount("a"));
        near("alfa tras 25", kb.hintAlpha("a"), 1f - 25 / 40f);
        String saved = Prefs.get(app).getString(Prefs.HINT_COUNTS, "");
        check(saved.contains("a 25"), "guardado cada 25 pulsaciones: " + saved);
        check(kb.hintAlpha("e") == 1f, "las demas letras siguen enteras");
        // 40: invisible; mas pulsaciones no pasan del tope
        for (int i = 0; i < 20; i++) tap(kb, a);
        check(kb.hintCount("a") == AurebeshKeyboardView.HINT_FADE_TAPS, "tope de 40: " + kb.hintCount("a"));
        check(kb.hintAlpha("a") == 0f, "a las 40 pulsaciones la pista desaparece");
        Bitmap faded = render(kb, W);
        save(faded, "aprendiendo_40.png");
        check(same(faded, never, box), "pixeles: la esquina de la a es igual que en 'nunca'");
        check(!same(faded, always, box), "pixeles: distinta que en 'siempre'");
        check(rec.typed.toString().equals(repeat("a", 45)), "las pulsaciones escribieron: " + rec.typed);

        // Lo guardado se carga en otra vista; restablecer lo borra todo
        AurebeshKeyboardView other = view(app, W);
        other.setHintMode(2);
        check(other.hintCount("a") >= 25, "otra vista carga el contador guardado: " + other.hintCount("a"));
        other.resetHintCounts();
        check(other.hintCount("a") == 0 && other.hintAlpha("a") == 1f, "restablecer vacia los contadores");
        check(Prefs.get(app).getString(Prefs.HINT_COUNTS, null) == null, "restablecer borra la preferencia");
        // applyPrefs ya no manda sobre el modo una vez fijado con setHintMode
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);
        check(kb.hintMode() == 2, "applyPrefs respeta setHintMode");
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
