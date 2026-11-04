package com.aurebesh.teclado;

import android.app.Application;

import com.aurebesh.teclado.llm.ChatMessage;
import com.aurebesh.teclado.llm.LlmEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Panel de inteligencia, accion Traducir y runner compartido: mensajes por idioma destino (en el idioma del
 * destino si es es/en/de, en el del teclado si es otro), sistema de traductor sin la regla de conservar el idioma,
 * limpieza del resultado (etiquetas, comillas, nota final, texto sin traducir = flojo), submenu de idiomas del
 * panel, y reenganche: un panel nuevo creado con el runner del servicio retoma la generacion en vuelo y recoge el
 * final que nadie escucho, sin que el panel viejo la cancele.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
public class AiTranslateTest {

    private Application app;
    private File fakeModel;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
    }

    @After
    public void tearDown() {
        if (fakeModel != null) fakeModel.delete();
        ShadowLooper.shadowMainLooper().idle();
    }

    /** Un archivo que empieza por "GGUF" pero no es un modelo: el panel lo ve disponible y el motor lo rechaza al instante. */
    private void installFakeModel() throws Exception {
        fakeModel = new File(AiModels.dir(app), AiModels.DEFAULT.file);
        try (FileOutputStream f = new FileOutputStream(fakeModel)) { f.write("GGUF".getBytes("UTF-8")); }
        AiModels.select(app, fakeModel.getName());
    }

    private static AiTray.Host host(final String text) {
        return new AiTray.Host() {
            public CharSequence currentText() { return text; }
            public CharSequence selectedText() { return null; }
            public void replaceText(CharSequence t) {}
            public void insertText(CharSequence t) {}
            public void copyText(CharSequence t) {}
            public void closeTray() {}
            public void openSettings() {}
        };
    }

    /** Oyente que apunta lo que recibe. */
    private static final class Recorder implements AiRunner.Callback {
        final List<String> events = new ArrayList<>();
        public void onPhase(CharSequence label) { events.add("phase"); }
        public void onPiece(String soFar, int tokens, long elapsedMs) { events.add("piece"); }
        public void onDone(String text, boolean weak) { events.add("done:" + text); }
        public void onCancelled(String partialText) { events.add("cancelled"); }
        public void onError(CharSequence message) { events.add("error"); }
    }

    /** Deja correr el hilo de trabajo y reparte en el principal hasta que la peticion termina. */
    private static void settle(AiRunner runner) throws InterruptedException {
        long end = System.currentTimeMillis() + 10000;
        while (runner.isBusy() && System.currentTimeMillis() < end) {
            ShadowLooper.shadowMainLooper().idle();
            Thread.sleep(10);
        }
        ShadowLooper.shadowMainLooper().idle();
    }

    // ------------------------------------------------------------------ mensajes

    @Test
    public void knownTargetsPromptInTheTargetLanguage() {
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.TRANSLATE, "hola mundo");
        r.lang = "es";
        r.target = "en";
        assertEquals("en", AiRunner.promptLang(r));
        String p = AiRunner.userPrompt(r, r.source, AiRunner.promptLang(r));
        assertTrue(p, p.startsWith("Translate the following text into English."));
        assertTrue(p, p.endsWith("Text:\nhola mundo"));
        r.target = "de";
        p = AiRunner.userPrompt(r, r.source, AiRunner.promptLang(r));
        assertTrue(p, p.startsWith("Übersetze den folgenden Text ins Deutsche."));
        r.target = "ES";
        assertEquals("es", AiRunner.promptLang(r));
        p = AiRunner.userPrompt(r, r.source, AiRunner.promptLang(r));
        assertTrue(p, p.startsWith("Traduce el siguiente texto al español."));
        assertTrue(p, p.endsWith("Texto:\nhola mundo"));
    }

    @Test
    public void otherTargetPromptsInTheKeyboardLanguageAndIsSanitized() {
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.TRANSLATE, "hola");
        r.lang = "de";
        r.target = " Französisch\n\"<|im_end|>\" ";
        assertNull(AiRunner.targetCode(r.target));
        assertEquals("de", AiRunner.promptLang(r));
        String p = AiRunner.userPrompt(r, r.source, "de");
        assertTrue(p, p.contains("in die Sprache „Französisch“"));
        assertFalse(p, p.contains("<|"));
        r.lang = "en";
        assertEquals("en", AiRunner.promptLang(r));
        p = AiRunner.userPrompt(r, r.source, "en");
        assertTrue(p, p.startsWith("Translate the following text into Französisch."));
        r.lang = "es";
        p = AiRunner.userPrompt(r, r.source, "es");
        assertTrue(p, p.startsWith("Traduce el siguiente texto al Französisch."));
    }

    @Test
    public void translatorSystemPromptDoesNotAskToKeepTheLanguage() {
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.TRANSLATE, "hola");
        r.target = "en";
        List<ChatMessage> m = AiRunner.messages(r, "hola", "en");
        assertEquals(2, m.size());
        assertEquals("system", m.get(0).role);
        assertTrue(m.get(0).content, m.get(0).content.startsWith("You are a translator."));
        assertFalse(m.get(0).content, m.get(0).content.contains("Keep the language"));
        AiRunner.Request fix = new AiRunner.Request(AiRunner.Action.FIX, "hola");
        assertTrue(AiRunner.messages(fix, "hola", "en").get(0).content.contains("Keep the language"));
    }

    @Test
    public void retryKeepsTheTarget() {
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.TRANSLATE, "hola");
        r.target = "italiano";
        AiRunner.Request c = r.copyForRetry();
        assertEquals("italiano", c.target);
        assertEquals(1, c.retries);
        assertTrue(AiRunner.Action.TRANSLATE.isRewrite());
        assertFalse(AiRunner.Action.TRANSLATE.isStyle());
    }

    @Test
    public void translateOptionsLeaveRoomForLongerOutput() {
        LlmEngine.Options o = AiRunner.options(AiRunner.Action.TRANSLATE, 350, 0);
        int srcTokens = (int) Math.ceil(350 / 3.5);
        assertEquals(Math.min((int) Math.ceil(srcTokens * 1.8) + 32, 500), o.maxTokens);
        assertEquals(0.20f, o.temperature, 0.001f);
        assertEquals(500, AiRunner.options(AiRunner.Action.TRANSLATE, 1800, 0).maxTokens);
    }

    // ------------------------------------------------------------------ limpieza

    @Test
    public void cleanStripsLabelQuotesAndTrailingNote() {
        AiRunner.Cleaned c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE,
                "Traducción al inglés:\n\"Hello, how are you?\"\n\nNota: he mantenido el tono informal.", "hola, ¿qué tal?");
        assertEquals(0, c.errorRes);
        assertEquals("Hello, how are you?", c.text);
        assertFalse(c.weak);
        c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "Translation: Hallo Welt", "hello world");
        assertEquals("Hallo Welt", c.text);
        c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "Übersetzung:\nHola mundo\n\n(Hinweis: informell.)", "Hallo Welt");
        assertEquals("Hola mundo", c.text);
    }

    @Test
    public void unchangedOrEmptyTranslationIsFlagged() {
        AiRunner.Cleaned c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "Hola mundo", "Hola mundo");
        assertTrue(c.weak);
        assertEquals(0, c.errorRes);
        c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "", "Hola mundo");
        assertEquals(R.string.ai_weak_result, c.errorRes);
        // Una traduccion normal no es floja aunque cambie la longitud
        c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "Guten Morgen allerseits", "Buenos días a todos");
        assertFalse(c.weak);
    }

    @Test
    public void keepsTheTrailingWhitespaceOfTheOriginal() {
        AiRunner.Cleaned c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "Hello world", "Hola mundo\n");
        assertEquals("Hello world\n", c.text);
        c = AiRunner.cleanChecked(AiRunner.Action.TRANSLATE, "Hello world ", "Hola mundo");
        assertEquals("Hello world", c.text);
    }

    // ------------------------------------------------------------------ panel

    @Test
    public void translateOpensTargetsAndOtherOpensThePrompt() throws Exception {
        installFakeModel();
        AiTray tray = new AiTray(app);
        tray.setHost(host("hola, ¿qué tal?"));
        tray.setLanguage("es");
        tray.applyTheme(Themes.ESTELAR);
        tray.show();
        assertEquals(AiTray.State.IDLE, tray.state());
        tray.showTargets();
        assertEquals(AiTray.State.TARGETS, tray.state());
        assertEquals("AiTray[targets]", tray.toString());
        tray.showTargetPrompt();
        assertEquals(AiTray.State.PROMPT, tray.state());
        tray.release();
    }

    @Test
    public void newTrayPicksUpTheEndNobodyHeard() throws Exception {
        // Sin modelo la peticion falla enseguida; el oyente se suelta antes de que el hilo principal reparta el final
        AiRunner runner = new AiRunner(app);
        Recorder first = new Recorder();
        AiRunner.Request r = new AiRunner.Request(AiRunner.Action.TRANSLATE, "hola mundo");
        r.target = "en";
        runner.run(r, first);
        assertTrue(runner.isBusy());
        assertTrue(runner.hasListener());
        runner.detach(first);
        assertFalse(runner.hasListener());
        settle(runner);
        assertFalse(runner.isBusy());
        assertTrue(runner.hasPending());
        assertTrue(first.events.toString(), first.events.isEmpty());

        // El panel nuevo creado con el runner compartido recoge el error al crearse...
        AiTray tray = new AiTray(app, runner);
        tray.setHost(host("hola mundo"));
        assertEquals(AiTray.State.ERROR, tray.state());
        assertFalse(runner.hasPending());
        assertSame(r, runner.request());
        // ...y show() lo conserva la primera vez (llego sin que nadie lo viera); despues vuelve a lo normal
        tray.show();
        assertEquals(AiTray.State.ERROR, tray.state());
        tray.show();
        assertEquals(AiTray.State.NO_MODEL, tray.state());
        runner.release();
    }

    @Test
    public void detachedTrayNeitherCancelsNorStealsTheRequest() throws Exception {
        installFakeModel();
        AiRunner runner = new AiRunner(app);
        AiTray old = new AiTray(app, runner);
        old.setHost(host("hola mundo"));
        old.setLanguage("es");
        old.show();
        assertEquals(AiTray.State.IDLE, old.state());
        old.translate("en");
        assertEquals(AiTray.State.RUNNING, old.state());
        assertTrue(old.isBusy());
        assertEquals(AiRunner.Action.TRANSLATE, runner.request().action);
        assertEquals("en", runner.request().target);

        // La vista se rehace: el panel viejo se suelta; cancel() ya no toca la generacion
        old.detach();
        assertFalse(old.isBusy());
        old.cancel();
        assertTrue(runner.isBusy());
        assertEquals(AiTray.State.IDLE, old.state());

        // El panel nuevo entra en "escribiendo" con la peticion original y recibe el final
        AiTray fresh = new AiTray(app, runner);
        fresh.setHost(host("hola mundo"));
        assertEquals(AiTray.State.RUNNING, fresh.state());
        assertTrue(fresh.isBusy());
        settle(runner);
        assertEquals(AiTray.State.ERROR, fresh.state());        // el modelo falso no carga
        assertEquals(AiTray.State.IDLE, old.state());           // el viejo no se entero
        runner.release();
    }

    @Test
    public void attachReplaysPhaseAndPieces() {
        AiRunner runner = new AiRunner(app);
        Recorder late = new Recorder();
        assertFalse(runner.attach(late));                       // nada en vuelo ni pendiente
        assertTrue(late.events.isEmpty());
        runner.detach(late);
        assertFalse(runner.hasListener());
        runner.release();
    }
}
