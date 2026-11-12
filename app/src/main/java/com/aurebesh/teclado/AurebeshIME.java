package com.aurebesh.teclado;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Servicio de teclado (IME) Aurebesh. Escribe letras latinas mostradas como glifos Aurebesh.
 * Autocorrige con los diccionarios integrados (y aprende palabras), ofrece un panel de emojis con
 * busqueda y el historial del portapapeles (texto e imagenes). Sin internet.
 */
public class AurebeshIME extends InputMethodService
        implements AurebeshKeyboardView.Listener, KeyboardTray.Host, EmojiTray.Host, AiTray.Host,
        ToolsTray.Host, EditTray.Host {

    private static final String SEPARATORS = " .,;:!?\n";
    private static final long DOUBLE_SPACE_MS = 500;

    private FrameLayout root;
    private Context ui;                 // contexto con el idioma de la app elegido (menus del teclado)
    private AurebeshKeyboardView view;
    private KeyboardTray tray;          // historial del portapapeles
    private EmojiTray emojis;           // panel de emojis
    private AiTray ai;                  // panel de inteligencia (modelo local o servidor propio)
    private ToolsTray tools;            // cuadricula "..." con todas las herramientas de la cinta
    private EditTray edit;              // panel de edicion (cursor, seleccion, cortar/copiar/pegar, deshacer)
    private ScreenshotWatcher screenshots;   // capturas recientes (opcional, con permiso de fotos)
    private int lastOrientation = Configuration.ORIENTATION_UNDEFINED;   // para saber si un cambio de configuracion fue un giro
    private Bigrams bigrams;            // siguiente palabra y contexto del idioma actual
    private boolean privateField = false;
    private boolean stripOn = false;
    private boolean autocorrect = true;           // texto predictivo (chips de la barra)
    private boolean autoReplace = true;           // sustitucion automatica al teclear un separador
    private boolean autoCaps = true;              // mayuscula automatica al inicio de frase
    private boolean preview = true;               // burbuja al pulsar
    private boolean learn = true;
    private boolean doubleSpace = true;
    private boolean suggestionsAllowed = false;   // el campo admite sugerencias
    private boolean incognito = false;            // el campo pide no aprender nada (modo incognito)
    private String lang = "es";
    private boolean severalLangs = false;         // varios idiomas Aurebesh activos (globo y cambio de idioma con el espacio)

    // Autocorreccion (las sugerencias se calculan en segundo plano)
    private Dictionary dict;                      // del idioma actual; null hasta que cargue
    private String lastWord;                      // palabra que se esta escribiendo
    private String computedWord;                  // palabra cuyo resultado ya llego
    private String pendingCorrection;             // se aplica con el siguiente separador
    private String[] lastCorrection;              // {original, corregida, separador} para deshacer
    private int suggestSeq = 0;                   // descarta resultados atrasados
    private long lastSpaceAt = 0;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();      // E/S: portapapeles, capturas, cargas
    private final ExecutorService suggester = Executors.newSingleThreadExecutor();   // solo dict.suggest(): la espera de 30 ms mide su coste real
    private Future<Dictionary.Result> suggestFuture;   // peticion en vuelo de lastWord (un separador la espera como mucho 30 ms)
    private String suggestFutureWord;                  // palabra de esa peticion
    private String correctionAnchor;              // texto previo al cursor tras autocorregir: el chip "original" sigue mientras no cambie
    private final HashMap<String, Integer> revertCount = new HashMap<>();   // veces que se deshizo la misma correccion en la sesion
    private Map<String, String> shortcuts = new LinkedHashMap<>();         // atajos de texto (clave en minusculas -> expansion)
    private AiRunner runner;                      // unico motor de IA del servicio (sobrevive a rehacer la vista)
    // Seleccion conocida (EditorInfo al empezar y onUpdateSelection despues); -1 si el editor no la informa
    private int selStart = -1, selEnd = -1, candStart = -1;

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
                    if (!Prefs.affectsKeyboard(key)) return;          // historial, emojis recientes, bigramas, candidatas, pantalla de ajustes...
                    if (Prefs.APP_LANG.equals(key)) {                 // idioma de la app: rehacer la vista
                        ui = AppLocale.wrap(AurebeshIME.this);
                        if (view != null) setInputView(onCreateInputView());
                        return;
                    }
                    if (Prefs.HINT_COUNTS.equals(key)) {              // "Reiniciar progreso" del modo Aprendiendo
                        if (view != null) view.setHintMode(Prefs.hintModeInt(AurebeshIME.this));
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
                    if (Prefs.STARWARS.equals(key)) {                 // lexico Star Wars en la correccion
                        if (dict != null) dict.setStarWars(Prefs.starWars(AurebeshIME.this));
                        lastWord = null;                              // que la palabra actual se recalcule
                        refresh();
                        return;
                    }
                    if (Prefs.SHORTCUTS.equals(key)) {                // atajos de texto editados en la app
                        shortcuts = Prefs.shortcuts(AurebeshIME.this);
                        lastWord = null;                              // que el chip de la palabra actual se recalcule
                        refresh();
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
        lastOrientation = getResources().getConfiguration().orientation;
        runner = new AiRunner(this);                          // un solo motor de IA mientras viva el servicio
        screenshots = new ScreenshotWatcher(this, new ScreenshotWatcher.Listener() {
            @Override public void onScreenshot(ClipHistory.Entry e) { onScreenshotFound(e); }
        });
        worker.execute(new Runnable() {                       // calienta el indice de emojis fuera del hilo principal
            @Override public void run() { EmojiSuggest.lookup(AurebeshIME.this, "ok", 1); }
        });
    }

    @Override
    public void onDestroy() {
        Bigrams.flush(this);
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        if (screenshots != null) screenshots.stop();
        if (ai != null) ai.cancel();
        if (runner != null) runner.release();                 // el motor es del servicio: se libera con el
        worker.shutdownNow();
        suggester.shutdownNow();
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
        // El modelo se descarga solo con la memoria justa (RUNNING_LOW / RUNNING_CRITICAL) o con el teclado oculto
        // (UI_HIDDEN y los niveles de fondo que le siguen); con RUNNING_MODERATE se conserva para no recargarlo a cada rato.
        // Si esta generando, unloadModel no hace nada.
        if (runner != null && (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_UI_HIDDEN)) runner.unloadModel();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (runner != null) runner.unloadModel();
    }

    @Override
    public View onCreateInputView() {
        if (ai != null) ai.detach();                         // la vista se rehace (giro, modo noche): el motor compartido sigue cargado y la generacion en curso pasa al panel nuevo
        ui = AppLocale.wrap(this);                           // siempre nuevo: uno guardado conservaria la Configuration (orientacion, alto) de cuando se creo
        if (runner == null) runner = new AiRunner(this);
        root = new FrameLayout(ui);
        view = new AurebeshKeyboardView(ui);
        view.setListener(this);
        tray = new KeyboardTray(ui);
        tray.setHost(this);
        tray.setVisibility(View.GONE);
        emojis = new EmojiTray(ui);
        emojis.setHost(this);
        emojis.setVisibility(View.GONE);
        ai = new AiTray(ui, runner);
        ai.setHost(this);
        ai.setVisibility(View.GONE);
        tools = new ToolsTray(ui, this);
        tools.setVisibility(View.GONE);
        edit = new EditTray(ui, this);
        edit.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(view, lp);
        root.addView(tray, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(emojis, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(ai, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tools, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(edit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyPrefs();
        return root;
    }

    private void applyPrefs() {
        stripOn = Prefs.strip(this);
        autocorrect = Prefs.autocorrect(this);
        autoReplace = Prefs.autoReplace(this);
        autoCaps = Prefs.autoCaps(this);
        preview = Prefs.preview(this);
        learn = Prefs.learn(this);
        doubleSpace = Prefs.doubleSpace(this);
        shortcuts = Prefs.shortcuts(this);
        Themes.Theme theme = Themes.byId(Prefs.theme(this));
        if (view != null) {
            view.applyPrefs(Prefs.hints(this), Prefs.vibrate(this), stripOn, Prefs.digraphs(this),
                    theme.id, Prefs.numbers(this), Prefs.sound(this), Prefs.heightScale(this));
            view.setHintMode(Prefs.hintModeInt(this));         // nunca / siempre / aprendiendo (manda sobre el booleano antiguo)
            view.setSaber(Prefs.saberArgb(this));              // color de la estela al deslizar
            view.setToolbar(Prefs.toolbar(this));
            view.setToolbarItems(Prefs.toolbarItems(this));    // iconos de la cinta elegidos en "..."
            view.setSuggestionBar(autocorrect);
            view.setHandMode(Prefs.handMode(this));
            view.setSplitMode(Prefs.splitMode(this));
            view.setPreviewEnabled(preview && !privateField);
            view.setSpaceSlide(spaceSlideMode(severalLangs));
            view.setLongPressMs(Prefs.longPressMs(this));
            view.setHaptics(Prefs.vibrationLevel(this));
            view.setBottomPad(Prefs.bottomDp(this) * getResources().getDisplayMetrics().density);
            view.setSplitBox(Prefs.splitBox(this));
            if (!autocorrect) clearSuggestions();
            updateGlide();
        }
        KeySound.get(this).setVolume(Prefs.soundVolume(this));
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
        if (tools != null) {
            tools.setFeedback(Prefs.vibrate(this), Prefs.sound(this));
            tools.applyTheme(theme);
        }
        if (edit != null) {
            edit.setFeedback(Prefs.vibrate(this), Prefs.sound(this));
            edit.applyTheme(theme);
        }
        if (dict != null) dict.setStarWars(Prefs.starWars(this));
    }

    /**
     * Que hace deslizar sobre el espacio segun la preferencia; "auto" cambia de idioma solo si hay varios subtipos
     * Aurebesh activos (la misma condicion que el globo) y, si no, mueve el cursor.
     */
    private int spaceSlideMode(boolean several) {
        String mode = Prefs.spaceSlide(this);
        if ("cursor".equals(mode)) return AurebeshKeyboardView.SPACE_CURSOR;
        if ("lang".equals(mode)) return AurebeshKeyboardView.SPACE_LANG;
        if ("none".equals(mode)) return AurebeshKeyboardView.SPACE_NONE;
        return several ? AurebeshKeyboardView.SPACE_LANG : AurebeshKeyboardView.SPACE_CURSOR;
    }

    /** Hay mas de un idioma Aurebesh activo en el sistema (condicion del globo y del cambio de idioma con el espacio). */
    private static boolean severalSubtypes(InputMethodManager imm) {
        if (imm == null) return false;
        try {
            List<InputMethodSubtype> enabled = imm.getEnabledInputMethodSubtypeList(null, true);
            return enabled != null && enabled.size() > 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Editor a pantalla completa (extract UI) solo en telefonos apaisados, como hace Android por defecto y el teclado
     * de Samsung: ahi la decision queda en super, que respeta IME_FLAG_NO_FULLSCREEN / IME_FLAG_NO_EXTRACT_UI de la
     * app. En vertical, plegables desplegados y tabletas, nunca. Se mira la configuracion del servicio, no la del
     * contexto envuelto (que podria estar desfasada).
     */
    @Override
    public boolean onEvaluateFullscreenMode() {
        Configuration c = getResources().getConfiguration();
        boolean phoneLandscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenHeightDp < 480;
        return phoneLandscape && super.onEvaluateFullscreenMode();
    }

    /**
     * Giro, tapa del plegable o modo noche: el contexto envuelto con el idioma de la app se desecha ANTES de llamar a
     * super, porque es dentro de super donde la plataforma rehace la vista de entrada (onCreateInputView) si la
     * ventana esta visible; asi la vista nueva mide con la pantalla actual y no con la de cuando se creo el contexto
     * (con el contexto viejo el teclado apaisado se cortaba al creerse en vertical). Se sustituye por uno nuevo en
     * vez de dejarlo a null porque el oyente del portapapeles (chip "Pegar") lo usa aunque la ventana este oculta.
     * Si la plataforma no rehizo la vista y hubo giro, se fuerza una nueva: el motor de IA compartido se desengancha
     * en onCreateInputView.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        ui = AppLocale.wrap(this);               // los recursos del servicio ya traen la configuracion nueva
        boolean turned = newConfig.orientation != lastOrientation;
        lastOrientation = newConfig.orientation;
        View before = root;
        super.onConfigurationChanged(newConfig);
        if (turned && root == before && isInputViewShown()) setInputView(onCreateInputView());
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view == null) return;
        // El panel de edicion sigue abierto si la app solo reinicia el mismo campo (p. ej. tras pegar); lo demas se cierra
        boolean editing = restarting && edit != null && edit.getVisibility() == View.VISIBLE;
        if (!editing) hideTray();
        applyPrefs();
        selStart = info.initialSelStart;
        selEnd = info.initialSelEnd;
        candStart = -1;
        if (editing) edit.setSelection(selectionCount());
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
        view.setPreviewEnabled(preview && !privateField);   // sin burbuja en contrasenas
        updateGlide();
        if (ai != null) ai.setFieldAllowed(suggestionsAllowed && !privateField);
        int field = AurebeshKeyboardView.FIELD_NONE;        // teclas extra segun el campo (@ y .com, / y .com), siempre en Aurebesh
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) field = AurebeshKeyboardView.FIELD_EMAIL;
            else if (variation == InputType.TYPE_TEXT_VARIATION_URI) field = AurebeshKeyboardView.FIELD_URL;
        }
        view.setFieldVariant(field);
        if (!restarting) {
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {     // teclado numerico Aurebesh
                boolean number = cls == InputType.TYPE_CLASS_NUMBER;
                view.showNumpad(!number, number && (info.inputType & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0,
                        number && (info.inputType & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0);
            } else {
                view.resetToLetters();
            }
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
        Bigrams.flush(this);                       // parejas aprendidas (escritura diferida) a disco
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
        severalLangs = severalSubtypes(imm);
        view.setLanguageSwitch(severalLangs);
        view.setSpaceSlide(spaceSlideMode(severalLangs));     // "auto" depende de cuantos idiomas haya
        view.setSpaceLabel(severalLangs ? getString("en".equals(lang) ? R.string.lang_en : "de".equals(lang) ? R.string.lang_de : R.string.lang_es) : null);
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
            dict.setStarWars(Prefs.starWars(this));
            return;
        }
        dict = null;
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Dictionary loaded = Dictionary.get(AurebeshIME.this, l);
                    loaded.setPersonal(Prefs.learnedWords(AurebeshIME.this));
                    loaded.setStarWars(Prefs.starWars(AurebeshIME.this));
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
        selStart = newSelStart;
        selEnd = newSelEnd;
        candStart = candidatesStart;
        if (edit != null && edit.getVisibility() == View.VISIBLE) edit.setSelection(selectionCount());
        refresh();
    }

    /** Caracteres seleccionados segun la ultima seleccion informada, -1 si no se conoce. */
    private int selectionCount() {
        return selStart >= 0 && selEnd >= 0 ? Math.abs(selEnd - selStart) : -1;
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
        boolean caps = autoCaps && capsAtCursor(ic, info);
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

    /** El editor pide mayuscula en el cursor (inicio de frase, campo en mayusculas). */
    private static boolean capsAtCursor(InputConnection ic, EditorInfo info) {
        return info != null && info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0;
    }

    private boolean capsAtCursor(InputConnection ic) { return capsAtCursor(ic, getCurrentInputEditorInfo()); }

    /**
     * Hay texto seleccionado. Si la ultima seleccion informada esta vacia no se consulta al editor; si hay una o no
     * se conoce (-1), lo confirma el editor.
     */
    private boolean hasSelection(InputConnection ic) {
        if (selStart >= 0 && selEnd >= 0 && selStart == selEnd) return false;
        return !TextUtils.isEmpty(ic.getSelectedText(0));
    }

    /** Palabra que se esta escribiendo (letras justo antes del cursor, con el cursor al final); null con seleccion. */
    private String currentWord(InputConnection ic) {
        if (hasSelection(ic)) return null;               // con texto seleccionado no hay palabra "en curso"
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0 && Character.isLetter(after.charAt(0))) return null;
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return null;
        int i = before.length();
        while (i > 0 && (Character.isLetter(before.charAt(i - 1)) || before.charAt(i - 1) == '\'')) i--;
        return before.subSequence(i, before.length()).toString();
    }

    static boolean looksLikeAcronym(String w) {
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
        if (correctionAnchor != null) {                  // chip "original" tras autocorregir: igual que los del deslizado
            CharSequence before = ic.getTextBeforeCursor(60, 0);
            if (before != null && before.toString().equals(correctionAnchor)) return;
            correctionAnchor = null;
        }
        if (!autocorrect || !suggestionsAllowed) {
            clearSuggestions();
            return;
        }
        String word = currentWord(ic);
        if (word == null || word.isEmpty()) {
            clearSuggestions();
            if (word != null && bigrams != null && fresh == null) {          // siguiente palabra prevista
                String prev = previousWord(ic, "");
                if (prev != null) {
                    boolean start = "<s>".equals(prev);
                    boolean caps = start && autoCaps && capsAtCursor(ic);  // al inicio de frase, los chips ya en mayuscula
                    List<String> chips = new ArrayList<>();
                    for (String s : bigrams.next(prev, 3)) {
                        String canon = canonicalOf(s);                       // "ahsoka" -> "Ahsoka"
                        chips.add(canon != null ? canon : caps ? capitalize(s) : s);
                    }
                    if (!start) chips.addAll(EmojiSuggest.lookup(this, prev, 1));   // emoji de la palabra recien cerrada
                    if (!chips.isEmpty()) view.setSuggestions(chips, null);
                }
            }
            return;
        }
        if (word.equals(lastWord)) return;              // ya pedidas
        String expansion = shortcutFor(word);
        if (expansion != null) {                         // atajo de texto: la expansion como chip resaltado (se aplica con el separador)
            lastWord = word;
            computedWord = word;
            pendingCorrection = null;
            suggestSeq++;
            view.setSuggestions(Arrays.asList(expansion), expansion);
            return;
        }
        if (dict == null) {
            clearSuggestions();
            return;
        }
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
        try {
            suggestFutureWord = w;
            suggestFuture = suggester.submit(new Callable<Dictionary.Result>() {   // un separador puede esperar este Future
                @Override public Dictionary.Result call() {
                    final Dictionary.Result r = d.suggest(w);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (seq != suggestSeq || !w.equals(lastWord) || view == null) return;
                            showResult(w, r);
                        }
                    });
                    return r;
                }
            });
        } catch (RejectedExecutionException e) {
            suggestFuture = null;                        // el servicio se esta destruyendo
        }
    }

    /** Expansion del atajo de texto si la palabra es una clave (nunca en contrasenas ni en campos sin sugerencias). */
    private String shortcutFor(String word) {
        if (word == null || word.isEmpty() || shortcuts.isEmpty() || privateField || !suggestionsAllowed) return null;
        String expansion = shortcuts.get(word.toLowerCase(Locale.ROOT));
        return expansion == null ? null : matchCase(word, expansion);
    }

    /**
     * Espera (como mucho 30 ms) la peticion en vuelo de la palabra actual, para que el separador no bloquee el hilo
     * principal. Si no llega a tiempo, la palabra se confirma tal cual (sin correccion ni deshacer) y devuelve false.
     */
    private boolean awaitSuggestion() {
        if (lastWord == null) return false;
        if (lastWord.equals(computedWord)) return true;
        Future<Dictionary.Result> f = suggestFuture;
        if (f == null || !lastWord.equals(suggestFutureWord)) return false;    // sin peticion (sigla): nada que corregir
        try {
            Dictionary.Result r = f.get(30, TimeUnit.MILLISECONDS);
            rankResult(lastWord, r);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException e) {
            lastCorrection = null;
            clearSuggestions();                          // la palabra se cierra tal cual; el resultado tardio se descarta
            return false;
        }
    }

    private void showResult(String word, Dictionary.Result r) {
        rankResult(word, r);
        List<String> chips = new ArrayList<>();
        if (!r.known && !r.suggestions.isEmpty()) chips.add("\"" + word + "\"");   // conservar lo escrito
        List<String> emo = EmojiSuggest.lookup(this, r.known ? word : (r.correction != null ? r.correction : word), 2);
        int maxWords = emo.isEmpty() ? 3 : 2;                 // con emojis, dejarles sitio en la barra
        for (String s : r.suggestions) {
            if (chips.size() >= maxWords) break;
            chips.add(chip(word, s));                             // lexico Star Wars: "Coruscant", no "coruscant"
        }
        chips.addAll(emo);
        view.setSuggestions(chips, pendingCorrection);
    }

    /** Anota el resultado de la palabra (correccion pendiente), eligiendo por contexto entre los candidatos. */
    private void rankResult(String word, Dictionary.Result r) {
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
    }

    private void clearSuggestions() {
        lastWord = null;
        computedWord = null;
        pendingCorrection = null;
        suggestSeq++;
        correctionAnchor = null;
        clearGlide();
        if (view != null) view.setSuggestions(null, null);
    }

    /** Tras autocorregir: un unico chip con la palabra original entrecomillada, que la recupera; se va cuando cambia el texto. */
    private void showCorrectionChip(InputConnection ic, String original) {
        lastWord = null;
        computedWord = null;
        pendingCorrection = null;
        suggestSeq++;
        clearGlide();
        CharSequence before = ic.getTextBeforeCursor(60, 0);
        correctionAnchor = before == null ? "" : before.toString();
        view.setSuggestions(Arrays.asList("\"" + original + "\""), null);
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
        correctionAnchor = null;
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
        if (!looksLikeAcronym(best)) {                   // lexico Star Wars: con su forma canonica (salvo en mayusculas)
            String canon = canonicalOf(best);
            if (canon != null) best = canon;
        }
        ic.beginBatchEdit();
        String cur = currentWord(ic);
        if (cur != null && !cur.isEmpty()) {
            if (!closeWord(ic, " ")) ic.commitText(" ", 1);
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
        if (alternatives != null) {
            for (String a : alternatives) {
                String alt = chip(best, a);
                if (!alt.equals(best) && !alts.contains(alt)) alts.add(alt);
            }
        }
        glideWord = best;
        glideAlts = alts;
        glideAutoSpace = true;
        showGlideChips(ic, alts, null);
    }

    /** Caja de lo escrito aplicada a la sustitucion: MAYUSCULAS, Capitalizada o tal cual. */
    static String matchCase(String original, String replacement) {
        if (original.isEmpty() || replacement.isEmpty()) return replacement;
        if (original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT))) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.charAt(0)))
            return replacement.substring(0, 1).toUpperCase(Locale.ROOT) + replacement.substring(1);
        return replacement;
    }

    /** Lo escrito esta todo en mayusculas (dos letras o mas): la sustitucion tambien. */
    static boolean allCaps(String s) {
        return s != null && s.length() > 1 && s.equals(s.toUpperCase(Locale.ROOT));
    }

    /** Forma canonica de una palabra del lexico Star Wars ("coruscant" -> "Coruscant"), o null si no lo es. */
    private String canonicalOf(String w) {
        if (dict == null || w == null || w.isEmpty()) return null;
        return dict.canonical(w.toLowerCase(Locale.ROOT));
    }

    /** Texto de un chip: la forma canonica si la palabra es del lexico Star Wars (en mayusculas si lo escrito lo esta); si no, tal cual. */
    private String chip(String typed, String s) {
        String canon = canonicalOf(s);
        if (canon == null) return s;
        return allCaps(typed) ? canon.toUpperCase(Locale.ROOT) : canon;
    }

    /**
     * Caja de lo que se escribe en lugar de lo tecleado: la forma canonica si es del lexico Star Wars ("Coruscant",
     * "Ahsoka"), y si no la de lo escrito ({@link #matchCase}). Todo en mayusculas se respeta en ambos casos.
     */
    private String cased(String typed, String word) {
        String canon = canonicalOf(word);
        if (canon == null) return matchCase(typed, word);
        return allCaps(typed) ? canon.toUpperCase(Locale.ROOT) : canon;
    }

    /** Primera letra en mayuscula (si era minuscula). */
    static String capitalize(String w) {
        if (w == null || w.isEmpty() || !Character.isLowerCase(w.charAt(0))) return w;
        return w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1);
    }

    /** Cierra la palabra en curso con el separador dado: el atajo de texto si lo es, o la autocorreccion pendiente. */
    private boolean closeWord(InputConnection ic, String suffix) {
        return expandShortcut(ic, suffix) || applyCorrection(ic, suffix);
    }

    /**
     * Atajo de texto: si la palabra escrita es una clave, la cambia por su expansion (con la caja de lo escrito) mas el
     * separador; retroceso justo despues lo deshace como una autocorreccion. Manda sobre la autocorreccion y no depende
     * del diccionario ni del texto predictivo; nunca en contrasenas ni en campos sin sugerencias.
     */
    private boolean expandShortcut(InputConnection ic, String suffix) {
        if (shortcuts.isEmpty() || privateField || !suggestionsAllowed) return false;
        String cur = currentWord(ic);
        String expansion = shortcutFor(cur);
        if (expansion == null) return false;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(cur.length(), 0);
        ic.commitText(expansion + suffix, 1);
        ic.endBatchEdit();
        lastCorrection = new String[]{cur, expansion, suffix};
        clearSuggestions();
        return true;
    }

    /**
     * Sustituye la palabra escrita por la correccion pendiente (mas el separador), solo con la sustitucion automatica
     * activa. Si el resultado aun no llego se espera como mucho 30 ms; si no, la palabra se confirma tal cual.
     */
    private boolean applyCorrection(InputConnection ic, String suffix) {
        if (!autoReplace || lastWord == null || dict == null) return false;
        String cur = currentWord(ic);
        if (cur == null || !cur.equals(lastWord)) return false;
        if (!lastWord.equals(computedWord)) {           // el resultado aun no llego: esperarlo un poco
            if (looksLikeAcronym(lastWord)) return false;
            if (!awaitSuggestion()) return false;
        }
        if (pendingCorrection == null) return false;
        String fix = cased(lastWord, pendingCorrection);     // "Coruscant" aunque se escribiera "coruscnt"
        if (fix.equals(lastWord)) return false;
        String original = lastWord;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(original.length(), 0);
        ic.commitText(fix + suffix, 1);
        ic.endBatchEdit();
        lastCorrection = new String[]{original, fix, suffix};
        Prefs.removeCandidate(this, original.toLowerCase(Locale.ROOT));   // una palabra corregida deja de ser candidata a aprenderse
        showCorrectionChip(ic, original);
        return true;
    }

    /**
     * Deshace la ultima autocorreccion (o atajo) si el texto previo al cursor sigue siendo el corregido: recupera la
     * palabra original con su separador. La misma correccion deshecha dos veces en la sesion aprende la palabra.
     */
    private boolean revertCorrection(InputConnection ic) {
        String[] c = lastCorrection;
        lastCorrection = null;
        if (c == null) return false;
        String corrected = c[1] + c[2];
        CharSequence before = ic.getTextBeforeCursor(corrected.length(), 0);
        if (before == null || !before.toString().equals(corrected)) return false;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(corrected.length(), 0);
        ic.commitText(c[0] + c[2], 1);
        ic.endBatchEdit();
        String key = c[0].toLowerCase(Locale.ROOT);
        if (!shortcuts.containsKey(key)) {              // deshacer un atajo no dice nada sobre la palabra
            Integer n = revertCount.get(key);
            int count = n == null ? 1 : n + 1;
            revertCount.put(key, count);
            if (count >= 2) learnWord(c[0]);
        }
        return true;
    }

    /**
     * Palabra desconocida cerrada con un separador (sin correccion ni atajo): se anota y a la segunda vez que se
     * escribe se aprende. Solo si se permite aprender (opcion, campo normal, sin modo incognito).
     */
    private void noteCandidate(InputConnection ic) {
        if (dict == null || !learn || incognito || !suggestionsAllowed || privateField) return;
        String cur = currentWord(ic);
        if (cur == null || cur.length() < 3 || looksLikeAcronym(cur)) return;
        String lw = cur.toLowerCase(Locale.ROOT);
        if (dict.contains(lw) || shortcuts.containsKey(lw)) return;
        // Con el predictivo activo se exige el veredicto del diccionario para esta palabra: si el resultado no llego
        // (30 ms agotados, excepcion) o habia correccion y no se aplico (sustitucion desactivada), no se anota
        if (autocorrect && (!cur.equals(computedWord) || pendingCorrection != null)) return;
        if (Prefs.bumpCandidate(this, lw) >= 2) {
            learnWord(cur);
            Prefs.removeCandidate(this, lw);
        }
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
        if (separator) {
            if (expandShortcut(ic, text)) return;                         // atajo de texto: manda sobre la autocorreccion
            rememberPair(ic);
        }
        if (glideWord != null) {                                          // la puntuacion absorbe el espacio del deslizado
            if (glideAutoSpace && text.length() == 1 && GLIDE_PUNCTUATION.indexOf(text.charAt(0)) >= 0) {
                CharSequence before = ic.getTextBeforeCursor(glideWord.length() + 1, 0);
                if (before != null && before.toString().equals(glideWord + " ")) ic.deleteSurroundingText(1, 0);
            }
            clearGlide();
        }
        if (separator && applyCorrection(ic, text)) return;
        if (separator) noteCandidate(ic);                                 // palabra desconocida que se queda tal cual
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
            awaitSuggestion();                                // el resultado aun no llego: la misma espera corta que applyCorrection
        }
        boolean replaced = autoReplace && pendingCorrection != null && lastWord != null && lastWord.equals(cur)
                && lastWord.equals(computedWord);             // lo que quedara escrito tras el separador
        String w = replaced ? pendingCorrection.toLowerCase(Locale.ROOT) : cur.toLowerCase(Locale.ROOT);
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
        // Retroceso justo despues de una autocorreccion: recupera la palabra original (se aprende si se repite)
        if (lastCorrection != null && revertCorrection(ic)) return;
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
            closeWord(ic, "");
            ic.performEditorAction(action);
        } else if (multiLine || info.inputType == InputType.TYPE_NULL) {
            onText("\n");
        } else {
            closeWord(ic, "");
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

    // ------------------------------------------------------------------ 1.10: cursor, borrar palabra, ocultar, tamano

    /** Deslizar sobre el espacio en modo cursor: flechas del teclado fisico (funcionan en cualquier editor); con select, extienden la seleccion. */
    @Override
    public void onCursorMove(int steps, boolean select) {
        if (steps == 0) return;
        int code = steps < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        int n = Math.abs(steps);
        for (int i = 0; i < n; i++) sendKey(code, select, false);
        lastCorrection = null;
        clearSuggestions();
    }

    /**
     * Tecla del teclado fisico (pulsar y soltar) por la conexion, con mayusculas (extiende la seleccion) y control
     * opcionales. Con mayusculas se envia tambien la tecla Shift en si: TextView decide si extiende la seleccion
     * por el estado de esa tecla en el texto, no por el meta del evento de la flecha.
     */
    private void sendKey(int code, boolean shift, boolean ctrl) {
        InputConnection ic = getCurrentInputConnection();
        int meta = (shift ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON : 0)
                | (ctrl ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0);
        if (ic == null || meta == 0) {
            sendDownUpKeyEvents(code);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (shift) ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta));
        if (shift) {
            int rest = meta & ~(KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
            ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, rest));
        }
    }

    /** Borrar la palabra anterior entera (mantener borrar mas de ~2 s). */
    @Override
    public void onDeleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastSpaceAt = 0;
        if (hasSelection(ic)) {                           // con seleccion: se borra ella y nada mas
            ic.commitText("", 1);
            lastCorrection = null;
            clearSuggestions();
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return; }
        int n = before.length(), i = n;
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
        int count = n - i;
        if (count <= 0) count = 1;
        lastCorrection = null;
        ic.deleteSurroundingText(count, 0);
        clearSuggestions();
    }

    /** Boton ∨ de la banda inferior. */
    @Override
    public void onHide() { requestHideSelf(0); }

    /** Fin del modo de ajuste: se guardan alto y margen (por pantalla) y el oyente de preferencias los aplica. */
    @Override
    public void onResize(int heightPct, int bottomDp) {
        Prefs.setHeightPct(this, heightPct);
        Prefs.setBottomDp(this, bottomDp);
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
            String original = word.substring(1, word.length() - 1);
            if (lastCorrection != null && original.equals(lastCorrection[0]) && revertCorrection(ic)) {
                learnWord(original);                    // el usuario deshizo la correccion a proposito: la palabra es suya
            } else {
                ic.commitText(" ", 1);                  // conservar lo escrito, y aprenderlo
                learnWord(cur);
            }
        } else if (AurebeshKeyboardView.isEmoji(word)) {   // emoji sugerido: sustituye la palabra
            if (cur != null && !cur.isEmpty()) ic.deleteSurroundingText(cur.length(), 0);
            ic.commitText(word, 1);
            Prefs.addRecentEmoji(this, word);
        } else {
            String canon = canonicalOf(word);           // lexico Star Wars: siempre con su forma canonica
            if (cur != null && !cur.isEmpty()) {
                ic.deleteSurroundingText(cur.length(), 0);
                word = cased(cur, word);
            } else if (canon != null) {
                word = canon;
            } else if (autoCaps && capsAtCursor(ic)) {  // prediccion al inicio de frase: con mayuscula
                word = capitalize(word);
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
            case AurebeshKeyboardView.TOOL_TRANSLATE: openTranslate(); break;
            case AurebeshKeyboardView.TOOL_MORE: openTools(); break;
            case AurebeshKeyboardView.TOOL_EDIT: openEdit(); break;
            case AurebeshKeyboardView.TOOL_SPLIT: toggleSplit(); break;                           // el oyente de preferencias lo aplica
            case AurebeshKeyboardView.TOOL_STRIP: Prefs.get(this).edit().putBoolean(Prefs.STRIP, !Prefs.strip(this)).apply(); break;
            case AurebeshKeyboardView.TOOL_HIDE: requestHideSelf(0); break;
            case AurebeshKeyboardView.TOOL_LANG: onSwitchLanguage(); break;
            case AurebeshKeyboardView.TOOL_RESIZE: view.setResizeMode(!view.isResizeMode()); break;   // tiradores de alto y margen
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

    /**
     * Teclado dividido desde la cinta: "on" u "off" segun lo que se ve ahora (no segun la preferencia, que puede
     * ser "auto"). El dividido y el modo a una mano se excluyen: al dividir se apaga el modo a una mano.
     */
    private void toggleSplit() {
        if (view == null) return;
        boolean on = !view.isSplit();
        if (on && !"none".equals(Prefs.hand(this))) Prefs.toggleHand(this);
        Prefs.get(this).edit().putString(Prefs.SPLIT, on ? "on" : "off").apply();
    }

    /**
     * Si la herramienta tiene sentido ahora: dividir solo en pantallas anchas (Fold desplegado, tableta) o si ya
     * esta dividido; el globo solo con varios idiomas Aurebesh; inteligencia y traducir no en contrasenas.
     */
    private boolean toolAvailable(int tool) {
        switch (tool) {
            case AurebeshKeyboardView.TOOL_SPLIT:
                return view.isSplit() || getResources().getConfiguration().smallestScreenWidthDp >= 600;
            case AurebeshKeyboardView.TOOL_LANG:
                return severalLangs;
            case AurebeshKeyboardView.TOOL_AI:
            case AurebeshKeyboardView.TOOL_TRANSLATE:
                return !privateField;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ cuadricula "..." de herramientas

    private void openTools() {
        if (tools == null || view == null) return;
        int[] all = AurebeshKeyboardView.ALL_TOOLS;
        boolean[] available = new boolean[all.length];
        for (int i = 0; i < all.length; i++) available[i] = toolAvailable(all[i]);
        int[] bar = view.toolbarItems();
        if (bar == null) bar = AurebeshKeyboardView.DEFAULT_TOOLBAR;
        tools.show(bar, all, available);
        showTray(tools);
    }

    /** Herramienta tocada en la cuadricula: como si se hubiera tocado en la cinta (sobre las teclas, ya visibles). */
    @Override
    public void onToolsPick(int tool) {
        hideTray();
        if (tool == AurebeshKeyboardView.TOOL_MORE) return;
        onToolbar(tool);
    }

    /** Nueva eleccion y orden de la cinta (modo Editar de la cuadricula); null vuelve al orden de fabrica. */
    @Override
    public void onToolsOrder(int[] barItems) {
        Prefs.setToolbarItems(this, barItems);           // el oyente de preferencias tambien lo aplica
        if (view != null) view.setToolbarItems(barItems);
    }

    @Override
    public void onToolsClose() { hideTray(); }

    // ------------------------------------------------------------------ panel de edicion

    private void openEdit() {
        if (edit == null || view == null) return;
        edit.show();
        edit.setSelection(selectionCount());
        showTray(edit);
    }

    /** Flecha de la cruceta (la repeticion al mantener la gestiona la bandeja); con select extiende la seleccion. */
    @Override
    public void onEditMove(int keyCode, boolean select) {
        sendKey(keyCode, select, false);
        lastCorrection = null;
        lastSpaceAt = 0;
        clearSuggestions();
    }

    /** Seleccionar todo, cortar, copiar, pegar, deshacer o rehacer, por el menu contextual del editor. */
    @Override
    public void onEditAction(int actionId) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean done = ic.performContextMenuAction(actionId);
        if (!done && (actionId == android.R.id.undo || actionId == android.R.id.redo)) {
            Toast.makeText(this, ui.getString(R.string.edit_action_unavailable), Toast.LENGTH_SHORT).show();
        }
        if (actionId == android.R.id.cut || actionId == android.R.id.paste) {
            lastCorrection = null;
            lastSpaceAt = 0;
            clearSuggestions();
        }
    }

    /** Inicio o fin de todo el texto (con control; sin el, Inicio/Fin solo van a la linea), extendiendo la seleccion si select. */
    @Override
    public void onEditHome(boolean end, boolean select) {
        sendKey(end ? KeyEvent.KEYCODE_MOVE_END : KeyEvent.KEYCODE_MOVE_HOME, select, true);
        lastCorrection = null;
        lastSpaceAt = 0;
        clearSuggestions();
    }

    @Override
    public void onEditClose() { hideTray(); }

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
        if (tools != null) tools.setVisibility(tools == which ? View.VISIBLE : View.GONE);
        if (edit != null) edit.setVisibility(edit == which ? View.VISIBLE : View.GONE);
        view.setVisibility(View.INVISIBLE);
    }

    private void hideTray() {
        if (view == null) return;
        if (tray != null) tray.setVisibility(View.GONE);
        if (emojis != null) emojis.setVisibility(View.GONE);
        if (ai != null) ai.setVisibility(View.GONE);
        if (tools != null) tools.setVisibility(View.GONE);
        if (edit != null) edit.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ inteligencia

    private void openAi() {
        if (ai == null || privateField) return;
        ai.show();
        showTray(ai);
    }

    /** Icono "Traducir" de la cinta: el panel de inteligencia directamente en Traducir. */
    private void openTranslate() {
        if (ai == null || privateField) return;
        ai.openTranslate();
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
        closeWord(ic, "");
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
        closeWord(ic, "");
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
            closeWord(ic, "");
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
        ClipHistory.clear(this);                     // conserva los fijados: siguen a la vista
        tray.show(ui.getString(R.string.clipboard_title), ClipHistory.items(this), ui.getString(R.string.clipboard_empty));
    }

    @Override
    public void onTrayClose() {
        hideTray();
    }

    /**
     * Chincheta de la bandeja: fija o suelta una entrada del historial (las fijadas no caducan al llenarse el
     * historial y sobreviven al reinicio). Sin @Override: KeyboardTray.Host lo declara cuando llegue el fijado;
     * entonces, antes de volver a mostrar la lista, va aqui ClipHistory.setPinned(this, entry, pinned).
     */
    @Override
    public void onTrayPin(ClipHistory.Entry entry, boolean pinned) {
        if (tray == null) return;
        if (!ClipHistory.setPinned(this, entry, pinned) && pinned) {
            Toast.makeText(this, ui.getString(R.string.clip_pin_limit, ClipHistory.MAX_PINNED), Toast.LENGTH_SHORT).show();
        }
        tray.show(ui.getString(R.string.clipboard_title), ClipHistory.items(this), ui.getString(R.string.clipboard_empty));
    }
}
