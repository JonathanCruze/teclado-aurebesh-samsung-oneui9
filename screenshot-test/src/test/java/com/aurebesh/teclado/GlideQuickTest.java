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
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pruebas rapidas de deslizar para escribir. Funcionamiento: palabras corrientes (y una del lexico Star Wars)
 * trazadas con desvio por letra y ruido de sensor realista, con el reloj avanzando a 8 ms por muestra como en
 * el telefono. Estetica: estela de sable en los seis colores a mitad del gesto en tema claro y oscuro, su
 * desvanecimiento tras soltar y una variante con dedo tembloroso. Deja capturas glide_*.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GlideQuickTest {
    private static final int W = 1080;
    private static final long DT = 8;                       // ms entre muestras (120 Hz)

    private static final class Recorder implements AurebeshKeyboardView.Listener {
        final StringBuilder typed = new StringBuilder();
        final List<String> glides = new ArrayList<>();
        final List<List<String>> alts = new ArrayList<>();
        public void onText(String s) { typed.append(s); }
        public void onDelete() { typed.append("<del>"); }
        public void onEnter() {}
        public void onSwitchKeyboard() {}
        public void onSwitchLanguage() {}
        public void onSuggestion(String w) {}
        public void onToolbar(int t) {}
        public void onHandSide(int m) {}
        public void onPasteSuggestion() {}
        public void onGlide(String best, List<String> alternatives) { glides.add(best); alts.add(alternatives); }
        public void onCursorMove(int steps, boolean select) {}
        public void onDeleteWord() {}
        public void onHide() {}
        public void onResize(int heightPct, int bottomDp) {}
    }

    private static final class Stroke { float[] x, y; int n; }

    private static AurebeshKeyboardView view(Application app, String themeId) {
        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        kb.applyPrefs(false, false, false, true, themeId);
        kb.setLanguage("es");
        layout(kb);
        kb.setGlideEnabled(true);
        return kb;
    }

    private static void layout(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, W, v.getMeasuredHeight());
    }

    /**
     * Trazo por los centros de las letras con desvio gaussiano por letra (sigma en fraccion de tecla), muestras
     * cada {@code stepDp} y ruido de sensor {@code jitterDp} por muestra (un dedo real ronda 0.3-0.5 dp a 120 Hz).
     */
    private static Stroke path(SwipeDecoder.Geometry g, float dp, String word, float sigma, float jitterDp, Random rnd) {
        String plain = Dictionary.deaccent(word.toLowerCase(Locale.ROOT));
        List<float[]> v = new ArrayList<>();
        for (int i = 0; i < plain.length(); i++) {
            int k = g.indexOf(plain.charAt(i));
            if (k < 0) throw new IllegalArgumentException("sin tecla: " + plain.charAt(i));
            if (i > 0 && plain.charAt(i) == plain.charAt(i - 1)) continue;
            v.add(new float[]{g.cx[k] + (float) rnd.nextGaussian() * sigma * g.keyW, g.cy[k] + (float) rnd.nextGaussian() * sigma * g.keyH});
        }
        List<float[]> pts = new ArrayList<>();
        float step = 4 * dp, jitter = jitterDp * dp;
        pts.add(v.get(0));
        for (int i = 1; i < v.size(); i++) {
            float[] a = v.get(i - 1), b = v.get(i);
            float d = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, Math.round(d / step));
            for (int s = 1; s <= steps; s++) {
                float f = s / (float) steps;
                float jx = s == steps ? 0 : (float) rnd.nextGaussian() * jitter, jy = s == steps ? 0 : (float) rnd.nextGaussian() * jitter;
                pts.add(new float[]{a[0] + f * (b[0] - a[0]) + jx, a[1] + f * (b[1] - a[1]) + jy});
            }
        }
        Stroke st = new Stroke();
        st.n = pts.size(); st.x = new float[st.n]; st.y = new float[st.n];
        for (int i = 0; i < st.n; i++) { st.x[i] = pts.get(i)[0]; st.y[i] = pts.get(i)[1]; }
        return st;
    }

    private static void event(View v, int action, float x, float y, long down, long t) {
        MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void idle(long ms) { ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(ms)); }

    private static Bitmap snapshot(View v) {
        Bitmap b = Bitmap.createBitmap(W, v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    /** DOWN, MOVE por muestra (el reloj avanza DT entre muestras) y UP; devuelve la captura al 85 % del gesto si se pide. */
    private static Bitmap glide(AurebeshKeyboardView kb, Stroke s, boolean snapshot) {
        long down = SystemClock.uptimeMillis();
        event(kb, MotionEvent.ACTION_DOWN, s.x[0], s.y[0], down, down);
        Bitmap shot = null;
        for (int i = 1; i < s.n; i++) {
            idle(DT);
            event(kb, MotionEvent.ACTION_MOVE, s.x[i], s.y[i], down, SystemClock.uptimeMillis());
            if (snapshot && i == (int) (s.n * 0.85f)) shot = snapshot(kb);
        }
        idle(DT);
        event(kb, MotionEvent.ACTION_UP, s.x[s.n - 1], s.y[s.n - 1], down, SystemClock.uptimeMillis());
        return shot;
    }

    private static void awaitGlide(AurebeshKeyboardView kb) throws Exception {
        Future<?> f = kb.lastGlideTask();
        if (f != null) f.get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void save(Bitmap b, String name) throws Exception {
        File out = new File(System.getProperty("shots.dir", "shots"), name);
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.PNG, 100, fos); }
    }

    private static int differentPixels(Bitmap a, Bitmap b) {
        int n = 0;
        int[] pa = new int[a.getWidth()], pb = new int[b.getWidth()];
        for (int y = 0; y < a.getHeight(); y++) {
            a.getPixels(pa, 0, a.getWidth(), 0, y, a.getWidth(), 1);
            b.getPixels(pb, 0, b.getWidth(), 0, y, b.getWidth(), 1);
            for (int x = 0; x < pa.length; x++) if (pa[x] != pb[x]) n++;
        }
        return n;
    }

    @Test
    public void palabrasCorrientes() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, Themes.ESTELAR.id);
        Recorder rec = new Recorder();
        kb.setListener(rec);
        SwipeDecoder.Geometry g = kb.glideGeometry();
        Dictionary dict = Dictionary.get(app, "es");
        dict.setStarWars(true);
        try {
            Random rnd = new Random(7);
            String[] words = {"hola", "gracias", "fuerza", "teclado", "mañana", "siempre", "jedi", "coruscant", "palpatine", "kyber"};
            int ok = 0;
            for (String w : words) {
                rec.glides.clear(); rec.alts.clear(); rec.typed.setLength(0);
                glide(kb, path(g, dp, w, 0.12f, 0.4f, rnd), false);
                awaitGlide(kb);
                idle(AurebeshKeyboardView.TRAIL_MS + 50);
                String got = rec.glides.isEmpty() ? "(nada)" : rec.glides.get(0);
                boolean hit = rec.glides.size() == 1 && Dictionary.deaccent(got).equals(Dictionary.deaccent(w));
                if (hit) ok++;
                System.out.println("GLIDE " + w + " -> " + got + (hit ? " OK" : " FALLO") + " alternativas " + (rec.alts.isEmpty() ? "[]" : rec.alts.get(0)));
                assertEquals("un deslizado no escribe letras sueltas", "", rec.typed.toString());
            }
            System.out.println("GLIDE aciertos " + ok + "/" + words.length);
            assertTrue("al menos ocho de diez palabras", ok >= 8);
        } finally {
            dict.setStarWars(false);
        }
    }

    @Test
    public void esteticaDelSable() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        Random rnd = new Random(3);
        for (int pass = 0; pass < 2; pass++) {
            String theme = pass == 0 ? Themes.ESTELAR.id : Themes.NOCHE.id;
            AurebeshKeyboardView kb = view(app, theme);
            kb.setListener(new Recorder());
            SwipeDecoder.Geometry g = kb.glideGeometry();
            Bitmap clean = snapshot(kb);
            for (int i = 0; i < Prefs.SABERS.length; i++) {
                kb.setSaber(Prefs.SABER_ARGB[i]);
                Bitmap shot = glide(kb, path(g, dp, "palabra", 0.08f, 0.4f, rnd), true);
                save(shot, "glide_" + theme + "_" + Prefs.SABERS[i] + ".png");
                awaitGlide(kb);
                if (i == 0) {
                    // Desvanecimiento tras soltar: fotogramas cada 150 ms hasta pasado TRAIL_MS
                    long[] at = {0, 150, 300, 450, 600, 750};
                    long t = 0;
                    for (int f = 0; f < at.length; f++) {
                        idle(at[f] - t); t = at[f];
                        Bitmap b = snapshot(kb);
                        save(b, "glide_" + theme + "_fade" + f + ".png");
                        System.out.println("GLIDE fade " + theme + " +" + at[f] + "ms pixeles distintos del teclado limpio: " + differentPixels(clean, b));
                        if (at[f] > AurebeshKeyboardView.TRAIL_MS) assertEquals("la estela desaparece del todo pasado TRAIL_MS", 0, differentPixels(clean, b));
                    }
                } else {
                    idle(AurebeshKeyboardView.TRAIL_MS + 50);
                }
            }
            // Dedo tembloroso (ruido de sensor 1.5 dp): la hoja debe seguir leyendose como un trazo continuo
            kb.setSaber(Prefs.SABER_ARGB[0]);
            Bitmap shaky = glide(kb, path(g, dp, "palabra", 0.08f, 1.5f, rnd), true);
            save(shaky, "glide_" + theme + "_tembloroso.png");
            awaitGlide(kb);
            idle(AurebeshKeyboardView.TRAIL_MS + 50);
        }
    }

    /** Medida rapida del decodificador sin vista: palabras corrientes con varios desvios y semillas, y el caso "teclado". */
    @Test
    public void precisionDelDecodificador() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;
        AurebeshKeyboardView kb = view(app, Themes.ESTELAR.id);
        SwipeDecoder.Geometry g = kb.glideGeometry();
        Dictionary dict = Dictionary.get(app, "es");
        SwipeDecoder dec = new SwipeDecoder(dict, g);
        String[] words = {"hola", "casa", "tiempo", "gracias", "fuerza", "teclado", "siempre", "porque", "mensaje", "trabajo",
                "familia", "noche", "fiesta", "bueno", "mucho", "gente", "nuevo", "hasta", "donde", "tarde",
                "mañana", "llamar", "comida", "perro", "camino", "ventana", "jugar", "libro", "problema", "ciudad",
                "dinero", "amigos", "escuela", "persona", "momento", "verdad", "cuenta", "quiero", "puedo", "tengo",
                "mundo", "manera", "parte", "forma", "pregunta", "respuesta", "historia", "palabra", "ejemplo", "numero",
                "ultimo", "primero", "segundo", "grande", "pequeño", "cuando", "entonces", "ahora", "despues", "antes"};
        float[] sigmas = {0f, 0.08f, 0.15f};
        for (float sigma : sigmas) {
            int ok = 0, top3 = 0, total = 0;
            StringBuilder miss = new StringBuilder();
            for (int seed = 1; seed <= 3; seed++) {
                Random rnd = new Random(seed);
                for (String w : words) {
                    Stroke st = path(g, dp, w, sigma, 0.4f, rnd);
                    long[] t = new long[st.n];
                    for (int i = 0; i < st.n; i++) t[i] = DT * i;
                    List<String> res = dec.decode(st.x, st.y, t, st.n);
                    total++;
                    if (!res.isEmpty() && Dictionary.deaccent(res.get(0)).equals(Dictionary.deaccent(w))) ok++;
                    else {
                        if (res.contains(w)) top3++;
                        miss.append(' ').append(w).append("->").append(res.isEmpty() ? "(nada)" : res.get(0));
                    }
                }
            }
            System.out.println("GLIDE precision sigma " + sigma + ": primera " + ok + "/" + total + ", entre las alternativas " + top3 + ", fallos:" + miss);
        }
    }
}
