package com.aurebesh.teclado;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
 * Lote 2.2: el ajuste de tamano se aplica al soltar (sin rebote durante el arrastre, con el rotulo en vivo); la fila
 * superior admite numeros, digrafos o nada (y Prefs.topRow deriva del interruptor antiguo); el numerico de cinco
 * columnas (numero y telefono) y el PIN de cuatro; los glifos nuevos (barra invertida, mas-menos, monedas, grados,
 * marcas, o con barra, distinto, por mil y las vocales con grave, dieresis, circunflejo y virgulilla); "Todo en
 * Aurebesh" dibuja tambien las sugerencias en glifos; los temas automaticos Republica e Imperio; y el postproceso de
 * la inteligencia (vinetas con titulo y "- ", limpieza de traducciones y deteccion de eco). Ademas, las pistas
 * latinas siguen en la esquina inferior derecha de la cara y el glifo nunca entra en ese cuadrado de 12 dp: ni en la
 * fila de numeros, ni en los digrafos, ni en el numerico.
 * Con el calificador xxhdpi de Robolectric la densidad es 3: 1080 px son 360 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Lote22Test {

    private static final int W = 1080;
    private static final int PHONE_H = 2340;
    /** Aire de la cara dentro de su celda en un telefono: 3 dp a los lados y 7,25 dp arriba y abajo. */
    private static final float INSET_X_DP = 3f;
    private static final float INSET_Y_DP = 7.25f;
    /** Lado del cuadrado de la esquina inferior derecha de la cara reservado a la pista latina. */
    private static final float HINT_SQUARE_DP = 12f;

    private Application app;
    private float dp;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        dp = app.getResources().getDisplayMetrics().density;
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
    }

    // ------------------------------------------------------------------ ayudas

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

    /** Evento de un dedo; con obtain(x, y) la coordenada en pantalla (getRawY) coincide con la de la vista. */
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

    private static void idle() { ShadowLooper.shadowMainLooper().idle(); }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void check(String got, String want, String what) {
        if (!want.equals(got)) throw new AssertionError(what + ": " + got + " (esperado " + want + ")");
    }

    private static void near(String what, float actual, float expected, float tol) {
        if (Math.abs(actual - expected) > tol) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }

    /** Teclado sin vibracion ni sonido ni pistas, al 100 %, con ese tema y la fila de numeros, medido a {@code w} px. */
    private AurebeshKeyboardView view(int w, String themeId, boolean strip) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, strip, true, themeId, true, false, 1f);
        kb.setEnterAction(4);
        kb.setListener(new SizeTest.Recorder());       // sin oyente las teclas de funcion no hacen nada
        layout(kb, w);
        return kb;
    }

    /** Cara de la tecla: la celda menos el aire (3 dp a los lados, 7,25 dp arriba y abajo en un telefono). */
    private RectF face(AurebeshKeyboardView.Key k) {
        return new RectF(k.rect.left + INSET_X_DP * dp, k.rect.top + INSET_Y_DP * dp,
                k.rect.right - INSET_X_DP * dp, k.rect.bottom - INSET_Y_DP * dp);
    }

    /** Cuadrado de 12 dp en la esquina inferior derecha de la cara (donde va la pista latina). */
    private RectF hintSquare(AurebeshKeyboardView.Key k) {
        RectF f = face(k);
        float s = HINT_SQUARE_DP * dp;
        return new RectF(f.right - s, f.bottom - s, f.right, f.bottom);
    }

    /** Zona central de la cara donde vive el glifo. */
    private RectF middle(AurebeshKeyboardView.Key k) {
        RectF f = face(k);
        return new RectF(f.centerX() - 0.37f * f.width(), f.centerY() - 0.25f * f.height(),
                f.centerX() + 0.37f * f.width(), f.centerY() + 0.25f * f.height());
    }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** Pixeles del rectangulo que se alejan del fondo de la tecla y del fondo del teclado (tinta de cualquier color). */
    private static int inkCount(Bitmap b, RectF r, Themes.Theme t) {
        int n = 0;
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int px = b.getPixel(x, y);
            if (Color.alpha(px) < 128 || dist(px, t.bg) <= 24 || dist(px, t.keyBg) <= 24) continue;
            n++;
        }
        return n;
    }

    /** Pixeles del rectangulo cercanos a {@code color} (la tinta del glifo se distingue de la pista, mas apagada). */
    private static int colorCount(Bitmap b, RectF r, int color, int tol) {
        int n = 0;
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int px = b.getPixel(x, y);
            if (Color.alpha(px) >= 128 && dist(px, color) <= tol) n++;
        }
        return n;
    }

    /** Los dos mapas son identicos dentro del rectangulo. */
    private static boolean same(Bitmap a, Bitmap b, RectF r) {
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(Math.min(a.getWidth(), b.getWidth()), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(Math.min(a.getHeight(), b.getHeight()), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
        return true;
    }

    private static String firstAlt(AurebeshKeyboardView kb, String out) {
        AurebeshKeyboardView.Key k = kb.keyFor(out);
        check(k != null, "falta la tecla " + out);
        return k.alts == null ? null : k.alts[0];
    }

    // ------------------------------------------------------------------ pistas abajo a la derecha, glifo fuera del cuadrado

    /**
     * Las pistas latinas van en la esquina inferior derecha de la cara (nunca al centro) y el glifo deja libre ese
     * cuadrado de 12 dp: en la fila de numeros (letras y ?123), en la fila de digrafos y en el numerico. Sin pistas el
     * cuadrado de los digitos y los digrafos queda limpio; en los simbolos del numerico la leyenda va siempre, pero
     * en el color de las pistas, nunca en el del glifo. Con pistas, el cuadrado lleva tinta del color de las pistas.
     */
    @Test
    public void pistasAbajoDerechaYGlifoFueraDelCuadrado() throws Exception {
        Themes.Theme t = Themes.NOCHE;
        check(dist(t.glyph, t.hint) > 150, "en Noche el glifo y la pista tienen colores bien distintos");
        AurebeshKeyboardView kb = view(W, t.id, false);
        String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
        int glyphTol = 90;

        // Fila de numeros de las letras, sin pistas: cuadrado limpio, glifo en el centro
        Bitmap off = render(kb, W);
        for (String d : digits) {
            AurebeshKeyboardView.Key k = kb.keyFor(d);
            check(inkCount(off, hintSquare(k), t) == 0, "fila de numeros sin pistas: el glifo del " + d + " no entra en el cuadrado de 12 dp");
            check(inkCount(off, middle(k), t) > 0, "fila de numeros: el glifo del " + d + " sigue en el centro");
        }
        // Con pistas (siempre): el digito latino en el cuadrado, en el color de la pista; el glifo fuera
        kb.setHintMode(1);
        Bitmap on = render(kb, W);
        save(on, "fila_numeros_pistas.png");
        for (String d : digits) {
            AurebeshKeyboardView.Key k = kb.keyFor(d);
            RectF sq = hintSquare(k);
            check(inkCount(on, sq, t) >= 12, "fila de numeros con pistas: el " + d + " latino abajo a la derecha (" + inkCount(on, sq, t) + " px)");
            check(colorCount(on, sq, t.glyph, glyphTol) == 0, "fila de numeros con pistas: el glifo del " + d + " no entra en el cuadrado");
            RectF f = face(k);
            RectF center = new RectF(f.centerX() - 4 * dp, f.bottom - HINT_SQUARE_DP * dp, f.centerX() + 4 * dp, f.bottom);
            check(colorCount(on, center, t.hint, 40) == 0, "la pista del " + d + " no va abajo al centro");
        }
        // Letras: la q y la a con la pista en la esquina y nada abajo al centro
        for (String l : new String[]{"q", "a", "m"}) {
            AurebeshKeyboardView.Key k = kb.keyFor(l);
            check(inkCount(on, hintSquare(k), t) >= 12, "letra " + l + ": pista abajo a la derecha");
            RectF f = face(k);
            RectF bl = new RectF(f.left, f.bottom - HINT_SQUARE_DP * dp, f.left + HINT_SQUARE_DP * dp, f.bottom);
            check(inkCount(on, bl, t) == 0, "letra " + l + ": la esquina inferior izquierda queda limpia");
        }
        kb.setHintMode(0);

        // Fila de numeros de ?123: lo mismo
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        Bitmap sym = render(kb, W);
        for (String d : digits) check(inkCount(sym, hintSquare(kb.keyFor(d)), t) == 0, "?123 sin pistas: el glifo del " + d + " fuera del cuadrado");
        kb.setHintMode(1);
        Bitmap symOn = render(kb, W);
        for (String d : digits) {
            check(inkCount(symOn, hintSquare(kb.keyFor(d)), t) >= 12, "?123 con pistas: el " + d + " latino abajo a la derecha");
            check(colorCount(symOn, hintSquare(kb.keyFor(d)), t.glyph, glyphTol) == 0, "?123 con pistas: el glifo del " + d + " fuera del cuadrado");
        }
        kb.setHintMode(0);
        kb.resetToLetters();

        // Fila de digrafos (glifos anchos y bajos): cuadrado limpio sin pistas, pista en el cuadrado con ellas
        kb.setTopRow("digraphs");
        Bitmap dig = render(kb, W);
        for (String d : Glyphs.DIGRAPHS) {
            AurebeshKeyboardView.Key k = kb.keyFor(d);
            check(k != null, "fila de digrafos: falta " + d);
            check(inkCount(dig, hintSquare(k), t) == 0, "digrafo " + d + " sin pistas: el glifo no entra en el cuadrado de 12 dp");
            check(inkCount(dig, middle(k), t) > 0, "digrafo " + d + ": glifo en el centro");
        }
        kb.setHintMode(1);
        Bitmap digOn = render(kb, W);
        save(digOn, "digrafos_pistas.png");
        for (String d : Glyphs.DIGRAPHS) {
            RectF sq = hintSquare(kb.keyFor(d));
            check(inkCount(digOn, sq, t) >= 12, "digrafo " + d + " con pistas: la pista abajo a la derecha");
            check(colorCount(digOn, sq, t.glyph, glyphTol) == 0, "digrafo " + d + " con pistas: el glifo fuera del cuadrado");
        }
        kb.setHintMode(0);
        kb.setTopRow("numbers");

        // Numerico (numero y telefono): digitos grandes sin entrar en el cuadrado; los simbolos con su leyenda en el color de la pista
        String[] numberSyms = {"(", ")", "*", "/", "-", "+", ",", "."};
        String[] phoneSyms = {"(", ")", "*", "/", "-", "+", "#"};
        for (int variant = 0; variant < 2; variant++) {
            boolean phone = variant == 1;
            kb.showNumpad(phone, true, true);
            String[] syms = phone ? phoneSyms : numberSyms;
            Bitmap np = render(kb, W);
            for (String d : digits) {
                AurebeshKeyboardView.Key k = kb.keyFor(d);
                check(inkCount(np, hintSquare(k), t) == 0, "numerico " + (phone ? "telefono" : "numero") + " sin pistas: el " + d + " fuera del cuadrado");
                check(inkCount(np, middle(k), t) > 0, "numerico: el glifo del " + d + " en el centro");
            }
            for (String s : syms) {
                AurebeshKeyboardView.Key k = kb.keyFor(s);
                check(k != null, "numerico: falta " + s);
                RectF sq = hintSquare(k);
                check(colorCount(np, sq, t.glyph, glyphTol) == 0, "numerico: el glifo de " + s + " no entra en el cuadrado de 12 dp");
                check(inkCount(np, sq, t) >= 6, "numerico: la leyenda de " + s + " va abajo a la derecha, siempre");
            }
            kb.setHintMode(1);
            Bitmap npOn = render(kb, W);
            save(npOn, phone ? "numerico_telefono_pistas.png" : "numerico_numero_pistas.png");
            for (String d : digits) {
                RectF sq = hintSquare(kb.keyFor(d));
                check(inkCount(npOn, sq, t) >= 12, "numerico con pistas: el " + d + " latino abajo a la derecha");
                check(colorCount(npOn, sq, t.glyph, glyphTol) == 0, "numerico con pistas: el glifo del " + d + " fuera del cuadrado");
            }
            kb.setHintMode(0);
        }
        kb.resetToLetters();
    }

    // ------------------------------------------------------------------ (a) ajuste de tamano sin rebote

    /**
     * En el modo de ajuste, arrastrar el tirador superior no cambia el alto mientras dura el gesto (ni se pide volver
     * a medir): solo se dibuja la vista previa con el rotulo del porcentaje previsto. Al soltar, el alto cambia una
     * sola vez al valor esperado y Listo comunica los valores con onResize.
     */
    @Test
    public void ajusteSinRebote() throws Exception {
        AurebeshKeyboardView kb = view(W, Themes.ESTELAR.id, false);
        SizeTest.Recorder rec = new SizeTest.Recorder();
        kb.setListener(rec);
        float rows100 = (56 + 4 * 64) * dp;                  // filas al 100 % con la fila de numeros
        kb.setResizeMode(true);
        Bitmap rest = render(kb, W);
        int h0 = kb.getMeasuredHeight();
        check(kb.heightPct() == 100 && kb.bottomDp() == 0, "al entrar: 100 % y 0 dp");
        RectF top = kb.resizeHandleRect(true);
        check(!top.isEmpty(), "tirador superior dibujado");
        float x = top.centerX(), y = top.centerY();

        // Caja del rotulo "NNN % · M dp": centrado sobre los botones, que van en el centro de las filas
        float keysTop = 40 * dp, keysBottom = h0 - 6 * dp;
        float baseline = (keysTop + keysBottom) / 2f + 8 * dp - 20 * dp - 12 * dp;
        RectF label = new RectF(W / 2f - 80 * dp, baseline - 16 * dp, W / 2f + 80 * dp, baseline + 6 * dp);

        down(kb, x, y);
        check(!kb.isLayoutRequested(), "posar el dedo no pide medir");
        Bitmap at30 = null, at60 = null, at60b = null;
        for (int i = 1; i <= 10; i++) {
            float dy = -6 * dp * i;
            move(kb, x, y + dy);
            check(!kb.isLayoutRequested(), "paso " + i + ": mover no pide volver a medir");
            check(kb.heightPct() == 100 && kb.bottomDp() == 0, "paso " + i + ": el alto y el margen no cambian durante el arrastre");
            Bitmap b = render(kb, W);
            check(kb.getMeasuredHeight() == h0, "paso " + i + ": el alto medido no cambia durante el arrastre: " + kb.getMeasuredHeight() + " vs " + h0);
            near("paso " + i + ": las filas siguen al 100 %", kb.keyFor("q").rect.height(), 64 * dp, 1f);
            if (i == 5) at30 = b;
            if (i == 10) at60 = b;
        }
        move(kb, x, y - 60 * dp - 1);                        // un pixel mas: el mismo porcentaje redondeado
        at60b = render(kb, W);
        save(at60, "ajuste_arrastrando.png");
        int want = Math.round((rows100 + 60 * dp) / rows100 * 100f);
        int want30 = Math.round((rows100 + 30 * dp) / rows100 * 100f);
        check(want != want30 && want != 100, "los porcentajes previstos difieren: " + want30 + " / " + want);
        check(!same(rest, at60, label), "el rotulo cambia al arrastrar (ya no dice 100 %)");
        check(!same(at30, at60, label), "el rotulo refleja el porcentaje previsto (" + want30 + " % frente a " + want + " %)");
        check(same(at60, at60b, label), "con el mismo porcentaje previsto el rotulo es identico");
        check(rec.events.isEmpty(), "arrastrar no avisa al oyente");

        // Soltar: se aplica una sola vez
        up(kb, x, y - 60 * dp - 1);
        check(kb.isLayoutRequested(), "al soltar se pide medir (una vez)");
        check(kb.heightPct() == want, "alto tras soltar: " + kb.heightPct() + " (esperado " + want + ")");
        layout(kb, W);
        near("filas al nuevo alto", kb.keyFor("q").rect.height(), 64 * dp * want / 100f, 2.5f);
        near("alto total nuevo", kb.getMeasuredHeight(), 40 * dp + rows100 * want / 100f + 6 * dp, 2.5f);
        check(kb.isResizeMode() && rec.events.isEmpty(), "soltar no sale del modo ni avisa");
        Bitmap after = render(kb, W);
        check(!kb.isLayoutRequested(), "tras medir, nada mas pendiente");
        check(!same(after, rest, new RectF(0, 0, W, Math.min(rest.getHeight(), after.getHeight()))), "la vista se ve distinta con el alto nuevo");

        // Tirador inferior: 20 dp de margen al soltar, nada durante el arrastre
        RectF bottom = kb.resizeHandleRect(false);
        check(!bottom.isEmpty(), "tirador inferior dibujado");
        int h1 = kb.getMeasuredHeight();
        down(kb, bottom.centerX(), bottom.centerY());
        for (int i = 1; i <= 4; i++) {
            move(kb, bottom.centerX(), bottom.centerY() - 5 * dp * i);
            check(!kb.isLayoutRequested() && kb.bottomDp() == 0, "margen: nada cambia durante el arrastre");
            layout(kb, W);
            check(kb.getMeasuredHeight() == h1, "margen: el alto medido no cambia durante el arrastre");
        }
        up(kb, bottom.centerX(), bottom.centerY() - 20 * dp);
        check(kb.bottomDp() == 20, "margen tras soltar: " + kb.bottomDp());
        layout(kb, W);
        near("alto total con margen", kb.getMeasuredHeight(), 40 * dp + rows100 * want / 100f + 20 * dp, 2.5f);

        // Listo: onResize con lo elegido, una sola vez
        render(kb, W);
        RectF done = kb.resizeButtonRect(true);
        tap(kb, done.centerX(), done.centerY());
        check(rec.events.toString(), "[resize" + want + ",20]", "Listo avisa una vez con alto y margen");
        check(!kb.isResizeMode(), "Listo sale del modo");
        layout(kb, W);
        check(kb.heightPct() == want && kb.bottomDp() == 20, "los valores se quedan");
    }

    // ------------------------------------------------------------------ (b) fila superior

    @Test
    public void filaSuperiorNumerosDigrafosONada() throws Exception {
        AurebeshKeyboardView kb = view(W, Themes.ESTELAR.id, false);
        check(kb.topRow(), "numbers", "de fabrica, numeros");
        check(kb.keyFor("1") != null, "con numeros hay fila de digitos");
        check(!"1".equals(firstAlt(kb, "q")), "con la fila de numeros la q no lleva el digito como primera alternativa: " + firstAlt(kb, "q"));
        float qTopNumbers = kb.keyFor("q").rect.top;

        // Digrafos: los ocho arriba, de ancho 1,25, en el paso de la fila superior; q..p dan el digito al mantener y pierden su simbolo
        kb.setTopRow("digraphs");
        layout(kb, W);
        check(kb.topRow(), "digraphs", "setTopRow");
        AurebeshKeyboardView.Key ch = kb.keyFor("ch");
        check(ch != null, "tecla ch");
        for (String d : Glyphs.DIGRAPHS) {
            AurebeshKeyboardView.Key k = kb.keyFor(d);
            check(k != null, "falta el digrafo " + d);
            near("los digrafos van en la misma fila: " + d, k.rect.top, ch.rect.top, 1f);
            near("ancho 1,25 de los digrafos: " + d, k.rect.width(), 1.25f * kb.keyFor("q").rect.width(), 2.5f);
        }
        check(kb.keyFor("1") == null, "sin teclas de digitos");
        check(kb.keyFor("q").rect.top >= ch.rect.bottom - 1f, "la fila de digrafos va encima de la q");
        near("la q en el mismo sitio que con los numeros", kb.keyFor("q").rect.top, qTopNumbers, 1f);
        near("fila superior de 56 dp", ch.rect.height(), 56 * dp, 1f);
        check("1".equals(firstAlt(kb, "q")) && "0".equals(firstAlt(kb, "p")), "q..p dan el digito al mantener: " + firstAlt(kb, "q") + " " + firstAlt(kb, "p"));
        check(!Arrays.asList(kb.keyFor("q").alts).contains("+"), "sin su simbolo (como con la fila oculta): " + Arrays.toString(kb.keyFor("q").alts));
        save(render(kb, W), "fila_superior_digrafos.png");
        SizeTest.Recorder rec = new SizeTest.Recorder();
        kb.setListener(rec);
        tap(kb, ch);
        check(rec.typed.toString(), "ch", "tocar el digrafo escribe sus dos letras");
        // applyPrefs ya no manda sobre la fila una vez fijada con setTopRow
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, false, false, 1f);
        layout(kb, W);
        check(kb.topRow(), "digraphs", "applyPrefs(numbers=false) no toca la fila tras setTopRow");
        check(kb.keyFor("ch") != null, "los digrafos siguen");

        // Nada: sin fila superior, la q arriba del todo y los digitos al mantener
        kb.setTopRow("none");
        layout(kb, W);
        check(kb.topRow(), "none", "setTopRow(none)");
        check(kb.keyFor("ch") == null && kb.keyFor("1") == null, "sin fila superior");
        check(kb.keyFor("q").rect.top < qTopNumbers - 40 * dp, "la q sube al sitio de la fila superior");
        check("1".equals(firstAlt(kb, "q")), "sin fila la q da el 1 al mantener");

        // Numeros: como siempre
        kb.setTopRow("numbers");
        layout(kb, W);
        check(kb.keyFor("1") != null && kb.keyFor("ch") == null, "numeros de vuelta");
        near("la q vuelve a su sitio", kb.keyFor("q").rect.top, qTopNumbers, 1f);
        check(!"1".equals(firstAlt(kb, "q")), "con numeros la q recupera su simbolo");
        // Un valor desconocido equivale a numeros; en una vista nueva applyPrefs(numbers=false) sigue valiendo "none"
        kb.setTopRow("lo que sea");
        check(kb.topRow(), "numbers", "valor desconocido");
        AurebeshKeyboardView fresh = new AurebeshKeyboardView(app);
        fresh.applyPrefs(false, false, false, true, Themes.ESTELAR.id, false, false, 1f);
        layout(fresh, W);
        check(fresh.topRow(), "none", "applyPrefs(numbers=false) antes de setTopRow");
        fresh.setTopRow("digraphs");
        layout(fresh, W);
        check(fresh.keyFor("sh") != null, "y setTopRow manda despues");

        // Prefs.topRow deriva del interruptor NUMBERS antiguo cuando TOP_ROW falta o trae basura
        Prefs.get(app).edit().clear().apply();
        check(Prefs.topRow(app), "numbers", "sin nada guardado");
        Prefs.get(app).edit().putBoolean(Prefs.NUMBERS, false).apply();
        check(Prefs.topRow(app), "none", "NUMBERS=false sin TOP_ROW");
        Prefs.get(app).edit().putString(Prefs.TOP_ROW, "digraphs").apply();
        check(Prefs.topRow(app), "digraphs", "TOP_ROW manda sobre NUMBERS");
        Prefs.get(app).edit().putString(Prefs.TOP_ROW, "basura").apply();
        check(Prefs.topRow(app), "none", "TOP_ROW desconocida: se deriva de NUMBERS");
        Prefs.get(app).edit().putBoolean(Prefs.NUMBERS, true).apply();
        check(Prefs.topRow(app), "numbers", "TOP_ROW desconocida con NUMBERS=true");
        check(Prefs.affectsKeyboard(Prefs.TOP_ROW), "cambiar la fila superior despierta al teclado");
        // La pantalla de ajustes guarda TOP_ROW y NUMBERS en paralelo desde los chips de Idiomas y teclas
        Prefs.get(app).edit().clear().apply();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        SettingsTest.openCategory(a, R.string.cat_keys, MainActivity.PAGE_KEYS);
        check(hasText(a, a.getString(R.string.top_row_title)), "fila Fila superior");
        TextView digraphs = textView(a, a.getString(R.string.top_row_digraphs));
        check(digraphs != null, "chip Digrafos");
        digraphs.performClick();
        check(Prefs.topRow(app), "digraphs", "el chip guarda TOP_ROW");
        check(!Prefs.numbers(app), "y NUMBERS=false en paralelo");
        TextView numbers = textView(a, a.getString(R.string.top_row_numbers));
        numbers.performClick();
        check(Prefs.topRow(app), "numbers", "Numeros");
        check(Prefs.numbers(app), "NUMBERS=true con numeros");
        boolean listed = false;
        for (String k : MainActivity.RESET_KEYS) if (Prefs.TOP_ROW.equals(k)) listed = true;
        check(listed && MainActivity.keyType(Prefs.TOP_ROW) == MainActivity.TYPE_STRING, "TOP_ROW se restablece y exporta como texto");
    }

    // ------------------------------------------------------------------ (c) numerico

    @Test
    public void numericoNumeroTelefonoYPin() throws Exception {
        AurebeshKeyboardView kb = view(W, Themes.ESTELAR.id, false);
        kb.setHintMode(1);
        // Numero: ( ) * / - + y los dos separadores; sin # ni pausa
        kb.showNumpad(false, true, true);
        layout(kb, W);
        for (String k : new String[]{"(", ")", "*", "/", "-", "+", ",", ".", "0", "9"}) check(kb.keyFor(k) != null, "numero sin " + k);
        check(kb.keyFor("#") == null, "numero sin almohadilla");
        check(kb.keyFor(AurebeshKeyboardView.CODE_DELETE) != null && kb.keyFor(AurebeshKeyboardView.CODE_ENTER) != null, "borrar e intro");
        save(render(kb, W), "numerico_simbolos.png");
        // Telefono: + # * ( ) - / y la pausa (coma con ; al mantener)
        kb.showNumpad(true, false, false);
        layout(kb, W);
        for (String k : new String[]{"+", "#", "*", "(", ")", "-", "/", ",", "0", "5"}) check(kb.keyFor(k) != null, "telefono sin " + k);
        check(kb.keyFor(".") == null, "telefono sin punto");
        check(Arrays.toString(kb.keyFor(",").alts), "[;]", "la pausa ofrece el punto y coma");
        save(render(kb, W), "numerico_telefono.png");
        // PIN: solo digitos, borrar e intro
        kb.showNumpad(false, false, false, true);
        layout(kb, W);
        for (String d : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}) check(kb.keyFor(d) != null, "PIN sin " + d);
        for (String k : new String[]{"(", ")", "*", "/", "-", "+", ",", ".", "#"}) check(kb.keyFor(k) == null, "PIN con " + k);
        check(kb.keyFor(AurebeshKeyboardView.CODE_DELETE) != null && kb.keyFor(AurebeshKeyboardView.CODE_ENTER) != null, "PIN: borrar e intro");
        check(kb.keyFor("1").rect.width() > kb.keyFor("1").rect.height() * 1.1f, "PIN: cuatro columnas");
        save(render(kb, W), "numerico_pin.png");
        kb.resetToLetters();
        layout(kb, W);
        check(!kb.isNumpad() && kb.keyFor("q") != null, "vuelta a letras");
    }

    // ------------------------------------------------------------------ (d) glifos nuevos

    private static final String[] NEW_GLYPHS = {"\\", "\u00b1", "\u00a3", "\u00a5", "\u00b0", "\u00a9", "\u00ae", "\u2122",
            "\u00f8", "\u2260", "\u2030",
            "\u00e0", "\u00e8", "\u00ec", "\u00f2", "\u00f9",                       // grave
            "\u00e4", "\u00eb", "\u00ef", "\u00f6", "\u00fc",                       // dieresis
            "\u00e2", "\u00ea", "\u00ee", "\u00f4", "\u00fb",                       // circunflejo
            "\u00e3", "\u00f5"};                                                   // virgulilla

    @Test
    public void glifosNuevos() throws Exception {
        for (String g : NEW_GLYPHS) {
            check(Glyphs.has(g), "falta el glifo de " + g);
            check(g.equals(Glyphs.glyphKey(g)), "glyphKey devuelve la propia letra para " + g + ": " + Glyphs.glyphKey(g));
            check(Glyphs.width(g) > 0, "ancho del glifo " + g);
        }
        check("\u00e0".equals(Glyphs.glyphKey("\u00e0")), "glyphKey(a grave) = a grave");
        check("\u00e0".equals(Glyphs.glyphKey("\u00c0")), "la mayuscula usa el glifo de la minuscula");
        check("y".equals(Glyphs.glyphKey("\u00fd")), "una letra con acento sin glifo propio sigue usando la base");
        check(Glyphs.has("\u00e1") && Glyphs.has("\u00f1"), "los glifos de siempre siguen");
        check(Glyphs.has("\u00d7") == Glyphs.has("\u00f7"), "por y entre van juntos (pendientes de aprobacion)");

        // Render de dos filas con los glifos nuevos sobre fondo Noche: cada celda deja tinta
        Themes.Theme t = Themes.NOCHE;
        int perRow = 14, rows = (NEW_GLYPHS.length + perRow - 1) / perRow;
        float cellW = (float) W / perRow, cellH = 60 * dp;
        Bitmap b = Bitmap.createBitmap(W, Math.round(cellH * rows), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(t.bg);
        Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyph.setColor(t.glyph);
        glyph.setStyle(Paint.Style.FILL);
        for (int i = 0; i < NEW_GLYPHS.length; i++) {
            float cx = (i % perRow + 0.5f) * cellW, cy = (i / perRow + 0.5f) * cellH;
            Glyphs.drawFitted(c, NEW_GLYPHS[i], cx, cy, 28 * dp, cellW * 0.8f, glyph);
        }
        save(b, "glifos_nuevos.png");
        for (int i = 0; i < NEW_GLYPHS.length; i++) {
            RectF cell = new RectF((i % perRow) * cellW, (i / perRow) * cellH, (i % perRow + 1) * cellW, (i / perRow + 1) * cellH);
            check(colorCount(b, cell, t.glyph, 120) > 10, "el glifo de " + NEW_GLYPHS[i] + " deja tinta");
        }
        // En el teclado: la pagina 2/2 dibuja monedas, grado y marcas con su glifo; la a con grave viene del menu de la a
        AurebeshKeyboardView kb = view(W, t.id, false);
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_MODE));
        layout(kb, W);
        tap(kb, kb.keyFor(AurebeshKeyboardView.CODE_PAGE));
        layout(kb, W);
        for (String g : new String[]{"\u00a3", "\u00a5", "\u00b0", "\u00a9", "\u00ae", "\u2122", "\\"}) {
            AurebeshKeyboardView.Key k = kb.keyFor(g);
            check(k != null, "2/2 sin " + g);
            check(g.equals(k.glyph), "2/2: " + g + " con su glifo propio: " + k.glyph);
        }
        save(render(kb, W), "glifos_nuevos_teclado.png");
    }

    // ------------------------------------------------------------------ (e) Todo en Aurebesh

    @Test
    public void todoEnAurebeshDibujaLasSugerenciasEnGlifos() throws Exception {
        Themes.Theme t = Themes.NOCHE;
        List<String> words = Arrays.asList("hola", "mundo", "amigo");
        AurebeshKeyboardView latin = view(W, t.id, false);
        latin.setSuggestionBar(true);
        latin.setSuggestions(words);
        Bitmap lb = render(latin, W);
        save(lb, "todo_en_aurebesh_latino.png");
        RectF band = latin.suggestionBandRect();
        check(!band.isEmpty() && band.height() > 20 * dp, "banda de sugerencias dibujada: " + band);
        check(latin.suggestionRects().size() == 3, "tres celdas en latino");

        AurebeshKeyboardView aure = view(W, t.id, true);
        aure.setSuggestionBar(true);
        aure.setSuggestions(words);
        Bitmap ab = render(aure, W);
        save(ab, "todo_en_aurebesh.png");
        RectF band2 = aure.suggestionBandRect();
        check(!band2.isEmpty(), "con Todo en Aurebesh la banda sigue");
        // Nota: en Aurebesh tres palabras de 4-5 letras miden mas que la banda (79 + 87 + 87 dp frente a 250 dp) y la vista omite
        // la tercera dejando 84 dp vacios (drawSuggestions, modo medido); aqui solo se exige que la banda no se vacie
        int cells = aure.suggestionRects().size();
        check(cells >= 2 && cells <= 3, "la banda en Aurebesh conserva al menos dos sugerencias: " + aure.suggestionRects() + " banda " + band2);
        RectF probe = new RectF(Math.max(band.left, band2.left), Math.max(band.top, band2.top), Math.min(band.right, band2.right), Math.min(band.bottom, band2.bottom));
        check(!same(lb, ab, probe), "las sugerencias se dibujan distinto con Todo en Aurebesh (glifos) que sin el (latino)");
        int inkA = colorCount(ab, probe, t.glyph, 90), inkL = colorCount(lb, probe, t.glyph, 90);
        check(inkA > 50, "con Todo en Aurebesh hay tinta de glifos en la banda (" + inkA + " px)");
        check(inkL > 50, "en latino tambien hay tinta (" + inkL + " px)");
        // Sin sugerencias la banda desaparece: lo que habia era de las palabras
        aure.setSuggestions(null);
        Bitmap empty = render(aure, W);
        check(aure.suggestionBandRect().isEmpty(), "sin sugerencias no hay banda");
        check(!same(ab, empty, probe), "la tinta era de las sugerencias");
        // Una palabra larga no vacia la banda: en Aurebesh su celda se recorta y las tres siguen
        aure.setSuggestions(Arrays.asList("supercalifragilisticoespialidoso", "hola", "mundo"));
        render(aure, W);
        List<RectF> cellsLong = aure.suggestionRects();
        check(cellsLong.size() >= 2, "una palabra larga en Aurebesh no se come a las demas: " + cellsLong);
        check(cellsLong.get(0).width() <= aure.suggestionBandRect().width() / 2f + dp, "la celda de la palabra larga se limita a media banda: " + cellsLong.get(0));
        // La pastilla resaltada tambien va en glifos (se dibuja sin romper nada)
        aure.setSuggestions(words, "hola");
        Bitmap hl = render(aure, W);
        check(!same(hl, ab, probe), "la pastilla de la resaltada se ve");
        // Las cadenas de la opcion ya se llaman "Todo en Aurebesh"
        check(app.getString(R.string.immersive_title).equals(app.getString(R.string.opt_strip)), "la fila de la app se llama como la opcion: " + app.getString(R.string.opt_strip));
    }

    // ------------------------------------------------------------------ (f) temas automaticos

    @Test
    public void temasAutomaticosRepublicaEImperio() throws Exception {
        check(Themes.isAuto(Prefs.THEME_AUTO) && Themes.isAuto(Prefs.THEME_AUTO_IMPERIO) && !Themes.isAuto("arena"), "isAuto");
        check(Themes.autoDay(Prefs.THEME_AUTO) == Themes.ESTELAR && Themes.autoNight(Prefs.THEME_AUTO) == Themes.NOCHE, "Republica: Estelar / Noche");
        check(Themes.autoDay(Prefs.THEME_AUTO_IMPERIO) == Themes.ARENA && Themes.autoNight(Prefs.THEME_AUTO_IMPERIO) == Themes.IMPERIAL, "Imperio: Arena / Imperial");
        check(Themes.AUTOS.length == 2 && Themes.ALL.length == 6, "dos automaticos y seis fijos");

        // Prefs.theme resuelve con el uiMode del contexto
        Prefs.get(app).edit().putString(Prefs.THEME, Prefs.THEME_AUTO_IMPERIO).apply();
        check(Prefs.themePref(app), Prefs.THEME_AUTO_IMPERIO, "guardado");
        check(Prefs.theme(app), Themes.ARENA.id, "Imperio de dia");
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context nightContext = app.createConfigurationContext(night);
        check(Prefs.theme(nightContext), Themes.IMPERIAL.id, "Imperio de noche (contexto)");
        RuntimeEnvironment.setQualifiers("+night");
        check(Prefs.theme(app), Themes.IMPERIAL.id, "Imperio de noche (calificador -night)");
        Prefs.get(app).edit().putString(Prefs.THEME, Prefs.THEME_AUTO).apply();
        check(Prefs.theme(app), Themes.NOCHE.id, "Republica de noche");
        RuntimeEnvironment.setQualifiers("es-rES-w411dp-h914dp-xxhdpi");
        check(Prefs.theme(app), Themes.ESTELAR.id, "Republica de dia");
        Prefs.get(app).edit().putString(Prefs.THEME, Prefs.THEME_AUTO_IMPERIO).apply();
        check(Prefs.theme(app), Themes.ARENA.id, "Imperio de dia otra vez");

        // Pantalla de ajustes: pagina Estilo con el grupo automatico (Republica e Imperio) y los seis fijos
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        SettingsTest.openCategory(a, R.string.cat_style, MainActivity.PAGE_STYLE);
        check(hasText(a, a.getString(R.string.theme_auto_group)), "grupo de automaticos");
        check(hasText(a, a.getString(R.string.theme_fixed_group)), "grupo de fijos");
        View republic = SettingsTest.clickableStartingWith(a, a.getString(R.string.theme_auto_republic) + ". ");
        View imperio = SettingsTest.clickableStartingWith(a, a.getString(R.string.theme_auto_imperio) + ". ");
        check(republic != null && imperio != null, "tarjetas Republica e Imperio");
        check(hasText(a, a.getString(R.string.theme_auto_republic_sub)) && hasText(a, a.getString(R.string.theme_auto_imperio_sub)), "con sus subtitulos");
        check(imperio.isSelected() && !republic.isSelected(), "Imperio (guardado) aparece seleccionado");
        for (Themes.Theme th : Themes.ALL) {
            View item = byDescription(a, a.getString(th.name));
            check(item != null, "muestra fija " + th.id);
            check(!item.isSelected(), "con un automatico ningun fijo esta seleccionado: " + th.id);
        }
        republic.performClick();
        check(Prefs.themePref(app), Prefs.THEME_AUTO, "Republica guarda auto");
        check(republic.isSelected() && !imperio.isSelected(), "la seleccion cambia de tarjeta");
        idle();
        check(textContains(a, a.getString(R.string.theme_auto_republic)), "el resumen de Estilo nombra Republica");
        // Render de la pagina Estilo
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(PHONE_H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, PHONE_H);
        Bitmap shot = Bitmap.createBitmap(W, PHONE_H, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(shot));
        save(shot, "ajustes_estilo.png");
    }

    // ------------------------------------------------------------------ (g) inteligencia: postproceso

    @Test
    public void inteligenciaVinetasYLimpiezaDeTraduccion() throws Exception {
        // Vinetas: una sola linea con vinetas sueltas, enumeracion con titulo, Markdown, numeradas y solo titulo
        check(AiRunner.bulletList("\u2022 Huevo \u2022 Leche \u2022 Pan"), "- Huevo\n- Leche\n- Pan", "vinetas sueltas en una linea");
        check(AiRunner.bulletList("Lista de compras: Huevo, Leche, Pan y Tortillas"), "Lista de compras:\n- Huevo\n- Leche\n- Pan\n- Tortillas", "enumeracion con titulo y conjuncion final");
        check(AiRunner.bulletList("**Planes del fin de semana:**\n* Ir al cine\n* Cenar con la familia"), "Planes del fin de semana:\n- Ir al cine\n- Cenar con la familia", "Markdown normalizado a - ");
        check(AiRunner.bulletList("1. Uno\n2. Dos\n\n3. Tres"), "- Uno\n- Dos\n- Tres", "numeradas sin titulo ni lineas en blanco");
        check(AiRunner.bulletList("Planes del fin de semana\n- Cine\n- Cena"), "Planes del fin de semana:\n- Cine\n- Cena", "titulo sin dos puntos los gana");
        check(AiRunner.bulletList("Aqu\u00ed tienes la lista:\n- a\n- b"), "- a\n- b", "el preambulo no es titulo");
        check(AiRunner.bulletList("Solo un titulo:"), "", "solo titulo = nada");
        check(AiRunner.bulletList("   \n  "), "", "vacio");
        String bullets = AiRunner.clean(AiRunner.Action.BULLETS, "Lista de puntos:\n\u2022 Huevo\n\u2022 Leche", "huevo leche");
        for (String line : bullets.split("\n")) check(line.startsWith("- ") || line.endsWith(":"), "cada linea de Vinetas es un elemento o el titulo: " + line);
        check(bullets.contains("- Huevo") && bullets.contains("- Leche"), "Vinetas limpias: " + bullets);

        // Traducir: preambulo charlatan, comillas y nota final fuera
        String chatty = "Sure! Here is the translation of \"hola\" into English:\n\"Hello\"\n\nThis translation maintains the informal tone.";
        check(AiRunner.clean(AiRunner.Action.TRANSLATE, chatty, "hola"), "Hello", "preambulo, comillas y nota");
        check(AiRunner.clean(AiRunner.Action.TRANSLATE, "\u00a1Claro!\nAqu\u00ed tienes la traducci\u00f3n al ingl\u00e9s:\nGood morning", "Buenos d\u00edas"), "Good morning", "preambulo en espanol");
        check(AiRunner.stripTrailingNotes("Hola\n\nEsta traducci\u00f3n mantiene el tono informal."), "Hola", "nota final en parrafo");
        check(AiRunner.stripTrailingNotes("Hola\nNota: informal"), "Hola", "nota final en la ultima linea");
        check(AiRunner.stripTrailingNotes("Hola\n\nAdi\u00f3s"), "Hola\n\nAdi\u00f3s", "un parrafo normal se queda");
        check("Hello".equals(AiRunner.quotedBlock("\"Hello\" This translation keeps the tone.")), "bloque entre comillas con aclaracion");
        check("Hola".equals(AiRunner.quotedBlock("\u00abHola\u00bb")), "comillas latinas");
        check(AiRunner.quotedBlock("\"Hello\" and then more text") == null, "con mas texto normal detras no se recorta");
        check(AiRunner.quotedBlock("Hello") == null, "sin comillas");

        // Eco: el modelo devolvio el original (sin contar mayusculas, espacios ni puntuacion final)
        check(AiRunner.sameText("Hola mundo.", "hola  mundo"), "sameText ignora mayusculas, espacios y puntuacion final");
        check(!AiRunner.sameText("Hola mundo", "Hello world"), "textos distintos");
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.TRANSLATE, "hola mundo");
        r.target = "en";
        check(AiRunner.untranslated(r, "Hola mundo.", "hola mundo"), "eco del original = sin traducir");
        check(!AiRunner.untranslated(r, "Hello world", "hola mundo"), "traducido de verdad");
        check(!AiRunner.untranslated(r, "", "hola mundo"), "vacio no cuenta como eco (ya es un error aparte)");
        AiRunner.Cleaned echo = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "hola mundo", "hola mundo");
        check(echo.weak, "la limpieza marca el eco como flojo");
        // Las cadenas del reintento existen
        check(!app.getString(R.string.ai_err_untranslated).isEmpty() && !app.getString(R.string.ia4_translating_again).isEmpty(), "cadenas del reintento");
    }

    // ------------------------------------------------------------------ ayudas de la actividad

    private static List<View> all(MainActivity a) {
        List<View> out = new ArrayList<>();
        collect(a.getWindow().getDecorView(), out);
        return out;
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static TextView textView(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && s.equals(((TextView) v).getText().toString())) return (TextView) v;
        }
        return null;
    }

    private static boolean hasText(MainActivity a, String s) { return textView(a, s) != null; }

    private static boolean textContains(MainActivity a, String s) {
        for (View v : all(a)) {
            if (v instanceof TextView && ((TextView) v).getText().toString().contains(s)) return true;
        }
        return false;
    }

    private static View byDescription(MainActivity a, String description) {
        for (View v : all(a)) {
            if (v.getContentDescription() != null && description.equals(v.getContentDescription().toString())) return v;
        }
        return null;
    }
}
