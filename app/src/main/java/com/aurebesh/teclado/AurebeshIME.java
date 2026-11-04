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
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
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
        implements AurebeshKeyboardView.Listener, KeyboardTray.Host, EmojiTray.Host, AiTray.Host {

    private static final String SEPARATORS = " .,;:!?\n";
    private static final long DOUBLE_SPACE_MS = 500;

    private FrameLayout root;
    private Context ui;                 // contexto con el idioma de la app elegido (menus del teclado)
    private AurebeshKeyboardView view;
    private KeyboardTray tray;          // historial del portapapeles
    private EmojiTray emojis;           // panel de emojis
    private AiTray ai;                  // panel de inteligencia (modelo local o servidor propio)
    private ScreenshotWatcher screenshots;   // capturas recientes (opcional, con permiso de fotos)
    private Bigrams bigrams;            // siguiente palabra y contexto del idioma actual
    private boolean privateField = false;
    private boolean stripOn = false;
    private boolean autocorrect = true;
    private boolean learn = true;
    private boolean doubleSpace = true;
    private boolean suggestionsAllowed = false;   // el campo admite sugerencias
    private boolean incognito = false;            // el campo pide no aprender nada (modo incognito)
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

    // Escritura deslizando: la ultima palabra escrita con un trazo, para cambiarla por un chip o borrarla entera
    private static final String GLIDE_PUNCTUATION = ".,;:!?";
    private String glideWord;                     // tal cual se escribio (con mayusculas), o null
    private List<String> glideAlts;               // alternativas que se muestran en la barra
    private String glideAnchor;                   // texto previo al cursor al poner los chips: si cambia, se quitan
    private boolean glideAutoSpace;               // el espacio tras la palabra es automatico (la puntuacion lo absorbe)
    private final Handler main = new Handler(Looper.getMainLooper());

    // Portapapeles y chip "Pegar" de lo recien copiado
    private static final long PASTE_FRESH_MS = 2 * 60 * 1000L;
    private long lastClipStamp = -1;
    private ClipHistory.Entry fresh;             // lo ultimo copiado, candidato al chip "Pegar"
    private long freshAt = 0;                    // cuando se copio (reloj de pared)
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override public void onPrimaryClipChanged() { captureClip(); }
            };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (ClipHistory.KEY.equals(key) || Prefs.EMOJI_RECENT.equals(key) || Bigrams.PREF_KEY.equals(key)
                            || ScreenshotWatcher.PREF_LAST_ID.equals(key) || AiModels.LAST_TONE.equals(key)) return;
                    if (Prefs.APP_LANG.equals(key)) {                 // idioma de la app: rehacer la vista
                        ui = AppLocale.wrap(AurebeshIME.this);
                        if (view != null) setInputView(onCreateInputView());
                        return;
                    }
                    if (Prefs.KEYBOARD_LANG.equals(key)) {            // idioma del teclado elegido en la app
                        subtypeSwitchTried = null;
                        applyLanguage();
                        refresh();
                        return;
                    }
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
        ui = AppLocale.wrap(this);
        screenshots = new ScreenshotWatcher(this, new ScreenshotWatcher.Listener() {
            @Override public void onScreenshot(ClipHistory.Entry e) { onScreenshotFound(e); }
        });
        worker.execute(new Runnable() {                       // calienta el indice de emojis fuera del hilo principal
            @Override public void run() { EmojiSuggest.lookup(AurebeshIME.this, "ok", 1); }
        });
    }

    @Override
    public void onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        if (screenshots != null) screenshots.stop();
        if (ai != null) ai.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (screenshots != null) screenshots.start();     // no hace nada sin la opcion o sin permiso
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        if (screenshots != null) screenshots.stop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (ai != null && (level >= TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN)) ai.unloadModel();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (ai != null) ai.unloadModel();
    }

    @Override
    public View onCreateInputView() {
        if (ai != null) ai.release();                        // la vista se rehace (giro, modo noche): no filtrar el motor
        if (ui == null) ui = AppLocale.wrap(this);
        root = new FrameLayout(ui);
        view = new AurebeshKeyboardView(ui);
        view.setListener(this);
        tray = new KeyboardTray(ui);
        tray.setHost(this);
        tray.setVisibility(View.GONE);
        emojis = new EmojiTray(ui);
        emojis.setHost(this);
        emojis.setVisibility(View.GONE);
        ai = new AiTray(ui);
        ai.setHost(this);
        ai.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(view, lp);
        root.addView(tray, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(emojis, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(ai, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            view.setSplitMode(Prefs.splitMode(this));
            if (!autocorrect) clearSuggestions();
            updateGlide();
        }
        if (tray != null) tray.applyTheme(theme);
        if (emojis != null) {
            emojis.setFeedback(Prefs.vibrate(this), Prefs.sound(this));
            emojis.setLanguage(lang);
            emojis.applyTheme(theme);
        }
        if (ai != null) {
            ai.setFeedback(Prefs.vibrate(this), Prefs.sound(this));
            ai.setLanguage(lang);
            ai.applyTheme(theme);
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
        incognito = (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        view.setPreviewEnabled(!privateField);   // sin burbuja en contrasenas
        updateGlide();
        if (ai != null) ai.setFieldAllowed(suggestionsAllowed && !privateField);
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
        showPasteChip();
        if (Prefs.screenshots(this) && !privateField) {          // captura reciente -> chip "Pegar"
            worker.execute(new Runnable() {
                @Override public void run() {
                    final ClipHistory.Entry e = screenshots.checkRecent(PASTE_FRESH_MS);
                    if (e != null) main.post(new Runnable() { @Override public void run() { onScreenshotFound(e); } });
                }
            });
        }
        refresh();
    }

    private void onScreenshotFound(ClipHistory.Entry e) {
        if (e == null || privateField || view == null) return;
        if (fresh != null && fresh.isImage() && Math.abs(freshAt - System.currentTimeMillis()) < 10000) return;
        fresh = e;
        freshAt = System.currentTimeMillis();
        showPasteChip();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        clearSuggestions();
        if (ai != null) ai.cancel();
        hideTray();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype);
        // El globo (o el sistema) cambio de subtipo: si contradice el idioma fijado desde la app, manda el subtipo
        String fixed = Prefs.keyboardLang(this);
        if (fixed != null && newSubtype != null && !fixed.equals(langOf(newSubtype.getLocale()))) {
            Prefs.clearKeyboardLang(this);
        }
        applyLanguage();
        refresh();
    }

    /** "es", "en" o "de" a partir de la locale de un subtipo. */
    private static String langOf(String locale) {
        return locale != null && locale.startsWith("en") ? "en" : locale != null && locale.startsWith("de") ? "de" : "es";
    }

    /** Idioma para el que ya se intento cambiar el subtipo (para no insistir si el sistema no lo permite). */
    private String subtypeSwitchTried;

    /**
     * Distribucion segun el idioma fijado desde la app (iconos de arriba) o, si no hay, el subtipo activo;
     * el globo solo cuando hay varios idiomas Aurebesh activos.
     */
    @SuppressWarnings("deprecation")
    private void applyLanguage() {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        InputMethodSubtype subtype = imm != null ? imm.getCurrentInputMethodSubtype() : null;
        String locale = subtype != null ? subtype.getLocale() : Locale.getDefault().getLanguage();
        String fromSubtype = langOf(locale);
        String fixed = Prefs.keyboardLang(this);
        if (fixed != null && !fixed.equals(fromSubtype) && !fixed.equals(subtypeSwitchTried)) {
            subtypeSwitchTried = fixed;
            switchSubtypeTo(imm, fixed);          // que el sistema muestre el mismo idioma (si lo permite)
        }
        lang = fixed != null ? fixed : fromSubtype;
        view.setLanguage(lang);
        if (emojis != null) emojis.setLanguage(lang);
        if (ai != null) ai.setLanguage(lang);
        boolean several = false;
        if (imm != null) {
            List<InputMethodSubtype> enabled = imm.getEnabledInputMethodSubtypeList(null, true);
            several = enabled != null && enabled.size() > 1;
        }
        view.setLanguageSwitch(several);
        view.setSpaceLabel(several ? getString("en".equals(lang) ? R.string.lang_en : "de".equals(lang) ? R.string.lang_de : R.string.lang_es) : null);
        loadDictionary();
        loadBigrams();
    }

    /** Cambia el subtipo de este teclado al del idioma dado (API 28+); si no se puede, no pasa nada. */
    private void switchSubtypeTo(InputMethodManager imm, String lang) {
        if (imm == null || Build.VERSION.SDK_INT < 28) return;
        try {
            for (android.view.inputmethod.InputMethodInfo info : imm.getInputMethodList()) {
                if (!getPackageName().equals(info.getPackageName())) continue;
                for (int i = 0; i < info.getSubtypeCount(); i++) {
                    InputMethodSubtype st = info.getSubtypeAt(i);
                    if (lang.equals(langOf(st.getLocale()))) {
                        switchInputMethod(info.getId(), st);
                        return;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // el sistema no lo permitio: el teclado usa igualmente el idioma fijado
        }
    }

    /** Tabla de bigramas del idioma (siguiente palabra y contexto), cargada en segundo plano. */
    private void loadBigrams() {
        final String l = lang;
        Bigrams b0 = Bigrams.cached(l);
        if (b0 != null) { bigrams = b0; return; }
        bigrams = null;
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Bigrams b = Bigrams.get(AurebeshIME.this, l);
                    main.post(new Runnable() {
                        @Override public void run() { if (l.equals(lang)) bigrams = b; }
                    });
                } catch (RuntimeException ignored) {
                    // sin bigramas no hay prediccion; el resto sigue igual
                }
            }
        });
    }

    /** Palabra completa anterior a la que se escribe (para contexto y prediccion); "<s>" al inicio de frase. */
    private String previousWord(InputConnection ic, String current) {
        CharSequence before = ic.getTextBeforeCursor(80, 0);
        if (before == null) return null;
        String s = before.toString();
        if (current != null) s = s.substring(0, Math.max(0, s.length() - current.length()));
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        if (end == 0) return "<s>";
        char c = s.charAt(end - 1);
        if (".!?\n".indexOf(c) >= 0) return "<s>";
        int i = end;
        while (i > 0 && (Character.isLetter(s.charAt(i - 1)) || s.charAt(i - 1) == '\'')) i--;
        return i == end ? null : s.substring(i, end).toLowerCase(Locale.ROOT);
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
        if (glideAnchor != null) {                       // chips del deslizado: siguen mientras el texto no cambie
            CharSequence before = ic.getTextBeforeCursor(60, 0);
            if (before != null && before.toString().equals(glideAnchor)) return;
            clearGlide();
        }
        if (!autocorrect || !suggestionsAllowed || dict == null) {
            clearSuggestions();
            return;
        }
        String word = currentWord(ic);
        if (word == null || word.isEmpty()) {
            clearSuggestions();
            if (word != null && bigrams != null && fresh == null) {          // siguiente palabra prevista
                String prev = previousWord(ic, "");
                if (prev != null) {
                    List<String> next = bigrams.next(prev, 3);
                    if (!next.isEmpty()) view.setSuggestions(next, null);
                }
            }
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
        if (bigrams != null && !r.known && r.correction != null && r.suggestions.size() > 1) {   // contexto: candidato que mejor sigue a la anterior
            InputConnection ic = getCurrentInputConnection();
            String prev = ic != null ? previousWord(ic, word) : null;
            if (prev != null) {
                String best = r.correction;
                double bestBonus = best != null ? bigrams.bonus(prev, best) : 0;
                for (String s : r.suggestions) {
                    double b = bigrams.bonus(prev, s);
                    if (b > bestBonus + 1.0) { best = s; bestBonus = b; }
                }
                if (best != null && !best.equals(pendingCorrection)) {
                    pendingCorrection = best;
                    r.suggestions.remove(best);
                    r.suggestions.add(0, best);
                }
            }
        }
        List<String> chips = new ArrayList<>();
        if (!r.known && !r.suggestions.isEmpty()) chips.add("\"" + word + "\"");   // conservar lo escrito
        List<String> emo = EmojiSuggest.lookup(this, r.known ? word : (r.correction != null ? r.correction : word), 2);
        int maxWords = emo.isEmpty() ? 3 : 2;                 // con emojis, dejarles sitio en la barra
        for (String s : r.suggestions) {
            if (chips.size() >= maxWords) break;
            chips.add(s);
        }
        chips.addAll(emo);
        view.setSuggestions(chips, pendingCorrection);
    }

    private void clearSuggestions() {
        lastWord = null;
        computedWord = null;
        pendingCorrection = null;
        suggestSeq++;
        clearGlide();
        if (view != null) view.setSuggestions(null, null);
    }

    // ------------------------------------------------------------------ escritura deslizando

    /** El deslizado solo con la opcion activa y en campos normales de texto (no contrasenas ni campos sin sugerencias). */
    private void updateGlide() {
        if (view != null) view.setGlideEnabled(Prefs.glide(this) && suggestionsAllowed && !privateField
                && (Prefs.toolbar(this) || autocorrect || stripOn));
    }

    private void clearGlide() {
        glideWord = null;
        glideAlts = null;
        glideAnchor = null;
        glideAutoSpace = false;
    }

    /** Pone chips propios del deslizado y recuerda el texto previo al cursor para quitarlos cuando cambie. */
    private void showGlideChips(InputConnection ic, List<String> chips, String highlight) {
        lastWord = null;
        computedWord = null;
        pendingCorrection = null;
        suggestSeq++;
        CharSequence before = ic.getTextBeforeCursor(60, 0);
        glideAnchor = before == null ? "" : before.toString();
        view.setSuggestions(chips.isEmpty() ? null : chips, highlight);
    }

    /**
     * Palabra reconocida al deslizar: cierra la palabra que se estuviera escribiendo a toques (con su
     * correccion), separa con espacio si hace falta y escribe la palabra mas un espacio. Las alternativas van
     * a la barra; un chip la sustituye y retroceso justo despues la borra entera.
     */
    @Override
    public void onGlide(String best, List<String> alternatives) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || view == null || best == null || best.isEmpty()) return;
        ic.beginBatchEdit();
        String cur = currentWord(ic);
        if (cur != null && !cur.isEmpty()) {
            if (!applyCorrection(ic, " ")) ic.commitText(" ", 1);
        } else {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before != null && before.length() == 1) {
                char c = before.charAt(0);
                if (c != ' ' && c != '\n' && "([{\u00bf\u00a1\u00ab\u201c".indexOf(c) < 0) ic.commitText(" ", 1);
                if (".!?".indexOf(c) >= 0 && Character.isLowerCase(best.charAt(0))) {
                    best = best.substring(0, 1).toUpperCase(Locale.ROOT) + best.substring(1);
                }
            }
        }
        ic.commitText(best + " ", 1);
        ic.endBatchEdit();
        lastCorrection = null;
        lastSpaceAt = 0;
        if (fresh != null) clearPasteChip();
        List<String> alts = new ArrayList<>();
        if (alternatives != null) for (String a : alternatives) if (!a.equals(best) && !alts.contains(a)) alts.add(a);
        glideWord = best;
        glideAlts = alts;
        glideAutoSpace = true;
        showGlideChips(ic, alts, null);
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
        if (!learn || incognito || !suggestionsAllowed || privateField || w == null) return;
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
            if (glideWord != null && glideAutoSpace) {                      // el espacio automatico del deslizado ya esta
                CharSequence b = ic.getTextBeforeCursor(glideWord.length() + 1, 0);
                if (b != null && b.toString().equals(glideWord + " ")) {
                    clearGlide();
                    lastSpaceAt = now;
                    return;
                }
            }
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
        if (!separator && fresh != null) clearPasteChip();
        if (separator) rememberPair(ic);
        if (glideWord != null) {                                          // la puntuacion absorbe el espacio del deslizado
            if (glideAutoSpace && text.length() == 1 && GLIDE_PUNCTUATION.indexOf(text.charAt(0)) >= 0) {
                CharSequence before = ic.getTextBeforeCursor(glideWord.length() + 1, 0);
                if (before != null && before.toString().equals(glideWord + " ")) ic.deleteSurroundingText(1, 0);
            }
            clearGlide();
        }
        if (separator && applyCorrection(ic, text)) return;
        if (!separator) lastCorrection = null;
        ic.commitText(text, 1);
    }

    /** Aprende (solo en el telefono) el par palabra anterior + palabra actual al cerrarla con un separador. */
    private void rememberPair(InputConnection ic) {
        if (!learn || incognito || bigrams == null || !suggestionsAllowed || privateField) return;
        String cur = currentWord(ic);
        if (cur == null || cur.length() < 2 || looksLikeAcronym(cur)) return;
        String prev = previousWord(ic, cur);
        if (prev == null || "<s>".equals(prev)) return;
        if (dict != null && lastWord != null && lastWord.equals(cur) && !lastWord.equals(computedWord)) {
            Dictionary.Result r = dict.suggest(lastWord);     // el resultado aun no llego: resolverlo como applyCorrection
            computedWord = lastWord;
            pendingCorrection = r.correction;
        }
        String w = pendingCorrection != null && lastWord != null && lastWord.equals(cur) && lastWord.equals(computedWord)
                ? pendingCorrection.toLowerCase(Locale.ROOT) : cur.toLowerCase(Locale.ROOT);
        bigrams.learn(this, prev, w);
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
        // Retroceso justo despues de deslizar: borra la palabra entera y ofrece elegir otra en la barra
        if (glideWord != null) {
            String w = glideWord;
            List<String> alts = glideAlts;
            CharSequence before = ic.getTextBeforeCursor(w.length() + 1, 0);
            String s = before == null ? "" : before.toString();
            int n = s.equals(w + " ") ? w.length() + 1 : s.endsWith(w) ? w.length() : 0;
            clearGlide();
            if (n > 0) {
                ic.deleteSurroundingText(n, 0);
                List<String> chips = new ArrayList<>();
                chips.add(w);
                if (alts != null) for (String a : alts) if (chips.size() < 3 && !chips.contains(a)) chips.add(a);
                showGlideChips(ic, chips, null);
                return;
            }
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
        if (glideWord != null && glideAutoSpace && !AurebeshKeyboardView.isEmoji(word) && !word.startsWith("\"")) {
            CharSequence before = ic.getTextBeforeCursor(glideWord.length() + 1, 0);
            if (before != null && before.toString().equals(glideWord + " ")) {   // chip tras deslizar: cambia la palabra
                ic.beginBatchEdit();
                ic.deleteSurroundingText(glideWord.length() + 1, 0);
                ic.commitText(word + " ", 1);
                ic.endBatchEdit();
                List<String> alts = glideAlts == null ? new ArrayList<String>() : glideAlts;
                int i = alts.indexOf(word);
                if (i >= 0) alts.set(i, glideWord); else alts.add(glideWord);   // la anterior pasa a ser alternativa
                glideWord = word;
                lastCorrection = null;
                lastSpaceAt = 0;
                showGlideChips(ic, alts, null);
                glideAlts = alts;
                return;
            }
        }
        String cur = currentWord(ic);
        ic.beginBatchEdit();
        if (word.length() >= 2 && word.startsWith("\"") && word.endsWith("\"")) {
            ic.commitText(" ", 1);                      // conservar lo escrito, y aprenderlo
            learnWord(cur);
        } else if (AurebeshKeyboardView.isEmoji(word)) {   // emoji sugerido: sustituye la palabra
            if (cur != null && !cur.isEmpty()) ic.deleteSurroundingText(cur.length(), 0);
            ic.commitText(word, 1);
            Prefs.addRecentEmoji(this, word);
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
            case AurebeshKeyboardView.TOOL_HAND: Prefs.toggleHand(this); break;      // el oyente de preferencias lo aplica
            case AurebeshKeyboardView.TOOL_AI: openAi(); break;
            case AurebeshKeyboardView.TOOL_SETTINGS:
                try {
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // sin permiso para abrir la pantalla desde aqui
                }
                break;
        }
    }

    @Override
    public void onHandSide(int mode) {
        Prefs.setHand(this, mode == 1 ? "left" : "right");
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
        if (ai != null) ai.setVisibility(ai == which ? View.VISIBLE : View.GONE);
        view.setVisibility(View.INVISIBLE);
    }

    private void hideTray() {
        if (view == null) return;
        if (tray != null) tray.setVisibility(View.GONE);
        if (emojis != null) emojis.setVisibility(View.GONE);
        if (ai != null) ai.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ inteligencia

    private void openAi() {
        if (ai == null || privateField) return;
        ai.show();
        showTray(ai);
    }

    /** Texto completo del campo (o lo que haya antes y despues del cursor si la app no lo da entero). */
    private CharSequence fieldText(InputConnection ic) {
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et != null && et.text != null) return et.text;
        CharSequence b = ic.getTextBeforeCursor(8000, 0);
        CharSequence a = ic.getTextAfterCursor(8000, 0);
        return (b == null ? "" : b.toString()) + (a == null ? "" : a.toString());
    }

    @Override
    public CharSequence currentText() {
        InputConnection ic = getCurrentInputConnection();
        return ic == null ? "" : fieldText(ic);
    }

    @Override
    public CharSequence selectedText() {
        InputConnection ic = getCurrentInputConnection();
        return ic == null ? null : ic.getSelectedText(0);
    }

    @Override
    public void replaceText(CharSequence t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || t == null) return;
        ic.beginBatchEdit();
        CharSequence sel = ic.getSelectedText(0);
        if (TextUtils.isEmpty(sel)) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null && et.text.length() > 0) {
                ic.setSelection(0, et.text.length());
            } else {
                CharSequence b = ic.getTextBeforeCursor(10000, 0);
                CharSequence a = ic.getTextAfterCursor(10000, 0);
                ic.deleteSurroundingText(b == null ? 0 : b.length(), a == null ? 0 : a.length());
            }
        }
        ic.commitText(t, 1);
        ic.endBatchEdit();
        lastCorrection = null;
        lastSpaceAt = 0;
        clearSuggestions();
    }

    @Override
    public void insertText(CharSequence t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || t == null) return;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        String prefix = before != null && before.length() == 1 && !Character.isWhitespace(before.charAt(0)) ? " " : "";
        ic.commitText(prefix + t, 1);
        lastCorrection = null;
        lastSpaceAt = 0;
        clearSuggestions();
    }

    @Override
    public void copyText(CharSequence t) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && t != null) cm.setPrimaryClip(ClipData.newPlainText("aurebesh", t));
    }

    @Override
    public void closeTray() { hideTray(); }

    @Override
    public void openSettings() {
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "ai"));
        } catch (RuntimeException ignored) {
            // sin permiso para abrir la pantalla desde aqui
        }
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
            if (d != null && d.getExtras() != null
                    && d.getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false)) return;   // contrasenas copiadas
            long stamp = d != null ? d.getTimestamp() : -1;
            if (stamp > 0 && stamp == lastClipStamp) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            lastClipStamp = stamp;
            ClipData.Item item = clip.getItemAt(0);
            final Uri uri = item.getUri();
            ClipHistory.Entry added = null;
            if (uri != null && item.getText() == null) {
                String type = null;
                try { type = getContentResolver().getType(uri); } catch (RuntimeException ignored) {}
                if (type == null && d != null && d.getMimeTypeCount() > 0) type = d.getMimeType(0);
                if (type != null && type.startsWith("image/")) {
                    final String mime = type;
                    final long at = stamp > 0 ? stamp : System.currentTimeMillis();
                    if (worker.isShutdown()) return;
                    worker.execute(new Runnable() {                      // copiar la imagen fuera del hilo principal
                        @Override public void run() {
                            final ClipHistory.Entry e = ClipHistory.addImage(AurebeshIME.this, uri, mime);
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (e != null) { fresh = e; freshAt = at; showPasteChip(); }
                                }
                            });
                        }
                    });
                    return;
                }
            }
            if (added == null) {
                CharSequence t = item.coerceToText(this);
                if (!TextUtils.isEmpty(t)) {
                    ClipHistory.addText(this, t);
                    added = new ClipHistory.Entry(ClipHistory.Entry.TEXT, t.toString(), null, null);
                }
            }
            if (added != null) {
                fresh = added;
                freshAt = stamp > 0 ? stamp : System.currentTimeMillis();
                showPasteChip();
            }
        } catch (RuntimeException ignored) {
            // sin acceso al portapapeles en este momento
        }
    }

    /** Chip "Pegar" mientras lo copiado sea reciente y no se haya empezado a escribir una palabra. */
    private void showPasteChip() {
        if (view == null) return;
        if (fresh == null || privateField || System.currentTimeMillis() - freshAt > PASTE_FRESH_MS) {
            fresh = null;
            view.setPasteSuggestion(null, null, null);
            return;
        }
        if (fresh.isImage()) view.setPasteSuggestion(ClipHistory.thumbnail(fresh, 160), null, ui.getString(R.string.paste));
        else view.setPasteSuggestion(null, fresh.text, ui.getString(R.string.paste));
    }

    private void clearPasteChip() {
        fresh = null;
        if (view != null) view.setPasteSuggestion(null, null, null);
    }

    @Override
    public void onPasteSuggestion() {
        ClipHistory.Entry e = fresh;
        clearPasteChip();
        if (e == null) return;
        if (e.isImage()) { commitImage(e); return; }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || e.text == null) return;
        applyCorrection(ic, "");
        ic.commitText(e.text, 1);
        lastCorrection = null;
        lastSpaceAt = 0;
    }

    private void openClipboard() {
        captureClip();
        tray.show(ui.getString(R.string.clipboard_title), ClipHistory.items(this), ui.getString(R.string.clipboard_empty));
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
                    new ClipDescription(ui.getString(R.string.clip_image), new String[]{entry.mime}), null);
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
        tray.show(ui.getString(R.string.clipboard_title), new ArrayList<ClipHistory.Entry>(), ui.getString(R.string.clipboard_empty));
    }

    @Override
    public void onTrayClose() {
        hideTray();
    }
}
