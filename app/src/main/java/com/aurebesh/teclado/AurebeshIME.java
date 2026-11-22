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

    private static final String SEPARATORS = " .,;:!?\u203d\n";      // el exclarrogativo cierra la palabra como ? y !
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
    private boolean proseField = true;            // texto corriente (ni contrasena, correo o direccion web): doble espacio = punto
    private boolean glideAllowed = false;         // se puede deslizar: texto que no sea contrasena ni correo (tambien direcciones y buscadores)
    private boolean uriField = false;             // direccion web: lo deslizado no lleva mayuscula ni espacio tras un punto o una barra
    private boolean tildesOnly = false;           // nombres, direcciones postales y campos con autocompletado propio: solo se ponen tildes
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
    private Learning learning;                    // politica del diccionario personal (usos en mensajes distintos, erratas, purga)
    private Map<String, String> shortcuts = new LinkedHashMap<>();         // atajos de texto (clave en minusculas -> expansion)
    private AiRunner runner;                      // unico motor de IA del servicio (sobrevive a rehacer la vista)
    // Seleccion conocida (EditorInfo al empezar y onUpdateSelection despues); -1 si el editor no la informa
    private int selStart = -1, selEnd = -1, candStart = -1;

    // Escritura deslizando: la ultima palabra escrita con un trazo, para cambiarla por un chip o borrarla entera
    private static final String GLIDE_PUNCTUATION = ".,;:!?\u203d";
    // Signos de cierre: tambien cierran la palabra (con su correccion) y se pegan a la palabra deslizada o elegida en la
    // cinta. La comilla recta solo cuando cierra (hay otra abierta antes en el parrafo).
    private static final String CLOSERS = ")]}\u201d\u00bb";
    private String glideWord;                     // tal cual se escribio (con mayusculas), o null
    private List<String> glideAlts;               // alternativas que se muestran en la barra
    private String glideAnchor;                   // texto previo al cursor al poner los chips: si cambia, se quitan
    private boolean glideAutoSpace;               // el espacio tras la palabra es automatico (la puntuacion lo absorbe)
    private final Handler main = new Handler(Looper.getMainLooper());

    // Palabra puesta con un chip de la cinta: su espacio es automatico, como el del deslizado (la puntuacion que
    // sigue lo absorbe y un espacio tecleado no lo duplica). Solo vale para la tecla siguiente.
    private String chipWord;                      // tal cual quedo escrita, o null
    private String chipAnchor;                    // texto previo al cursor justo despues: si cambia, se olvida
    // Lo tecleado seguido, sin separadores. El corrector solo cambia (y el diccionario personal solo aprende) palabras
    // cuyo final se acaba de teclear: lo pegado, lo que ya estaba en el campo o una correccion deshecha se respetan.
    private final StringBuilder typedRun = new StringBuilder();
    private static final int TYPED_RUN_MAX = 64;
    private long lastDeleteAt = -10000;           // ultimo borrado hecho con el teclado (reloj de uptimeMillis)
    // Correcciones que el usuario deshizo (borrar justo despues o el chip entre comillas), en minusculas: mientras viva
    // el servicio esa palabra no se vuelve a cambiar sola (la correccion sigue a un toque en la cinta)
    private final java.util.LinkedHashSet<String> rejected = new java.util.LinkedHashSet<>();
    private static final int REJECTED_MAX = 200;
    // Rechazo por borrar justo despues, aun sin confirmar: borrar tambien es el gesto de quien solo queria quitar el
    // espacio para arreglar la palabra a mano. Se confirma al seguir escribiendo con la palabra restaurada intacta.
    private String undoneKey;                     // clave para rejected ("lalo", o con la apertura delante si es un interrogativo)
    private String undoneWord;                    // la palabra tal como quedo restaurada

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
        setTheme(R.style.TemaTeclado);       // antes de super: la ventana del teclado se crea ahi con este tema
        super.onCreate();
        Prefs.migrate(this);                 // datos de versiones anteriores (una sola vez)
        learning = new Learning(this);
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
        if (learning != null) learning.endSession();
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
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(0, 0, 0, navInset(insets));
                return insets;
            }
        });
        applyPrefs();
        return root;
    }

    /**
     * Alto de la barra de navegacion que el sistema NO ha descontado ya de la ventana del teclado. Con el tema del
     * servicio (TemaTeclado) la ventana no se dibuja de borde a borde y esto vale 0; si algun sistema la dibujara asi,
     * el teclado sube ese alto para no quedar bajo la barra, con el fondo del tema debajo.
     */
    static int navInset(android.view.WindowInsets insets) {
        if (insets == null) return 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) return Math.max(0, insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom);
        return Math.max(0, insets.getSystemWindowInsetBottom());
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
            view.setTopRow(Prefs.topRow(this));                // numeros / digrafos / nada (manda sobre el booleano NUMBERS de applyPrefs)
            view.setSpanishDigraphs(Prefs.digraphsEs(this));   // ll, rr, qu y gu con tecla y glifo en el teclado espanol
            view.setHintMode(Prefs.hintModeInt(this));         // nunca / siempre / aprendiendo (manda sobre el booleano antiguo)
            view.setSaber(Prefs.saberArgb(this));              // color de la estela al deslizar
            view.setToolbar(Prefs.toolbar(this));
            view.setToolbarItems(Prefs.toolbarItems(this));    // iconos de la cinta elegidos en "..."
            view.setAltChars(Prefs.altChars(this));            // simbolo de cada letra arriba a la derecha y al mantener (como Samsung)
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
        if (root != null) root.setBackgroundColor(theme.bg);   // lo que quede bajo las teclas (relleno de navInset), del color del teclado
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
        proseField = cls == InputType.TYPE_CLASS_TEXT && !privateField && variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        uriField = cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI;
        glideAllowed = cls == InputType.TYPE_CLASS_TEXT && !privateField
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        tildesOnly = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                || variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_FILTER
                || ((info.inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0
                        && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0));
        view.setPasswordField(privateField && cls == InputType.TYPE_CLASS_TEXT);   // contrasenas: siempre con fila de numeros
        view.setEnterEmoji(!privateField);                  // mantener intro abre los emojis (no en contrasenas ni PIN)
        // Diccionario personal: un campo nuevo abre una sesion (un uso por palabra como mucho); si la app solo reinicia
        // el campo y lo ha vaciado (mensaje enviado con su propio boton), empieza otro mensaje
        if (!restarting) learning.beginSession();
        else if (info.initialSelStart <= 0 && info.initialSelEnd <= 0) learning.newMessage();
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
        forgetChip();
        if (undoneKey != null && (!restarting || fieldEmptied())) {   // otro campo, o mensaje enviado con el boton de la app:
            reject(undoneKey);                                        // la palabra restaurada se quedo como estaba
            undoneKey = null;
            undoneWord = null;
        }
        if (!restarting) {
            typedRun.setLength(0);
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {     // teclado numerico Aurebesh
                boolean number = cls == InputType.TYPE_CLASS_NUMBER;
                boolean pin = number && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;   // PIN: solo digitos, borrar e intro
                view.showNumpad(!number, number && (info.inputType & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0,
                        number && (info.inputType & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0, pin);
            } else {
                view.resetToLetters();
            }
        } else if (cls == InputType.TYPE_CLASS_TEXT && view.onSymbols() && fieldEmptied()) {
            view.showLetters();          // mensaje enviado con el boton de la app desde la pagina de signos: otra vez letras
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

    /**
     * La app reinicio el campo y lo dejo vacio sin que se borrara nada con el teclado en el ultimo medio segundo:
     * lo normal es un mensaje enviado con el boton de la propia app.
     */
    private boolean fieldEmptied() {
        if (SystemClock.uptimeMillis() - lastDeleteAt < 500) return false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        return TextUtils.isEmpty(ic.getTextBeforeCursor(1, 0)) && TextUtils.isEmpty(ic.getTextAfterCursor(1, 0));
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
        learning.endSession();                     // candidatos del diccionario personal a disco; la sesion de campo termina
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
        if (".!?\u203d\n".indexOf(c) >= 0) return "<s>";
        if (c == '\u00bf' || c == '\u00a1') return "<s>";                // tras un signo de apertura empieza pregunta o exclamacion
        if (c == ',' && end < s.length()) end--;                         // "si, " -> la palabra de antes de la coma
        int i = end;
        while (i > 0 && (Character.isLetter(s.charAt(i - 1)) || s.charAt(i - 1) == '\'')) i--;
        return i == end ? null : s.substring(i, end).toLowerCase(Locale.ROOT);
    }

    /** El caracter justo antes del cursor es {@code c}. */
    private static boolean endsWith(InputConnection ic, char c) {
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        return b != null && b.length() == 1 && b.charAt(0) == c;
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
        if (info == null || info.inputType == InputType.TYPE_NULL) return false;
        if (ic.getCursorCapsMode(info.inputType) != 0) return true;
        // el exclarrogativo tambien cierra frase y los signos de apertura (¿ ¡ « “) la empiezan, y el editor no lo sabe
        boolean sentences = (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (info.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0;
        if (!sentences) return false;
        CharSequence before = ic.getTextBeforeCursor(CAPS_WINDOW, 0);
        return afterInterrobang(before) || opensSentence(before, before != null && before.length() >= CAPS_WINDOW);
    }

    private static final int CAPS_WINDOW = 64;
    private static final String OPENERS = "\u00bf\u00a1\u00ab\u201c\u2018\"'([{";     // todo lo que puede abrir una frase
    private static final String OWN_OPENERS = "\u00bf\u00a1\u00ab\u201c\u2018";        // los que Android no salta al decidir la mayuscula

    /**
     * Lo anterior al cursor termina en signos de apertura (alguno de ¿ ¡ « “ ‘, que el editor no reconoce) y, sin
     * ellos, se esta a inicio de frase: principio del texto, tras un salto de linea o tras un punto, ! o ? y un
     * espacio. "¿", "Hola. ¿" y "Hola.\n¡" piden mayuscula; "Hola, ¿" y "hola ¿" no. {@code clipped} dice que el texto
     * leido es solo el final de uno mas largo (si no queda nada antes de los signos, no se sabe que habia).
     */
    static boolean opensSentence(CharSequence before, boolean clipped) {
        if (before == null) return false;
        int e = before.length();
        boolean own = false;
        while (e > 0 && OPENERS.indexOf(before.charAt(e - 1)) >= 0) {
            if (OWN_OPENERS.indexOf(before.charAt(e - 1)) >= 0) own = true;
            e--;
        }
        if (!own) return false;
        if (e == 0) return !clipped;
        CharSequence rest = before.subSequence(0, e);
        return TextUtils.getCapsMode(rest, e, TextUtils.CAP_MODE_SENTENCES) != 0 || afterInterrobang(rest);
    }

    /** Lo anterior al cursor es un exclarrogativo seguido de espacios: empieza frase. */
    static boolean afterInterrobang(CharSequence before) {
        if (before == null) return false;
        int e = before.length();
        if (e == 0 || before.charAt(e - 1) != ' ') return false;
        while (e > 0 && before.charAt(e - 1) == ' ') e--;
        return e > 0 && before.charAt(e - 1) == '\u203d';
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
        if (chipAnchor != null) {                        // el espacio automatico del chip caduca si el texto cambia
            CharSequence before = ic.getTextBeforeCursor(60, 0);
            if (before == null || !before.toString().equals(chipAnchor)) forgetChip();
        }
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
        if (typedRun.length() > 0 && !justTyped(word)) typedRun.setLength(0);   // el cursor ya no sigue a lo tecleado
        if (word == null || word.isEmpty()) {
            clearSuggestions();
            if (word != null && bigrams != null && fresh == null) {          // siguiente palabra prevista
                String prev = previousWord(ic, "");
                // Con el campo vacio no se predice nada: los tres chips de inicio de frase son siempre los mismos y
                // taparian los iconos de la cinta (emojis, portapapeles), que es lo que se busca al abrir el teclado
                if (prev != null && !TextUtils.isEmpty(ic.getTextBeforeCursor(1, 0))) {
                    boolean start = "<s>".equals(prev);
                    boolean caps = start && autoCaps && capsAtCursor(ic);  // al inicio de frase, los chips ya en mayuscula
                    List<String> chips = new ArrayList<>();
                    if (start && "es".equals(lang) && endsWith(ic, '\u00bf')) {   // tras ¿, lo que mas se pregunta
                        boolean up = autoCaps && capsAtCursor(ic);
                        for (String q : new String[]{"qu\u00e9", "c\u00f3mo", "cu\u00e1ndo"}) chips.add(up ? capitalize(q) : q);
                    }
                    for (String s : bigrams.next(prev, 6)) {
                        if (chips.size() >= 3) break;
                        if (dict != null && dict.vetoed(s)) continue;        // las familias vetadas tampoco como palabra siguiente
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
        // conservar lo escrito: si la palabra es desconocida, o conocida pero se le va a cambiar ("papa" -> "papá", "¿que" -> "¿qué")
        if ((!r.known && !r.suggestions.isEmpty()) || (r.known && pendingCorrection != null)) chips.add("\"" + word + "\"");
        List<String> emo = EmojiSuggest.lookup(this, r.known ? word : (r.correction != null ? r.correction : word), 2);
        int maxWords = emo.isEmpty() ? 3 : 2;                 // con emojis, dejarles sitio en la barra
        // Orden: la correccion, las que suelen seguir a la palabra anterior y empiezan asi, y el resto por frecuencia
        List<String> words = new ArrayList<>(r.suggestions);
        int at = 0;
        if (pendingCorrection != null) {
            words.remove(pendingCorrection);
            words.add(0, pendingCorrection);
            at = 1;
        }
        for (String f : followers(word, word.length() == 1 ? 3 : 2)) {
            if (f.equals(pendingCorrection)) continue;
            words.remove(f);
            words.add(Math.min(at++, words.size()), f);
        }
        for (String s : words) {
            if (chips.size() >= maxWords) break;
            chips.add(cased(word, s));                            // como quedara escrita: "Hola" si se tecleo "Hol"; "Coruscant"
        }
        chips.addAll(emo);
        // lo que no se acaba de teclear (pegado, de antes) no se cambia solo: sin pastilla resaltada
        view.setSuggestions(chips, pendingCorrection != null && justTyped(word) ? cased(word, pendingCorrection) : null);
    }

    /**
     * Palabras que suelen seguir a la anterior y empiezan por lo que se lleva tecleado (sin contar tildes ni
     * mayusculas): "muchas g" -> "gracias", "buenos di" -> "días". Primero las parejas aprendidas del usuario.
     */
    private List<String> followers(String word, int max) {
        List<String> out = new ArrayList<>();
        InputConnection ic = getCurrentInputConnection();
        if (bigrams == null || ic == null || word == null || word.isEmpty()) return out;
        String prev = previousWord(ic, word);
        if (prev == null) return out;
        String typed = Dictionary.deaccent(word.toLowerCase(Locale.ROOT));
        for (String s : bigrams.next(prev, 15)) {
            if (out.size() >= max) break;
            String plain = Dictionary.deaccent(s.toLowerCase(Locale.ROOT));
            if (!plain.startsWith(typed) || s.equalsIgnoreCase(word)) continue;
            if (dict != null && dict.vetoed(s)) continue;
            out.add(s);
        }
        return out;
    }

    private static final String[] INTERROGATIVES = {"que", "qu\u00e9", "como", "c\u00f3mo", "cuando", "cu\u00e1ndo",
            "donde", "d\u00f3nde", "adonde", "ad\u00f3nde", "quien", "qui\u00e9n", "quienes", "qui\u00e9nes",
            "cual", "cu\u00e1l", "cuales", "cu\u00e1les", "cuanto", "cu\u00e1nto", "cuanta", "cu\u00e1nta",
            "cuantos", "cu\u00e1ntos", "cuantas", "cu\u00e1ntas"};

    /**
     * En espanol, la palabra pegada a un signo de interrogacion de apertura que casi siempre lleva tilde ahi ("¿que" ->
     * "qué", "¿como" -> "cómo"); null si no es el caso. Se aplica como cualquier correccion y se deshace con borrar.
     */
    /** Forma con tilde del interrogativo escrito sin ella (en minusculas), o null. */
    private static String interrogativeOf(String lower) {
        for (int i = 0; i < INTERROGATIVES.length; i += 2) if (INTERROGATIVES[i].equals(lower)) return INTERROGATIVES[i + 1];
        return null;
    }

    /** El texto previo al cursor es {@code tail} precedido justo por {@code c}. */
    private static boolean endsWith(InputConnection ic, String tail, char c) {
        CharSequence b = ic.getTextBeforeCursor(tail.length() + 1, 0);
        return b != null && b.length() == tail.length() + 1 && b.charAt(0) == c;
    }

    private String interrogative(String word) {
        if (!"es".equals(lang) || word == null || word.length() < 3) return null;
        String fix = interrogativeOf(word.toLowerCase(Locale.ROOT));
        if (fix == null) return null;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        CharSequence b = ic.getTextBeforeCursor(word.length() + 1, 0);
        return b != null && b.length() == word.length() + 1 && b.charAt(0) == '\u00bf' ? fix : null;
    }

    /** Anota el resultado de la palabra (correccion pendiente), eligiendo por contexto entre los candidatos. */
    private void rankResult(String word, Dictionary.Result r) {
        computedWord = word;
        pendingCorrection = r.correction;
        if (r.known && r.correction == null) {           // "¿que" -> "¿qué": la palabra existe, pero ahi lleva tilde
            pendingCorrection = interrogative(word);
            if (pendingCorrection != null && rejected.contains("\u00bf" + word.toLowerCase(Locale.ROOT))) pendingCorrection = null;
            return;
        }
        if (pendingCorrection != null && holdsBack(word, pendingCorrection)) {
            pendingCorrection = null;                    // sigue en la cinta, la primera, pero no se aplica sola
            return;
        }
        // contexto: candidato que mejor sigue a la anterior. No cuando la correccion es lo mismo que se escribio salvo
        // tildes o espacios ("asi" -> "así", "dió" -> "dio", "porfavor" -> "por favor"): ahi no hay duda de la palabra,
        // y el contexto la cambiaba por otra ("como asi" -> "como si", "no habria" -> "no había")
        if (bigrams != null && !r.known && r.correction != null && r.suggestions.size() > 1 && !sameLetters(word, r.correction)) {
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
        // "¿qeu" -> "que" -> "qué": la errata de un interrogativo pegado a la apertura sale ya con su tilde
        if ("es".equals(lang) && pendingCorrection != null) {
            String q = interrogativeOf(pendingCorrection.toLowerCase(Locale.ROOT));
            InputConnection ic = getCurrentInputConnection();
            if (q != null && ic != null && endsWith(ic, word, '\u00bf')) {
                int at = r.suggestions.indexOf(pendingCorrection);
                if (at >= 0) r.suggestions.set(at, q);
                pendingCorrection = q;
            }
        }
    }

    /**
     * La correccion de esta palabra no se aplica sola (se queda como chip a un toque): el usuario ya la deshizo una vez,
     * el campo es de nombre, direccion postal o con autocompletado propio (ahi solo se ponen tildes), la palabra va pegada a una cifra o a un simbolo de direccion o etiqueta ("juan@gmail", "#parati", "com.mx",
     * "16mb"), o es un nombre escrito con mayuscula a proposito a mitad de frase ("con Lalo", "en Tepito").
     */
    private boolean holdsBack(String word, String correction) {
        if (rejected.contains(word.toLowerCase(Locale.ROOT))) return true;
        if (tildesOnly && !sameLetters(word, correction)) return true;     // un nombre o una calle: no se cambia por otra palabra
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        if (gluedToAddress(ic, word) || unitAfterNumber(ic, word)) return true;
        // un nombre con mayuscula a proposito: solo se le ponen tildes ("Mexico"), se separa ("Porfavor"), se contrae en
        // ingles ("Im" -> "I'm") o se lleva a una palabra del usuario o del lexico
        return namedOnPurpose(ic, word) && !sameLetters(word, correction) && correction.indexOf('\'') < 0
                && !ownWord(correction);
    }

    private static final java.util.HashSet<String> UNITS = new java.util.HashSet<>(Arrays.asList(
            "hr", "hrs", "min", "seg", "mb", "gb", "kb", "tb", "kg", "gr", "mg", "km", "cm", "mm", "ml", "lt", "lts", "pza", "pzas"));

    /** Unidad escrita tras una cifra y un espacio ("2 hr", "16 mb"): es una abreviatura, no la errata de otra palabra. */
    static boolean unitAfterNumber(InputConnection ic, String word) {
        if (!UNITS.contains(word.toLowerCase(Locale.ROOT))) return false;
        CharSequence b = ic.getTextBeforeCursor(word.length() + 2, 0);
        return b != null && b.length() == word.length() + 2 && b.charAt(1) == ' ' && Character.isDigit(b.charAt(0));
    }

    /** La correccion lleva a una palabra del usuario o del lexico Star Wars: esa si se aplica aunque se escribiera con mayuscula. */
    private boolean ownWord(String correction) {
        String lw = correction.toLowerCase(Locale.ROOT);
        return dict != null && (dict.isPersonal(lw) || dict.canonical(lw) != null);
    }

    /**
     * Mayuscula inicial puesta a proposito: una sola, con el resto en minusculas, a mitad de frase (al inicio la pone el
     * teclado). En aleman no cuenta: todos los sustantivos la llevan.
     */
    private boolean namedOnPurpose(InputConnection ic, String word) {
        if ("de".equals(lang) || word.length() < 3 || !Character.isUpperCase(word.charAt(0))) return false;
        for (int i = 1; i < word.length(); i++) if (!Character.isLowerCase(word.charAt(i))) return false;
        // el mismo criterio que decide la mayuscula automatica, aplicado al texto anterior a la palabra
        CharSequence b = ic.getTextBeforeCursor(word.length() + CAPS_WINDOW, 0);
        if (b == null || b.length() <= word.length()) return false;      // principio del texto: la puso el teclado
        CharSequence before = b.subSequence(0, b.length() - word.length());
        boolean clipped = b.length() >= word.length() + CAPS_WINDOW;
        return TextUtils.getCapsMode(before, before.length(), TextUtils.CAP_MODE_SENTENCES) == 0
                && !afterInterrobang(before) && !opensSentence(before, clipped);
    }

    /**
     * La palabra va pegada por delante a una cifra, a uno de @ # / _ \ = & % + o a un punto que a su vez sigue a una letra
     * o una cifra ("tienda.com", "archivo.pdf"): es un trozo de correo, direccion, etiqueta o codigo. Un punto tras un
     * espacio u otro punto no cuenta ("pues...tambien" sigue recibiendo su tilde).
     */
    static boolean gluedToAddress(InputConnection ic, String word) {
        CharSequence b = ic.getTextBeforeCursor(word.length() + 2, 0);
        if (b == null || b.length() <= word.length()) return false;
        int at = b.length() - word.length() - 1;
        char p = b.charAt(at);
        if (Character.isDigit(p) || "@#/_\\=&%+".indexOf(p) >= 0) return true;
        return p == '.' && at > 0 && Character.isLetterOrDigit(b.charAt(at - 1));
    }

    /**
     * Resuelve el rechazo pendiente tras deshacer una correccion con borrar. {@code keeps}: lo que se va a hacer deja la
     * palabra restaurada como esta (otra palabra, un signo, enviar). Se confirma solo si el texto previo al cursor sigue
     * terminando en ella (con o sin su separador); si se le borraron o anadieron letras, no era un rechazo.
     */
    private void settleUndone(InputConnection ic, boolean keeps) {
        String key = undoneKey, word = undoneWord;
        undoneKey = null;
        undoneWord = null;
        if (key == null || !keeps || ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(word.length() + 2, 0);
        if (b == null) return;
        String t = b.toString();
        int end = t.length();
        while (end > 0 && end > t.length() - 1 && !Character.isLetter(t.charAt(end - 1))) end--;   // como mucho, su separador
        if (end >= word.length() && t.substring(end - word.length(), end).equals(word)
                && (end == word.length() || !Character.isLetter(t.charAt(end - word.length() - 1)))) reject(key);
    }

    /** Recuerda que el usuario deshizo la correccion de esta palabra (con su marca si era un interrogativo tras la apertura). */
    private void reject(String key) {
        rejected.remove(key);
        rejected.add(key);
        if (rejected.size() > REJECTED_MAX) {
            java.util.Iterator<String> it = rejected.iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * Palabra de la lista cuya correccion rechazo el usuario ("papa" por "papá", "seria" por "sería"): se guarda en el
     * diccionario personal para que no se le vuelva a cambiar; su gemela con tilde sigue a un toque en la cinta.
     */
    private void keepWord(String w) {
        if (!mayLearn() || dict == null || !Learning.wellFormed(w)) return;
        String lw = w.toLowerCase(Locale.ROOT);
        if (!dict.inList(lw) || dict.isPersonal(lw)) return;
        Prefs.addLearnedWord(this, lw);
        dict.setPersonal(Prefs.learnedWords(this));
    }

    /** Las mismas letras, sin contar tildes, mayusculas ni espacios. */
    static boolean sameLetters(String typed, String correction) {
        String a = Dictionary.deaccent(typed.toLowerCase(Locale.ROOT));
        String b = Dictionary.deaccent(correction.toLowerCase(Locale.ROOT)).replace(" ", "");
        return a.equals(b);
    }

    /**
     * El final de la palabra (o toda ella) se acaba de teclear, sin contar mayusculas: "nesecit" que ya estaba mas una
     * "o" tecleada cuenta; una palabra pegada, la que ya estaba en el campo o una correccion recien deshecha, no.
     */
    private boolean justTyped(String word) {
        if (word == null || word.isEmpty() || typedRun.length() == 0) return false;
        String run = typedRun.toString().toLowerCase(Locale.ROOT), w = word.toLowerCase(Locale.ROOT);
        return w.endsWith(run) || run.endsWith(w);
    }

    private void forgetChip() {
        chipWord = null;
        chipAnchor = null;
    }

    /** Recuerda la palabra recien puesta con un chip (con su espacio automatico detras). */
    private void rememberChip(InputConnection ic, String word) {
        CharSequence before = ic.getTextBeforeCursor(60, 0);
        chipWord = word;
        chipAnchor = before == null ? "" : before.toString();
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

    /**
     * El deslizado solo con la opcion activa y en campos de texto que no sean contrasenas ni correos: tambien en la barra
     * de direcciones y en los buscadores marcados sin sugerencias, donde escribe la palabra y ofrece sus alternativas sin
     * corregir ni aprender nada.
     */
    private void updateGlide() {
        if (view != null) view.setGlideEnabled(Prefs.glide(this) && glideAllowed && !privateField
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
        settleUndone(ic, true);                         // otra palabra tras la restaurada: el rechazo queda confirmado
        ic.beginBatchEdit();
        String cur = currentWord(ic);
        if (cur != null && !cur.isEmpty()) {
            if (!closeWord(ic, " ")) ic.commitText(" ", 1);
        } else {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before != null && before.length() == 1) {
                char c = before.charAt(0);
                boolean opens = "([{\u00bf\u00a1\u00ab\u201c".indexOf(c) >= 0 || (c == '"' && quoteOpen(ic));   // comilla recta que abre
                // En una direccion web la palabra va pegada a lo que no sea letra o cifra ("www." + google, "sitio.com/" + tacos)
                if (uriField ? Character.isLetterOrDigit(c) : c != ' ' && c != '\n' && !opens) ic.commitText(" ", 1);
                if (!uriField && ".!?\u203d".indexOf(c) >= 0 && Character.isLowerCase(best.charAt(0))) {
                    best = best.substring(0, 1).toUpperCase(Locale.ROOT) + best.substring(1);
                }
            }
        }
        ic.commitText(best + " ", 1);
        ic.endBatchEdit();
        lastCorrection = null;
        lastSpaceAt = 0;
        forgetChip();
        typedRun.setLength(0);
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
    private boolean applyCorrection(InputConnection ic, String suffix) { return applyCorrection(ic, suffix, false); }

    /**
     * Con {@code accentsOnly} (intro con accion: buscar, enviar, ir, siguiente, listo) solo se aplica la correccion que
     * deja las mismas letras ("cancion" -> "canción", "porfavor" -> "por favor"): ahi no hay ocasion de verla ni de
     * deshacerla, y una marca o un apodo no deben cambiarse justo al buscar o enviar.
     */
    private boolean applyCorrection(InputConnection ic, String suffix, boolean accentsOnly) {
        if (!autoReplace || lastWord == null || dict == null) return false;
        String cur = currentWord(ic);
        if (cur == null || !cur.equals(lastWord)) return false;
        if (!justTyped(cur)) return false;              // pegada, de antes o con la correccion ya rechazada: se respeta
        if (!lastWord.equals(computedWord)) {           // el resultado aun no llego: esperarlo un poco
            if (looksLikeAcronym(lastWord)) return false;
            if (!awaitSuggestion()) return false;
        }
        if (pendingCorrection == null) return false;
        if (accentsOnly && !sameLetters(lastWord, pendingCorrection)) return false;
        String fix = cased(lastWord, pendingCorrection);     // "Coruscant" aunque se escribiera "coruscnt"
        if (fix.equals(lastWord)) return false;
        String original = lastWord;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(original.length(), 0);
        ic.commitText(fix + suffix, 1);
        ic.endBatchEdit();
        lastCorrection = new String[]{original, fix, suffix};
        showCorrectionChip(ic, original);
        return true;
    }

    /**
     * Deshace la ultima autocorreccion (o atajo) si el texto previo al cursor sigue siendo el corregido: recupera la
     * palabra original con su separador. Deshacer la correccion cuenta como un uso de la palabra original (aunque
     * parezca una errata, el usuario la rechazo): tras {@link Prefs#learnUses} mensajes distintos se aprende.
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
        typedRun.setLength(0);                          // la correccion rechazada no vuelve al teclear otro separador
        String key = c[0].toLowerCase(Locale.ROOT);
        if (!shortcuts.containsKey(key)) {              // deshacer un atajo no dice nada sobre la palabra
            boolean asked = c[1].equalsIgnoreCase(interrogativeOf(key)) && endsWith(ic, c[0] + c[2], '\u00bf');
            undoneKey = asked ? "\u00bf" + key : key;   // el rechazo se confirma si la palabra se queda como esta
            undoneWord = c[0];
            if (!asked && mayLearn()) {
                if (learning.note(dict, c[0], Prefs.learnUses(this), true)) learnWord(c[0]);
                else if (dict != null && dict.inList(key) && learning.noteKept(c[0], Prefs.learnUses(this))) keepWord(c[0]);
            }
        }
        return true;
    }

    /** Se permite aprender: opcion activa, campo normal (ni contrasenas, correos o direcciones) y sin modo incognito. */
    private boolean mayLearn() {
        return learn && !incognito && suggestionsAllowed && !privateField;
    }

    /**
     * Palabra desconocida cerrada con un separador o un envio (sin correccion ni atajo): se anota como candidata y,
     * cuando lleva {@link Prefs#learnUses} usos en mensajes distintos (politica de {@link Learning}: nada de cifras,
     * siglas, direcciones ni erratas de palabras frecuentes), se aprende. Solo si se permite aprender.
     */
    private void noteCandidate(InputConnection ic) {
        if (dict == null || !mayLearn()) return;
        String cur = currentWord(ic);
        if (cur == null || cur.length() < Learning.MIN_LEN || gluedToSymbol(ic, cur)) return;
        if (!justTyped(cur)) return;                    // solo se aprende lo tecleado, no lo pegado ni lo que ya estaba
        if (shortcuts.containsKey(cur.toLowerCase(Locale.ROOT))) return;
        // Con el predictivo activo se exige el veredicto del diccionario para esta palabra: si el resultado no llego
        // (30 ms agotados, excepcion) o habia correccion y no se aplico (sustitucion desactivada), no se anota
        if (autocorrect && (!cur.equals(computedWord) || pendingCorrection != null)) return;
        // rechazada antes o nombre con mayuscula a proposito: cuenta aunque parezca una errata de otra palabra
        boolean force = rejected.contains(cur.toLowerCase(Locale.ROOT)) || namedOnPurpose(ic, cur);
        if (learning.note(dict, cur, Prefs.learnUses(this), force)) learnWord(cur);
    }

    /**
     * La palabra va pegada a un simbolo o una cifra ("usuario@", "www.", "3er", "#tag"): es parte de una direccion,
     * un correo o un codigo, no una palabra del usuario.
     */
    private static boolean gluedToSymbol(InputConnection ic, String word) {
        CharSequence b = ic.getTextBeforeCursor(word.length() + 1, 0);
        if (b == null || b.length() <= word.length()) return false;
        char p = b.charAt(0);
        return Character.isDigit(p) || "@./:_-#&=?%+\\|~*".indexOf(p) >= 0;
    }

    /**
     * Guarda una palabra que el usuario quiso conservar (si la opcion esta activa): bien formada y desconocida
     * ({@link Learning#eligible}). Es el unico aprendizaje inmediato, desde el chip de la palabra original.
     */
    private void learnWord(String w) {
        if (!mayLearn() || !Learning.eligible(dict, w)) return;
        Prefs.addLearnedWord(this, w.toLowerCase(Locale.ROOT));
        dict.setPersonal(Prefs.learnedWords(this));
    }

    private static boolean endsSentence(char c) {
        // tambien superindices, subindices y fracciones ("5 m²", "3½"): son numeros para Unicode, no letras ni digitos
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.OTHER_NUMBER || ")]}\"'»”’".indexOf(c) >= 0;
    }

    // ------------------------------------------------------------------ teclas

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean separator = text.length() == 1 && SEPARATORS.indexOf(text.charAt(0)) >= 0;
        boolean closer = !separator && text.length() == 1
                && (CLOSERS.indexOf(text.charAt(0)) >= 0 || (text.charAt(0) == '"' && quoteOpen(ic)));
        typeText(ic, text, separator, closer);
        if (separator || closer) {
            typedRun.setLength(0);
        } else {
            typedRun.append(text);
            if (typedRun.length() > TYPED_RUN_MAX) typedRun.delete(0, typedRun.length() - TYPED_RUN_MAX);
        }
    }

    /**
     * Hay unas comillas rectas abiertas: en el parrafo (hasta 200 caracteres atras) hay un numero impar de ellas antes
     * del cursor. La siguiente comilla recta las cierra; y si lo ultimo escrito es una comilla, es la que abre.
     */
    private static boolean quoteOpen(InputConnection ic) {
        return oddQuotes(ic.getTextBeforeCursor(200, 0));
    }

    static boolean oddQuotes(CharSequence before) {
        if (before == null) return false;
        int n = 0;
        for (int i = before.length() - 1; i >= 0 && before.charAt(i) != '\n'; i--) if (before.charAt(i) == '"') n++;
        return (n & 1) == 1;
    }

    private void typeText(InputConnection ic, String text, boolean separator, boolean closer) {
        long now = SystemClock.uptimeMillis();
        if (undoneKey != null) {
            // Otra palabra tras el separador, o un signo pegado a la palabra restaurada: se queda como esta (rechazo).
            // Una letra pegada a ella la esta cambiando: no lo era.
            CharSequence last = ic.getTextBeforeCursor(1, 0);
            boolean glued = last != null && last.length() == 1 && Character.isLetter(last.charAt(0));
            boolean letter = text.length() > 0 && Character.isLetter(text.charAt(0));
            settleUndone(ic, !(glued && letter));
        }
        // Espacio automatico de la palabra puesta con un chip: solo cuenta para esta tecla
        boolean chipSpace = false;
        if (chipWord != null) {
            CharSequence b = ic.getTextBeforeCursor(chipWord.length() + 1, 0);
            chipSpace = b != null && b.toString().equals(chipWord + " ");
            forgetChip();
        }
        if (" ".equals(text)) {
            if (glideWord != null && glideAutoSpace) {                      // el espacio automatico del deslizado ya esta
                CharSequence b = ic.getTextBeforeCursor(glideWord.length() + 1, 0);
                if (b != null && b.toString().equals(glideWord + " ")) {
                    clearGlide();
                    lastSpaceAt = now;
                    return;
                }
            }
            if (chipSpace) {                                                // y el del chip tambien
                lastSpaceAt = now;
                return;
            }
            // doble espacio = punto y espacio, solo en texto corriente: en una contrasena, un correo o una direccion
            // web cambiaria lo escrito sin que se vea
            if (doubleSpace && proseField && now - lastSpaceAt < DOUBLE_SPACE_MS) {
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
        // Un cierre con palabra delante la cierra como un separador ("(tambien)" -> "(también)"); suelto, es un signo mas
        boolean ends = separator;
        if (closer) {
            String cur = currentWord(ic);
            ends = cur != null && !cur.isEmpty();
        }
        if (ends) {
            if (expandShortcut(ic, text)) return;                         // atajo de texto: manda sobre la autocorreccion
            rememberPair(ic);
        }
        boolean sticks = (text.length() == 1 && (GLIDE_PUNCTUATION.indexOf(text.charAt(0)) >= 0 || closer))
                || (uriField && (text.startsWith(".") || text.startsWith("/")));   // ".com" y "/" tras la palabra deslizada
        if (glideWord != null) {                                          // la puntuacion absorbe el espacio del deslizado
            if (glideAutoSpace && sticks) {
                CharSequence before = ic.getTextBeforeCursor(glideWord.length() + 1, 0);
                if (before != null && before.toString().equals(glideWord + " ")) ic.deleteSurroundingText(1, 0);
            }
            clearGlide();
        }
        if (chipSpace && sticks) ic.deleteSurroundingText(1, 0);          // "mañana" + chip + coma = "mañana,"
        if (ends && applyCorrection(ic, text)) return;
        if (ends) noteCandidate(ic);                                      // palabra desconocida que se queda tal cual
        if (!ends) lastCorrection = null;
        ic.commitText(text, 1);
    }

    /** Aprende (solo en el telefono) el par palabra anterior + palabra actual al cerrarla con un separador. */
    private void rememberPair(InputConnection ic) {
        if (!learn || incognito || bigrams == null || !suggestionsAllowed || privateField) return;
        String cur = currentWord(ic);
        if (cur == null || cur.length() < 2 || looksLikeAcronym(cur) || !justTyped(cur)) return;
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
        lastDeleteAt = SystemClock.uptimeMillis();
        forgetChip();
        if (undoneKey != null && lastCorrection == null) {           // borrar otra vez: el separador se puede quitar; una letra, no
            CharSequence last = ic.getTextBeforeCursor(1, 0);
            if (last == null || last.length() == 0 || Character.isLetter(last.charAt(0))) settleUndone(ic, false);
        }
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
            typedRun.setLength(0);
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
            typedRun.setLength(0);
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
            typedRun.setLength(0);
        } else {
            ic.deleteSurroundingText(n, 0);
            typedRun.setLength(Math.max(0, typedRun.length() - n));       // lo tecleado pierde lo mismo que el texto
        }
    }

    @Override
    public void onEnter() {
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || info == null) return;
        lastSpaceAt = 0;
        forgetChip();
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendWord(ic);
            ic.performEditorAction(action);
        } else if (multiLine || info.inputType == InputType.TYPE_NULL) {
            onText("\n");
        } else {
            sendWord(ic);
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    /**
     * Intro o accion del editor (buscar, enviar, ir...): cierra la palabra en curso (atajo de texto, o candidata a
     * aprenderse) y abre otro mensaje para el diccionario personal. De la autocorreccion solo se aplica la que pone o
     * quita tildes: la accion se ejecuta en el acto y cualquier otro cambio no se podria ver ni deshacer.
     */
    private void sendWord(InputConnection ic) {
        settleUndone(ic, true);                         // enviar con la palabra restaurada intacta confirma el rechazo
        if (!expandShortcut(ic, "") && !applyCorrection(ic, "", true)) noteCandidate(ic);
        typedRun.setLength(0);
        learning.newMessage();
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
        undoneKey = null;
        forgetChip();
        typedRun.setLength(0);
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
        lastDeleteAt = SystemClock.uptimeMillis();
        forgetChip();
        typedRun.setLength(0);
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
        String placed = null;                           // palabra que queda escrita con su espacio automatico
        forgetChip();
        typedRun.setLength(0);
        boolean quoted = word.length() >= 2 && word.startsWith("\"") && word.endsWith("\"");
        settleUndone(ic, cur == null || cur.isEmpty());   // un chip sobre otra palabra confirma; sobre la restaurada, no
        if (!quoted && !AurebeshKeyboardView.isEmoji(word) && cur != null && !cur.isEmpty()) {
            rejected.remove(cur.toLowerCase(Locale.ROOT));    // aceptar una sugerencia para ella le devuelve la correccion
            rejected.remove("\u00bf" + cur.toLowerCase(Locale.ROOT));
        }
        ic.beginBatchEdit();
        if (word.length() >= 2 && word.startsWith("\"") && word.endsWith("\"")) {
            String original = word.substring(1, word.length() - 1);
            if (lastCorrection != null && original.equals(lastCorrection[0]) && revertCorrection(ic)) {
                learnWord(original);                    // el usuario deshizo la correccion a proposito: la palabra es suya
                String key = undoneKey;                 // con el chip el rechazo no espera confirmacion
                undoneKey = null;
                undoneWord = null;
                if (key != null) reject(key);
            } else {
                boolean asked = cur != null && pendingCorrection != null && cur.equals(lastWord)
                        && pendingCorrection.equals(interrogative(cur));
                ic.commitText(" ", 1);                  // conservar lo escrito, y aprenderlo
                if (cur != null && !cur.isEmpty()) {
                    placed = cur;
                    reject(asked ? "\u00bf" + cur.toLowerCase(Locale.ROOT) : cur.toLowerCase(Locale.ROOT));
                    if (!asked) {
                        learnWord(cur);                 // desconocida: al diccionario personal, en el acto
                        // de la lista ("papa" por "papá"): se guarda cuando el rechazo se repite en mensajes distintos
                        if (dict != null && mayLearn() && dict.inList(cur.toLowerCase(Locale.ROOT))
                                && learning.noteKept(cur, Prefs.learnUses(this))) keepWord(cur);
                    }
                }
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
            } else {
                // Prediccion tocada con el punto pegado delante ("Hola." + chip): primero el espacio que falta
                CharSequence b = ic.getTextBeforeCursor(1, 0);
                if (b != null && b.length() == 1 && ".!?\u203d".indexOf(b.charAt(0)) >= 0) ic.commitText(" ", 1);
                if (canon != null) word = canon;
                else if (autoCaps && capsAtCursor(ic)) word = capitalize(word);   // al inicio de frase: con mayuscula
            }
            // Elegida en medio del texto: si detras ya hay un espacio o un signo, no se anade otro espacio
            CharSequence after = ic.getTextAfterCursor(1, 0);
            boolean spaced = after != null && after.length() == 1 && " \n.,;:!?)]}\u00bb\u201d\"".indexOf(after.charAt(0)) >= 0;
            ic.commitText(spaced ? word : word + " ", 1);
            if (!spaced) placed = word;
        }
        ic.endBatchEdit();
        lastCorrection = null;
        lastSpaceAt = 0;
        clearSuggestions();
        if (placed != null) rememberChip(ic, placed);
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
        forgetChip();
        typedRun.setLength(0);
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
        forgetChip();
        if (actionId == android.R.id.cut || actionId == android.R.id.paste) {
            lastCorrection = null;
            lastSpaceAt = 0;
            typedRun.setLength(0);
            clearSuggestions();
        }
    }

    /** Inicio o fin de todo el texto (con control; sin el, Inicio/Fin solo van a la linea), extendiendo la seleccion si select. */
    @Override
    public void onEditHome(boolean end, boolean select) {
        sendKey(end ? KeyEvent.KEYCODE_MOVE_END : KeyEvent.KEYCODE_MOVE_HOME, select, true);
        lastCorrection = null;
        lastSpaceAt = 0;
        forgetChip();
        typedRun.setLength(0);
        clearSuggestions();
    }

    /** Tecla de borrar del panel de edicion: la seleccion si la hay, o el caracter anterior (sin pasar por el portapapeles). */
    @Override
    public void onEditDelete() { onDelete(); }

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
        forgetChip();
        typedRun.setLength(0);
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
        forgetChip();
        typedRun.setLength(0);
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
        forgetChip();
        typedRun.setLength(0);
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
        forgetChip();
        typedRun.setLength(0);
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
            forgetChip();
            typedRun.setLength(0);
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
