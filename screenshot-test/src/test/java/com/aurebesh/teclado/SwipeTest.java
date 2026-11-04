package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Escritura deslizando: precision del reconocedor con trazos sinteticos (centros de las teclas mas ruido
 * gaussiano) sobre las 200 palabras mas frecuentes de 4 o mas letras, casos explicitos (tildes, letra doble,
 * ingles), tiempo por reconocimiento y el gesto en la vista (toques intactos, deslizar a la vecina intacto).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SwipeTest {

    private static final int W = 1080;
    private static final int WORDS = 200;
    private static final int MIN_LEN = 4;
    /** Ruido principal (desviacion en anchos/altos de tecla) y umbrales de la prueba. */
    private static final float SIGMA = 0.15f;
    private static final double MIN_TOP1 = 0.80, MIN_TOP3 = 0.92;

    private static final class Stroke {
        float[] x, y; long[] t; int n;
    }

    private static AurebeshKeyboardView view(Application app, String lang) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id);
        kb.setLanguage(lang);
        layout(kb);
        return kb;
    }

    private static void layout(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, W, v.getMeasuredHeight());
    }

    /**
     * Trazo sintetico de una palabra: centros de sus teclas (sin tildes) con ruido gaussiano por vertice
     * (sigma * ancho en x, sigma * alto en y), interpolado cada 4 dp con 1.5 dp de temblor y una muestra
     * cada 8 ms. Con {@code loops}, las letras dobles llevan un pequeno bucle sobre la tecla.
     */
    private static Stroke path(SwipeDecoder.Geometry g, float dp, String word, float sigma, Random rnd, boolean loops) {
        String plain = Dictionary.deaccent(word.toLowerCase(Locale.ROOT));
        List<float[]> v = new ArrayList<>();
        for (int i = 0; i < plain.length(); i++) {
            int k = g.indexOf(plain.charAt(i));
            if (k < 0) throw new IllegalArgumentException("sin tecla: " + plain.charAt(i));
            float cx = g.cx[k], cy = g.cy[k];
            if (i > 0 && plain.charAt(i) == plain.charAt(i - 1)) {
                if (!loops) continue;
                float lx = g.keyW * 0.25f, ly = g.keyH * 0.25f;
                v.add(new float[]{cx + lx, cy + ly}); v.add(new float[]{cx + lx, cy - ly});
                v.add(new float[]{cx - lx, cy - ly}); v.add(new float[]{cx - lx, cy + ly});
                continue;
            }
            v.add(new float[]{cx + (float) rnd.nextGaussian() * sigma * g.keyW, cy + (float) rnd.nextGaussian() * sigma * g.keyH});
        }
        List<float[]> pts = new ArrayList<>();
        float step = 4 * dp, jitter = 1.5f * dp;
        pts.add(v.get(0));
        for (int i = 1; i < v.size(); i++) {
            float[] a = v.get(i - 1), b = v.get(i);
            float d = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, Math.round(d / step));
            for (int s = 1; s <= steps; s++) {
                float f = s / (float) steps;
                float jx = s == steps ? 0 : (float) rnd.nextGaussian() * jitter;
                float jy = s == steps ? 0 : (float) rnd.nextGaussian() * jitter;
                pts.add(new float[]{a[0] + f * (b[0] - a[0]) + jx, a[1] + f * (b[1] - a[1]) + jy});
            }
        }
        Stroke st = new Stroke();
        st.n = pts.size();
        st.x = new float[st.n]; st.y = new float[st.n]; st.t = new long[st.n];
        for (int i = 0; i < st.n; i++) { st.x[i] = pts.get(i)[0]; st.y[i] = pts.get(i)[1]; st.t[i] = 1000 + 8L * i; }
        return st;
    }

    /** Las {@code WORDS} palabras mas frecuentes de {@code MIN_LEN} o mas letras que se pueden trazar. */
    private static List<String> targets(Dictionary d, SwipeDecoder.Geometry g) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < d.size() && out.size() < WORDS; r++) {
            String w = d.word(r);
            if (w.length() < MIN_LEN || Dictionary.noSuggest(w)) continue;
            String plain = Dictionary.deaccent(w);
            boolean ok = true;
            for (int i = 0; i < plain.length() && ok; i++) ok = g.indexOf(plain.charAt(i)) >= 0;
            if (ok) out.add(w);
        }
        return out;
    }

    private static boolean sameWord(String a, String b) {
        return Dictionary.deaccent(a).equals(Dictionary.deaccent(b.replace("'", "")));
    }

    private double[] accuracy(String lang, float sigma, boolean print) throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, lang);
        SwipeDecoder.Geometry g = kb.glideGeometry();
        assertNotNull(g);
        Dictionary d = Dictionary.get(app, lang);
        SwipeDecoder dec = new SwipeDecoder(d, g);
        List<String> words = targets(d, g);
        assertEquals(WORDS, words.size());
        Random rnd = new Random(42);
        int top1 = 0, top1Exact = 0, top3 = 0;
        StringBuilder misses = new StringBuilder();
        for (String w : words) {
            Stroke s = path(g, dp, w, sigma, rnd, false);
            List<String> got = dec.decode(s.x, s.y, s.t, s.n);
            if (!got.isEmpty() && got.get(0).equals(w)) top1Exact++;
            if (!got.isEmpty() && sameWord(got.get(0), w)) top1++;
            else misses.append(' ').append(w).append("->").append(got.isEmpty() ? "-" : got.get(0));
            for (int i = 0; i < Math.min(3, got.size()); i++) if (sameWord(got.get(i), w)) { top3++; break; }
        }
        double a1 = top1 / (double) words.size(), a3 = top3 / (double) words.size();
        if (print) {
            System.out.println(String.format(Locale.ROOT, "SWIPE %s sigma=%.2f top1=%.3f (exacto %.3f) top3=%.3f fallos:%s",
                    lang, sigma, a1, top1Exact / (double) words.size(), a3, misses));
        }
        return new double[]{a1, a3};
    }

    @Test
    public void accuracyEs() throws Exception {
        accuracy("es", 0f, true);
        accuracy("es", 0.25f, true);
        double[] a = accuracy("es", SIGMA, true);
        assertTrue("top-1 es " + a[0], a[0] >= MIN_TOP1);
        assertTrue("top-3 es " + a[1], a[1] >= MIN_TOP3);
    }

    @Test
    public void accuracyEn() throws Exception {
        accuracy("en", 0f, true);
        accuracy("en", 0.25f, true);
        double[] a = accuracy("en", SIGMA, true);
        assertTrue("top-1 en " + a[0], a[0] >= MIN_TOP1);
        assertTrue("top-3 en " + a[1], a[1] >= MIN_TOP3);
    }

    /** Casos explicitos: saludo, tildes (mañana con su tecla ñ), letra doble con bucle e ingles. */
    @Test
    public void explicitWords() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, "es");
        SwipeDecoder.Geometry g = kb.glideGeometry();
        Dictionary es = Dictionary.get(app, "es");
        SwipeDecoder dec = new SwipeDecoder(es, g);
        Random rnd = new Random(7);
        String[] words = {"hola", "fuerza", "gracias", "mañana", "llamar", "también", "qué"};
        for (String w : words) {
            Stroke s = path(g, dp, w, SIGMA, rnd, true);
            List<String> got = dec.decode(s.x, s.y, s.t, s.n);
            System.out.println("SWIPE es " + w + " -> " + got);
            assertTrue(w + ": " + got, !got.isEmpty() && sameWord(got.get(0), w));
        }
        // La hermana con/sin tilde de la mejor va siempre entre las alternativas
        Stroke s = path(g, dp, "que", 0f, rnd, false);
        List<String> got = dec.decode(s.x, s.y, s.t, s.n);
        assertTrue("que/qué: " + got, got.contains("que") && got.contains("qué"));
        // Sin la vocal doble: "llamar" trazado sin bucle sigue saliendo (la plantilla sin bucle coincide con l-a-m-a-r)
        s = path(g, dp, "llamar", 0f, rnd, false);
        got = dec.decode(s.x, s.y, s.t, s.n);
        assertTrue("llamar sin bucle: " + got, got.contains("llamar"));

        AurebeshKeyboardView kbEn = view(app, "en");
        SwipeDecoder.Geometry ge = kbEn.glideGeometry();
        assertEquals(-1, ge.indexOf('ñ'));
        Dictionary en = Dictionary.get(app, "en");
        SwipeDecoder decEn = new SwipeDecoder(en, ge);
        for (String w : new String[]{"the", "hello", "force", "thanks", "tomorrow"}) {
            s = path(ge, dp, w, SIGMA, rnd, true);
            got = decEn.decode(s.x, s.y, s.t, s.n);
            System.out.println("SWIPE en " + w + " -> " + got);
            assertTrue(w + ": " + got, !got.isEmpty() && sameWord(got.get(0), w));
        }
        s = path(ge, dp, "dont", 0f, rnd, false);
        got = decEn.decode(s.x, s.y, s.t, s.n);
        assertTrue("dont -> don't: " + got, got.contains("don't"));
        // Palabra aprendida
        es.setPersonal(Arrays.asList("aurebesh"));
        s = path(g, dp, "aurebesh", 0f, rnd, false);
        got = dec.decode(s.x, s.y, s.t, s.n);
        es.setPersonal(null);
        assertTrue("aprendida: " + got, got.contains("aurebesh"));
    }

    /** Tiempo por reconocimiento (palabras largas) y construccion del indice. */
    @Test
    public void performance() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, "es");
        SwipeDecoder.Geometry g = kb.glideGeometry();
        Dictionary d = Dictionary.get(app, "es");
        long t0 = System.nanoTime();
        SwipeDecoder dec = new SwipeDecoder(d, g);
        long build = (System.nanoTime() - t0) / 1000000;
        List<String> longWords = new ArrayList<>();
        for (int r = 0; r < d.size() && longWords.size() < 200; r++) {
            String w = d.word(r);
            if (w.length() >= 8 && !Dictionary.noSuggest(w)) longWords.add(w);
        }
        Random rnd = new Random(3);
        List<Stroke> strokes = new ArrayList<>();
        for (String w : longWords) strokes.add(path(g, dp, w, SIGMA, rnd, false));
        for (int i = 0; i < 30; i++) { Stroke s = strokes.get(i); dec.decode(s.x, s.y, s.t, s.n); }   // calentamiento
        long[] times = new long[strokes.size()];
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            long a = System.nanoTime();
            dec.decode(s.x, s.y, s.t, s.n);
            times[i] = (System.nanoTime() - a) / 1000;
        }
        Arrays.sort(times);
        long median = times[times.length / 2], p95 = times[(int) (times.length * 0.95)], max = times[times.length - 1];
        System.out.println(String.format(Locale.ROOT, "SWIPE perf indice=%d ms, decode mediana=%.1f ms p95=%.1f ms max=%.1f ms (JVM)",
                build, median / 1000.0, p95 / 1000.0, max / 1000.0));
        assertTrue("indice " + build + " ms", build < 2000);
        assertTrue("mediana " + median + " us", median < 15000);
    }

    // ------------------------------------------------------------------ gesto en la vista

    private static final class Recorder implements AurebeshKeyboardView.Listener {
        final StringBuilder typed = new StringBuilder();
        final List<String> glides = new ArrayList<>();
        final List<List<String>> alts = new ArrayList<>();
        public void onText(String s) { typed.append(s); }
        public void onDelete() { typed.append("<del>"); }
        public void onEnter() { typed.append("\n"); }
        public void onSwitchKeyboard() {}
        public void onSwitchLanguage() {}
        public void onSuggestion(String w) { typed.append("[").append(w).append("]"); }
        public void onToolbar(int t) {}
        public void onHandSide(int m) {}
        public void onPasteSuggestion() {}
        public void onGlide(String best, List<String> alternatives) { glides.add(best); alts.add(alternatives); }
    }

    private static void event(View v, int action, float x, float y, long down, long t) {
        MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    /** Envia el trazo a la vista como DOWN, MOVE por cada punto y UP; devuelve el bitmap de mitad del gesto. */
    private static Bitmap glide(AurebeshKeyboardView kb, Stroke s, boolean snapshot) {
        long base = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, s.x[0], s.y[0], base, base);
        Bitmap shot = null;
        for (int i = 1; i < s.n; i++) {
            event(kb, MotionEvent.ACTION_MOVE, s.x[i], s.y[i], base, base + s.t[i] - s.t[0]);
            if (snapshot && i == s.n / 2) {
                shot = Bitmap.createBitmap(W, kb.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                kb.draw(new Canvas(shot));
            }
        }
        event(kb, MotionEvent.ACTION_UP, s.x[s.n - 1], s.y[s.n - 1], base, base + s.t[s.n - 1] - s.t[0]);
        return shot;
    }

    private static void awaitGlide(AurebeshKeyboardView kb) throws Exception {
        Future<?> f = kb.lastGlideTask();
        if (f != null) f.get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void gesture() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, "es");
        Recorder rec = new Recorder();
        kb.setListener(rec);
        kb.setGlideEnabled(true);
        SwipeDecoder.Geometry g = kb.glideGeometry();
        Random rnd = new Random(1);

        // Un toque sigue siendo un toque
        AurebeshKeyboardView.Key h = kb.keyFor("h");
        long t = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, h.rect.centerX(), h.rect.centerY(), t, t);
        event(kb, MotionEvent.ACTION_UP, h.rect.centerX(), h.rect.centerY(), t, t + 60);
        assertEquals("h", rec.typed.toString());
        assertTrue(rec.glides.isEmpty());

        // Deslizar h-o-l-a escribe "hola" (y la estela se dibuja a mitad del gesto)
        rec.typed.setLength(0);
        Bitmap mid = glide(kb, path(g, dp, "hola", 0.1f, rnd, false), true);
        assertNotNull(mid);
        awaitGlide(kb);
        assertEquals("", rec.typed.toString());
        assertEquals(Arrays.asList("hola"), rec.glides);
        System.out.println("SWIPE gesto hola -> " + rec.glides + " alternativas " + rec.alts);

        // Con shift, la palabra sale capitalizada y shift se apaga
        AurebeshKeyboardView.Key shift = kb.keyFor(AurebeshKeyboardView.CODE_SHIFT);
        t = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, shift.rect.centerX(), shift.rect.centerY(), t, t);
        event(kb, MotionEvent.ACTION_UP, shift.rect.centerX(), shift.rect.centerY(), t, t + 40);
        glide(kb, path(g, dp, "gracias", 0.1f, rnd, false), false);
        awaitGlide(kb);
        assertEquals("Gracias", rec.glides.get(rec.glides.size() - 1));
        glide(kb, path(g, dp, "fuerza", 0.1f, rnd, false), false);
        awaitGlide(kb);
        assertEquals("fuerza", rec.glides.get(rec.glides.size() - 1));

        // Acabar sobre la tecla de borrar cancela el trazo
        int before = rec.glides.size();
        Stroke s = path(g, dp, "hola", 0f, rnd, false);
        AurebeshKeyboardView.Key del = kb.keyFor(AurebeshKeyboardView.CODE_DELETE);
        Stroke s2 = new Stroke();
        s2.n = s.n + 1;
        s2.x = Arrays.copyOf(s.x, s2.n); s2.y = Arrays.copyOf(s.y, s2.n); s2.t = Arrays.copyOf(s.t, s2.n);
        s2.x[s.n] = del.rect.centerX(); s2.y[s.n] = del.rect.centerY(); s2.t[s.n] = s.t[s.n - 1] + 8;
        glide(kb, s2, false);
        awaitGlide(kb);
        assertEquals(before, rec.glides.size());

        // Acabar en el espacio: los puntos finales se recortan y la palabra sale igual
        AurebeshKeyboardView.Key space = kb.keyFor(AurebeshKeyboardView.CODE_SPACE);
        s2.x[s.n] = space.rect.centerX(); s2.y[s.n] = space.rect.centerY();
        glide(kb, s2, false);
        awaitGlide(kb);
        assertEquals("hola", rec.glides.get(rec.glides.size() - 1));
        assertEquals("", rec.typed.toString());

        // Un tiron corto (menos de 1.2 teclas) es un toque en la tecla inicial
        rec.typed.setLength(0);
        before = rec.glides.size();
        t = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, h.rect.centerX(), h.rect.centerY(), t, t);
        event(kb, MotionEvent.ACTION_MOVE, h.rect.centerX() + h.rect.width() * 1.0f, h.rect.centerY(), t, t + 40);
        event(kb, MotionEvent.ACTION_UP, h.rect.centerX() + h.rect.width() * 1.0f, h.rect.centerY(), t, t + 60);
        awaitGlide(kb);
        assertEquals("h", rec.typed.toString());
        assertEquals(before, rec.glides.size());

        // Deslizar despacio a la tecla vecina sigue corrigiendo el toque (h -> j)
        rec.typed.setLength(0);
        AurebeshKeyboardView.Key j = kb.keyFor("j");
        t = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, h.rect.centerX(), h.rect.centerY(), t, t);
        event(kb, MotionEvent.ACTION_MOVE, h.rect.centerX() + h.rect.width() * 0.5f, h.rect.centerY(), t, t + 300);
        event(kb, MotionEvent.ACTION_MOVE, j.rect.centerX(), j.rect.centerY(), t, t + 700);
        event(kb, MotionEvent.ACTION_UP, j.rect.centerX(), j.rect.centerY(), t, t + 760);
        awaitGlide(kb);
        assertEquals("j", rec.typed.toString());
        assertEquals(before, rec.glides.size());

        // Con el deslizado apagado, el gesto h..a vuelve a ser deslizar a otra tecla: escribe "a"
        kb.setGlideEnabled(false);
        rec.typed.setLength(0);
        glide(kb, path(g, dp, "hola", 0f, rnd, false), false);
        awaitGlide(kb);
        assertEquals("a", rec.typed.toString());
        assertEquals(before, rec.glides.size());
        kb.setGlideEnabled(true);

        // La pagina ?123 nunca desliza: el mismo gesto escribe el signo donde se suelta
        rec.typed.setLength(0);
        AurebeshKeyboardView.Key mode = kb.keyFor(AurebeshKeyboardView.CODE_MODE);
        t = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, mode.rect.centerX(), mode.rect.centerY(), t, t);
        event(kb, MotionEvent.ACTION_UP, mode.rect.centerX(), mode.rect.centerY(), t, t + 40);
        layout(kb);
        glide(kb, s, false);
        awaitGlide(kb);
        assertEquals(before, rec.glides.size());
        assertTrue("?123: " + rec.typed, rec.typed.length() == 1);
    }
}
