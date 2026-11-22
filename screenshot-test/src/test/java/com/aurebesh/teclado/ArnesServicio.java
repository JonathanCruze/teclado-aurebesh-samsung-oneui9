package com.aurebesh.teclado;

import android.app.Application;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Arnes comun para probar el servicio de punta a punta: editor falso con cursor, tecleo letra a letra (con o sin las
 * mayusculas de la vista), chips, deslizado, intro y onUpdateSelection atrasado. Nacio en la revision independiente de la 2.8.
 */
public abstract class ArnesServicio {

    protected final StringBuilder text = new StringBuilder();
    protected final List<Integer> actions = new ArrayList<>();
    protected final List<String> ops = new ArrayList<>();
    protected int cursor = 0;
    protected int lastReported = 0;
    protected boolean late = false;            // no informar del cursor en settle (onUpdateSelection atrasado)
    protected FlujoCorrectorTest.TestIME ime;
    protected Application app;
    protected static final int NORMAL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    protected static final int CHAT = NORMAL | InputType.TYPE_TEXT_FLAG_MULTI_LINE;

    @Before
    public void setUp() throws Exception {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
        ime = Robolectric.buildService(FlujoCorrectorTest.TestIME.class).create().get();
        ime.ic = editor();
        ime.info = new EditorInfo();
        ime.info.inputType = NORMAL;
        ime.onCreateInputView();
        newField();
    }

    protected InputConnection editor() {
        return (InputConnection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{InputConnection.class},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method m, Object[] a) {
                        String n = m.getName();
                        if (n.equals("getTextBeforeCursor")) return text.substring(Math.max(0, cursor - (Integer) a[0]), cursor);
                        if (n.equals("getTextAfterCursor")) return text.substring(cursor, Math.min(text.length(), cursor + (Integer) a[0]));
                        if (n.equals("getSelectedText")) return null;
                        if (n.equals("commitText")) {
                            String s = a[0].toString();
                            text.insert(cursor, s);
                            cursor += s.length();
                            ops.add("commit[" + s + "]");
                            return true;
                        }
                        if (n.equals("deleteSurroundingText")) {
                            int b = Math.min((Integer) a[0], cursor), af = Math.min((Integer) a[1], text.length() - cursor);
                            text.delete(cursor, cursor + af);
                            text.delete(cursor - b, cursor);
                            cursor -= b;
                            ops.add("del[" + a[0] + "," + a[1] + "]");
                            return true;
                        }
                        if (n.equals("sendKeyEvent")) {
                            KeyEvent e = (KeyEvent) a[0];
                            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && cursor > 0) cursor--;
                                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && cursor < text.length()) cursor++;
                                if (e.getKeyCode() == KeyEvent.KEYCODE_DEL && cursor > 0) { text.delete(cursor - 1, cursor); cursor--; }
                                if (e.getKeyCode() == KeyEvent.KEYCODE_ENTER) ops.add("ENTER");
                            }
                            return true;
                        }
                        if (n.equals("performEditorAction")) { actions.add((Integer) a[0]); return true; }
                        if (n.equals("getCursorCapsMode")) return TextUtils.getCapsMode(text, cursor, (Integer) a[0]);
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) return true;
                        if (r == int.class) return 0;
                        return null;
                    }
                });
    }

    protected Object field(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    protected void setField(Object o, Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    protected Object imeField(String name) throws Exception { return field(ime, AurebeshIME.class, name); }

    protected AurebeshKeyboardView view() throws Exception {
        return (AurebeshKeyboardView) field(ime, AurebeshIME.class, "view");
    }

    protected boolean shifted() throws Exception {
        return (Boolean) field(view(), AurebeshKeyboardView.class, "shift");
    }

    /** Entrega el onUpdateSelection pendiente (si el cursor cambio). */
    protected void report() {
        if (cursor != lastReported) {
            ime.onUpdateSelection(lastReported, lastReported, cursor, cursor, -1, -1);
            lastReported = cursor;
        }
    }

    protected void idle() throws Exception {
        for (int round = 0; round < 3; round++) {
            ((ExecutorService) field(ime, AurebeshIME.class, "worker")).submit(new Runnable() { @Override public void run() { } }).get();
            ((ExecutorService) field(ime, AurebeshIME.class, "suggester")).submit(new Runnable() { @Override public void run() { } }).get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    protected void settle() throws Exception {
        if (!late) report();
        idle();
    }

    protected void newField() throws Exception { newField(NORMAL, 0, ""); }

    protected void newField(int inputType, int imeOptions, String already) throws Exception {
        text.setLength(0);
        text.append(already);
        cursor = text.length();
        lastReported = cursor;
        actions.clear();
        ime.info = new EditorInfo();
        ime.info.inputType = inputType;
        ime.info.imeOptions = imeOptions;
        ime.info.initialSelStart = cursor;
        ime.info.initialSelEnd = cursor;
        ime.onFinishInputView(true);
        ime.onStartInputView(ime.info, false);
        settle();
        settle();
    }

    /** La app reinicia el mismo campo (restarting=true) con el texto dado. */
    protected void restart(String now) throws Exception {
        text.setLength(0);
        text.append(now);
        cursor = text.length();
        lastReported = cursor;
        ime.info.initialSelStart = cursor;
        ime.info.initialSelEnd = cursor;
        ime.onStartInputView(ime.info, true);
        settle();
    }

    protected String typed(String s) throws Exception { newField(); return more(s); }

    protected String more(String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            ime.onText(String.valueOf(s.charAt(i)));
            settle();
        }
        return text.toString();
    }

    /** Teclea deprisa: sin onUpdateSelection ni resultados entre teclas. */
    protected String fast(String s) throws Exception {
        for (int i = 0; i < s.length(); i++) ime.onText(String.valueOf(s.charAt(i)));
        settle();
        return text.toString();
    }

    /**
     * Teclea como en el telefono: las letras pasan por el estado de mayusculas de la vista (automatico o manual), que se
     * apaga tras cada letra como en release().
     */
    protected String keys(String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            AurebeshKeyboardView v = view();
            boolean sh = (Boolean) field(v, AurebeshKeyboardView.class, "shift");
            boolean lock = (Boolean) field(v, AurebeshKeyboardView.class, "capsLock");
            String out = String.valueOf(c);
            if ((sh || lock) && Character.isLetter(c)) out = out.toUpperCase();
            ime.onText(out);
            if (sh && !lock && c != ' ' && c != '\n') {
                setField(v, AurebeshKeyboardView.class, "shift", false);
                setField(v, AurebeshKeyboardView.class, "autoShifted", false);
            }
            settle();
        }
        return text.toString();
    }

    protected String chip(String word) throws Exception { ime.onSuggestion(word); settle(); return text.toString(); }

    protected String delete() throws Exception { ime.onDelete(); settle(); return text.toString(); }

    protected String delete(int n) throws Exception { for (int i = 0; i < n; i++) delete(); return text.toString(); }

    protected String glide(String best, String... alts) throws Exception {
        List<String> l = new ArrayList<>();
        l.add(best);
        for (String a : alts) l.add(a);
        ime.onGlide(best, l);
        settle();
        return text.toString();
    }

    protected String enter() throws Exception { ime.onEnter(); settle(); return text.toString(); }

    /** El usuario toca en otro sitio del texto. */
    protected void moveTo(int pos) throws Exception {
        cursor = pos;
        report();
        idle();
    }

    @SuppressWarnings("unchecked")
    protected List<String> chips() throws Exception {
        return new ArrayList<>((List<String>) field(view(), AurebeshKeyboardView.class, "suggestions"));
    }

    protected String highlighted() throws Exception {
        return (String) field(view(), AurebeshKeyboardView.class, "highlighted");
    }

    protected void setLang(String l) throws Exception {
        Prefs.get(app).edit().putString(Prefs.KEYBOARD_LANG, l).apply();
        settle();
        settle();
    }
}
