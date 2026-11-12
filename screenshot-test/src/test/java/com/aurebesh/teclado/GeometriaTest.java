package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Geometria como Samsung Keyboard (1.11): el Z Flip 7 apaisado cabe en el 56 % del alto de la pantalla con filas
 * de 36 dp y sin fila de numeros; en vertical el margen lateral es el 2,1 % del ancho (minimo 8 dp), el paso de
 * columna (ancho - 2 margenes) / 10 y los pasos de fila 64 dp (letras) y 56 dp (digitos), con la cara de la tecla
 * 3 dp por dentro a los lados y 7,25 dp arriba y abajo. Ademas: la pista latina va abajo a la derecha de la cara y
 * la alternativa de pulsacion larga arriba a la derecha; los glifos comparten escala (la i, la l y la m no salen
 * mas altas que la w, tampoco al agrandar el teclado); y las alternativas dependen del idioma (en espanol ni
 * cedilla ni eszett). Desde 2.1 los caracteres alternativos (activos de fabrica) ponen el simbolo de cada letra
 * delante de sus alternativas y lo insinuan arriba a la derecha; aqui se apagan donde la premisa era la antigua
 * (AltCharsTest los cubre). Con el calificador xxhdpi de Robolectric la densidad es 3: 1080 px son 360 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GeometriaTest {

    private static final int W = 1080;
    private static final String PHONE = "es-rES-w411dp-h914dp-xxhdpi";
    /** Z Flip 7 apaisado: 960 x 411 dp; a la densidad del aparato (2,625) la pantalla mide 2520 px de ancho. */
    private static final String FLIP_LAND = "es-rES-w960dp-h411dp-land-xxhdpi";
    private static final int FLIP_LAND_W = 2520;
    /** Tapa exterior del Z Flip: 360 x 374 dp, sin fila de numeros. */
    private static final String FLIP_COVER = "es-rES-w360dp-h374dp-xxhdpi";
    /** Aire de la cara dentro de su celda en pantallas normales: 3 dp a los lados y 7,25 dp arriba y abajo. */
    private static final float INSET_X_DP = 3f;
    private static final float INSET_Y_DP = 7.25f;
    /** Diferencia de color (suma de los tres canales) a partir de la cual un pixel ya no es fondo. */
    private static final int INK = 60;

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

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void near(String what, float actual, float expected) { near(what, actual, expected, 2.5f); }

    private static void near(String what, float actual, float expected, float tol) {
        if (Math.abs(actual - expected) > tol) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }

    /** Teclado con la fila de numeros activada, sin vibracion ni sonido, al 100 %, con ese tema y ya medido a {@code w} px. */
    private static AurebeshKeyboardView view(Application app, int w, String themeId) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, themeId, true, false, 1f);
        kb.setEnterAction(4);
        kb.setListener(new SizeTest.Recorder());       // sin oyente las teclas de funcion no hacen nada
        layout(kb, w);
        return kb;
    }

    /** Cara de la tecla: la celda (zona de toque) menos el aire de pantallas normales. */
    private static RectF face(AurebeshKeyboardView.Key k, float dp) {
        return new RectF(k.rect.left + INSET_X_DP * dp, k.rect.top + INSET_Y_DP * dp,
                k.rect.right - INSET_X_DP * dp, k.rect.bottom - INSET_Y_DP * dp);
    }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** Fondo del teclado (asoma entre caras y en las esquinas redondeadas) o nada pintado. */
    private static boolean isBg(int px, Themes.Theme t) { return Color.alpha(px) < 128 || dist(px, t.bg) <= 24; }

    /** Tinta: un pixel que se aleja tanto del fondo de la tecla como del fondo del teclado. */
    private static boolean isInk(int px, Themes.Theme t) {
        return Color.alpha(px) >= 128 && dist(px, t.keyBg) > INK && dist(px, t.bg) > INK;
    }

    /** Pixeles de tinta dentro del rectangulo (en px). */
    private static int inkCount(Bitmap b, RectF r, Themes.Theme t) {
        int n = 0;
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (isInk(b.getPixel(x, y), t)) n++;
        return n;
    }

    /** Caja de la tinta dentro del rectangulo: {izquierda, arriba, derecha, abajo} en px (bordes incluidos), o null si no hay. */
    private static int[] inkBox(Bitmap b, RectF r, Themes.Theme t) {
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        int l = Integer.MAX_VALUE, tp = Integer.MAX_VALUE, rt = -1, bt = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (!isInk(b.getPixel(x, y), t)) continue;
                if (x < l) l = x;
                if (x > rt) rt = x;
                if (y < tp) tp = y;
                if (y > bt) bt = y;
            }
        }
        return rt < 0 ? null : new int[]{l, tp, rt, bt};
    }

    /** Pixeles que no son fondo del teclado a lo largo de una linea vertical (x0 == x1) u horizontal (y0 == y1). */
    private static int runNotBg(Bitmap b, float x0, float y0, float x1, float y1, Themes.Theme t) {
        int n = 0;
        if (Math.round(x0) == Math.round(x1)) {
            int x = Math.round(x0);
            for (int y = Math.round(y0); y < Math.round(y1); y++) if (!isBg(b.getPixel(x, y), t)) n++;
        } else {
            int y = Math.round(y0);
            for (int x = Math.round(x0); x < Math.round(x1); x++) if (!isBg(b.getPixel(x, y), t)) n++;
        }
        return n;
    }

    private static int pixel(Bitmap b, float x, float y) { return b.getPixel(Math.round(x), Math.round(y)); }

    // ------------------------------------------------------------------ Z Flip 7 apaisado

    @Test
    public void flipApaisado() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        RuntimeEnvironment.setQualifiers(FLIP_LAND);
        try {
            float dp = app.getResources().getDisplayMetrics().density;
            AurebeshKeyboardView kb = view(app, FLIP_LAND_W, Themes.ESTELAR.id);
            Bitmap b = render(kb, FLIP_LAND_W);
            save(b, "tamano_flip_apaisado.png");
            float limit = 0.56f * 411 * dp;
            check(kb.getMeasuredHeight() <= limit, "Flip apaisado: el teclado pasa del 56 % del alto de la pantalla: "
                    + kb.getMeasuredHeight() + " px (tope " + limit + ")");
            check(kb.keyFor("1") == null, "Flip apaisado: sin fila de numeros aunque este activada");
            AurebeshKeyboardView.Key q = kb.keyFor("q"), dot = kb.keyFor(".");
            check(q.alts != null && "1".equals(q.alts[0]), "Flip apaisado: la q da 1 al mantener");
            near("Flip apaisado: filas de letras de 36 dp", q.rect.height(), 36 * dp);
            near("Flip apaisado: cinta de 32 dp", q.rect.top, 32 * dp);
            near("Flip apaisado: alto total", kb.getMeasuredHeight(), (32 + 4 * 36 + 6) * dp);
            near("Flip apaisado: la fila inferior cabe entera (nada se corta)", dot.rect.bottom, kb.getMeasuredHeight() - 6 * dp);
            check(b.getHeight() == kb.getMeasuredHeight(), "la captura mide lo que el teclado");
            // El alto elegido no amplia en esta clase: al 130 % sigue en 36 dp y dentro del tope
            kb.setHeightPct(130);
            layout(kb, FLIP_LAND_W);
            near("Flip apaisado: el 130 % no amplia", kb.keyFor("q").rect.height(), 36 * dp);
            check(kb.getMeasuredHeight() <= limit, "Flip apaisado al 130 %: " + kb.getMeasuredHeight() + " px (tope " + limit + ")");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }
    }

    // ------------------------------------------------------------------ vertical como Samsung Keyboard

    @Test
    public void verticalComoSamsung() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Themes.Theme t = Themes.NOCHE;                       // sin sombra, borde ni halo: los huecos son fondo puro
        AurebeshKeyboardView kb = view(app, W, t.id);
        Bitmap b = render(kb, W);
        save(b, "geometria_samsung.png");
        AurebeshKeyboardView.Key q = kb.keyFor("q"), w = kb.keyFor("w"), p = kb.keyFor("p"), a = kb.keyFor("a"), one = kb.keyFor("1");
        float margin = Math.max(8 * dp, 0.021f * W);         // a 360 dp el 2,1 % (7,56 dp) no llega al minimo de 8 dp
        near("margen lateral del 2,1 % del ancho (minimo 8 dp)", kb.keyMargin(), margin);
        near("q al margen", q.rect.left, margin);
        near("p al margen derecho", p.rect.right, W - margin);
        float step = (W - 2 * margin) / 10f;
        near("paso de columna = (ancho - 2 margenes) / 10", q.rect.width(), step);
        near("w a un paso de la q", w.rect.left - q.rect.left, step);
        near("fila de letras: paso de 64 dp", q.rect.height(), 64 * dp);
        near("fila de digitos: paso de 56 dp", one.rect.height(), 56 * dp);
        near("cinta de 40 dp", one.rect.top, 40 * dp);
        near("a pegada bajo la q (el aire va dentro de la celda)", a.rect.top, q.rect.bottom);
        near("alto total: cinta + digitos + 4 filas + banda", kb.getMeasuredHeight(), (40 + 56 + 4 * 64 + 6) * dp);

        // La cara lleva el aire dentro de la celda: 3 dp a los lados (6 dp entre caras) y 7,25 dp arriba y abajo
        // (49,5 dp de cara en el paso de 64). Se mide en el dibujo: lo que no es fondo del teclado a lo largo de una
        // columna a 7 dp del borde izquierdo de la cara (fuera de la esquina redondeada) y de una fila a 7 dp del superior
        RectF f = face(q, dp);
        check(dist(pixel(b, f.left + 7 * dp, f.top + 7 * dp), t.keyBg) <= 24, "la cara es del color de la tecla");
        int tall = runNotBg(b, f.left + 7 * dp, q.rect.top, f.left + 7 * dp, q.rect.bottom, t);
        near("cara de 49,5 dp de alto (7,25 dp de aire arriba y abajo)", tall, f.height(), 4f);
        int wide = runNotBg(b, q.rect.left, f.top + 7 * dp, q.rect.right, f.top + 7 * dp, t);
        near("cara con 3 dp de aire a cada lado", wide, f.width(), 4f);
        check(isBg(pixel(b, q.rect.right, f.centerY()), t), "entre la q y la w asoma el fondo");
        check(isBg(pixel(b, f.centerX(), q.rect.bottom), t), "entre la q y la a asoma el fondo");
        check(isBg(pixel(b, f.centerX(), q.rect.top + 2 * dp), t), "sobre la cara de la q hay aire");

        // A los 411 dp reales del Z Flip 7 (1233 px a esta densidad) el 2,1 % ya supera el minimo: 8,6 dp y columnas de 39,4 dp
        AurebeshKeyboardView flip = view(app, 1233, t.id);
        float fm = 1233 * 0.021f;
        check(fm > 8 * dp, "a 411 dp el 2,1 % supera los 8 dp");
        near("411 dp: margen del 2,1 %", flip.keyMargin(), fm);
        near("411 dp: q al margen", flip.keyFor("q").rect.left, fm);
        near("411 dp: paso de columna", flip.keyFor("q").rect.width(), (1233 - 2 * fm) / 10f);
    }

    // ------------------------------------------------------------------ pistas abajo a la derecha

    @Test
    public void pistasAbajoDerecha() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Themes.Theme t = Themes.NOCHE;
        AurebeshKeyboardView kb = view(app, W, t.id);
        kb.setHintMode(1);
        Bitmap on = render(kb, W);
        save(on, "pistas_abajo_derecha.png");
        AurebeshKeyboardView.Key q = kb.keyFor("q"), a = kb.keyFor("a");
        RectF fq = face(q, dp), fa = face(a, dp);
        float s = 12 * dp;                                   // cuadrado de sondeo en cada esquina de la cara
        RectF qBR = new RectF(fq.right - s, fq.bottom - s, fq.right, fq.bottom);
        RectF qTL = new RectF(fq.left, fq.top, fq.left + s, fq.top + s);
        RectF qBL = new RectF(fq.left, fq.bottom - s, fq.left + s, fq.bottom);
        RectF qTR = new RectF(fq.right - s, fq.top, fq.right, fq.top + s);
        RectF aBR = new RectF(fa.right - s, fa.bottom - s, fa.right, fa.bottom);
        RectF aTR = new RectF(fa.right - s, fa.top, fa.right, fa.top + s);
        check(dist(pixel(on, fq.left + 7 * dp, fq.top + 7 * dp), t.keyBg) <= 24, "muestra del fondo de la tecla");
        int n = inkCount(on, qBR, t);
        check(n >= 12, "con pistas: la q latina va abajo a la derecha de la cara (" + n + " px de tinta en 12 dp)");
        check(inkCount(on, qTL, t) == 0, "con pistas: la esquina superior izquierda queda limpia");
        check(inkCount(on, qBL, t) == 0, "con pistas: la esquina inferior izquierda queda limpia");
        // Con los caracteres alternativos (activos de fabrica) la q insinua su simbolo (+) y la a el suyo (@) arriba a la derecha
        check(kb.altChars(), "los caracteres alternativos vienen activos de fabrica");
        check(inkCount(on, qTR, t) >= 6, "caracteres alternativos: la q insinua el + arriba a la derecha");
        check(inkCount(on, aBR, t) >= 12, "la a latina abajo a la derecha");
        check(inkCount(on, aTR, t) >= 12, "la a con su simbolo (@, alternativa al mantener) arriba a la derecha, lejos de la letra latina");

        // Sin pistas los simbolos siguen (son informacion funcional, sin desvanecido): la letra latina se va, el @ y el + no
        kb.setHintMode(0);
        Bitmap off = render(kb, W);
        check(inkCount(off, qBR, t) == 0, "sin pistas: la esquina inferior derecha de la q queda limpia");
        check(inkCount(off, aBR, t) == 0, "sin pistas: la a sin letra latina");
        check(inkCount(off, aTR, t) >= 6 && inkCount(off, qTR, t) >= 6, "sin pistas: los simbolos de la a y la q se insinuan igual");
        check(inkCount(off, fq, t) > 0, "sin pistas sigue el glifo");

        // Con la opcion apagada vuelve la premisa anterior: la q sin alternativa en espanol y la a con la tilde, que si sigue a las pistas
        kb.setAltChars(false);
        kb.setHintMode(1);
        Bitmap plain = render(kb, W);
        check(inkCount(plain, qTR, t) == 0, "sin caracteres alternativos: la q no tiene alternativa en espanol: arriba a la derecha nada");
        check(inkCount(plain, aTR, t) >= 12, "sin caracteres alternativos: la a con tilde (alternativa al mantener) arriba a la derecha");
        check(inkCount(plain, qBR, t) >= 12 && inkCount(plain, aBR, t) >= 12, "sin caracteres alternativos: las letras latinas siguen abajo a la derecha");
        kb.setHintMode(0);
        Bitmap none = render(kb, W);
        check(inkCount(none, aBR, t) == 0 && inkCount(none, aTR, t) == 0, "sin pistas ni caracteres alternativos: la a sin letra latina ni tilde");
        check(inkCount(none, qTR, t) == 0 && inkCount(none, qBR, t) == 0, "sin pistas ni caracteres alternativos: la q limpia en las dos esquinas de la derecha");
        check(inkCount(none, fq, t) > 0, "sin pistas sigue el glifo");
    }

    // ------------------------------------------------------------------ glifos uniformes

    @Test
    public void glifosUniformes() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Themes.Theme t = Themes.NOCHE;
        AurebeshKeyboardView kb = view(app, W, t.id);
        // Sin pistas en la cara queda solo el glifo: la franja de 14 dp del enunciado no cabe en una cara de 28 x 49,5 dp
        // sin comerse el glifo (que ocupa el 50 % del alto), asi que las pistas se excluyen apagandolas. Tambien los
        // caracteres alternativos: el simbolo (el > de la i) se insinua con alfa 1 aunque no haya pistas y entraria en la caja de tinta
        kb.setHintMode(0);
        kb.setAltChars(false);
        String[] letters = {"i", "l", "m", "w"};
        float[][] box100 = null;
        float face100 = 0;
        for (int pass = 0; pass < 2; pass++) {
            int pct = pass == 0 ? 100 : 150;
            kb.setHeightPct(pct);
            Bitmap b = render(kb, W);
            save(b, pass == 0 ? "glifos_uniformes.png" : "glifos_uniformes_150.png");
            float[][] boxes = new float[letters.length][];
            float faceH = 0;
            for (int i = 0; i < letters.length; i++) {
                AurebeshKeyboardView.Key k = kb.keyFor(letters[i]);
                RectF f = face(k, dp);
                faceH = f.height();
                RectF inner = new RectF(f.left + dp, f.top + dp, f.right - dp, f.bottom - dp);   // sin el borde suavizado de la cara
                int[] box = inkBox(b, inner, t);
                check(box != null, pct + " %: la " + letters[i] + " no tiene tinta");
                boxes[i] = new float[]{box[2] - box[0] + 1, box[3] - box[1] + 1};   // ancho y alto de la tinta en px
            }
            float hw = boxes[3][1];
            for (int i = 0; i < 3; i++) {
                check(boxes[i][1] <= 1.1f * hw, pct + " %: la " + letters[i] + " mide " + boxes[i][1]
                        + " px de alto y la w " + hw + ": mas de un 10 % mas alta");
            }
            if (pass == 0) {
                box100 = boxes;
                face100 = faceH;
            } else {
                // Al 150 % (el alto se acota al 140 %) la cara es mas alta y cada glifo conserva su relacion de aspecto
                check(kb.heightPct() == 140, "el alto se acota al 140 %: " + kb.heightPct());
                check(faceH > face100 + 10 * dp, "al 140 % la cara es mas alta: " + faceH + " vs " + face100);
                for (int i = 0; i < letters.length; i++) {
                    float r100 = box100[i][1] / box100[i][0], r150 = boxes[i][1] / boxes[i][0];
                    check(Math.abs(r150 - r100) <= 0.1f * r100, "la " + letters[i] + " cambia de proporcion al agrandar: "
                            + r100 + " -> " + r150);
                }
            }
        }
    }

    // ------------------------------------------------------------------ alternativas por idioma

    @Test
    public void alternativasPorIdioma() throws Exception {
        // Tablas (sin vista)
        check(Alternatives.alts("es", "c") == null, "es: la c no lleva cedilla");
        check(Alternatives.alts("es", "s") == null, "es: la s no lleva eszett");
        check(Alternatives.alts("es", "n") == null, "es: la n no insinua nada (la n con virgulilla tiene tecla)");
        check(Alternatives.alts("es", "ñ") == null, "es: la n con virgulilla no tiene alternativas");
        String[] esA = Alternatives.alts("es", "a");
        check(esA != null && esA.length > 0 && "á".equals(esA[0]), "es: la a empieza por a con tilde: " + Arrays.toString(esA));
        String[] deS = Alternatives.alts("de", "s");
        check(deS != null && "ß".equals(deS[0]), "de: la s da eszett: " + Arrays.toString(deS));
        String[] enC = Alternatives.alts("en", "c");
        check(enC != null && "ç".equals(enC[0]), "en: la c da cedilla: " + Arrays.toString(enC));
        check("á".equals(Alternatives.hint("es", "a")), "hint es a: a con tilde");
        check(Alternatives.hint("en", "a") == null, "hint en a: nada");
        check("ß".equals(Alternatives.hint("de", "s")), "hint de s: eszett");
        check(Alternatives.hint("es", "s") == null && Alternatives.hint("es", "c") == null, "hint es s y c: nada");
        check(Alternatives.hint("de", "a") == null, "hint de a: nada (la a con dieresis tiene tecla)");
        check(Alternatives.alts("es", null) == null && Alternatives.hint("es", null) == null, "null tolerado");

        // En la vista, por idioma, sin los caracteres alternativos (activos de fabrica, ponen el simbolo de cada letra
        // delante: lo cubre AltCharsTest): las alternativas son las de la tabla
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W, Themes.ESTELAR.id);
        check(kb.altChars(), "los caracteres alternativos vienen activos de fabrica");
        kb.setAltChars(false);
        check(!kb.altChars(), "setAltChars(false) apaga la opcion");
        kb.setLanguage("es");
        layout(kb, W);
        check(kb.keyFor("s").alts == null, "vista es: la s sin alternativas");
        check(kb.keyFor("c").alts == null, "vista es: la c sin alternativas");
        check(kb.keyFor("ñ").alts == null, "vista es: la n con virgulilla sin alternativas");
        check("á".equals(kb.keyFor("a").alts[0]), "vista es: la a da a con tilde");
        kb.setLanguage("de");
        layout(kb, W);
        check("ß".equals(kb.keyFor("s").alts[0]), "vista de: la s da eszett");
        check("ä".equals(kb.keyFor("a").alts[0]), "vista de: la a da a con dieresis primero");
        check(kb.keyFor("ü") != null, "vista de: tecla propia para la u con dieresis");
        kb.setLanguage("en");
        layout(kb, W);
        check("ç".equals(kb.keyFor("c").alts[0]), "vista en: la c da cedilla");
        check("ñ".equals(kb.keyFor("n").alts[0]), "vista en: la n da n con virgulilla");
        check(kb.keyFor("s").alts == null, "vista en: la s sin alternativas");

        // Con la fila de numeros oculta (tapa del Flip) la fila superior sigue dando los digitos como primera alternativa
        RuntimeEnvironment.setQualifiers(FLIP_COVER);
        try {
            AurebeshKeyboardView cover = view(app, W, Themes.ESTELAR.id);
            check(cover.keyFor("1") == null, "tapa: sin fila de numeros");
            check("1".equals(cover.keyFor("q").alts[0]), "tapa: la q da 1");
            check("0".equals(cover.keyFor("p").alts[0]), "tapa: la p da 0");
            String[] e = cover.keyFor("e").alts;
            check("3".equals(e[0]) && "é".equals(e[1]), "tapa: la e da 3 y luego e con tilde (el digito manda, sin simbolo): " + Arrays.toString(e));
            // Con los caracteres alternativos (de fabrica) la s y la a, fuera de la fila superior, empiezan por su simbolo...
            check("#".equals(cover.keyFor("s").alts[0]), "tapa: la s da su simbolo: " + Arrays.toString(cover.keyFor("s").alts));
            String[] a = cover.keyFor("a").alts;
            check("@".equals(a[0]) && "á".equals(a[1]), "tapa: la a da @ y luego a con tilde: " + Arrays.toString(a));
            // ...y sin ellos siguen como la tabla, con los digitos intactos
            cover.setAltChars(false);
            layout(cover, W);
            check(cover.keyFor("s").alts == null, "tapa: la s sigue sin alternativas");
            check("á".equals(cover.keyFor("a").alts[0]), "tapa: la a sigue dando a con tilde");
            check("1".equals(cover.keyFor("q").alts[0]) && "0".equals(cover.keyFor("p").alts[0]), "tapa sin caracteres alternativos: q y p siguen dando 1 y 0");
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }
    }
}
