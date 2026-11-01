package com.aurebesh.teclado;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.List;
import java.util.Locale;

/** Servicio de teclado (IME) Aurebesh. Escribe letras latinas mostradas como glifos Aurebesh. */
public class AurebeshIME extends InputMethodService implements AurebeshKeyboardView.Listener {

    private AurebeshKeyboardView view;
    private boolean privateField = false;
    private boolean stripOn = false;

    @Override
    public View onCreateInputView() {
        view = new AurebeshKeyboardView(this);
        view.setListener(this);
        applyPrefs();
        return view;
    }

    private void applyPrefs() {
        stripOn = Prefs.strip(this);
        if (view != null) {
            view.applyPrefs(Prefs.hints(this), Prefs.vibrate(this), stripOn, Prefs.digraphs(this),
                    Prefs.theme(this), Prefs.numbers(this), Prefs.sound(this), Prefs.heightScale(this));
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;   // nunca el editor a pantalla completa en apaisado
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view == null) return;
        applyPrefs();
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        privateField = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        view.setPreviewEnabled(!privateField);   // sin burbuja en contrasenas
        if (!restarting) {
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) view.showSymbols();
            else view.resetToLetters();
        }
        view.setEnterAction(enterAction(info));
        applyLanguage();
        refresh();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype);
        applyLanguage();
    }

    /** Distribucion segun el subtipo activo (espanol o ingles) y etiqueta del espacio si hay varios. */
    @SuppressWarnings("deprecation")
    private void applyLanguage() {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        InputMethodSubtype subtype = imm != null ? imm.getCurrentInputMethodSubtype() : null;
        String locale = subtype != null ? subtype.getLocale() : Locale.getDefault().getLanguage();
        String lang = locale != null && locale.startsWith("en") ? "en" : "es";
        view.setLanguage(lang);
        boolean several = false;
        if (imm != null) {
            List<InputMethodSubtype> enabled = imm.getEnabledInputMethodSubtypeList(null, true);
            several = enabled != null && enabled.size() > 1;
        }
        view.setSpaceLabel(several ? getString("en".equals(lang) ? R.string.lang_en : R.string.lang_es) : null);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        refresh();
    }

    /** Actualiza la franja Aurebesh y la mayuscula automatica. */
    private void refresh() {
        if (view == null) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        if (privateField || !stripOn) {
            view.setStripText("");
        } else {
            CharSequence before = ic.getTextBeforeCursor(60, 0);
            view.setStripText(before == null ? "" : before);
        }
        boolean caps = info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0;
        view.setAutoShift(caps);
    }

    private static int enterAction(EditorInfo info) {
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return 0;
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEARCH: return 1;
            case EditorInfo.IME_ACTION_SEND:
            case EditorInfo.IME_ACTION_GO: return 2;
            case EditorInfo.IME_ACTION_NEXT: return 3;
            case EditorInfo.IME_ACTION_DONE: return 4;
            default: return 0;
        }
    }

    // ------------------------------------------------------------------ Listener

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(text, 1);
    }

    @Override
    public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
        } else {
            // Borra un caracter completo (incluye emojis / pares sustitutos)
            CharSequence before = ic.getTextBeforeCursor(2, 0);
            int n = 1;
            if (before != null && before.length() == 2
                    && Character.isSurrogatePair(before.charAt(0), before.charAt(1))) n = 2;
            if (before == null || before.length() == 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            } else {
                ic.deleteSurroundingText(n, 0);
            }
        }
    }

    @Override
    public void onEnter() {
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || info == null) return;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else if (multiLine || info.inputType == InputType.TYPE_NULL) {
            ic.commitText("\n", 1);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    @Override
    public void onSwitchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28 && shouldOfferSwitchingToNextInputMethod()
                && switchToNextInputMethod(false)) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }
}
