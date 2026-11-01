package com.aurebesh.teclado;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
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

    private TextView setupStep, setupTitle, setupHint;
    private Button setupButton;
    private EditText input;
    private AurebeshTextView translation;
    private AurebeshKeyboardView preview;
    private final java.util.List<View> themeItems = new java.util.ArrayList<>();

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

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(surface);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = column();
        root.setPadding(px(20), px(28), px(20), px(32));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        buildHeader(root);
        buildCompat(root);
        buildSetup(root);

        section(root, R.string.try_title);
        buildTry(root);
        buildRealFields(root);

        section(root, R.string.appearance);
        LinearLayout appearance = cardColumn();
        themeRow(appearance);
        optionRow(appearance, R.string.opt_hints, R.string.opt_hints_sub, Prefs.HINTS, Prefs.hints(this));
        optionRow(appearance, R.string.opt_strip, R.string.opt_strip_sub, Prefs.STRIP, Prefs.strip(this));
        optionRow(appearance, R.string.opt_toolbar, R.string.opt_toolbar_sub, Prefs.TOOLBAR, Prefs.toolbar(this));
        root.addView(appearance, block(0));

        section(root, R.string.typing);
        LinearLayout typing = cardColumn();
        optionRow(typing, R.string.opt_digraphs, R.string.opt_digraphs_sub, Prefs.DIGRAPHS, Prefs.digraphs(this));
        optionRow(typing, R.string.opt_numbers, R.string.opt_numbers_sub, Prefs.NUMBERS, Prefs.numbers(this));
        chipRow(typing, R.string.opt_height, Prefs.HEIGHT, SIZES, SIZE_NAMES, Prefs.height(this));
        chipRow(typing, R.string.opt_hand, Prefs.HAND, HANDS, HAND_NAMES, Prefs.hand(this));
        optionRow(typing, R.string.opt_autocorrect, R.string.opt_autocorrect_sub, Prefs.AUTOCORRECT, Prefs.autocorrect(this));
        optionRow(typing, R.string.opt_sound, 0, Prefs.SOUND, Prefs.sound(this));
        optionRow(typing, R.string.opt_vibrate, 0, Prefs.VIBRATE, Prefs.vibrate(this));
        root.addView(typing, block(0));

        section(root, R.string.gestures);
        LinearLayout gestures = cardColumn();
        gestureRow(gestures, R.string.gesture_accent, R.string.gesture_accent_result);
        gestureRow(gestures, R.string.gesture_enye, R.string.gesture_enye_result);
        gestureRow(gestures, R.string.gesture_marks, R.string.gesture_marks_result);
        gestureRow(gestures, R.string.gesture_caps, R.string.gesture_caps_result);
        gestureRow(gestures, R.string.gesture_symbols, R.string.gesture_symbols_result);
        gestureRow(gestures, R.string.gesture_switch, R.string.gesture_switch_result);
        gestureRow(gestures, R.string.gesture_picker, R.string.gesture_picker_result);
        gestureRow(gestures, R.string.gesture_emoji, R.string.gesture_emoji_result);
        gestureRow(gestures, R.string.gesture_clipboard, R.string.gesture_clipboard_result);
        root.addView(gestures, block(0));

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetup();
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

    private void buildSetup(LinearLayout root) {
        LinearLayout c = cardColumn();
        c.setPadding(px(20), px(18), px(20), px(20));
        setupStep = text("", 13, secondary, true);
        setupTitle = text("", 17, ink, true);
        setupHint = text("", 14, secondary, false);
        setupButton = button();
        c.addView(setupStep, block(0));
        c.addView(setupTitle, block(4));
        c.addView(setupHint, block(6));
        c.addView(setupButton, block(16));
        root.addView(c, block(24));
    }

    private void buildTry(LinearLayout root) {
        LinearLayout c = cardColumn();
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
        preview.setLanguage("es".equals(java.util.Locale.getDefault().getLanguage()) ? "es" : "en");
        preview.setEnterAction(4);
        preview.setListener(new AurebeshKeyboardView.Listener() {
            @Override public void onText(String s) { replaceSelection(s); }
            @Override public void onEnter() { replaceSelection("\n"); }
            @Override public void onSwitchKeyboard() {}
            @Override public void onSwitchLanguage() {}
            @Override public void onSuggestion(String word) { replaceSelection(word + " "); }
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
        boolean enabled = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (info.getPackageName().equals(getPackageName())) enabled = true;
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");

        if (selected) {
            setupStep.setVisibility(View.GONE);
            setupTitle.setText("✓  " + getString(R.string.setup_done_title));
            setupHint.setText(R.string.setup_done_hint);
            setupHint.setVisibility(View.VISIBLE);
            styleButton(setupButton, false);
            setupButton.setText(R.string.setup_done_action);
            setupButton.setOnClickListener(showPicker);
        } else if (enabled) {
            setupStep.setVisibility(View.VISIBLE);
            setupStep.setText(R.string.step_2);
            setupTitle.setText(R.string.setup_select_title);
            setupHint.setVisibility(View.GONE);
            styleButton(setupButton, true);
            setupButton.setText(R.string.setup_select_action);
            setupButton.setOnClickListener(showPicker);
        } else {
            setupStep.setVisibility(View.VISIBLE);
            setupStep.setText(R.string.step_1);
            setupTitle.setText(R.string.setup_enable_title);
            setupHint.setVisibility(View.GONE);
            styleButton(setupButton, true);
            setupButton.setText(R.string.setup_enable_action);
            setupButton.setOnClickListener(new View.OnClickListener() {
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
        preview.setToolbar(Prefs.toolbar(this));
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
    private static final int[] HAND_NAMES = {R.string.hand_none, R.string.hand_left, R.string.hand_right};

    /** Fila de opciones excluyentes (chips); la copia del teclado cambia al instante. */
    private void chipRow(LinearLayout parent, int title, final String prefKey, final String[] ids, int[] names, String current) {
        if (parent.getChildCount() > 0) parent.addView(dividerLine());
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
        LinearLayout c = cardColumn();
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
        root.addView(c, block(12));
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
        if (parent.getChildCount() > 0) parent.addView(dividerLine());
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
        if (parent.getChildCount() > 0) parent.addView(dividerLine());
        LinearLayout col = column();
        col.setPadding(px(18), px(12), px(18), px(12));
        col.addView(text(getString(gesture), 16, ink, false), block(0));
        col.addView(text(getString(result), 13, secondary, false), block(2));
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

    private void section(LinearLayout root, int title) {
        LinearLayout.LayoutParams p = block(28);
        p.bottomMargin = px(10);
        root.addView(text(getString(title), 17, ink, true), p);
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

    private View dividerLine() {
        View v = new View(this);
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
