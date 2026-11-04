package com.aurebesh.teclado;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Pantalla de la app: configurar el teclado en dos pasos, probarlo con una copia
 * funcional del teclado y ajustar las opciones. Sigue el modo claro/oscuro del sistema.
 */
public final class MainActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private float dp;
    private int surface, card, ink, secondary, divider, thumbOff, trackOff;
    private Typeface medium;

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
    private static final String[] SECTION_KEYS = {"try", "appearance", "typing", "fold", "ai", "gestures"};
    private static final int[] SECTION_TITLES = {R.string.try_title, R.string.appearance, R.string.typing,
            R.string.fold_section, R.string.ai_section, R.string.gestures};
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener summaryListener =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(android.content.SharedPreferences p, String key) {
                    if (key == null || key.startsWith("sec_")) return;
                    updateSummaries();
                }
            };
    private EditText input;
    private AurebeshTextView translation;
    private AurebeshKeyboardView preview;
    private View aiCard;
    private final java.util.List<View> themeItems = new java.util.ArrayList<>();
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

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(surface);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = column();
        root.setPadding(px(20), px(28), px(20), px(32));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        buildHeader(root);
        buildTop(root);
        buildNav(root);
        buildCompat(root);

        // Cada seccion es una tarjeta colapsable con resumen; solo "Pruebalo" empieza abierta
        LinearLayout tryBody = section(root, "try", R.string.try_title);
        buildTry(tryBody);
        buildRealFields(tryBody);

        LinearLayout appearance = section(root, "appearance", R.string.appearance);
        themeRow(appearance);
        optionRow(appearance, R.string.opt_hints, R.string.opt_hints_sub, Prefs.HINTS, Prefs.hints(this));
        optionRow(appearance, R.string.opt_strip, R.string.opt_strip_sub, Prefs.STRIP, Prefs.strip(this));
        optionRow(appearance, R.string.opt_toolbar, R.string.opt_toolbar_sub, Prefs.TOOLBAR, Prefs.toolbar(this));

        LinearLayout typing = section(root, "typing", R.string.typing);
        optionRow(typing, R.string.opt_digraphs, R.string.opt_digraphs_sub, Prefs.DIGRAPHS, Prefs.digraphs(this));
        optionRow(typing, R.string.opt_numbers, R.string.opt_numbers_sub, Prefs.NUMBERS, Prefs.numbers(this));
        chipRow(typing, R.string.opt_height, Prefs.HEIGHT, SIZES, SIZE_NAMES, Prefs.height(this));
        chipRow(typing, R.string.opt_hand, Prefs.HAND, HANDS, HAND_NAMES, Prefs.hand(this));
        optionRow(typing, R.string.opt_glide, R.string.opt_glide_sub, Prefs.GLIDE, Prefs.glide(this));
        optionRow(typing, R.string.opt_autocorrect, R.string.opt_autocorrect_sub, Prefs.AUTOCORRECT, Prefs.autocorrect(this));
        optionRow(typing, R.string.opt_double_space, R.string.opt_double_space_sub, Prefs.DOUBLE_SPACE, Prefs.doubleSpace(this));
        optionRow(typing, R.string.opt_learn, R.string.opt_learn_sub, Prefs.LEARN, Prefs.learn(this));
        learnClearRow(typing);
        optionRow(typing, R.string.opt_sound, 0, Prefs.SOUND, Prefs.sound(this));
        customSoundRow(typing);
        optionRow(typing, R.string.opt_vibrate, 0, Prefs.VIBRATE, Prefs.vibrate(this));
        // Portapapeles: capturas de pantalla recientes (permiso opcional de fotos)
        typing.addView(dividerLine());
        typing.addView(ScreenshotSettingsRow.build(this, ink, secondary, thumbOff, trackOff), block(0));

        // Plegables: teclado dividido como el de Samsung en un Fold desplegado; en telefonos y Flip no cambia nada
        LinearLayout fold = section(root, "fold", R.string.fold_section);
        LinearLayout foldText = column();
        foldText.setPadding(px(18), px(14), px(18), px(4));
        foldText.addView(text(getString(R.string.fold_title), 16, ink, false), block(0));
        foldText.addView(text(getString(R.string.fold_body), 13, secondary, false), block(4));
        int sw = DeviceInfo.smallestWidthDp(this);
        foldText.addView(text(getString(DeviceInfo.wideScreen(this) ? R.string.fold_detected_wide : R.string.fold_detected_phone, sw),
                13, secondary, true), block(8));
        fold.addView(foldText, block(0));
        chipRow(fold, R.string.opt_split, Prefs.SPLIT, SPLITS, SPLIT_NAMES, Prefs.splitPref(this));

        // Inteligencia: modelo local o servidor propio
        LinearLayout aiBody = section(root, "ai", R.string.ai_section);
        aiCard = AiSettingsCard.build(this, ink, secondary, card, divider);
        aiBody.addView(aiCard, block(0));

        LinearLayout gestures = section(root, "gestures", R.string.gestures);
        gestureRow(gestures, R.string.gesture_accent, R.string.gesture_accent_result);
        gestureRow(gestures, R.string.gesture_enye, R.string.gesture_enye_result);
        gestureRow(gestures, R.string.gesture_marks, R.string.gesture_marks_result);
        gestureRow(gestures, R.string.gesture_caps, R.string.gesture_caps_result);
        gestureRow(gestures, R.string.gesture_symbols, R.string.gesture_symbols_result);
        gestureRow(gestures, R.string.gesture_switch, R.string.gesture_switch_result);
        gestureRow(gestures, R.string.gesture_picker, R.string.gesture_picker_result);
        gestureRow(gestures, R.string.gesture_emoji, R.string.gesture_emoji_result);
        gestureRow(gestures, R.string.gesture_clipboard, R.string.gesture_clipboard_result);
        gestureRow(gestures, R.string.gesture_glide, R.string.gesture_glide_result);
        gestureRow(gestures, R.string.gesture_ai, R.string.gesture_ai_result);
        gestureRow(gestures, R.string.gesture_gear, R.string.gesture_gear_result);
        updateSummaries();
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
        if ("ai".equals(getIntent().getStringExtra("section"))) {   // desde el engrane del teclado: Inteligencia abierta
            Section ai = sections.get("ai");
            if (ai != null) setOpen(ai, true, true);
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
            @Override public void onHide() {}
            @Override public void onResize(int heightPct, int bottomDp) {
                Prefs.setHeightPct(MainActivity.this, heightPct);
                Prefs.setBottomDp(MainActivity.this, bottomDp);
                applyPreviewPrefs();
            }
            @Override public void onGlide(String best, java.util.List<String> alternatives) { replaceSelection(best + " "); }
            @Override public void onToolbar(int tool) {
                // En la copia del teclado: un emoji de muestra o pegar el portapapeles
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

    private void applyPreviewPrefs() {
        preview.applyPrefs(Prefs.hints(this), Prefs.vibrate(this), Prefs.strip(this), Prefs.digraphs(this),
                Prefs.theme(this), Prefs.numbers(this), Prefs.sound(this), Prefs.heightScale(this));
        preview.setHandMode(Prefs.handMode(this));
        preview.setSplitMode(Prefs.splitMode(this));
        preview.setToolbar(Prefs.toolbar(this));
        preview.setGlideEnabled(Prefs.glide(this));
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

    // ------------------------------------------------------------------ filas

    private static final String[] SIZES = {"compact", "normal", "large", "xlarge"};
    private static final int[] SIZE_NAMES = {R.string.size_compact, R.string.size_normal, R.string.size_large, R.string.size_xlarge};
    private static final String[] HANDS = {"none", "left", "right"};
    private static final String[] SPLITS = {"auto", "on", "off"};
    private static final int[] SPLIT_NAMES = {R.string.split_auto, R.string.split_on, R.string.split_off};
    private static final String[] TOP_LANGS = {"es", "en", "de"};
    private static final int[] TOP_LANG_NAMES = {R.string.app_lang_es, R.string.app_lang_en, R.string.app_lang_de};
    private static final int[] HAND_NAMES = {R.string.hand_none, R.string.hand_left, R.string.hand_right};

    /** Fila de opciones excluyentes (chips); la copia del teclado cambia al instante. */
    private void chipRow(LinearLayout parent, int title, final String prefKey, final String[] ids, int[] names, String current) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(14), px(18), px(16));
        col.addView(text(getString(title), 16, ink, false), block(0));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final java.util.List<TextView> chips = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            final TextView chip = text(getString(names[i]), 14, ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, px(9), 0, px(9));
            chip.setSelected(id.equals(current));
            styleChip(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.get(MainActivity.this).edit().putString(prefKey, id).apply();
                    if (Prefs.APP_LANG.equals(prefKey)) { recreate(); return; }
                    for (TextView other : chips) {
                        other.setSelected(other == chip);
                        styleChip(other);
                    }
                    applyPreviewPrefs();
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) cp.leftMargin = px(8);
            row.addView(chip, cp);
            chips.add(chip);
        }
        col.addView(row, block(10));
        parent.addView(col, block(0));
    }

    private void styleChip(TextView chip) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(chip.isSelected() ? ink : divider);
        bg.setCornerRadius(px(10));
        chip.setBackground(bg);
        chip.setTextColor(chip.isSelected() ? card : ink);
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

    /** Fila "Tema": muestras de cada tema; la copia del teclado cambia al instante. */
    private void themeRow(LinearLayout parent) {
        LinearLayout col = column();
        col.setPadding(px(10), px(14), px(10), px(14));
        TextView title = text(getString(R.string.opt_theme), 16, ink, false);
        title.setPadding(px(8), 0, px(8), 0);
        col.addView(title, block(0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String current = Prefs.theme(this);
        for (final Themes.Theme t : Themes.ALL) {
            final LinearLayout item = column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, px(4), 0, px(4));
            item.setBackground(ripple(0x00000000));
            item.setContentDescription(getString(t.name));
            item.addView(new ThemeSwatchView(this, t, ink), new LinearLayout.LayoutParams(px(56), px(56)));
            TextView name = text(getString(t.name), 12, secondary, false);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(name, block(4));
            item.setSelected(t.id.equals(current));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.get(MainActivity.this).edit().putString(Prefs.THEME, t.id).apply();
                    for (View other : themeItems) other.setSelected(other == item);
                    applyPreviewPrefs();
                }
            });
            row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
            themeItems.add(item);
        }
        col.addView(row, block(10));
        parent.addView(col, block(0));
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

    private void gestureRow(LinearLayout parent, int gesture, int result) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(12), px(18), px(12));
        col.addView(text(getString(gesture), 16, ink, false), block(0));
        col.addView(text(getString(result), 13, secondary, false), block(2));
        parent.addView(col, block(0));
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

    /** Boton "Olvidar palabras aprendidas". */
    private void learnClearRow(LinearLayout parent) {
        separator(parent);
        LinearLayout col = column();
        col.setPadding(px(18), px(12), px(18), px(16));
        TextView clear = chipButton(R.string.learn_clear, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.clearLearnedWords(MainActivity.this);
                Bigrams.forget(MainActivity.this);
                Toast.makeText(MainActivity.this, R.string.learn_cleared, Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));
        parent.addView(col, block(0));
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
     * al que el llamante anade sus filas. El estado abierto/cerrado se recuerda por seccion; al principio
     * solo "Pruebalo" esta abierta. Devuelve el cuerpo.
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
        TextView chevron = text("\u25BE", 20, secondary, false);   // ▾ cerrado, ▴ abierto
        chevron.setPadding(px(12), 0, px(6), 0);
        header.addView(chevron, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout body = column();
        body.addView(dividerLine());
        outer.addView(header, block(0));
        outer.addView(body, block(0));
        final Section sec = new Section(key, outer, body, summary, chevron);
        sections.put(key, sec);
        boolean open = Prefs.get(this).getBoolean("sec_" + key, "try".equals(key));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setText(open ? "\u25B4" : "\u25BE");
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
        sec.chevron.setText(open ? "\u25B4" : "\u25BE");
        Prefs.get(this).edit().putBoolean("sec_" + sec.key, open).apply();
        if (scrollTo && scroll != null) {
            scroll.post(new Runnable() {
                @Override public void run() { scroll.smoothScrollTo(0, Math.max(0, sec.outer.getTop() - px(12))); }
            });
        }
    }

    /** Fila "Ir a" con un chip por seccion: la abre y la lleva arriba, sin recorrer toda la pantalla. */
    private void buildNav(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(getString(R.string.nav_go), 13, secondary, true);
        label.setPadding(px(4), 0, px(10), 0);
        row.addView(label, new LinearLayout.LayoutParams(WRAP, WRAP));
        for (int i = 0; i < SECTION_KEYS.length; i++) {
            final String key = SECTION_KEYS[i];
            TextView chip = text(getString(SECTION_TITLES[i]), 13, ink, true);
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
            cp.leftMargin = px(i == 0 ? 0 : 6);
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
        if ((s = sections.get("appearance")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            Themes.Theme t = Themes.byId(Prefs.theme(this));
            parts.add(getString(t.name));
            if (Prefs.hints(this)) parts.add(getString(R.string.sum_hints));
            if (Prefs.toolbar(this)) parts.add(getString(R.string.sum_toolbar));
            if (Prefs.strip(this)) parts.add(getString(R.string.sum_strip));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("typing")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(getString(nameOf(SIZES, SIZE_NAMES, Prefs.height(this))));
            if (Prefs.glide(this)) parts.add(getString(R.string.sum_glide));
            if (Prefs.autocorrect(this)) parts.add(getString(R.string.sum_autocorrect));
            if (Prefs.learn(this)) parts.add(getString(R.string.sum_learn));
            if (Prefs.sound(this)) parts.add(getString(R.string.sum_sound));
            if (Prefs.vibrate(this)) parts.add(getString(R.string.sum_vibrate));
            if (!"none".equals(Prefs.hand(this))) parts.add(getString(R.string.sum_hand, getString(nameOf(HANDS, HAND_NAMES, Prefs.hand(this)))));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("fold")) != null) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(getString(nameOf(SPLITS, SPLIT_NAMES, Prefs.splitPref(this))));
            parts.add(getString(DeviceInfo.wideScreen(this) ? R.string.sum_wide : R.string.sum_phone));
            s.summary.setText(join(parts));
        }
        if ((s = sections.get("ai")) != null) {
            String name = AiModels.engineShortName(this);
            if (AiModels.remote(this)) s.summary.setText(getString(R.string.sum_ai_remote, name == null ? "—" : name));
            else if (name != null) s.summary.setText(getString(R.string.sum_ai_ready, name));
            else s.summary.setText(R.string.sum_ai_none);
        }
        if ((s = sections.get("gestures")) != null) s.summary.setText(getString(R.string.sum_gestures, 12));
    }

    private static int nameOf(String[] ids, int[] names, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return names[i];
        return names[0];
    }

    private static String join(java.util.List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(" \u00b7 ");
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
