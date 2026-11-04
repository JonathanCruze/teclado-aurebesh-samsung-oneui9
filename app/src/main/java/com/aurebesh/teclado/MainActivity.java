package com.aurebesh.teclado;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
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
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Pantalla de la app: configurar el teclado en dos pasos, probarlo con una copia funcional del teclado y
 * ajustar las opciones en secciones colapsables como las de One UI (Diseno, Tamano y posicion, Escritura
 * inteligente, Tocar y respuesta, Portapapeles, Inteligencia, Ayuda y gestos; Plegables solo en pantallas
 * anchas). Sigue el modo claro/oscuro del sistema; en pantallas anchas el contenido va en una columna centrada.
 */
public final class MainActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private float dp;
    private int surface, card, ink, secondary, divider, thumbOff, trackOff;
    private Typeface medium;
    private boolean wide;                                // pantalla interior de un plegable o tableta (>= 600 dp)
    private int contentWidth;                            // ancho util de la columna en px (reparto de las muestras de tema)

    private TextView topStatus;                          // estado del teclado bajo el boton de arriba
    private Button keyboardButton;                       // "Elegir teclado" / "Activar el teclado"
    private ScrollView scroll;

    /** Seccion colapsable: tarjeta con cabecera (titulo, resumen, flecha) y cuerpo que se muestra u oculta. */
    private static final class Section {
        final String key;
        final LinearLayout outer, body;
        final TextView summary, chevron;
        Section(String key, LinearLayout outer, LinearLayout body, TextView summary, TextView chevron) {
            this.key = key; this.outer = outer; this.body = body; this.summary = summary; this.chevron = chevron;
        }
        boolean open() { return body.getVisibility() == View.VISIBLE; }
    }
    private final java.util.LinkedHashMap<String, Section> sections = new java.util.LinkedHashMap<>();
    /** Secciones de la fila "Ir a" (sin Pruebalo); "fold" solo existe en pantallas anchas. */
    private static final String[] NAV_KEYS = {"design", "fold", "size", "smart", "touch", "clipboard", "ai", "help"};
    private static final int[] NAV_TITLES = {R.string.aj_sec_design, R.string.fold_section, R.string.aj_sec_size,
            R.string.aj_sec_smart, R.string.aj_sec_touch, R.string.aj_sec_clipboard, R.string.ai_section, R.string.aj_sec_help};
    /** Cualquier cambio de preferencia (desde aqui, desde la copia del teclado o desde el IME) pone al dia la pantalla. */
    private final SharedPreferences.OnSharedPreferenceChangeListener summaryListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (key == null) return;
                    refreshControls();
                }
            };
    private EditText input;
    private AurebeshTextView translation;
    private AurebeshKeyboardView preview;
    private View aiCard;
    private final java.util.List<View> themeItems = new java.util.ArrayList<>();
    private final java.util.List<String> themeIds = new java.util.ArrayList<>();   // paralelo a themeItems
    private final java.util.List<ChipGroup> chipGroups = new java.util.ArrayList<>();
    private View splitWidthRow;                          // "Ancho de las mitades": solo con pantalla ancha o dividido en Siempre
    private Slider heightSlider, volumeSlider;
    private int liveHeightPct;                           // alto que se esta arrastrando en el deslizador (0 = el guardado)
    private TextView learnedTitle, shortcutsTitle;       // "Palabras aprendidas · N" y "Atajos de texto · N"
    private int gestureCount;                            // filas de Ayuda y gestos, para el resumen
    private TextView soundCurrent;                       // "Actual: nombre" del sonido personalizado

    private static final int PICK_SOUND = 41;
    private static final long MAX_SOUND_BYTES = 2L * 1024 * 1024;

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
        migrateSectionState();

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(surface);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = column();
        root.setPadding(px(20), px(28), px(20), px(32));
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        if (screenWidthDp >= 700) {
            // Pantalla ancha: columna centrada de min(640 dp, 80 %) en vez de filas de borde a borde
            int w = Math.min(px(640), Math.round(screenWidthDp * 0.8f * dp));
            FrameLayout frame = new FrameLayout(this);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(w, WRAP);
            fp.gravity = Gravity.CENTER_HORIZONTAL;
            frame.addView(root, fp);
            scroll.addView(frame, new ViewGroup.LayoutParams(MATCH, WRAP));
            contentWidth = w - px(40);
        } else {
            scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));
            contentWidth = px(screenWidthDp) - px(40);
        }

        buildHeader(root);
        buildTop(root);
        buildNav(root);
        buildCompat(root);

        // Cada seccion es una tarjeta colapsable con resumen; solo "Pruebalo" empieza abierta
        LinearLayout tryBody = section(root, "try", R.string.try_title);
        buildTry(tryBody);
        buildRealFields(tryBody);
        buildDesign(root);
        if (wide) buildFold(root);
        buildSize(root);
        buildSmart(root);
        buildTouch(root);
        buildClipboard(root);

        // Inteligencia: modelo local o servidor propio
        LinearLayout aiBody = section(root, "ai", R.string.ai_section);
        aiCard = AiSettingsCard.build(this, ink, secondary, card, divider);
        aiBody.addView(aiCard, block(0));

        buildHelp(root);
        refreshControls();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(summaryListener);

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
        root.addView(foot, block(28));

        setContentView(scroll);
        String want = getIntent().getStringExtra("section");   // desde el engrane del teclado: "ai" abierta
        if (want != null) {
            Section s = sections.get(want);
            if (s != null) setOpen(s, true, true);
        }
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
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateSetup();
    }

    /**
     * Las claves sec_* (seccion abierta o cerrada) vivian junto a las del teclado; ahora van en Prefs.ui para no
     * despertar al IME cada vez que se pliega una tarjeta. Se copian una vez y se borran de las antiguas.
     */
    private void migrateSectionState() {
        SharedPreferences old = Prefs.get(this);
        SharedPreferences ui = Prefs.ui(this);
        SharedPreferences.Editor clean = null, copy = null;
        for (java.util.Map.Entry<String, ?> e : old.getAll().entrySet()) {
            if (!e.getKey().startsWith("sec_")) continue;
            if (clean == null) { clean = old.edit(); copy = ui.edit(); }
            if (e.getValue() instanceof Boolean && !ui.contains(e.getKey())) copy.putBoolean(e.getKey(), (Boolean) e.getValue());
            clean.remove(e.getKey());
        }
        if (clean != null) { copy.apply(); clean.apply(); }
    }

    // ------------------------------------------------------------------ secciones

    private void buildHeader(LinearLayout root) {
        AurebeshTextView mark = new AurebeshTextView(this, 14, ink);
        mark.setText("aurebesh");
        root.addView(mark, block(0));
        root.addView(text(getString(R.string.app_name), 26, ink, true), block(10));
        root.addView(text(getString(R.string.tagline), 15, secondary, false), block(6));
    }

    /** Aviso solo fuera de Samsung One UI 9.x: el teclado se probo ahi; en otros dispositivos es experimental. */
    private void buildCompat(LinearLayout root) {
        if (DeviceInfo.optimized()) return;
        LinearLayout c = cardColumn();
        c.setPadding(px(20), px(16), px(20), px(18));
        c.addView(text(getString(R.string.compat_title), 15, ink, true), block(0));
        c.addView(text(getString(R.string.compat_body, DeviceInfo.label()), 13, secondary, false), block(4));
        root.addView(c, block(20));
    }

    /**
     * Lo primero de la app: los tres idiomas (cambian a la vez la app y el teclado) y el boton para
     * elegir el teclado, con el estado de la activacion debajo.
     */
    private void buildTop(LinearLayout root) {
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
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setLanguage(MainActivity.this, id);   // app y teclado a la vez
                    recreate();
                }
            });
            row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        c.addView(row, block(10));
        c.addView(text(getString(R.string.top_lang_hint), 12, secondary, false), block(4));
        keyboardButton = button();
        c.addView(keyboardButton, block(16));
        topStatus = text("", 13, secondary, false);
        c.addView(topStatus, block(8));
        root.addView(c, block(24));
    }

    private void buildTry(LinearLayout root) {
        LinearLayout c = column();
        c.setClipToOutline(true);

        input = new EditText(this);
        input.setBackground(null);
        input.setHint(R.string.try_hint);
        input.setTextColor(ink);
        input.setHintTextColor(secondary);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setPadding(px(18), px(16), px(18), px(6));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        input.setShowSoftInputOnFocus(false);   // se escribe con la copia del teclado de abajo
        c.addView(input, block(0));

        translation = new AurebeshTextView(this, 13, ink);
        translation.setPadding(px(18), 0, px(18), px(14));
        c.addView(translation, block(0));

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
        applyPreviewPrefs();
        c.addView(preview, block(0));
        root.addView(c, block(0));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(Editable s) { syncPreview(); }
        });
        syncPreview();
        input.requestFocus();
    }

    /** Diseno: tema (con Automatico), letra latina, barra de traduccion, digrafos, barra de herramientas, fila de numeros, modo y dividido. */
    private void buildDesign(LinearLayout root) {
        LinearLayout design = section(root, "design", R.string.aj_sec_design);
        themeRow(design);
        optionRow(design, R.string.opt_hints, R.string.opt_hints_sub, Prefs.HINTS, Prefs.hints(this));
        optionRow(design, R.string.opt_strip, R.string.opt_strip_sub, Prefs.STRIP, Prefs.strip(this));
        optionRow(design, R.string.opt_digraphs, R.string.opt_digraphs_sub, Prefs.DIGRAPHS, Prefs.digraphs(this));
        optionRow(design, R.string.opt_toolbar, R.string.opt_toolbar_sub, Prefs.TOOLBAR, Prefs.toolbar(this));
        optionRow(design, R.string.opt_numbers, R.string.opt_numbers_sub, Prefs.NUMBERS, Prefs.numbers(this));
        chipRow(design, R.string.aj_mode, getString(R.string.aj_mode_sub), handGroup());
        if (!wide) splitRows(design);   // en telefonos el dividido va aqui; en pantallas anchas, en Plegables
    }

    /** Plegables (solo pantallas anchas): explicacion, deteccion y las filas del dividido; Diseno no las repite. */
    private void buildFold(LinearLayout root) {
        LinearLayout fold = section(root, "fold", R.string.fold_section);
        LinearLayout foldText = column();
        foldText.setPadding(px(18), px(14), px(18), px(4));
        foldText.addView(text(getString(R.string.fold_title), 16, ink, false), block(0));
        foldText.addView(text(getString(R.string.fold_body), 13, secondary, false), block(4));
        fold.addView(foldText, block(0));
        splitRows(fold);
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

    /** Tamano y posicion: alto en % (deslizador), margen inferior (chips) y como ajustarlo desde el propio teclado. */
    private void buildSize(LinearLayout root) {
        LinearLayout size = section(root, "size", R.string.aj_sec_size);
        heightSlider = sliderRow(size, R.string.aj_height, R.string.aj_height_sub, 70, 140, Prefs.heightPct(this), PCT,
                new SliderListener() {
                    @Override public void onLive(int value) { liveHeightPct = value; applyPreviewPrefs(); }
                    @Override public void onSave(int value) {
                        liveHeightPct = 0;
                        Prefs.setHeightPct(MainActivity.this, value);
                        applyPreviewPrefs();
                    }
                });
        chipRow(size, R.string.aj_bottom, getString(R.string.aj_bottom_sub), bottomGroup());
        separator(size);
        LinearLayout notes = column();
        notes.setPadding(px(18), px(12), px(18), px(16));
        notes.addView(text(getString(R.string.aj_size_from_keyboard), 13, secondary, false), block(0));
        if (wide || DeviceInfo.family().startsWith("Galaxy Z Fold")) {
            // En plegables alto, margen y una mano se guardan por pantalla: decir cual se esta ajustando
            String which = wide ? getString(R.string.aj_size_screen_inner, DeviceInfo.smallestWidthDp(this))
                    : getString(R.string.aj_size_screen_cover);
            notes.addView(text(which, 13, secondary, true), block(6));
        }
        size.addView(notes, block(0));
    }

    /** Escritura inteligente: predictivo, sustitucion, mayusculas, deslizar, doble espacio, aprendizaje, atajos y espacio. */
    private void buildSmart(LinearLayout root) {
        LinearLayout smart = section(root, "smart", R.string.aj_sec_smart);
        optionRow(smart, R.string.opt_autocorrect, R.string.opt_autocorrect_sub, Prefs.AUTOCORRECT, Prefs.autocorrect(this));
        optionRow(smart, R.string.aj_auto_replace, R.string.aj_auto_replace_sub, Prefs.AUTO_REPLACE, Prefs.autoReplace(this));
        optionRow(smart, R.string.aj_auto_caps, R.string.aj_auto_caps_sub, Prefs.AUTO_CAPS, Prefs.autoCaps(this));
        optionRow(smart, R.string.opt_glide, R.string.opt_glide_sub, Prefs.GLIDE, Prefs.glide(this));
        optionRow(smart, R.string.opt_double_space, R.string.opt_double_space_sub, Prefs.DOUBLE_SPACE, Prefs.doubleSpace(this));
        optionRow(smart, R.string.opt_learn, R.string.opt_learn_sub, Prefs.LEARN, Prefs.learn(this));
        learnedTitle = linkRow(smart, R.string.aj_learned_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { showLearnedDialog(); }
        });
        shortcutsTitle = linkRow(smart, R.string.aj_shortcuts_sub, new View.OnClickListener() {
            @Override public void onClick(View v) { showShortcutsDialog(); }
        });
        chipRow(smart, R.string.aj_space_slide, getString(R.string.aj_space_slide_sub),
                prefGroup(Prefs.SPACE_SLIDE, "auto", SPACE_SLIDES, SPACE_SLIDE_NAMES));
    }

    /** Tocar y respuesta: burbuja, pulsacion larga, sonido (con el personalizado y su volumen) y vibracion con intensidad. */
    private void buildTouch(LinearLayout root) {
        LinearLayout touch = section(root, "touch", R.string.aj_sec_touch);
        optionRow(touch, R.string.aj_preview, R.string.aj_preview_sub, Prefs.PREVIEW, Prefs.preview(this));
        chipRow(touch, R.string.aj_longpress, getString(R.string.aj_longpress_sub),
                prefGroup(Prefs.LONGPRESS, "medium", LONGPRESSES, LONGPRESS_NAMES));
        optionRow(touch, R.string.opt_sound, R.string.aj_sound_sub, Prefs.SOUND, Prefs.sound(this));
        customSoundRow(touch);
        volumeSlider = sliderRow(touch, R.string.aj_volume, R.string.aj_volume_sub, 0, 100, Prefs.soundVolume(this), PCT,
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
        optionRow(touch, R.string.opt_vibrate, R.string.aj_vibrate_sub, Prefs.VIBRATE, Prefs.vibrate(this));
        chipRow(touch, R.string.aj_vibration, getString(R.string.aj_vibration_sub),
                prefGroup(Prefs.VIBRATION, "system", VIBRATIONS, VIBRATION_NAMES));
    }

    /** Portapapeles: que se guarda y la fila de capturas de pantalla (permiso opcional de fotos). */
    private void buildClipboard(LinearLayout root) {
        LinearLayout clip = section(root, "clipboard", R.string.aj_sec_clipboard);
        TextView body = text(getString(R.string.aj_clipboard_body), 13, secondary, false);
        body.setPadding(px(18), px(14), px(18), px(4));
        clip.addView(body, block(0));
        clip.addView(dividerLine());
        clip.addView(ScreenshotSettingsRow.build(this, ink, secondary, thumbOff, trackOff), block(0));
    }

    /** Ayuda y gestos: todo lo que se hace con las teclas, de la escritura a los paneles y el modo de ajuste. */
    private void buildHelp(LinearLayout root) {
        LinearLayout help = section(root, "help", R.string.aj_sec_help);
        gestureRow(help, R.string.gesture_accent, R.string.gesture_accent_result);
        gestureRow(help, R.string.gesture_enye, R.string.gesture_enye_result);
        gestureRow(help, R.string.gesture_marks, R.string.gesture_marks_result);              // mantener el punto
        gestureRow(help, R.string.aj_gesture_comma, R.string.aj_gesture_comma_result);
        gestureRow(help, R.string.gesture_caps, R.string.gesture_caps_result);
        gestureRow(help, R.string.aj_gesture_shift_hold, R.string.aj_gesture_shift_hold_result);
        gestureRow(help, R.string.aj_gesture_delete_hold, R.string.aj_gesture_delete_hold_result);
        gestureRow(help, R.string.gesture_symbols, R.string.gesture_symbols_result);
        gestureRow(help, R.string.aj_gesture_space, R.string.aj_gesture_space_result);
        gestureRow(help, R.string.gesture_picker, R.string.gesture_picker_result);
        gestureRow(help, R.string.gesture_switch, R.string.gesture_switch_result);
        gestureRow(help, R.string.gesture_glide, R.string.gesture_glide_result);
        gestureRow(help, R.string.gesture_emoji, R.string.gesture_emoji_result);
        gestureRow(help, R.string.gesture_clipboard, R.string.gesture_clipboard_result);
        gestureRow(help, R.string.gesture_ai, R.string.gesture_ai_result);
        gestureRow(help, R.string.gesture_gear, R.string.gesture_gear_result);
        gestureRow(help, R.string.aj_gesture_resize, R.string.aj_gesture_resize_result);
        gestureRow(help, R.string.aj_gesture_hide, R.string.aj_gesture_hide_result);
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
                Prefs.theme(this), Prefs.numbers(this), Prefs.sound(this), scale);
        preview.setHandMode(Prefs.handMode(this));
        preview.setSplitMode(Prefs.splitMode(this));
        preview.setSplitBox(Prefs.splitBox(this));
        preview.setToolbar(Prefs.toolbar(this));
        preview.setSuggestionBar(Prefs.autocorrect(this));   // como el IME: con predictivo la cinta se reserva aunque no haya barra
        preview.setGlideEnabled(Prefs.glide(this));
        preview.setPreviewEnabled(Prefs.preview(this));
        preview.setSpaceSlide(spaceSlideMode());
        preview.setLongPressMs(Prefs.longPressMs(this));
        preview.setHaptics(Prefs.vibrationLevel(this));
        preview.setBottomPad(Prefs.bottomDp(this) * dp);
    }

    /** Deslizar sobre el espacio en la copia: con un solo idioma, "auto" mueve el cursor (como en el IME). */
    private int spaceSlideMode() {
        String mode = Prefs.spaceSlide(this);
        if ("lang".equals(mode)) return AurebeshKeyboardView.SPACE_LANG;
        if ("none".equals(mode)) return AurebeshKeyboardView.SPACE_NONE;
        return AurebeshKeyboardView.SPACE_CURSOR;
    }

    /** Traduccion, barra y mayuscula automatica de la copia del teclado. */
    private void syncPreview() {
        String t = input.getText().toString();
        if (t.isEmpty()) {
            translation.setText(getString(R.string.try_hint));
            translation.setAlpha(0.35f);
        } else {
            translation.setText(t);
            translation.setAlpha(1f);
        }
        preview.setStripText(t);
        String before = t.substring(0, Math.min(selStart(), t.length())).trim();
        preview.setAutoShift(before.isEmpty() || before.endsWith(".") || before.endsWith("!")
                || before.endsWith("?") || before.endsWith("¿") || before.endsWith("¡"));
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

    /** Pone al dia resumenes, chips, contadores, muestras de tema y deslizadores con los valores guardados. */
    private void refreshControls() {
        updateSummaries();
        for (ChipGroup g : chipGroups) g.refresh();
        refreshThemeItems();
        refreshCounts();
        refreshSplitWidth();
        if (heightSlider != null) heightSlider.set(Prefs.heightPct(this));
        if (volumeSlider != null) volumeSlider.set(Prefs.soundVolume(this));
    }

    // ------------------------------------------------------------------ filas

    private static final String PCT = " %";   // "100 %" con espacio fino
    private static final String[] HANDS = {"none", "left", "right"};
    private static final int[] HAND_NAMES = {R.string.hand_none, R.string.hand_left, R.string.hand_right};
    private static final int[] MODE_NAMES = {R.string.aj_mode_standard, R.string.aj_mode_left, R.string.aj_mode_right};
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
     * Fila de opciones excluyentes (chips) con titulo y subtitulo opcional; la copia del teclado cambia al instante.
     * La linea divisoria va dentro de la fila devuelta, para que ocultarla no deje dos lineas juntas.
     */
    private View chipRow(LinearLayout parent, int title, String sub, final ChipGroup g) {
        LinearLayout outer = column();
        int n = parent.getChildCount();
        if (n > 0 && !"divider".equals(parent.getChildAt(n - 1).getTag())) outer.addView(dividerLine());
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(title), 16, ink, false), block(0));
        if (sub != null && !sub.isEmpty()) col.addView(text(sub, 13, secondary, false), block(2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String current = g.current();
        int sp = g.ids.length >= 4 ? 13 : 14;   // cuatro chips en un telefono: letra algo menor
        for (int i = 0; i < g.ids.length; i++) {
            final String id = g.ids[i];
            final TextView chip = text(g.labels[i], sp, ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(px(4), px(9), px(4), px(9));
            chip.setMaxLines(1);
            chip.setSelected(id.equals(current));
            styleChip(chip);
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
        outer.addView(col, block(0));
        parent.addView(outer, block(0));
        chipGroups.add(g);
        return outer;
    }

    private void styleChip(TextView chip) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(chip.isSelected() ? ink : divider);
        bg.setCornerRadius(px(10));
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

    private Slider sliderRow(LinearLayout parent, int title, int min, int max, int current, String unitSuffix, SliderListener l) {
        return sliderRow(parent, title, 0, min, max, current, unitSuffix, l);
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
        if (sub != 0) col.addView(text(getString(sub), 13, secondary, false), block(2));

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
        return s;
    }

    /** Campos que usan el teclado del sistema (el elegido en Ajustes): texto normal y contrasena. */
    private void buildRealFields(LinearLayout root) {
        root.addView(dividerLine());
        LinearLayout c = column();
        c.setPadding(0, px(14), 0, px(4));
        TextView title = text(getString(R.string.real_title), 16, ink, false);
        title.setPadding(px(18), 0, px(18), 0);
        c.addView(title, block(0));
        TextView sub = text(getString(R.string.real_sub), 13, secondary, false);
        sub.setPadding(px(18), 0, px(18), px(8));
        c.addView(sub, block(2));
        c.addView(dividerLine());
        EditText plain = realField(R.string.real_text_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        plain.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        c.addView(plain, block(0));
        c.addView(dividerLine());
        EditText password = realField(R.string.real_password_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        c.addView(password, block(0));
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
        e.setInputType(inputType);   // despues de setSingleLine para conservar el enmascarado de la contrasena
        return e;
    }

    /**
     * Fila "Tema": muestras de cada tema mas "Automatico" (sigue el modo oscuro del telefono: Estelar de dia,
     * Noche de noche); la copia del teclado cambia al instante. En telefonos, dos filas de tres.
     */
    private void themeRow(LinearLayout parent) {
        LinearLayout col = column();
        col.setPadding(px(10), px(14), px(10), px(14));
        TextView title = text(getString(R.string.opt_theme), 16, ink, false);
        title.setPadding(px(8), 0, px(8), 0);
        col.addView(title, block(0));

        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        Themes.Theme autoShown = night == Configuration.UI_MODE_NIGHT_YES ? Themes.NOCHE : Themes.DEFAULT;
        int n = Themes.ALL.length + 1;
        int perRow = contentWidth - px(20) >= px(78) * n ? n : (n + 1) / 2;
        String current = Prefs.themePref(this);
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, block(i == 0 ? 10 : 4));
            }
            boolean auto = i == Themes.ALL.length;
            final String id = auto ? Prefs.THEME_AUTO : Themes.ALL[i].id;
            Themes.Theme shown = auto ? autoShown : Themes.ALL[i];
            String label = auto ? getString(R.string.aj_theme_auto) : getString(shown.name);
            final LinearLayout item = column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, px(4), 0, px(4));
            item.setBackground(ripple(0x00000000));
            item.setContentDescription(label);
            item.addView(new ThemeSwatchView(this, shown, ink), new LinearLayout.LayoutParams(px(56), px(56)));
            TextView name = text(label, 12, secondary, false);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(1);
            item.addView(name, block(4));
            item.setSelected(id.equals(current));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.get(MainActivity.this).edit().putString(Prefs.THEME, id).apply();
                    refreshThemeItems();
                    applyPreviewPrefs();
                }
            });
            row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
            themeItems.add(item);
            themeIds.add(id);
        }
        for (int i = n; i % perRow != 0; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));   // huecos de la ultima fila
        TextView hint = text(getString(R.string.aj_theme_auto_hint), 13, secondary, false);
        hint.setPadding(px(8), 0, px(8), 0);
        col.addView(hint, block(8));
        parent.addView(col, block(0));
    }

    private void refreshThemeItems() {
        String cur = Prefs.themePref(this);
        for (int i = 0; i < themeItems.size(); i++) themeItems.get(i).setSelected(themeIds.get(i).equals(cur));
    }

    private void optionRow(LinearLayout parent, int label, int sub, final String key, boolean value) {
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
        if (sub != 0) col.addView(text(getString(sub), 13, secondary, false), block(2));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final Switch sw = new Switch(this);
        sw.setContentDescription(sub != 0 ? getString(label) + ". " + getString(sub) : getString(label));
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ink, thumbOff}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{(ink & 0x00FFFFFF) | 0x66000000, trackOff}));
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).edit().putBoolean(key, checked).apply();
                applyPreviewPrefs();
                if (Prefs.DIGRAPHS.equals(key)) translation.setDigraphs(checked);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(WRAP, WRAP);
        sp.leftMargin = px(12);
        row.addView(sw, sp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
        parent.addView(row, block(0));
    }

    /** Fila que abre un dialogo: titulo (con contador, se rellena en refreshCounts), subtitulo y ›. Devuelve el titulo. */
    private TextView linkRow(LinearLayout parent, int sub, View.OnClickListener onClick) {
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
        col.addView(text(getString(sub), 13, secondary, false), block(2));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView chevron = text("›", 22, secondary, false);
        chevron.setPadding(px(12), 0, px(6), 0);
        row.addView(chevron, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(onClick);
        parent.addView(row, block(0));
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
        col.addView(text(getString(gesture), 16, ink, false), block(0));
        col.addView(text(getString(result), 13, secondary, false), block(2));
        parent.addView(col, block(0));
        gestureCount++;
    }

    /** Boton de texto con el estilo de los chips. */
    private TextView chipButton(int label, View.OnClickListener onClick) {
        TextView chip = text(getString(label), 14, ink, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(px(14), px(9), px(14), px(9));
        chip.setSelected(false);
        styleChip(chip);
        chip.setOnClickListener(onClick);
        return chip;
    }

    /** Fila "Sonido personalizado": elegir un audio corto del telefono o volver al clic del sistema. */
    private void customSoundRow(LinearLayout parent) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(R.string.opt_custom_sound), 16, ink, false), block(0));
        col.addView(text(getString(R.string.opt_custom_sound_sub), 13, secondary, false), block(2));
        soundCurrent = text("", 13, secondary, true);
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
            @Override public void onClick(View v) {
                new java.io.File(getFilesDir(), KeySound.FILE).delete();
                Prefs.get(MainActivity.this).edit().remove(Prefs.CUSTOM_SOUND).apply();
                KeySound.get(MainActivity.this).reload(MainActivity.this);
                refreshSoundRow();
            }
        });
        row.addView(choose, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(WRAP, WRAP);
        rp.leftMargin = px(8);
        row.addView(reset, rp);
        col.addView(row, block(10));
        parent.addView(col, block(0));
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AiSettingsCard.onActivityResult(this, requestCode, resultCode, data);
        if (requestCode != PICK_SOUND || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
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

    // ------------------------------------------------------------------ dialogos: palabras aprendidas y atajos

    /** Palabras aprendidas tal como se guardan, la mas reciente primero (Prefs.learnedWords devuelve un conjunto sin orden). */
    private java.util.List<String> learnedList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : Prefs.get(this).getString(Prefs.LEARNED, "").split("\n")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Dialogo "Palabras aprendidas": lista con × por fila, campo para anadir y "Olvidar todo" (con confirmacion). */
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
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(field, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(WRAP, WRAP);
        ap.leftMargin = px(8);
        form.addView(add, ap);

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

    /**
     * Seccion colapsable: tarjeta con cabecera pulsable (titulo, resumen de una linea y flecha) y un cuerpo
     * al que el llamante anade sus filas. El estado abierto/cerrado se recuerda por seccion en Prefs.ui (no
     * despierta al teclado); al principio solo "Pruebalo" esta abierta. Devuelve el cuerpo.
     */
    private LinearLayout section(LinearLayout root, final String key, int titleRes) {
        LinearLayout outer = cardColumn();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(18), px(16), px(12), px(16));
        header.setBackground(ripple(0));
        LinearLayout col = column();
        col.addView(text(getString(titleRes), 17, ink, true), block(0));
        TextView summary = text("", 13, secondary, false);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(summary, block(2));
        header.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView chevron = text("▾", 20, secondary, false);   // ▾ cerrado, ▴ abierto
        chevron.setPadding(px(12), 0, px(6), 0);
        header.addView(chevron, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout body = column();
        body.addView(dividerLine());
        outer.addView(header, block(0));
        outer.addView(body, block(0));
        final Section sec = new Section(key, outer, body, summary, chevron);
        sections.put(key, sec);
        boolean open = Prefs.ui(this).getBoolean("sec_" + key, "try".equals(key));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setText(open ? "▴" : "▾");
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sec.outer.getLayoutTransition() == null) sec.outer.setLayoutTransition(new android.animation.LayoutTransition());
                setOpen(sec, !sec.open(), false);
            }
        });
        root.addView(outer, block(16));
        return body;
    }

    /** Abre o cierra una seccion (y lo recuerda); con scroll, la lleva arriba de la pantalla. */
    private void setOpen(final Section sec, boolean open, boolean scrollTo) {
        sec.body.setVisibility(open ? View.VISIBLE : View.GONE);
        sec.chevron.setText(open ? "▴" : "▾");
        Prefs.ui(this).edit().putBoolean("sec_" + sec.key, open).apply();
        if (scrollTo && scroll != null) {
            scroll.post(new Runnable() {
                @Override public void run() { scroll.smoothScrollTo(0, Math.max(0, sec.outer.getTop() - px(12))); }
            });
        }
    }

    /** Fila "Ir a" con un chip por seccion (sin Pruebalo): la abre y la lleva arriba, sin recorrer toda la pantalla. */
    private void buildNav(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(getString(R.string.nav_go), 13, secondary, true);
        label.setPadding(px(4), 0, px(10), 0);
        row.addView(label, new LinearLayout.LayoutParams(WRAP, WRAP));
        boolean first = true;
        for (int i = 0; i < NAV_KEYS.length; i++) {
            final String key = NAV_KEYS[i];
            if ("fold".equals(key) && !wide) continue;
            TextView chip = text(getString(NAV_TITLES[i]), 13, ink, true);
            chip.setPadding(px(12), px(7), px(12), px(7));
            chip.setSelected(false);
            styleChip(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Section sec = sections.get(key);
                    if (sec != null) setOpen(sec, true, true);
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(WRAP, WRAP);
            cp.leftMargin = px(first ? 0 : 6);
            first = false;
            row.addView(chip, cp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row, new ViewGroup.LayoutParams(WRAP, WRAP));
        root.addView(hs, block(14));
    }

    /** Resumen de una linea bajo el titulo de cada seccion, con los valores actuales. */
    private void updateSummaries() {
        Section s;
        if ((s = sections.get("try")) != null) s.summary.setText(R.string.sum_try);
        if ((s = sections.get("design")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(themeLabel());
            if (Prefs.hints(this)) parts.add(getString(R.string.sum_hints));
            if (Prefs.strip(this)) parts.add(getString(R.string.sum_strip));
            if (Prefs.toolbar(this)) parts.add(getString(R.string.sum_toolbar));
            String hand = Prefs.hand(this);
            if (!"none".equals(hand)) parts.add(getString(R.string.sum_hand, getString(nameOf(HANDS, HAND_NAMES, hand))));
            String split = Prefs.splitPref(this);
            if (!wide && !"auto".equals(split)) parts.add(getString(R.string.aj_sum_split, getString(nameOf(SPLITS, SPLIT_NAMES, split))));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("fold")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(getString(nameOf(SPLITS, SPLIT_NAMES, Prefs.splitPref(this))));
            parts.add(getString(nameOf(SPLIT_WIDTHS, SPLIT_WIDTH_NAMES, Prefs.splitWidth(this))).toLowerCase(java.util.Locale.getDefault()));
            parts.add(getString(R.string.sum_wide));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("size")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(Prefs.heightPct(this) + PCT);
            parts.add(getString(R.string.aj_sum_margin, Prefs.bottomDp(this)));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("smart")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (Prefs.autocorrect(this)) parts.add(getString(R.string.sum_autocorrect));
            if (Prefs.autoReplace(this)) parts.add(getString(R.string.aj_sum_replace));
            if (Prefs.glide(this)) parts.add(getString(R.string.sum_glide));
            if (Prefs.learn(this)) parts.add(getString(R.string.sum_learn));
            int n = Prefs.shortcuts(this).size();
            parts.add(getResources().getQuantityString(R.plurals.aj_sum_shortcuts, n, n));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("touch")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (Prefs.preview(this)) parts.add(getString(R.string.aj_sum_bubble));
            parts.add(lower(nameOf(LONGPRESSES, LONGPRESS_NAMES, Prefs.longPress(this))));
            if (Prefs.sound(this)) parts.add(getString(R.string.sum_sound));
            if (Prefs.vibrate(this)) {
                String level = Prefs.vibration(this);
                parts.add("system".equals(level) ? getString(R.string.sum_vibrate)
                        : getString(R.string.aj_sum_vibration, lower(nameOf(VIBRATIONS, VIBRATION_NAMES, level))));
            }
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("clipboard")) != null) {
            s.summary.setText(Prefs.screenshots(this) ? R.string.aj_sum_shots_on : R.string.aj_sum_shots_off);
        }
        if ((s = sections.get("ai")) != null) {
            String name = AiModels.engineShortName(this);
            if (AiModels.remote(this)) s.summary.setText(getString(R.string.sum_ai_remote, name == null ? "—" : name));
            else if (name != null) s.summary.setText(getString(R.string.sum_ai_ready, name));
            else s.summary.setText(R.string.sum_ai_none);
        }
        if ((s = sections.get("help")) != null) s.summary.setText(getString(R.string.sum_gestures, gestureCount));
    }

    /** Nombre del tema para el resumen; con Automatico, tambien el que esta en uso: "Automatico (Noche)". */
    private String themeLabel() {
        String name = getString(Themes.byId(Prefs.theme(this)).name);
        return Prefs.THEME_AUTO.equals(Prefs.themePref(this)) ? getString(R.string.aj_theme_auto) + " (" + name + ")" : name;
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

    /** Linea entre filas, salvo si la fila anterior ya es una linea (p. ej. la que sigue a la cabecera de la seccion). */
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
