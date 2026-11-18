package com.aurebesh.teclado;

import android.app.Application;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Arnes de medida del diccionario espanol (no es una prueba: su nombre no acaba en Test y la suite no lo ejecuta).
 * Uso, desde screenshot-test:  mvn -q -o test -Dtest=DiccBench -Ddicc.out=/ruta/salida.txt
 * <p>
 * Mide en la JVM, con la lista words_es.txt que haya en el APK de recursos (../build/base.apk, que genera build.sh):
 * tiempo de carga de {@link Dictionary} y memoria que ocupa; latencia de {@link Dictionary#suggest} (media y p95)
 * sobre 2000 entradas mezcladas y fijas (palabras correctas, erratas a una y dos ediciones y prefijos, sacadas de
 * las 40 000 primeras palabras para que sirvan igual con cualquier lista); el deslizado ({@link SwipeDecoder}):
 * palabras indexadas, tiempo de indice y de reconocimiento; y el corrector frente a las erratas reales de
 * erratas_es.tsv, con el detalle de cada una para comparar dos listas o dos versiones del codigo.
 * <p>
 * Con -Ddicc.formas=archivo (el que escribe tools/dict_cobertura.py --formas: verbos y sustantivos de texto real, bien
 * escritos) cuenta ademas cuantos conoce el diccionario, cuantos deja como estan sin conocerlos y cuantos cambiaria
 * por otra palabra, que es lo que mas molesta de un corrector.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DiccBench {

    private static final int BASE = 40000;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzñ";

    private static String edit(String w, Random rnd) {
        int n = w.length();
        switch (rnd.nextInt(4)) {
            case 0: { int i = rnd.nextInt(n); return w.substring(0, i) + w.substring(i + 1); }
            case 1: { int i = rnd.nextInt(n - 1); return w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2); }
            case 2: { int i = rnd.nextInt(n); return w.substring(0, i) + LETTERS.charAt(rnd.nextInt(LETTERS.length())) + w.substring(i + 1); }
            default: { int i = rnd.nextInt(n + 1); return w.substring(0, i) + LETTERS.charAt(rnd.nextInt(LETTERS.length())) + w.substring(i); }
        }
    }

    /** Las 2000 entradas de la medida de latencia: 500 de cada clase, siempre las mismas. */
    static Map<String, List<String>> inputs(Dictionary d) {
        Random rnd = new Random(12345);
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> ok = new ArrayList<>(), e1 = new ArrayList<>(), e2 = new ArrayList<>(), pre = new ArrayList<>();
        while (ok.size() < 500) {                         // rangos repartidos en escala logaritmica
            int r = (int) Math.exp(rnd.nextDouble() * Math.log(BASE));
            String w = d.word(Math.min(BASE - 1, r));
            if (w.length() >= 2) ok.add(w);
        }
        while (e1.size() < 500) {
            String w = d.word(rnd.nextInt(5000));
            if (w.length() >= 4) e1.add(edit(w, rnd));
        }
        while (e2.size() < 500) {
            String w = d.word(rnd.nextInt(5000));
            if (w.length() >= 6) e2.add(edit(edit(w, rnd), rnd));
        }
        while (pre.size() < 500) {
            String w = d.word(rnd.nextInt(20000));
            if (w.length() >= 4) pre.add(w.substring(0, 2 + rnd.nextInt(w.length() - 2)));
        }
        out.put("correctas", ok);
        out.put("una edicion", e1);
        out.put("dos ediciones", e2);
        out.put("prefijos", pre);
        return out;
    }

    private static long usedHeap() throws InterruptedException {
        for (int i = 0; i < 4; i++) { System.gc(); Thread.sleep(60); }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Dictionary> cache() throws Exception {
        Field f = Dictionary.class.getDeclaredField("CACHE");
        f.setAccessible(true);
        return (Map<String, Dictionary>) f.get(null);
    }

    private static double mean(long[] v) {
        double s = 0;
        for (long x : v) s += x;
        return s / v.length;
    }

    @Test
    public void medir() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        File outFile = new File(System.getProperty("dicc.out", "target/dicc-bench.txt"));
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        PrintWriter out = new PrintWriter(outFile, "UTF-8");
        try {
            // ---- carga y memoria
            cache().clear();
            long heap0 = usedHeap();
            long[] load = new long[21];
            Dictionary d = null;
            for (int i = 0; i < load.length; i++) {
                cache().remove("es");
                d = null;
                long t = System.nanoTime();
                d = Dictionary.get(app, "es");
                load[i] = (System.nanoTime() - t) / 1000;
            }
            long heap1 = usedHeap();
            long first = load[0];
            long[] rest = Arrays.copyOfRange(load, 1, load.length);
            Arrays.sort(rest);
            out.println(String.format(Locale.ROOT, "PALABRAS %d", d.size()));
            out.println(String.format(Locale.ROOT, "CARGA primera=%.1f ms mediana=%.1f ms minima=%.1f ms de %d (JVM)",
                    first / 1000.0, rest[rest.length / 2] / 1000.0, rest[0] / 1000.0, rest.length));
            out.println(String.format(Locale.ROOT, "MEMORIA diccionario=%.1f MB", (heap1 - heap0) / 1048576.0));

            // ---- latencia de suggest()
            d.setPersonal(null);
            d.setStarWars(false);
            Map<String, List<String>> in = inputs(d);
            List<String> all = new ArrayList<>();
            for (List<String> l : in.values()) all.addAll(l);
            for (int pass = 0; pass < 4; pass++) for (String w : all) d.suggest(w);          // calentamiento
            int passes = 7;
            long[][] t = new long[all.size()][passes];
            for (int pass = 0; pass < passes; pass++) {
                for (int i = 0; i < all.size(); i++) {
                    long a = System.nanoTime();
                    d.suggest(all.get(i));
                    t[i][pass] = System.nanoTime() - a;
                }
            }
            long[] med = new long[all.size()];
            for (int i = 0; i < med.length; i++) { Arrays.sort(t[i]); med[i] = t[i][passes / 2] / 1000; }
            long[] sorted = med.clone();
            Arrays.sort(sorted);
            out.println(String.format(Locale.ROOT, "SUGGEST %d entradas: media=%.1f us p95=%d us p99=%d us max=%d us (JVM)",
                    med.length, mean(med), sorted[(int) (sorted.length * 0.95)], sorted[(int) (sorted.length * 0.99)], sorted[sorted.length - 1]));
            int at = 0;
            for (Map.Entry<String, List<String>> e : in.entrySet()) {
                long[] part = Arrays.copyOfRange(med, at, at + e.getValue().size());
                at += e.getValue().size();
                long[] ps = part.clone();
                Arrays.sort(ps);
                out.println(String.format(Locale.ROOT, "SUGGEST   %-14s media=%.1f us p95=%d us", e.getKey(), mean(part), ps[(int) (ps.length * 0.95)]));
            }

            // ---- deslizado
            AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
            kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id);
            kb.setLanguage("es");
            kb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            kb.layout(0, 0, 1080, kb.getMeasuredHeight());
            SwipeDecoder.Geometry g = kb.glideGeometry();
            long[] build = new long[5];
            SwipeDecoder dec = null;
            for (int i = 0; i < build.length; i++) {
                long a = System.nanoTime();
                dec = new SwipeDecoder(d, g);
                build[i] = (System.nanoTime() - a) / 1000;
            }
            Arrays.sort(build);
            Field bf = SwipeDecoder.class.getDeclaredField("bucket");
            bf.setAccessible(true);
            int indexed = 0;
            for (int[] b : (int[][]) bf.get(dec)) indexed += b.length;
            float dp = app.getResources().getDisplayMetrics().density;
            Random rnd = new Random(3);
            List<float[][]> strokes = new ArrayList<>();
            List<String> targets = new ArrayList<>();
            for (int r = 0; r < BASE && strokes.size() < 300; r++) {
                String w = d.word(r);
                if (w.length() < 6 || Dictionary.noSuggest(w)) continue;
                float[][] s = stroke(g, dp, w, 0.15f, rnd);
                if (s != null) { strokes.add(s); targets.add(w); }
            }
            long[] ts = new long[strokes.size()];
            int top1 = 0;
            for (int pass = 0; pass < 3; pass++) {
                top1 = 0;
                for (int i = 0; i < strokes.size(); i++) {
                    float[][] s = strokes.get(i);
                    long[] tt = new long[s[0].length];
                    for (int k = 0; k < tt.length; k++) tt[k] = 1000 + 8L * k;
                    long a = System.nanoTime();
                    List<String> got = dec.decode(s[0], s[1], tt, tt.length);
                    ts[i] = (System.nanoTime() - a) / 1000;
                    if (!got.isEmpty() && Dictionary.deaccent(got.get(0)).equals(Dictionary.deaccent(targets.get(i)))) top1++;
                }
            }
            long[] tss = ts.clone();
            Arrays.sort(tss);
            out.println(String.format(Locale.ROOT, "DESLIZADO palabras indexadas=%d indice=%.1f ms decode media=%.0f us p95=%d us acierto=%d/%d (JVM)",
                    indexed, build[build.length / 2] / 1000.0, mean(ts), tss[(int) (tss.length * 0.95)], top1, strokes.size()));

            // ---- erratas reales
            Map<String, int[]> byClass = new LinkedHashMap<>();
            List<String> detail = new ArrayList<>();
            InputStream is = DiccBench.class.getResourceAsStream("/erratas_es.tsv");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                Dictionary.Result res = d.suggest(p[0]);
                String got = res.correction != null ? res.correction : res.known ? p[0] : "(desconocida, sin corregir)";
                boolean hit = got.equals(p[1]);
                int[] c = byClass.get(p[2]);
                if (c == null) byClass.put(p[2], c = new int[2]);
                c[0]++;
                if (hit) c[1]++;
                detail.add(p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + got + "\t" + (hit ? "OK" : "FALLO") + "\t" + res.suggestions);
            }
            r.close();
            int total = 0, hits = 0;
            for (Map.Entry<String, int[]> e : byClass.entrySet()) {
                out.println(String.format(Locale.ROOT, "ERRATAS %-12s %d/%d", e.getKey(), e.getValue()[1], e.getValue()[0]));
                total += e.getValue()[0];
                hits += e.getValue()[1];
            }
            out.println(String.format(Locale.ROOT, "ERRATAS total %d/%d", hits, total));
            for (String s : detail) out.println("E\t" + s);

            // ---- palabras bien escritas de texto real: cuantas cambiaria el corrector
            String formas = System.getProperty("dicc.formas");
            if (formas != null && !formas.isEmpty()) {
                Map<String, long[]> acc = new LinkedHashMap<>();           // clase -> {tipos, conocidos, cambiados, apariciones, conocidas, cambiadas}
                List<String> changed = new ArrayList<>();
                BufferedReader fr = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(formas), "UTF-8"));
                while ((line = fr.readLine()) != null) {
                    String[] p = line.split("\t");
                    if (p.length != 3) continue;
                    long n = Long.parseLong(p[2]);
                    Dictionary.Result res = d.suggest(p[1]);
                    long[] c = acc.get(p[0]);
                    if (c == null) acc.put(p[0], c = new long[6]);
                    c[0]++;
                    c[3] += n;
                    if (res.correction != null && !res.correction.equals(p[1])) {
                        c[2]++;
                        c[5] += n;
                        if (changed.size() < 400) changed.add(p[1] + ">" + res.correction);
                    } else if (res.known) {
                        c[1]++;
                        c[4] += n;
                    }
                }
                fr.close();
                for (Map.Entry<String, long[]> e : acc.entrySet()) {
                    long[] c = e.getValue();
                    out.println(String.format(Locale.ROOT, "TEXTO %s tipos=%d conocidos=%.2f%% cambiados=%.2f%% | apariciones=%d conocidas=%.2f%% cambiadas=%.2f%%",
                            "V".equals(e.getKey()) ? "verbos" : "sustantivos", c[0], 100.0 * c[1] / c[0], 100.0 * c[2] / c[0],
                            c[3], 100.0 * c[4] / c[3], 100.0 * c[5] / c[3]));
                }
                out.println("C\t" + changed);
            }
        } finally {
            out.close();
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(outFile), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.startsWith("E\t") && !line.startsWith("C\t")) System.out.println("DICC " + line);
        }
        r.close();
    }

    /** Trazo por los centros de las teclas con desvio gaussiano por letra, interpolado cada 4 dp: {x[], y[]}. */
    private static float[][] stroke(SwipeDecoder.Geometry g, float dp, String word, float sigma, Random rnd) {
        String plain = Dictionary.deaccent(word.toLowerCase(Locale.ROOT));
        List<float[]> v = new ArrayList<>();
        for (int i = 0; i < plain.length(); i++) {
            int k = g.indexOf(plain.charAt(i));
            if (k < 0) return null;
            if (i > 0 && plain.charAt(i) == plain.charAt(i - 1)) continue;
            v.add(new float[]{g.cx[k] + (float) rnd.nextGaussian() * sigma * g.keyW, g.cy[k] + (float) rnd.nextGaussian() * sigma * g.keyH});
        }
        if (v.size() < 2) return null;
        List<float[]> pts = new ArrayList<>();
        float step = 4 * dp;
        pts.add(v.get(0));
        for (int i = 1; i < v.size(); i++) {
            float[] a = v.get(i - 1), b = v.get(i);
            int steps = Math.max(1, Math.round((float) Math.hypot(b[0] - a[0], b[1] - a[1]) / step));
            for (int s = 1; s <= steps; s++) {
                float f = s / (float) steps;
                pts.add(new float[]{a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])});
            }
        }
        float[][] out = new float[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) { out[0][i] = pts.get(i)[0]; out[1][i] = pts.get(i)[1]; }
        return out;
    }
}
