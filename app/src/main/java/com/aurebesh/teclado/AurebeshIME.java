package com.aurebesh.teclado;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio de teclado (IME) Aurebesh. Escribe letras latinas mostradas como glifos Aurebesh.
 * Autocorrige con los diccionarios integrados, guarda el historial del portapapeles y envia
 * texto a las apps del telefono que lo procesan (acciones de texto). Sin internet.
 */
public class AurebeshIME extends InputMethodService
        implements AurebeshKeyboardView.Listener, KeyboardTray.Host {

    private static final String SEPARATORS = " .,;:!?\n";
    private static final long PROCESSED_TTL = 5 * 60 * 1000L;

    private FrameLayout root;
    private AurebeshKeyboardView view;
    private KeyboardTray tray;
    private boolean privateField = false;
    private boolean stripOn = false;
    private boolean autocorrect = true;
    private boolean suggestionsAllowed = false;   // el campo admite sugerencias
    private String lang = "es";

    // Autocorreccion
    private Dictionary dict;                      // del idioma actual; null hasta que cargue
    private String lastWord;                      // palabra para la que se calcularon las sugerencias
    private String pendingCorrection;             // se aplica con el siguiente separador
    private String[] lastCorrection;              // {original, corregida, separador} para deshacer
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Portapapeles
    private long lastClipStamp = -1;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override public void onPrimaryClipChanged() { captureClip(); }
            };

    // Acciones de texto: el resultado llega por TextActionActivity y se aplica al volver al campo
    private static CharSequence processed;
    private static long processedAt;
    private String pendingText;
    private String pendingField;
    private boolean pendingWhole;
    private int pendingSelStart, pendingSelEnd;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (ClipHistory.KEY.equals(key)) return;
                    applyPrefs();                   // el teclado en pantalla cambia al instante
                    applyLanguage();
                    refresh();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.addPrimaryClipChangedListener(clipListener);
    }

    @Override
    public void onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        root = new FrameLayout(this);
        view = new AurebeshKeyboardView(this);
        view.setListener(this);
        tray = new KeyboardTray(this);
        tray.setHost(this);
        tray.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tray, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyPrefs();
        return root;
    }

    private void applyPrefs() {
        stripOn = Prefs.strip(this);
        autocorrect = Prefs.autocorrect(this);
        if (view != null) {
            view.applyPrefs(Prefs.hints(this), Prefs.vibrate(this), stripOn, Prefs.digraphs(this),
                    Prefs.theme(this), Prefs.numbers(this), Prefs.sound(this), Prefs.heightScale(this));
            view.setToolbar(Prefs.toolbar(this));
            view.setSuggestionBar(autocorrect);
            view.setHandMode(Prefs.handMode(this));
            if (!autocorrect) clearSuggestions();
        }
        if (tray != null) tray.applyTheme(Themes.byId(Prefs.theme(this)));
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;   // nunca el editor a pantalla completa en apaisado
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        applyProcessed(info);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view == null) return;
        hideTray();
        applyPrefs();
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        privateField = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        suggestionsAllowed = cls == InputType.TYPE_CLASS_TEXT && !privateField
                && (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
                && variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        view.setPreviewEnabled(!privateField);   // sin burbuja en contrasenas
        if (!restarting) {
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) view.showSymbols();
            else view.resetToLetters();
        }
        view.setEnterAction(enterAction(info));
        lastCorrection = null;
        clearSuggestions();
        applyLanguage();
        captureClip();
        applyProcessed(info);
        refresh();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        clearSuggestions();
        hideTray();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype);
        applyLanguage();
        refresh();
    }

    /** Distribucion segun el subtipo activo; el globo solo cuando hay varios idiomas Aurebesh activos. */
    @SuppressWarnings("deprecation")
    private void applyLanguage() {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        InputMethodSubtype subtype = imm != null ? imm.getCurrentInputMethodSubtype() : null;
        String locale = subtype != null ? subtype.getLocale() : Locale.getDefault().getLanguage();
        lang = locale != null && locale.startsWith("en") ? "en" : "es";
        view.setLanguage(lang);
        boolean several = false;
        if (imm != null) {
            List<InputMethodSubtype> enabled = imm.getEnabledInputMethodSubtypeList(null, true);
            several = enabled != null && enabled.size() > 1;
        }
        view.setLanguageSwitch(several);
        view.setSpaceLabel(several ? getString("en".equals(lang) ? R.string.lang_en : R.string.lang_es) : null);
        loadDictionary();
    }

    /** Carga el diccionario del idioma actual en segundo plano (una vez por idioma). */
    private void loadDictionary() {
        final String l = lang;
        Dictionary d = Dictionary.cached(l);
        if (d != null) { dict = d; return; }
        dict = null;
        loader.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Dictionary loaded = Dictionary.get(AurebeshIME.this, l);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (l.equals(lang)) { dict = loaded; refresh(); }
                        }
                    });
                } catch (Exception ignored) {
                    // sin diccionario no hay sugerencias; el teclado sigue funcionando
                }
            }
        });
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        refresh();
    }

    /** Actualiza la franja Aurebesh, la mayuscula automatica y las sugerencias. */
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
        updateSuggestions(ic);
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

    // ------------------------------------------------------------------ autocorreccion

    /** Palabra que se esta escribiendo (letras justo antes del cursor, con el cursor al final). */
    private String currentWord(InputConnection ic) {
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0 && Character.isLetter(after.charAt(0))) return null;
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return null;
        int i = before.length();
        while (i > 0 && (Character.isLetter(before.charAt(i - 1)) || before.charAt(i - 1) == '\'')) i--;
        return before.subSequence(i, before.length()).toString();
    }

    private static boolean looksLikeAcronym(String w) {
        int upper = 0;
        for (int i = 0; i < w.length(); i++) if (Character.isUpperCase(w.charAt(i))) upper++;
        return upper >= 2;
    }

    private void updateSuggestions(InputConnection ic) {
        if (!autocorrect || !suggestionsAllowed || dict == null) {
            clearSuggestions();
            return;
        }
        String word = currentWord(ic);
        if (word == null || word.isEmpty()) {
            clearSuggestions();
            return;
        }
        if (word.equals(lastWord)) return;              // ya calculadas
        lastWord = word;
        pendingCorrection = null;
        List<String> chips = new ArrayList<>();
        if (!looksLikeAcronym(word)) {
            Dictionary.Result r = dict.suggest(word);
            pendingCorrection = r.correction;
            if (!r.known && !r.suggestions.isEmpty()) chips.add("\"" + word + "\"");   // conservar lo escrito
            for (String s : r.suggestions) {
                if (chips.size() >= 3) break;
                chips.add(s);
            }
        }
        view.setSuggestions(chips, pendingCorrection);
    }

    private void clearSuggestions() {
        lastWord = null;
        pendingCorrection = null;
        if (view != null) view.setSuggestions(null, null);
    }

    private static String matchCase(String original, String replacement) {
        if (original.isEmpty() || replacement.isEmpty()) return replacement;
        if (original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT))) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.charAt(0)))
            return replacement.substring(0, 1).toUpperCase(Locale.ROOT) + replacement.substring(1);
        return replacement;
    }

    /** Sustituye la palabra escrita por la correccion pendiente (mas el separador). */
    private boolean applyCorrection(InputConnection ic, String suffix) {
        if (pendingCorrection == null || lastWord == null) return false;
        String cur = currentWord(ic);
        if (cur == null || !cur.equals(lastWord)) return false;
        String fix = matchCase(lastWord, pendingCorrection);
        if (fix.equals(lastWord)) return false;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(lastWord.length(), 0);
        ic.commitText(fix + suffix, 1);
        ic.endBatchEdit();
        lastCorrection = new String[]{lastWord, fix, suffix};
        clearSuggestions();
        return true;
    }

    // ------------------------------------------------------------------ teclas

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean separator = text.length() == 1 && SEPARATORS.indexOf(text.charAt(0)) >= 0;
        if (separator && applyCorrection(ic, text)) return;
        if (!separator) lastCorrection = null;
        ic.commitText(text, 1);
    }

    @Override
    public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
            return;
        }
        // Retroceso justo despues de una autocorreccion: recupera la palabra original
        if (lastCorrection != null) {
            String corrected = lastCorrection[1] + lastCorrection[2];
            CharSequence before = ic.getTextBeforeCursor(corrected.length(), 0);
            if (before != null && before.toString().equals(corrected)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(corrected.length(), 0);
                ic.commitText(lastCorrection[0] + lastCorrection[2], 1);
                ic.endBatchEdit();
                lastCorrection = null;
                return;
            }
            lastCorrection = null;
        }
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

    @Override
    public void onEnter() {
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || info == null) return;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            applyCorrection(ic, "");
            ic.performEditorAction(action);
        } else if (multiLine || info.inputType == InputType.TYPE_NULL) {
            onText("\n");
        } else {
            applyCorrection(ic, "");
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    /** Globo: siguiente idioma Aurebesh (espanol <-> ingles), sin salir del teclado. */
    @Override
    public void onSwitchLanguage() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToNextInputMethod(true);
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.switchToNextInputMethod(getWindow().getWindow().getAttributes().token, true);
        }
    }

    /** Mantener el espacio: selector de teclados del sistema (para volver al de Samsung). */
    @Override
    public void onSwitchKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override
    public void onSuggestion(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String cur = currentWord(ic);
        ic.beginBatchEdit();
        if (word.length() >= 2 && word.startsWith("\"") && word.endsWith("\"")) {
            ic.commitText(" ", 1);                      // conservar lo escrito
        } else {
            if (cur != null && !cur.isEmpty()) {
                ic.deleteSurroundingText(cur.length(), 0);
                word = matchCase(cur, word);
            }
            ic.commitText(word + " ", 1);
        }
        ic.endBatchEdit();
        lastCorrection = null;
        clearSuggestions();
    }

    // ------------------------------------------------------------------ barra de herramientas

    @Override
    public void onToolbar(int tool) {
        switch (tool) {
            case AurebeshKeyboardView.TOOL_ACTIONS: openTextActions(); break;
            case AurebeshKeyboardView.TOOL_CLIPBOARD: openClipboard(); break;
            case AurebeshKeyboardView.TOOL_SETTINGS:
                try {
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
                break;
        }
    }

    private void showTray() {
        if (tray == null || view == null) return;
        int h = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        if (h <= 0) h = Math.round(260 * getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = tray.getLayoutParams();
        lp.height = h;
        tray.setLayoutParams(lp);
        tray.setVisibility(View.VISIBLE);
        view.setVisibility(View.INVISIBLE);
    }

    private void hideTray() {
        if (tray == null || view == null) return;
        tray.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ portapapeles

    /** Guarda en el historial lo ultimo copiado (solo si cambio desde la ultima vez). */
    private void captureClip() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        try {
            if (!cm.hasPrimaryClip()) return;
            ClipDescription d = cm.getPrimaryClipDescription();
            long stamp = d != null ? d.getTimestamp() : -1;
            if (stamp > 0 && stamp == lastClipStamp) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            lastClipStamp = stamp;
            if (!TextUtils.isEmpty(t)) ClipHistory.add(this, t);
        } catch (RuntimeException ignored) {
            // sin acceso al portapapeles en este momento
        }
    }

    private void openClipboard() {
        captureClip();
        List<KeyboardTray.Item> items = new ArrayList<>();
        for (String s : ClipHistory.items(this)) items.add(new KeyboardTray.Item(s, null, s));
        tray.show(KeyboardTray.MODE_CLIPBOARD, getString(R.string.clipboard_title), items,
                getString(R.string.clipboard_empty), null);
        showTray();
    }

    // ------------------------------------------------------------------ acciones de texto

    private static Intent processIntent() {
        return new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
    }

    private static String fieldKey(EditorInfo info) {
        return info.packageName + "#" + info.fieldId;
    }

    /** Texto del campo (o la seleccion) y lista de apps que pueden procesarlo. */
    private void openTextActions() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        CharSequence sel = ic.getSelectedText(0);
        CharSequence text;
        boolean whole;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (!TextUtils.isEmpty(sel)) {
            text = sel;
            whole = false;
            if (et != null && et.selectionStart >= 0 && et.selectionEnd >= 0) {
                pendingSelStart = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
                pendingSelEnd = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
            } else {
                whole = true;
            }
        } else {
            text = et != null ? et.text : null;
            if (TextUtils.isEmpty(text)) {
                CharSequence b = ic.getTextBeforeCursor(5000, 0);
                CharSequence a = ic.getTextAfterCursor(5000, 0);
                text = (b == null ? "" : b.toString()) + (a == null ? "" : a.toString());
            }
            whole = true;
        }
        if (text == null || text.toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.actions_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingText = text.toString();
        pendingWhole = whole;
        pendingField = fieldKey(info);

        PackageManager pm = getPackageManager();
        List<KeyboardTray.Item> items = new ArrayList<>();
        List<ResolveInfo> handlers = pm.queryIntentActivities(processIntent(), 0);
        if (handlers != null) {
            for (ResolveInfo ri : handlers) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
                items.add(new KeyboardTray.Item(ri.loadLabel(pm), ri.loadIcon(pm), ri));
            }
        }
        items.add(new KeyboardTray.Item(getString(R.string.actions_choose), null, null));
        tray.show(KeyboardTray.MODE_ACTIONS, getString(R.string.actions_title), items, null, pendingText);
        showTray();
    }

    private void launchTextAction(ResolveInfo ri) {
        if (pendingText == null) return;
        Intent i = new Intent(this, TextActionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(TextActionActivity.EXTRA_TEXT, pendingText);
        if (ri != null && ri.activityInfo != null) {
            i.putExtra(TextActionActivity.EXTRA_PACKAGE, ri.activityInfo.packageName);
            i.putExtra(TextActionActivity.EXTRA_CLASS, ri.activityInfo.name);
        }
        processed = null;
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.actions_empty, Toast.LENGTH_SHORT).show();
        }
    }

    /** Llamado por TextActionActivity con el texto que devolvio la app elegida. */
    public static void deliverProcessedText(CharSequence text) {
        processed = text;
        processedAt = SystemClock.uptimeMillis();
    }

    /** Al volver al campo de origen, sustituye la seleccion (o todo el texto) por el resultado. */
    private void applyProcessed(EditorInfo info) {
        if (processed == null || pendingField == null || info == null) return;
        if (SystemClock.uptimeMillis() - processedAt > PROCESSED_TTL) {
            processed = null;
            pendingField = null;
            return;
        }
        if (!pendingField.equals(fieldKey(info))) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence out = processed;
        processed = null;
        pendingField = null;
        ic.beginBatchEdit();
        if (pendingWhole) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null && et.text.length() > 0) {
                ic.setSelection(0, et.text.length());
            } else {
                CharSequence b = ic.getTextBeforeCursor(10000, 0);
                CharSequence a = ic.getTextAfterCursor(10000, 0);
                ic.deleteSurroundingText(b == null ? 0 : b.length(), a == null ? 0 : a.length());
            }
        } else {
            ic.setSelection(pendingSelStart, pendingSelEnd);
        }
        ic.commitText(out, 1);
        ic.endBatchEdit();
        lastCorrection = null;
        clearSuggestions();
    }

    // ------------------------------------------------------------------ bandeja

    @Override
    public void onTrayPick(int mode, KeyboardTray.Item item) {
        if (mode == KeyboardTray.MODE_CLIPBOARD) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && item.tag instanceof String) ic.commitText((String) item.tag, 1);
            hideTray();
        } else {
            hideTray();
            launchTextAction(item.tag instanceof ResolveInfo ? (ResolveInfo) item.tag : null);
        }
    }

    @Override
    public void onTrayRemove(KeyboardTray.Item item) {
        if (item.tag instanceof String) ClipHistory.remove(this, (String) item.tag);
    }

    @Override
    public void onTrayClear() {
        ClipHistory.clear(this);
        tray.show(KeyboardTray.MODE_CLIPBOARD, getString(R.string.clipboard_title), new ArrayList<KeyboardTray.Item>(),
                getString(R.string.clipboard_empty), null);
    }

    @Override
    public void onTrayClose() {
        hideTray();
    }
}
