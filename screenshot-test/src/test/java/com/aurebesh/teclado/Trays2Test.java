package com.aurebesh.teclado;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bandejas 2.0: fijados del portapapeles (fijar y soltar sobreviven a reescrituras de la lista, "Borrar todo" los
 * conserva, tope de diez), cuadricula "..." (modo Editar: elegir, ordenar y "Listo" entrega la cinta), panel de
 * edicion (las flechas van con select cuando "Seleccionar" esta activo; repiten al mantener) y tonos de piel de los
 * emojis (variante recordada por emoji y aplicada al insertar).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Trays2Test {

    private static final int W = 1080;
    private static final String TONE_MEDIUM_LIGHT = "🏼";   // U+1F3FC
    private static final String TONE_MEDIUM = "🏽";         // U+1F3FD
    private static final String TONE_LIGHT = "🏻";          // U+1F3FB

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().commit();
        app.getSharedPreferences(ClipHistory.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences(EmojiTray.TONES_FILE, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void layout(View v, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, h);
    }

    private static void event(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void down(View v, float x, float y) { event(v, MotionEvent.ACTION_DOWN, x, y); }
    private static void up(View v, float x, float y) { event(v, MotionEvent.ACTION_UP, x, y); }
    private static void tap(View v, float x, float y) { down(v, x, y); up(v, x, y); }

    private static void idle(long ms) { ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(ms)); }

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

    /** Primera vista del arbol con esa descripcion de contenido. */
    private static View byDescription(View root, CharSequence description) {
        if (description.equals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = byDescription(g.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ClipHistory.Entry text(String s) { return new ClipHistory.Entry(ClipHistory.Entry.TEXT, s, null, null); }

    private static int count(List<ClipHistory.Entry> l, String s) {
        int n = 0;
        for (ClipHistory.Entry e : l) if (s.equals(e.text)) n++;
        return n;
    }

    // ------------------------------------------------------------------ ClipHistory: fijados

    @Test
    public void pinSurvivesRewritesAndUnpin() {
        ClipHistory.addText(app, "a");
        ClipHistory.addText(app, "b");
        ClipHistory.addText(app, "c");
        assertEquals("c", ClipHistory.items(app).get(0).text);

        assertTrue(ClipHistory.setPinned(app, text("a"), true));
        List<ClipHistory.Entry> items = ClipHistory.items(app);
        assertEquals("fijado primero", "a", items.get(0).text);
        assertTrue(items.get(0).pinned);
        assertEquals("c", items.get(1).text);
        assertEquals("b", items.get(2).text);
        assertFalse(items.get(1).pinned);

        // "f":1 en el JSON del archivo propio: asi sobrevive al reinicio
        SharedPreferences own = app.getSharedPreferences(ClipHistory.FILE, Context.MODE_PRIVATE);
        assertTrue(own.getString(ClipHistory.KEY, "").contains("\"f\":1"));

        // La lista se reescribe muchas veces (mas de MAX textos nuevos): el fijado no caduca
        for (int i = 0; i < ClipHistory.MAX + 5; i++) ClipHistory.addText(app, "texto " + i);
        items = ClipHistory.items(app);
        assertEquals("a", items.get(0).text);
        assertTrue(items.get(0).pinned);
        assertEquals("MAX sueltos mas el fijado", ClipHistory.MAX + 1, items.size());
        assertEquals("texto " + (ClipHistory.MAX + 4), items.get(1).text);

        // Copiar de nuevo un texto fijado no lo desfija ni lo duplica
        ClipHistory.addText(app, "a");
        items = ClipHistory.items(app);
        assertEquals(1, count(items, "a"));
        assertTrue(items.get(0).pinned);

        // equals ignora pinned: la entrada suelta encuentra a la fijada
        assertTrue(items.get(0).equals(text("a")));
        assertEquals(items.get(0).hashCode(), text("a").hashCode());

        // Soltar: vuelve a ser una entrada normal (y sigue ahi)
        assertTrue(ClipHistory.setPinned(app, items.get(0), false));
        items = ClipHistory.items(app);
        assertEquals(1, count(items, "a"));
        for (ClipHistory.Entry e : items) assertFalse(e.pinned);
        assertFalse(own.getString(ClipHistory.KEY, "").contains("\"f\":1"));

        // Fijar algo que no esta: false
        assertFalse(ClipHistory.setPinned(app, text("no existe"), true));
    }

    @Test
    public void clearKeepsPinnedUnlessAsked() {
        ClipHistory.addText(app, "suelto");
        ClipHistory.addText(app, "fijo");
        assertTrue(ClipHistory.setPinned(app, text("fijo"), true));

        ClipHistory.clear(app);
        List<ClipHistory.Entry> items = ClipHistory.items(app);
        assertEquals(1, items.size());
        assertEquals("fijo", items.get(0).text);
        assertTrue(items.get(0).pinned);

        ClipHistory.clear(app, true);
        assertTrue(ClipHistory.items(app).isEmpty());
    }

    @Test
    public void pinnedLimitIsTen() {
        for (int i = 0; i < ClipHistory.MAX_PINNED + 1; i++) ClipHistory.addText(app, "p" + i);
        for (int i = 0; i < ClipHistory.MAX_PINNED; i++) assertTrue(ClipHistory.setPinned(app, text("p" + i), true));
        assertFalse("el undecimo no se fija", ClipHistory.setPinned(app, text("p" + ClipHistory.MAX_PINNED), true));
        int pinned = 0;
        for (ClipHistory.Entry e : ClipHistory.items(app)) if (e.pinned) pinned++;
        assertEquals(ClipHistory.MAX_PINNED, pinned);
        // Quitar una fijada con remove funciona aunque llegue la version suelta
        ClipHistory.remove(app, text("p0"));
        assertEquals(0, count(ClipHistory.items(app), "p0"));
    }

    // ------------------------------------------------------------------ KeyboardTray: chincheta

    @Test
    public void trayPinButtonAndLongPressAskHost() {
        final StringBuilder out = new StringBuilder();
        KeyboardTray tray = new KeyboardTray(app);
        tray.setHost(new KeyboardTray.Host() {
            public void onTrayPick(ClipHistory.Entry entry, String text) { out.append("pick:").append(text).append(";"); }
            public void onTrayRemove(ClipHistory.Entry entry) { out.append("rm:").append(entry.text).append(";"); }
            public void onTrayClear() { out.append("clear;"); }
            public void onTrayClose() { out.append("close;"); }
            public void onTrayPin(ClipHistory.Entry entry, boolean pinned) { out.append("pin:").append(entry.text).append(pinned ? "+" : "-").append(";"); }
        });
        List<ClipHistory.Entry> clips = new ArrayList<>();
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.TEXT, "fijo", null, null, true));
        clips.add(text("suelto"));
        tray.show("Portapapeles", clips, "vacio");
        layout(tray, 900);

        View pinned = byDescription(tray, app.getString(R.string.clip_unpin));   // chincheta de la fila fijada
        View loose = byDescription(tray, app.getString(R.string.clip_pin));      // chincheta de la suelta
        assertNotNull(pinned);
        assertNotNull(loose);
        pinned.performClick();
        loose.performClick();
        assertEquals("pin:fijo-;pin:suelto+;", out.toString());

        out.setLength(0);
        View row = (View) loose.getParent();
        assertTrue("pulsacion larga en la fila", row.performLongClick());
        assertEquals("pin:suelto+;", out.toString());

        // Hay sueltas: "Borrar todo" solo avisa de vaciar
        out.setLength(0);
        TextView clear = (TextView) byText(tray, app.getString(R.string.clipboard_clear));
        assertNotNull(clear);
        clear.performClick();
        assertEquals("clear;", out.toString());

        // Solo fijadas: el boton pasa a "Borrar tambien los fijados" y las quita una a una antes de vaciar
        out.setLength(0);
        clips.clear();
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.TEXT, "fijo", null, null, true));
        tray.show("Portapapeles", clips, "vacio");
        layout(tray, 900);
        TextView clearPinned = (TextView) byText(tray, app.getString(R.string.clip_clear_pinned));
        assertNotNull(clearPinned);
        clearPinned.performClick();
        assertEquals("rm:fijo;clear;", out.toString());
    }

    /** Primer TextView del arbol con ese texto exacto. */
    private static View byText(View root, CharSequence text) {
        if (root instanceof TextView && text.toString().contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = byText(g.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ToolsTray

    @Test
    public void toolsEditModeDeliversOrderWithTwoItems() {
        final List<String> out = new ArrayList<>();
        ToolsTray tools = new ToolsTray(app, new ToolsTray.Host() {
            public void onToolsPick(int tool) { out.add("pick" + tool); }
            public void onToolsOrder(int[] barItems) {
                StringBuilder b = new StringBuilder("order");
                for (int id : barItems) b.append(':').append(id);
                out.add(b.toString());
            }
            public void onToolsClose() { out.add("close"); }
        });
        int[] all = AurebeshKeyboardView.ALL_TOOLS;
        boolean[] available = new boolean[all.length];
        for (int i = 0; i < all.length; i++) available[i] = all[i] != AurebeshKeyboardView.TOOL_SPLIT;   // dividir no cabe aqui
        int[] bar = {AurebeshKeyboardView.TOOL_EMOJI, AurebeshKeyboardView.TOOL_CLIPBOARD, AurebeshKeyboardView.TOOL_HAND};
        tools.show(bar, all, available);
        layout(tools, 900);

        // Modo normal: una celda ejecuta; una no disponible, no; "..." no esta en la cuadricula
        tools.cellOf(AurebeshKeyboardView.TOOL_CLIPBOARD).performClick();
        assertEquals("pick" + AurebeshKeyboardView.TOOL_CLIPBOARD, out.get(0));
        tools.cellOf(AurebeshKeyboardView.TOOL_SPLIT).performClick();
        assertEquals(1, out.size());
        assertNull(tools.cellOf(AurebeshKeyboardView.TOOL_MORE));

        // Editar: quitar emojis y una mano, anadir edicion de texto; Listo entrega dos elementos
        tools.editButton().performClick();
        assertTrue(tools.isEditing());
        tools.cellOf(AurebeshKeyboardView.TOOL_EMOJI).performClick();
        tools.cellOf(AurebeshKeyboardView.TOOL_HAND).performClick();
        tools.cellOf(AurebeshKeyboardView.TOOL_EDIT).performClick();
        assertEquals(1, out.size());                                               // en edicion no se ejecuta nada
        assertEquals(2, tools.barItems().length);
        assertEquals(AurebeshKeyboardView.TOOL_CLIPBOARD, tools.barItems()[0]);
        assertEquals(AurebeshKeyboardView.TOOL_EDIT, tools.barItems()[1]);

        // ‹ sobre edicion de texto la pone primera
        tools.arrowOf(AurebeshKeyboardView.TOOL_EDIT, true).performClick();
        assertEquals(AurebeshKeyboardView.TOOL_EDIT, tools.barItems()[0]);
        assertEquals(AurebeshKeyboardView.TOOL_CLIPBOARD, tools.barItems()[1]);

        tools.editButton().performClick();                                         // Listo
        assertFalse(tools.isEditing());
        assertEquals("order:" + AurebeshKeyboardView.TOOL_EDIT + ":" + AurebeshKeyboardView.TOOL_CLIPBOARD, out.get(1));
        assertEquals(2, out.size());
    }

    @Test
    public void toolsBarHoldsAtMostFive() {
        ToolsTray tools = new ToolsTray(app, new ToolsTray.Host() {
            public void onToolsPick(int tool) {}
            public void onToolsOrder(int[] barItems) {}
            public void onToolsClose() {}
        });
        int[] all = AurebeshKeyboardView.ALL_TOOLS;
        boolean[] available = new boolean[all.length];
        for (int i = 0; i < all.length; i++) available[i] = true;
        tools.show(new int[0], all, available);
        tools.editButton().performClick();
        int added = 0;
        for (int id : all) {
            if (id == AurebeshKeyboardView.TOOL_MORE) continue;
            tools.cellOf(id).performClick();
            added++;
        }
        assertTrue(added > ToolsTray.MAX_BAR);
        assertEquals(ToolsTray.MAX_BAR, tools.barItems().length);
    }

    // ------------------------------------------------------------------ EditTray

    @Test
    public void editArrowsCarrySelectAndRepeat() {
        final List<String> out = new ArrayList<>();
        EditTray edit = new EditTray(app, new EditTray.Host() {
            public void onEditMove(int keyCode, boolean select) { out.add("move" + keyCode + (select ? "s" : "")); }
            public void onEditAction(int actionId) { out.add("act" + actionId); }
            public void onEditHome(boolean end, boolean select) { out.add((end ? "end" : "home") + (select ? "s" : "")); }
            public void onEditClose() { out.add("close"); }
        });
        edit.show();
        layout(edit, 900);

        View right = edit.arrowKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertNotNull(right);
        tap(right, 5, 5);
        assertEquals("move" + KeyEvent.KEYCODE_DPAD_RIGHT, out.get(0));

        edit.selectButton().performClick();
        assertTrue(edit.isSelecting());
        tap(right, 5, 5);
        assertEquals("move" + KeyEvent.KEYCODE_DPAD_RIGHT + "s", out.get(1));

        // Mantener: una al bajar y luego repeticiones (420 ms y despues cada 60 ms); al soltar se para
        out.clear();
        View left = edit.arrowKey(KeyEvent.KEYCODE_DPAD_LEFT);
        down(left, 5, 5);
        idle(400);
        assertEquals(1, out.size());
        idle(200);
        assertTrue("repite al mantener: " + out.size(), out.size() >= 3);
        up(left, 5, 5);
        int n = out.size();
        idle(500);
        assertEquals("se para al soltar", n, out.size());
        for (String s : out) assertEquals("move" + KeyEvent.KEYCODE_DPAD_LEFT + "s", s);

        // Inicio con seleccion; copiar apaga Seleccionar
        out.clear();
        edit.actionButton(android.R.id.copy).performClick();
        assertEquals("act" + android.R.id.copy, out.get(0));
        assertFalse(edit.isSelecting());
        tap(right, 5, 5);
        assertEquals("move" + KeyEvent.KEYCODE_DPAD_RIGHT, out.get(1));

        edit.setSelection(3);
        assertEquals(app.getResources().getQuantityString(R.plurals.edit_selected, 3, 3),
                ((TextView) byText(edit, app.getResources().getQuantityString(R.plurals.edit_selected, 3, 3))).getText().toString());
        edit.setSelection(0);
        assertNotNull(byText(edit, app.getString(R.string.edit_title)));
    }

    // ------------------------------------------------------------------ EmojiTray: tonos de piel

    @Test
    public void emojiToneRememberedAndApplied() {
        final StringBuilder out = new StringBuilder();
        EmojiTray emojis = new EmojiTray(app);
        emojis.setHost(new EmojiTray.Host() {
            public void onEmoji(String e) { out.append(e); }
            public void onEmojiDelete() { out.append("<del>"); }
            public void onEmojiSpace() { out.append(" "); }
            public void onEmojiClose() { out.append("<abc>"); }
        });

        assertTrue(EmojiTray.canTone("👍"));
        assertTrue("con FE0F en la lista", EmojiTray.canTone("✌️"));
        assertFalse(EmojiTray.canTone("😀"));
        assertEquals(6, EmojiTray.variants("👋").size());
        assertEquals("👋", EmojiTray.variants("👋").get(0));
        assertEquals("✌" + TONE_LIGHT, EmojiTray.applyTone("✌️", 1));          // el modificador sustituye al FE0F

        assertEquals("👍", emojis.toned("👍"));
        emojis.rememberTone("👍", 3);
        assertEquals(3, emojis.toneOf("👍"));
        assertEquals("👍" + TONE_MEDIUM, emojis.toned("👍"));
        assertEquals("👎", emojis.toned("👎"));                                     // cada emoji recuerda el suyo
        assertEquals(3, app.getSharedPreferences(EmojiTray.TONES_FILE, Context.MODE_PRIVATE).getInt("👍", 0));

        // Un toque normal escribe la variante recordada
        List<String> recent = new ArrayList<>();
        recent.add("👍");
        recent.add("😀");
        emojis.show(recent);
        layout(emojis, 900);
        ViewGroup row = (ViewGroup) byClassName(emojis, "EmojiRow");
        assertNotNull(row);
        TextView thumb = (TextView) row.getChildAt(0);
        assertEquals("👍", thumb.getText().toString());
        thumb.performClick();
        assertEquals("👍" + TONE_MEDIUM, out.toString());

        // Mantener abre el menu con las seis variantes; elegir una la escribe y queda recordada
        out.setLength(0);
        assertTrue(thumb.performLongClick());
        assertTrue(emojis.isToneMenuShown());
        ViewGroup menu = (ViewGroup) byDescription(emojis, app.getString(R.string.emoji_tone_title));
        assertNotNull(menu);
        assertEquals(6, menu.getChildCount());
        assertEquals("👍" + TONE_MEDIUM_LIGHT, ((TextView) menu.getChildAt(2)).getText().toString());
        menu.getChildAt(2).performClick();
        assertFalse(emojis.isToneMenuShown());
        assertEquals("👍" + TONE_MEDIUM_LIGHT, out.toString());
        assertEquals(2, emojis.toneOf("👍"));

        // Una carita no abre menu; sin modificador (0) se olvida el tono
        assertFalse(((TextView) row.getChildAt(1)).performLongClick());
        emojis.rememberTone("👍", 0);
        assertEquals("👍", emojis.toned("👍"));

        // Un reciente que ya lleva tono se escribe tal cual, y su menu es el de la base
        emojis.rememberTone("👍", 4);
        assertEquals("👍" + TONE_LIGHT, emojis.toned("👍" + TONE_LIGHT));
        assertTrue(EmojiTray.canTone("👍" + TONE_LIGHT));
        assertEquals("👍", EmojiTray.baseOf("👍" + TONE_LIGHT));
        assertEquals("👍" + TONE_MEDIUM, EmojiTray.applyTone("👍" + TONE_LIGHT, 3));
        assertEquals("👍", EmojiTray.applyTone("👍" + TONE_LIGHT, 0));
    }
}
