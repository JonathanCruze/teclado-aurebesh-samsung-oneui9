package com.aurebesh.teclado;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowVibrator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Bandejas y portapapeles (1.10): la respuesta haptica de las bandejas obedece a "Vibracion al pulsar" y al
 * nivel (sistema: performHapticFeedback de la vista; suave/media/fuerte: pulso del vibrador de 6/10/16 ms),
 * las filas del portapapeles y las teclas del panel de emojis pasan por ella, el historial se muda una sola
 * vez de "aurebesh" a "aurebesh_clips" y solo escribe ahi, y dos hilos anadiendo textos a la vez no pierden
 * ninguno dentro del tope.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TrayTest {

    private static final int W = 1080;

    private Application app;
    private ShadowVibrator vibrator;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().commit();
        app.getSharedPreferences(ClipHistory.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        vibrator = Shadows.shadowOf((Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE));
        ShadowVibrator.reset();
    }

    /** Vista que cuenta las llamadas a performHapticFeedback (la respuesta del sistema). */
    private static final class CountingView extends View {
        int calls = 0, last = -1;
        CountingView(Context c) { super(c); }
        @Override public boolean performHapticFeedback(int c) { calls++; last = c; return true; }
        @Override public boolean performHapticFeedback(int c, int flags) { calls++; last = c; return true; }
    }

    /** Fija "Vibracion al pulsar" y el nivel, y olvida lo que haya vibrado hasta ahora. */
    private void feedback(boolean vibrate, String level) {
        Prefs.get(app).edit().putBoolean(Prefs.VIBRATE, vibrate).putString(Prefs.VIBRATION, level).commit();
        ShadowVibrator.reset();
    }

    private static void layout(View v, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, h);
    }

    private static void tap(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        v.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0));
    }

    /** Primera vista del arbol cuya clase se llama asi (clases interiores privadas de las bandejas). */
    private static View byClassName(View root, String simpleName) {
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = byClassName(g.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Primera fila del portapapeles: LinearLayout pulsable con oyente (la cabecera no lo es). */
    private static View firstRow(View root) {
        if (root instanceof LinearLayout && root.isClickable() && root.hasOnClickListeners()) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = firstRow(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Haptics

    @Test
    public void hapticsOffDoesNothing() {
        CountingView v = new CountingView(app);
        feedback(false, "strong");
        Haptics.tap(v);
        Haptics.tick(v);
        assertEquals(0, v.calls);
        assertFalse(vibrator.isVibrating());
        assertEquals(0, vibrator.getMilliseconds());
        Haptics.tap(null);                              // tolera una vista nula
    }

    @Test
    public void hapticsSystemLevelUsesViewFeedback() {
        CountingView v = new CountingView(app);
        feedback(true, "system");
        Haptics.tap(v);
        assertEquals(1, v.calls);
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, v.last);
        Haptics.tick(v);
        assertEquals(2, v.calls);
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, v.last);
        assertEquals(0, vibrator.getMilliseconds());    // el vibrador no se toca
        // Sin preferencia guardada, la vibracion esta activada por defecto
        Prefs.get(app).edit().clear().commit();
        Haptics.tap(v);
        assertEquals(3, v.calls);
    }

    @Test
    public void hapticsLevelsPulseTheVibrator() {
        CountingView v = new CountingView(app);
        String[] levels = {"soft", "medium", "strong"};
        long[] ms = {6, 10, 16};
        for (int i = 0; i < levels.length; i++) {
            feedback(true, levels[i]);
            Haptics.tap(v);
            assertEquals(levels[i], ms[i], vibrator.getMilliseconds());
            assertEquals(levels[i], 0, v.calls);        // con pulso propio no se usa la respuesta del sistema
            ShadowVibrator.reset();
            Haptics.tick(v);
            assertEquals(levels[i] + " tick", ms[i], vibrator.getMilliseconds());
        }
        // Sin vibrador se vuelve a la respuesta del sistema
        feedback(true, "strong");
        vibrator.setHasVibrator(false);
        Haptics.tap(v);
        assertEquals(1, v.calls);
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, v.last);
        assertEquals(0, vibrator.getMilliseconds());
    }

    // ------------------------------------------------------------------ bandejas

    @Test
    public void clipboardRowTapsThroughHaptics() {
        final StringBuilder picked = new StringBuilder();
        KeyboardTray tray = new KeyboardTray(app);
        tray.setHost(new KeyboardTray.Host() {
            public void onTrayPick(ClipHistory.Entry entry, String text) { picked.append(text); }
            public void onTrayRemove(ClipHistory.Entry entry) { picked.append("<x>"); }
            public void onTrayClear() { picked.append("<clear>"); }
            public void onTrayClose() { picked.append("<close>"); }
            public void onTrayPin(ClipHistory.Entry entry, boolean pinned) { picked.append(pinned ? "<pin>" : "<unpin>"); }
        });
        List<ClipHistory.Entry> clips = new ArrayList<>();
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.TEXT, "hola", null, null));
        tray.show("Portapapeles", clips, "vacio");
        layout(tray, 900);
        View row = firstRow(tray);
        assertNotNull("fila del portapapeles", row);

        feedback(true, "soft");
        row.performClick();
        assertEquals("hola", picked.toString());
        assertEquals(6, vibrator.getMilliseconds());

        feedback(false, "soft");                        // apagada: pega igual, sin vibrar
        row.performClick();
        assertEquals("holahola", picked.toString());
        assertEquals(0, vibrator.getMilliseconds());

        View close = byClassName(tray, "CloseButton");
        assertNotNull("boton de cerrar", close);
        feedback(true, "medium");
        close.performClick();
        assertEquals("holahola<close>", picked.toString());
        assertEquals(10, vibrator.getMilliseconds());
    }

    @Test
    public void emojiKeysTapThroughHaptics() {
        final StringBuilder out = new StringBuilder();
        EmojiTray emojis = new EmojiTray(app);
        emojis.setHost(new EmojiTray.Host() {
            public void onEmoji(String e) { out.append(e); }
            public void onEmojiDelete() { out.append("<del>"); }
            public void onEmojiSpace() { out.append(" "); }
            public void onEmojiClose() { out.append("<abc>"); }
        });
        emojis.setFeedback(true, false);
        emojis.show(new ArrayList<String>());
        layout(emojis, 900);

        View abc = byClassName(emojis, "KeyView");      // la primera tecla inferior es abc
        assertNotNull("tecla abc", abc);
        feedback(true, "strong");
        tap(abc, 5, 5);
        assertEquals("<abc>", out.toString());
        assertEquals(16, vibrator.getMilliseconds());

        View cells = byClassName(emojis, "EmojiRow");   // primera fila de emojis visible
        assertNotNull("fila de emojis", cells);
        View cell = ((ViewGroup) cells).getChildAt(0);
        feedback(false, "strong");                      // apagada: escribe el emoji sin vibrar
        cell.performClick();
        assertEquals("<abc>" + ((android.widget.TextView) cell).getText(), out.toString());
        assertEquals(0, vibrator.getMilliseconds());
    }

    // ------------------------------------------------------------------ ClipHistory

    @Test
    public void clipHistoryMigratesToOwnFile() {
        SharedPreferences main = Prefs.get(app);
        SharedPreferences own = app.getSharedPreferences(ClipHistory.FILE, Context.MODE_PRIVATE);
        main.edit().putString(ClipHistory.KEY, "[\"hola\",{\"t\":0,\"v\":\"adios\"}]").commit();   // formato viejo y nuevo mezclados

        List<ClipHistory.Entry> items = ClipHistory.items(app);
        assertEquals(2, items.size());
        assertEquals("hola", items.get(0).text);
        assertTrue("copiado al archivo propio", own.contains(ClipHistory.KEY));
        assertFalse("borrado del principal", main.contains(ClipHistory.KEY));

        ClipHistory.addText(app, "nuevo");
        assertFalse("el principal no se vuelve a escribir", main.contains(ClipHistory.KEY));
        assertTrue(own.getString(ClipHistory.KEY, "").contains("nuevo"));
        assertEquals("nuevo", ClipHistory.items(app).get(0).text);

        main.edit().putString(ClipHistory.KEY, "[\"viejo\"]").commit();   // una vez migrado, el principal ya no se lee
        assertEquals(3, ClipHistory.items(app).size());
        assertEquals("nuevo", ClipHistory.items(app).get(0).text);

        ClipHistory.clear(app);
        assertTrue(ClipHistory.items(app).isEmpty());
        assertTrue(own.contains(ClipHistory.KEY));
        assertEquals("[]", own.getString(ClipHistory.KEY, null));
    }

    @Test
    public void clipHistoryKeepsNewestWithinCap() {
        for (int i = 0; i < ClipHistory.MAX + 5; i++) ClipHistory.addText(app, "texto " + i);
        List<ClipHistory.Entry> items = ClipHistory.items(app);
        assertEquals(ClipHistory.MAX, items.size());
        assertEquals("texto " + (ClipHistory.MAX + 4), items.get(0).text);
        ClipHistory.addText(app, "texto 10");           // repetido: sube al principio, no se duplica
        assertEquals("texto 10", ClipHistory.items(app).get(0).text);
        assertEquals(ClipHistory.MAX, ClipHistory.items(app).size());
        ClipHistory.remove(app, new ClipHistory.Entry(ClipHistory.Entry.TEXT, "texto 10", null, null));
        assertEquals(ClipHistory.MAX - 1, ClipHistory.items(app).size());
    }

    /** Dos hilos anaden 50 textos cada uno (10 distintos por hilo, repetidos): al final estan los 20, ninguno se pierde. */
    @Test
    public void concurrentAddsLoseNothing() throws Exception {
        final int perThread = 50, distinct = ClipHistory.MAX / 2;
        final Throwable[] failure = new Throwable[1];
        Thread[] threads = new Thread[2];
        for (int t = 0; t < threads.length; t++) {
            final String prefix = t == 0 ? "a" : "b";
            threads[t] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int i = 0; i < perThread; i++) ClipHistory.addText(app, prefix + (i % distinct));
                    } catch (Throwable e) {
                        failure[0] = e;
                    }
                }
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join(30000);
        if (failure[0] != null) throw new AssertionError("hilo", failure[0]);

        List<ClipHistory.Entry> items = ClipHistory.items(app);
        assertEquals(ClipHistory.MAX, items.size());
        Set<String> texts = new HashSet<>();
        for (ClipHistory.Entry e : items) texts.add(e.text);
        assertEquals("sin duplicados", ClipHistory.MAX, texts.size());
        for (int i = 0; i < distinct; i++) {
            assertTrue("falta a" + i, texts.contains("a" + i));
            assertTrue("falta b" + i, texts.contains("b" + i));
        }
    }
}
