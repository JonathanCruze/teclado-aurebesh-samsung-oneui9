package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import java.util.Arrays;

/**
 * Caracteres alternativos (2.1, la opcion homonima de Samsung Keyboard): con la opcion activa (de fabrica) cada letra
 * lleva el simbolo de la pagina ?123 que le toca por idioma ({@link Alternatives#symbol}) insinuado arriba a la
 * derecha de la cara y como primera alternativa al mantenerla, delante de los acentos; apagada, vuelven las
 * alternativas de la tabla sin mas. Sin fila de numeros el digito manda en la fila superior y la tecla no lleva su
 * simbolo. Ademas: los umlauts del QWERTZ llevan el glifo de la letra base y la latina completa abajo a la derecha;
 * la fila de numeros tiene fondo de tecla como las letras (tambien en Contraste); y en pantallas de 600 dp o mas el
 * aire horizontal de la cara es el 11 % de la unidad, entre 3 y 7 dp (en el telefono siguen los 3 dp fijos).
 * Con el calificador xxhdpi de Robolectric la densidad es 3: 1080 px son 360 dp y 2176 px (Fold) son 725 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AltCharsTest {

    private static final int W = 1080;
    /** Fold desplegado a esta densidad (725 dp), como la captura dividido_fold de ScreenshotTest. */
    private static final int FOLD_W = 2176;
    private static final String PHONE = "es-rES-w411dp-h914dp-xxhdpi";
    /** Tapa exterior del Z Flip: 360 x 374 dp, sin fila de numeros aunque este activada. */
    private static final String FLIP_COVER = "es-rES-w360dp-h374dp-xxhdpi";
    /** Fold desplegado a densidad 2 (xhdpi): 840 x 900 dp, 1680 px de ancho. */
    private static final String FOLD_XHDPI = "es-rES-w840dp-h900dp-xhdpi";
    private static final int FOLD_XHDPI_W = 1680;
    /** Aire de la cara dentro de su celda en un telefono: 3 dp a los lados y 7,25 dp arriba y abajo. */
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

    private static void event(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void down(View v, float x, float y) { event(v, MotionEvent.ACTION_DOWN, x, y); }
    private static void move(View v, float x, float y) { event(v, MotionEvent.ACTION_MOVE, x, y); }
    private static void up(View v, float x, float y) { event(v, MotionEvent.ACTION_UP, x, y); }
    private static void tap(View v, AurebeshKeyboardView.Key k) { down(v, k.rect.centerX(), k.rect.centerY()); up(v, k.rect.centerX(), k.rect.centerY()); }

    private static void idle(long ms) { ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(ms)); }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void near(String what, float actual, float expected, float tol) {
        if (Math.abs(actual - expected) > tol) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }

    /** Lista legible de las alternativas de una tecla (o "null"). */
    private static String alts(AurebeshKeyboardView kb, String out) {
        AurebeshKeyboardView.Key k = kb.keyFor(out);
        return k == null ? "(sin tecla " + out + ")" : Arrays.toString(k.alts);
    }

    /** Primera alternativa de la tecla, o null si no tiene. */
    private static String first(AurebeshKeyboardView kb, String out) {
        AurebeshKeyboardView.Key k = kb.keyFor(out);
        check(k != null, "falta la tecla " + out);
        return k.alts == null ? null : k.alts[0];
    }

    private static boolean has(AurebeshKeyboardView kb, String out, String alt) {
        AurebeshKeyboardView.Key k = kb.keyFor(out);
        if (k == null || k.alts == null) return false;
        for (String a : k.alts) if (alt.equals(a)) return true;
        return false;
    }

    /** Teclado sin vibracion ni sonido ni pistas, al 100 %, con ese tema y la fila de numeros segun se pida, medido a {@code w} px. */
    private static AurebeshKeyboardView view(Application app, int w, String themeId, boolean numbers) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, themeId, numbers, false, 1f);
        kb.setEnterAction(4);
        kb.setListener(new SizeTest.Recorder());       // sin oyente las teclas de funcion no hacen nada
        layout(kb, w);
        return kb;
    }

    /** Cara de la tecla en un telefono: la celda (zona de toque) menos el aire de 3 x 7,25 dp. */
    private static RectF face(AurebeshKeyboardView.Key k, float dp) {
        return new RectF(k.rect.left + INSET_X_DP * dp, k.rect.top + INSET_Y_DP * dp,
                k.rect.right - INSET_X_DP * dp, k.rect.bottom - INSET_Y_DP * dp);
    }

    /** Cuadrado de {@code s} px en la esquina superior derecha de la cara (donde se insinua la alternativa). */
    private static RectF topRight(RectF f, float s) { return new RectF(f.right - s, f.top, f.right, f.top + s); }

    /** Cuadrado de {@code s} px en la esquina inferior derecha de la cara (donde va la letra latina). */
    private static RectF bottomRight(RectF f, float s) { return new RectF(f.right - s, f.bottom - s, f.right, f.bottom); }

    /** Zona central de la cara donde vive el glifo (el 50 % del alto y el 74 % del ancho, centrados), sin las esquinas. */
    private static RectF middle(RectF f) {
        return new RectF(f.centerX() - 0.37f * f.width(), f.centerY() - 0.25f * f.height(),
                f.centerX() + 0.37f * f.width(), f.centerY() + 0.25f * f.height());
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

    /** Pixeles del rectangulo que se alejan del fondo de la tecla (sirve en Contraste, donde la tinta es del color del fondo del teclado). */
    private static int notKeyBg(Bitmap b, RectF r, Themes.Theme t) {
        int n = 0;
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int px = b.getPixel(x, y);
            if (Color.alpha(px) >= 128 && dist(px, t.keyBg) > INK) n++;
        }
        return n;
    }

    /** Pixeles del rectangulo del color {@code color} (a 24 de distancia). */
    private static int countColor(Bitmap b, RectF r, int color) {
        int n = 0;
        int x0 = Math.max(0, Math.round(r.left)), x1 = Math.min(b.getWidth(), Math.round(r.right));
        int y0 = Math.max(0, Math.round(r.top)), y1 = Math.min(b.getHeight(), Math.round(r.bottom));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int px = b.getPixel(x, y);
            if (Color.alpha(px) >= 128 && dist(px, color) <= 24) n++;
        }
        return n;
    }

    /** Pixeles que no son fondo del teclado a lo largo de una linea horizontal en {@code y}, de x0 a x1. */
    private static int runNotBg(Bitmap b, float x0, float x1, float y, Themes.Theme t) {
        int n = 0;
        int yy = Math.round(y);
        for (int x = Math.round(x0); x < Math.round(x1); x++) if (!isBg(b.getPixel(x, yy), t)) n++;
        return n;
    }

    /** Ancho en px de la cara de la tecla en el dibujo: lo que no es fondo a 7 dp bajo el borde superior de la cara (fuera de la esquina redondeada). */
    private static float faceWidth(Bitmap b, AurebeshKeyboardView.Key k, float dp, Themes.Theme t) {
        return runNotBg(b, k.rect.left, k.rect.right, k.rect.top + INSET_Y_DP * dp + 7 * dp, t);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int pixel(Bitmap b, float x, float y) { return b.getPixel(Math.round(x), Math.round(y)); }

    // ------------------------------------------------------------------ simbolos por idioma

    @Test
    public void simbolosPorIdioma() throws Exception {
        // Tabla (sin vista): el simbolo de cada letra por idioma, o null
        check("+".equals(Alternatives.symbol("es", "q")), "es q: +");
        check("@".equals(Alternatives.symbol("es", "a")), "es a: @");
        check("#".equals(Alternatives.symbol("es", "s")), "es s: #");
        check("?".equals(Alternatives.symbol("es", "m")), "es m: ?");
        check("!".equals(Alternatives.symbol("es", "ñ")), "es n con virgulilla: !");
        check("(".equals(Alternatives.symbol("es", "o")) && ")".equals(Alternatives.symbol("es", "p")), "es o p: ( )");
        check("¿".equals(Alternatives.symbol("es", "k")) && "¡".equals(Alternatives.symbol("es", "l")), "es k l: apertura de interrogacion y exclamacion");
        check("[".equals(Alternatives.symbol("en", "o")) && "]".equals(Alternatives.symbol("en", "p")), "en o p: [ ]");
        check("(".equals(Alternatives.symbol("en", "k")) && ")".equals(Alternatives.symbol("en", "l")), "en k l: ( )");
        check("{".equals(Alternatives.symbol("de", "ü")), "de u con dieresis: {");
        check("!".equals(Alternatives.symbol("de", "ö")) && "?".equals(Alternatives.symbol("de", "ä")), "de o y a con dieresis: ! ?");
        check(Alternatives.symbol("es", "1") == null && Alternatives.symbol("es", ".") == null, "los digitos y signos no tienen simbolo");
        check(Alternatives.symbol("es", null) == null, "null tolerado");

        // En la vista, con la opcion activa de fabrica: el simbolo primero y despues los acentos de la tabla
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W, Themes.ESTELAR.id, true);
        check(kb.altChars(), "caracteres alternativos activos de fabrica");
        kb.setLanguage("es");
        layout(kb, W);
        check("+".equals(first(kb, "q")), "es: la q da + al mantener: " + alts(kb, "q"));
        check("@".equals(first(kb, "a")), "es: la a da @ primero: " + alts(kb, "a"));
        check(has(kb, "a", "á"), "es: la a conserva la a con tilde: " + alts(kb, "a"));
        check("á".equals(kb.keyFor("a").alts[1]), "es: la a con tilde va justo detras del simbolo: " + alts(kb, "a"));
        check("?".equals(first(kb, "m")), "es: la m da ?: " + alts(kb, "m"));
        check("!".equals(first(kb, "ñ")), "es: la n con virgulilla da !: " + alts(kb, "ñ"));
        check("#".equals(first(kb, "s")) && kb.keyFor("s").alts.length == 1, "es: la s solo da #: " + alts(kb, "s"));
        check("\"".equals(first(kb, "c")) && kb.keyFor("c").alts.length == 1, "es: la c solo da comillas: " + alts(kb, "c"));
        check("(".equals(first(kb, "o")) && has(kb, "o", "ó"), "es: la o da ( y luego sus acentos: " + alts(kb, "o"));
        check(first(kb, "1") == null, "es: los digitos de la fila de numeros no llevan simbolo");

        kb.setLanguage("en");
        layout(kb, W);
        check("[".equals(first(kb, "o")), "en: la o da [: " + alts(kb, "o"));
        check("(".equals(first(kb, "k")), "en: la k da (: " + alts(kb, "k"));
        check("]".equals(first(kb, "p")) && ")".equals(first(kb, "l")), "en: p y l dan ] y )");
        check("@".equals(first(kb, "a")) && has(kb, "a", "æ"), "en: la a da @ y conserva la ae: " + alts(kb, "a"));
        check(",".equals(first(kb, "n")) && has(kb, "n", "ñ"), "en: la n da , y conserva la n con virgulilla: " + alts(kb, "n"));
        check("\"".equals(first(kb, "c")) && has(kb, "c", "ç"), "en: la c da comillas y conserva la cedilla: " + alts(kb, "c"));
        check("#".equals(first(kb, "s")) && kb.keyFor("s").alts.length == 1, "en: la s solo da #: " + alts(kb, "s"));
        check(kb.keyFor("ñ") == null, "en: sin tecla de n con virgulilla");

        kb.setLanguage("de");
        layout(kb, W);
        String[] s = kb.keyFor("s").alts;
        check(s != null && s.length >= 2 && "#".equals(s[0]) && "ß".equals(s[1]), "de: la s da # y luego eszett: " + Arrays.toString(s));
        check("{".equals(first(kb, "ü")), "de: la u con dieresis da {: " + alts(kb, "ü"));
        check("!".equals(first(kb, "ö")) && "?".equals(first(kb, "ä")), "de: o y a con dieresis dan ! y ?");
        String[] a = kb.keyFor("a").alts;
        check("@".equals(a[0]) && "ä".equals(a[1]) && has(kb, "a", "á"), "de: la a da @, a con dieresis y luego el resto: " + Arrays.toString(a));
        String[] u = kb.keyFor("u").alts;
        check("<".equals(u[0]) && "ü".equals(u[1]) && has(kb, "u", "ú"), "de: la u da <, u con dieresis y el resto: " + Arrays.toString(u));
        check("[".equals(first(kb, "o")) && "]".equals(first(kb, "p")), "de: o y p dan [ ]");
        for (String letter : new String[]{"s", "a", "o", "u"}) {           // sin duplicados en el menu
            String[] x = kb.keyFor(letter).alts;
            for (int i = 0; i < x.length; i++) for (int j = i + 1; j < x.length; j++) {
                check(!x[i].equals(x[j]), "de: la " + letter + " repite " + x[i] + ": " + Arrays.toString(x));
            }
        }
    }

    // ------------------------------------------------------------------ opcion apagada

    @Test
    public void sinCaracteresAlternativos() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W, Themes.ESTELAR.id, true);
        kb.setLanguage("es");
        kb.setAltChars(false);
        layout(kb, W);
        check(!kb.altChars(), "altChars() refleja setAltChars(false)");
        check("á".equals(first(kb, "a")), "es sin la opcion: la a da a con tilde: " + alts(kb, "a"));
        check(!has(kb, "a", "@"), "es sin la opcion: la a no ofrece @: " + alts(kb, "a"));
        check(kb.keyFor("q").alts == null, "es sin la opcion: la q sin alternativas: " + alts(kb, "q"));
        check(kb.keyFor("s").alts == null, "es sin la opcion: la s sin alternativas: " + alts(kb, "s"));
        check(kb.keyFor("m").alts == null && kb.keyFor("ñ").alts == null, "es sin la opcion: m y n con virgulilla sin alternativas");
        kb.setLanguage("de");
        layout(kb, W);
        check("ß".equals(first(kb, "s")), "de sin la opcion: la s da eszett: " + alts(kb, "s"));
        check(kb.keyFor("ü").alts == null, "de sin la opcion: la u con dieresis sin alternativas: " + alts(kb, "ü"));
        kb.setLanguage("en");
        layout(kb, W);
        check("ç".equals(first(kb, "c")) && kb.keyFor("o").alts != null && "ó".equals(first(kb, "o")), "en sin la opcion: tabla intacta");
        // Volver a encenderla reconstruye las teclas con el simbolo delante
        kb.setAltChars(true);
        layout(kb, W);
        check(kb.altChars() && "[".equals(first(kb, "o")) && "\"".equals(first(kb, "c")), "al encenderla vuelve el simbolo: " + alts(kb, "o"));
        // setAltChars con el mismo valor no cambia nada
        kb.setAltChars(true);
        layout(kb, W);
        check("[".equals(first(kb, "o")), "repetir el valor es inocuo");
    }

    // ------------------------------------------------------------------ sin fila de numeros: el digito manda

    @Test
    public void filaDeNumerosOculta() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();

        // Tapa del Z Flip (pantalla baja): la fila de numeros se esconde aunque este activada
        RuntimeEnvironment.setQualifiers(FLIP_COVER);
        try {
            AurebeshKeyboardView cover = view(app, W, Themes.ESTELAR.id, true);
            check(cover.keyFor("1") == null, "tapa: sin fila de numeros");
            check("1".equals(first(cover, "q")), "tapa: la q da 1: " + alts(cover, "q"));
            check(!has(cover, "q", "+"), "tapa: la q no lleva su simbolo (el menu no se llena): " + alts(cover, "q"));
            check("0".equals(first(cover, "p")) && !has(cover, "p", ")"), "tapa: la p da 0 y sin simbolo: " + alts(cover, "p"));
            check("3".equals(first(cover, "e")) && has(cover, "e", "é") && !has(cover, "e", "÷"), "tapa: la e da 3 y sus acentos, sin simbolo: " + alts(cover, "e"));
            check("@".equals(first(cover, "a")), "tapa: la a (fuera de la fila superior) sigue dando @: " + alts(cover, "a"));
            check("#".equals(first(cover, "s")) && "!".equals(first(cover, "ñ")), "tapa: s y n con virgulilla con su simbolo");
            cover.setLanguage("de");
            layout(cover, W);
            check("1".equals(first(cover, "q")) && !has(cover, "q", "+"), "tapa de: la q da 1 sin simbolo: " + alts(cover, "q"));
            check("{".equals(first(cover, "ü")), "tapa de: la u con dieresis, que no recibe digito, conserva su {: " + alts(cover, "ü"));
            check("8".equals(first(cover, "i")) && "9".equals(first(cover, "o")) && "0".equals(first(cover, "p")), "tapa de: i o p dan 8 9 0");
            String[] u = cover.keyFor("u").alts;
            check("7".equals(u[0]) && "ü".equals(u[1]) && !has(cover, "u", "<"), "tapa de: la u da 7, luego u con dieresis, sin simbolo: " + Arrays.toString(u));
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }

        // Preferencia de fila de numeros desactivada en un telefono: lo mismo
        AurebeshKeyboardView kb = view(app, W, Themes.ESTELAR.id, false);
        check(kb.keyFor("1") == null, "sin fila de numeros");
        check("1".equals(first(kb, "q")) && !has(kb, "q", "+"), "sin fila: la q da 1 y no +: " + alts(kb, "q"));
        check("@".equals(first(kb, "a")), "sin fila: la a da @: " + alts(kb, "a"));
        check("0".equals(first(kb, "p")), "sin fila: la p da 0");
        // Al activarla de nuevo la q recupera su simbolo y pierde el digito (que ya tiene tecla)
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);
        layout(kb, W);
        check(kb.keyFor("1") != null, "fila de numeros de vuelta");
        check("+".equals(first(kb, "q")) && !has(kb, "q", "1"), "con fila: la q da + y no 1: " + alts(kb, "q"));
    }

    // ------------------------------------------------------------------ menu al mantener

    @Test
    public void menuEmpiezaPorElSimbolo() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        AurebeshKeyboardView kb = view(app, W, Themes.ESTELAR.id, true);
        SizeTest.Recorder rec = new SizeTest.Recorder();
        kb.setListener(rec);

        // Mantener la s (espanol): el # y, de ultima celda, la propia s; soltar sin mover escribe #
        AurebeshKeyboardView.Key s = kb.keyFor("s");
        down(kb, s.rect.centerX(), s.rect.centerY());
        idle(450);
        String[] opts = kb.popupOptions();
        check(opts != null && Arrays.toString(opts).equals("[#, s]"), "menu de la s: " + (opts == null ? "null" : Arrays.toString(opts)));
        save(render(kb, W), "caracteres_alternativos_menu_s.png");
        up(kb, s.rect.centerX(), s.rect.centerY());
        check("#".equals(rec.typed.toString()), "mantener la s escribe #: " + rec.typed);
        rec.reset();

        // Mantener la a: @, los acentos y la propia a de ultima celda; soltar sin mover escribe @
        AurebeshKeyboardView.Key a = kb.keyFor("a");
        down(kb, a.rect.centerX(), a.rect.centerY());
        idle(450);
        opts = kb.popupOptions();
        check(opts != null && opts.length >= 3 && "@".equals(opts[0]) && "á".equals(opts[1]) && "a".equals(opts[opts.length - 1]),
                "menu de la a: " + (opts == null ? "null" : Arrays.toString(opts)));
        save(render(kb, W), "caracteres_alternativos_menu_a.png");
        up(kb, a.rect.centerX(), a.rect.centerY());
        check("@".equals(rec.typed.toString()), "mantener la a escribe @: " + rec.typed);
        rec.reset();

        // Deslizar hasta la celda de la a con tilde (indice 1) y soltar
        down(kb, a.rect.centerX(), a.rect.centerY());
        idle(450);
        RectF c1 = kb.popupCellRect(1);
        move(kb, c1.centerX(), c1.centerY());
        up(kb, c1.centerX(), c1.centerY());
        check("á".equals(rec.typed.toString()), "celda de la a con tilde: " + rec.typed);
        rec.reset();

        // Un toque normal sigue escribiendo la letra
        tap(kb, s);
        tap(kb, a);
        check("sa".equals(rec.typed.toString()), "toque normal: " + rec.typed);
        rec.reset();

        // Sin la opcion, la s no tiene menu
        kb.setAltChars(false);
        layout(kb, W);
        s = kb.keyFor("s");
        down(kb, s.rect.centerX(), s.rect.centerY());
        idle(450);
        check(kb.popupOptions() == null, "sin caracteres alternativos la s no abre menu");
        up(kb, s.rect.centerX(), s.rect.centerY());
        rec.reset();
        a = kb.keyFor("a");
        down(kb, a.rect.centerX(), a.rect.centerY());
        idle(450);
        opts = kb.popupOptions();
        check(opts != null && "á".equals(opts[0]), "sin la opcion el menu de la a empieza por la a con tilde: " + Arrays.toString(opts));
        up(kb, a.rect.centerX(), a.rect.centerY());
        check("á".equals(rec.typed.toString()), "sin la opcion mantener la a escribe a con tilde: " + rec.typed);
    }

    // ------------------------------------------------------------------ el simbolo en la cara

    @Test
    public void simboloEnLaCara() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Themes.Theme t = Themes.NOCHE;                       // sin sombra, borde ni halo: lo que no es fondo es tinta
        AurebeshKeyboardView kb = view(app, W, t.id, true);  // espanol, sin pistas
        kb.setLanguage("es");
        Bitmap on = render(kb, W);
        save(on, "caracteres_alternativos.png");
        AurebeshKeyboardView.Key s = kb.keyFor("s"), a = kb.keyFor("a"), q = kb.keyFor("q");
        RectF fs = face(s, dp), fa = face(a, dp), fq = face(q, dp);
        float sq = 12 * dp;                                  // cuadrado de sondeo de 12 dp en cada esquina
        check(dist(pixel(on, fs.left + 7 * dp, fs.top + 7 * dp), t.keyBg) <= 24, "muestra del fondo de la tecla");
        int n = inkCount(on, topRight(fs, sq), t);
        check(n >= 6, "con la opcion la s insinua el # arriba a la derecha de la cara (" + n + " px de tinta en 12 dp)");
        check(inkCount(on, topRight(fa, sq), t) >= 6, "la a insinua el @");
        check(inkCount(on, topRight(fq, sq), t) >= 6, "la q insinua el +");
        check(inkCount(on, bottomRight(fs, sq), t) == 0, "sin pistas no hay letra latina abajo a la derecha");
        check(inkCount(on, new RectF(fs.left, fs.top, fs.left + sq, fs.top + sq), t) == 0, "la esquina superior izquierda queda limpia");
        check(inkCount(on, middle(fs), t) > 0, "el glifo de la s sigue en el centro");

        // Con pistas (modo siempre) el simbolo sigue arriba y la s latina va abajo a la derecha, lejos de el
        kb.setHintMode(1);
        Bitmap hints = render(kb, W);
        save(hints, "caracteres_alternativos_pistas.png");
        check(inkCount(hints, topRight(fs, sq), t) >= 6, "con pistas: el # sigue arriba a la derecha");
        check(inkCount(hints, bottomRight(fs, sq), t) >= 12, "con pistas: la s latina abajo a la derecha");
        kb.setHintMode(0);

        // Sin la opcion, en espanol la s no insinua nada (ni cedilla ni eszett ni simbolo)
        kb.setAltChars(false);
        Bitmap off = render(kb, W);
        save(off, "caracteres_alternativos_off.png");
        check(inkCount(off, topRight(fs, sq), t) == 0, "sin la opcion la s no insinua nada arriba a la derecha");
        check(inkCount(off, topRight(fq, sq), t) == 0, "sin la opcion la q no insinua nada");
        check(inkCount(off, topRight(fa, sq), t) == 0, "sin la opcion ni pistas la a tampoco (la tilde sigue a las pistas)");
        check(inkCount(off, middle(fs), t) > 0, "el glifo de la s sigue");
        // El tema Estelar (con sombra) tambien pinta el simbolo
        kb.setAltChars(true);
        AurebeshKeyboardView est = view(app, W, Themes.ESTELAR.id, true);
        Bitmap eb = render(est, W);
        RectF efs = face(est.keyFor("s"), dp);
        check(inkCount(eb, topRight(efs, sq), Themes.ESTELAR) >= 6, "Estelar: el # de la s");
    }

    // ------------------------------------------------------------------ umlauts del QWERTZ

    @Test
    public void umlautsDelQwertz() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Themes.Theme t = Themes.NOCHE;
        AurebeshKeyboardView kb = view(app, W, t.id, true);  // sin pistas: lo que se vea abajo a la derecha es por la letra misma
        kb.setLanguage("de");
        layout(kb, W);
        Bitmap b = render(kb, W);
        save(b, "qwertz_umlauts.png");
        float sq = 12 * dp;
        for (String u : new String[]{"ü", "ö", "ä"}) {
            AurebeshKeyboardView.Key k = kb.keyFor(u);
            check(k != null, "de: falta la tecla " + u);
            check(k.glyph != null && !k.glyph.equals(k.output), "de: la " + u + " dibuja el glifo de la letra base: " + k.glyph);
            check(k.glyph.equals(Glyphs.glyphKey(u)), "de: el glifo de la " + u + " es el de Glyphs.glyphKey: " + k.glyph);
            RectF f = face(k, dp);
            check(dist(pixel(b, f.left + 7 * dp, f.top + 7 * dp), t.keyBg) <= 24, "de: muestra del fondo de la " + u);
            int mid = inkCount(b, middle(f), t);
            check(mid >= 12, "de: la " + u + " tiene el glifo en el centro de la cara (" + mid + " px)");
            int br = inkCount(b, bottomRight(f, sq), t);
            check(br >= 12, "de: la " + u + " latina abajo a la derecha aunque no haya pistas (" + br + " px)");
        }
        // La u normal, sin pistas, no lleva letra latina (solo las que comparten glifo la necesitan para distinguirse)
        RectF fu = face(kb.keyFor("u"), dp);
        check(inkCount(b, bottomRight(fu, sq), t) == 0, "de: la u sin pistas no lleva letra latina");
        check(inkCount(b, middle(fu), t) >= 12, "de: la u con su glifo");
        // Y la u con dieresis insinua su simbolo { arriba a la derecha
        check(inkCount(b, topRight(face(kb.keyFor("ü"), dp), sq), t) >= 6, "de: la u con dieresis insinua {");
        // La fila superior tiene 11 teclas: la u con dieresis a la derecha de la p, al margen
        AurebeshKeyboardView.Key p = kb.keyFor("p"), ue = kb.keyFor("ü");
        near("de: la u con dieresis sigue a la p", ue.rect.left, p.rect.right, 1f);
        near("de: la u con dieresis al margen derecho", ue.rect.right, W - kb.keyMargin(), 2.5f);
    }

    // ------------------------------------------------------------------ fila de numeros con teclas

    @Test
    public void filaDeNumerosConTeclas() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;

        // Estelar: la cara del 1 es del color de fondo de tecla (no del teclado), con aire alrededor como las letras
        Themes.Theme t = Themes.ESTELAR;
        AurebeshKeyboardView kb = view(app, W, t.id, true);
        Bitmap b = render(kb, W);
        save(b, "fila_numeros.png");
        AurebeshKeyboardView.Key one = kb.keyFor("1"), two = kb.keyFor("2"), q = kb.keyFor("q");
        check(one != null && two != null, "fila de numeros");
        near("fila de digitos: paso de 56 dp", one.rect.height(), 56 * dp, 2.5f);
        near("fila de letras: paso de 64 dp", q.rect.height(), 64 * dp, 2.5f);
        RectF f = face(one, dp), fq = face(q, dp);
        check(dist(t.keyBg, t.bg) > 24, "Estelar distingue fondo de tecla y de teclado");
        check(dist(pixel(b, f.left + 3 * dp, f.centerY()), t.keyBg) <= 24, "Estelar: la cara del 1 es del color de la tecla (lado izquierdo)");
        check(dist(pixel(b, f.right - 3 * dp, f.centerY()), t.keyBg) <= 24, "Estelar: la cara del 1 es del color de la tecla (lado derecho)");
        check(dist(pixel(b, f.centerX(), f.top + 3 * dp), t.keyBg) <= 24, "Estelar: la cara del 1 es del color de la tecla (arriba)");
        check(!isBg(pixel(b, f.centerX(), f.centerY()), t), "Estelar: el centro de la cara del 1 no es fondo del teclado");
        check(dist(pixel(b, f.centerX(), f.centerY()), t.keyBg) <= 24 || isInk(pixel(b, f.centerX(), f.centerY()), t),
                "Estelar: el centro de la cara del 1 es fondo de tecla (o el propio glifo)");
        int area = Math.round(f.width() * f.height());
        int keyPx = countColor(b, f, t.keyBg), bgPx = countColor(b, f, t.bg);
        check(keyPx > area / 2, "Estelar: la cara del 1 es mayormente fondo de tecla: " + keyPx + " de " + area);
        check(bgPx < area / 20, "Estelar: apenas fondo del teclado dentro de la cara del 1: " + bgPx + " de " + area);
        check(inkCount(b, f, t) > 0, "Estelar: el 1 se dibuja");
        check(dist(pixel(b, fq.left + 3 * dp, fq.centerY()), pixel(b, f.left + 3 * dp, f.centerY())) <= 24, "la fila de numeros lleva el mismo fondo que las letras");
        check(isBg(pixel(b, one.rect.right, f.centerY()), t), "entre el 1 y el 2 asoma el fondo del teclado");
        check(isBg(pixel(b, f.centerX(), one.rect.top + 2 * dp), t), "sobre la cara del 1 hay aire");
        check(isBg(pixel(b, f.centerX(), one.rect.bottom - 2 * dp), t), "bajo la cara del 1 hay aire");
        // La cara del 1 mide lo que la celda menos el aire (3 dp a los lados), como las letras
        RectF probe = new RectF(one.rect.left, f.top + 7 * dp, one.rect.right, f.top + 7 * dp);
        int wide = runNotBg(b, probe.left, probe.right, probe.top, t);
        near("Estelar: cara del 1 con 3 dp de aire a cada lado", wide, f.width(), 4f);

        // Contraste: teclas blancas con borde negro y glifo negro; los digitos se leen sobre su tecla
        Themes.Theme hc = Themes.CONTRASTE;
        AurebeshKeyboardView ck = view(app, W, hc.id, true);
        Bitmap cb = render(ck, W);
        save(cb, "fila_numeros_contraste.png");
        AurebeshKeyboardView.Key cone = ck.keyFor("1");
        RectF cf = face(cone, dp);
        RectF inner = new RectF(cf.left + 4 * dp, cf.top + 4 * dp, cf.right - 4 * dp, cf.bottom - 4 * dp);   // por dentro del borde de 2 dp
        check(dist(pixel(cb, inner.left + dp, inner.centerY()), hc.keyBg) <= 24, "Contraste: la cara del 1 es blanca");
        check(dist(pixel(cb, inner.right - dp, inner.centerY()), hc.keyBg) <= 24, "Contraste: la cara del 1 es blanca (derecha)");
        int ink = notKeyBg(cb, inner, hc);
        check(ink >= 20, "Contraste: el 1 se lee (tinta sobre la tecla blanca): " + ink + " px");
        int innerArea = Math.round(inner.width() * inner.height());
        check(countColor(cb, inner, hc.keyBg) > innerArea / 2, "Contraste: la cara del 1 es mayormente blanca");
        check(isBg(pixel(cb, cone.rect.right, cf.centerY()), hc), "Contraste: entre el 1 y el 2 asoma el fondo negro");
        // Y las letras igual: la cara de la q blanca con su glifo
        RectF cq = face(ck.keyFor("q"), dp);
        RectF cqi = new RectF(cq.left + 4 * dp, cq.top + 4 * dp, cq.right - 4 * dp, cq.bottom - 4 * dp);
        check(notKeyBg(cb, cqi, hc) >= 20, "Contraste: la q se lee");
    }

    // ------------------------------------------------------------------ aire horizontal proporcional

    @Test
    public void aireProporcionalEnPantallasAnchas() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Themes.Theme t = Themes.NOCHE;

        // Fold desplegado (2176 px = 725 dp) dividido, como la captura dividido_fold: unidad de 49,5 dp, aire del 11 % (5,4 dp)
        AurebeshKeyboardView fold = view(app, FOLD_W, t.id, true);
        fold.setSplitMode(1);
        Bitmap fb = render(fold, FOLD_W);
        save(fb, "aire_fold_dividido.png");
        check(fold.isSplit(), "Fold: dividido");
        AurebeshKeyboardView.Key q = fold.keyFor("q");
        float unit = q.rect.width();
        float inset = clamp(0.11f * unit, 3 * dp, 7 * dp);
        check(inset > 3 * dp + 0.5f && inset < 7 * dp - 0.5f, "Fold dividido: el 11 % de la unidad queda entre los topes: " + inset / dp + " dp");
        check(dist(pixel(fb, q.rect.left + inset + 7 * dp, q.rect.top + INSET_Y_DP * dp + 7 * dp), t.keyBg) <= 24, "Fold dividido: muestra del fondo de la tecla");
        near("Fold dividido: cara de unidad - 2 x 0,11 x unidad", faceWidth(fb, q, dp, t), unit - 2 * inset, 4f);
        check(isBg(pixel(fb, q.rect.left + inset / 2f, q.rect.top + INSET_Y_DP * dp + 7 * dp), t), "Fold dividido: a medio aire asoma el fondo");
        check(isBg(pixel(fb, q.rect.right, q.rect.top + INSET_Y_DP * dp + 7 * dp), t), "Fold dividido: entre la q y la w asoma el fondo");
        // Las demas filas comparten el aire de la pagina: la a mide lo mismo
        AurebeshKeyboardView.Key a = fold.keyFor("a");
        near("Fold dividido: la a con el mismo aire", faceWidth(fb, a, dp, t), a.rect.width() - 2 * inset, 4f);

        // Sin dividir: unidad de 65 dp, el 11 % (7,2 dp) se acota a 7 dp
        fold.setSplitMode(2);
        Bitmap ub = render(fold, FOLD_W);
        save(ub, "aire_fold_entero.png");
        check(!fold.isSplit(), "Fold: sin dividir");
        q = fold.keyFor("q");
        float unit2 = q.rect.width();
        check(0.11f * unit2 > 7 * dp, "Fold entero: el 11 % de la unidad (" + 0.11f * unit2 / dp + " dp) supera el tope de 7 dp");
        near("Fold entero: cara de unidad - 14 dp (tope de 7 dp por lado)", faceWidth(ub, q, dp, t), unit2 - 14 * dp, 4f);

        // Fold a densidad 2 (xhdpi, 840 dp): dividido 57 dp de unidad (6,3 dp de aire); entero el tope de 72 dp por tecla y 7 dp de aire
        RuntimeEnvironment.setQualifiers(FOLD_XHDPI);
        try {
            float d2 = app.getResources().getDisplayMetrics().density;
            check(Math.abs(d2 - 2f) < 0.01f, "xhdpi: densidad 2, no " + d2);
            AurebeshKeyboardView x = view(app, FOLD_XHDPI_W, t.id, true);
            x.setSplitMode(1);
            Bitmap xb = render(x, FOLD_XHDPI_W);
            save(xb, "aire_fold_xhdpi.png");
            AurebeshKeyboardView.Key xq = x.keyFor("q");
            float xu = xq.rect.width(), xi = clamp(0.11f * xu, 3 * d2, 7 * d2);
            check(xi > 3 * d2 + 0.5f && xi < 7 * d2 - 0.5f, "xhdpi dividido: aire entre los topes: " + xi / d2 + " dp");
            near("xhdpi dividido: cara de unidad - 2 x 0,11 x unidad", faceWidth(xb, xq, d2, t), xu - 2 * xi, 4f);
            x.setSplitMode(2);
            Bitmap xe = render(x, FOLD_XHDPI_W);
            xq = x.keyFor("q");
            near("xhdpi entero: tecla de 72 dp (tope)", xq.rect.width(), 72 * d2, 2.5f);
            near("xhdpi entero: cara de 72 - 14 dp", faceWidth(xe, xq, d2, t), xq.rect.width() - 14 * d2, 4f);
        } finally {
            RuntimeEnvironment.setQualifiers(PHONE);
        }

        // Telefono (360 dp): 3 dp fijos por lado
        AurebeshKeyboardView phone = view(app, W, t.id, true);
        Bitmap pb = render(phone, W);
        AurebeshKeyboardView.Key pq = phone.keyFor("q");
        check(0.11f * pq.rect.width() > 3 * dp, "telefono: el 11 % de la unidad superaria los 3 dp, pero no se aplica");
        near("telefono: cara de unidad - 6 dp", faceWidth(pb, pq, dp, t), pq.rect.width() - 6 * dp, 4f);
        // Y en la pagina ?123 del telefono igual
        phone.setListener(new SizeTest.Recorder());
        tap(phone, phone.keyFor(AurebeshKeyboardView.CODE_MODE));
        Bitmap sb = render(phone, W);
        AurebeshKeyboardView.Key plus = phone.keyFor("+");
        check(plus != null, "pagina de signos");
        near("telefono ?123: cara de unidad - 6 dp", faceWidth(sb, plus, dp, t), plus.rect.width() - 6 * dp, 4f);
    }
}
