package com.aurebesh.teclado;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
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
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
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
 * Autocorrige con los diccionarios integrados (y aprende palabras), ofrece un panel de emojis con
 * busqueda y el historial del portapapeles (texto e imagenes). Sin internet.
 */
public class AurebeshIME extends InputMethodService
        implements AurebeshKeyboardView.Listener, KeyboardTray.Host, EmojiTray.Host {

    private static final String SEPARATORS = " .,;:!?\n";
    private static final long DOUBLE_SPACE_MS = 500;

    private FrameLayout root;
    private AurebeshKeyboardView view;
    private KeyboardTray tray;          // historial del portapapeles
    private EmojiTray emojis;           // panel de emojis
    private boolean privateField = false;
    private boolean stripOn = false;
    private boolean autocorrect = true;
    private boolean learn = true;
    private boolean doubleSpace = true;
    private boolean suggestionsAllowed = false;   // el campo admite sugerencias
    private String lang = "es";

    // Autocorreccion (las sugerencias se calculan en segundo plano)
    private Dictionary dict;                      // del idioma actual; null hasta que cargue
    private String lastWord;                      // palabra que se esta escribiendo
    private String computedWord;                  // palabra cuyo resultado ya llego
    private String pendingCorrection;             // se aplica con el siguiente separador
    private String[] lastCorrection;              // {original, corregida, separador} para deshacer
    private int suggestSeq = 0;                   // descarta resultados atrasados
    private long lastSpaceAt = 0;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Portapapeles
    private long lastClipStamp = -1;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override public void onPrimaryClipChanged() { captureClip(); }
            };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (ClipHistory.KEY.equals(key) || Prefs.EMOJI_RECENT.equals(key)) return;
                    if (Prefs.LEARNED.equals(key)) {
                        if (dict != null) dict.setPersonal(Prefs.learnedWords(AurebeshIME.this));
                        return;
                    }
                    if (Prefs.CUSTOM_SOUND.equals(key)) {
                        KeySound.get(AurebeshIME.this).reload(AurebeshIME.this);
                        return;
                    }
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
        KeySound.get(this).reload(this);
    }

    @Override
    public void onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        worker.shutdownNow();
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
        emojis = new EmojiTray(this);
        emojis.setHost(this);
        emojis.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(view, lp);
        root.addView(tray, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(emojis, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyPrefs();
        return root;
    }

    private void applyPrefs() {
        stripOn = Prefs.strip(this);
        autocorrect = Prefs.autocorrect(this);
        learn = Prefs.learn(this);
        doubleSpace = Prefs.doubleSpace(this);
        Themes.Theme theme = Themes.byId(Prefs.theme(this));
        if (view != null) {
            view.applyPrefs(Prefs.hints(this), Prefs.vibrate(this), stripOn, Prefs.digraphs(this),
                    theme.id, Prefs.numbers(this), Prefs.sound(this), Prefs.heightScale(this));
            view.setToolbar(Prefs.toolbar(this));
            view.setSuggestionBar(autocorrect);
            view.setHandMode(Prefs.handMode(this));
            if (!autocorrect) clearSuggestions();
        }
        if (tray != null) tray.applyTheme(theme);
        if (emojis != null) {
            emojis.setFeedback(Prefs.vibrate(this), Prefs.sound(this));
            emojis.setLanguage(lang);
            emojis.applyTheme(theme);
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
        lastSpaceAt = 0;
        clearSuggestions();
        applyLanguage();
        captureClip();
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
        if (emojis != null) emojis.setLanguage(lang);
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
        if (d != null) {
            dict = d;
            dict.setPersonal(Prefs.learnedWords(this));
            return;
        }
        dict = null;
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Dictionary loaded = Dictionary.get(AurebeshIME.this, l);
                    loaded.setPersonal(Prefs.learnedWords(AurebeshIME.this));
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

    /** Pide las sugerencias de la palabra actual al hilo de trabajo; el resultado llega por showResult. */
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
        if (word.equals(lastWord)) return;              // ya pedidas
        lastWord = word;
        computedWord = null;
        pendingCorrection = null;
        if (looksLikeAcronym(word)) {
            view.setSuggestions(null, null);
            return;
        }
        final String w = word;
        final Dictionary d = dict;
        final int seq = ++suggestSeq;
        worker.execute(new Runnable() {
            @Override public void run() {
                final Dictionary.Result r = d.suggest(w);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (seq != suggestSeq || !w.equals(lastWord) || view == null) return;
                        showResult(w, r);
                    }
                });
            }
        });
    }

    private void showResult(String word, Dictionary.Result r) {
        computedWord = word;
        pendingCorrection = r.correction;
        List<String> chips = new ArrayList<>();
        if (!r.known && !r.suggestions.isEmpty()) chips.add("\"" + word + "\"");   // conservar lo escrito
        for (String s : r.suggestions) {
            if (chips.size() >= 3) break;
            chips.add(s);
        }
        view.setSuggestions(chips, pendingCorrection);
    }

    private void clearSuggestions() {
        lastWord = null;
        computedWord = null;
        pendingCorrection = null;
        suggestSeq++;
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
        if (lastWord == null || dict == null) return false;
        String cur = currentWord(ic);
        if (cur == null || !cur.equals(lastWord)) return false;
        if (!lastWord.equals(computedWord)) {           // el resultado aun no llego: calcular ahora
            if (looksLikeAcronym(lastWord)) return false;
            Dictionary.Result r = dict.suggest(lastWord);
            computedWord = lastWord;
            pendingCorrection = r.correction;
        }
        if (pendingCorrection == null) return false;
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

    /** Guarda una palabra que el usuario quiso conservar (si la opcion esta activa). */
    private void learnWord(String w) {
        if (!learn || w == null) return;
        String lw = w.toLowerCase(Locale.ROOT);
        if (lw.length() < 2 || lw.length() > 30) return;
        for (int i = 0; i < lw.length(); i++) {
            char c = lw.charAt(i);
            if (!Character.isLetter(c) && c != '\'') return;
        }
        if (dict != null && dict.contains(lw)) return;
        Prefs.addLearnedWord(this, lw);
        if (dict != null) dict.setPersonal(Prefs.learnedWords(this));
    }

    private static boolean endsSentence(char c) {
        return Character.isLetterOrDigit(c) || ")]}\"'»”’".indexOf(c) >= 0;
    }

    // ------------------------------------------------------------------ teclas

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean separator = text.length() == 1 && SEPARATORS.indexOf(text.charAt(0)) >= 0;
        long now = SystemClock.uptimeMillis();
        if (" ".equals(text)) {
            if (doubleSpace && now - lastSpaceAt < DOUBLE_SPACE_MS) {      // doble espacio = punto y espacio
                CharSequence before = ic.getTextBeforeCursor(2, 0);
                if (before != null && before.length() == 2 && before.charAt(1) == ' ' && endsSentence(before.charAt(0))) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(". ", 1);
                    ic.endBatchEdit();
                    lastSpaceAt = 0;
                    lastCorrection = null;
                    clearSuggestions();
                    return;
                }
            }
            lastSpaceAt = now;
        } else {
            lastSpaceAt = 0;
        }
        if (separator && applyCorrection(ic, text)) return;
        if (!separator) lastCorrection = null;
        ic.commitText(text, 1);
    }

    @Override
    public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastSpaceAt = 0;
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
            return;
        }
        // Retroceso justo despues de una autocorreccion: recupera la palabra original (y la aprende)
        if (lastCorrection != null) {
            String corrected = lastCorrection[1] + lastCorrection[2];
            CharSequence before = ic.getTextBeforeCursor(corrected.length(), 0);
            if (before != null && before.toString().equals(corrected)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(corrected.length(), 0);
                ic.commitText(lastCorrection[0] + lastCorrection[2], 1);
                ic.endBatchEdit();
                learnWord(lastCorrection[0]);
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
        lastSpaceAt = 0;
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
            ic.commitText(" ", 1);                      // conservar lo escrito, y aprenderlo
            learnWord(cur);
        } else {
            if (cur != null && !cur.isEmpty()) {
                ic.deleteSurroundingText(cur.length(), 0);
                word = matchCase(cur, word);
            }
            ic.commitText(word + " ", 1);
        }
        ic.endBatchEdit();
        lastCorrection = null;
        lastSpaceAt = 0;
        clearSuggestions();
    }

    // ------------------------------------------------------------------ barra de herramientas

    @Override
    public void onToolbar(int tool) {
        switch (tool) {
            case AurebeshKeyboardView.TOOL_EMOJI: openEmojis(); break;
            case AurebeshKeyboardView.TOOL_CLIPBOARD: openClipboard(); break;
            case AurebeshKeyboardView.TOOL_SETTINGS:
                try {
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // sin permiso para abrir la pantalla desde aqui
                }
                break;
        }
    }

    /** Muestra una bandeja ocupando exactamente el alto del teclado. */
    private void showTray(View which) {
        if (which == null || view == null) return;
        int h = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        if (h <= 0) h = Math.round(260 * getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = which.getLayoutParams();
        lp.height = h;
        which.setLayoutParams(lp);
        if (tray != null) tray.setVisibility(tray == which ? View.VISIBLE : View.GONE);
        if (emojis != null) emojis.setVisibility(emojis == which ? View.VISIBLE : View.GONE);
        view.setVisibility(View.INVISIBLE);
    }

    private void hideTray() {
        if (view == null) return;
        if (tray != null) tray.setVisibility(View.GONE);
        if (emojis != null) emojis.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ emojis

    private void openEmojis() {
        emojis.show(Prefs.recentEmoji(this));
        showTray(emojis);
    }

    @Override
    public void onEmoji(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        applyCorrection(ic, "");
        ic.commitText(emoji, 1);
        lastCorrection = null;
        lastSpaceAt = 0;
        Prefs.addRecentEmoji(this, emoji);
    }

    @Override
    public void onEmojiDelete() { onDelete(); }

    @Override
    public void onEmojiSpace() { onText(" "); }

    @Override
    public void onEmojiClose() { hideTray(); }

    // ------------------------------------------------------------------ portapapeles

    /** Guarda en el historial lo ultimo copiado, texto o imagen (solo si cambio desde la ultima vez). */
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
            lastClipStamp = stamp;
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();
            if (uri != null && item.getText() == null) {
                String type = null;
                try { type = getContentResolver().getType(uri); } catch (RuntimeException ignored) {}
                if (type == null && d != null && d.getMimeTypeCount() > 0) type = d.getMimeType(0);
                if (type != null && type.startsWith("image/")) {
                    ClipHistory.addImage(this, uri, type);
                    return;
                }
            }
            CharSequence t = item.coerceToText(this);
            if (!TextUtils.isEmpty(t)) ClipHistory.addText(this, t);
        } catch (RuntimeException ignored) {
            // sin acceso al portapapeles en este momento
        }
    }

    private void openClipboard() {
        captureClip();
        tray.show(getString(R.string.clipboard_title), ClipHistory.items(this), getString(R.string.clipboard_empty));
        showTray(tray);
    }

    /** Pega una imagen del historial en el campo (si la app lo admite) mediante commitContent. */
    private void commitImage(ClipHistory.Entry entry) {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null || entry.mime == null) return;
        boolean supported = false;
        String[] mimes = info.contentMimeTypes;
        if (mimes != null) {
            for (String m : mimes) if (ClipDescription.compareMimeTypes(entry.mime, m)) supported = true;
        }
        if (!supported) {
            Toast.makeText(this, R.string.clip_image_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = ClipProvider.uriFor(entry.file());
            InputContentInfo content = new InputContentInfo(uri,
                    new ClipDescription(getString(R.string.clip_image), new String[]{entry.mime}), null);
            if (!ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) {
                Toast.makeText(this, R.string.clip_image_unsupported, Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.clip_image_unsupported, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ bandeja

    @Override
    public void onTrayPick(ClipHistory.Entry entry, String text) {
        if (entry.isImage()) {
            commitImage(entry);
            hideTray();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && text != null) {
            applyCorrection(ic, "");
            ic.commitText(text, 1);
            lastCorrection = null;
            lastSpaceAt = 0;
        }
        hideTray();
    }

    @Override
    public void onTrayRemove(ClipHistory.Entry entry) {
        ClipHistory.remove(this, entry);
    }

    @Override
    public void onTrayClear() {
        ClipHistory.clear(this);
        tray.show(getString(R.string.clipboard_title), new ArrayList<ClipHistory.Entry>(), getString(R.string.clipboard_empty));
    }

    @Override
    public void onTrayClose() {
        hideTray();
    }
}
