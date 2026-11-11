package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Vista del teclado: distribucion, dibujo con temas, gestion del toque y escritura deslizando el dedo. */
public final class AurebeshKeyboardView extends View {

    interface Listener {
        void onText(String text);
        void onDelete();
        void onEnter();
        void onSwitchKeyboard();        // selector de teclados del sistema (mantener el espacio)
        void onSwitchLanguage();        // globo: siguiente idioma Aurebesh
        void onSuggestion(String word); // toque en una sugerencia de la barra
        void onToolbar(int tool);       // icono de la barra de herramientas (TOOL_*)
        void onHandSide(int mode);      // flecha del modo a una mano: 1 izquierda, 2 derecha
        void onPasteSuggestion();       // chip "Pegar" de lo recien copiado
        /**
         * Palabra reconocida al deslizar el dedo por las letras (ya con mayusculas segun shift/bloqueo) y
         * alternativas para la barra, de mejor a peor (puede estar vacia). Llega en el hilo principal.
         */
        void onGlide(String best, List<String> alternatives);
        /** Deslizar sobre la barra espaciadora en modo cursor: pasos (negativo = izquierda); select = extender la seleccion. */
        void onCursorMove(int steps, boolean select);
        /** Mantener borrar mas de ~2 s: borrar la palabra anterior entera. */
        void onDeleteWord();
        /** Boton ∨ de la banda inferior: ocultar el teclado. */
        void onHide();
        /** Fin del modo de ajuste de tamano: alto en % (70-140) y margen inferior en dp (0-40) elegidos con los tiradores. */
        void onResize(int heightPct, int bottomDp);
    }

    static final int CODE_TEXT = 0;
    static final int CODE_SHIFT = -1;
    static final int CODE_MODE = -2;
    static final int CODE_PAGE = -3;      // 1/2 <-> 2/2 en la pagina de signos
    static final int CODE_GAP = -10;      // hueco central del teclado dividido (no se dibuja ni se toca)
    static final int CODE_CANCELLED = -11; // marcador: el dedo se alejo de las teclas (o del menu); al soltar no se escribe nada
    static final int TOOL_RESIZE = 6;     // icono "Tamano y posicion" (modo de ajuste con tiradores), en la cinta de ?123
    /** Deslizar sobre el espacio: nada, mover el cursor o cambiar de idioma. */
    static final int SPACE_NONE = 0, SPACE_CURSOR = 1, SPACE_LANG = 2;
    /** Variante del campo: ninguna, correo (@ y .com) o direccion web (/ y .com). */
    static final int FIELD_NONE = 0, FIELD_EMAIL = 1, FIELD_URL = 2;
    static final int CODE_ENTER = -4;
    static final int CODE_DELETE = -5;
    static final int CODE_SPACE = -6;
    static final int CODE_GLOBE = -7;

    // Barra de herramientas (como la de Samsung Keyboard): a la derecha de las sugerencias, emojis y portapapeles
    static final int TOOL_EMOJI = 1;
    static final int TOOL_CLIPBOARD = 2;
    static final int TOOL_SETTINGS = 3;     // solo en la pagina ?123
    static final int TOOL_HAND = 4;         // modo a una mano (junto al portapapeles)
    static final int TOOL_AI = 5;           // inteligencia, a la izquierda de la barra (boton propio, no va en la cinta)
    // 2.0: herramientas nuevas; la cinta es personalizable (Prefs.TOOLBAR_ITEMS) y termina siempre en "..."
    static final int TOOL_EDIT = 8;         // panel de edicion: cursor, seleccion, copiar y pegar
    static final int TOOL_SPLIT = 9;        // dividir el teclado (solo en pantallas de 600 dp o mas)
    static final int TOOL_HIDE = 10;        // ocultar el teclado
    static final int TOOL_STRIP = 11;       // cinta de lo escrito en Aurebesh
    static final int TOOL_LANG = 12;        // siguiente idioma
    static final int TOOL_TRANSLATE = 13;   // traducir con la inteligencia
    static final int TOOL_MORE = 14;        // "...": cuadricula con todas las herramientas
    /** Catalogo de herramientas que pueden ir en la cinta, en el orden de la cuadricula "...". */
    static final int[] ALL_TOOLS = {TOOL_EMOJI, TOOL_CLIPBOARD, TOOL_HAND, TOOL_EDIT, TOOL_TRANSLATE, TOOL_SPLIT,
            TOOL_STRIP, TOOL_LANG, TOOL_HIDE, TOOL_RESIZE, TOOL_SETTINGS};
    /** Cinta de fabrica (sin el "..." final, que se anade siempre). */
    static final int[] DEFAULT_TOOLBAR = {TOOL_EMOJI, TOOL_CLIPBOARD, TOOL_HAND, TOOL_EDIT, TOOL_SETTINGS};   // cinco: caben en un telefono junto al "..."

    static final class Key {
        final String output;
        final String glyph;
        final String label;
        final String[] alts;      // alternativas por pulsacion larga; la primera es la predeterminada
        final int code;
        final float weight;
        final RectF rect = new RectF();

        Key(String output, String glyph, String label, String[] alts, int code, float weight) {
            this.output = output; this.glyph = glyph; this.label = label;
            this.alts = alts == null || alts.length == 0 ? null : alts; this.code = code; this.weight = weight;
        }

        Key withFirstAlt(String first) {
            String[] a = new String[(alts == null ? 0 : alts.length) + 1];
            a[0] = first;
            if (alts != null) System.arraycopy(alts, 0, a, 1, alts.length);
            return new Key(output, glyph, label, a, code, weight);
        }
    }

    /** Tecla ficticia que sustituye a la pulsada cuando el dedo se aleja: al soltar no se escribe nada. */
    private static final Key CANCELLED = new Key(null, null, "", null, CODE_CANCELLED, 0f);

    /** Tipos de respuesta haptica de {@link #haptic(int)}. */
    private static final int H_PRESS = 0, H_LONG = 1, H_TICK = 2, H_CONFIRM = 3, H_REJECT = 4;
    /** Constantes de HapticFeedbackConstants que faltan en android.jar antiguos: KEYBOARD_PRESS (27), CONFIRM y REJECT (30). */
    private static final int HFC_KEYBOARD_PRESS = 3, HFC_CONFIRM = 16, HFC_REJECT = 17;

    /** Alternativas de cada letra (acentos y variantes); la n~ y la c con cedilla incluidas. */
    private static final java.util.Map<String, String[]> LETTER_ALTS = new java.util.HashMap<>();
    static {
        LETTER_ALTS.put("a", new String[]{"\u00e1", "\u00e0", "\u00e4", "\u00e2", "\u00e3", "\u00e5", "\u00e6"});
        LETTER_ALTS.put("e", new String[]{"\u00e9", "\u00e8", "\u00eb", "\u00ea"});
        LETTER_ALTS.put("i", new String[]{"\u00ed", "\u00ec", "\u00ef", "\u00ee"});
        LETTER_ALTS.put("o", new String[]{"\u00f3", "\u00f2", "\u00f6", "\u00f4", "\u00f5", "\u00f8", "\u0153"});
        LETTER_ALTS.put("u", new String[]{"\u00fa", "\u00f9", "\u00fc", "\u00fb"});
        LETTER_ALTS.put("n", new String[]{"\u00f1"});
        LETTER_ALTS.put("c", new String[]{"\u00e7"});
        LETTER_ALTS.put("s", new String[]{"\u00df"});
        LETTER_ALTS.put("y", new String[]{"\u00fd", "\u00ff"});
    }

    private Listener listener;
    private final List<List<Key>> letters = new ArrayList<>();
    private final List<List<Key>> symbols = new ArrayList<>();    // signos 1/2 (como Samsung Keyboard)
    private final List<List<Key>> symbols2 = new ArrayList<>();   // signos 2/3: digrafos, ¿ ¡ « » y mas simbolos
    private final List<List<Key>> symbols3 = new ArrayList<>();   // letras con acento 3/3: a a a a a... c e e e e... u u u u ß
    private final List<List<Key>> numpad = new ArrayList<>();     // teclado numerico 3x4 (numero, decimal, con signo o telefono)
    private List<List<Key>> current;

    private boolean shift = false;
    private boolean capsLock = false;
    private long lastShiftTap = 0;
    private boolean autoShifted = false;
    private int shiftPointer = -1;          // dedo que mantiene pulsada la tecla de mayusculas (shift sostenido)
    private boolean shiftHeldTyped = false; // se escribio alguna letra mientras se mantenia: al soltar no conmuta

    // Deslizar sobre la barra espaciadora (mover el cursor o cambiar de idioma)
    private float spaceDownX;               // x donde se poso el dedo en el espacio
    private int spaceSteps;                 // pasos de cursor ya enviados en este gesto (negativo = izquierda)
    private boolean spaceDragged;           // hubo gesto: al soltar no se escribe el espacio
    private boolean spaceLangFired;         // ya se cambio de idioma en este gesto
    private long spaceFlashAt;              // instante del cambio de idioma (destello de los chevrones)
    private int spaceFlashDir;              // sentido del gesto que cambio de idioma: -1 izquierda, 1 derecha

    // Teclado numerico: variante pedida por el campo
    private boolean numpadPhone, numpadDecimal, numpadSigned;
    private int repeatCount;                // repeticiones de borrar en la pulsacion actual (acelera y pasa a palabras)

    private boolean showHints = false;      // alguna pista latina (modo 1 o 2)
    private int hintMode = 0;               // 0 nunca, 1 siempre, 2 aprendiendo (cada letra se desvanece con el uso)
    private boolean hintModeSet = false;    // setHintMode ya mando: applyPrefs deja de tocar el modo
    private final java.util.HashMap<String, Integer> hintCounts = new java.util.HashMap<>();   // veces pulsada cada letra
    private int hintSinceSave = 0;          // pulsaciones desde el ultimo guardado en Prefs.HINT_COUNTS
    private boolean hintDirty = false;
    /** Modo aprendiendo: pulsaciones de una letra hasta que su pista latina desaparece, y cada cuantas se guarda. */
    static final int HINT_FADE_TAPS = 40;
    private static final int HINT_SAVE_EVERY = 25;
    private boolean vibrate = true;
    private boolean showStrip = false;
    private boolean useDigraphs = true;
    private boolean numberRow = true;
    private boolean sound = true;
    private float heightScale = 1f;

    // Menu de alternativas abierto por pulsacion larga
    private Key popupKey;
    private String[] popupAlts;
    private int popupIndex;
    private final RectF popupRect = new RectF();
    private float popupCell;
    private float popupCellH;               // alto de cada fila del menu
    private int popupCols = 1, popupRows = 1; // si no caben en el ancho, dos filas; la fila 0 es la de abajo (pegada al dedo)
    private String language = "es";       // "es": con tecla n~ y , -> ?; "en": sin tecla n~ y , -> !; "de": QWERTZ con u" o" a" y , -> ;
    private String spaceLabel;             // nombre del idioma cuando hay mas de uno activo
    private boolean languageSwitch = false; // tecla del globo (solo con varios idiomas Aurebesh activos)
    private boolean suggestionBar = false;  // barra de sugerencias (autocorreccion)
    private int handMode = 0;               // 0 normal, 1 izquierda, 2 derecha
    private int splitMode = 0;              // teclado dividido: 0 automatico (pantalla ancha), 1 siempre, 2 nunca
    private boolean split = false;          // estado actual del dividido (dos mitades con hueco, como en los Fold)
    private final List<String> suggestions = new ArrayList<>();
    private final List<RectF> suggestionRects = new ArrayList<>();
    private final List<String> suggestionWords = new ArrayList<>();   // palabra de cada chip dibujado (puede saltarse alguno)
    private String highlighted;             // sugerencia que se aplicara sola (resaltada)
    private boolean toolbar = true;         // iconos sobre las teclas cuando no hay sugerencias
    private final RectF[] toolRects = new RectF[ALL_TOOLS.length + 1];   // iconos dibujados y el "..." (vacio = no esta)
    private final int[] toolRectIds = new int[ALL_TOOLS.length + 1];     // herramienta de cada rectangulo
    private int[] toolbarItems;                                          // orden elegido por el usuario (null = DEFAULT_TOOLBAR)
    private final int[] toolsBuf = new int[ALL_TOOLS.length];            // herramientas con sentido ahora (sin asignar por fotograma)
    private final RectF suggestionBand = new RectF();                    // banda unica de las sugerencias
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // lineas entre sugerencias
    private int toolDownId = -1;                 // herramienta bajo el dedo al bajar (se resuelve al soltar)
    private final RectF toolDownRect = new RectF();
    private final RectF hideRect = new RectF();     // boton ∨ de la banda inferior (margen >= 24 dp)
    private final RectF sideRect = new RectF();     // flecha para cambiar de lado en modo a una mano
    private final RectF moreRect = new RectF();     // "..." que muestra los iconos sobre las sugerencias
    private final RectF aiRect = new RectF();       // boton de inteligencia a la izquierda de la barra
    private float stripLeft;                        // donde empiezan los chips (tras el boton de inteligencia)
    private boolean toolsOverChips = false;
    private final RectF pasteRect = new RectF();    // chip "Pegar" de lo recien copiado
    private Bitmap pasteThumb;
    private String pasteLabel;
    private String pasteAction = "Pegar";
    private int toolPressed = -1;
    private int toolPointer = -1;
    private boolean previewEnabled = true;
    private List<Glyphs.Token> stripTokens = new ArrayList<>();
    private Themes.Theme theme = Themes.DEFAULT;
    private CharSequence stripText = "";
    private int enterAction = 0;   // 0 = intro, 1 = buscar, 2 = enviar/ir, 3 = siguiente, 4 = listo

    private final float dp;
    private final float sp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path iconPath = new Path();

    private final SparseArray<Key> pressed = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Key longPressKey;
    private boolean longPressFired;
    private Key repeatKey;

    // ---- Escritura deslizando (glide). Constantes de ajuste del gesto y de la estela, en un solo sitio.
    /** Distancia desde el punto inicial, en anchos de tecla, a la que un toque pasa a ser trazo. */
    static final float GLIDE_SLOP_KEYS = 0.9f;
    /** Velocidad minima (dp/ms) al cruzar esa distancia; mas despacio es deslizar a la tecla vecina. */
    static final float GLIDE_MIN_SPEED = 0.10f;
    /** Si no se cruza la distancia en este tiempo (ms) desde el toque, es un toque para siempre. */
    static final long GLIDE_DETECT_WINDOW = 500;
    /** Longitud minima del trazo al soltar, en anchos de tecla; menos se trata como toque. */
    static final float GLIDE_MIN_LEN_KEYS = 1.2f;
    /** Los puntos a menos de esta distancia (dp) del anterior guardado se descartan. */
    static final float POINT_MIN_DIST_DP = 2f;
    /** Puntos maximos guardados; al llegar se diezma por dos. */
    static final int MAX_POINTS = 2048;
    /** Tiempo (ms) que tarda en desvanecerse cada tramo de la estela, y grosor maximo (dp) de la hoja del sable. */
    static final long TRAIL_MS = 600;
    static final float TRAIL_WIDTH_DP = 6.5f;
    /** Grosores (dp) del nucleo blanco y de los dos halos translucidos de la estela. */
    static final float TRAIL_CORE_DP = 2.5f, TRAIL_HALO_DP = 20f, TRAIL_OUTER_DP = 30f;
    /** Tramos por edad en que se parte la estela para desvanecer la cola. */
    static final int TRAIL_BANDS = 10;

    private static final int GLIDE_NONE = 0, GLIDE_PENDING = 1, GLIDE_GLIDING = 2;
    private boolean glideEnabled = false;
    private boolean glideTrail = true;
    private int glideState = GLIDE_NONE;
    private int glidePointer = -1;
    private Key glideDownKey;
    private float glideDownX, glideDownY;
    private long glideDownT;
    private float[] strokeX = new float[256], strokeY = new float[256];
    private long[] strokeT = new long[256];
    private int strokeN = 0;
    // Estela de sable de luz: nucleo blanco, hoja del color elegido y dos halos translucidos, en tramos por edad
    private int saberColor = Prefs.SABER_ARGB[0];
    private final Paint trailCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailBladePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailFlashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] trailPaths = new Path[TRAIL_BANDS];          // una Path por tramo, reutilizadas en cada fotograma
    private final boolean[] trailBandUsed = new boolean[TRAIL_BANDS];
    private BlurMaskFilter trailBlur;                                 // halo difuminado, solo sin aceleracion por hardware
    private volatile SwipeDecoder decoder;          // indice del idioma y la geometria actuales (se rehace si cambian)
    private Future<?> glideTask;                    // ultima tarea de reconocimiento (para pruebas)
    private static ExecutorService glideExecutor;

    public AurebeshKeyboardView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        glyphPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeWidth(2.5f * dp);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp);
        for (Paint p : new Paint[]{trailCorePaint, trailBladePaint, trailHaloPaint, trailOuterPaint}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.BUTT);          // los tramos empalman en los puntos medios: sin remates solapados
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        for (int i = 0; i < TRAIL_BANDS; i++) trailPaths[i] = new Path();
        try { trailBlur = new BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL); } catch (RuntimeException e) { trailBlur = null; }
        for (int i = 0; i < toolRects.length; i++) toolRects[i] = new RectF();
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(dp);
        compact = heightClass() == 0;      // pantalla muy baja: sin fila de numeros desde el principio
        buildLayouts();
        current = letters;
        applyTheme();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);   // nodos virtuales para el lector de pantalla
    }

    void setListener(Listener l) { listener = l; }

    void applyPrefs(boolean hints, boolean vibrate, boolean strip, boolean digraphs, String themeId) {
        applyPrefs(hints, vibrate, strip, digraphs, themeId, true);
    }

    void applyPrefs(boolean hints, boolean vibrate, boolean strip, boolean digraphs, String themeId, boolean numbers) {
        applyPrefs(hints, vibrate, strip, digraphs, themeId, numbers, true, 1f);
    }

    void applyPrefs(boolean hints, boolean vibrate, boolean strip, boolean digraphs, String themeId, boolean numbers,
                    boolean sound, float heightScale) {
        this.sound = sound;
        if (this.heightScale != heightScale) {
            this.heightScale = heightScale;
            requestLayout();
        }
        if (!hintModeSet) { hintMode = hints ? 1 : 0; showHints = hints; }   // hasta que setHintMode mande
        this.vibrate = vibrate;
        if (useDigraphs != digraphs) {
            useDigraphs = digraphs;
            stripTokens = Glyphs.tokenize(stripText.toString().replace('\n', ' '), useDigraphs);
        }
        theme = Themes.byId(themeId);
        applyTheme();
        if (numberRow != numbers) {
            numberRow = numbers;
            rebuildLayouts();
        }
        if (showStrip != strip) {
            showStrip = strip;
            requestLayout();
        }
        invalidate();
    }

    /** Sin burbuja de vista previa (campos de contrasena). */
    void setPreviewEnabled(boolean on) { previewEnabled = on; }

    // ------------------------------------------------------------------ 1.10: contrato (los cuerpos se completan por partes)

    /** Que hace deslizar el dedo sobre la barra espaciadora (SPACE_NONE, SPACE_CURSOR, SPACE_LANG). */
    void setSpaceSlide(int mode) { spaceSlide = mode; }
    private int spaceSlide = SPACE_CURSOR;

    /** Retardo de la pulsacion larga en ms (el espacio suma 220; repetir borrar suma 40). */
    void setLongPressMs(int ms) { longPressMs = Math.max(150, Math.min(900, ms)); }
    private int longPressMs = 380;

    /** Nivel de vibracion: 0 respuesta del sistema, 1 suave, 2 media, 3 fuerte. */
    void setHaptics(int level) { hapticsLevel = level; }
    private int hapticsLevel = 0;

    /** Margen inferior bajo la ultima fila, en px; con >= 24 dp se dibuja ahi el boton ∨ de ocultar. */
    void setBottomPad(float px) { if (bottomPad != px) { bottomPad = px; requestLayout(); invalidate(); a11yChanged(); } }
    private float bottomPad = 0f;

    /** Fraccion del ancho de cada mitad del teclado dividido (0,33 / 0,375 / 0,42). */
    void setSplitBox(float fraction) { if (splitBox != fraction) { splitBox = fraction; requestLayout(); invalidate(); } }
    private float splitBox = 0.375f;

    /** Teclas especiales segun el campo: FIELD_EMAIL anade @ y .com, FIELD_URL anade / y .com; FIELD_NONE lo normal. */
    void setFieldVariant(int variant) { if (fieldVariant != variant) { fieldVariant = variant; rebuildLayouts(); } }
    private int fieldVariant = FIELD_NONE;

    /**
     * Teclado numerico 3x4 con glifos Aurebesh para campos de numero, telefono y PIN (el IME lo abre en
     * onStartInputView en vez de showSymbols). {@code phone}: + * # y pausa; {@code decimal}: separador
     * decimal del idioma; {@code signed}: teclas − y +. Sin deslizado, sin dividir y con la cinta de ?123.
     */
    void showNumpad(boolean phone, boolean decimal, boolean signed) {
        numpadPhone = phone;
        numpadDecimal = decimal;
        numpadSigned = signed;
        numpad.clear();
        buildNumpad();
        current = numpad;
        capsLock = false;
        shift = false;
        clearTouchState();
        pressed.clear();
        resetGlide(true);
        requestLayout();
        invalidate();
        a11yChanged();
    }

    boolean isNumpad() { return current == numpad && !numpad.isEmpty(); }

    /**
     * Modo de ajuste de tamano y posicion con tiradores (TOOL_RESIZE): velo sobre las teclas, marco con un
     * tirador arriba (alto, 70-140 %) y otro abajo (margen inferior, 0-40 dp) y botones Listo y Restablecer.
     * Listo llama a onResize y sale; apagarlo desde fuera (o al desaparecer la vista) devuelve lo que habia al entrar.
     */
    void setResizeMode(boolean on) {
        if (resizeMode == on) return;
        resizeMode = on;
        resizeDrag = 0;
        resizePointer = -1;
        resizePressed = 0;
        if (on) {                                   // nada a medias al entrar: ni menu, ni repeticion, ni trazo
            clearTouchState();
            resizeEntryScale = heightScale;
            resizeEntryPad = bottomPad;
        } else {
            clearResizeRects();
            if (heightScale != resizeEntryScale || bottomPad != resizeEntryPad) {
                heightScale = resizeEntryScale;
                bottomPad = resizeEntryPad;
                requestLayout();
            }
        }
        invalidate();
    }
    boolean isResizeMode() { return resizeMode; }
    private boolean resizeMode = false;
    private int resizeDrag = 0;                 // 0 nada, 1 tirador superior (alto), 2 tirador inferior (margen)
    private int resizePointer = -1;             // dedo que arrastra o que tiene pulsado un boton
    private int resizePressed = 0;              // 1 Listo, 2 Restablecer (pulsado)
    private float resizeDragY0;                 // y en pantalla donde empezo el arrastre
    private float resizeLastY;                  // y en pantalla de la ultima aplicacion (se aplica cada ~4 dp)
    private float resizeScale0, resizePad0;     // alto y margen al empezar el arrastre
    private float resizeEntryScale = 1f, resizeEntryPad = 0f;   // alto y margen al entrar (se restauran si se cancela)
    private final RectF resizeTopRect = new RectF(), resizeBottomRect = new RectF();
    private final RectF resizeDoneRect = new RectF(), resizeResetRect = new RectF();
    private final int[] screenLoc = new int[2];

    /** Alto actual en % (70-140), derivado de heightScale. */
    int heightPct() { return Math.round(heightScale * 100f); }

    /** Alto en % (70-140); equivale al heightScale de applyPrefs, que se sigue aceptando. */
    void setHeightPct(int pct) {
        float s = Math.max(70, Math.min(140, pct)) / 100f;
        if (s == heightScale) return;
        heightScale = s;
        requestLayout();
        invalidate();
    }

    /** Margen inferior actual en dp enteros (0-40). */
    int bottomDp() { return Math.round(bottomPad / dp); }

    /** Zonas del modo de ajuste (para pruebas): tirador superior o inferior; vacias fuera del modo o sin dibujar. */
    RectF resizeHandleRect(boolean top) { return new RectF(top ? resizeTopRect : resizeBottomRect); }

    /** Botones del modo de ajuste (para pruebas): Listo o Restablecer; vacios fuera del modo o sin dibujar. */
    RectF resizeButtonRect(boolean done) { return new RectF(done ? resizeDoneRect : resizeResetRect); }

    /** Boton ∨ de la banda inferior (para pruebas); vacio si el margen no llega a 24 dp o aun no se dibujo. */
    RectF hideButtonRect() { return new RectF(hideRect); }

    private void clearResizeRects() {
        resizeTopRect.setEmpty();
        resizeBottomRect.setEmpty();
        resizeDoneRect.setEmpty();
        resizeResetRect.setEmpty();
    }

    /**
     * Escritura deslizando el dedo por las letras (solo en la pagina de letras; los toques siguen igual).
     * El reconocimiento corre en un hilo aparte y llega por {@link Listener#onGlide}.
     */
    public void setGlideEnabled(boolean on) {
        glideEnabled = on;
        if (!on && glideState == GLIDE_PENDING) resetGlide(true);
        if (on) warmGlide();
    }

    /** Estela del dedo mientras se desliza (por defecto si). */
    public void setGlideTrail(boolean on) {
        glideTrail = on;
        invalidate();
    }

    boolean isGlideEnabled() { return glideEnabled; }

    /** Color de la hoja del sable de la estela (ARGB; el nucleo siempre es blanco). Cambia en vivo. */
    public void setSaber(int argb) {
        saberColor = argb | 0xFF000000;
        invalidate();
    }

    int saber() { return saberColor; }

    // ------------------------------------------------------------------ letras latinas: nunca, siempre, aprendiendo

    /**
     * Letras latinas en las teclas: 0 nunca, 1 siempre, 2 aprendiendo (cada letra se desvanece con las veces que
     * se pulsa: alfa = 1 - veces/40, y a las 40 desaparece). Carga los contadores de Prefs.HINT_COUNTS
     * ("letra n" por linea); se guardan cada 25 pulsaciones y al desaparecer la vista.
     */
    public void setHintMode(int mode) {
        hintModeSet = true;
        hintMode = Math.max(0, Math.min(2, mode));
        showHints = hintMode != 0;
        if (hintDirty) saveHintCounts();
        loadHintCounts();
        invalidate();
    }

    int hintMode() { return hintMode; }

    /** Opacidad (0-1) de la pista latina de una letra segun el modo; en aprendiendo, 1 - min(1, veces/40). */
    float hintAlpha(String letter) {
        if (hintMode == 0) return 0f;
        if (hintMode == 1) return 1f;
        return 1f - Math.min(1f, hintCount(letter) / (float) HINT_FADE_TAPS);
    }

    /** Veces que se ha pulsado la letra en modo aprendiendo (para pruebas). */
    int hintCount(String letter) {
        Integer n = hintCounts.get(letter);
        return n == null ? 0 : n;
    }

    /** Vuelve a mostrar todas las pistas: borra los contadores en memoria y en preferencias. */
    public void resetHintCounts() {
        hintCounts.clear();
        hintSinceSave = 0;
        hintDirty = false;
        Prefs.get(getContext()).edit().remove(Prefs.HINT_COUNTS).apply();
        invalidate();
    }

    /** Una pulsacion mas de la letra (solo cuenta en modo aprendiendo y hasta el tope, que es cuando ya no se ve). */
    private void countHint(String letter) {
        if (hintMode != 2 || letter == null) return;
        int n = hintCount(letter);
        if (n >= HINT_FADE_TAPS) return;
        hintCounts.put(letter, n + 1);
        hintDirty = true;
        if (++hintSinceSave >= HINT_SAVE_EVERY) saveHintCounts();
    }

    private void loadHintCounts() {
        hintCounts.clear();
        hintSinceSave = 0;
        hintDirty = false;
        String raw = Prefs.get(getContext()).getString(Prefs.HINT_COUNTS, null);
        if (raw == null) return;
        for (String line : raw.split("\n")) {
            int cut = line.indexOf(' ');
            if (cut <= 0) continue;
            try {
                hintCounts.put(line.substring(0, cut), Integer.parseInt(line.substring(cut + 1).trim()));
            } catch (NumberFormatException ignored) {
                // linea corrupta: se ignora
            }
        }
    }

    private void saveHintCounts() {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : hintCounts.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append(' ').append(e.getValue());
        }
        Prefs.get(getContext()).edit().putString(Prefs.HINT_COUNTS, b.toString()).apply();
        hintSinceSave = 0;
        hintDirty = false;
    }

    /** Color con su alfa multiplicado por {@code a} (0-1). */
    private static int fade(int color, float a) {
        return (color & 0x00FFFFFF) | (((int) ((color >>> 24) * a)) << 24);
    }

    // ------------------------------------------------------------------ cinta personalizable

    /** Iconos de la cinta en orden (ids TOOL_*), como vienen de Prefs.toolbarItems; null = cinta de fabrica. */
    void setToolbarItems(int[] ids) {
        toolbarItems = ids == null ? null : ids.clone();
        invalidate();
        a11yChanged();
    }

    /** Cinta actual (copia): el orden elegido o DEFAULT_TOOLBAR, sin filtrar y sin el "..." final. */
    int[] toolbarItems() { return (toolbarItems == null ? DEFAULT_TOOLBAR : toolbarItems).clone(); }

    /** Para cada herramienta de ALL_TOOLS, si tiene sentido ahora (para la cuadricula "..."). */
    boolean[] availableTools() {
        boolean[] out = new boolean[ALL_TOOLS.length];
        for (int i = 0; i < ALL_TOOLS.length; i++) out[i] = toolAllowed(ALL_TOOLS[i]);
        return out;
    }

    /** Dividir solo en pantallas de 600 dp o mas, una mano nunca con el dividido activo, idioma solo con varios; tamano y ajustes siempre. */
    private boolean toolAllowed(int id) {
        switch (id) {
            case TOOL_SPLIT: return getResources().getConfiguration().smallestScreenWidthDp >= 600;
            case TOOL_HAND: return !split;
            case TOOL_LANG: return languageSwitch;
            case TOOL_EMOJI: case TOOL_CLIPBOARD: case TOOL_EDIT: case TOOL_TRANSLATE: case TOOL_STRIP:
            case TOOL_HIDE: case TOOL_RESIZE: case TOOL_SETTINGS:
                return true;
            default:
                return false;        // la inteligencia tiene su propio boton; "..." se anade aparte; ids desconocidos se ignoran
        }
    }

    /** Rellena toolsBuf con la cinta elegida filtrada y sin repetidos; devuelve cuantas hay (el "..." no cuenta). */
    private int computeTools() {
        int[] items = toolbarItems == null ? DEFAULT_TOOLBAR : toolbarItems;
        int n = 0;
        for (int id : items) {
            if (!toolAllowed(id) || n >= toolsBuf.length) continue;
            boolean dup = false;
            for (int j = 0; j < n; j++) dup |= toolsBuf[j] == id;
            if (!dup) toolsBuf[n++] = id;
        }
        return n;
    }

    /** Zonas de toque de las sugerencias dibujadas (copia, para pruebas): celdas contiguas de la banda. */
    List<RectF> suggestionRects() {
        List<RectF> out = new ArrayList<>(suggestionRects.size());
        for (RectF r : suggestionRects) out.add(new RectF(r));
        return out;
    }

    /** Banda de las sugerencias tal y como se dibujo (copia, para pruebas); vacia si no hay sugerencias. */
    RectF suggestionBandRect() { return new RectF(suggestionBand); }

    /** Abre la pantalla ?123 (campos numericos y de telefono). */
    void showSymbols() {
        current = symbols;
        requestLayout();
        invalidate();
        a11yChanged();
    }

    private void applyTheme() {
        setBackgroundColor(theme.bg);
        glyphPaint.setColor(theme.glyph);
        iconPaint.setColor(theme.icon);
        textPaint.setColor(theme.glyph);
        hintPaint.setColor(theme.hint);
        glowPaint.setColor((theme.glow & 0x00FFFFFF) | 0x40000000);
        strokePaint.setColor(theme.keyStroke);
    }

    void setEnterAction(int action) { enterAction = action; invalidate(); a11yChanged(); }

    /** Distribucion por idioma ("es", "en" o "de"); reconstruye las teclas si cambia. */
    void setLanguage(String lang) {
        String l = "en".equals(lang) ? "en" : "de".equals(lang) ? "de" : "es";
        if (l.equals(language)) return;
        language = l;
        rebuildLayouts();
        warmGlide();
    }

    /** Tecla del globo visible o no; reconstruye la fila inferior. */
    void setLanguageSwitch(boolean on) {
        if (languageSwitch == on) return;
        languageSwitch = on;
        rebuildLayouts();
    }

    void setSuggestionBar(boolean on) {
        if (suggestionBar == on) return;
        suggestionBar = on;
        requestLayout();
        invalidate();
    }

    void setSuggestions(List<String> words) { setSuggestions(words, null); }

    /** Chips de la barra; {@code highlight} es la que se aplicara sola al teclear un separador. */
    void setSuggestions(List<String> words, String highlight) {
        suggestions.clear();
        if (words != null) suggestions.addAll(words);
        if (suggestions.isEmpty() && !hasPasteSuggestion()) toolsOverChips = false;
        highlighted = highlight;
        invalidate();
        a11yChanged();
    }

    /** Chip "Pegar" con lo recien copiado (miniatura o fragmento de texto); null lo quita. */
    void setPasteSuggestion(Bitmap thumb, String label, String action) {
        pasteThumb = thumb;
        pasteLabel = label;
        if (action != null) pasteAction = action;
        if (!hasPasteSuggestion() && suggestions.isEmpty()) toolsOverChips = false;
        invalidate();
        a11yChanged();
    }

    boolean hasPasteSuggestion() { return pasteThumb != null || pasteLabel != null; }

    /** Barra de herramientas (emojis y portapapeles) a la derecha de las sugerencias. */
    void setToolbar(boolean on) {
        if (toolbar == on) return;
        toolbar = on;
        requestLayout();
        invalidate();
        a11yChanged();
    }

    /** Teclado dividido en dos mitades (Galaxy Fold desplegado): 0 automatico, 1 siempre, 2 nunca. */
    void setSplitMode(int mode) {
        if (splitMode == mode) return;
        splitMode = mode;
        if (!updateSplit()) { requestLayout(); invalidate(); }
    }

    boolean isSplit() { return split; }

    /**
     * Recalcula si toca dividir y reconstruye las filas si cambia. "Automatico" divide solo con un ancho menor de
     * 600 dp o mas y un ancho actual por debajo de 1000 dp (Fold desplegado si; tableta apaisada o DeX, donde
     * las mitades quedarian demasiado lejos, no); "siempre" divide en cualquier parte; a una mano nunca.
     */
    private boolean updateSplit() {
        Configuration cfg = getResources().getConfiguration();
        boolean wide = cfg.smallestScreenWidthDp >= 600 && cfg.screenWidthDp < 1000;
        boolean s = handMode == 0 && (splitMode == 1 || (splitMode == 0 && wide));
        if (s == split) return false;
        split = s;
        rebuildLayouts();
        return true;
    }

    /** Modo a una mano: 0 normal, 1 izquierda, 2 derecha. */
    void setHandMode(int mode) {
        if (handMode == mode) return;
        handMode = mode;
        if (updateSplit()) return;
        requestLayout();
        invalidate();
        a11yChanged();
    }

    /** Reconstruye todas las distribuciones (idioma, fila de numeros, globo, dividido, campo) conservando la pagina actual. */
    private void rebuildLayouts() {
        List<List<Key>> was = current;
        boolean numpadWas = isNumpad();
        letters.clear();
        symbols.clear();
        symbols2.clear();
        symbols3.clear();
        numpad.clear();
        buildLayouts();
        if (numpadWas) buildNumpad();
        current = numpadWas ? numpad : was == symbols ? symbols : was == symbols2 ? symbols2 : was == symbols3 ? symbols3 : letters;
        closePopup();
        requestLayout();
        invalidate();
        a11yChanged();
    }

    void setSpaceLabel(String label) {
        spaceLabel = label;
        invalidate();
        a11yChanged();
    }

    void setStripText(CharSequence text) {
        stripText = text == null ? "" : text;
        stripTokens = Glyphs.tokenize(stripText.toString().replace('\n', ' '), useDigraphs);
        invalidate();
    }

    void resetToLetters() {
        current = letters;
        capsLock = false;
        shift = false;
        requestLayout();
        invalidate();
        a11yChanged();
    }

    void setAutoShift(boolean on) {
        if (capsLock || shiftPointer >= 0) return;     // con mayusculas sostenida manda el dedo, no el texto
        boolean was = shift;
        if (on) { shift = true; autoShifted = true; }
        else if (autoShifted) { shift = false; autoShifted = false; }
        if (shift != was) a11yChanged();              // las letras cambian de caja para el lector de pantalla
        invalidate();
    }

    // ------------------------------------------------------------------ distribucion

    private static Key letter(String c) { return new Key(c, c, c, LETTER_ALTS.get(c), CODE_TEXT, 1f); }

    /** Signo, con alternativas opcionales por pulsacion larga. */
    private static Key sym(String out, float w, String... alts) {
        return new Key(out, Glyphs.has(out) ? out : null, out, alts, CODE_TEXT, w);
    }
    private static Key special(String label, int code, float w) { return new Key(null, null, label, null, code, w); }

    private void row(List<List<Key>> layout, Key... keys) {
        List<Key> r = new ArrayList<>();
        for (Key k : keys) r.add(k);
        layout.add(r);
    }

    private void lettersRow(List<List<Key>> layout, String chars) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) r.add(letter(String.valueOf(chars.charAt(i))));
        layout.add(r);
    }

    private List<Key> digitsRow() {
        List<Key> nums = new ArrayList<>();
        for (char c = '1'; c <= '9'; c++) nums.add(sym(String.valueOf(c), 1f));
        nums.add(sym("0", 1f));
        return nums;
    }

    /** Tecla de letra del aleman: la vocal con diaeresis (o la eszett en la s) pasa a ser la primera alternativa. */
    private static Key germanLetter(String c) {
        Key k = letter(c);
        String first = "s".equals(c) ? "\u00df" : "a".equals(c) ? "\u00e4" : "o".equals(c) ? "\u00f6" : "u".equals(c) ? "\u00fc" : null;
        if (first == null) return k;
        List<String> a = new ArrayList<>();
        a.add(first);
        if (k.alts != null) for (String x : k.alts) if (!x.equals(first)) a.add(x);
        return new Key(c, c, c, a.toArray(new String[0]), CODE_TEXT, 1f);
    }

    /** Fila de letras del aleman (QWERTZ): las alternativas de a, o, u y s empiezan por ä, ö, ü y ß. */
    private void germanRow(List<List<Key>> layout, String chars) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) r.add(germanLetter(String.valueOf(chars.charAt(i))));
        layout.add(r);
    }

    private void buildLayouts() {
        boolean de = "de".equals(language);     // QWERTZ: qwertzuiopü / asdfghjklöä / yxcvbnm
        if (numbersShown()) letters.add(digitsRow());
        if (de) germanRow(letters, "qwertzuiop\u00fc"); else lettersRow(letters, "qwertyuiop");
        if (!numbersShown()) {                    // sin fila de numeros: q..p dan 1..0 en pulsacion larga
            List<Key> top = letters.get(0);
            for (int i = 0; i < 10 && i < top.size(); i++) top.set(i, top.get(i).withFirstAlt(String.valueOf((i + 1) % 10)));
        }
        if (de) germanRow(letters, "asdfghjkl\u00f6\u00e4"); else lettersRow(letters, "asdfghjkl");
        if ("es".equals(language)) letters.get(letters.size() - 1).add(new Key("ñ", "ñ", "ñ", null, CODE_TEXT, 1f));
        List<Key> r3 = new ArrayList<>();
        r3.add(special("", CODE_SHIFT, 1.5f));
        String z = de ? "yxcvbnm" : "zxcvbnm";
        for (int i = 0; i < z.length(); i++) r3.add(de ? germanLetter(String.valueOf(z.charAt(i))) : letter(String.valueOf(z.charAt(i))));
        r3.add(special("", CODE_DELETE, 1.5f));
        letters.add(r3);
        bottomRow(letters, "?123");

        // Signos 1/2: la misma distribucion que Samsung Keyboard (, ; : ? en la cuarta fila, . abajo)
        symbols.add(digitsRow());
        row(symbols, sym("+", 1f, "\u00b1"), sym("\u00d7", 1f), sym("\u00f7", 1f), sym("=", 1f, "\u2260"), sym("/", 1f, "\\"),
                sym("_", 1f), sym("<", 1f, "\u2264"), sym(">", 1f, "\u2265"), sym("[", 1f, "{"), sym("]", 1f, "}"));
        row(symbols, sym("!", 1f, "\u00a1"), sym("@", 1f), sym("#", 1f), sym("$", 1f, "\u20ac"), sym("%", 1f, "\u2030"),
                sym("^", 1f), sym("&", 1f), sym("*", 1f, "\u2022"), sym("(", 1f), sym(")", 1f));
        row(symbols, special("1/3", CODE_PAGE, 1.5f), sym("-", 1f, "\u2013"), sym("'", 1f, "\u2018"), sym("\"", 1f, "\u201c"),
                sym(":", 1f), sym(";", 1f), sym(",", 1f), sym("?", 1f, "\u00bf"), special("", CODE_DELETE, 1.5f));
        bottomRow(symbols, "abc");

        // Signos 2/2: digrafos Aurebesh, signos del espanol y simbolos menos frecuentes
        symbols2.add(digitsRow());
        List<Key> dig = new ArrayList<>();
        for (String d : Glyphs.DIGRAPHS) dig.add(new Key(d, d, d, null, CODE_TEXT, 1.25f));
        symbols2.add(dig);
        row(symbols2, sym("\u00bf", 1f), sym("\u00a1", 1f), sym("\u00ab", 1f), sym("\u00bb", 1f), sym("{", 1f), sym("}", 1f),
                sym("|", 1f), sym("\\", 1f), sym("~", 1f), sym("\u2022", 1f));
        row(symbols2, special("2/3", CODE_PAGE, 1.5f), sym("\u00a3", 1f), sym("\u20ac", 1f), sym("\u00a5", 1f), sym("\u00b0", 1f),
                sym("\u00a9", 1f), sym("\u00ae", 1f), sym("\u2122", 1f), special("", CODE_DELETE, 1.5f));
        bottomRow(symbols2, "abc");

        // Letras con acento 3/3: todas a la vista (ademas de la pulsacion larga sobre la vocal), con el glifo de la
        // letra base y la letra latina siempre visible para distinguir a a a a
        symbols3.add(digitsRow());
        row(symbols3, accent("\u00e0"), accent("\u00e1"), accent("\u00e2"), accent("\u00e4"), accent("\u00e3"), accent("\u00e5"),
                accent("\u00e6"), accent("\u00e7"), accent("\u00e8"), accent("\u00e9"));
        row(symbols3, accent("\u00ea"), accent("\u00eb"), accent("\u00ec"), accent("\u00ed"), accent("\u00ee"), accent("\u00ef"),
                accent("\u00f2"), accent("\u00f3"), accent("\u00f4"), accent("\u00f6"));
        row(symbols3, special("3/3", CODE_PAGE, 1.5f), accent("\u00f5"), accent("\u00f8"), accent("\u00f9"), accent("\u00fa"),
                accent("\u00fb"), accent("\u00fc"), accent("\u00df"), special("", CODE_DELETE, 1.5f));
        bottomRow(symbols3, "abc");
    }

    /** Tecla de letra con acento: glifo de la letra base (si lo hay), la letra latina completa siempre visible. */
    private static Key accent(String ch) {
        String g = Glyphs.glyphKey(ch);
        String[] alts = "\u00e6".equals(ch) ? new String[]{"\u0153"} : "\u00fc".equals(ch) ? new String[]{"\u00fd", "\u00ff"} : null;
        return new Key(ch, g, ch, alts, CODE_TEXT, 1f);
    }

    /** Alternativas del punto al mantenerlo: la puntuacion completa del idioma (la primera es la que sale al soltar sin mover). */
    private String[] dotAlts() {
        if ("es".equals(language)) return new String[]{"?", "\u00bf", "!", "\u00a1", ":", ";", "-", "\"", "'", "@"};
        if ("de".equals(language)) return new String[]{"?", "!", ":", ";", "-", "\"", "'", "@"};
        return new String[]{"?", "!", ",", ":", ";", "-", "\"", "'", "@"};
    }

    /** Alternativas de la coma: en espanol los signos de apertura; en ingles !; en aleman ;. */
    private String[] commaAlts() {
        if ("es".equals(language)) return new String[]{"\u00bf", "\u00a1", ";", ":"};
        if ("de".equals(language)) return new String[]{";"};
        return new String[]{"!"};
    }

    /**
     * Fila inferior: ?123/abc, coma, [globo], espacio, punto e intro. En campos de correo la coma pasa a ser @
     * y aparece una tecla .com (1,5 unidades) a costa del espacio; en direcciones web, / y .com.
     */
    private void bottomRow(List<List<Key>> layout, String modeLabel) {
        List<Key> r = new ArrayList<>();
        r.add(special(modeLabel, CODE_MODE, 1.5f));
        String open = "es".equals(language) ? "\u00bf" : "!";
        if (fieldVariant == FIELD_EMAIL) r.add(sym("@", 1f, ",", open));
        else if (fieldVariant == FIELD_URL) r.add(sym("/", 1f, ",", "-", "_"));
        else r.add(sym(",", 1f, commaAlts()));
        Key dotCom = fieldVariant == FIELD_NONE ? null
                : new Key(".com", null, ".com", new String[]{".es", ".mx", ".org", ".net"}, CODE_TEXT, 1.5f);
        float space = 5f;
        if (languageSwitch) { r.add(special("", CODE_GLOBE, 1f)); space -= 1f; }
        if (split) {                                       // dos mitades de 5,5 unidades, como Samsung Keyboard en el Fold
            r.add(special("", CODE_SPACE, space - 2f));    // ?123 (1,5) + , (1) [+ globo (1)] + espacio = 5,5
            r.add(special("", CODE_GAP, 0f));
            r.add(special("", CODE_SPACE, dotCom != null ? 1.5f : 3f));   // espacio (3) [o 1,5 + .com] + . (1) + intro (1,5) = 5,5
        } else {
            r.add(special("", CODE_SPACE, dotCom != null ? space - 1.5f : space));
        }
        if (dotCom != null) r.add(dotCom);
        r.add(sym(".", 1f, dotAlts()));
        r.add(special("", CODE_ENTER, 1.5f));
        layout.add(r);
    }

    /**
     * Teclado numerico, 4 columnas x 4 filas con los digitos en glifos Aurebesh grandes. Numero:
     * 1 2 3 / 4 5 6 / 7 8 9 / [separador] 0 borrar, y una columna derecha con \u2212 y + (campos con signo) e
     * intro alto. Telefono: * 0 # en la ultima fila y a la derecha borrar, +, pausa (,) e intro. Las celdas
     * sin tecla llevan CODE_GAP; {@link #layoutNumpad} coloca las teclas y estira intro sobre las celdas vacias.
     */
    private void buildNumpad() {
        boolean en = "en".equals(language);
        if (numpadPhone) {
            String pause = getContext().getString(R.string.numpad_pause);
            row(numpad, sym("1", 1f), sym("2", 1f), sym("3", 1f), special("", CODE_DELETE, 1f));
            row(numpad, sym("4", 1f), sym("5", 1f), sym("6", 1f), sym("+", 1f));
            row(numpad, sym("7", 1f), sym("8", 1f), sym("9", 1f), new Key(",", null, pause, new String[]{";"}, CODE_TEXT, 1f));
            row(numpad, sym("*", 1f), sym("0", 1f), sym("#", 1f), special("", CODE_ENTER, 1f));
            return;
        }
        Key sep = !numpadDecimal ? special("", CODE_GAP, 1f)
                : en ? sym(".", 1f, ",") : sym(",", 1f, ".");
        row(numpad, sym("1", 1f), sym("2", 1f), sym("3", 1f), numpadSigned ? sym("-", 1f, "\u2212") : special("", CODE_GAP, 1f));
        row(numpad, sym("4", 1f), sym("5", 1f), sym("6", 1f), numpadSigned ? sym("+", 1f) : special("", CODE_GAP, 1f));
        row(numpad, sym("7", 1f), sym("8", 1f), sym("9", 1f), special("", CODE_ENTER, 1f));
        row(numpad, sep, sym("0", 1f), special("", CODE_DELETE, 1f), special("", CODE_GAP, 1f));
    }

    /** Tecla de funcion del teclado actual con ese codigo (para pruebas). */
    Key keyFor(int code) {
        for (List<Key> row : current) for (Key k : row) if (k.code == code) return k;
        return null;
    }

    /** Tecla del teclado actual que escribe {@code output} (para pruebas). */
    Key keyFor(String output) {
        for (List<Key> row : current) for (Key k : row) if (output.equals(k.output)) return k;
        return null;
    }

    // ------------------------------------------------------------------ medidas

    /** Cinta de 40 dp (32 en pantallas muy bajas), o 4 dp de aire cuando no hay barra ni iconos. */
    private float stripHeight() {
        if (!(toolbar || showStrip || suggestionBar)) return 4 * dp;
        return (heightClass() == 0 ? 32 : 40) * dp;
    }

    /** Radio de los botones redondos de la cinta: 16 dp, algo menos en la cinta de 32 dp. */
    private float toolRadius() { return Math.min(16 * dp, stripHeight() / 2f - 2 * dp); }

    /**
     * Clase de alto de la pantalla real (Configuration.screenHeightDp): 0 muy baja (menos de 420 dp: tapa del
     * Z Flip, telefono apaisado con barra), 1 baja (menos de 560 dp: telefono apaisado), 2 normal.
     */
    private int heightClass() {
        int hdp = getResources().getConfiguration().screenHeightDp;
        if (hdp <= 0) return 2;                     // sin dato: lo normal
        return hdp < 420 ? 0 : hdp < 560 ? 1 : 2;
    }

    /** Fila de numeros efectiva: la preferencia, salvo en pantallas muy bajas, donde se oculta aunque este activada. */
    private boolean numbersShown() { return numberRow && !compact; }
    private boolean compact = false;            // clase 0: filas de 34/28 dp, cinta de 32 dp y sin fila de numeros

    /** Sigue la clase de alto (giro, tapa del plegable) y reconstruye las filas si cambia la fila de numeros efectiva. */
    private boolean updateCompact() {
        boolean c = heightClass() == 0;
        if (c == compact) return false;
        compact = c;
        rebuildLayouts();
        return true;
    }

    private boolean isDigitRow(List<List<Key>> layout, int ri) {
        return ri == 0 && layout != numpad && (numbersShown() || layout == symbols || layout == symbols2 || layout == symbols3);
    }

    /** Ancho de la caja de teclas: todo el ancho, o en modo a una mano el 78 % sin pasar de 400 dp (Fold, tabletas). */
    private float handWidth() {
        float full = getWidth();
        return handMode == 0 ? full : Math.min(full * 0.78f, 400 * dp);
    }

    /** Donde empieza la caja de teclas (y la cinta, que la acompana): pegada a la derecha en modo a una mano derecha. */
    private float handOffset() { return handMode == 2 ? getWidth() - handWidth() : 0; }

    /**
     * Alto de fila por clase de alto de la pantalla: 58/46 dp (filas / fila de numeros) con el alto elegido
     * entero en pantallas normales; 38/30 dp en las bajas y 34/28 dp en las muy bajas, donde el alto elegido
     * solo reduce (nunca amplia) para no tapar media pantalla.
     */
    private float rowHeight(List<List<Key>> layout, int ri) { return rowHeight(layout, ri, heightScale); }

    private float rowHeight(List<List<Key>> layout, int ri, float scale) {
        int hc = heightClass();
        boolean digits = isDigitRow(layout, ri);
        if (hc == 0) return (digits ? 28 : 34) * dp * Math.min(scale, 1f);
        if (hc == 1) return (digits ? 30 : 38) * dp * Math.min(scale, 1f);
        return (digits ? 46 : 58) * dp * scale;
    }

    private float keysHeight() { return keysHeight(heightScale); }

    /** Alto de las filas del teclado actual con ese alto elegido (1 = 100 %). */
    private float keysHeight(float scale) {
        float h = 0;
        for (int i = 0; i < current.size(); i++) h += rowHeight(current, i, scale);
        return h;
    }

    /** Banda bajo la ultima fila: el margen inferior elegido, nunca menos de 6 dp. */
    private float bottomBand() { return Math.max(6 * dp, bottomPad); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updateCompact();
        updateSplit();
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, (int) (stripHeight() + keysHeight() + bottomBand()));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutKeys(letters);
        layoutKeys(symbols);
        layoutKeys(symbols2);
        layoutKeys(symbols3);
        if (!numpad.isEmpty()) layoutNumpad();
        warmGlide();
    }

    /**
     * Margen lateral de las teclas (no de la cinta de herramientas, que va de borde a borde), como en
     * Samsung Keyboard: un 3 % del ancho en telefonos y un 5 % en pantallas de 600 dp o mas (Fold
     * desplegado, tabletas), nunca menos de 8 dp.
     */
    float keyMargin() {
        float wdp = getWidth() / dp;
        float pct = wdp >= 600 ? 0.05f : 0.03f;
        return Math.max(8 * dp, getWidth() * pct);
    }

    private void layoutKeys(List<List<Key>> layout) {
        if (split) { layoutKeysSplit(layout); return; }
        float w = handWidth();                                // a una mano: 78 % del ancho (maximo 400 dp)
        float offset = handOffset();
        float pad = keyMargin();
        float y = stripHeight();
        for (int ri = 0; ri < layout.size(); ri++) {
            List<Key> row = layout.get(ri);
            float rh = rowHeight(layout, ri);
            float total = 0;
            for (Key k : row) total += k.weight;
            float unit = Math.min((w - 2 * pad) / Math.max(10f, total), 72 * dp);   // tope de 72 dp (tabletas, DeX)
            float x = offset + pad + ((w - 2 * pad) - unit * total) / 2f;           // bloque centrado
            for (Key k : row) {
                float kw = unit * k.weight;
                k.rect.set(x, y, x + kw, y + rh);
                x += kw;
            }
            y += rh;
        }
    }

    /**
     * Teclado dividido con la misma geometria que Samsung Keyboard en un Galaxy Z Fold desplegado:
     * cada mitad es una caja de 5,5 teclas (el 37,5 % del ancho por defecto, ajustable con setSplitBox y con
     * un tope de 64 dp por tecla) pegada a su borde; la fila q-t / y-p se
     * apoya en el borde exterior, la fila central (a-g / h-ñ) se corre media tecla hacia el hueco, y en
     * la tercera fila mayusculas y borrar (1,5 teclas) ocupan los bordes con z-v bajo s-f y b-m bajo j-l.
     * Las filas con una tecla CODE_GAP se parten ahi; el resto donde la suma de anchos llega a la mitad.
     */
    private void layoutKeysSplit(List<List<Key>> layout) {
        float full = getWidth();
        float pad = keyMargin();
        float box = full * splitBox;                // sobre el ancho completo: el margen se come hueco, no teclas
        float unit = Math.min(box / 5.5f, 64 * dp); // tope de 64 dp por tecla (tabletas, DeX)
        float boxW = unit * 5.5f;                   // ancho real de cada mitad; con el tope, menor que la caja
        float x0 = pad, x1 = full - pad - boxW;
        float y = stripHeight();
        int homeRow = layout == letters ? (numbersShown() ? 2 : 1) : -1;
        for (int ri = 0; ri < layout.size(); ri++) {
            List<Key> row = layout.get(ri);
            float rh = rowHeight(layout, ri);
            float total = 0;
            int cut = -1;
            for (int i = 0; i < row.size(); i++) {
                if (row.get(i).code == CODE_GAP) cut = i; else total += row.get(i).weight;
            }
            int leftEnd;
            if (cut >= 0) {
                leftEnd = cut;
            } else {                                        // corte donde la suma de anchos llega a la mitad
                float acc = 0;
                leftEnd = row.size();
                for (int i = 0; i < row.size(); i++) {
                    acc += row.get(i).weight;
                    if (acc >= total / 2f - 0.01f) { leftEnd = i + 1; break; }
                }
            }
            float totalL = 0, totalR = 0;
            for (int i = 0; i < row.size(); i++) {
                Key k = row.get(i);
                if (k.code == CODE_GAP) { k.rect.setEmpty(); continue; }
                if (i < leftEnd) totalL += k.weight; else totalR += k.weight;
            }
            float unitL = totalL > 0 ? Math.min(unit, boxW / totalL) : unit;
            float unitR = totalR > 0 ? Math.min(unit, boxW / totalR) : unit;
            boolean home = ri == homeRow;
            float xl = home ? x0 + boxW - unitL * totalL : x0;         // central: pegada al hueco; resto: al borde
            float xr = home ? x1 : x1 + boxW - unitR * totalR;
            for (int i = 0; i < row.size(); i++) {
                Key k = row.get(i);
                if (k.code == CODE_GAP) continue;
                if (i < leftEnd) {
                    float kw = unitL * k.weight;
                    k.rect.set(xl, y, xl + kw, y + rh);
                    xl += kw;
                } else {
                    float kw = unitR * k.weight;
                    k.rect.set(xr, y, xr + kw, y + rh);
                    xr += kw;
                }
            }
            y += rh;
        }
    }

    /**
     * Teclado numerico: rejilla de 4 columnas que llena el ancho util (unidad maxima de 120 dp, centrada),
     * nunca dividida; intro se estira hacia abajo sobre las celdas vacias (CODE_GAP) de su columna.
     */
    private void layoutNumpad() {
        float w = handWidth(), off = handOffset(), pad = keyMargin();
        int cols = 0;
        for (List<Key> row : numpad) cols = Math.max(cols, row.size());
        if (cols == 0) return;
        float unit = Math.min((w - 2 * pad) / cols, 120 * dp);
        float x0 = off + pad + ((w - 2 * pad) - unit * cols) / 2f;
        float[] top = new float[numpad.size() + 1];
        top[0] = stripHeight();
        for (int ri = 0; ri < numpad.size(); ri++) {
            top[ri + 1] = top[ri] + rowHeight(numpad, ri);
            List<Key> row = numpad.get(ri);
            for (int ci = 0; ci < row.size(); ci++) {
                Key k = row.get(ci);
                if (k.code == CODE_GAP) k.rect.setEmpty();
                else k.rect.set(x0 + ci * unit, top[ri], x0 + (ci + 1) * unit, top[ri + 1]);
            }
        }
        for (int ri = 0; ri < numpad.size(); ri++) {
            List<Key> row = numpad.get(ri);
            for (int ci = 0; ci < row.size(); ci++) {
                if (row.get(ci).code != CODE_ENTER) continue;
                int rj = ri + 1;
                while (rj < numpad.size() && ci < numpad.get(rj).size() && numpad.get(rj).get(ci).code == CODE_GAP) rj++;
                row.get(ci).rect.bottom = top[rj];
            }
        }
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas c) {
        if (toolbar || showStrip || suggestionBar) drawStrip(c);
        float inset = 3 * dp;
        float radius = theme.radiusDp * dp;
        for (int ri = 0; ri < current.size(); ri++) {
            for (Key k : current.get(ri)) {
                if (k.code == CODE_GAP) continue;
                tmp.set(k.rect.left + inset, k.rect.top + inset, k.rect.right - inset, k.rect.bottom - inset);
                boolean isPressed = pressed.indexOfValue(k) >= 0 || k == repeatKey;
                boolean function = k.code != CODE_TEXT && k.code != CODE_SPACE;
                boolean inkFilled = k.code == CODE_ENTER || (k.code == CODE_SHIFT && (shift || capsLock));
                Integer bg;
                if (isDigitRow(current, ri)) bg = null;       // fila de numeros: franja sin tecla
                else if (inkFilled) bg = theme.accent;
                else if (function) bg = theme.specialBg;
                else bg = theme.keyBg;
                if (isPressed) bg = theme.pressed;
                if (bg != null) {
                    if (theme.shadow && !isPressed && !inkFilled) {
                        fill.setColor((theme.glyph & 0x00FFFFFF) | 0x1A000000);   // sombra de 1 px
                        c.drawRoundRect(tmp.left, tmp.top + dp, tmp.right, tmp.bottom + dp, radius, radius, fill);
                    }
                    fill.setColor(bg);
                    c.drawRoundRect(tmp, radius, radius, fill);
                    if (theme.keyStroke != 0 && !inkFilled) c.drawRoundRect(tmp, radius, radius, strokePaint);
                }
                drawKeyFace(c, k, tmp, isDigitRow(current, ri));
            }
        }
        drawSideSwitch(c);
        drawHideButton(c);
        if (glideTrail && strokeN > 1 && glideState != GLIDE_PENDING) drawTrail(c);
        for (int i = 0; i < pressed.size(); i++) {
            Key k = pressed.valueAt(i);
            if (glideState == GLIDE_GLIDING && pressed.keyAt(i) == glidePointer) continue;   // sin burbuja al trazar
            if (previewEnabled && k != null && k.code == CODE_TEXT && k != popupKey) drawPreview(c, k);
        }
        if (popupKey != null) drawPopup(c);
        if (resizeMode) drawResize(c);
    }

    /**
     * Estela de sable de luz: una curva continua y suave por los puntos del trazo (cuadraticas entre puntos
     * medios con el punto como control) con nucleo blanco fino, hoja del color del sable y dos halos anchos y
     * translucidos. Se parte en TRAIL_BANDS tramos por edad que empalman en los puntos medios (la curva sigue
     * siendo continua) y cada tramo se afina y se desvanece hacia la cola en TRAIL_MS; el extremo delantero
     * lleva un destello blanco con halo. Las Path se reutilizan y se vuelve a dibujar en cada fotograma
     * mientras quede algo visible. Sin aceleracion por hardware el halo va ademas difuminado.
     */
    private void drawTrail(Canvas c) {
        long now = SystemClock.uptimeMillis();
        int last = strokeN - 1;
        int first = last;                                             // punto mas viejo todavia visible
        while (first > 0 && now - strokeT[first - 1] <= TRAIL_MS) first--;
        for (int b = 0; b < TRAIL_BANDS; b++) trailBandUsed[b] = false;
        int cur = -1;
        float sx = strokeX[first], sy = strokeY[first];
        for (int i = first + 1; i <= last; i++) {
            long age = Math.max(0, now - strokeT[i]);
            int b = (int) (age * TRAIL_BANDS / TRAIL_MS);
            if (b >= TRAIL_BANDS) b = TRAIL_BANDS - 1;
            float ex, ey;                                             // fin del tramo: punto medio con el siguiente (o el ultimo punto)
            if (i < last) { ex = (strokeX[i] + strokeX[i + 1]) / 2f; ey = (strokeY[i] + strokeY[i + 1]) / 2f; }
            else { ex = strokeX[i]; ey = strokeY[i]; }
            if (b != cur) {                                           // las edades crecen hacia la cola: cada tramo es un solo trozo
                cur = b;
                trailPaths[b].rewind();
                trailPaths[b].moveTo(sx, sy);
                trailBandUsed[b] = true;
            }
            if (i < last) trailPaths[b].quadTo(strokeX[i], strokeY[i], ex, ey);
            else trailPaths[b].lineTo(ex, ey);
            sx = ex;
            sy = ey;
        }
        boolean any = cur >= 0;
        int rgb = saberColor & 0x00FFFFFF;
        trailHaloPaint.setMaskFilter(c.isHardwareAccelerated() ? null : trailBlur);
        // Cuatro capas de fuera adentro, cada una con todos sus tramos de la cola al frente: ningun halo tapa un nucleo
        for (int layer = 0; layer < 4; layer++) {
            Paint p = layer == 0 ? trailOuterPaint : layer == 1 ? trailHaloPaint : layer == 2 ? trailBladePaint : trailCorePaint;
            float base = (layer == 0 ? TRAIL_OUTER_DP : layer == 1 ? TRAIL_HALO_DP : layer == 2 ? TRAIL_WIDTH_DP : TRAIL_CORE_DP) * dp;
            for (int b = TRAIL_BANDS - 1; b >= 0; b--) {
                if (!trailBandUsed[b]) continue;
                float k = 1f - b / (float) TRAIL_BANDS;               // 1 recien trazado, hacia 0 en la cola
                int alpha = layer == 0 ? (int) (0x26 * k) : layer == 1 ? (int) (0x58 * k)
                        : layer == 2 ? (int) (255 * k) : (int) (255 * k * k);
                p.setColor((layer == 3 ? 0x00FFFFFF : rgb) | (alpha << 24));
                p.setStrokeWidth(base * (0.4f + 0.6f * k));
                c.drawPath(trailPaths[b], p);
            }
        }
        // Destello en el extremo delantero; al soltar se apaga con la edad del ultimo punto
        float kf = glideState == GLIDE_GLIDING ? 1f : 1f - Math.min(1f, Math.max(0, now - strokeT[last]) / (float) TRAIL_MS);
        if (kf > 0f) {
            trailFlashPaint.setColor(rgb | (((int) (0x90 * kf)) << 24));
            c.drawCircle(strokeX[last], strokeY[last], 9 * dp * (0.6f + 0.4f * kf), trailFlashPaint);
            trailFlashPaint.setColor(0x00FFFFFF | (((int) (255 * kf)) << 24));
            c.drawCircle(strokeX[last], strokeY[last], 3.5f * dp, trailFlashPaint);
            any = true;
        }
        if (any) postInvalidateOnAnimation();
        else if (glideState == GLIDE_NONE) strokeN = 0;              // ya se desvanecio del todo
    }

    /** True si dos colores apenas se distinguen (diferencia de luminancia pequena): la tinta no se veria sobre ese fondo. */
    private static boolean lowContrast(int a, int b) {
        return Math.abs(luma(a) - luma(b)) < 0.25f;
    }

    private static float luma(int argb) {
        return (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
    }

    private void drawKeyFace(Canvas c, Key k, RectF r, boolean digitRow) {
        float cx = r.centerX();
        float cy = r.centerY();
        switch (k.code) {
            case CODE_TEXT:
                if (k.glyph != null) {
                    float h = r.height() * (digitRow ? 0.44f : current == numpad ? 0.58f : 0.50f);   // numerico: digitos grandes
                    if (theme.glow != 0) Glyphs.drawCentered(c, k.glyph, cx, cy, h, r.width() * 0.74f, glowPaint);
                    boolean dimInk = digitRow && lowContrast(theme.glyph, theme.bg);   // franja sin tecla sobre un fondo del color de la tinta (tema Contraste)
                    if (dimInk) glyphPaint.setColor(theme.keyBg);
                    Glyphs.drawCentered(c, k.glyph, cx, cy, h, r.width() * 0.74f, glyphPaint);
                    if (dimInk) glyphPaint.setColor(theme.glyph);
                    float ha = !k.glyph.equals(k.output) ? 1f : hintAlpha(k.output);   // con acento: la latina siempre, para distinguirlas
                    if (ha > 0f) {
                        int base = hintPaint.getColor();
                        if (ha < 1f) hintPaint.setColor(fade(base, ha));        // aprendiendo: se desvanece con el uso
                        hintPaint.setTextSize(9 * sp);
                        hintPaint.setTextAlign(Paint.Align.LEFT);
                        String hint = shiftActive() ? k.label.toUpperCase() : k.label;
                        c.drawText(hint, r.left + 4 * dp, r.top + 10 * dp, hintPaint);
                        hintPaint.setTextAlign(Paint.Align.RIGHT);
                        hintPaint.setColor(base);
                    }
                } else if (k.label.length() > 1) {
                    drawMiniWord(c, k.label, cx, cy, 10 * dp);    // ".com" o "Pausa": palabra en Aurebesh pequeño, como ?123
                } else {
                    textPaint.setTextSize(19 * dp);
                    c.drawText(k.label, cx, cy + 7 * dp, textPaint);
                }
                boolean symbolKey = current != letters && k.glyph != null && !digitRow
                        && k.label.length() == 1 && !Character.isLetterOrDigit(k.label.charAt(0));
                if (symbolKey) {                                 // leyenda del signo en la pagina ?123
                    hintPaint.setTextSize(9 * sp);
                    c.drawText(k.label, r.right - 4 * dp, r.top + 10 * dp, hintPaint);
                }
                if (k.alts != null && !digitRow) {
                    String first = k.alts[0];
                    boolean digit = first.length() == 1 && Character.isDigit(first.charAt(0));
                    float aa = digit ? 1f : hintAlpha(k.output);   // los numeros ocultos siempre se insinuan; el resto sigue a su letra
                    if (aa > 0f) {
                        int base = hintPaint.getColor();
                        if (aa < 1f) hintPaint.setColor(fade(base, aa));
                        hintPaint.setTextSize(9 * sp);
                        if (symbolKey) {
                            hintPaint.setTextAlign(Paint.Align.LEFT);
                            c.drawText(first, r.left + 4 * dp, r.top + 10 * dp, hintPaint);
                            hintPaint.setTextAlign(Paint.Align.RIGHT);
                        } else {
                            c.drawText(first, r.right - 4 * dp, r.top + 10 * dp, hintPaint);
                        }
                        hintPaint.setColor(base);
                    }
                }
                break;
            case CODE_MODE:
            case CODE_PAGE:
                // "?123" / "abc" / "1/2" escritos en Aurebesh pequeño
                drawMiniWord(c, k.label, cx, cy, 10 * dp);
                break;
            case CODE_SHIFT: drawShift(c, cx, cy); break;
            case CODE_DELETE: drawDelete(c, cx, cy); break;
            case CODE_GLOBE: drawGlobe(c, cx, cy); break;
            case CODE_ENTER: drawEnter(c, cx, cy); break;
            case CODE_SPACE:
                if (spaceLabel != null) {
                    hintPaint.setTextSize(11 * sp);
                    hintPaint.setTextAlign(Paint.Align.CENTER);
                    c.drawText(spaceLabel, cx, cy + 4 * dp, hintPaint);
                    hintPaint.setTextAlign(Paint.Align.RIGHT);
                }
                if (spaceSlide != SPACE_NONE) drawSpaceChevrons(c, r, cy);
                break;
        }
    }

    /**
     * Chevrones tenues ‹ › en los extremos del espacio cuando deslizar sobre el hace algo (en cada mitad del
     * dividido tambien). Tras cambiar de idioma con el gesto, el chevron de ese lado destella en el color de
     * acento durante 350 ms.
     */
    private void drawSpaceChevrons(Canvas c, RectF r, float cy) {
        if (r.width() < 60 * dp) return;
        long age = SystemClock.uptimeMillis() - spaceFlashAt;
        boolean flashing = spaceFlashAt != 0 && age >= 0 && age < 350;
        hintPaint.setTextSize(13 * sp);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        int base = hintPaint.getColor();
        int faint = (base & 0x00FFFFFF) | 0x66000000;
        for (int side = -1; side <= 1; side += 2) {
            boolean lit = flashing && side == spaceFlashDir;
            int color = lit ? theme.accent == theme.keyBg ? theme.glyph : theme.accent : faint;
            if (lit) color = (color & 0x00FFFFFF) | (((int) (255 * (1f - age / 350f))) << 24);
            hintPaint.setColor(color);
            float x = side < 0 ? r.left + 10 * dp : r.right - 10 * dp;
            c.drawText(side < 0 ? "‹" : "›", x, cy + 5 * dp, hintPaint);
        }
        hintPaint.setColor(base);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        if (flashing) postInvalidateOnAnimation();
    }

    private void drawMiniWord(Canvas c, String word, float cx, float cy, float h) {
        List<Glyphs.Token> tokens = Glyphs.tokenize(word, false);
        float total = 0;
        for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, textPaint);
        float x = cx - total / 2f;
        for (Glyphs.Token t : tokens) x += Glyphs.drawToken(c, t, x, cy - h / 2f, h, glyphPaint, textPaint);
    }

    private void drawShift(Canvas c, float cx, float cy) {
        float s = 9 * dp;
        iconPath.reset();
        iconPath.moveTo(cx, cy - s);
        iconPath.lineTo(cx + s, cy);
        iconPath.lineTo(cx + s * 0.45f, cy);
        iconPath.lineTo(cx + s * 0.45f, cy + s * 0.9f);
        iconPath.lineTo(cx - s * 0.45f, cy + s * 0.9f);
        iconPath.lineTo(cx - s * 0.45f, cy);
        iconPath.lineTo(cx - s, cy);
        iconPath.close();
        boolean on = shift || capsLock;
        if (on) {
            fill.setColor(theme.onAccent);
            c.drawPath(iconPath, fill);
            if (capsLock) c.drawRect(cx - s * 0.45f, cy + s * 1.2f, cx + s * 0.45f, cy + s * 1.5f, fill);
        } else {
            iconPaint.setStrokeWidth(2.8f * dp);
            c.drawPath(iconPath, iconPaint);
        }
    }

    private void drawDelete(Canvas c, float cx, float cy) {
        float w = 11 * dp, h = 7 * dp;
        iconPath.reset();
        iconPath.moveTo(cx - w, cy);
        iconPath.lineTo(cx - w * 0.45f, cy - h);
        iconPath.lineTo(cx + w, cy - h);
        iconPath.lineTo(cx + w, cy + h);
        iconPath.lineTo(cx - w * 0.45f, cy + h);
        iconPath.close();
        iconPaint.setStrokeWidth(2.6f * dp);
        c.drawPath(iconPath, iconPaint);
        float x = cx + w * 0.2f, k = 2.6f * dp;
        c.drawLine(x - k, cy - k, x + k, cy + k, iconPaint);
        c.drawLine(x - k, cy + k, x + k, cy - k, iconPaint);
    }

    private void drawGlobe(Canvas c, float cx, float cy) {
        float r = 8 * dp;
        iconPaint.setStrokeWidth(2.2f * dp);
        c.drawCircle(cx, cy, r, iconPaint);                      // globo: circulo, ecuador y un meridiano
        c.drawLine(cx - r, cy, cx + r, cy, iconPaint);
        tmp.set(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r);
        c.drawOval(tmp, iconPaint);
    }

    private void drawEnter(Canvas c, float cx, float cy) {
        iconPaint.setStrokeWidth(3f * dp);
        iconPaint.setColor(theme.onAccent);
        try { drawEnterIcon(c, cx, cy); } finally { iconPaint.setColor(theme.icon); }
    }

    private void drawEnterIcon(Canvas c, float cx, float cy) {
        float s = 7 * dp;
        iconPath.reset();
        switch (enterAction) {
            case 1: // buscar
                c.drawCircle(cx - 2 * dp, cy - 2 * dp, s * 0.75f, iconPaint);
                c.drawLine(cx + 2 * dp, cy + 2 * dp, cx + s, cy + s, iconPaint);
                break;
            case 2: // enviar / ir
                iconPath.moveTo(cx - s, cy - s); iconPath.lineTo(cx + s, cy);
                iconPath.lineTo(cx - s, cy + s); iconPath.lineTo(cx - s * 0.5f, cy); iconPath.close();
                fill.setColor(theme.onAccent);
                c.drawPath(iconPath, fill);
                break;
            case 3: // siguiente
                c.drawLine(cx - s, cy, cx + s, cy, iconPaint);
                c.drawLine(cx + s * 0.2f, cy - s * 0.7f, cx + s, cy, iconPaint);
                c.drawLine(cx + s * 0.2f, cy + s * 0.7f, cx + s, cy, iconPaint);
                break;
            case 0: // intro
                iconPath.moveTo(cx + s, cy - s * 0.8f); iconPath.lineTo(cx + s, cy + s * 0.2f);
                iconPath.lineTo(cx - s, cy + s * 0.2f);
                c.drawPath(iconPath, iconPaint);
                c.drawLine(cx - s * 0.2f, cy - s * 0.5f, cx - s, cy + s * 0.2f, iconPaint);
                c.drawLine(cx - s * 0.2f, cy + s * 0.9f, cx - s, cy + s * 0.2f, iconPaint);
                break;
            default: // listo (check)
                iconPath.moveTo(cx - s, cy);
                iconPath.lineTo(cx - s * 0.3f, cy + s * 0.7f);
                iconPath.lineTo(cx + s, cy - s * 0.7f);
                c.drawPath(iconPath, iconPaint);
        }
    }

    /** Menu de alternativas sobre la tecla mantenida; la opcion bajo el dedo va resaltada. */
    private void drawPopup(Canvas c) {
        float r = 10 * dp;
        fill.setColor(0x40000000);
        c.drawRoundRect(popupRect.left, popupRect.top + 3 * dp, popupRect.right, popupRect.bottom + 3 * dp, r, r, fill);
        fill.setColor(theme.preview);
        c.drawRoundRect(popupRect, r, r, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(popupRect, r, r, strokePaint);
        if (popupRows > 1) {                                        // separacion entre las dos filas
            float yy = popupRect.bottom - popupCellH;
            c.drawLine(popupRect.left + 8 * dp, yy, popupRect.right - 8 * dp, yy, strokePaint);
        }
        for (int i = 0; i < popupAlts.length; i++) {
            RectF cell = popupCellRect(i);
            float left = cell.left;
            float cx = cell.centerX(), cy = cell.centerY();
            boolean sel = i == popupIndex;
            if (sel) {
                tmp.set(left + 3 * dp, cell.top + 3 * dp, left + popupCell - 3 * dp, cell.bottom - 3 * dp);
                fill.setColor(theme.accent);
                c.drawRoundRect(tmp, 8 * dp, 8 * dp, fill);
            }
            String a = popupAlts[i];
            String shown = shiftActive() && (current == letters || current == symbols3) ? a.toUpperCase() : a;
            String gk = Glyphs.glyphKey(a);                 // a a a a: glifo de la letra base y la latina debajo
            if (gk != null) {
                // La latina segun el modo (en aprendiendo siempre); con acento siempre, que el glifo es el de la base
                boolean latin = hintMode != 0 || !gk.equals(a.toLowerCase(java.util.Locale.ROOT));
                glyphPaint.setColor(sel ? theme.onAccent : theme.previewText);
                Glyphs.drawCentered(c, gk, cx, latin ? cy - 6 * dp : cy, popupCellH * 0.42f, popupCell * 0.7f, glyphPaint);
                glyphPaint.setColor(theme.glyph);
                if (latin) {
                    textPaint.setColor(sel ? theme.onAccent : theme.previewLatin);
                    textPaint.setTextSize(10 * sp);
                    c.drawText(shown, cx, cell.bottom - 7 * dp, textPaint);
                }
            } else {
                textPaint.setColor(sel ? theme.onAccent : theme.previewText);
                textPaint.setTextSize(a.length() > 2 ? 15 * dp : 22 * dp);
                c.drawText(shown, cx, cy + 8 * dp, textPaint);
            }
            textPaint.setColor(theme.glyph);
        }
    }

    /** Teclas de una sola letra (la n~ incluida): su menu termina con la propia letra para poder volver a ella. */
    private static boolean singleLetter(Key k) {
        return k != null && k.code == CODE_TEXT && k.output != null && k.output.length() == 1 && Character.isLetter(k.output.charAt(0));
    }

    /**
     * Abre el menu de alternativas sobre la tecla. En las letras la propia letra va de ultima celda. Si las
     * celdas no caben en el ancho se reparten en dos filas de ceil(n/2); la fila 0 es la de abajo (pegada al
     * dedo) y la celda i esta en la fila i / columnas, columna i % columnas.
     */
    private void openPopup(Key k) {
        popupKey = k;
        String[] alts = k.alts;
        if (singleLetter(k) && current == letters) {
            boolean dup = false;
            for (String a : alts) dup |= a.equals(k.output);
            if (!dup) {
                alts = java.util.Arrays.copyOf(alts, alts.length + 1);
                alts[alts.length - 1] = k.output;
            }
        }
        popupAlts = alts;
        popupIndex = 0;
        int n = alts.length;
        float avail = getWidth() - 4 * dp;
        popupCell = Math.max(k.rect.width(), 46 * dp);
        popupCols = n;
        popupRows = 1;
        if (popupCell * n > avail) {
            popupRows = 2;
            popupCols = (n + 1) / 2;
            popupCell = Math.min(popupCell, avail / popupCols);
        }
        popupCellH = 64 * dp;
        float h = popupCellH * popupRows;
        float total = popupCell * popupCols;
        float left = k.rect.centerX() - popupCell / 2f;              // la primera opcion, sobre la tecla
        left = Math.max(2 * dp, Math.min(getWidth() - total - 2 * dp, left));
        float top = Math.max(0, k.rect.top - h - 2 * dp);
        popupRect.set(left, top, left + total, top + h);
        invalidate();
    }

    /** Celda del menu bajo el punto (x, y): la columna por x y, con dos filas, la fila por y (fuera del menu, la mas cercana). */
    private int popupIndexAt(float x, float y) {
        int col = (int) Math.floor((x - popupRect.left) / popupCell);
        col = Math.max(0, Math.min(popupCols - 1, col));
        int row = 0;
        if (popupRows > 1) {
            row = (int) Math.floor((popupRect.bottom - y) / popupCellH);
            row = Math.max(0, Math.min(popupRows - 1, row));
        }
        return Math.min(popupAlts.length - 1, row * popupCols + col);
    }

    /** El dedo se fue del menu: por debajo de la tecla y media fila mas, o mas de una tecla a los lados. */
    private boolean popupAbandoned(float x, float y) {
        float keyW = popupKey.rect.width();
        return y > popupKey.rect.bottom + popupKey.rect.height() / 2f
                || x < popupRect.left - keyW || x > popupRect.right + keyW;
    }

    private void commitPopup() {
        if (popupKey == null) return;
        String out = popupAlts[popupIndex];
        if (shiftActive() && current == letters) out = out.toUpperCase();
        if (listener != null) listener.onText(out);
        if (current == letters && singleLetter(popupKey)) countHint(popupKey.output);   // aprendiendo: la tecla se uso
        if (shiftPointer >= 0) shiftHeldTyped = true;
        else if (shift && !capsLock) { shift = false; autoShifted = false; }
        closePopup();
    }

    private void closePopup() {
        popupKey = null;
        popupAlts = null;
    }

    /** Celda i del menu abierto (para pruebas). */
    RectF popupCellRect(int i) {
        if (popupKey == null) return null;
        int row = i / popupCols, col = i % popupCols;
        float left = popupRect.left + col * popupCell;
        float bottom = popupRect.bottom - row * popupCellH;
        return new RectF(left, bottom - popupCellH, left + popupCell, bottom);
    }

    /** Opciones del menu abierto, en el orden de las celdas (para pruebas); null si no hay menu. */
    String[] popupOptions() { return popupAlts == null ? null : popupAlts.clone(); }

    private void drawPreview(Canvas c, Key k) {
        float w = Math.max(k.rect.width() * 1.3f, 56 * dp);
        float h = 74 * dp;
        float left = Math.max(2 * dp, Math.min(getWidth() - w - 2 * dp, k.rect.centerX() - w / 2f));
        float top = Math.max(0, k.rect.top - h + 6 * dp);
        tmp.set(left, top, left + w, top + h - 10 * dp);
        fill.setColor(0x40000000);
        c.drawRoundRect(tmp.left, tmp.top + 3 * dp, tmp.right, tmp.bottom + 3 * dp, 10 * dp, 10 * dp, fill);
        fill.setColor(theme.preview);
        c.drawRoundRect(tmp, 10 * dp, 10 * dp, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(tmp, 10 * dp, 10 * dp, strokePaint);
        if (k.glyph != null) {
            boolean latin = hintMode != 0 || !k.glyph.equals(k.output);   // en aprendiendo siempre visible; con acento, siempre
            float gy = latin ? tmp.centerY() - 7 * dp : tmp.centerY();
            if (theme.glow != 0) Glyphs.drawCentered(c, k.glyph, tmp.centerX(), gy, tmp.height() * 0.46f, tmp.width() * 0.72f, glowPaint);
            glyphPaint.setColor(theme.previewText);
            Glyphs.drawCentered(c, k.glyph, tmp.centerX(), gy, tmp.height() * 0.46f, tmp.width() * 0.72f, glyphPaint);
            glyphPaint.setColor(theme.glyph);
            if (latin) {
                textPaint.setColor(theme.previewLatin);
                textPaint.setTextSize(11 * sp);
                String s = shiftActive() ? k.label.toUpperCase() : k.label;
                c.drawText(s, tmp.centerX(), tmp.bottom - 6 * dp, textPaint);
                textPaint.setColor(theme.glyph);
            }
        } else {
            textPaint.setColor(theme.previewText);
            textPaint.setTextSize(26 * dp);
            c.drawText(k.label, tmp.centerX(), tmp.centerY() + 9 * dp, textPaint);
            textPaint.setColor(theme.glyph);
        }
    }

    private void drawStrip(Canvas c) {
        float h = 13.5f * dp;
        float top = (stripHeight() - h) / 2f;
        suggestionRects.clear();
        suggestionWords.clear();
        suggestionBand.setEmpty();
        for (int i = 0; i < toolRects.length; i++) toolRects[i].setEmpty();
        float hw = handWidth(), off = handOffset();            // la cinta acompana a la caja de teclas
        float right = off + hw - 12 * dp;
        boolean chips = !suggestions.isEmpty();
        boolean compact = chips || hasPasteSuggestion();     // con chips o con "Pegar" la cinta se pliega a "..."
        moreRect.setEmpty();
        pasteRect.setEmpty();
        aiRect.setEmpty();
        stripLeft = toolbar ? drawAiButton(c) : off + 12 * dp;
        if (toolbar && (!compact || toolsOverChips)) right = drawToolbar(c);
        else if (toolbar) right = drawMore(c);
        if (chips) {
            drawSuggestions(c, h, top, right);
        } else {
            float left = stripLeft;
            if (hasPasteSuggestion()) left = drawPasteChip(c, right);
            if (showStrip) drawTranslation(c, h, top, left, right);
        }
        if (theme.keyStroke != 0) c.drawRect(0, stripHeight() - dp, getWidth(), stripHeight(), strokePaint);
    }

    /** Boton de inteligencia (estrella de cuatro puntas) a la izquierda de la barra. Devuelve donde empiezan los chips. */
    private float drawAiButton(Canvas c) {
        float off = handOffset();                                // modo a una mano derecha: la barra acompana a las teclas
        float cy = stripHeight() / 2f, cx = off + 6 * dp + 22 * dp;
        aiRect.set(off + 6 * dp, 0, off + 6 * dp + 44 * dp, stripHeight());
        fill.setColor(theme.specialBg);
        c.drawCircle(cx, cy, toolRadius(), fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, toolRadius(), strokePaint);
        ToolIcons.draw(c, TOOL_AI, cx, cy, 20 * dp, iconPaint);
        return aiRect.right + 4 * dp;
    }

    /** Boton "..." a la derecha mientras hay sugerencias: muestra los iconos de la barra. */
    private float drawMore(Canvas c) {
        float hw = handWidth(), off = handOffset();
        float cy = stripHeight() / 2f, cx = off + hw - 6 * dp - 22 * dp;
        moreRect.set(off + hw - 50 * dp, 0, off + hw, stripHeight());
        fill.setColor(theme.specialBg);
        c.drawCircle(cx, cy, toolRadius(), fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, toolRadius(), strokePaint);
        ToolIcons.draw(c, TOOL_MORE, cx, cy, 20 * dp, iconPaint);
        return moreRect.left - 4 * dp;
    }

    /** Chip con lo recien copiado (miniatura o texto) y la palabra "Pegar". Devuelve su borde derecho. */
    private float drawPasteChip(Canvas c, float right) {
        float h = stripHeight() - 10 * dp, top = 5 * dp, x = stripLeft;
        textPaint.setTextSize(13 * sp);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        float actionW = textPaint.measureText(pasteAction);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float thumbW = pasteThumb != null ? h - 6 * dp : 0;
        float maxText = Math.max(0, right - x - actionW - thumbW - 40 * dp);
        String label = pasteLabel == null ? "" : TextUtils.ellipsize(pasteLabel.replace('\n', ' '), new android.text.TextPaint(textPaint), Math.min(maxText, 160 * dp), TextUtils.TruncateAt.END).toString();
        float labelW = label.isEmpty() ? 0 : textPaint.measureText(label) + 6 * dp;
        float w = 12 * dp + (thumbW > 0 ? thumbW + 8 * dp : 0) + labelW + actionW + 12 * dp;
        pasteRect.set(x, top, Math.min(right, x + w), top + h);
        fill.setColor(theme.keyBg);
        c.drawRoundRect(pasteRect, h / 2f, h / 2f, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(pasteRect, h / 2f, h / 2f, strokePaint);
        c.save();
        c.clipRect(pasteRect);
        float cx = pasteRect.left + 12 * dp, cy = pasteRect.centerY();
        if (pasteThumb != null) {
            tmp.set(cx, top + 3 * dp, cx + thumbW, top + 3 * dp + thumbW);
            c.save();
            iconPath.reset();
            iconPath.addRoundRect(tmp, 4 * dp, 4 * dp, Path.Direction.CW);
            c.clipPath(iconPath);
            c.drawBitmap(pasteThumb, null, tmp, fill);
            c.restore();
            cx += thumbW + 8 * dp;
        }
        if (!label.isEmpty()) {
            textPaint.setColor(theme.hint);
            c.drawText(label, cx, cy + 4.5f * dp, textPaint);
            cx += labelW;
        }
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(theme.accent == theme.glyph ? theme.glyph : theme.accent);
        c.drawText(pasteAction, cx, cy + 4.5f * dp, textPaint);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        textPaint.setColor(theme.glyph);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.restore();
        return pasteRect.right + 10 * dp;
    }

    /**
     * Iconos redondos a la derecha, en el orden elegido por el usuario (filtrado por lo que tiene sentido ahora,
     * igual en letras que en ?123) y siempre con "..." (TOOL_MORE) al final; si no caben todos a 44 dp, los
     * primeros y "...". Con la cinta de lo escrito visible se le reserva un minimo de sitio. Devuelve el limite izquierdo.
     */
    private float drawToolbar(Canvas c) {
        int n = computeTools();
        float hw = handWidth(), off = handOffset();
        float cell = 44 * dp, cy = stripHeight() / 2f;
        float right = off + hw - 6 * dp;
        float reserve = showStrip && suggestions.isEmpty() ? 72 * dp : 0;      // sitio minimo para lo escrito en Aurebesh
        int fit = (int) Math.floor((right - stripLeft - reserve) / cell) - 1;  // menos el "..."
        int shown = Math.max(0, Math.min(n, fit));
        float x = right - cell * (shown + 1);
        float left = x;
        for (int i = 0; i < toolRects.length; i++) toolRects[i].setEmpty();
        for (int i = 0; i <= shown; i++) {
            int id = i < shown ? toolsBuf[i] : TOOL_MORE;
            toolRects[i].set(x, 0, x + cell, stripHeight());
            toolRectIds[i] = id;
            float cx = x + cell / 2f;
            boolean active = (id == TOOL_HAND && handMode != 0) || (id == TOOL_SPLIT && split) || (id == TOOL_STRIP && showStrip);
            fill.setColor(toolPressed == i ? theme.pressed : active ? theme.accent : theme.specialBg);
            c.drawCircle(cx, cy, toolRadius(), fill);
            if (theme.keyStroke != 0 && !active) c.drawCircle(cx, cy, toolRadius(), strokePaint);
            iconPaint.setColor(active ? theme.onAccent : theme.icon);
            ToolIcons.draw(c, id, cx, cy, 20 * dp, iconPaint);
            x += cell;
        }
        iconPaint.setColor(theme.icon);
        return left - 6 * dp;
    }

    /** En modo a una mano, boton en el margen libre para pasar el teclado al otro lado. */
    private void drawSideSwitch(Canvas c) {
        sideRect.setEmpty();
        if (handMode == 0 || current.isEmpty()) return;
        float full = getWidth(), w = handWidth(), pad = keyMargin();
        float marginLeft = handMode == 2 ? 0 : w - pad, marginRight = handMode == 2 ? full - w + pad : full;
        float cx = (marginLeft + marginRight) / 2f, cy = stripHeight() + keysHeight() / 2f;
        float r = 20 * dp;
        sideRect.set(cx - r, cy - r, cx + r, cy + r);
        fill.setColor(theme.specialBg);
        c.drawCircle(cx, cy, r, fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, r, strokePaint);
        iconPaint.setStrokeWidth(2.6f * dp);
        float d = handMode == 2 ? -1 : 1;           // apunta hacia el margen vacio
        float s = 7 * dp;
        c.drawLine(cx - d * s, cy, cx + d * s, cy, iconPaint);
        c.drawLine(cx + d * s, cy, cx + d * s * 0.35f, cy - s * 0.65f, iconPaint);
        c.drawLine(cx + d * s, cy, cx + d * s * 0.35f, cy + s * 0.65f, iconPaint);
    }

    /**
     * Boton ∨ de ocultar el teclado: a la izquierda de la banda inferior (alineado con el boton de
     * inteligencia), con el estilo de los iconos de la cinta, solo cuando el margen llega a 24 dp. El centro y
     * la derecha de la banda quedan libres.
     */
    private void drawHideButton(Canvas c) {
        hideRect.setEmpty();
        if (bottomPad < 24 * dp || current.isEmpty()) return;
        float top = stripHeight() + keysHeight(), band = bottomBand(), off = handOffset();
        float cy = top + band / 2f, cx = off + 6 * dp + 22 * dp;
        float r = Math.min(16 * dp, band / 2f - 2 * dp);
        float air = 4 * dp;                          // nunca pegado a la fila inferior: ahi manda la tolerancia de las teclas
        hideRect.set(cx - r - air, Math.max(top + 3 * dp, cy - r - air), cx + r + air, Math.min(top + band, cy + r + air));
        fill.setColor(theme.specialBg);
        c.drawCircle(cx, cy, r, fill);
        if (theme.keyStroke != 0) c.drawCircle(cx, cy, r, strokePaint);
        iconPaint.setStrokeWidth(2.4f * dp);
        float s = 5 * dp;
        c.drawLine(cx - s, cy - s * 0.5f, cx, cy + s * 0.5f, iconPaint);
        c.drawLine(cx, cy + s * 0.5f, cx + s, cy - s * 0.5f, iconPaint);
    }

    /**
     * Modo de ajuste: velo del color del fondo sobre teclas y banda, marco alrededor de las teclas con una
     * pastilla arriba (tirador del alto) y otra abajo (tirador del margen, sin salirse de la vista), el valor
     * actual y los botones Restablecer y Listo en el centro. Las zonas de toque se guardan para onTouchEvent.
     */
    private void drawResize(Canvas c) {
        float top = stripHeight(), bottom = top + keysHeight(), h = getHeight();
        float left = handOffset() + keyMargin(), right = handOffset() + handWidth() - keyMargin();
        if (split) { left = keyMargin(); right = getWidth() - keyMargin(); }
        int ink = theme.accent == theme.keyBg ? theme.glyph : theme.accent;
        fill.setColor((theme.bg & 0x00FFFFFF) | 0xB3000000);
        c.drawRect(0, top, getWidth(), h, fill);
        iconPaint.setColor(ink);
        iconPaint.setStrokeWidth(2 * dp);
        tmp.set(left, top + dp, right, bottom - dp);
        c.drawRoundRect(tmp, 6 * dp, 6 * dp, iconPaint);
        float pw = 64 * dp, ph = 8 * dp, cx = (left + right) / 2f;
        float cyTop = top + ph / 2f + dp, cyBottom = Math.min(bottom, h - ph / 2f - 2 * dp);
        fill.setColor(ink);
        tmp.set(cx - pw / 2f, cyTop - ph / 2f, cx + pw / 2f, cyTop + ph / 2f);
        c.drawRoundRect(tmp, ph / 2f, ph / 2f, fill);
        tmp.set(cx - pw / 2f, cyBottom - ph / 2f, cx + pw / 2f, cyBottom + ph / 2f);
        c.drawRoundRect(tmp, ph / 2f, ph / 2f, fill);
        float grab = 24 * dp;                                     // zona de agarre generosa a ambos lados del borde
        resizeTopRect.set(left, Math.max(0, cyTop - grab), right, cyTop + grab);
        resizeBottomRect.set(left, cyBottom - grab, right, Math.min(h, cyBottom + grab));
        float bw = Math.min(140 * dp, (right - left - 24 * dp) / 2f), bh = 40 * dp, gap = 12 * dp;
        float cy = (top + bottom) / 2f + 8 * dp;
        resizeResetRect.set(cx - gap / 2f - bw, cy - bh / 2f, cx - gap / 2f, cy + bh / 2f);
        resizeDoneRect.set(cx + gap / 2f, cy - bh / 2f, cx + gap / 2f + bw, cy + bh / 2f);
        textPaint.setTextSize(14 * sp);
        textPaint.setColor(theme.glyph);
        c.drawText(heightPct() + " % \u00b7 " + bottomDp() + " dp", cx, cy - bh / 2f - 12 * dp, textPaint);
        drawResizeButton(c, resizeResetRect, getContext().getString(R.string.resize_reset), false, resizePressed == 2, ink);
        drawResizeButton(c, resizeDoneRect, getContext().getString(R.string.resize_done), true, resizePressed == 1, ink);
        textPaint.setColor(theme.glyph);
        iconPaint.setColor(theme.icon);
    }

    /** Pastilla con texto latino: Listo relleno del acento, Restablecer como una tecla de funcion. */
    private void drawResizeButton(Canvas c, RectF r, String label, boolean primary, boolean held, int ink) {
        fill.setColor(held ? theme.pressed : primary ? ink : theme.specialBg);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
        if (theme.keyStroke != 0 && !primary) c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, strokePaint);
        textPaint.setTextSize(14 * sp);
        textPaint.setColor(primary && !held ? theme.onAccent : theme.glyph);
        c.drawText(label, r.centerX(), r.centerY() + 5 * dp, textPaint);
    }

    /**
     * Sugerencias del corrector en una sola banda redondeada (specialBg, radio 12 dp) de stripLeft a right, con
     * las palabras separadas por lineas verticales finas; solo la que se aplicara sola lleva su pastilla de acento
     * dentro de la banda. Las celdas se reparten el ancho a partes iguales cuando todas caben asi (como Samsung
     * Keyboard); si no, cada una con su ancho medido (y el sobrante repartido) y, si ni asi caben, las ultimas se
     * omiten. Los emojis conservan su tamano. Las zonas de toque son las celdas completas, contiguas.
     */
    private void drawSuggestions(Canvas c, float h, float top, float right) {
        int n = suggestions.size();
        float[] widths = new float[n];          // ancho medido con sus margenes: el minimo de la celda
        float[] textW = new float[n];
        boolean[] emojiAt = new boolean[n];
        List<List<Glyphs.Token>> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String word = suggestions.get(i);
            emojiAt[i] = isEmoji(word);
            List<Glyphs.Token> tk = showHints || emojiAt[i] ? null : Glyphs.tokenize(word, useDigraphs);
            tokens.add(tk);
            float tw = 0;
            if (tk != null) {
                for (Glyphs.Token t : tk) tw += Glyphs.tokenWidth(t, h, textPaint);
            } else {
                textPaint.setTextSize(emojiAt[i] ? 19 * sp : 14 * sp);
                tw = textPaint.measureText(word);
            }
            textW[i] = tw;
            widths[i] = tw + (emojiAt[i] ? 16 : 22) * dp;
        }
        float inset = Math.max(2 * dp, stripHeight() / 2f - toolRadius());     // tan alta como los botones redondos
        suggestionBand.set(stripLeft, inset, right - 2 * dp, stripHeight() - inset);
        float avail = suggestionBand.width();
        if (avail <= 0) { suggestionBand.setEmpty(); return; }
        // Si no cabe todo, se sacrifican palabras por el final (nunca la primera ni la resaltada) para que los emojis se vean
        boolean[] show = new boolean[n];
        java.util.Arrays.fill(show, true);
        boolean anyEmoji = false;
        for (int i = 0; i < n; i++) anyEmoji |= emojiAt[i];
        float total;
        int shown;
        while (true) {
            total = 0;
            shown = 0;
            for (int i = 0; i < n; i++) if (show[i]) { total += widths[i]; shown++; }
            if (total <= avail || !anyEmoji) break;
            int drop = -1;
            for (int i = n - 1; i > 0; i--) {
                if (show[i] && !emojiAt[i] && !(highlighted != null && highlighted.equals(suggestions.get(i)))) { drop = i; break; }
            }
            if (drop < 0) break;
            show[drop] = false;
        }
        if (shown == 0) { suggestionBand.setEmpty(); return; }
        float widest = 0;
        for (int i = 0; i < n; i++) if (show[i]) widest = Math.max(widest, widths[i]);
        boolean equal = widest * shown <= avail;                                 // a partes iguales si la mas ancha cabe en la suya
        float extra = !equal && total <= avail ? (avail - total) / shown : 0;    // sobrante repartido para llenar la banda
        float radius = 12 * dp;
        fill.setColor(theme.specialBg);
        c.drawRoundRect(suggestionBand, radius, radius, fill);
        if (theme.keyStroke != 0) c.drawRoundRect(suggestionBand, radius, radius, strokePaint);
        dividerPaint.setColor((theme.glyph & 0x00FFFFFF) | 0x55000000);
        float x = suggestionBand.left;
        int drawn = 0;
        for (int i = 0; i < n; i++) {
            if (!show[i]) continue;
            float w = equal ? avail / shown : widths[i] + extra;
            if (x + w > suggestionBand.right + 0.5f * dp) break;             // no cabe (solo en el modo medido): se omiten
            if (drawn == shown - 1) w = suggestionBand.right - x;             // la ultima cierra la banda sin restos de redondeo
            RectF cell = new RectF(x, suggestionBand.top, x + w, suggestionBand.bottom);
            String word = suggestions.get(i);
            boolean emoji = emojiAt[i];
            boolean hl = highlighted != null && highlighted.equals(word);
            if (drawn > 0) c.drawLine(x, cell.top + 7 * dp, x, cell.bottom - 7 * dp, dividerPaint);
            if (hl) {
                tmp.set(cell.left + 3 * dp, cell.top + 3 * dp, cell.right - 3 * dp, cell.bottom - 3 * dp);
                fill.setColor(theme.accent);
                c.drawRoundRect(tmp, radius - 3 * dp, radius - 3 * dp, fill);
            }
            int ink = hl ? theme.onAccent : theme.glyph;
            glyphPaint.setColor(ink);
            textPaint.setColor(ink);
            textPaint.setTextAlign(Paint.Align.LEFT);
            c.save();
            c.clipRect(cell.left + 2 * dp, cell.top, cell.right - 2 * dp, cell.bottom);
            float gx = cell.centerX() - textW[i] / 2f;                          // centrada en su celda
            List<Glyphs.Token> tk = tokens.get(i);
            if (tk != null) {
                for (Glyphs.Token t : tk) gx += Glyphs.drawToken(c, t, gx, top, h, glyphPaint, textPaint);
            } else {
                textPaint.setTextSize(emoji ? 19 * sp : 14 * sp);
                c.drawText(word, gx, cell.centerY() + (emoji ? 7 : 5) * dp, textPaint);
            }
            c.restore();
            textPaint.setTextAlign(Paint.Align.CENTER);
            glyphPaint.setColor(theme.glyph);
            textPaint.setColor(theme.glyph);
            suggestionRects.add(cell);
            suggestionWords.add(word);
            x = cell.right;
            drawn++;
        }
    }

    /** Una sugerencia sin letras ni digitos es un emoji: se dibuja con la fuente del sistema. */
    static boolean isEmoji(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return false;
        return true;
    }

    /** Lo escrito, en Aurebesh, entre {@code left} y {@code right}; si no cabe se ve el final. */
    private void drawTranslation(Canvas c, float h, float top, float left, float right) {
        List<Glyphs.Token> tokens = stripTokens;
        float total = 0;
        for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, textPaint);
        float x = left;
        float maxW = right - left;
        if (maxW <= 0) return;
        c.save();
        c.clipRect(x, 0, x + maxW, stripHeight());
        int start = 0;
        if (total > maxW) {                                   // si no cabe, se omiten tokens enteros del principio
            float over = total - maxW, skipped = 0;
            while (start < tokens.size() && skipped < over) {
                skipped += Glyphs.tokenWidth(tokens.get(start), h, textPaint);
                start++;
            }
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = start; i < tokens.size(); i++) x += Glyphs.drawToken(c, tokens.get(i), x, top, h, glyphPaint, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.restore();
    }

    // ------------------------------------------------------------------ toque

    private Key findKey(float x, float y) {
        Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Key> row : current) {
            for (Key k : row) {
                if (k.code == CODE_GAP || k.rect.isEmpty()) continue;
                if (k.rect.contains(x, y)) return k;
                float dx = Math.max(0, Math.max(k.rect.left - x, x - k.rect.right));
                float dy = Math.max(0, Math.max(k.rect.top - y, y - k.rect.bottom));
                float d = dx * dx + dy * dy;
                if (d < bestDist) { bestDist = d; best = k; }
            }
        }
        float tol = Math.max(20 * dp, keyMargin() + 4 * dp);   // el margen lateral entero lleva a la tecla del borde
        return bestDist < tol * tol ? best : null;
    }

    /** El punto esta sobre la cinta o a mas de 24 dp de la zona de teclas: el toque se cancela al pasar por ahi. */
    private boolean farFromKeys(float x, float y) {
        if (y < stripHeight()) return true;
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE, top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (List<Key> row : current) {
            for (Key k : row) {
                if (k.code == CODE_GAP || k.rect.isEmpty()) continue;
                left = Math.min(left, k.rect.left); right = Math.max(right, k.rect.right);
                top = Math.min(top, k.rect.top); bottom = Math.max(bottom, k.rect.bottom);
            }
        }
        if (left > right) return false;
        float m = 24 * dp;
        return x < left - m || x > right + m || y < top - m || y > bottom + m;
    }

    /**
     * El dedo se alejo de las teclas (o del menu de alternativas): la tecla pulsada pasa a ser el marcador
     * CANCELLED, se paran los temporizadores y vibra el rechazo; al soltar no se escribe nada.
     */
    /** Deja el estado tactil limpio (modo de ajuste, numerico, ACTION_CANCEL): ni menu, ni dedos, ni temporizadores. */
    private void clearTouchState() {
        closePopup();
        cancelTimers();
        pressed.clear();
        resetGlide(true);
        toolPressed = -1;
        toolPointer = -1;
        toolDownId = -1;
        shiftPointer = -1;
        shiftHeldTyped = false;
        spaceDragged = false;
        spaceLangFired = false;
    }

    private void cancelPointer(int pid) {
        Key was = pressed.get(pid);
        pressed.put(pid, CANCELLED);
        if (pid == glidePointer) resetGlide(true);
        if (was != null && was.code == CODE_TEXT && shift && !capsLock && shiftPointer < 0 && !autoShifted) shift = false;
        cancelTimers();
        haptic(H_REJECT);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (resizeMode) return resizeTouch(e);                           // en el modo de ajuste nada escribe
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (glideState == GLIDE_GLIDING) finishGlide();          // un segundo dedo cierra el trazo en curso
                else if (glideState == GLIDE_PENDING) resetGlide(true);  // y el toque pendiente se suelta como toque normal
                for (int i = pressed.size() - 1; i >= 0; i--) {
                    Key prev = pressed.valueAt(i);
                    if (prev != null && prev.code == CODE_SHIFT && pressed.keyAt(i) == shiftPointer) continue;   // mayusculas sostenida
                    if (prev != null && prev == popupKey) commitPopup();
                    else if (prev != null && prev.code != CODE_DELETE && prev.code != CODE_CANCELLED
                            && !(prev.code == CODE_SPACE && spaceDragged)
                            && !(prev == longPressKey && longPressFired)) release(prev);
                    pressed.removeAt(i);
                }
                cancelTimers();
                if (e.getY(idx) < stripHeight()) {                       // barra: sugerencias, pegar o iconos
                    float tx = e.getX(idx), ty = e.getY(idx);
                    if (!aiRect.isEmpty() && aiRect.contains(tx, ty) && listener != null) {
                        feedback(special("", CODE_SPACE, 1f));
                        listener.onToolbar(TOOL_AI);
                        return true;
                    }
                    if (!pasteRect.isEmpty() && pasteRect.contains(tx, ty) && listener != null) {
                        feedback(special("", CODE_SPACE, 1f));
                        listener.onPasteSuggestion();
                        return true;
                    }
                    if (!moreRect.isEmpty() && moreRect.contains(tx, ty)) {
                        feedback(special("", CODE_SPACE, 1f));
                        toolsOverChips = true;
                        invalidate();
                        a11yChanged();
                        return true;
                    }
                    for (int i = 0; i < suggestionRects.size(); i++) {
                        if (suggestionRects.get(i).contains(tx, ty) && listener != null) {
                            feedback(special("", CODE_SPACE, 1f));
                            listener.onSuggestion(suggestionWords.get(i));
                            return true;
                        }
                    }
                    for (int i = 0; i < toolRects.length; i++) {
                        if (!toolRects[i].isEmpty() && toolRects[i].contains(tx, ty)) {
                            toolPressed = i;
                            toolPointer = id;
                            toolDownId = toolRectIds[i];                      // se resuelve al soltar contra esta copia
                            toolDownRect.set(toolRects[i]);
                            feedback(special("", CODE_SPACE, 1f));
                            invalidate();
                            return true;
                        }
                    }
                    return true;
                }
                if (!hideRect.isEmpty() && hideRect.contains(e.getX(idx), e.getY(idx))) {   // ∨: ocultar el teclado
                    feedback(special("", CODE_SPACE, 1f));
                    if (listener != null) listener.onHide();
                    return true;
                }
                if (!sideRect.isEmpty() && sideRect.contains(e.getX(idx), e.getY(idx))) {   // cambiar de lado
                    feedback(special("", CODE_SPACE, 1f));
                    if (listener != null) listener.onHandSide(handMode == 2 ? 1 : 2);
                    return true;
                }
                Key k = findKey(e.getX(idx), e.getY(idx));
                if (k == null) return true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                pressed.put(id, k);
                feedback(k);
                if (k.code == CODE_SHIFT) { shiftPointer = id; shiftHeldTyped = false; }
                if (k.code == CODE_SPACE) beginSpace(e.getX(idx));
                if (k.code == CODE_DELETE) {
                    if (listener != null) listener.onDelete();
                    startRepeat(k);
                } else {
                    startLongPress(k);
                }
                if (glideEnabled && glidePointer < 0 && glideKey(k)) {   // puede ser el principio de un trazo
                    glideState = GLIDE_PENDING;
                    glidePointer = id;
                    glideDownKey = k;
                    glideDownX = e.getX(idx);
                    glideDownY = e.getY(idx);
                    glideDownT = e.getEventTime();
                    strokeN = 0;
                    strokeAdd(glideDownX, glideDownY, glideDownT);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    Key old = pressed.get(pid);
                    if (old == null) continue;
                    if (pid == glidePointer && glideState != GLIDE_NONE) {
                        glideMove(e, i);                              // acumula puntos y decide toque o trazo
                        if (glideState == GLIDE_GLIDING) {
                            Key under = findKey(e.getX(i), e.getY(i));
                            if (under != null && under != old) pressed.put(pid, under);   // resalta la tecla bajo el dedo
                            postInvalidateOnAnimation();
                            continue;
                        }
                    }
                    float x = e.getX(i), y = e.getY(i);
                    if (popupKey != null && old == popupKey) {       // deslizar por el menu
                        if (popupAbandoned(x, y)) {                   // alejarse lo cierra sin escribir nada
                            closePopup();
                            cancelPointer(pid);
                            continue;
                        }
                        int ni = popupIndexAt(x, y);
                        if (ni != popupIndex) { popupIndex = ni; haptic(H_TICK); invalidate(); }
                        continue;
                    }
                    if (old.code == CODE_CANCELLED || old.code == CODE_DELETE) continue;
                    if (old.code == CODE_SPACE) {
                        if (farFromKeys(x, y)) { cancelPointer(pid); continue; }   // soltar fuera no escribe (ni abre el selector)
                        spaceMove(pid, x);
                        continue;
                    }
                    Key k = findKey(x, y);
                    if (k == null) {
                        if (farFromKeys(x, y)) cancelPointer(pid);    // soltar fuera no escribe
                        continue;
                    }
                    if (k != old && !longPressFired) {
                        pressed.put(pid, k);
                        if (k.code == CODE_SPACE) {
                            beginSpace(x);                               // el gesto del espacio empieza donde entra el dedo
                            if (pid == glidePointer) resetGlide(true);   // entrar en el espacio no es un trazo
                        }
                        startLongPress(k);
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (toolPressed >= 0 && id == toolPointer) {
                    int tool = toolDownId;
                    toolPressed = -1;
                    toolPointer = -1;
                    toolDownId = -1;
                    invalidate();
                    // Contra la copia tomada al bajar: los rectangulos vivos cambian si llegan chips o cambia la pagina
                    if (tool >= 0 && toolDownRect.contains(e.getX(idx), e.getY(idx))) {
                        if (listener != null) listener.onToolbar(tool);
                        if (tool == TOOL_RESIZE && !resizeMode) setResizeMode(true);   // si el oyente no lo abrio, lo abre la vista
                    }
                    return true;
                }
                if (id == glidePointer && glideState == GLIDE_GLIDING) {
                    strokeAdd(e.getX(idx), e.getY(idx), e.getEventTime());
                    finishGlide();
                    invalidate();
                    return true;
                }
                if (id == glidePointer) resetGlide(true);            // pendiente: fue un toque normal
                Key k = pressed.get(id);
                pressed.remove(id);
                boolean fromShift = id == shiftPointer;
                if (k != null && k == popupKey) {
                    commitPopup();
                    cancelTimers();
                    if (fromShift) { shiftPointer = -1; shiftHeldTyped = false; }
                    invalidate();
                    return true;
                }
                boolean consumed = k != null && ((k == longPressKey && longPressFired) || k.code == CODE_CANCELLED
                        || (k.code == CODE_SPACE && spaceDragged));
                boolean shiftLift = fromShift && k != null && k.code == CODE_SHIFT;
                if (!shiftLift || pressed.size() == 0) cancelTimers();   // los temporizadores son del otro dedo, que sigue pulsado
                if (shiftLift) {
                    // Soltar mayusculas: si se escribio algo mientras se mantenia, se apaga sin conmutar (ni bloquear);
                    // la letra que aun este pulsada sale en mayuscula y apaga shift al escribirse
                    shiftPointer = -1;
                    if (shiftHeldTyped) {
                        boolean pendingLetter = false;
                        for (int i = 0; i < pressed.size(); i++) {
                            Key p = pressed.valueAt(i);
                            if (p != null && p.code == CODE_TEXT) pendingLetter = true;
                        }
                        shift = pendingLetter;
                        autoShifted = false; shiftHeldTyped = false; lastShiftTap = 0;
                    } else release(k);
                } else if (fromShift) {
                    // Deslizar desde mayusculas hasta una letra: esa letra en mayuscula, una sola
                    if (k != null && !consumed && k.code != CODE_DELETE) release(k);
                    shiftPointer = -1;
                    shiftHeldTyped = false;
                    if (shift && !capsLock) { shift = false; autoShifted = false; }
                } else if (k != null && !consumed && k.code != CODE_DELETE) {
                    release(k);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                clearTouchState();
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    // ------------------------------------------------------------------ escritura deslizando

    /** Solo las teclas de una letra de la pagina de letras pueden empezar o terminar un trazo. */
    private boolean glideKey(Key k) {
        return k != null && current == letters && k.code == CODE_TEXT && k.output != null
                && k.output.length() == 1 && Character.isLetter(k.output.charAt(0));
    }

    private void strokeAdd(float x, float y, long t) {
        if (strokeN > 0) {
            float dx = x - strokeX[strokeN - 1], dy = y - strokeY[strokeN - 1], min = POINT_MIN_DIST_DP * dp;
            if (dx * dx + dy * dy < min * min) return;
        }
        if (strokeN == strokeX.length) {
            if (strokeN >= MAX_POINTS) {                          // diezmar por dos conservando los tiempos
                int m = 0;
                for (int i = 0; i < strokeN; i += 2) { strokeX[m] = strokeX[i]; strokeY[m] = strokeY[i]; strokeT[m] = strokeT[i]; m++; }
                strokeN = m;
            } else {
                int cap = strokeX.length * 2;
                strokeX = java.util.Arrays.copyOf(strokeX, cap);
                strokeY = java.util.Arrays.copyOf(strokeY, cap);
                strokeT = java.util.Arrays.copyOf(strokeT, cap);
            }
        }
        strokeX[strokeN] = x; strokeY[strokeN] = y; strokeT[strokeN] = t;
        strokeN++;
    }

    /** Puntos del movimiento (con el historial del evento) y paso de PENDIENTE a TRAZO o a toque. */
    private void glideMove(MotionEvent e, int i) {
        int h = e.getHistorySize();
        for (int j = 0; j < h; j++) strokeAdd(e.getHistoricalX(i, j), e.getHistoricalY(i, j), e.getHistoricalEventTime(j));
        float x = e.getX(i), y = e.getY(i);
        long t = e.getEventTime();
        strokeAdd(x, y, t);
        if (glideState != GLIDE_PENDING) return;
        if (popupKey != null || longPressFired) { resetGlide(true); return; }   // ya se abrio el menu de alternativas
        float dx = x - glideDownX, dy = y - glideDownY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        long dt = Math.max(1, t - glideDownT);
        if (dist > GLIDE_SLOP_KEYS * glideDownKey.rect.width() && dist / dt > GLIDE_MIN_SPEED * dp) {
            glideState = GLIDE_GLIDING;
            handler.removeCallbacks(longPressRunnable);          // sin menu de alternativas mientras se traza
            longPressKey = null;
            longPressFired = false;
        } else if (dt > GLIDE_DETECT_WINDOW) {
            resetGlide(true);                                    // toque (y deslizar a la vecina, como siempre)
        }
    }

    private void resetGlide(boolean clearStroke) {
        glideState = GLIDE_NONE;
        glidePointer = -1;
        glideDownKey = null;
        if (clearStroke) strokeN = 0;
    }

    /**
     * Fin del trazo: recorta los puntos finales fuera de las letras (acabar en el espacio es habitual),
     * cancela si acaba en borrar, trata como toque los trazos demasiado cortos y manda el resto al
     * reconocedor en segundo plano. La estela sigue en pantalla hasta desvanecerse.
     */
    private void finishGlide() {
        int id = glidePointer;
        Key downKey = glideDownKey;
        pressed.remove(id);
        resetGlide(false);
        cancelTimers();
        int n = strokeN;
        Key last = n > 0 ? findKey(strokeX[n - 1], strokeY[n - 1]) : null;
        if (downKey == null || n < 2 || (last != null && last.code == CODE_DELETE)) return;
        while (n > 2 && !glideKey(findKey(strokeX[n - 1], strokeY[n - 1]))) n--;
        float len = 0;
        for (int i = 1; i < n; i++) {
            float dx = strokeX[i] - strokeX[i - 1], dy = strokeY[i] - strokeY[i - 1];
            len += (float) Math.sqrt(dx * dx + dy * dy);
        }
        Key under = findKey(strokeX[n - 1], strokeY[n - 1]);
        Key tapKey = glideKey(under) ? under : downKey;         // la tecla bajo el dedo al soltar, si es de letra
        if (len < GLIDE_MIN_LEN_KEYS * downKey.rect.width()) {   // un tiron corto: toque en la tecla bajo el dedo
            strokeN = 0;
            release(tapKey);
            return;
        }
        int caseMode = capsLock ? 2 : shift || shiftPointer >= 0 ? 1 : 0;
        if (shift && !capsLock) { shift = false; autoShifted = false; }
        SwipeDecoder.Geometry geo = glideGeometry();
        if (geo == null || listener == null) return;
        final float[] x = java.util.Arrays.copyOf(strokeX, n), y = java.util.Arrays.copyOf(strokeY, n);
        final long[] t = java.util.Arrays.copyOf(strokeT, n);
        boolean shortStroke = len < 2.5f * downKey.rect.width();
        glideTask = glideExecutor().submit(new GlideJob(getContext().getApplicationContext(), language, geo, x, y, t, caseMode,
                shortStroke ? tapKey : null));
    }

    /**
     * Prepara el reconocedor en segundo plano (diccionario e indice) para que el primer trazo tras abrir el
     * teclado, girar o cambiar de idioma no espere.
     */
    private void warmGlide() {
        if (!glideEnabled) return;
        final SwipeDecoder.Geometry g = glideGeometry();
        if (g == null) return;
        SwipeDecoder dec = decoder;
        if (dec != null && dec.geometry().sameAs(g) && dec.language().equals(language)) return;
        final Context ctx = getContext().getApplicationContext();
        final String lang = language;
        glideExecutor().submit(new Runnable() {
            @Override public void run() {
                try {
                    Dictionary d = Dictionary.get(ctx, lang);
                    SwipeDecoder cur = decoder;
                    if (cur == null || !cur.matches(d, g)) decoder = new SwipeDecoder(d, g);
                } catch (Exception ignored) {
                    // sin diccionario no hay deslizado; se reintenta con el primer trazo
                }
            }
        });
    }

    /** Reconocimiento en el hilo de trabajo: carga el diccionario, (re)construye el indice si hace falta y decodifica. */
    private final class GlideJob implements Runnable {
        private final Context ctx;
        private final String lang;
        private final SwipeDecoder.Geometry geo;
        private final float[] x, y;
        private final long[] t;
        private final int caseMode;
        private final Key fallback;          // tecla que se escribe si un trazo corto no da ninguna palabra

        GlideJob(Context ctx, String lang, SwipeDecoder.Geometry geo, float[] x, float[] y, long[] t, int caseMode, Key fallback) {
            this.ctx = ctx; this.lang = lang; this.geo = geo; this.x = x; this.y = y; this.t = t; this.caseMode = caseMode;
            this.fallback = fallback;
        }

        @Override public void run() {
            List<String> words;
            try {
                Dictionary d = Dictionary.get(ctx, lang);
                SwipeDecoder dec = decoder;
                if (dec == null || !dec.matches(d, geo)) {
                    dec = new SwipeDecoder(d, geo);
                    decoder = dec;
                }
                words = dec.decode(x, y, t, x.length);
            } catch (Exception ex) {
                words = Collections.emptyList();            // sin diccionario no hay deslizado; el teclado sigue
            }
            final List<String> result = words;
            handler.post(new Runnable() {
                @Override public void run() { deliverGlide(result, caseMode, fallback); }
            });
        }
    }

    private void deliverGlide(List<String> words, int caseMode, Key fallback) {
        if (words.isEmpty()) {
            if (fallback != null && listener != null) {           // trazo corto sin palabra: cuenta como toque
                if (caseMode == 1) { shift = true; autoShifted = false; }
                release(fallback);
                invalidate();
                return;
            }
            haptic(H_REJECT);                                     // nada reconocido
            return;
        }
        if (listener == null) return;
        List<String> cased = new ArrayList<>(words.size());
        for (String w : words) cased.add(applyCase(w, caseMode));
        listener.onGlide(cased.get(0), new ArrayList<>(cased.subList(1, cased.size())));
    }

    /** 0 tal cual, 1 inicial mayuscula (shift), 2 todo mayusculas (bloqueo). */
    private static String applyCase(String w, int caseMode) {
        if (caseMode == 2) return w.toUpperCase(java.util.Locale.ROOT);
        if (caseMode == 1 && !w.isEmpty()) return w.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + w.substring(1);
        return w;
    }

    private static synchronized ExecutorService glideExecutor() {
        if (glideExecutor == null) {
            glideExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "glide");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return glideExecutor;
    }

    /** Ultima tarea de reconocimiento enviada (para pruebas: esperar a que termine). */
    Future<?> lastGlideTask() { return glideTask; }

    /**
     * Instantanea de las teclas de letras (sin la fila de numeros ni la inferior) con sus centros en px y el
     * ancho y alto de una tecla, para el reconocedor; null si la vista aun no tiene medidas.
     */
    SwipeDecoder.Geometry glideGeometry() {
        if (getWidth() == 0 || letters.isEmpty()) return null;
        List<Key> ks = new ArrayList<>();
        float keyW = 0, keyH = 0;
        for (int ri = 0; ri < letters.size() - 1; ri++) {
            if (isDigitRow(letters, ri)) continue;
            if (keyH == 0) keyH = rowHeight(letters, ri);
            for (Key k : letters.get(ri)) {
                if (k.code != CODE_TEXT || k.output == null || k.output.length() != 1 || !Character.isLetter(k.output.charAt(0))) continue;
                ks.add(k);
                if (keyW == 0 && k.weight == 1f) keyW = k.rect.width();
            }
        }
        if (ks.isEmpty() || keyW <= 0) return null;
        char[] letter = new char[ks.size()];
        float[] cx = new float[ks.size()], cy = new float[ks.size()];
        for (int i = 0; i < ks.size(); i++) {
            Key k = ks.get(i);
            letter[i] = k.output.charAt(0);
            cx[i] = k.rect.centerX();
            cy[i] = k.rect.centerY();
        }
        return new SwipeDecoder.Geometry(letter, cx, cy, keyW, keyH);
    }

    /** Mayusculas activa: pulsada, bloqueada o sostenida con otro dedo. */
    private boolean shiftActive() { return shift || capsLock || shiftPointer >= 0; }

    /**
     * Deslizar sobre la barra espaciadora. SPACE_CURSOR: un paso de cursor por cada 12 dp (seleccion si
     * mayusculas esta activa), con tic haptico; SPACE_LANG: pasada una tecla de ancho cambia de idioma una
     * vez por gesto. En ambos casos se cancela la pulsacion larga y al soltar no se escribe el espacio.
     */
    /** Estado del gesto sobre el espacio (al bajar el dedo en el o al entrar deslizando desde una vecina). */
    private void beginSpace(float x) {
        spaceDownX = x;
        spaceSteps = 0;
        spaceDragged = false;
        spaceLangFired = false;
    }

    private void spaceMove(int pid, float x) {
        Key space = pressed.get(pid);
        if (space == null || spaceSlide == SPACE_NONE) return;
        float dx = x - spaceDownX;
        if (spaceSlide == SPACE_CURSOR) {
            int steps = (int) (dx / (12 * dp));
            if (steps == spaceSteps) return;
            if (!spaceDragged) {
                spaceDragged = true;
                handler.removeCallbacks(longPressRunnable);
                longPressKey = null;
                longPressFired = false;
            }
            int dir = steps > spaceSteps ? 1 : -1;
            boolean select = capsLock || shiftPointer >= 0 || (shift && !autoShifted);   // la mayuscula automatica no selecciona
            while (spaceSteps != steps) {
                spaceSteps += dir;
                if (listener != null) listener.onCursorMove(dir, select);
            }
            haptic(H_TICK);
            return;
        }
        float unit = space.weight > 0 ? space.rect.width() / space.weight : space.rect.width();   // una tecla de ancho
        if (!spaceLangFired && Math.abs(dx) > unit) {
            spaceLangFired = true;
            spaceDragged = true;
            handler.removeCallbacks(longPressRunnable);
            longPressKey = null;
            longPressFired = false;
            spaceFlashAt = SystemClock.uptimeMillis();
            spaceFlashDir = dx > 0 ? 1 : -1;
            haptic(H_CONFIRM);
            if (listener != null) listener.onSwitchLanguage();
            invalidate();
        }
    }

    /**
     * Toque en el modo de ajuste: un dedo cada vez; bajar sobre un tirador empieza a arrastrar (el superior
     * cambia el alto, el inferior el margen), bajar y soltar sobre un boton lo acciona. Ninguna tecla responde.
     */
    private boolean resizeTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        float x = e.getX(idx), y = e.getY(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (resizePointer >= 0) return true;
                resizePointer = id;
                resizePressed = 0;
                resizeDrag = 0;
                if (resizeDoneRect.contains(x, y)) resizePressed = 1;
                else if (resizeResetRect.contains(x, y)) resizePressed = 2;
                else if (resizeTopRect.contains(x, y)) resizeDrag = 1;
                else if (resizeBottomRect.contains(x, y)) resizeDrag = 2;
                if (resizePressed != 0 || resizeDrag != 0) haptic(H_PRESS);
                resizeDragY0 = resizeLastY = screenY(e, idx);
                resizeScale0 = heightScale;
                resizePad0 = bottomPad;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(resizePointer);
                if (i < 0 || resizeDrag == 0) return true;
                float sy = screenY(e, i);
                if (Math.abs(sy - resizeLastY) < 4 * dp) return true;     // se aplica (y se vuelve a medir) cada ~4 dp
                resizeLastY = sy;
                applyResizeDrag(sy - resizeDragY0);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (id != resizePointer) return true;
                resizePointer = -1;
                if (resizeDrag != 0) {
                    applyResizeDrag(screenY(e, idx) - resizeDragY0);      // valor exacto al soltar
                    resizeDrag = 0;
                } else if (resizePressed == 1 && resizeDoneRect.contains(x, y)) {
                    finishResize();
                } else if (resizePressed == 2 && resizeResetRect.contains(x, y)) {
                    haptic(H_CONFIRM);
                    heightScale = 1f;
                    bottomPad = 0f;
                    requestLayout();
                }
                resizePressed = 0;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resizePointer = -1;
                resizeDrag = 0;
                resizePressed = 0;
                invalidate();
                return true;
        }
        return true;
    }

    /** Coordenada y en pantalla: estable aunque la vista cambie de alto durante el arrastre (su borde superior se mueve). */
    private float screenY(MotionEvent e, int i) {
        getLocationOnScreen(screenLoc);
        return e.getY(i) + screenLoc[1];
    }

    /**
     * Aplica el arrastre (dy en px, negativo hacia arriba): el tirador superior cambia el alto en % del alto de
     * las filas al 100 % (en pantallas bajas no pasa del 100 %, que es lo que se ve); el inferior, el margen en dp.
     */
    private void applyResizeDrag(float dy) {
        if (resizeDrag == 1) {
            float base = keysHeight(1f);
            if (base <= 0) return;
            int maxPct = heightClass() == 2 ? 140 : 100;
            int pct = Math.round((resizeScale0 * base - dy) / base * 100f);
            pct = Math.max(70, Math.min(maxPct, pct));
            if (pct != heightPct()) { heightScale = pct / 100f; requestLayout(); }
        } else if (resizeDrag == 2) {
            int m = Math.round((resizePad0 - dy) / dp);
            m = Math.max(0, Math.min(40, m));
            if (m != bottomDp()) { bottomPad = m * dp; requestLayout(); }
        }
        invalidate();
    }

    /** Listo: se comunican el alto y el margen elegidos (que se quedan) y se sale del modo de ajuste. */
    private void finishResize() {
        haptic(H_CONFIRM);
        int pct = heightPct(), m = bottomDp();
        resizeMode = false;
        resizeDrag = 0;
        resizePointer = -1;
        resizePressed = 0;
        clearResizeRects();
        if (listener != null) listener.onResize(pct, m);
        invalidate();
    }

    private void release(Key k) {
        if (listener == null) return;
        boolean upper = shiftActive();
        List<List<Key>> page = current;
        switch (k.code) {
            case CODE_TEXT: {
                String out = shiftActive() && (current == letters || current == symbols3) ? k.output.toUpperCase() : k.output;
                listener.onText(out);
                if (current == letters && singleLetter(k)) countHint(k.output);   // aprendiendo: una pulsacion mas de la letra
                if (shiftPointer >= 0) shiftHeldTyped = true;           // sostenida: sigue en mayusculas hasta soltarla
                else if (shift && !capsLock) { shift = false; autoShifted = false; }
                break;
            }
            case CODE_SPACE: listener.onText(" "); break;
            case CODE_ENTER: listener.onEnter(); break;
            case CODE_GLOBE: listener.onSwitchLanguage(); break;
            case CODE_SHIFT: {
                long now = System.currentTimeMillis();
                if (capsLock) { capsLock = false; shift = false; }
                else if (now - lastShiftTap < 350) { capsLock = true; shift = true; haptic(H_CONFIRM); }
                else shift = !shift;
                autoShifted = false;
                lastShiftTap = now;
                break;
            }
            case CODE_MODE:
                current = current == letters ? symbols : letters;
                requestLayout();
                break;
            case CODE_PAGE:
                current = current == symbols ? symbols2 : current == symbols2 ? symbols3 : symbols;
                requestLayout();
                break;
        }
        if (upper != shiftActive() || page != current) a11yChanged();   // otra pagina u otra caja: el lector relee los nodos
        invalidate();
    }

    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            Key k = longPressKey;
            if (k == null || listener == null) return;
            if (k.code == CODE_SPACE) {
                if (spaceDragged) return;                              // ya es un gesto de cursor o de idioma
                longPressFired = true;
                haptic(H_LONG);
                listener.onSwitchKeyboard();
            } else if (k.code == CODE_TEXT && k.alts != null) {
                longPressFired = true;
                haptic(H_LONG);
                openPopup(k);
                if (glideState == GLIDE_PENDING) resetGlide(true);   // con el menu abierto ya no hay trazo
            }
            invalidate();
        }
    };

    /**
     * Borrar mantenido, con aceleracion: cada 60 ms las primeras 12 repeticiones, luego cada 35 ms y, a
     * partir de la 30 (unos 2 s), una palabra entera cada 150 ms con un tic haptico.
     */
    private final Runnable repeatRunnable = new Runnable() {
        @Override public void run() {
            if (repeatKey == null || listener == null) return;
            repeatCount++;
            if (repeatCount >= 30) {
                listener.onDeleteWord();
                haptic(H_TICK);
                handler.postDelayed(this, 150);
            } else {
                listener.onDelete();
                handler.postDelayed(this, repeatCount < 12 ? 60 : 35);
            }
        }
    };

    private void startLongPress(Key k) {
        handler.removeCallbacks(longPressRunnable);
        longPressKey = k;
        longPressFired = false;
        handler.postDelayed(longPressRunnable, k.code == CODE_SPACE ? longPressMs + 220 : longPressMs);
    }

    private void startRepeat(Key k) {
        repeatKey = k;
        repeatCount = 0;
        handler.postDelayed(repeatRunnable, longPressMs + 40);
    }

    private void cancelTimers() {
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(repeatRunnable);
        longPressKey = null;
        longPressFired = false;
        repeatKey = null;
    }

    /** Respuesta al pulsar una tecla: vibracion de pulsacion y clic. */
    private void feedback(Key k) {
        haptic(H_PRESS);
        if (sound) KeySound.get(getContext()).play(k.code);   // clic del sistema o sonido personalizado
    }

    /**
     * Respuesta haptica por tipo (H_PRESS, H_LONG, H_TICK, H_CONFIRM, H_REJECT), siempre que la vibracion
     * este activada. Con nivel 0 se usa la del sistema (performHapticFeedback); con niveles 1-3 la pulsacion y
     * el tic son un pulso propio del vibrador (6/10/16 ms a 70/150/255) y el resto sigue siendo del sistema.
     */
    private void haptic(int kind) {
        if (!vibrate) return;
        if (hapticsLevel > 0 && (kind == H_PRESS || kind == H_TICK)) {
            int ms = hapticsLevel == 1 ? 6 : hapticsLevel == 2 ? 10 : 16;
            int amp = hapticsLevel == 1 ? 70 : hapticsLevel == 2 ? 150 : 255;
            if (vibrateOneShot(ms, amp)) return;
        }
        int sdk = Build.VERSION.SDK_INT;
        int c;
        switch (kind) {
            case H_LONG: c = HapticFeedbackConstants.LONG_PRESS; break;
            case H_TICK: c = HapticFeedbackConstants.CLOCK_TICK; break;
            case H_CONFIRM: c = sdk >= 30 ? HFC_CONFIRM : HapticFeedbackConstants.LONG_PRESS; break;
            case H_REJECT: c = sdk >= 30 ? HFC_REJECT : HapticFeedbackConstants.KEYBOARD_TAP; break;
            default: c = sdk >= 27 ? HFC_KEYBOARD_PRESS : HapticFeedbackConstants.KEYBOARD_TAP;
        }
        performHapticFeedback(c);
    }

    /** Pulso del vibrador; false si no hay vibrador o no se pudo (sin permiso), para caer en la respuesta del sistema. */
    private boolean vibrateOneShot(int ms, int amplitude) {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                int amp = v.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE;
                v.vibrate(VibrationEffect.createOneShot(ms, amp));
            } else {
                v.vibrate(ms);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ accesibilidad (lector de pantalla)

    /**
     * Ids de los nodos virtuales que ve el lector de pantalla. Las teclas llevan fila * 100 + columna dentro de
     * {@code current} (estables mientras no cambie la pagina); el resto son fijos (inteligencia, pegar, "...",
     * cambiar de lado, ocultar) o por bloques: A11Y_TOOL + id TOOL_* del icono y A11Y_SUGGESTION + indice del chip.
     */
    static final int A11Y_NONE = Integer.MIN_VALUE;
    static final int A11Y_AI = 10000, A11Y_PASTE = 10001, A11Y_MORE = 10002, A11Y_SIDE = 10003, A11Y_HIDE = 10004;
    static final int A11Y_TOOL = 20000;
    static final int A11Y_SUGGESTION = 30000;
    private static final int A11Y_KEY_ROW = 100;
    private static final String A11Y_CLASS = "android.widget.Button";
    private A11yProvider a11yProvider;
    private AccessibilityManager a11yManager;
    private int a11yHovered = A11Y_NONE;        // nodo bajo el dedo en la exploracion tactil
    private int a11yFocused = A11Y_NONE;        // nodo con el foco de accesibilidad
    private final Rect a11yRect = new Rect();

    /** Nodos virtuales para TalkBack: cada tecla, boton de la cinta y sugerencia es un boton con su nombre. */
    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (a11yProvider == null) a11yProvider = new A11yProvider();
        return a11yProvider;
    }

    private AccessibilityManager accessibilityManager() {
        if (a11yManager == null) a11yManager = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return a11yManager;
    }

    /** Hay lector de pantalla con exploracion tactil: el dedo que pasa por encima (hover) va a los nodos. */
    private boolean touchExploring() {
        AccessibilityManager am = accessibilityManager();
        return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
    }

    /**
     * Exploracion tactil (TalkBack): el hover se traduce al nodo bajo el dedo y se anuncia al entrar y salir de
     * cada uno; sin lector de pantalla sigue el comportamiento normal de la vista.
     */
    @Override
    public boolean dispatchHoverEvent(MotionEvent e) {
        if (touchExploring()) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    int id = a11yIdAt(e.getX(), e.getY());
                    setA11yHovered(id);
                    if (id != A11Y_NONE) return true;
                    break;
                }
                case MotionEvent.ACTION_HOVER_EXIT:
                    if (a11yHovered != A11Y_NONE) { setA11yHovered(A11Y_NONE); return true; }
                    break;
            }
        }
        return super.dispatchHoverEvent(e);
    }

    private void setA11yHovered(int id) {
        if (a11yHovered == id) return;
        int was = a11yHovered;
        a11yHovered = id;
        sendA11yEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        sendA11yEvent(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
    }

    /** Nodo bajo el dedo en la exploracion tactil (para pruebas); A11Y_NONE si ninguno. */
    int a11yHovered() { return a11yHovered; }

    /** Nodo con el foco de accesibilidad (para pruebas); A11Y_NONE si ninguno. */
    int a11yFocused() { return a11yFocused; }

    /**
     * Nodo virtual bajo el punto (A11Y_NONE si ninguno), con las mismas zonas que el toque: la cinta por
     * encima de las teclas y, debajo, ocultar, cambiar de lado y las teclas con la tolerancia de {@link #findKey}
     * (20 dp alrededor, el margen lateral entero), de modo que en la clase compacta (filas de 34 dp y digitos de
     * 28 dp) ninguna tecla baja de los 48 dp efectivos tampoco al explorar con el dedo.
     */
    int a11yIdAt(float x, float y) {
        if (resizeMode) return A11Y_NONE;
        if (y < stripHeight()) {
            if (!aiRect.isEmpty() && aiRect.contains(x, y)) return A11Y_AI;
            if (!pasteRect.isEmpty() && pasteRect.contains(x, y)) return A11Y_PASTE;
            if (!moreRect.isEmpty() && moreRect.contains(x, y)) return A11Y_MORE;
            for (int i = 0; i < suggestionRects.size(); i++) if (suggestionRects.get(i).contains(x, y)) return A11Y_SUGGESTION + i;
            for (int i = 0; i < toolRects.length; i++) if (!toolRects[i].isEmpty() && toolRects[i].contains(x, y)) return A11Y_TOOL + toolRectIds[i];
            return A11Y_NONE;
        }
        if (!hideRect.isEmpty() && hideRect.contains(x, y)) return A11Y_HIDE;
        if (!sideRect.isEmpty() && sideRect.contains(x, y)) return A11Y_SIDE;
        return a11yIdOf(findKey(x, y));
    }

    /** Id del nodo de una tecla del teclado actual (fila * 100 + columna); A11Y_NONE si no esta o es un hueco. */
    int a11yIdOf(Key k) {
        if (k == null || k.code == CODE_GAP) return A11Y_NONE;
        for (int r = 0; r < current.size(); r++) {
            int c = current.get(r).indexOf(k);
            if (c >= 0) return r * A11Y_KEY_ROW + c;
        }
        return A11Y_NONE;
    }

    /** Tecla de un id de nodo; null si el id no es de tecla, no existe ahora, es un hueco o aun no se coloco. */
    private Key a11yKey(int id) {
        if (id < 0 || id >= A11Y_AI) return null;
        int r = id / A11Y_KEY_ROW, c = id % A11Y_KEY_ROW;
        if (r >= current.size() || c >= current.get(r).size()) return null;
        Key k = current.get(r).get(c);
        return k.code == CODE_GAP || k.rect.isEmpty() ? null : k;
    }

    /** Zona (en la vista) de un nodo virtual tal y como se dibujo; null si ahora no existe. */
    private RectF a11yRectOf(int id) {
        if (id >= A11Y_SUGGESTION) {
            int i = id - A11Y_SUGGESTION;
            return i < suggestionRects.size() ? suggestionRects.get(i) : null;
        }
        if (id >= A11Y_TOOL) {
            int tool = id - A11Y_TOOL;
            for (int i = 0; i < toolRects.length; i++) if (!toolRects[i].isEmpty() && toolRectIds[i] == tool) return toolRects[i];
            return null;
        }
        RectF r = id == A11Y_AI ? aiRect : id == A11Y_PASTE ? pasteRect : id == A11Y_MORE ? moreRect
                : id == A11Y_SIDE ? sideRect : id == A11Y_HIDE ? hideRect : null;
        if (r != null) return r.isEmpty() ? null : r;
        Key k = a11yKey(id);
        return k == null ? null : k.rect;
    }

    /** Lo que lee el lector de pantalla por cada nodo, en el idioma de la app; null si el nodo no existe. */
    private String a11yDescription(int id) {
        Context ctx = getContext();
        if (id >= A11Y_SUGGESTION) {
            int i = id - A11Y_SUGGESTION;
            if (i >= suggestionWords.size()) return null;
            String w = suggestionWords.get(i);
            return highlighted != null && highlighted.equals(w) ? ctx.getString(R.string.a11y_suggestion_auto, w) : w;
        }
        if (id >= A11Y_TOOL) return toolName(id - A11Y_TOOL);
        switch (id) {
            case A11Y_AI: return ctx.getString(R.string.tools_label_ai);
            case A11Y_PASTE:
                if (pasteLabel != null && !pasteLabel.isEmpty()) return pasteAction + ", " + pasteLabel.replace('\n', ' ');
                return pasteThumb != null ? ctx.getString(R.string.a11y_paste_image) : pasteAction;
            case A11Y_MORE: return ctx.getString(R.string.a11y_tool_more);
            case A11Y_SIDE: return ctx.getString(handMode == 2 ? R.string.a11y_side_left : R.string.a11y_side_right);
            case A11Y_HIDE: return ctx.getString(R.string.tools_label_hide);
        }
        Key k = a11yKey(id);
        return k == null ? null : keyDescription(k);
    }

    /** Nombre de una herramienta de la cinta (el mismo que en la cuadricula "..."), con ", activado" si esta en marcha. */
    private String toolName(int tool) {
        Context ctx = getContext();
        if (tool == TOOL_MORE) return ctx.getString(R.string.a11y_tool_more);
        int res = ToolsTray.labelOf(tool);
        String name = res != 0 ? ctx.getString(res) : String.valueOf(tool);
        boolean active = (tool == TOOL_HAND && handMode != 0) || (tool == TOOL_SPLIT && split) || (tool == TOOL_STRIP && showStrip);
        return active ? ctx.getString(R.string.a11y_tool_active, name) : name;
    }

    /**
     * Nombre de una tecla: la letra latina (en mayuscula segun mayusculas, como lo que escribe), "Espacio" con el
     * idioma si se muestra, "Borrar", "Mayusculas" con su estado, la accion de intro, "Simbolos"/"Letras", la
     * pagina de signos o "Siguiente idioma".
     */
    private String keyDescription(Key k) {
        Context ctx = getContext();
        switch (k.code) {
            case CODE_TEXT: {
                String s = k.label != null && !k.label.isEmpty() ? k.label : k.output;   // la pausa del telefono se llama "Pausa"
                return shiftActive() && (current == letters || current == symbols3) ? s.toUpperCase() : s;
            }
            case CODE_SPACE: {
                String s = ctx.getString(R.string.a11y_space);
                return spaceLabel != null ? s + ", " + spaceLabel : s;
            }
            case CODE_DELETE: return ctx.getString(R.string.a11y_delete);
            case CODE_SHIFT: return ctx.getString(capsLock ? R.string.a11y_shift_lock : shift ? R.string.a11y_shift_on : R.string.a11y_shift);
            case CODE_ENTER:
                switch (enterAction) {
                    case 1: return ctx.getString(R.string.a11y_enter_search);
                    case 2: return ctx.getString(R.string.a11y_enter_send);
                    case 3: return ctx.getString(R.string.a11y_enter_next);
                    case 4: return ctx.getString(R.string.a11y_enter_done);
                    default: return ctx.getString(R.string.a11y_enter);
                }
            case CODE_MODE: return ctx.getString(current == letters ? R.string.a11y_symbols : R.string.a11y_letters);
            case CODE_PAGE: return ctx.getString(R.string.a11y_page, k.label);
            case CODE_GLOBE: return ctx.getString(R.string.a11y_globe);
            default: return k.label;
        }
    }

    /** Nodo raiz: la vista con todos sus hijos en orden de lectura (cinta, teclas fila a fila, lado y ocultar). */
    private AccessibilityNodeInfo a11yHostNode() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(this);
        onInitializeAccessibilityNodeInfo(node);
        if (!aiRect.isEmpty()) node.addChild(this, A11Y_AI);
        if (!pasteRect.isEmpty()) node.addChild(this, A11Y_PASTE);
        for (int i = 0; i < suggestionRects.size(); i++) node.addChild(this, A11Y_SUGGESTION + i);
        for (int i = 0; i < toolRects.length; i++) if (!toolRects[i].isEmpty()) node.addChild(this, A11Y_TOOL + toolRectIds[i]);
        if (!moreRect.isEmpty()) node.addChild(this, A11Y_MORE);
        for (int r = 0; r < current.size(); r++) {
            List<Key> row = current.get(r);
            for (int c = 0; c < row.size(); c++) {
                Key k = row.get(c);
                if (k.code != CODE_GAP && !k.rect.isEmpty()) node.addChild(this, r * A11Y_KEY_ROW + c);
            }
        }
        if (!sideRect.isEmpty()) node.addChild(this, A11Y_SIDE);
        if (!hideRect.isEmpty()) node.addChild(this, A11Y_HIDE);
        return node;
    }

    /** Nodo virtual: un boton con su nombre, su zona en la vista y en pantalla, y la accion de clic. */
    private AccessibilityNodeInfo a11yNode(int id) {
        RectF r = a11yRectOf(id);
        String desc = a11yDescription(id);
        if (r == null || desc == null) return null;
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(getContext().getPackageName());
        node.setClassName(A11Y_CLASS);
        node.setSource(this, id);
        node.setParent(this);
        node.setContentDescription(desc);
        node.setEnabled(true);
        node.setClickable(true);
        node.setFocusable(true);
        node.setVisibleToUser(true);
        node.setImportantForAccessibility(true);
        r.round(a11yRect);
        node.setBoundsInParent(a11yRect);
        getLocationOnScreen(screenLoc);
        a11yRect.offset(screenLoc[0], screenLoc[1]);
        node.setBoundsInScreen(a11yRect);
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        boolean focused = id == a11yFocused;
        node.setAccessibilityFocused(focused);
        node.addAction(focused ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        Key k = a11yKey(id);
        if (k != null && k.code == CODE_SHIFT) {
            node.setCheckable(true);
            node.setChecked(shift || capsLock);
        }
        return node;
    }

    /** Acciones sobre un nodo virtual: foco de accesibilidad y clic (lo mismo que un toque). */
    private boolean a11yAction(int id, int action) {
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (a11yFocused == id || a11yRectOf(id) == null) return false;
                a11yFocused = id;
                sendA11yEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                invalidate();
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (a11yFocused != id) return false;
                a11yFocused = A11Y_NONE;
                sendA11yEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                invalidate();
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                if (!a11yClick(id)) return false;
                sendA11yEvent(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
                return true;
            default:
                return false;
        }
    }

    /**
     * Clic por accesibilidad: exactamente lo que hace un toque en esa zona (inteligencia, pegar, "...", sugerencia,
     * icono de la cinta, cambiar de lado, ocultar o tecla, borrar incluido), con su misma respuesta sonora y haptica.
     */
    private boolean a11yClick(int id) {
        if (resizeMode || a11yRectOf(id) == null) return false;
        Key button = special("", CODE_SPACE, 1f);            // respuesta de los botones de la cinta, como al tocarlos
        if (id >= A11Y_SUGGESTION) {
            if (listener == null) return false;
            feedback(button);
            listener.onSuggestion(suggestionWords.get(id - A11Y_SUGGESTION));
            return true;
        }
        if (id >= A11Y_TOOL) {
            int tool = id - A11Y_TOOL;
            feedback(button);
            if (listener != null) listener.onToolbar(tool);
            if (tool == TOOL_RESIZE && !resizeMode) setResizeMode(true);
            return true;
        }
        switch (id) {
            case A11Y_AI:
                if (listener == null) return false;
                feedback(button);
                listener.onToolbar(TOOL_AI);
                return true;
            case A11Y_PASTE:
                if (listener == null) return false;
                feedback(button);
                listener.onPasteSuggestion();
                return true;
            case A11Y_MORE:
                feedback(button);
                toolsOverChips = true;
                invalidate();
                a11yChanged();
                return true;
            case A11Y_SIDE:
                if (listener == null) return false;
                feedback(button);
                listener.onHandSide(handMode == 2 ? 1 : 2);
                return true;
            case A11Y_HIDE:
                if (listener == null) return false;
                feedback(button);
                listener.onHide();
                return true;
        }
        Key k = a11yKey(id);
        if (k == null) return false;
        feedback(k);
        if (k.code == CODE_DELETE) { if (listener != null) listener.onDelete(); }
        else release(k);
        invalidate();
        return true;
    }

    /** Evento de accesibilidad sobre un nodo virtual (hover, foco, clic); nada sin lector de pantalla o sin ventana. */
    private void sendA11yEvent(int id, int type) {
        if (id == A11Y_NONE) return;
        AccessibilityManager am = accessibilityManager();
        ViewParent parent = getParent();
        if (am == null || !am.isEnabled() || parent == null) return;
        AccessibilityEvent ev = AccessibilityEvent.obtain(type);
        ev.setPackageName(getContext().getPackageName());
        ev.setClassName(A11Y_CLASS);
        ev.setEnabled(true);
        ev.setSource(this, id);
        String desc = a11yDescription(id);
        if (desc != null) ev.setContentDescription(desc);
        parent.requestSendAccessibilityEvent(this, ev);
    }

    /**
     * Los nodos virtuales cambiaron (otra pagina, otras sugerencias, otro idioma, otra cinta, mayusculas): se
     * avisa al lector de pantalla con WINDOW_CONTENT_CHANGED para que los vuelva a leer. Sin lector no cuesta nada.
     */
    private void a11yChanged() {
        AccessibilityManager am = accessibilityManager();
        if (am == null || !am.isEnabled() || getParent() == null) return;
        AccessibilityEvent ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        sendAccessibilityEventUnchecked(ev);
    }

    /** Proveedor de nodos del framework (sin androidx): la raiz es la vista y cada zona tocable es un hijo virtual. */
    private final class A11yProvider extends AccessibilityNodeProvider {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            return virtualViewId == HOST_VIEW_ID ? a11yHostNode() : a11yNode(virtualViewId);
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId == HOST_VIEW_ID) return performAccessibilityAction(action, arguments);
            return a11yAction(virtualViewId, action);
        }

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && a11yFocused != A11Y_NONE) return a11yNode(a11yFocused);
            return null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (resizeMode) setResizeMode(false);       // sin Listo no se guarda nada
        if (hintDirty) saveHintCounts();            // contadores del modo aprendiendo
        cancelTimers();
        closePopup();
        resetGlide(true);
        pressed.clear();
        shiftPointer = -1;
        shiftHeldTyped = false;
        spaceDragged = false;
        a11yHovered = A11Y_NONE;
        a11yFocused = A11Y_NONE;
    }
}
