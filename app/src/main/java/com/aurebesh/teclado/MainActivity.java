package com.aurebesh.teclado;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pantalla de la app, al estilo Ajustes de One UI 9. La pantalla principal lleva un titulo grande que se funde en
 * una barra fina al desplazarse, la tarjeta de idiomas y activacion, el buscador, "Pruebalo" (campos con el teclado
 * del sistema) y una lista de categorias: cada fila tiene icono, titulo y un resumen de una linea con el estado
 * actual, y al tocarla abre su propia pagina dentro de esta misma Activity (barra superior con flecha atras y
 * titulo; el boton o gesto atras vuelve a la lista; la pagina abierta sobrevive al giro). Las paginas se construyen
 * al abrirlas, no al arrancar, y agrupan sus filas en tarjetas redondeadas con separadores finos.
 * <p>
 * Anclado abajo, sobre la barra de navegacion del sistema y presente en la principal y en todas las paginas, va el
 * panel de vista previa: la copia funcional del teclado con, encima, un campo de una linea donde se ve lo que se
 * escribe (en Aurebesh si "Todo en Aurebesh" esta activo) y un asa con chevron para plegarlo a una barra fina. El
 * panel refleja cada cambio de ajuste al instante y su estado plegado se recuerda en Prefs.ui; el contenido lleva
 * relleno inferior igual al alto del panel para que nada quede tapado. Mientras un campo del sistema tiene el foco
 * (y su teclado esta en pantalla) la copia se retira sola y vuelve al tocar el asa.
 * <p>
 * El buscador lista las filas que coinciden (sin acentos ni mayusculas) y, al elegir una, abre su pagina, baja
 * hasta la fila y la resalta un momento. Sigue el modo claro/oscuro del sistema; en pantallas anchas el contenido
 * va en una columna centrada.
 */
public final class MainActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private float dp;
    private int surface, card, ink, secondary, divider, thumbOff, trackOff;
    private Typeface medium;
    private boolean wide;                                // pantalla interior de un plegable o tableta (>= 600 dp)
    private int screenWidthDp;
    private int contentWidth;                            // ancho util de la columna en px (reparto de las muestras de tema)

    private TextView topStatus;                          // estado del teclado bajo el boton de arriba
    private Button keyboardButton;                       // "Elegir teclado" / "Activar el teclado"

    /** Las dos pantallas: la principal (lista) y la pagina abierta, dentro del contenedor de contenido de la ventana. */
    private FrameLayout rootFrame;
    private ScrollView scroll;                           // pantalla principal
    private LinearLayout homeColumn;                     // columna de la principal (su relleno inferior deja sitio al panel)
    private View homeBar;                                // barra fina con el titulo, aparece al desplazar la principal
    private View bigHeader;                              // titulo grande de la principal (se funde al desplazar)
    private LinearLayout pageView;                       // barra superior + pageScroll
    private ScrollView pageScroll;
    private LinearLayout pageContent;                    // columna donde se coloca la tarjeta de la pagina abierta
    private TextView pageTitle;
    private View topCard, tryCard, listCard, footCard, compatCard;   // tarjetas de la principal (se ocultan al buscar)
    private LinearLayout searchResults;                  // tarjeta con los resultados del buscador
    private View searchEmpty;                            // tarjeta "Ningun ajuste coincide"

    /** Panel de vista previa anclado abajo: asa (campo o rotulo + chevron) y la copia del teclado. */
    private LinearLayout previewPanel;
    private View panelField;                             // caja del campo de una linea (solo desplegado)
    private View panelLabel;                             // rotulo "Vista previa" con su pista (solo plegado)
    private ImageView panelChevron;
    private View panelClear;                             // aspa que borra lo escrito en el campo
    private View panelHandle;
    private int panelInsetBottom;                        // alto de la barra de navegacion del sistema bajo el panel
    private boolean panelOpen = true;                    // estado elegido por el usuario (Prefs.ui)
    private boolean systemFieldFocused;                  // un campo del sistema tiene el foco: la copia se retira
    private int panelHeight;                             // ultimo alto del panel, aplicado como relleno inferior
    private int basePadBottom;                           // relleno inferior propio de las columnas (sin el panel)
    private static final String UI_PANEL_OPEN = "preview_open";

    /**
     * Categoria de la lista principal: su fila (icono, titulo, resumen) y su pagina, que se construye la primera
     * vez que se abre (o cuando el buscador necesita sus filas).
     */
    private static final class Page {
        final String key;
        final int titleRes;
        final int icon;                                  // icono de UiIcons
        LinearLayout body;                               // columna con las tarjetas de la pagina; null hasta construirse
        View row;                                        // fila de la lista principal
        TextView summary;                                // resumen de una linea bajo el titulo de la fila
        Page(String key, int titleRes, int icon) { this.key = key; this.titleRes = titleRes; this.icon = icon; }
    }
    private final java.util.LinkedHashMap<String, Page> pages = new java.util.LinkedHashMap<>();
    private Page openPage;                               // pagina abierta, o null en la lista
    private Page building;                               // pagina en construccion: register apunta sus filas
    private int pendingHomeScroll = -1, pendingPageScroll = -1;   // desplazamientos a restaurar tras el giro

    /** Cualquier cambio de preferencia (desde aqui, desde la copia del teclado o desde el IME) pone al dia la pantalla. */
    private final SharedPreferences.OnSharedPreferenceChangeListener summaryListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (key == null) return;
                    refreshControls();
                    if (Prefs.STRIP.equals(key) || Prefs.DIGRAPHS.equals(key)) applyPreviewPrefs();   // el campo del panel pasa a Aurebesh (o vuelve)
                    if (Prefs.LEARNED.equals(key) || Prefs.CUSTOM_SOUND.equals(key) || Prefs.SHORTCUTS.equals(key)
                            || Prefs.EMOJI_RECENT.equals(key) || AiModels.MODEL.equals(key)) refreshDataCard();
                }
            };
    private EditText input;
    private AurebeshTextView translation;
    private AurebeshKeyboardView preview;
    private View aiCard;
    private final java.util.List<View> themeItems = new java.util.ArrayList<>();
    private final java.util.List<String> themeIds = new java.util.ArrayList<>();   // paralelo a themeItems
    private final java.util.List<View> autoCards = new java.util.ArrayList<>();    // tarjetas Republica, Imperio y Templos (dentro de themeItems)
    private final java.util.List<ChipGroup> chipGroups = new java.util.ArrayList<>();
    private final java.util.List<View> saberChips = new java.util.ArrayList<>();   // paralelo a Prefs.SABERS
    private View splitWidthRow;                          // "Ancho de las mitades": solo con pantalla ancha o dividido en Siempre
    private Slider heightSlider, volumeSlider;
    private int liveHeightPct;                           // alto que se esta arrastrando en el deslizador (0 = el guardado)
    private TextView learnedTitle, shortcutsTitle;       // "Diccionario personal · N" y "Atajos de texto · N"
    private TextView soundCurrent;                       // "Actual: nombre" del sonido personalizado

    /** Fila registrada para el buscador: su vista, la pagina donde vive, su titulo visible y su texto normalizado. */
    private static final class Row {
        final View view;
        final Page page;
        final String label, text;
        Row(View view, Page page, String label, String text) { this.view = view; this.page = page; this.label = label; this.text = text; }
    }
    private final java.util.List<Row> rows = new java.util.ArrayList<>();
    private EditText searchField;
    private View searchClear;
    private TextView searchNone;                         // "Ningun ajuste coincide"
    private String query = "";

    /** Tarjeta "Datos en este telefono": una linea por dato, con su boton; los tamanos se calculan en un hilo corto. */
    private TextView dataClip, dataDict, dataModels, dataSound;
    private View dataClipButton, dataSoundButton;
    private boolean exportIncludeKey;                    // casilla del dialogo de exportar ajustes

    private static final int PICK_SOUND = 41;
    private static final int EXPORT_WORDS = 45;
    private static final int IMPORT_WORDS = 46;
    private static final int EXPORT_SETTINGS = 47;
    private static final int IMPORT_SETTINGS = 48;
    private static final long MAX_SOUND_BYTES = 2L * 1024 * 1024;
    private static final long MAX_IMPORT_BYTES = 4L * 1024 * 1024;
    private static final String EXPORT_APP = "aurebesh";   // marca del JSON de ajustes
    private static final int EXPORT_FORMAT = 1;
    private static final String STATE_PAGE = "page";
    private static final String STATE_HOME_SCROLL = "home_scroll";
    private static final String STATE_PAGE_SCROLL = "page_scroll";
    private static final String STATE_TRY_TEXT = "try_text";

    /** Claves de las paginas (categorias), en el orden de la lista. */
    static final String PAGE_KEYS = "keys", PAGE_STYLE = "style", PAGE_SIZE = "size", PAGE_SMART = "smart", PAGE_TOUCH = "touch",
            PAGE_CLIPBOARD = "clipboard", PAGE_AI = "ai", PAGE_PRIVACY = "privacy", PAGE_HELP = "help";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));   // idioma de la app elegido en ajustes
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        surface = getColor(R.color.surface);
        card = getColor(R.color.card);
        ink = getColor(R.color.ink);
        secondary = getColor(R.color.text_secondary);
        divider = getColor(R.color.divider);
        thumbOff = getColor(R.color.switch_thumb_off);
        trackOff = getColor(R.color.switch_track_off);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        wide = DeviceInfo.wideScreen(this);
        screenWidthDp = getResources().getConfiguration().screenWidthDp;
        contentWidth = (screenWidthDp >= 700 ? Math.min(px(640), Math.round(screenWidthDp * 0.8f * dp)) : px(screenWidthDp)) - px(40);
        cleanSectionState();
        declarePages();

        panelOpen = Prefs.ui(this).getBoolean(UI_PANEL_OPEN, true);
        basePadBottom = px(24);

        // Pantalla principal
        LinearLayout root = column();
        root.setPadding(px(16), px(16), px(16), basePadBottom);
        homeColumn = root;
        scroll = scroller(root);
        buildHeader(root);
        topCard = buildTop(root);
        buildSearch(root);
        compatCard = buildCompat(root);
        tryCard = buildTryCard(root);
        listCard = buildCategoryList(root);
        footCard = buildFooter(root);

        // Pagina abierta: barra superior (atras + titulo) y su propia columna desplazable
        buildPageView();

        // Las dos pantallas cuelgan del contenedor de contenido de la ventana: la principal (ScrollView) como primer
        // hijo, la pagina encima (oculta hasta que se abre una), la barra fina del titulo y, por ultimo, el panel de
        // vista previa anclado abajo
        setContentView(scroll);
        rootFrame = (FrameLayout) findViewById(android.R.id.content);
        pageView.setVisibility(View.GONE);
        rootFrame.addView(pageView, new FrameLayout.LayoutParams(MATCH, MATCH));
        buildHomeBar();
        buildPreviewPanel();
        if (savedInstanceState != null && savedInstanceState.getString(STATE_TRY_TEXT) != null) {
            input.setText(savedInstanceState.getString(STATE_TRY_TEXT));
            input.setSelection(input.length());
        }

        refreshControls();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(summaryListener);

        // Pagina a abrir: la guardada antes del giro, o la que pide el engrane del teclado ("ai")
        String want = savedInstanceState != null ? savedInstanceState.getString(STATE_PAGE) : null;
        if (want == null) want = pageKeyFor(getIntent().getStringExtra("section"));
        if (savedInstanceState != null) {
            pendingHomeScroll = savedInstanceState.getInt(STATE_HOME_SCROLL, -1);
            pendingPageScroll = savedInstanceState.getInt(STATE_PAGE_SCROLL, -1);
        }
        Page p = want == null ? null : pages.get(want);
        if (p != null) openPage(p, false);
        if (pendingHomeScroll > 0) {
            scroll.post(new Runnable() {
                @Override public void run() { scroll.scrollTo(0, pendingHomeScroll); pendingHomeScroll = -1; }
            });
        }
    }

    /** Clave de pagina para el extra "section" del intent (el engrane del teclado manda "ai"; se aceptan las secciones antiguas). */
    static String pageKeyFor(String section) {
        if (section == null) return null;
        switch (section) {
            case "design": return PAGE_STYLE;
            case "fold": return PAGE_SIZE;
            case "more": return PAGE_PRIVACY;
            case PAGE_KEYS: case PAGE_STYLE: case PAGE_SIZE: case PAGE_SMART: case PAGE_TOUCH:
            case PAGE_CLIPBOARD: case PAGE_AI: case PAGE_PRIVACY: case PAGE_HELP:
                return section;
            default: return null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (openPage != null) out.putString(STATE_PAGE, openPage.key);
        if (scroll != null) out.putInt(STATE_HOME_SCROLL, scroll.getScrollY());
        if (pageScroll != null) out.putInt(STATE_PAGE_SCROLL, pageScroll.getScrollY());
        if (input != null) out.putString(STATE_TRY_TEXT, input.getText().toString());
    }

    @Override
    public void onBackPressed() {
        if (openPage != null) { closePage(true); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(summaryListener);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        ScreenshotSettingsRow.onPermissionResult(this, requestCode, perms, results);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetup();
        ScreenshotSettingsRow.refresh(this);
        refreshDataCard();   // el teclado pudo copiar, aprender o fijar algo mientras tanto
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateSetup();
    }

    /**
     * Las claves sec_* (seccion abierta o cerrada de la pantalla antigua) ya no se usan: se borran de las preferencias
     * del teclado (para no despertar al IME) y de las de la pantalla.
     */
    private void cleanSectionState() {
        for (SharedPreferences p : new SharedPreferences[]{Prefs.get(this), Prefs.ui(this)}) {
            SharedPreferences.Editor clean = null;
            for (String k : p.getAll().keySet()) {
                if (!k.startsWith("sec_")) continue;
                if (clean == null) clean = p.edit();
                clean.remove(k);
            }
            if (clean != null) clean.apply();
        }
    }

    /** ScrollView de pantalla completa; en pantallas anchas el contenido va en una columna centrada de min(640 dp, 80 %). */
    private ScrollView scroller(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(surface);
        sv.setVerticalScrollBarEnabled(false);
        if (screenWidthDp >= 700) {
            int w = Math.min(px(640), Math.round(screenWidthDp * 0.8f * dp));
            FrameLayout frame = new FrameLayout(this);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(w, WRAP);
            fp.gravity = Gravity.CENTER_HORIZONTAL;
            frame.addView(content, fp);
            sv.addView(frame, new ViewGroup.LayoutParams(MATCH, WRAP));
        } else {
            sv.addView(content, new ViewGroup.LayoutParams(MATCH, WRAP));
        }
        return sv;
    }

    // ------------------------------------------------------------------ pantalla principal

    /** Titulo grande de la principal (marca en Aurebesh, nombre y lema); al desplazar se funde en la barra fina. */
    private void buildHeader(LinearLayout root) {
        LinearLayout head = column();
        head.setPadding(px(4), px(12), px(4), px(8));
        AurebeshTextView mark = new AurebeshTextView(this, 13, secondary);
        mark.setText("aurebesh");
        mark.setContentDescription(getString(R.string.app_name));
        head.addView(mark, block(0));
        TextView title = text(getString(R.string.app_name), 28, ink, true);
        head.addView(title, block(8));
        head.addView(text(getString(R.string.tagline), 15, secondary, false), block(6));
        root.addView(head, block(0));
        bigHeader = head;
    }

    /**
     * Barra fina de la principal: el nombre de la app, que aparece (y el titulo grande se funde) conforme se baja
     * por la lista, como el titulo plegable de los Ajustes de One UI. Solo se ve en la lista, no en las paginas.
     */
    private void buildHomeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(surface);
        bar.setPadding(px(20), 0, px(20), 0);
        bar.setElevation(px(2));
        TextView title = text(getString(R.string.app_name), 19, ink, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);   // el titulo grande ya se lee
        bar.setAlpha(0f);
        bar.setVisibility(View.INVISIBLE);
        homeBar = bar;
        rootFrame.addView(bar, new FrameLayout.LayoutParams(MATCH, px(56), Gravity.TOP));
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int oldX, int oldY) { updateHomeBar(); }
        });
    }

    /** Alfa de la barra fina y del titulo grande segun el desplazamiento de la principal (transicion de 72 dp). */
    private void updateHomeBar() {
        if (homeBar == null || bigHeader == null) return;
        int y = scroll.getScrollY();
        int start = Math.max(0, bigHeader.getBottom() - px(56) - px(72));
        float f = Math.max(0f, Math.min(1f, (y - start) / (float) px(72)));
        boolean on = openPage == null && f > 0f && query.isEmpty();
        homeBar.setAlpha(on ? f : 0f);
        homeBar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        bigHeader.setAlpha(1f - f * 0.85f);
    }

    /** Aviso solo fuera de Samsung One UI 9.x: el teclado se probo ahi; en otros dispositivos es experimental. */
    private View buildCompat(LinearLayout root) {
        if (DeviceInfo.optimized()) return null;
        LinearLayout c = cardColumn();
        c.setPadding(px(20), px(16), px(20), px(18));
        c.addView(text(getString(R.string.compat_title), 16, ink, true), block(0));
        c.addView(text(getString(R.string.compat_body, DeviceInfo.label()), 14, secondary, false), block(4));
        root.addView(c, block(12));
        return c;
    }

    /**
     * Lo primero de la app: los tres idiomas (cambian a la vez la app y el teclado) y el boton para
     * elegir el teclado, con el estado de la activacion debajo.
     */
    private View buildTop(LinearLayout root) {
        LinearLayout c = cardColumn();
        c.setPadding(px(20), px(18), px(20), px(20));
        c.addView(text(getString(R.string.top_lang_title), 13, secondary, true), block(0));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String current = AppLocale.current(this);
        for (int i = 0; i < TOP_LANGS.length; i++) {
            final String id = TOP_LANGS[i];
            boolean selected = id.equals(current);
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView icon = text(id.toUpperCase(java.util.Locale.ROOT), 18, selected ? card : ink, true);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(selected ? ink : divider);
            icon.setBackground(bg);
            item.addView(icon, new LinearLayout.LayoutParams(px(56), px(56)));
            TextView name = text(getString(TOP_LANG_NAMES[i]), 13, ink, selected);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(name, block(6));
            item.setBackground(ripple(0));
            item.setPadding(0, px(6), 0, px(6));
            item.setSelected(selected);
            item.setContentDescription(getString(TOP_LANG_NAMES[i]));
            a11y(item, RadioButton.class, true, null);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setLanguage(MainActivity.this, id);   // app y teclado a la vez
                    recreate();
                }
            });
            row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        c.addView(row, block(10));
        c.addView(text(getString(R.string.top_lang_hint), 13, secondary, false), block(4));
        keyboardButton = button();
        c.addView(keyboardButton, block(16));
        topStatus = text("", 14, secondary, false);
        c.addView(topStatus, block(8));
        root.addView(c, block(16));
        return c;
    }

    /**
     * Tarjeta "Pruebalo": solo los campos con el teclado del sistema (texto normal y PIN numerico); la copia del
     * teclado vive en el panel de vista previa de abajo.
     */
    private View buildTryCard(LinearLayout root) {
        LinearLayout c = cardColumn();
        LinearLayout head = column();
        head.setPadding(px(18), px(16), px(18), px(12));
        head.addView(text(getString(R.string.try_title), 17, ink, true), block(0));
        head.addView(text(getString(R.string.real_sub), 14, secondary, false), block(2));
        c.addView(head, block(0));
        buildRealFields(c);
        root.addView(c, block(12));
        return c;
    }

    private View buildFooter(LinearLayout root) {
        LinearLayout foot = column();
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView footer = text(getString(R.string.footer, getString(R.string.version)), 13, secondary, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.addView(footer, block(0));
        TextView compat = text(getString(DeviceInfo.optimized() ? R.string.footer_compat_ok : R.string.footer_compat_other,
                DeviceInfo.label()), 12, secondary, false);
        compat.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.addView(compat, block(6));
        TextView credits = text(getString(R.string.credits), 12, secondary, false);
        credits.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.addView(credits, block(6));
        root.addView(foot, block(24));
        return foot;
    }

    // ------------------------------------------------------------------ panel de vista previa

    /**
     * Panel anclado abajo (sobre la barra de navegacion, respetando sus insets), presente en la principal y en todas
     * las paginas: un asa con el campo de una linea donde se ve lo que se escribe (o, plegado, el rotulo "Vista
     * previa") y un chevron para plegarlo o desplegarlo, y debajo la copia funcional del teclado. Se construye una
     * sola vez: cambiar de pagina no lo toca. Su alto se aplica como relleno inferior de las columnas.
     */
    private void buildPreviewPanel() {
        LinearLayout panel = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card);
        float r = px(20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setElevation(px(12));
        panel.setClipToOutline(true);
        panel.setClickable(true);                        // los toques en el panel no llegan al contenido de debajo

        // Asa: campo (desplegado) o rotulo (plegado), borrar y chevron
        final LinearLayout handle = new LinearLayout(this);
        handle.setOrientation(LinearLayout.HORIZONTAL);
        handle.setGravity(Gravity.CENTER_VERTICAL);
        handle.setMinimumHeight(px(56));
        handle.setPadding(px(12), px(6), px(6), px(6));
        handle.setBackground(ripple(0x00000000));

        FrameLayout fieldBox = new FrameLayout(this);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor((divider & 0x00FFFFFF) | 0x66000000);
        fieldBg.setCornerRadius(px(14));
        fieldBox.setBackground(fieldBg);
        fieldBox.setMinimumHeight(px(44));
        input = new EditText(this);
        input.setBackground(null);
        input.setHint(R.string.try_hint);
        input.setTextColor(ink);
        input.setHintTextColor(secondary);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setPadding(px(14), px(10), px(14), px(10));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setShowSoftInputOnFocus(false);   // se escribe con la copia del teclado de abajo
        fieldBox.addView(input, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL));
        translation = new AurebeshTextView(this, 13, ink);
        translation.setPadding(px(14), 0, px(14), 0);
        translation.setVisibility(View.GONE);
        fieldBox.addView(translation, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL));
        panelField = fieldBox;
        handle.addView(fieldBox, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(px(4), 0, 0, 0);
        label.addView(iconView(UiIcons.KEYS, ink, 24), new LinearLayout.LayoutParams(px(24), px(24)));
        LinearLayout labelText = column();
        labelText.addView(text(getString(R.string.ux_preview_title), 15, ink, true), block(0));
        TextView hint = text(getString(R.string.ux_preview_hint), 13, secondary, false);
        hint.setMaxLines(1);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelText.addView(hint, block(1));
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        ltp.leftMargin = px(12);
        label.addView(labelText, ltp);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        panelLabel = label;
        handle.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final ImageView clear = iconView(UiIcons.CLEAR, secondary, 20);
        clear.setContentDescription(getString(R.string.ux_preview_clear));
        clear.setBackground(ripple(0x00000000));
        a11y(clear, Button.class, false, null);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { input.setText(""); }
        });
        clear.setVisibility(View.GONE);
        panelClear = clear;
        handle.addView(clear, new LinearLayout.LayoutParams(px(44), px(44)));

        panelChevron = iconView(UiIcons.CHEVRON_DOWN, ink, 24);
        panelChevron.setBackground(ripple(0x00000000));
        a11y(panelChevron, Button.class, false, null);
        panelChevron.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePanel(); }
        });
        handle.addView(panelChevron, new LinearLayout.LayoutParams(px(48), px(48)));
        handle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePanel(); }
        });
        handle.setContentDescription(getString(R.string.ux_preview_expand));
        panelHandle = handle;
        panel.addView(handle, block(0));

        // La copia del teclado
        preview = new AurebeshKeyboardView(this);
        preview.setLanguage(AppLocale.current(this));
        preview.setEnterAction(4);
        preview.setListener(new AurebeshKeyboardView.Listener() {
            @Override public void onText(String s) { replaceSelection(s); }
            @Override public void onEnter() { replaceSelection("\n"); }
            @Override public void onSwitchKeyboard() {}
            @Override public void onSwitchLanguage() {}
            @Override public void onHandSide(int mode) { Prefs.setHand(MainActivity.this, mode == 1 ? "left" : "right"); applyPreviewPrefs(); }
            @Override public void onPasteSuggestion() {}
            @Override public void onSuggestion(String word) { replaceSelection(word + " "); }
            @Override public void onCursorMove(int steps, boolean select) {
                int a = selStart(), b = selEnd();
                int pos = Math.max(0, Math.min(input.length(), (steps < 0 ? a : b) + steps));
                if (select) input.setSelection(steps < 0 ? pos : a, steps < 0 ? b : pos); else input.setSelection(pos);
            }
            @Override public void onDeleteWord() {
                Editable e = input.getText();
                int b = selStart(), a = b;
                while (a > 0 && Character.isWhitespace(e.charAt(a - 1))) a--;
                while (a > 0 && !Character.isWhitespace(e.charAt(a - 1))) a--;
                if (a < b) e.delete(a, b);
            }
            @Override public void onHide() {}   // en la app no hay nada que ocultar: la copia se queda
            @Override public void onResize(int heightPct, int bottomDp) {
                // Fin del modo de ajuste con tiradores: se guarda por pantalla y el oyente pone al dia deslizador y chips
                liveHeightPct = 0;
                Prefs.setHeightPct(MainActivity.this, heightPct);
                Prefs.setBottomDp(MainActivity.this, bottomDp);
                applyPreviewPrefs();
            }
            @Override public void onGlide(String best, java.util.List<String> alternatives) { replaceSelection(best + " "); }
            @Override public void onToolbar(int tool) {
                // En la copia del teclado: un emoji de muestra, pegar el portapapeles, una mano o el modo de ajuste
                if (tool == AurebeshKeyboardView.TOOL_EMOJI) {
                    replaceSelection("😊");
                } else if (tool == AurebeshKeyboardView.TOOL_CLIPBOARD) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                        if (t != null) replaceSelection(t.toString());
                    }
                } else if (tool == AurebeshKeyboardView.TOOL_HAND) {
                    Prefs.toggleHand(MainActivity.this);
                    applyPreviewPrefs();
                } else if (tool == AurebeshKeyboardView.TOOL_RESIZE) {
                    preview.setResizeMode(!preview.isResizeMode());
                } else if (tool == AurebeshKeyboardView.TOOL_SETTINGS) {
                    // ya estamos en los ajustes
                }
            }
            @Override public void onDelete() {
                Editable e = input.getText();
                int a = selStart(), b = selEnd();
                if (a != b) {
                    e.delete(a, b);
                } else if (a > 0) {
                    int n = a >= 2 && Character.isSurrogatePair(e.charAt(a - 2), e.charAt(a - 1)) ? 2 : 1;
                    e.delete(a - n, a);
                }
            }
        });
        panel.addView(preview, block(0));

        previewPanel = panel;
        FrameLayout.LayoutParams lp;
        if (screenWidthDp >= 700) {
            lp = new FrameLayout.LayoutParams(Math.min(px(640), Math.round(screenWidthDp * 0.8f * dp)), WRAP,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        } else {
            lp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        }
        rootFrame.addView(panel, lp);

        // Barra de navegacion del sistema: el panel la respeta con su relleno inferior
        panel.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int bottom = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars()).bottom : insets.getSystemWindowInsetBottom();
                if (bottom != panelInsetBottom) { panelInsetBottom = bottom; refreshPanel(); }
                return insets;
            }
        });
        // Cada cambio de alto del panel se traslada al relleno inferior de las columnas
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int rr, int b, int ol, int ot, int orr, int ob) {
                int h = b - t;
                if (h != panelHeight) { panelHeight = h; applyPanelPadding(); }
            }
        });
        // Con el foco en un campo del sistema (su teclado sale en pantalla) la copia se retira; al tocar el asa vuelve
        rootFrame.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View old, View now) {
                boolean sys = now instanceof EditText && now != input && ((EditText) now).getShowSoftInputOnFocus();
                if (sys != systemFieldFocused) { systemFieldFocused = sys; refreshPanel(); }
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(Editable s) { syncPreview(); }
        });
        translation.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int rr, int b, int ol, int ot, int orr, int ob) {
                if (rr - l != orr - ol) syncPreview();   // otro ancho: otra cola del texto cabe
            }
        });
        applyPreviewPrefs();
        syncPreview();
        refreshPanel();
        input.requestFocus();
    }

    /** Pliega o despliega el panel (si un campo del sistema tenia el foco, se lo quita y esconde su teclado). */
    void togglePanel() {
        if (systemFieldFocused) {
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && rootFrame.getWindowToken() != null) imm.hideSoftInputFromWindow(rootFrame.getWindowToken(), 0);
            systemFieldFocused = false;
            panelOpen = true;
            input.requestFocus();
        } else {
            panelOpen = !panelOpen;
        }
        Prefs.ui(this).edit().putBoolean(UI_PANEL_OPEN, panelOpen).apply();
        refreshPanel();
    }

    /** Despliega o pliega el panel desde fuera (pruebas y capturas). */
    void setPanelOpen(boolean open) {
        if (panelOpen == open) return;
        panelOpen = open;
        Prefs.ui(this).edit().putBoolean(UI_PANEL_OPEN, panelOpen).apply();
        refreshPanel();
    }

    /** True si la copia del teclado esta a la vista en el panel. */
    boolean isPanelOpen() { return panelOpen && !systemFieldFocused; }

    /** Pone el panel en su estado: desplegado (campo + teclado) o plegado (rotulo), con el chevron y el relleno de los insets. */
    private void refreshPanel() {
        if (previewPanel == null) return;
        boolean open = panelOpen && !systemFieldFocused;
        preview.setVisibility(open ? View.VISIBLE : View.GONE);
        panelField.setVisibility(open ? View.VISIBLE : View.GONE);
        panelLabel.setVisibility(open ? View.GONE : View.VISIBLE);
        panelClear.setVisibility(open && input.length() > 0 ? View.VISIBLE : View.GONE);
        panelChevron.setImageDrawable(UiIcons.drawable(open ? UiIcons.CHEVRON_DOWN : UiIcons.CHEVRON_UP, ink, px(24)));
        panelChevron.setContentDescription(getString(open ? R.string.ux_preview_collapse : R.string.ux_preview_expand));
        panelHandle.setClickable(!open);                 // plegado, toda el asa despliega; desplegado solo el chevron
        panelHandle.setImportantForAccessibility(open ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        previewPanel.setPadding(0, 0, 0, panelInsetBottom);
    }

    /** Relleno inferior de las columnas = el propio + el alto del panel, para que nada quede tapado. */
    private void applyPanelPadding() {
        int pad = basePadBottom + panelHeight;
        if (homeColumn != null) homeColumn.setPadding(homeColumn.getPaddingLeft(), homeColumn.getPaddingTop(), homeColumn.getPaddingRight(), pad);
        if (pageContent != null) pageContent.setPadding(pageContent.getPaddingLeft(), pageContent.getPaddingTop(), pageContent.getPaddingRight(), pad);
    }

    /** Campos que usan el teclado del sistema (el elegido en Ajustes): texto normal y PIN numerico (campo privado). */
    private void buildRealFields(LinearLayout root) {
        root.addView(dividerLine());
        LinearLayout c = column();
        c.setPadding(0, 0, 0, px(4));
        EditText plain = realField(R.string.real_text_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        plain.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        c.addView(plain, block(0));
        c.addView(dividerLine());
        EditText pin = realField(R.string.real_pin_hint,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);   // teclado numerico, tratado como contrasena
        pin.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        c.addView(pin, block(0));
        root.addView(c, block(0));
    }

    private EditText realField(int hint, int inputType) {
        EditText e = new EditText(this);
        e.setBackground(null);
        e.setHint(hint);
        e.setTextColor(ink);
        e.setHintTextColor(secondary);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setPadding(px(18), px(14), px(18), px(14));
        e.setSingleLine(true);
        e.setInputType(inputType);   // despues de setSingleLine para conservar el enmascarado del PIN
        return e;
    }

    // ------------------------------------------------------------------ categorias y paginas

    /** Las categorias, en el orden de la lista. Cada icono es un dibujo de UiIcons (decorativo). */
    private void declarePages() {
        pages.put(PAGE_KEYS, new Page(PAGE_KEYS, R.string.cat_keys, UiIcons.KEYS));
        pages.put(PAGE_STYLE, new Page(PAGE_STYLE, R.string.cat_style, UiIcons.STYLE));
        pages.put(PAGE_SIZE, new Page(PAGE_SIZE, R.string.cat_size, UiIcons.SIZE));
        pages.put(PAGE_SMART, new Page(PAGE_SMART, R.string.aj_sec_smart, UiIcons.SMART));
        pages.put(PAGE_TOUCH, new Page(PAGE_TOUCH, R.string.cat_touch, UiIcons.TOUCH));
        pages.put(PAGE_CLIPBOARD, new Page(PAGE_CLIPBOARD, R.string.aj_sec_clipboard, UiIcons.CLIPBOARD));
        pages.put(PAGE_AI, new Page(PAGE_AI, R.string.ai_section, UiIcons.AI));
        pages.put(PAGE_PRIVACY, new Page(PAGE_PRIVACY, R.string.cat_privacy, UiIcons.PRIVACY));
        pages.put(PAGE_HELP, new Page(PAGE_HELP, R.string.aj_sec_help, UiIcons.HELP));
    }

    /** Tarjeta con una fila por categoria: icono, titulo, resumen de una linea y ›; tocarla abre su pagina. */
    private View buildCategoryList(LinearLayout root) {
        LinearLayout wrap = column();
        TextView title = text(getString(R.string.cat_list_title), 13, secondary, true);
        title.setPadding(px(16), px(8), px(16), px(8));
        wrap.addView(title, block(0));
        LinearLayout c = cardColumn();
        for (Page p : pages.values()) categoryRow(c, p);
        wrap.addView(c, block(0));
        root.addView(wrap, block(12));
        return wrap;
    }

    private void categoryRow(LinearLayout parent, final Page p) {
        separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(64));
        row.setPadding(px(16), px(10), px(12), px(10));
        row.setBackground(ripple(0x00000000));
        ImageView icon = iconView(p.icon, ink, 22);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((divider & 0x00FFFFFF) | 0x99000000);
        bg.setCornerRadius(px(12));
        icon.setBackground(bg);
        row.addView(icon, new LinearLayout.LayoutParams(px(40), px(40)));
        LinearLayout col = column();
        col.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        col.addView(text(getString(p.titleRes), 16, ink, false), block(0));
        TextView summary = text("", 14, secondary, false);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(summary, block(2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cp.leftMargin = px(14);
        row.addView(col, cp);
        row.addView(chevronView(), new LinearLayout.LayoutParams(px(24), px(24)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPage(p, true); }
        });
        a11y(row, Button.class, false, null);
        p.row = row;
        p.summary = summary;
        describeRow(p);
        parent.addView(row, block(0));
    }

    /** Descripcion accesible de la fila de categoria: "Estilo. Republica (Noche) · sable azul · barra de herramientas". */
    private void describeRow(Page p) {
        if (p.row == null) return;
        String summary = p.summary.getText().toString();
        p.row.setContentDescription(getString(p.titleRes) + (summary.isEmpty() ? "" : ". " + summary));
    }

    /** Pantalla de pagina: barra superior con la flecha atras y el titulo, y debajo la columna desplazable de la pagina. */
    private void buildPageView() {
        pageView = column();
        pageView.setBackgroundColor(surface);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(4), px(4), px(16), px(4));
        bar.setMinimumHeight(px(56));
        ImageView back = iconView(UiIcons.BACK, ink, 24);
        back.setContentDescription(getString(R.string.aj_back));
        back.setBackground(ripple(0x00000000));
        a11y(back, Button.class, false, null);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePage(true); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(px(48), px(48)));
        pageTitle = text("", 20, ink, true);
        pageTitle.setMaxLines(1);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tp.leftMargin = px(8);
        bar.addView(pageTitle, tp);
        pageView.addView(bar, block(0));
        pageContent = column();
        pageContent.setPadding(px(16), px(4), px(16), basePadBottom);
        pageScroll = scroller(pageContent);
        pageView.addView(pageScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }

    /** Construye la pagina si hace falta (sus filas quedan registradas para el buscador y sus controles al dia). */
    private void buildPage(Page p) {
        if (p.body != null) return;
        building = p;
        LinearLayout body = column();
        p.body = body;
        switch (p.key) {
            case PAGE_KEYS: buildKeys(body); break;
            case PAGE_STYLE: buildStyle(body); break;
            case PAGE_SIZE: buildSize(body); break;
            case PAGE_SMART: buildSmart(body); break;
            case PAGE_TOUCH: buildTouch(body); break;
            case PAGE_CLIPBOARD: buildClipboard(body); break;
            case PAGE_AI: buildAi(body); break;
            case PAGE_PRIVACY: buildPrivacy(body); break;
            case PAGE_HELP: buildHelp(body); break;
            default: break;
        }
        building = null;
        refreshControls();
        if (p.key.equals(PAGE_PRIVACY)) refreshDataCard();
    }

    /** Abre una pagina: la construye si es la primera vez, la coloca en la columna y entra con un deslizamiento corto. */
    void openPage(final Page p, boolean animate) {
        buildPage(p);
        pageContent.removeAllViews();
        pageContent.addView(p.body, block(4));
        pageTitle.setText(p.titleRes);
        openPage = p;
        pageView.animate().cancel();
        pageView.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        updateHomeBar();
        if (Build.VERSION.SDK_INT >= 28) pageView.setAccessibilityPaneTitle(getString(p.titleRes));
        if (animate) {
            pageView.setAlpha(0.5f);
            pageView.setTranslationX(px(40));
            pageView.animate().alpha(1f).translationX(0f).setDuration(180).start();
            pageScroll.scrollTo(0, 0);
        } else {
            pageView.setAlpha(1f);
            pageView.setTranslationX(0f);
            if (pendingPageScroll > 0) {
                pageScroll.post(new Runnable() {
                    @Override public void run() { pageScroll.scrollTo(0, pendingPageScroll); pendingPageScroll = -1; }
                });
            }
        }
        pageTitle.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    /** Vuelve a la lista (boton o gesto atras, flecha de la barra); la principal reaparece con un fundido corto. */
    void closePage(boolean animate) {
        if (openPage == null) return;
        openPage = null;
        pageView.animate().cancel();
        pageView.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        updateHomeBar();
        if (Build.VERSION.SDK_INT >= 28) pageView.setAccessibilityPaneTitle(null);
        if (animate) {
            scroll.setAlpha(0.6f);
            scroll.animate().alpha(1f).setDuration(160).start();
        } else {
            scroll.setAlpha(1f);
        }
    }

    /** Pagina abierta, o null en la lista (para pruebas). */
    String openPageKey() { return openPage == null ? null : openPage.key; }

    // ------------------------------------------------------------------ paginas

    /** Idiomas y teclas: idioma del teclado, fila superior, digrafos, caracteres alternativos, letras latinas y Todo en Aurebesh. */
    private void buildKeys(LinearLayout body) {
        LinearLayout g = group(body, 0);
        chipRow(g, R.string.top_lang_title, getString(R.string.top_lang_hint), langGroup());
        chipRow(g, R.string.top_row_title, getString(R.string.top_row_sub), topRowGroup());
        optionRow(g, R.string.opt_digraphs, R.string.opt_digraphs_sub, Prefs.DIGRAPHS, Prefs.digraphs(this));
        optionRow(g, R.string.alt_chars_title, R.string.alt_chars_sub, R.string.alt_chars_hint, Prefs.ALT_CHARS, Prefs.altChars(this));
        g = group(body, R.string.ux_group_alphabet);
        TextView reset = chipButton(R.string.aj_hint_reset, new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Vuelven a verse todas las pistas: contadores fuera de las preferencias y de la copia del teclado
                Prefs.get(MainActivity.this).edit().remove(Prefs.HINT_COUNTS).apply();
                if (preview != null) preview.resetHintCounts();
                Toast.makeText(MainActivity.this, R.string.aj_hint_reset_done, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout resetRow = new LinearLayout(this);
        resetRow.setOrientation(LinearLayout.HORIZONTAL);
        resetRow.addView(reset, new LinearLayout.LayoutParams(WRAP, WRAP));
        chipRow(g, R.string.opt_hints, getString(R.string.aj_hint_mode_sub), hintGroup(), resetRow);
        optionRow(g, R.string.immersive_title, R.string.immersive_sub, Prefs.STRIP, Prefs.strip(this));
    }

    /** Estilo: temas (automaticos y fijos), sable de luz y barra de herramientas (sus iconos se eligen desde el teclado). */
    private void buildStyle(LinearLayout body) {
        themeRow(group(body, R.string.opt_theme));
        saberRow(group(body, 0));
        LinearLayout g = group(body, 0);
        optionRow(g, R.string.opt_toolbar, R.string.opt_toolbar_sub, Prefs.TOOLBAR, Prefs.toolbar(this));
        noteRow(g, R.string.aj_toolbar_icons_note);
    }

    /** Tamano y distribucion: alto, margen inferior, modo (una mano), dividido (con Plegables en pantallas anchas) y la nota del modo de ajuste. */
    private void buildSize(LinearLayout body) {
        LinearLayout g = group(body, 0);
        heightSlider = sliderRow(g, R.string.aj_height, R.string.aj_height_sub, 70, 140, Prefs.heightPct(this), PCT,
                new SliderListener() {
                    @Override public void onLive(int value) { liveHeightPct = value; applyPreviewPrefs(); }
                    @Override public void onSave(int value) {
                        liveHeightPct = 0;
                        Prefs.setHeightPct(MainActivity.this, value);
                        applyPreviewPrefs();
                    }
                });
        chipRow(g, R.string.aj_bottom, getString(R.string.aj_bottom_sub), bottomGroup());
        chipRow(g, R.string.aj_mode, getString(R.string.aj_mode_sub), handGroup());
        g = group(body, R.string.ux_group_split);
        if (wide) {
            LinearLayout foldText = column();
            foldText.setPadding(px(18), px(14), px(18), px(4));
            foldText.addView(text(getString(R.string.fold_section), 16, ink, false), block(0));
            foldText.addView(text(getString(R.string.fold_title), 14, ink, false), block(6));
            foldText.addView(text(getString(R.string.fold_body), 14, secondary, false), block(4));
            g.addView(foldText, block(0));
            register(foldText, getString(R.string.fold_section), getString(R.string.fold_section) + " " + getString(R.string.fold_title) + " " + getString(R.string.fold_body));
        }
        splitRows(g);
        g = group(body, 0);
        LinearLayout notes = column();
        notes.setPadding(px(18), px(14), px(18), px(16));
        notes.addView(text(getString(R.string.aj_size_from_keyboard), 14, secondary, false), block(0));
        String which = null;
        if (wide || DeviceInfo.family().startsWith("Galaxy Z Fold")) {
            // En plegables alto, margen y una mano se guardan por pantalla: decir cual se esta ajustando
            which = wide ? getString(R.string.aj_size_screen_inner, DeviceInfo.smallestWidthDp(this))
                    : getString(R.string.aj_size_screen_cover);
            notes.addView(text(which, 14, secondary, true), block(6));
        }
        g.addView(notes, block(0));
        register(notes, getString(R.string.aj_size_from_keyboard), getString(R.string.aj_size_from_keyboard) + (which == null ? "" : " " + which));
    }

    /** Filas del teclado dividido: modo (con la linea de deteccion de la pantalla) y ancho de las mitades. */
    private void splitRows(LinearLayout parent) {
        int sw = DeviceInfo.smallestWidthDp(this);
        String detected = getString(wide ? R.string.fold_detected_wide : R.string.fold_detected_phone, sw);
        chipRow(parent, R.string.opt_split, getString(R.string.aj_split_sub) + "\n" + detected,
                prefGroup(Prefs.SPLIT, "auto", SPLITS, SPLIT_NAMES));
        splitWidthRow = chipRow(parent, R.string.aj_split_width, getString(R.string.aj_split_width_sub),
                prefGroup(Prefs.SPLIT_WIDTH, "normal", SPLIT_WIDTHS, SPLIT_WIDTH_NAMES));
        refreshSplitWidth();
    }

    /** "Ancho de las mitades" solo tiene sentido donde el teclado se divide: pantalla ancha o dividido en Siempre. */
    private void refreshSplitWidth() {
        if (splitWidthRow == null) return;
        boolean show = wide || "on".equals(Prefs.splitPref(this));
        splitWidthRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Escritura inteligente: predictivo, sustitucion, mayusculas, doble espacio, aprendizaje, Star Wars, diccionario y atajos. */
    private void buildSmart(LinearLayout body) {
        LinearLayout g = group(body, R.string.ux_group_correction);
        optionRow(g, R.string.opt_autocorrect, R.string.opt_autocorrect_sub, Prefs.AUTOCORRECT, Prefs.autocorrect(this));
        optionRow(g, R.string.aj_auto_replace, R.string.aj_auto_replace_sub, Prefs.AUTO_REPLACE, Prefs.autoReplace(this));
        optionRow(g, R.string.aj_auto_caps, R.string.aj_auto_caps_sub, Prefs.AUTO_CAPS, Prefs.autoCaps(this));
        optionRow(g, R.string.opt_double_space, R.string.opt_double_space_sub, Prefs.DOUBLE_SPACE, Prefs.doubleSpace(this));
        g = group(body, R.string.ux_group_dictionary);
        optionRow(g, R.string.opt_learn, R.string.opt_learn_sub, Prefs.LEARN, Prefs.learn(this));
        chipRow(g, R.string.learn_uses_title, getString(R.string.learn_uses_sub), learnUsesGroup());
        optionRow(g, R.string.aj_starwars, R.string.aj_starwars_sub, Prefs.STARWARS, Prefs.starWars(this));
        learnedTitle = linkRow(g, R.string.aj_learned_title, R.string.aj_learned_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { showLearnedDialog(); }
        });
        shortcutsTitle = linkRow(g, R.string.aj_shortcuts_title, R.string.aj_shortcuts_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { showShortcutsDialog(); }
        });
        refreshCounts();
    }

    /** Deslizar, tocar y respuesta: deslizar para escribir, el espacio, burbuja, pulsacion larga, sonido (con el personalizado y su volumen) y vibracion. */
    private void buildTouch(LinearLayout body) {
        LinearLayout g = group(body, 0);
        optionRow(g, R.string.opt_glide, R.string.opt_glide_sub, Prefs.GLIDE, Prefs.glide(this));
        chipRow(g, R.string.aj_space_slide, getString(R.string.aj_space_slide_sub),
                prefGroup(Prefs.SPACE_SLIDE, "auto", SPACE_SLIDES, SPACE_SLIDE_NAMES));
        g = group(body, R.string.ux_group_press);
        optionRow(g, R.string.aj_preview, R.string.aj_preview_sub, Prefs.PREVIEW, Prefs.preview(this));
        chipRow(g, R.string.aj_longpress, getString(R.string.aj_longpress_sub),
                prefGroup(Prefs.LONGPRESS, "medium", LONGPRESSES, LONGPRESS_NAMES));
        g = group(body, R.string.ux_group_feedback);
        optionRow(g, R.string.opt_sound, R.string.aj_sound_sub, Prefs.SOUND, Prefs.sound(this));
        customSoundRow(g);
        volumeSlider = sliderRow(g, R.string.aj_volume, R.string.aj_volume_sub, 0, 100, Prefs.soundVolume(this), PCT,
                new SliderListener() {
                    @Override public void onLive(int value) {
                        KeySound.get(MainActivity.this).setVolume(value);   // se oye una muestra al nivel elegido
                        KeySound.get(MainActivity.this).play(0);
                    }
                    @Override public void onSave(int value) {
                        KeySound.get(MainActivity.this).setVolume(value);
                        Prefs.get(MainActivity.this).edit().putInt(Prefs.SOUND_VOLUME, value).apply();
                    }
                });
        optionRow(g, R.string.opt_vibrate, R.string.aj_vibrate_sub, Prefs.VIBRATE, Prefs.vibrate(this));
        chipRow(g, R.string.aj_vibration, getString(R.string.aj_vibration_sub),
                prefGroup(Prefs.VIBRATION, "system", VIBRATIONS, VIBRATION_NAMES));
    }

    /** Portapapeles: que se guarda y la fila de capturas de pantalla (permiso opcional de fotos). */
    private void buildClipboard(LinearLayout body) {
        LinearLayout g = group(body, 0);
        TextView text = text(getString(R.string.aj_clipboard_body), 14, secondary, false);
        text.setPadding(px(18), px(14), px(18), px(14));
        g.addView(text, block(0));
        register(text, getString(R.string.aj_sec_clipboard), getString(R.string.aj_clipboard_body));
        g = group(body, 0);
        View shots = ScreenshotSettingsRow.build(this, ink, secondary, thumbOff, trackOff);
        g.addView(shots, block(0));
        register(shots, getString(R.string.shots_title), getString(R.string.shots_title) + " " + getString(R.string.shots_sub));
    }

    /** Inteligencia: modelo local o servidor propio. */
    private void buildAi(LinearLayout body) {
        aiCard = AiSettingsCard.build(this, ink, secondary, card, divider);
        group(body, 0).addView(aiCard, block(0));
        accessibleRadios(aiCard);
        register(aiCard, getString(R.string.ai_section), getString(R.string.ai_section) + " " + getString(R.string.sum_ai_none)
                + " " + getString(R.string.sum_ai_remote, ""));
    }

    /** Ayuda y gestos: pares gesto / resultado, de la escritura a los paneles y el modo de ajuste. */
    private void buildHelp(LinearLayout body) {
        LinearLayout g = group(body, 0);
        for (int i = 0; i < GESTURES.length; i += 2) gestureRow(g, GESTURES[i], GESTURES[i + 1]);
    }

    /** Parejas gesto / resultado de "Ayuda y gestos" (su numero sale en el resumen antes de construir la pagina). */
    private static final int[] GESTURES = {
            R.string.gesture_accent, R.string.gesture_accent_result,
            R.string.gesture_enye, R.string.gesture_enye_result,
            R.string.gesture_marks, R.string.gesture_marks_result,              // mantener el punto
            R.string.aj_gesture_comma, R.string.aj_gesture_comma_result,
            R.string.gesture_caps, R.string.gesture_caps_result,
            R.string.aj_gesture_shift_hold, R.string.aj_gesture_shift_hold_result,
            R.string.aj_gesture_delete_hold, R.string.aj_gesture_delete_hold_result,
            R.string.gesture_symbols, R.string.gesture_symbols_result,
            R.string.aj_gesture_space, R.string.aj_gesture_space_result,
            R.string.gesture_picker, R.string.gesture_picker_result,
            R.string.gesture_switch, R.string.gesture_switch_result,
            R.string.gesture_glide, R.string.gesture_glide_result,
            R.string.gesture_emoji, R.string.gesture_emoji_result,
            R.string.gesture_clipboard, R.string.gesture_clipboard_result,
            R.string.gesture_ai, R.string.gesture_ai_result,
            R.string.gesture_gear, R.string.gesture_gear_result,
            R.string.aj_gesture_resize, R.string.aj_gesture_resize_result,
            R.string.aj_gesture_hide, R.string.aj_gesture_hide_result};

    /**
     * Fila "Sable de luz": un chip por color (circulo del color + nombre), solo el color. La copia del teclado
     * cambia la estela al instante. En telefonos, dos filas de tres.
     */
    private void saberRow(LinearLayout parent) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(R.string.aj_saber), 16, ink, false), block(0));
        col.addView(text(getString(R.string.aj_saber_sub), 14, secondary, false), block(2));
        int n = Prefs.SABERS.length;
        int perRow = contentWidth - px(36) >= px(104) * n ? n : (n + 1) / 2;
        StringBuilder searchable = new StringBuilder(getString(R.string.aj_saber)).append(' ').append(getString(R.string.aj_saber_sub));
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, block(i == 0 ? 10 : 8));
            }
            final String id = Prefs.SABERS[i];
            String name = getString(SABER_NAMES[i]);
            searchable.append(' ').append(name);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER);
            chip.setMinimumHeight(px(40));
            chip.setPadding(px(8), px(9), px(10), px(9));
            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Prefs.SABER_ARGB[i]);
            circle.setStroke(Math.max(1, px(1)), 0x33000000);
            dot.setBackground(circle);
            chip.addView(dot, new LinearLayout.LayoutParams(px(14), px(14)));
            TextView label = text(name, 13, ink, true);
            label.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.leftMargin = px(6);
            chip.addView(label, lp);
            chip.setTag(label);
            chip.setContentDescription(name);
            a11y(chip, RadioButton.class, true, null);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.get(MainActivity.this).edit().putString(Prefs.SABER, id).apply();
                    refreshSaber();
                    applyPreviewPrefs();
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i % perRow > 0) cp.leftMargin = px(8);
            row.addView(chip, cp);
            saberChips.add(chip);
        }
        for (int i = n; i % perRow != 0; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        refreshSaber();
        parent.addView(col, block(0));
        register(col, getString(R.string.aj_saber), searchable.toString());
    }

    /** Marca el chip del sable guardado (tambien si lo cambio el IME). */
    private void refreshSaber() {
        if (saberChips.isEmpty()) return;
        String cur = Prefs.saber(this);
        for (int i = 0; i < saberChips.size(); i++) {
            View chip = saberChips.get(i);
            boolean sel = Prefs.SABERS[i].equals(cur);
            chip.setSelected(sel);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(sel ? ink : divider);
            bg.setCornerRadius(px(12));
            chip.setBackground(bg);
            ((TextView) chip.getTag()).setTextColor(sel ? card : ink);
        }
    }

    // ------------------------------------------------------------------ Privacidad y datos

    /**
     * Claves que "Restablecer ajustes" devuelve a fabrica (y las que viajan en "Exportar ajustes"). Lista blanca
     * explicita: nunca el diccionario personal, las candidatas, los atajos, los pares, el portapapeles, los emojis
     * recientes, las claves ai_* ni el sonido personalizado. "dark_theme" es el tema oscuro antiguo (Prefs lo migra);
     * NUMBERS es el interruptor antiguo de la fila de numeros, que TOP_ROW sustituye (se mantiene en paralelo).
     */
    static final String[] RESET_KEYS = {Prefs.HINTS, Prefs.HINT_MODE, Prefs.VIBRATE, Prefs.DIGRAPHS, Prefs.STRIP,
            Prefs.THEME, "dark_theme", Prefs.NUMBERS, Prefs.TOP_ROW, Prefs.SOUND, Prefs.HEIGHT, Prefs.HEIGHT_PCT, Prefs.HEIGHT_PCT + "_wide",
            Prefs.BOTTOM_DP, Prefs.BOTTOM_DP + "_wide", Prefs.AUTOCORRECT, Prefs.AUTO_REPLACE, Prefs.AUTO_CAPS, Prefs.PREVIEW,
            Prefs.HAND, Prefs.HAND + "_wide", Prefs.HAND_LAST, Prefs.TOOLBAR, Prefs.TOOLBAR_ITEMS, Prefs.LEARN,
            Prefs.DOUBLE_SPACE, Prefs.GLIDE, Prefs.SCREENSHOTS, Prefs.APP_LANG, Prefs.KEYBOARD_LANG, Prefs.SPACE_SLIDE,
            Prefs.LONGPRESS, Prefs.VIBRATION, Prefs.SOUND_VOLUME, Prefs.SPLIT, Prefs.SPLIT_WIDTH, Prefs.SABER, Prefs.STARWARS,
            Prefs.ALT_CHARS, Prefs.LEARN_USES};

    /** Claves del servidor propio: solo se exportan con la casilla marcada (la clave nunca sin ella). */
    private static final String[] REMOTE_KEYS = {AiModels.MODE, AiModels.REMOTE_URL, AiModels.REMOTE_MODEL, AiModels.REMOTE_KEY};

    static final int TYPE_NONE = 0, TYPE_BOOL = 1, TYPE_INT = 2, TYPE_STRING = 3;

    /** Tipo que debe tener cada clave al importar (lo que no esta en la lista blanca no se importa). */
    static int keyType(String key) {
        if (key == null) return TYPE_NONE;
        switch (key) {
            case Prefs.HINTS: case Prefs.VIBRATE: case Prefs.DIGRAPHS: case Prefs.STRIP: case "dark_theme": case Prefs.NUMBERS:
            case Prefs.SOUND: case Prefs.AUTOCORRECT: case Prefs.AUTO_REPLACE: case Prefs.AUTO_CAPS: case Prefs.PREVIEW:
            case Prefs.TOOLBAR: case Prefs.LEARN: case Prefs.DOUBLE_SPACE: case Prefs.GLIDE: case Prefs.SCREENSHOTS:
            case Prefs.STARWARS: case Prefs.ALT_CHARS:
                return TYPE_BOOL;
            case Prefs.SOUND_VOLUME: case Prefs.LEARN_USES:
                return TYPE_INT;
            case Prefs.HINT_MODE: case Prefs.THEME: case Prefs.HEIGHT: case Prefs.HAND: case Prefs.HAND_LAST:
            case Prefs.TOOLBAR_ITEMS: case Prefs.APP_LANG: case Prefs.KEYBOARD_LANG: case Prefs.SPACE_SLIDE:
            case Prefs.LONGPRESS: case Prefs.VIBRATION: case Prefs.SPLIT: case Prefs.SPLIT_WIDTH: case Prefs.SABER: case Prefs.TOP_ROW:
            case AiModels.MODE: case AiModels.REMOTE_URL: case AiModels.REMOTE_MODEL: case AiModels.REMOTE_KEY:
                return TYPE_STRING;
            default:
                if (key.equals(Prefs.HEIGHT_PCT) || key.equals(Prefs.HEIGHT_PCT + "_wide")
                        || key.equals(Prefs.BOTTOM_DP) || key.equals(Prefs.BOTTOM_DP + "_wide")) return TYPE_INT;
                if (key.equals(Prefs.HAND + "_wide")) return TYPE_STRING;
                return TYPE_NONE;
        }
    }

    private static boolean inList(String[] list, String key) {
        for (String k : list) if (k.equals(key)) return true;
        return false;
    }

    /** Privacidad y datos: restablecer, borrar datos personales, que hay guardado en el telefono y exportar/importar. */
    private void buildPrivacy(LinearLayout body) {
        LinearLayout g = group(body, 0);
        actionRow(g, R.string.aj_reset, R.string.aj_reset_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReset(); }
        });
        actionRow(g, R.string.aj_wipe, R.string.aj_wipe_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmWipe(); }
        });
        dataCard(group(body, 0));
        g = group(body, R.string.ux_group_backup);
        actionRow(g, R.string.aj_export_settings, R.string.aj_export_settings_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { askExportSettings(); }
        });
        actionRow(g, R.string.aj_import_settings, R.string.aj_import_settings_sub, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                startPicker(i, IMPORT_SETTINGS);
            }
        });
    }

    /** Fila pulsable con titulo, subtitulo y ›. */
    private View actionRow(LinearLayout parent, int title, int sub, View.OnClickListener onClick) {
        separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(56));
        row.setPadding(px(18), px(12), px(14), px(12));
        row.setBackground(ripple(0x00000000));
        LinearLayout col = column();
        col.addView(text(getString(title), 16, ink, false), block(0));
        col.addView(text(getString(sub), 14, secondary, false), block(2));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(chevronView(), new LinearLayout.LayoutParams(px(24), px(24)));
        row.setOnClickListener(onClick);
        a11y(row, Button.class, false, null);
        parent.addView(row, block(0));
        register(row, getString(title), getString(title) + " " + getString(sub));
        return row;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.aj_reset)
                .setMessage(R.string.aj_reset_confirm)
                .setPositiveButton(R.string.aj_reset, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        resetSettings();
                        Toast.makeText(MainActivity.this, R.string.aj_reset_done, Toast.LENGTH_SHORT).show();
                        recreate();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Quita de las preferencias solo las claves de la lista blanca; devuelve cuantas habia. */
    int resetSettings() {
        SharedPreferences p = Prefs.get(this);
        SharedPreferences.Editor e = p.edit();
        int n = 0;
        for (String k : RESET_KEYS) {
            if (p.contains(k)) { e.remove(k); n++; }
        }
        e.apply();
        return n;
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.aj_wipe)
                .setMessage(R.string.aj_wipe_confirm)
                .setPositiveButton(R.string.aj_wipe_action, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        wipePersonalData();
                        Toast.makeText(MainActivity.this, R.string.aj_wipe_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Borra lo personal: diccionario y candidatas, pares, portapapeles (con fijados), emojis recientes y atajos. Los ajustes se quedan. */
    void wipePersonalData() {
        Prefs.clearLearnedWords(this);
        Prefs.clearCandidates(this);
        Learning.clear(this);                       // tambien los candidatos pendientes del diccionario personal
        Bigrams.forget(this);
        ClipHistory.clear(this, true);
        Prefs.get(this).edit().remove(Prefs.EMOJI_RECENT).remove(Prefs.SHORTCUTS).apply();
        refreshCounts();
        refreshDataCard();
    }

    /**
     * Tarjeta "Datos en este telefono": portapapeles (Vaciar), diccionario personal (Ver), modelos (Ver) y sonido
     * personalizado (Quitar), con la linea "Nada de esto sale del telefono". Los tamanos los calcula refreshDataCard.
     */
    private void dataCard(LinearLayout parent) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(R.string.aj_data_title), 16, ink, false), block(0));
        dataClip = text("", 14, ink, false);
        dataClipButton = dataLine(col, dataClip, R.string.aj_empty, new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(R.string.aj_clip_confirm)
                        .setPositiveButton(R.string.aj_empty, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                ClipHistory.clear(MainActivity.this, true);
                                refreshDataCard();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        dataDict = text("", 14, ink, false);
        dataLine(col, dataDict, R.string.aj_view, new View.OnClickListener() {
            @Override public void onClick(View v) { showLearnedDialog(); }
        });
        dataModels = text("", 14, ink, false);
        dataLine(col, dataModels, R.string.aj_view, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Page ai = pages.get(PAGE_AI);
                if (ai != null) openPage(ai, true);
            }
        });
        dataSound = text("", 14, ink, false);
        dataSoundButton = dataLine(col, dataSound, R.string.aj_remove_sound, new View.OnClickListener() {
            @Override public void onClick(View v) { removeCustomSound(); }
        });
        TextView local = text(getString(R.string.aj_data_local), 14, secondary, true);
        col.addView(local, block(10));
        parent.addView(col, block(0));
        register(col, getString(R.string.aj_data_title), getString(R.string.aj_data_title) + " " + getString(R.string.aj_data_clip, 0, "")
                + " " + getString(R.string.aj_data_dict, 0, 0) + " " + getString(R.string.aj_data_models, 0, "")
                + " " + getString(R.string.aj_data_sound, "") + " " + getString(R.string.aj_data_local));
    }

    /** Una linea de la tarjeta de datos: texto a la izquierda y un boton de chip a la derecha. Devuelve el boton. */
    private View dataLine(LinearLayout col, TextView label, int action, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView button = chipButton(action, onClick);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, WRAP);
        bp.leftMargin = px(10);
        row.addView(button, bp);
        col.addView(row, block(10));
        return button;
    }

    /** "1,2 KB", "352 MB"... (0 = nada). */
    static String sizeText(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return AiModels.sizeLabel(bytes);
    }

    /** Cuenta y mide en un hilo corto lo guardado en el telefono y lo pinta al volver al principal. */
    private void refreshDataCard() {
        if (dataClip == null) return;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                int clips = 0;
                long clipBytes = 0;
                try {
                    for (ClipHistory.Entry e : ClipHistory.items(app)) {
                        clips++;
                        if (e.isImage()) { java.io.File f = e.file(); if (f != null) clipBytes += f.length(); }
                        else if (e.text != null) clipBytes += e.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    }
                } catch (RuntimeException ignored) { }
                int words = Prefs.learnedWords(app).size();
                int pairs = Bigrams.userPairCount(app);
                int models = 0;
                long modelBytes = 0;
                for (AiModels.Entry e : AiModels.entries(app)) {
                    if (e.installed) { models++; modelBytes += e.size; }
                }
                java.io.File soundFile = new java.io.File(app.getFilesDir(), KeySound.FILE);
                String sound = Prefs.customSound(app);
                final String clipText = getString(R.string.aj_data_clip, clips, sizeText(clipBytes));
                final String dictText = getString(R.string.aj_data_dict, words, pairs);
                final String modelText = getString(R.string.aj_data_models, models, sizeText(modelBytes));
                final String soundText = getString(R.string.aj_data_sound, sound == null ? getString(R.string.aj_data_sound_none)
                        : sound + " · " + sizeText(soundFile.length()));
                final boolean hasClips = clips > 0, hasSound = sound != null;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed() || dataClip == null) return;
                        dataClip.setText(clipText);
                        dataDict.setText(dictText);
                        dataModels.setText(modelText);
                        dataSound.setText(soundText);
                        dataClipButton.setVisibility(hasClips ? View.VISIBLE : View.INVISIBLE);
                        dataSoundButton.setVisibility(hasSound ? View.VISIBLE : View.INVISIBLE);
                    }
                });
            }
        }, "aurebesh-datos").start();
    }

    private void removeCustomSound() {
        new java.io.File(getFilesDir(), KeySound.FILE).delete();
        Prefs.get(this).edit().remove(Prefs.CUSTOM_SOUND).apply();
        KeySound.get(this).reload(this);
        refreshSoundRow();
        refreshDataCard();
    }

    /** Pregunta si incluir el servidor propio (URL, modelo y clave) y abre el selector para guardar el JSON. */
    private void askExportSettings() {
        final CheckBox include = new CheckBox(this);
        include.setText(R.string.aj_export_include_key);
        include.setTextColor(ink);
        include.setChecked(false);
        include.setButtonTintList(ColorStateList.valueOf(ink));
        LinearLayout box = column();
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(text(getString(R.string.aj_export_settings_sub), 14, secondary, false), block(0));
        box.addView(include, block(10));
        new AlertDialog.Builder(this)
                .setTitle(R.string.aj_export_settings)
                .setView(box)
                .setPositiveButton(R.string.aj_export, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        exportIncludeKey = include.isChecked();
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("application/json");
                        i.putExtra(Intent.EXTRA_TITLE, "aurebesh-ajustes.json");
                        startPicker(i, EXPORT_SETTINGS);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startPicker(Intent i, int code) {
        try {
            startActivityForResult(i, code);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.aj_file_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * JSON con los ajustes de la lista blanca (con su tipo), los atajos, el diccionario personal y los pares
     * aprendidos. El servidor propio (URL, modelo y clave) solo con {@code includeRemote}.
     */
    String exportJson(boolean includeRemote) {
        JSONObject out = new JSONObject();
        try {
            out.put("app", EXPORT_APP);
            out.put("format", EXPORT_FORMAT);
            out.put("exported", System.currentTimeMillis());
            JSONObject settings = new JSONObject();
            for (java.util.Map.Entry<String, ?> e : Prefs.get(this).getAll().entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                boolean allowed = inList(RESET_KEYS, k) || (includeRemote && inList(REMOTE_KEYS, k));
                if (!allowed || v == null) continue;
                if (v instanceof Boolean || v instanceof Integer || v instanceof String) settings.put(k, v);
            }
            out.put("settings", settings);
            JSONObject shortcuts = new JSONObject();
            for (java.util.Map.Entry<String, String> e : Prefs.shortcuts(this).entrySet()) shortcuts.put(e.getKey(), e.getValue());
            out.put("shortcuts", shortcuts);
            JSONArray learned = new JSONArray();
            for (String w : learnedList()) learned.put(w);
            out.put("learned", learned);
            JSONArray pairs = new JSONArray();
            Bigrams.flush(this);
            for (String line : Bigrams.store(this).getString(Bigrams.PREF_KEY, "").split("\n")) {
                if (!line.isEmpty()) pairs.put(line);
            }
            out.put("pairs", pairs);
            return out.toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * Aplica un JSON exportado por esta app: solo claves de la lista blanca con el tipo esperado (enteros dentro de
     * su rango), atajos validos, palabras del diccionario y pares "previa palabra n". Devuelve cuantos ajustes
     * entraron, o -1 si el archivo no es una exportacion de este teclado.
     */
    int importJson(String raw) {
        JSONObject in;
        try {
            in = new JSONObject(raw);
        } catch (JSONException e) {
            return -1;
        }
        if (!EXPORT_APP.equals(in.optString("app")) || !in.has("settings")) return -1;
        JSONObject settings = in.optJSONObject("settings");
        if (settings == null) return -1;
        SharedPreferences.Editor e = Prefs.get(this).edit();
        int n = 0;
        java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = settings.opt(k);
            switch (keyType(k)) {
                case TYPE_BOOL:
                    if (v instanceof Boolean) { e.putBoolean(k, (Boolean) v); n++; }
                    break;
                case TYPE_INT:
                    if (v instanceof Integer) {
                        int i = (Integer) v;
                        if (k.startsWith(Prefs.HEIGHT_PCT)) i = Prefs.clamp(i, 70, 140);
                        else if (k.startsWith(Prefs.BOTTOM_DP)) i = Prefs.clamp(i, 0, 40);
                        else if (k.equals(Prefs.LEARN_USES)) i = i == 2 || i == 5 ? i : 3;
                        else i = Prefs.clamp(i, 0, 100);
                        e.putInt(k, i);
                        n++;
                    }
                    break;
                case TYPE_STRING:
                    if (v instanceof String && ((String) v).length() <= 500) { e.putString(k, (String) v); n++; }
                    break;
                default:
                    break;   // clave desconocida: no se importa
            }
        }
        e.apply();
        JSONObject shortcuts = in.optJSONObject("shortcuts");
        if (shortcuts != null) {
            java.util.Iterator<String> sk = shortcuts.keys();
            while (sk.hasNext()) {
                String k = sk.next();
                String x = shortcuts.optString(k, null);
                if (validShortcutKey(k) && x != null && !x.isEmpty()) Prefs.addShortcut(this, k, x);
            }
        }
        JSONArray learned = in.optJSONArray("learned");
        if (learned != null) {
            for (int i = learned.length() - 1; i >= 0; i--) {   // de atras adelante: la primera del archivo queda la mas reciente
                String w = learned.optString(i, null);
                if (w != null) Prefs.addLearnedWord(this, w);
            }
        }
        JSONArray pairs = in.optJSONArray("pairs");
        if (pairs != null && pairs.length() > 0) mergePairs(pairs);
        return n;
    }

    /** Suma pares "previa palabra n" a los aprendidos (se queda el mayor contador) y hace que Bigrams los relea. */
    private void mergePairs(JSONArray pairs) {
        Bigrams.flush(this);
        java.util.LinkedHashMap<String, Integer> all = new java.util.LinkedHashMap<>();
        for (String line : Bigrams.store(this).getString(Bigrams.PREF_KEY, "").split("\n")) {
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) continue;
            try { all.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1))); } catch (NumberFormatException ignored) { }
        }
        for (int i = 0; i < pairs.length(); i++) {
            String line = pairs.optString(i, "");
            String[] parts = line.split(" ");
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[0].length() > 30 || parts[1].length() > 30) continue;
            int count;
            try { count = Integer.parseInt(parts[2]); } catch (NumberFormatException ex) { continue; }
            if (count <= 0) continue;
            String key = parts[0] + ' ' + parts[1];
            Integer old = all.get(key);
            if (old == null || old < count) all.put(key, count);
        }
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> en : all.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(en.getKey()).append(' ').append(en.getValue());
        }
        Bigrams.store(this).edit().putString(Bigrams.PREF_KEY, b.toString()).apply();
        Bigrams.unload();
    }

    // ------------------------------------------------------------------ estado

    private void updateSetup() {
        if (keyboardButton == null) return;
        boolean enabled = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (info.getPackageName().equals(getPackageName())) enabled = true;
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");

        if (selected) {
            topStatus.setText(R.string.top_status_done);
            styleButton(keyboardButton, false);
            keyboardButton.setText(R.string.top_keyboard_choose);
            keyboardButton.setOnClickListener(showPicker);
        } else if (enabled) {
            topStatus.setText(R.string.top_status_choose);
            styleButton(keyboardButton, true);
            keyboardButton.setText(R.string.top_keyboard_choose);
            keyboardButton.setOnClickListener(showPicker);
        } else {
            topStatus.setText(R.string.top_status_enable);
            styleButton(keyboardButton, true);
            keyboardButton.setText(R.string.top_keyboard_enable);
            keyboardButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                }
            });
        }
    }

    private final View.OnClickListener showPicker = new View.OnClickListener() {
        @Override public void onClick(View v) {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
        }
    };

    /** La copia del teclado recibe todo lo que recibe el IME (mas el alto que se este arrastrando en el deslizador). */
    private void applyPreviewPrefs() {
        if (preview == null) return;
        float scale = liveHeightPct > 0 ? liveHeightPct / 100f : Prefs.heightScale(this);
        preview.applyPrefs(Prefs.hints(this), Prefs.vibrate(this), Prefs.strip(this), Prefs.digraphs(this),
                Prefs.theme(this), Prefs.numbers(this), Prefs.sound(this), scale);   // Prefs.theme resuelve los automaticos
        preview.setTopRow(Prefs.topRow(this));              // numeros / digrafos / nada (manda sobre el booleano antiguo)
        preview.setHintMode(Prefs.hintModeInt(this));      // nunca / siempre / aprendiendo (manda sobre el booleano antiguo)
        preview.setSaber(Prefs.saberArgb(this));
        preview.setHandMode(Prefs.handMode(this));
        preview.setSplitMode(Prefs.splitMode(this));
        preview.setSplitBox(Prefs.splitBox(this));
        preview.setToolbar(Prefs.toolbar(this));
        preview.setToolbarItems(Prefs.toolbarItems(this));
        preview.setAltChars(Prefs.altChars(this));           // simbolo de cada letra arriba a la derecha, como en el IME
        preview.setSuggestionBar(Prefs.autocorrect(this));   // como el IME: con predictivo la cinta se reserva aunque no haya barra
        preview.setGlideEnabled(Prefs.glide(this));
        preview.setPreviewEnabled(Prefs.preview(this));
        preview.setSpaceSlide(spaceSlideMode());
        preview.setLongPressMs(Prefs.longPressMs(this));
        preview.setHaptics(Prefs.vibrationLevel(this));
        preview.setBottomPad(Prefs.bottomDp(this) * dp);
        if (translation != null) translation.setDigraphs(Prefs.digraphs(this));
        syncPreview();   // el campo del panel pasa a Aurebesh (o vuelve a latino) con "Todo en Aurebesh"
    }

    /** Deslizar sobre el espacio en la copia: con un solo idioma, "auto" mueve el cursor (como en el IME). */
    private int spaceSlideMode() {
        String mode = Prefs.spaceSlide(this);
        if ("lang".equals(mode)) return AurebeshKeyboardView.SPACE_LANG;
        if ("none".equals(mode)) return AurebeshKeyboardView.SPACE_NONE;
        return AurebeshKeyboardView.SPACE_CURSOR;
    }

    /**
     * Campo del panel, cinta y mayuscula automatica de la copia del teclado. Con "Todo en Aurebesh" el campo latino
     * se oculta (sigue guardando el texto y la seleccion) y en su lugar se ve, en Aurebesh, la cola del texto que
     * cabe en una linea; sin texto, la pista "Escribe algo" atenuada.
     */
    private void syncPreview() {
        if (input == null || translation == null) return;
        String t = input.getText().toString();
        boolean aurebesh = Prefs.strip(this);
        translation.setVisibility(aurebesh ? View.VISIBLE : View.GONE);
        input.setVisibility(aurebesh ? View.INVISIBLE : View.VISIBLE);
        if (aurebesh) {
            if (t.isEmpty()) {
                translation.setText(getString(R.string.try_hint));
                translation.setAlpha(0.35f);
            } else {
                translation.setText(tailThatFits(t.replace('\n', ' ')));
                translation.setAlpha(1f);
            }
            translation.setContentDescription(t.isEmpty() ? getString(R.string.try_hint) : t);
        }
        if (panelClear != null) panelClear.setVisibility(isPanelOpen() && !t.isEmpty() ? View.VISIBLE : View.GONE);
        preview.setStripText(t);
        String before = t.substring(0, Math.min(selStart(), t.length())).trim();
        preview.setAutoShift(before.isEmpty() || before.endsWith(".") || before.endsWith("!")
                || before.endsWith("?") || before.endsWith("¿") || before.endsWith("¡"));
    }

    private final android.graphics.Paint measurePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    /** Ultimos caracteres de {@code t} que caben en una linea del campo en Aurebesh (misma metrica que AurebeshTextView). */
    private String tailThatFits(String t) {
        float letterH = 13 * dp;
        float avail = translation.getWidth() > 0 ? translation.getWidth() - translation.getPaddingLeft() - translation.getPaddingRight()
                : contentWidth - px(120);
        if (measurePaint.getTypeface() == null) measurePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        boolean digraphs = Prefs.digraphs(this);
        int from = 0;
        int len = t.length();
        if (len > 160) from = len - 160;   // una linea nunca lleva mas
        while (from < len) {
            float w = 0;
            for (Glyphs.Token tok : Glyphs.tokenize(t.substring(from), digraphs)) w += Glyphs.tokenWidth(tok, letterH, measurePaint);
            if (w <= avail) break;
            from += Character.charCount(t.codePointAt(from));
        }
        return t.substring(from);
    }

    private int selStart() {
        int a = input.getSelectionStart(), b = input.getSelectionEnd();
        if (a < 0 || b < 0) return input.length();
        return Math.min(a, b);
    }

    private int selEnd() {
        int a = input.getSelectionStart(), b = input.getSelectionEnd();
        if (a < 0 || b < 0) return input.length();
        return Math.max(a, b);
    }

    private void replaceSelection(String s) {
        input.getText().replace(selStart(), selEnd(), s);
    }

    /** Pone al dia resumenes, chips, contadores, muestras de tema, sable y deslizadores con los valores guardados. */
    private void refreshControls() {
        updateSummaries();
        for (ChipGroup g : chipGroups) g.refresh();
        refreshThemeItems();
        refreshSaber();
        refreshCounts();
        refreshSplitWidth();
        if (heightSlider != null) heightSlider.set(Prefs.heightPct(this));
        if (volumeSlider != null) volumeSlider.set(Prefs.soundVolume(this));
    }

    // ------------------------------------------------------------------ filas

    private static final String PCT = " %";   // "100 %" con espacio fino
    private static final String[] HANDS = {"none", "left", "right"};
    private static final int[] HAND_NAMES = {R.string.hand_none, R.string.hand_left, R.string.hand_right};
    private static final int[] MODE_NAMES = {R.string.aj_mode_standard, R.string.hand_left, R.string.hand_right};   // bajo el titulo "Modo a una mano" basta Izquierda / Derecha
    private static final String[] SPLITS = {"auto", "on", "off"};
    private static final int[] SPLIT_NAMES = {R.string.split_auto, R.string.split_on, R.string.split_off};
    private static final String[] SPLIT_WIDTHS = {"narrow", "normal", "wide"};
    private static final int[] SPLIT_WIDTH_NAMES = {R.string.aj_split_narrow, R.string.aj_split_normal, R.string.aj_split_wide};
    private static final String[] BOTTOMS = {"0", "12", "24", "36"};
    private static final String[] SPACE_SLIDES = {"auto", "cursor", "lang", "none"};
    private static final int[] SPACE_SLIDE_NAMES = {R.string.aj_space_auto, R.string.aj_space_cursor, R.string.aj_space_lang, R.string.aj_space_none};
    private static final String[] LONGPRESSES = {"short", "medium", "long"};
    private static final int[] LONGPRESS_NAMES = {R.string.aj_lp_short, R.string.aj_lp_medium, R.string.aj_lp_long};
    private static final String[] VIBRATIONS = {"system", "soft", "medium", "strong"};
    private static final int[] VIBRATION_NAMES = {R.string.aj_vib_system, R.string.aj_vib_soft, R.string.aj_vib_medium, R.string.aj_vib_strong};
    private static final String[] TOP_LANGS = {"es", "en", "de"};
    private static final int[] TOP_LANG_NAMES = {R.string.app_lang_es, R.string.app_lang_en, R.string.app_lang_de};
    private static final String[] HINT_MODES = {"off", "on", "learning"};
    private static final int[] HINT_MODE_NAMES = {R.string.aj_hint_off, R.string.aj_hint_on, R.string.aj_hint_learning};
    static final String[] TOP_ROWS = {"numbers", "digraphs", "none"};
    private static final int[] TOP_ROW_NAMES = {R.string.top_row_numbers, R.string.top_row_digraphs, R.string.top_row_none};
    /** Paralelo a Prefs.SABERS: nombre del color (solo el color, sin personajes). */
    private static final int[] SABER_NAMES = {R.string.saber_blue, R.string.saber_green, R.string.saber_yellow,
            R.string.saber_orange, R.string.saber_purple, R.string.saber_red};
    /** Usos antes de aprender una palabra (Prefs.LEARN_USES, entero). */
    private static final String[] LEARN_USES = {"2", "3", "5"};
    private static final int[] LEARN_USES_NAMES = {R.string.learn_uses_2, R.string.learn_uses_3, R.string.learn_uses_5};

    /**
     * Grupo de chips excluyentes ligado a una preferencia: current() lee el valor guardado y pick() lo guarda.
     * Las subclases cambian uno u otro cuando la preferencia no es una cadena simple (margen, una mano por pantalla).
     */
    private class ChipGroup {
        final String key, def;
        final String[] ids, labels;
        final java.util.List<TextView> chips = new java.util.ArrayList<>();

        ChipGroup(String key, String def, String[] ids, String[] labels) {
            this.key = key; this.def = def; this.ids = ids; this.labels = labels;
        }

        ChipGroup(String key, String def, String[] ids, int[] names) {
            this(key, def, ids, labels(names));
        }

        String current() { return Prefs.get(MainActivity.this).getString(key, def); }

        void pick(String id) { Prefs.get(MainActivity.this).edit().putString(key, id).apply(); }

        /** Marca el chip del valor guardado (tambien cuando lo cambio la copia del teclado o el IME). */
        void refresh() {
            String cur = current();
            for (int i = 0; i < chips.size(); i++) {
                TextView chip = chips.get(i);
                boolean sel = ids[i].equals(cur);
                if (chip.isSelected() != sel) {
                    chip.setSelected(sel);
                    styleChip(chip);
                }
            }
        }
    }

    private String[] labels(int[] names) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) out[i] = getString(names[i]);
        return out;
    }

    private ChipGroup prefGroup(String key, String def, String[] ids, int[] names) {
        return new ChipGroup(key, def, ids, names);
    }

    /** Modo del teclado: una mano se guarda por pantalla y, con un lado, tambien como ultimo lado del boton de la barra. */
    private ChipGroup handGroup() {
        return new ChipGroup(Prefs.perScreen(this, Prefs.HAND), "none", HANDS, MODE_NAMES) {
            @Override void pick(String id) {
                if ("none".equals(id)) super.pick(id); else Prefs.setHand(MainActivity.this, id);
            }
        };
    }

    /** Margen inferior: entero por pantalla; con un valor fuera de los chips (tiradores) no hay ninguno marcado. */
    private ChipGroup bottomGroup() {
        String[] labels = new String[BOTTOMS.length];
        for (int i = 0; i < BOTTOMS.length; i++) labels[i] = BOTTOMS[i] + " dp";
        return new ChipGroup(Prefs.perScreen(this, Prefs.BOTTOM_DP), "0", BOTTOMS, labels) {
            @Override String current() { return String.valueOf(Prefs.bottomDp(MainActivity.this)); }
            @Override void pick(String id) { Prefs.setBottomDp(MainActivity.this, Integer.parseInt(id)); }
        };
    }

    /**
     * Letras latinas: Nunca / Siempre / Aprendiendo en Prefs.HINT_MODE (migra el booleano HINTS antiguo, que se
     * mantiene en paralelo para el codigo que aun lo lee).
     */
    private ChipGroup hintGroup() {
        return new ChipGroup(Prefs.HINT_MODE, "off", HINT_MODES, HINT_MODE_NAMES) {
            @Override String current() { return Prefs.hintMode(MainActivity.this); }
            @Override void pick(String id) {
                Prefs.get(MainActivity.this).edit().putString(Prefs.HINT_MODE, id).putBoolean(Prefs.HINTS, !"off".equals(id)).apply();
            }
        };
    }

    /**
     * Fila superior: Numeros / Digrafos / Nada en Prefs.TOP_ROW (sin ella Prefs.topRow deriva del interruptor NUMBERS
     * antiguo, que se mantiene en paralelo: true solo con los numeros).
     */
    private ChipGroup topRowGroup() {
        return new ChipGroup(Prefs.TOP_ROW, "numbers", TOP_ROWS, TOP_ROW_NAMES) {
            @Override String current() { return Prefs.topRow(MainActivity.this); }
            @Override void pick(String id) {
                Prefs.get(MainActivity.this).edit().putString(Prefs.TOP_ROW, id).putBoolean(Prefs.NUMBERS, "numbers".equals(id)).apply();
            }
        };
    }

    /** Usos distintos que necesita una palabra desconocida para aprenderse: 2, 3 o 5, guardados como entero. */
    private ChipGroup learnUsesGroup() {
        return new ChipGroup(Prefs.LEARN_USES, "3", LEARN_USES, LEARN_USES_NAMES) {
            @Override String current() { return String.valueOf(Prefs.learnUses(MainActivity.this)); }
            @Override void pick(String id) { Prefs.get(MainActivity.this).edit().putInt(Prefs.LEARN_USES, Integer.parseInt(id)).apply(); }
        };
    }

    /** Idioma del teclado (y de la app): los mismos tres de la tarjeta de arriba; cambiarlo rehace la pantalla. */
    private ChipGroup langGroup() {
        return new ChipGroup(Prefs.APP_LANG, "es", TOP_LANGS, TOP_LANG_NAMES) {
            @Override String current() { return AppLocale.current(MainActivity.this); }
            @Override void pick(String id) {
                if (id.equals(current())) return;
                Prefs.setLanguage(MainActivity.this, id);   // app y teclado a la vez
                recreate();
            }
        };
    }

    private View chipRow(LinearLayout parent, int title, String sub, final ChipGroup g) {
        return chipRow(parent, title, sub, g, null);
    }

    /**
     * Fila de opciones excluyentes (chips) con titulo, subtitulo opcional y, si se da, una vista mas bajo los chips
     * (p. ej. "Reiniciar progreso"); la copia del teclado cambia al instante. La linea divisoria va dentro de la
     * fila devuelta, para que ocultarla no deje dos lineas juntas.
     */
    private View chipRow(LinearLayout parent, int title, String sub, final ChipGroup g, View extra) {
        LinearLayout outer = column();
        int n = parent.getChildCount();
        if (n > 0 && !"divider".equals(parent.getChildAt(n - 1).getTag())) outer.addView(dividerLine());
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(title), 16, ink, false), block(0));
        if (sub != null && !sub.isEmpty()) col.addView(text(sub, 14, secondary, false), block(2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String current = g.current();
        boolean many = g.ids.length >= 4;       // cuatro chips en un telefono: letra algo menor y menos relleno
        int sp = many ? 12 : 14;
        for (int i = 0; i < g.ids.length; i++) {
            final String id = g.ids[i];
            final TextView chip = text(g.labels[i], sp, ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(px(40));
            chip.setPadding(px(many ? 2 : 4), px(9), px(many ? 2 : 4), px(9));
            chip.setMaxLines(1);
            chip.setSelected(id.equals(current));
            styleChip(chip);
            a11y(chip, RadioButton.class, true, null);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    g.pick(id);
                    g.refresh();
                    applyPreviewPrefs();
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) cp.leftMargin = px(8);
            row.addView(chip, cp);
            g.chips.add(chip);
        }
        col.addView(row, block(10));
        if (extra != null) col.addView(extra, block(10));
        outer.addView(col, block(0));
        parent.addView(outer, block(0));
        chipGroups.add(g);
        StringBuilder searchable = new StringBuilder(getString(title));
        if (sub != null) searchable.append(' ').append(sub);
        for (String l : g.labels) searchable.append(' ').append(l);
        register(outer, getString(title), searchable.toString());
        return outer;
    }

    private void styleChip(TextView chip) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(chip.isSelected() ? ink : divider);
        bg.setCornerRadius(px(12));
        chip.setBackground(bg);
        chip.setTextColor(chip.isSelected() ? card : ink);
    }

    /** Oyente del deslizador: en vivo mientras se arrastra (solo la copia del teclado) y al soltar (se guarda). */
    private interface SliderListener {
        void onLive(int value);
        void onSave(int value);
    }

    /** Deslizador con su etiqueta de valor; set() lo mueve desde fuera (otro origen cambio la preferencia). */
    private static final class Slider {
        final SeekBar bar;
        final TextView value;
        final int min;
        boolean tracking;
        Slider(SeekBar bar, TextView value, int min) { this.bar = bar; this.value = value; this.min = min; }
        void set(int v) { if (!tracking) bar.setProgress(v - min); }   // onProgressChanged actualiza la etiqueta
    }

    /**
     * Fila con un SeekBar entre min y max: titulo a la izquierda, valor actual (con su unidad) a la derecha.
     * Mientras se arrastra avisa en vivo; al soltar (o al cambiarlo con el teclado o accesibilidad) guarda.
     */
    private Slider sliderRow(LinearLayout parent, int title, int sub, final int min, int max, int current,
                             final String unitSuffix, final SliderListener l) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(10));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(title), 16, ink, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView value = text(current + unitSuffix, 14, ink, true);
        value.setMinWidth(px(52));
        value.setGravity(Gravity.END);
        head.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        col.addView(head, block(0));
        if (sub != 0) col.addView(text(getString(sub), 14, secondary, false), block(2));

        final SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(current - min);
        bar.setContentDescription(getString(title));
        bar.setProgressTintList(ColorStateList.valueOf(ink));
        bar.setThumbTintList(ColorStateList.valueOf(ink));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(divider));
        final Slider s = new Slider(bar, value, min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = min + progress;
                value.setText(v + unitSuffix);
                if (!fromUser) return;
                if (s.tracking) l.onLive(v); else l.onSave(v);   // sin arrastre (teclado, accesibilidad): se guarda ya
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { s.tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                s.tracking = false;
                l.onSave(min + sb.getProgress());
            }
        });
        col.addView(bar, block(6));
        parent.addView(col, block(0));
        register(col, getString(title), getString(title) + (sub != 0 ? " " + getString(sub) : ""));
        return s;
    }

    /**
     * Fila "Tema" (dentro de la tarjeta del grupo Tema). Arriba, el grupo "Automaticos · siguen el modo oscuro del
     * telefono" con tres tarjetas de igual ancho en una fila (apiladas si la pantalla es muy estrecha): Republica
     * (Nucleo de dia, Borde Exterior de noche; valor "auto"), Imperio (Arena / Imperial; "auto_imperio") y Templos
     * (Santuario Jedi / Templo Sith; "auto_templos"), cada una con su muestra doble y el distintivo de automatico; la
     * seleccionada lleva un borde de acento. Debajo, "Siempre el mismo" con los ocho fijos en filas de cuatro. La
     * copia del teclado cambia al instante al tema efectivo (Prefs.theme).
     */
    private void themeRow(LinearLayout parent) {
        LinearLayout col = column();
        col.setPadding(px(10), px(14), px(10), px(14));
        StringBuilder searchable = new StringBuilder(getString(R.string.opt_theme));
        String current = Prefs.themePref(this);

        // Automaticos
        TextView autoGroup = text(getString(R.string.theme_auto_group), 14, secondary, true);
        autoGroup.setPadding(px(8), 0, px(8), 0);
        col.addView(autoGroup, block(0));
        searchable.append(' ').append(getString(R.string.theme_auto_group));
        boolean sideBySide = contentWidth - px(20) >= px(300);
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < Themes.AUTOS.length; i++) {
            final String id = Themes.AUTOS[i];
            String name = getString(Themes.autoName(id)), sub = getString(Themes.autoSub(id));
            searchable.append(' ').append(name).append(' ').append(sub);
            View cardView = autoCard(id, name, sub, id.equals(current), sideBySide);
            LinearLayout.LayoutParams cp = sideBySide ? new LinearLayout.LayoutParams(0, MATCH, 1f) : new LinearLayout.LayoutParams(MATCH, WRAP);
            if (i > 0) { if (sideBySide) cp.leftMargin = px(8); else cp.topMargin = px(8); }
            autoRow.addView(cardView, cp);
            themeItems.add(cardView);
            themeIds.add(id);
            autoCards.add(cardView);
        }
        col.addView(autoRow, block(10));

        // Fijos: filas de cuatro
        TextView fixedGroup = text(getString(R.string.theme_fixed_group), 14, secondary, true);
        fixedGroup.setPadding(px(8), 0, px(8), 0);
        col.addView(fixedGroup, block(18));
        searchable.append(' ').append(getString(R.string.theme_fixed_group));
        int n = Themes.ALL.length;
        int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, block(i == 0 ? 8 : 6));
            }
            Themes.Theme t = Themes.ALL[i];
            final String id = t.id;
            String label = getString(t.name);
            searchable.append(' ').append(label);
            final LinearLayout item = column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(px(2), px(6), px(2), px(6));
            item.setBackground(ripple(0x00000000));
            item.setContentDescription(label);
            a11y(item, RadioButton.class, true, null);
            item.addView(new ThemeSwatchView(this, t, ink), new LinearLayout.LayoutParams(px(56), px(56)));
            TextView name = text(label, 12, secondary, false);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            item.addView(name, block(4));
            item.setSelected(id.equals(current));
            item.setOnClickListener(themePicker(id));
            row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
            themeItems.add(item);
            themeIds.add(id);
        }
        for (int i = n; i % perRow != 0; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));   // huecos de la ultima fila
        parent.addView(col, block(0));
        register(col, getString(R.string.opt_theme), searchable.toString());
    }

    /** Guarda el tema (fijo o automatico) y lo refleja en las muestras y en la copia del teclado. */
    private View.OnClickListener themePicker(final String id) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.get(MainActivity.this).edit().putString(Prefs.THEME, id).apply();
                refreshThemeItems();
                applyPreviewPrefs();
            }
        };
    }

    /**
     * Tarjeta de un automatico: muestra doble (dia arriba a la izquierda, noche abajo a la derecha, con el distintivo),
     * nombre y "Nucleo de dia, Borde Exterior de noche". En una fila de tres va en vertical (muestra arriba, textos
     * centrados); apilada, en horizontal. Se lee como un boton de opcion con los tres textos.
     */
    private View autoCard(String id, String name, String sub, boolean selected, boolean vertical) {
        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        cardView.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        cardView.setPadding(px(8), px(10), px(8), px(10));
        cardView.addView(new ThemeSwatchView(this, Themes.autoDay(id), Themes.autoNight(id), ink), new LinearLayout.LayoutParams(px(56), px(56)));
        LinearLayout textCol = column();
        TextView title = text(name, 14, ink, true);
        title.setMaxLines(1);
        if (vertical) title.setGravity(Gravity.CENTER_HORIZONTAL);
        textCol.addView(title, block(0));
        TextView subtitle = text(sub, 12, secondary, false);
        subtitle.setMaxLines(3);
        if (vertical) subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        textCol.addView(subtitle, block(2));
        LinearLayout.LayoutParams tp = vertical ? new LinearLayout.LayoutParams(MATCH, WRAP) : new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (vertical) tp.topMargin = px(8); else tp.leftMargin = px(10);
        cardView.addView(textCol, tp);
        cardView.setContentDescription(name + ". " + sub + ". " + getString(R.string.aj_theme_auto_badge));
        a11y(cardView, RadioButton.class, true, null);
        cardView.setSelected(selected);
        styleAutoCard(cardView);
        cardView.setOnClickListener(themePicker(id));
        return cardView;
    }

    /** Fondo de la tarjeta de automatico: relleno suave y, seleccionada, borde de acento de 2 dp. */
    private void styleAutoCard(View cardView) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((divider & 0x00FFFFFF) | 0x66000000);
        bg.setCornerRadius(px(14));
        if (cardView.isSelected()) bg.setStroke(px(2), ink);
        cardView.setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x1A000000), bg, null));
    }

    private void refreshThemeItems() {
        String cur = Prefs.themePref(this);
        for (int i = 0; i < themeItems.size(); i++) {
            View item = themeItems.get(i);
            boolean sel = themeIds.get(i).equals(cur);
            if (item.isSelected() == sel) continue;
            item.setSelected(sel);
            if (autoCards.contains(item)) styleAutoCard(item);
            // La muestra (hija) pinta el anillo segun el estado seleccionado de su tarjeta
            if (item instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) item;
                for (int j = 0; j < g.getChildCount(); j++) if (g.getChildAt(j) instanceof ThemeSwatchView) g.getChildAt(j).setSelected(sel);
            }
        }
    }

    private void optionRow(LinearLayout parent, int label, int sub, final String key, boolean value) {
        optionRow(parent, label, sub, 0, key, value);
    }

    /**
     * Fila con interruptor: titulo, subtitulo y, si se da, una nota mas pequena debajo (p. ej. "Como en Samsung
     * Keyboard"). El interruptor es el unico elemento accesible y su descripcion lleva los tres textos; el buscador
     * tambien encuentra la fila por la nota.
     */
    private void optionRow(LinearLayout parent, int label, int sub, int hint, final String key, boolean value) {
        separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(56));
        row.setPadding(px(18), px(12), px(14), px(12));
        row.setBackground(ripple(0x00000000));

        // El interruptor es el elemento accesible; el texto solo lo describe.
        LinearLayout col = column();
        col.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        col.addView(text(getString(label), 16, ink, false), block(0));
        if (sub != 0) col.addView(text(getString(sub), 14, secondary, false), block(2));
        if (hint != 0) col.addView(text(getString(hint), 13, secondary, false), block(4));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final Switch sw = new Switch(this);
        StringBuilder spoken = new StringBuilder(getString(label));
        if (sub != 0) spoken.append(". ").append(getString(sub));
        if (hint != 0) spoken.append(". ").append(getString(hint));
        sw.setContentDescription(spoken.toString());
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ink, thumbOff}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{(ink & 0x00FFFFFF) | 0x66000000, trackOff}));
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).edit().putBoolean(key, checked).apply();
                applyPreviewPrefs();
                if (Prefs.DIGRAPHS.equals(key) && translation != null) translation.setDigraphs(checked);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(WRAP, WRAP);
        sp.leftMargin = px(12);
        row.addView(sw, sp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
        parent.addView(row, block(0));
        register(row, getString(label), getString(label) + (sub != 0 ? " " + getString(sub) : "") + (hint != 0 ? " " + getString(hint) : ""));
    }

    /**
     * Fila que abre un dialogo: titulo (con contador, se rellena en refreshCounts), subtitulo y ›. Devuelve el
     * titulo; {@code titleRes} es el nombre sin contador, para el buscador.
     */
    private TextView linkRow(LinearLayout parent, int titleRes, int sub, View.OnClickListener onClick) {
        separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(56));
        row.setPadding(px(18), px(12), px(14), px(12));
        row.setBackground(ripple(0x00000000));
        LinearLayout col = column();
        TextView title = text("", 16, ink, false);
        col.addView(title, block(0));
        col.addView(text(getString(sub), 14, secondary, false), block(2));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(chevronView(), new LinearLayout.LayoutParams(px(24), px(24)));
        row.setOnClickListener(onClick);
        a11y(row, Button.class, false, null);
        parent.addView(row, block(0));
        register(row, getString(titleRes), getString(titleRes) + " " + getString(sub));
        return title;
    }

    private void refreshCounts() {
        if (learnedTitle != null) learnedTitle.setText(getString(R.string.aj_learned_row, learnedList().size()));
        if (shortcutsTitle != null) shortcutsTitle.setText(getString(R.string.aj_shortcuts_row, Prefs.shortcuts(this).size()));
    }

    private void gestureRow(LinearLayout parent, int gesture, int result) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(12), px(18), px(12));
        col.setMinimumHeight(px(56));
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(text(getString(gesture), 16, ink, false), block(0));
        col.addView(text(getString(result), 14, secondary, false), block(2));
        parent.addView(col, block(0));
        register(col, getString(gesture), getString(gesture) + " " + getString(result));
    }

    /** Nota explicativa de una linea o dos (letra pequena), registrada para el buscador. */
    private void noteRow(LinearLayout parent, int textRes) {
        separator(parent);
        TextView note = text(getString(textRes), 14, secondary, false);
        note.setPadding(px(18), px(12), px(18), px(14));
        parent.addView(note, block(0));
        register(note, getString(textRes), getString(textRes));
    }

    /** Boton de texto con el estilo de los chips. */
    private TextView chipButton(int label, View.OnClickListener onClick) {
        TextView chip = text(getString(label), 14, ink, true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(px(40));
        chip.setPadding(px(14), px(9), px(14), px(9));
        chip.setSelected(false);
        styleChip(chip);
        chip.setOnClickListener(onClick);
        a11y(chip, Button.class, false, null);
        return chip;
    }

    /** Fila "Sonido personalizado": elegir un audio corto del telefono o volver al clic del sistema. */
    private void customSoundRow(LinearLayout parent) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(R.string.opt_custom_sound), 16, ink, false), block(0));
        col.addView(text(getString(R.string.opt_custom_sound_sub), 14, secondary, false), block(2));
        soundCurrent = text("", 14, secondary, true);
        col.addView(soundCurrent, block(2));
        refreshSoundRow();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView choose = chipButton(R.string.sound_choose, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                try {
                    startActivityForResult(i, PICK_SOUND);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, R.string.sound_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
        TextView reset = chipButton(R.string.sound_default, new View.OnClickListener() {
            @Override public void onClick(View v) { removeCustomSound(); }
        });
        row.addView(choose, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(WRAP, WRAP);
        rp.leftMargin = px(8);
        row.addView(reset, rp);
        col.addView(row, block(10));
        parent.addView(col, block(0));
        register(col, getString(R.string.opt_custom_sound), getString(R.string.opt_custom_sound) + " " + getString(R.string.opt_custom_sound_sub)
                + " " + getString(R.string.sound_choose) + " " + getString(R.string.sound_default));
    }

    private void refreshSoundRow() {
        if (soundCurrent == null) return;
        String name = Prefs.customSound(this);
        if (name == null) {
            soundCurrent.setVisibility(View.GONE);
        } else {
            soundCurrent.setText(getString(R.string.sound_current, name));
            soundCurrent.setVisibility(View.VISIBLE);
        }
    }

    /** Despacha por codigo: sonido (41), diccionario (45 exportar, 46 importar), ajustes (47 exportar, 48 importar); 42 es de la IA. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AiSettingsCard.onActivityResult(this, requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (requestCode) {
            case PICK_SOUND: importSound(uri); break;
            case EXPORT_WORDS: exportWords(uri); break;
            case IMPORT_WORDS: importWords(uri); break;
            case EXPORT_SETTINGS: exportSettings(uri); break;
            case IMPORT_SETTINGS: importSettings(uri); break;
            default: break;
        }
    }

    private void importSound(Uri uri) {
        java.io.File target = new java.io.File(getFilesDir(), KeySound.FILE);
        boolean ok = false;
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in != null) {
                out = new java.io.FileOutputStream(target);
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_SOUND_BYTES) throw new java.io.IOException("demasiado grande");
                    out.write(buf, 0, n);
                }
                ok = total > 0;
            }
        } catch (java.io.IOException | SecurityException e) {
            ok = false;
        } finally {
            try { if (in != null) in.close(); } catch (java.io.IOException ignored) {}
            try { if (out != null) out.close(); } catch (java.io.IOException ignored) {}
        }
        if (!ok) {
            target.delete();
            Toast.makeText(this, R.string.sound_error, Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.get(this).edit().putString(Prefs.CUSTOM_SOUND, displayName(uri)).apply();
        KeySound.get(this).reload(this);
        refreshSoundRow();
        refreshDataCard();
    }

    /** Escribe texto UTF-8 en el documento elegido; false si no se pudo. */
    private boolean writeText(Uri uri, String text) {
        java.io.OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) return false;
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (java.io.IOException | SecurityException | IllegalArgumentException e) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /** Lee el documento elegido como texto UTF-8 (hasta MAX_IMPORT_BYTES), o null si no se pudo. */
    private String readText(Uri uri) {
        java.io.InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_IMPORT_BYTES) return null;
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | SecurityException e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /** Diccionario personal a un archivo de texto: una palabra por linea, la mas reciente primero. */
    private void exportWords(Uri uri) {
        java.util.List<String> words = learnedList();
        StringBuilder b = new StringBuilder();
        for (String w : words) b.append(w).append('\n');
        if (writeText(uri, b.toString())) Toast.makeText(this, getString(R.string.aj_words_exported, words.size()), Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, R.string.aj_file_error, Toast.LENGTH_SHORT).show();
    }

    /** Diccionario personal desde un archivo de texto: una palabra por linea (se ignoran las vacias y las largas). */
    private void importWords(Uri uri) {
        String raw = readText(uri);
        if (raw == null) {
            Toast.makeText(this, R.string.aj_file_error, Toast.LENGTH_SHORT).show();
            return;
        }
        int n = importWordList(raw);
        Toast.makeText(this, getString(R.string.aj_words_imported, n), Toast.LENGTH_SHORT).show();
        refreshCounts();
        refreshDataCard();
    }

    /** Anade las palabras de un texto (una por linea) al diccionario personal y devuelve cuantas entraron. */
    int importWordList(String raw) {
        String[] lines = raw.split("\n");
        int n = 0;
        for (int i = lines.length - 1; i >= 0; i--) {   // de atras adelante: la primera del archivo queda la mas reciente
            String w = lines[i].trim();
            if (w.isEmpty() || w.length() > 30 || w.indexOf(' ') >= 0) continue;
            Prefs.addLearnedWord(this, w);
            n++;
        }
        return n;
    }

    private void exportSettings(Uri uri) {
        if (writeText(uri, exportJson(exportIncludeKey))) Toast.makeText(this, R.string.aj_settings_exported, Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, R.string.aj_file_error, Toast.LENGTH_SHORT).show();
        exportIncludeKey = false;
    }

    private void importSettings(Uri uri) {
        String raw = readText(uri);
        if (raw == null) {
            Toast.makeText(this, R.string.aj_file_error, Toast.LENGTH_SHORT).show();
            return;
        }
        int n = importJson(raw);
        if (n < 0) {
            Toast.makeText(this, R.string.aj_import_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.aj_settings_imported, n), Toast.LENGTH_SHORT).show();
        recreate();   // idioma, tema y demas se vuelven a leer
    }

    /** Nombre visible del archivo elegido (o el ultimo tramo de la URI). */
    private String displayName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (RuntimeException ignored) {
            // sin nombre: se usa el tramo final de la URI
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "audio" : last;
    }

    // ------------------------------------------------------------------ dialogos: diccionario personal y atajos

    /** Palabras aprendidas tal como se guardan, la mas reciente primero (Prefs.learnedWords devuelve un conjunto sin orden). */
    private java.util.List<String> learnedList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : Prefs.get(this).getString(Prefs.LEARNED, "").split("\n")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /**
     * Dialogo "Diccionario personal": las reglas de lo que se aprende, lista con × por fila, campo para anadir,
     * Exportar / Importar (un archivo de texto, una palabra por linea) y "Olvidar todo" (con confirmacion).
     */
    private void showLearnedDialog() {
        final java.util.List<String> words = learnedList();
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return words.size(); }
            @Override public Object getItem(int i) { return words.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                final String w = words.get(i);
                return removableRow(w, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.removeLearnedWord(MainActivity.this, w);
                        words.remove(w);
                        notifyDataSetChanged();
                    }
                });
            }
        };
        final EditText field = dialogField(R.string.aj_learned_add_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView add = chipButton(R.string.aj_add, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String w = field.getText().toString().trim();
                if (w.isEmpty()) return;
                Prefs.addLearnedWord(MainActivity.this, w);
                field.setText("");
                words.clear();
                words.addAll(learnedList());
                adapter.notifyDataSetChanged();
            }
        });
        LinearLayout form = column();
        TextView rules = text(getString(R.string.aj_personal_rules), 13, secondary, false);
        form.addView(rules, block(0));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(field, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(WRAP, WRAP);
        ap.leftMargin = px(8);
        line.addView(add, ap);
        form.addView(line, block(8));
        LinearLayout files = new LinearLayout(this);
        files.setOrientation(LinearLayout.HORIZONTAL);
        files.addView(chipButton(R.string.aj_export, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, "aurebesh-diccionario.txt");
                startPicker(i, EXPORT_WORDS);
            }
        }), new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(WRAP, WRAP);
        ip.leftMargin = px(8);
        files.addView(chipButton(R.string.aj_import, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                startPicker(i, IMPORT_WORDS);
            }
        }), ip);
        form.addView(files, block(8));

        new AlertDialog.Builder(this)
                .setTitle(R.string.aj_learned_title)
                .setView(listDialogContent(form, adapter, R.string.aj_learned_empty))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.aj_forget_all, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { confirmForgetAll(); }
                })
                .show();
    }

    /** "Olvidar todo": palabras aprendidas y pares (bigramas) de este telefono, tras confirmar. */
    private void confirmForgetAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.learn_clear)
                .setMessage(R.string.aj_forget_confirm)
                .setPositiveButton(R.string.aj_forget_all, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.clearLearnedWords(MainActivity.this);
                        Bigrams.forget(MainActivity.this);
                        refreshDataCard();
                        Toast.makeText(MainActivity.this, R.string.learn_cleared, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Atajos guardados como pares {clave, expansion}, en el orden de Prefs.shortcuts. */
    private java.util.List<String[]> shortcutList() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : Prefs.shortcuts(this).entrySet()) {
            out.add(new String[]{e.getKey(), e.getValue()});
        }
        return out;
    }

    /** La clave de un atajo: solo letras (de cualquier alfabeto), de 1 a 20. */
    static boolean validShortcutKey(String k) {
        if (k == null || k.isEmpty() || k.length() > 20) return false;
        for (int i = 0; i < k.length(); i++) {
            if (!Character.isLetter(k.charAt(i))) return false;
        }
        return true;
    }

    /** Dialogo "Atajos de texto": lista clave → expansion con × por fila y un formulario de dos campos para anadir. */
    private void showShortcutsDialog() {
        final java.util.List<String[]> entries = shortcutList();
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int i) { return entries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                final String[] e = entries.get(i);
                return removableRow(e[0] + " → " + e[1], new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.removeShortcut(MainActivity.this, e[0]);
                        entries.remove(e);
                        notifyDataSetChanged();
                    }
                });
            }
        };
        final EditText key = dialogField(R.string.aj_shortcut_key_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText expansion = dialogField(R.string.aj_shortcut_text_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        TextView add = chipButton(R.string.aj_add, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String k = key.getText().toString().trim();
                String x = expansion.getText().toString().trim();
                if (!validShortcutKey(k)) {
                    Toast.makeText(MainActivity.this, R.string.aj_shortcut_key_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (x.isEmpty()) {
                    Toast.makeText(MainActivity.this, R.string.aj_shortcut_text_missing, Toast.LENGTH_SHORT).show();
                    return;
                }
                Prefs.addShortcut(MainActivity.this, k, x);
                key.setText("");
                expansion.setText("");
                entries.clear();
                entries.addAll(shortcutList());
                adapter.notifyDataSetChanged();
            }
        });
        LinearLayout form = column();
        form.addView(key, block(0));
        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        second.setGravity(Gravity.CENTER_VERTICAL);
        second.addView(expansion, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(WRAP, WRAP);
        ap.leftMargin = px(8);
        second.addView(add, ap);
        form.addView(second, block(0));

        new AlertDialog.Builder(this)
                .setTitle(R.string.aj_shortcuts_title)
                .setView(listDialogContent(form, adapter, R.string.aj_shortcuts_empty))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Cuerpo de un dialogo con lista: formulario arriba y lista (con texto de vacio) de alto fijo para que se desplace. */
    private View listDialogContent(View form, BaseAdapter adapter, int emptyRes) {
        LinearLayout box = column();
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(form, block(0));
        TextView empty = text(getString(emptyRes), 14, secondary, false);
        empty.setPadding(0, px(16), 0, px(16));
        box.addView(empty, block(0));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        box.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        int h = Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(box, new FrameLayout.LayoutParams(MATCH, h));
        return holder;
    }

    /** Fila de lista con un texto y un × a la derecha. */
    private View removableRow(String label, View.OnClickListener onRemove) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(44));
        row.addView(text(label, 15, ink, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView x = text("×", 20, secondary, false);
        x.setPadding(px(14), px(4), px(6), px(4));
        x.setContentDescription(getString(R.string.aj_remove, label));
        x.setBackground(ripple(0x00000000));
        x.setOnClickListener(onRemove);
        a11y(x, Button.class, false, null);
        row.addView(x, new LinearLayout.LayoutParams(WRAP, WRAP));
        return row;
    }

    private EditText dialogField(int hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setInputType(inputType);
        return e;
    }

    // ------------------------------------------------------------------ piezas

    private int px(float dps) { return Math.round(dps * dp); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout cardColumn() {
        LinearLayout l = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card);
        bg.setCornerRadius(px(16));
        l.setBackground(bg);
        return l;
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.topMargin = px(topDp);
        return p;
    }

    /** ImageView decorativo con un icono de UiIcons de {@code sizeDp} de lado en ese color (centrado en su caja). */
    private ImageView iconView(int kind, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(UiIcons.drawable(kind, color, px(sizeDp)));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** Chevron › de las filas que abren algo (decorativo). */
    private ImageView chevronView() {
        ImageView v = iconView(UiIcons.CHEVRON_RIGHT, secondary, 22);
        return v;
    }

    /**
     * Grupo de una pagina: una tarjeta redondeada mas (12 dp bajo la anterior) y, con {@code headerRes}, un rotulo
     * pequeno encima, al estilo de las secciones de los Ajustes de One UI. Devuelve la tarjeta donde van las filas.
     */
    private LinearLayout group(LinearLayout body, int headerRes) {
        boolean first = body.getChildCount() == 0;
        if (headerRes != 0) {
            TextView header = text(getString(headerRes), 13, secondary, true);
            header.setPadding(px(16), px(first ? 6 : 14), px(16), px(8));
            body.addView(header, block(first ? 0 : 4));
        }
        LinearLayout c = cardColumn();
        body.addView(c, block(headerRes != 0 || first ? 0 : 12));
        return c;
    }

    // ------------------------------------------------------------------ buscador

    /** Campo "Buscar ajuste" con × para borrar; debajo, la tarjeta de resultados y la linea "Ningun ajuste coincide". */
    private void buildSearch(LinearLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card);
        bg.setCornerRadius(px(14));
        box.setBackground(bg);
        box.setPadding(px(14), 0, px(6), 0);
        box.setMinimumHeight(px(48));
        box.addView(iconView(UiIcons.SEARCH, secondary, 20), new LinearLayout.LayoutParams(px(20), px(20)));
        searchField = new EditText(this);
        searchField.setBackground(null);
        searchField.setHint(R.string.aj_search_hint);
        searchField.setTextColor(ink);
        searchField.setHintTextColor(secondary);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchField.setPadding(px(10), px(13), px(10), px(13));
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.addView(searchField, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageView clear = iconView(UiIcons.CLEAR, secondary, 20);
        clear.setContentDescription(getString(R.string.aj_search_clear));
        clear.setBackground(ripple(0x00000000));
        clear.setVisibility(View.GONE);
        a11y(clear, Button.class, false, null);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { searchField.setText(""); }
        });
        searchClear = clear;
        box.addView(clear, new LinearLayout.LayoutParams(px(40), px(40)));
        root.addView(box, block(12));
        searchResults = cardColumn();
        searchResults.setVisibility(View.GONE);
        root.addView(searchResults, block(12));
        // Estado vacio: tarjeta con la lupa, "Ningun ajuste coincide" y una pista
        LinearLayout empty = cardColumn();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(px(20), px(28), px(20), px(28));
        ImageView lens = iconView(UiIcons.SEARCH, secondary, 32);
        empty.addView(lens, new LinearLayout.LayoutParams(px(40), px(40)));
        searchNone = text(getString(R.string.aj_search_none), 16, ink, true);
        searchNone.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.addView(searchNone, block(10));
        TextView emptyHint = text(getString(R.string.ux_search_none_hint), 14, secondary, false);
        emptyHint.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.addView(emptyHint, block(4));
        empty.setVisibility(View.GONE);
        searchEmpty = empty;
        root.addView(empty, block(12));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(Editable s) { search(s.toString()); }
        });
    }

    /** Minusculas sin acentos ni otras marcas (NFD) y con los espacios reducidos, para comparar textos. */
    static String normalize(String s) {
        if (s == null) return "";
        String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(d.length());
        boolean space = false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
            if (Character.isWhitespace(c)) { space = true; continue; }
            if (space && b.length() > 0) b.append(' ');
            space = false;
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /**
     * Apunta una fila para el buscador: su vista, la pagina en construccion donde vive, el titulo que se mostrara
     * en los resultados y los textos (normalizados) por los que se encuentra.
     */
    private void register(View view, String label, String text) {
        if (building == null) return;
        rows.add(new Row(view, building, label, normalize(text)));
    }

    /** Todas las paginas construidas (el buscador necesita sus filas; cada una se construye una sola vez). */
    private void ensureAllBuilt() {
        for (Page p : pages.values()) buildPage(p);
    }

    /**
     * Busca: con texto, la pantalla principal deja solo el buscador y una tarjeta con las filas cuyo titulo,
     * subtitulo o etiquetas lo contienen (sin acentos ni mayusculas), cada una con el nombre de su pagina; al tocar
     * una se abre la pagina, se baja hasta la fila y se resalta un momento. Vacio, vuelve la pantalla normal.
     */
    void search(String raw) {
        query = normalize(raw);
        boolean on = !query.isEmpty();
        if (searchClear != null) searchClear.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) ensureAllBuilt();
        int shown = on ? View.GONE : View.VISIBLE;
        for (View v : new View[]{topCard, tryCard, listCard, footCard, compatCard, bigHeader}) if (v != null) v.setVisibility(shown);
        searchResults.removeAllViews();
        int n = 0;
        if (on) {
            for (Row r : rows) {
                if (!r.text.contains(query)) continue;
                if (r.view.getVisibility() == View.GONE) continue;   // p. ej. "Ancho de las mitades" donde no se divide
                resultRow(r);
                n++;
            }
        }
        searchResults.setVisibility(on && n > 0 ? View.VISIBLE : View.GONE);
        if (searchEmpty != null) searchEmpty.setVisibility(on && n == 0 ? View.VISIBLE : View.GONE);
        updateHomeBar();
    }

    /** Fila de resultado: titulo de la fila encontrada y, debajo, la pagina donde esta; tocarla la abre y la resalta. */
    private void resultRow(final Row r) {
        separator(searchResults);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(56));
        row.setPadding(px(18), px(12), px(14), px(12));
        row.setBackground(ripple(0x00000000));
        LinearLayout col = column();
        col.addView(text(r.label, 16, ink, false), block(0));
        col.addView(text(getString(r.page.titleRes), 14, secondary, false), block(2));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(chevronView(), new LinearLayout.LayoutParams(px(24), px(24)));
        row.setContentDescription(r.label + ". " + getString(R.string.aj_open_page, getString(r.page.titleRes)));
        a11y(row, Button.class, false, null);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRow(r); }
        });
        searchResults.addView(row, block(0));
    }

    /** Abre la pagina de la fila, baja hasta ella y la resalta un momento. */
    void showRow(final Row r) {
        openPage(r.page, true);
        pageScroll.post(new Runnable() {
            @Override public void run() {
                int y = 0;
                for (View p = r.view; p != null && p != pageScroll; p = p.getParent() instanceof View ? (View) p.getParent() : null) y += p.getTop();
                pageScroll.smoothScrollTo(0, Math.max(0, y - px(12)));
                flash(r.view);
            }
        });
    }

    /** Resalta una fila: fondo del color de la tinta muy transparente que se desvanece en algo menos de un segundo. */
    private void flash(final View v) {
        final Drawable old = v.getBackground();
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), (ink & 0x00FFFFFF) | 0x33000000, (ink & 0x00FFFFFF));
        a.setStartDelay(150);
        a.setDuration(900);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator anim) { v.setBackgroundColor((Integer) anim.getAnimatedValue()); }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) { v.setBackground(old); }
        });
        a.start();
    }

    // ------------------------------------------------------------------ accesibilidad

    /**
     * Da a una vista un rol para los lectores de pantalla (chips y temas como RadioButton con su estado, botones
     * de texto como Button). Con {@code checkable}, el estado marcado sale de {@code source} si se da y, si no,
     * de isSelected().
     */
    static void a11y(View v, final Class<?> role, final boolean checkable, final CompoundButton source) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(role.getName());
                if (checkable) {
                    info.setCheckable(true);
                    info.setChecked(source != null ? source.isChecked() : host.isSelected());
                }
            }
        });
    }

    /**
     * Radios de la tarjeta de inteligencia: el boton redondo deja de ser un elemento accesible y la fila que lo
     * contiene (la pulsable) se lee como RadioButton con su estado.
     */
    private static void accessibleRadios(View root) {
        if (root instanceof RadioButton) {
            RadioButton radio = (RadioButton) root;
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (radio.getParent() instanceof View) {
                View row = (View) radio.getParent();
                if (row.hasOnClickListeners()) a11y(row, RadioButton.class, true, radio);
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) accessibleRadios(g.getChildAt(i));
        }
    }

    // ------------------------------------------------------------------ resumenes

    /** Resumen de una linea bajo el titulo de cada categoria, con los valores actuales (y la descripcion accesible). */
    private void updateSummaries() {
        Page p;
        if ((p = pages.get(PAGE_KEYS)) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(getString(nameOf(TOP_LANGS, TOP_LANG_NAMES, AppLocale.current(this))));
            String top = Prefs.topRow(this);
            parts.add("none".equals(top) ? getString(R.string.sum_top_row_none)
                    : getString(R.string.sum_top_row, lower(nameOf(TOP_ROWS, TOP_ROW_NAMES, top))));
            if (Prefs.digraphs(this)) parts.add(getString(R.string.sum_digraphs));
            if (Prefs.altChars(this)) parts.add(getString(R.string.sum_alt_chars));
            String hints = Prefs.hintMode(this);
            if ("on".equals(hints)) parts.add(getString(R.string.sum_hints));
            else if ("learning".equals(hints)) parts.add(getString(R.string.aj_sum_hints_learning));
            if (Prefs.strip(this)) parts.add(getString(R.string.immersive_short));
            p.summary.setText(join(parts));
        }
        if ((p = pages.get(PAGE_STYLE)) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(themeLabel());
            parts.add(getString(R.string.aj_sum_saber, lower(SABER_NAMES[saberIndex()])));
            if (Prefs.toolbar(this)) parts.add(getString(R.string.sum_toolbar));
            p.summary.setText(join(parts));
        }
        if ((p = pages.get(PAGE_SIZE)) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(Prefs.heightPct(this) + PCT);
            parts.add(getString(R.string.aj_sum_margin, Prefs.bottomDp(this)));
            String hand = Prefs.hand(this);
            if (!"none".equals(hand)) parts.add(getString(R.string.sum_hand, getString(nameOf(HANDS, HAND_NAMES, hand))));
            String split = Prefs.splitPref(this);
            if (wide || !"auto".equals(split)) parts.add(getString(R.string.aj_sum_split, getString(nameOf(SPLITS, SPLIT_NAMES, split))));
            p.summary.setText(join(parts));
        }
        if ((p = pages.get(PAGE_SMART)) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (Prefs.autocorrect(this)) parts.add(getString(R.string.sum_autocorrect));
            if (Prefs.autoReplace(this)) parts.add(getString(R.string.aj_sum_replace));
            if (Prefs.learn(this)) parts.add(getString(R.string.sum_learn));
            if (Prefs.starWars(this)) parts.add(getString(R.string.aj_sum_starwars));
            int n = Prefs.shortcuts(this).size();
            parts.add(getResources().getQuantityString(R.plurals.aj_sum_shortcuts, n, n));
            p.summary.setText(join(parts));
        }
        if ((p = pages.get(PAGE_TOUCH)) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (Prefs.glide(this)) parts.add(getString(R.string.sum_glide));
            if (Prefs.preview(this)) parts.add(getString(R.string.aj_sum_bubble));
            parts.add(getString(R.string.sum_longpress, lower(nameOf(LONGPRESSES, LONGPRESS_NAMES, Prefs.longPress(this)))));
            if (Prefs.sound(this)) parts.add(getString(R.string.sum_sound));
            if (Prefs.vibrate(this)) {
                String level = Prefs.vibration(this);
                parts.add("system".equals(level) ? getString(R.string.sum_vibrate)
                        : getString(R.string.aj_sum_vibration, lower(nameOf(VIBRATIONS, VIBRATION_NAMES, level))));
            }
            p.summary.setText(join(parts));
        }
        if ((p = pages.get(PAGE_CLIPBOARD)) != null) {
            p.summary.setText(Prefs.screenshots(this) ? R.string.aj_sum_shots_on : R.string.aj_sum_shots_off);
        }
        if ((p = pages.get(PAGE_AI)) != null) {
            String name = AiModels.engineShortName(this);
            if (AiModels.remote(this)) p.summary.setText(getString(R.string.sum_ai_remote, name == null ? "—" : name));
            else if (name != null) p.summary.setText(getString(R.string.sum_ai_ready, name));
            else p.summary.setText(R.string.sum_ai_none);
        }
        if ((p = pages.get(PAGE_PRIVACY)) != null) p.summary.setText(R.string.aj_sum_more);
        if ((p = pages.get(PAGE_HELP)) != null) p.summary.setText(getString(R.string.sum_gestures, GESTURES.length / 2));
        for (Page page : pages.values()) describeRow(page);
    }

    /** Indice en Prefs.SABERS del color guardado (0 si no se reconoce). */
    private int saberIndex() {
        String id = Prefs.saber(this);
        for (int i = 0; i < Prefs.SABERS.length; i++) if (Prefs.SABERS[i].equals(id)) return i;
        return 0;
    }

    /** Nombre del tema para el resumen; con un automatico, tambien el que esta en uso: "Republica (Noche)". */
    private String themeLabel() {
        String name = getString(Themes.byId(Prefs.theme(this)).name);
        String pref = Prefs.themePref(this);
        return Themes.isAuto(pref) ? getString(Themes.autoName(pref)) + " (" + name + ")" : name;
    }

    private String lower(int res) { return getString(res).toLowerCase(java.util.Locale.getDefault()); }

    private static int nameOf(String[] ids, int[] names, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return names[i];
        return names[0];
    }

    private static String join(java.util.List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(" · ");
            b.append(p);
        }
        return b.toString();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(medium);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    /** Linea entre filas, salvo si la fila anterior ya es una linea (o la tarjeta esta vacia). */
    private void separator(LinearLayout parent) {
        int n = parent.getChildCount();
        if (n > 0 && !"divider".equals(parent.getChildAt(n - 1).getTag())) parent.addView(dividerLine());
    }

    private View dividerLine() {
        View v = new View(this);
        v.setTag("divider");
        v.setBackgroundColor(divider);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, Math.max(1, px(1)));
        p.leftMargin = px(18);
        p.rightMargin = px(18);
        v.setLayoutParams(p);
        return v;
    }

    private Button button() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(medium);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(px(48));
        b.setPadding(px(16), 0, px(16), 0);
        return b;
    }

    private void styleButton(Button b, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ink : divider);
        bg.setCornerRadius(px(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x22000000), bg, null));
        b.setTextColor(primary ? card : ink);
    }

    private RippleDrawable ripple(int base) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(base);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x1A000000), content, mask);
    }
}
