package com.aurebesh.teclado;

import android.app.Application;
import android.view.View;

import com.aurebesh.teclado.llm.ChatMessage;
import com.aurebesh.teclado.llm.LlmEngine;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * IA mas rapida y util en el telefono (2.0): cache de prefijo KV reutilizada entre dos generaciones con el mismo
 * system prompt, progreso real de la lectura del prompt ("Leyendo el texto… 45 %"), errores con su tipo y la
 * salida que toca en el panel, historial de resultados con ‹ 2/3 › que Reintentar no borra, y tonos personalizados
 * guardados como chips (pulsacion larga para olvidar).
 *
 * <p>Las pruebas del motor usan un GGUF aleatorio: el que deja tools/llm/run_tests.sh en LLM_SCRATCH (o
 * /tmp/aurebesh-llm) o uno generado aqui con tools/llm/make_random_gguf.py; sin python3 con gguf y numpy se saltan.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AiSpeedTest {

    private Application app;
    private File installed;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
    }

    @After
    public void tearDown() {
        if (installed != null) installed.delete();
        ShadowLooper.shadowMainLooper().idle();
    }

    // ------------------------------------------------------------------ ayudas

    private static AiTray.Host host(final String text, final String selection) {
        return new AiTray.Host() {
            public CharSequence currentText() { return text; }
            public CharSequence selectedText() { return selection; }
            public void replaceText(CharSequence t) {}
            public void insertText(CharSequence t) {}
            public void copyText(CharSequence t) {}
            public void closeTray() {}
            public void openSettings() {}
        };
    }

    /** Oyente que apunta lo que recibe, con el tipo de cada error. */
    private static final class Recorder implements AiRunner.Callback {
        final List<String> phases = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        AiRunner.ErrorKind kind;
        public void onPhase(CharSequence label) { phases.add(label.toString()); }
        public void onPiece(String soFar, int tokens, long elapsedMs) { events.add("piece"); }
        public void onDone(String text, boolean weak) { events.add("done"); }
        public void onCancelled(String partialText) { events.add("cancelled"); }
        public void onError(CharSequence message) { events.add("error-sin-tipo"); }
        @Override public void onError(AiRunner.ErrorKind k, CharSequence message) { kind = k; events.add("error:" + k); }
    }

    /** Deja correr el hilo de trabajo y reparte en el principal hasta que la peticion termina. */
    private static void settle(AiRunner runner) throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (runner.isBusy() && System.currentTimeMillis() < end) {
            ShadowLooper.shadowMainLooper().idle();
            Thread.sleep(10);
        }
        ShadowLooper.shadowMainLooper().idle();
    }

    /** Un archivo que empieza por "GGUF" pero no es un modelo: el panel lo ve disponible y el motor lo rechaza. */
    private void installFakeModel() throws Exception {
        installed = new File(AiModels.dir(app), AiModels.DEFAULT.file);
        try (FileOutputStream f = new FileOutputStream(installed)) { f.write("GGUF".getBytes("UTF-8")); }
        AiModels.select(app, installed.getName());
    }

    /** Copia el GGUF aleatorio a la carpeta de modelos del telefono simulado y lo selecciona. */
    private void installRandomModel(File gguf) throws Exception {
        installed = new File(AiModels.dir(app), AiModels.DEFAULT.file);
        Files.copy(gguf.toPath(), installed.toPath(), StandardCopyOption.REPLACE_EXISTING);
        AiModels.select(app, installed.getName());
    }

    private static File findUp(String relative) {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 5 && dir != null; i++) {
            File f = new File(dir, relative);
            if (f.isFile()) return f;
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Modelo GGUF aleatorio con la plantilla ChatML: el que deja run_tests.sh en LLM_SCRATCH (o /tmp/aurebesh-llm),
     * o uno generado ahora con make_random_gguf.py; null si no hay python3 con gguf y numpy.
     */
    private static File randomModel() {
        String scratch = System.getenv("LLM_SCRATCH");
        if (scratch == null || scratch.isEmpty()) scratch = new File(System.getProperty("java.io.tmpdir"), "aurebesh-llm").getPath();
        File ready = new File(scratch, "models/tiny-qwen2.gguf");
        if (ready.isFile()) return ready;
        File script = findUp("tools/llm/make_random_gguf.py");
        if (script == null) return null;
        try {
            File out = Files.createTempDirectory("aurebesh-aispeed").toFile();
            Process p = new ProcessBuilder("python3", "-I", script.getAbsolutePath(), "tiny", out.getAbsolutePath())
                    .redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) { /* vaciar la salida */ }
            if (p.waitFor() != 0) return null;
            File f = new File(out, "tiny-qwen2.gguf");
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ChatMessage> chat(String system, String user) {
        List<ChatMessage> m = new ArrayList<>(2);
        m.add(new ChatMessage("system", system));
        m.add(new ChatMessage("user", user));
        return m;
    }

    private static String longSpanish(int repeats) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < repeats; i++) {
            if (b.length() > 0) b.append(' ');
            b.append("Hola, mañana vamos al cine con los amigos de la escuela y después cenamos en casa de mi hermana.");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ cache de prefijo

    @Test
    public void commonPrefixLogic() {
        assertEquals(2, LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2, 4}, 10));
        assertEquals(3, LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2, 3}, 10));
        assertEquals(2, LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2, 3}, 2));      // tope: el ultimo se reevalua
        assertEquals(2, LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2}, 10));        // longitudes distintas
        assertEquals(0, LlmEngine.commonPrefix(new int[]{9, 2, 3}, new int[]{1, 2, 3}, 10));     // nada en comun
        assertEquals(0, LlmEngine.commonPrefix(new int[]{1}, null, 10));
        assertEquals(0, LlmEngine.commonPrefix(new int[]{1, 2}, new int[]{1, 2}, -1));           // tope negativo: nunca < 0
        assertEquals(0, LlmEngine.commonPrefix(new int[0], new int[]{1}, 10));
    }

    @Test
    public void prefixCacheIsReusedBetweenTwoGenerationsWithTheSameSystemPrompt() throws Exception {
        File gguf = randomModel();
        Assume.assumeTrue("sin GGUF aleatorio (hace falta python3 con gguf y numpy)", gguf != null);
        LlmEngine engine = LlmEngine.load(gguf, 2);
        try {
            String system = AiRunner.systemPrompt("es");
            LlmEngine.Options o = new LlmEngine.Options();
            o.maxTokens = 4;
            o.seed = 5;
            final List<int[]> progress = new ArrayList<>();
            LlmEngine.PromptListener pl = new LlmEngine.PromptListener() {
                @Override public boolean onPrompt(int done, int total) { progress.add(new int[]{done, total}); return true; }
            };
            // Primera generacion: se evalua todo; al terminar la cache sigue viva
            engine.generate(chat(system, "Corrige: ola ke ase"), o, null, pl);
            assertEquals(0, engine.lastReused());
            assertTrue(engine.cachedTokens() > 0);
            assertEquals(0, progress.get(0)[0]);
            assertEquals(progress.get(0)[1], progress.get(progress.size() - 1)[0]);     // termina en el total
            progress.clear();
            // Segunda con el mismo system prompt: solo se lee lo que cambia (al menos todo el system prompt)
            engine.generate(chat(system, "Corrige: mañana bamos al sine"), o, null, pl);
            int reused = engine.lastReused();
            assertTrue("reutilizadas " + reused, reused >= engine.countTokens(system));
            assertEquals(reused, progress.get(0)[0]);                                   // el progreso arranca en el prefijo
            assertTrue(progress.get(0)[1] > reused);
            // Un system prompt distinto desde el primer token: la plantilla sigue siendo comun, pero poco mas
            engine.generate(chat("You are a translator.", "Translate: hola"), o, null, pl);
            assertTrue(engine.lastReused() < engine.countTokens(system));
            // Cancelar durante la lectura: texto vacio, sin excepcion, y la cache guarda lo leido
            String none = engine.generate(chat(system, "Corrige: ola ke ase"), o, null, new LlmEngine.PromptListener() {
                @Override public boolean onPrompt(int done, int total) { return false; }
            });
            assertEquals("", none);
            // Presion de memoria: la cache se suelta y la siguiente generacion empieza de cero
            engine.trimMemory();
            assertEquals(0, engine.cachedTokens());
            engine.generate(chat(system, "Corrige: ola ke ase"), o, null, null);
            assertEquals(0, engine.lastReused());
        } finally {
            engine.close();
        }
    }

    // ------------------------------------------------------------------ progreso real y cache en el runner

    @Test
    public void runnerReportsReadingProgressAndReusesThePrefix() throws Exception {
        File gguf = randomModel();
        Assume.assumeTrue("sin GGUF aleatorio (hace falta python3 con gguf y numpy)", gguf != null);
        installRandomModel(gguf);
        AiRunner runner = new AiRunner(app);
        try {
            Recorder rec = new Recorder();
            AiRunner.Request r = new AiRunner.Request(AiRunner.Action.FIX, longSpanish(3));   // unos 300 tokens: varios trozos
            r.lang = "es";
            runner.run(r, rec);
            settle(runner);
            assertFalse(runner.isBusy());
            assertEquals(1, rec.events.size() - count(rec.events, "piece"));          // un unico final (done o error flojo)
            String last = rec.events.get(rec.events.size() - 1);
            assertTrue(last, last.equals("done") || last.equals("error:WEAK"));
            // Fases: "Leyendo el texto… NN %" con porcentajes crecientes antes de escribir
            List<Integer> pcts = new ArrayList<>();
            for (String ph : rec.phases) {
                if (ph.endsWith("%") && ph.startsWith(app.getString(R.string.ai_reading).replace("…", ""))) {
                    String digits = ph.replaceAll("[^0-9]", "");
                    pcts.add(Integer.parseInt(digits));
                }
            }
            assertTrue("fases: " + rec.phases, pcts.size() >= 2);
            for (int i = 1; i < pcts.size(); i++) assertTrue(pcts.get(i) > pcts.get(i - 1));
            assertEquals(100, (int) pcts.get(pcts.size() - 1));
            assertEquals(0, runner.lastReused());
            // Segunda peticion con otro texto: mismo system prompt y misma instruccion, se reutiliza el prefijo
            Recorder rec2 = new Recorder();
            AiRunner.Request r2 = new AiRunner.Request(AiRunner.Action.FIX, longSpanish(2) + " Y luego dormimos.");
            r2.lang = "es";
            runner.run(r2, rec2);
            settle(runner);
            assertTrue("reutilizadas " + runner.lastReused(), runner.lastReused() > 0);
            // Cancelar antes de que escriba (mientras carga o lee): el final es onCancelled, nunca onDone. El oyente
            // antiguo sin onError con tipo sigue valiendo.
            final Recorder rec3 = new Recorder();
            AiRunner.Request r3 = new AiRunner.Request(AiRunner.Action.FIX, longSpanish(6));
            r3.lang = "es";
            runner.run(r3, new AiRunner.Callback() {
                public void onPhase(CharSequence label) { rec3.onPhase(label); }
                public void onPiece(String soFar, int tokens, long elapsedMs) { rec3.onPiece(soFar, tokens, elapsedMs); }
                public void onDone(String text, boolean weak) { rec3.onDone(text, weak); }
                public void onCancelled(String partialText) { rec3.onCancelled(partialText); }
                public void onError(CharSequence message) { rec3.onError(message); }
            });
            runner.cancel();
            settle(runner);
            assertTrue(rec3.events.toString(), rec3.events.contains("cancelled"));
            assertFalse(rec3.events.toString(), rec3.events.contains("done"));
        } finally {
            runner.release();
        }
    }

    private static int count(List<String> l, String s) {
        int n = 0;
        for (String x : l) if (x.equals(s)) n++;
        return n;
    }

    // ------------------------------------------------------------------ errores accionables

    @Test
    public void badModelFileIsAModelErrorWithChooseModelAsTheOnlyWayOut() throws Exception {
        installFakeModel();
        AiRunner runner = new AiRunner(app);
        AiTray tray = new AiTray(app, runner);
        tray.setHost(host("hola mundo", null));
        tray.setLanguage("es");
        tray.show();
        assertEquals(AiTray.State.IDLE, tray.state());
        tray.run(AiRunner.Action.FIX, null, null);
        settle(runner);
        assertEquals(AiTray.State.ERROR, tray.state());
        assertEquals(AiRunner.ErrorKind.MODEL, tray.errorKind());
        assertEquals(app.getString(R.string.ai_open_settings), tray.primaryErrorAction().toString());
        assertFalse(tray.offersRetry());
        assertFalse(tray.offersSelectionOnly());
        runner.release();
    }

    @Test
    public void noModelIsItsOwnErrorKind() throws Exception {
        AiRunner runner = new AiRunner(app);
        Recorder rec = new Recorder();
        runner.run(new AiRunner.Request(AiRunner.Action.FIX, "hola"), rec);
        settle(runner);
        assertEquals(AiRunner.ErrorKind.NO_MODEL, rec.kind);
        assertTrue(rec.events.toString(), rec.events.contains("error:NO_MODEL"));
        assertFalse(rec.events.contains("error-sin-tipo"));
        runner.release();
    }

    @Test
    public void eachErrorKindOffersItsWayOut() throws Exception {
        installFakeModel();
        AiTray tray = new AiTray(app);
        tray.setHost(host("hola mundo, esto es una prueba", null));
        tray.setLanguage("es");
        tray.show();
        AiRunner.Callback cb = tray.callback();

        cb.onError(AiRunner.ErrorKind.TIMEOUT, "tardo");
        assertEquals(AiTray.State.ERROR, tray.state());
        assertNull(tray.primaryErrorAction());
        assertTrue(tray.offersRetry());
        assertTrue(tray.offersSelectionOnly());            // se uso todo el campo: se puede acotar a una seleccion

        cb.onError(AiRunner.ErrorKind.MEMORY, "memoria");
        assertEquals(app.getString(R.string.ia3_err_smaller_model), tray.primaryErrorAction().toString());
        assertTrue(tray.offersRetry());
        assertFalse(tray.offersSelectionOnly());

        cb.onError(AiRunner.ErrorKind.SERVER, "servidor");
        assertEquals(app.getString(R.string.ia3_err_check_server), tray.primaryErrorAction().toString());
        assertTrue(tray.offersRetry());

        cb.onError(AiRunner.ErrorKind.NO_MODEL, "ninguno");
        assertEquals(app.getString(R.string.ai_open_settings), tray.primaryErrorAction().toString());
        assertFalse(tray.offersRetry());

        cb.onError(AiRunner.ErrorKind.WEAK, "flojo");
        assertNull(tray.primaryErrorAction());
        assertTrue(tray.offersRetry());
        assertFalse(tray.offersSelectionOnly());

        // Oyente antiguo (sin tipo): solo Reintentar
        cb.onError("sin tipo");
        assertNull(tray.errorKind());
        assertNull(tray.primaryErrorAction());
        assertTrue(tray.offersRetry());
        tray.release();
    }

    @Test
    public void timeoutOnASelectionDoesNotOfferSelectionOnly() throws Exception {
        installFakeModel();
        AiTray tray = new AiTray(app);
        tray.setHost(host("todo el campo con mucho texto", "mucho texto"));
        tray.setLanguage("es");
        tray.show();
        tray.callback().onError(AiRunner.ErrorKind.TIMEOUT, "tardo");
        assertEquals(AiTray.State.ERROR, tray.state());
        assertTrue(tray.offersRetry());
        assertFalse(tray.offersSelectionOnly());
        tray.release();
    }

    // ------------------------------------------------------------------ historial de resultados

    @Test
    public void retryKeepsPreviousResultsAndArrowsNavigate() throws Exception {
        installFakeModel();
        AiRunner runner = new AiRunner(app);
        AiTray tray = new AiTray(app, runner);
        tray.setHost(host("ola ke ase", null));
        tray.setLanguage("es");
        tray.show();
        // La peticion fija el texto fuente del historial; los finales se simulan sin repartir el hilo principal
        // (el modelo falso fallaria), igual que haria el runner tras cada Reintentar
        tray.run(AiRunner.Action.FIX, null, null);
        assertEquals(AiTray.State.RUNNING, tray.state());
        AiRunner.Callback cb = tray.callback();
        cb.onDone("Hola, ¿qué hace?", false);
        assertEquals(AiTray.State.RESULT, tray.state());
        assertEquals(1, tray.resultCount());
        assertFalse(tray.showsNavigation());
        cb.onDone("Hola, ¿qué haces?", false);                 // Reintentar: se anade, no sustituye
        assertEquals(2, tray.resultCount());
        assertEquals(1, tray.shownIndex());
        assertEquals("Hola, ¿qué haces?", tray.result());
        assertTrue(tray.showsNavigation());
        assertEquals("2/2", tray.navigationLabel().toString());
        tray.previousResult();
        assertEquals("Hola, ¿qué hace?", tray.result());      // Reemplazar / Insertar / Copiar usan este
        assertEquals("1/2", tray.navigationLabel().toString());
        tray.previousResult();                                 // en el extremo no pasa nada
        assertEquals(0, tray.shownIndex());
        tray.nextResult();
        assertEquals(1, tray.shownIndex());
        // Tope de cinco: cae el mas antiguo
        for (int i = 3; i <= 7; i++) cb.onDone("resultado " + i, false);
        assertEquals(AiTray.HISTORY_MAX, tray.resultCount());
        assertEquals("resultado 7", tray.result());
        assertEquals("5/5", tray.navigationLabel().toString());
        for (int i = 0; i < 10; i++) tray.previousResult();
        assertEquals("resultado 3", tray.result());             // el 1 y el 2 ya no estan
        // Un resultado parcial (Cancelar) tambien entra en el historial
        cb.onCancelled("Hola, esto es un resultado parcial largo.");
        assertEquals(AiTray.State.RESULT, tray.state());
        assertEquals("Hola, esto es un resultado parcial largo.", tray.result());
        // Reabrir con el mismo texto en el campo vuelve al resultado que se estaba viendo
        tray.show();
        assertEquals(AiTray.State.RESULT, tray.state());
        assertEquals("Hola, esto es un resultado parcial largo.", tray.result());
        assertEquals(AiTray.HISTORY_MAX, tray.resultCount());
        // Con otro texto en el campo se vuelve a las acciones y el historial se vacia
        tray.setHost(host("otro texto distinto", null));
        tray.show();
        assertEquals(AiTray.State.IDLE, tray.state());
        assertEquals(0, tray.resultCount());
        assertNull(tray.result());
        tray.detach();                                           // que el error del modelo falso no llegue a este panel
        runner.release();
    }

    @Test
    public void aNewActionStartsAFreshHistory() throws Exception {
        installFakeModel();
        AiRunner runner = new AiRunner(app);
        AiTray tray = new AiTray(app, runner);
        tray.setHost(host("ola ke ase", null));
        tray.setLanguage("es");
        tray.show();
        tray.run(AiRunner.Action.FIX, null, null);
        tray.callback().onDone("uno", false);
        tray.callback().onDone("dos", false);
        assertEquals(2, tray.resultCount());
        tray.run(AiRunner.Action.SUMMARY, null, null);          // otra accion: historial nuevo
        assertEquals(AiTray.State.RUNNING, tray.state());
        assertEquals(0, tray.resultCount());
        tray.callback().onDone("resumen", false);
        assertEquals(1, tray.resultCount());
        assertFalse(tray.showsNavigation());
        tray.detach();
        runner.release();
    }

    // ------------------------------------------------------------------ tonos guardados

    @Test
    public void savedTonesAppearAsChipsNewestFirstAndLongPressForgets() throws Exception {
        installFakeModel();
        Prefs.rememberAiTone(app, "pirata");
        Prefs.rememberAiTone(app, "poético");
        AiTray tray = new AiTray(app);
        tray.setHost(host("hola mundo", null));
        tray.setLanguage("es");
        tray.show();
        tray.showTones();
        assertEquals(AiTray.State.TONES, tray.state());
        // 4 fijos + 2 guardados (el mas reciente primero) + "Personalizado…"
        assertEquals(7, tray.toneChipCount());
        assertEquals("✎ poético", tray.toneChip(4).getText().toString());
        assertEquals("✎ pirata", tray.toneChip(5).getText().toString());
        assertEquals(app.getString(R.string.ai_tone_custom), tray.toneChip(6).getText().toString());
        // Pulsacion larga: se olvida y la lista se rehace
        View chip = tray.toneChip(5);
        assertTrue(chip.performLongClick());
        assertEquals(6, tray.toneChipCount());
        assertEquals("✎ poético", tray.toneChip(4).getText().toString());
        assertFalse(Prefs.aiTones(app).contains("pirata"));
        // Los fijos no se olvidan con pulsacion larga
        assertFalse(tray.toneChip(0).isLongClickable());
        tray.release();
    }

    @Test
    public void lastToneFromPreviousVersionMigratesToTheSavedList() throws Exception {
        installFakeModel();
        Prefs.get(app).edit().putString(AiModels.LAST_TONE, "sarcástico").apply();
        AiTray tray = new AiTray(app);
        tray.setHost(host("hola mundo", null));
        tray.setLanguage("es");
        tray.show();
        tray.showTones();
        assertEquals(6, tray.toneChipCount());
        assertEquals("✎ sarcástico", tray.toneChip(4).getText().toString());
        assertEquals(1, Prefs.aiTones(app).size());
        assertEquals("sarcástico", Prefs.aiTones(app).get(0));
        tray.release();
    }

    @Test
    public void toneListIsCappedAtFiveMostRecent() {
        for (String t : new String[]{"a", "b", "c", "d", "e", "f"}) Prefs.rememberAiTone(app, t);
        List<String> saved = Prefs.aiTones(app);
        assertEquals(5, saved.size());
        assertEquals("f", saved.get(0));
        assertFalse(saved.contains("a"));
        Prefs.rememberAiTone(app, "c");                          // usar uno lo sube al principio sin duplicarlo
        saved = Prefs.aiTones(app);
        assertEquals("c", saved.get(0));
        assertEquals(5, saved.size());
        assertNotNull(saved);
    }
}
