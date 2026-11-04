package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Panel "Inteligencia": asistente de escritura al estilo de Samsung Keyboard que sustituye a las
 * teclas. Estados: sin modelo (explicacion y boton a ajustes), acciones (3 + 3 + 1: ortografia,
 * estilo, resumir / vinetas, tabla, traducir / redactor), submenus de tonos y de idioma destino,
 * entrada de texto con teclado reducido (tono personalizado, otro idioma y Redactor), generacion en
 * curso con el texto en directo y Cancelar, y resultado con Reemplazar / Insertar / Copiar / Reintentar.
 *
 * <p>El panel lee el texto del campo a traves de {@link Host} al abrirse y al lanzar cada accion;
 * nunca lo guarda. El trabajo lo hace {@link AiRunner} (modelo local o servidor del usuario). Con el
 * runner compartido del servicio, un panel nuevo (la vista se rehace al girar, cambiar de tema o de
 * idioma) se engancha a la generacion que hubiera en vuelo y la muestra como si la hubiera lanzado el.
 */
final class AiTray extends LinearLayout {

    /** Puente con el servicio de teclado. */
    interface Host {
        CharSequence currentText();          // todo el texto del campo (o lo que se pueda leer)
        CharSequence selectedText();         // la seleccion, o null/vacio
        void replaceText(CharSequence t);    // sustituye la seleccion o todo el campo
        void insertText(CharSequence t);     // inserta en el cursor
        void copyText(CharSequence t);       // al portapapeles
        void closeTray();                    // vuelve a las teclas
        void openSettings();                 // abre la pantalla de ajustes (seccion IA)
    }

    enum State { NO_MODEL, DISABLED_FIELD, IDLE, TONES, TARGETS, PROMPT, RUNNING, RESULT, ERROR }
    private enum PromptKind { TONE, TARGET, COMPOSE }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int PROMPT_MAX = 300, TONE_MAX = 60, TARGET_MAX = 40;
    /** Preferencia: ultimo idioma destino escrito a mano en Traducir (como AiModels.LAST_TONE para los tonos). */
    private static final String LAST_TARGET = "ai_last_target";

    private final float dp;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Themes.Theme theme = Themes.DEFAULT;
    private Host host;
    private String lang = "es";
    private boolean vibrate = true, sound = true;
    private boolean fieldAllowed = true;
    private final AiRunner runner;
    private State state = State.IDLE;

    // Texto fuente leido del campo
    private String source = "";
    private boolean hadSelection;

    // Peticion y resultado actuales
    private AiRunner.Request lastRequest;
    private String result;
    private PromptKind promptKind = PromptKind.COMPOSE;
    private boolean useContext = true;
    private final StringBuilder query = new StringBuilder();
    private boolean userScrolled;
    private boolean attached;        // este panel es el oyente del runner
    private boolean unseen;          // el resultado (o error) llego con el panel oculto: show() lo conserva

    // Cabecera
    private final LinearLayout header;
    private final HeaderButton back;
    private final TextView title, badge, sourceLine;

    // Contenido por estado
    private final FrameLayout content;
    private final ScrollView noModelScroll;
    private final TextView noModelBody, noModelButton, noModelNote;
    private final TextView disabledView;
    private final ScrollView gridScroll;
    private final LinearLayout grid;
    private final List<Card> cards = new ArrayList<>();
    private final AbcKey gridAbc;
    private final ScrollView tonesScroll;          // tonos de Estilo o idiomas de Traducir
    private final LinearLayout tones;
    private final List<TextView> toneChips = new ArrayList<>();
    private final LinearLayout runView;
    private final TextView phase, output;
    private final ScrollView outScroll;
    private final LinearLayout errorView;
    private final TextView errorText;

    // Entrada de texto (tono personalizado / Redactor)
    private final LinearLayout promptBar, contextRow;
    private final HeaderButton promptBack;
    private final TextView promptIcon, queryView, promptClear, contextChip;
    private final AurebeshKeyboardView miniKb;

    // Fila inferior
    private final LinearLayout bottom;
    private final AbcKey abc;
    private final TextView cancelKey, replaceKey, insertKey, copyKey, retryKey;
    private final List<TextView> keys = new ArrayList<>();

    /** True si el runner es propio (se libera con el panel); false si lo presta el servicio y sobrevive al rehacer la vista. */
    private final boolean ownsRunner;

    AiTray(Context c) { this(c, null); }

    /** Con un runner compartido (el del servicio) el modelo no se descarga al plegar, girar o cambiar de tema. */
    AiTray(Context c, AiRunner shared) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        runner = shared != null ? shared : new AiRunner(c);
        ownsRunner = shared == null;

        // ---------------------------------------------------------------- cabecera
        header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(px(44));
        header.setPadding(px(6), 0, px(6), 0);
        back = new HeaderButton(c);
        back.setContentDescription("close");
        back.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tap(v); onBack(); }
        });
        header.addView(back, new LinearLayout.LayoutParams(px(40), px(40)));
        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setAllCaps(true);
        title.setLetterSpacing(0.08f);
        title.setTypeface(medium);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(px(8), 0, px(8), 0);
        title.setText(R.string.ai_title);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        badge = new TextView(c);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(medium);
        badge.setMaxLines(1);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        badge.setMaxWidth(px(170));
        badge.setPadding(px(10), px(5), px(10), px(5));
        badge.setContentDescription("ai_engine");
        badge.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tap(v); if (host != null) host.openSettings(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, WRAP);
        bp.rightMargin = px(4);
        header.addView(badge, bp);
        addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        sourceLine = new TextView(c);
        sourceLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sourceLine.setMaxLines(1);
        sourceLine.setEllipsize(TextUtils.TruncateAt.END);
        sourceLine.setPadding(px(16), 0, px(16), px(2));
        addView(sourceLine, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---------------------------------------------------------------- contenido
        content = new FrameLayout(c);
        addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Sin modelo
        noModelScroll = scroll(c);
        LinearLayout noModel = column(c);
        noModel.setPadding(px(20), px(6), px(20), px(10));
        noModelBody = new TextView(c);
        noModelBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        noModelBody.setLineSpacing(0, 1.15f);
        noModelBody.setText(R.string.ai_no_model_body);
        noModel.addView(noModelBody, new LinearLayout.LayoutParams(MATCH, WRAP));
        noModelButton = new TextView(c);
        noModelButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        noModelButton.setTypeface(medium);
        noModelButton.setGravity(Gravity.CENTER);
        noModelButton.setPadding(px(20), 0, px(20), 0);
        noModelButton.setText(R.string.ai_open_settings);
        noModelButton.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tap(v); if (host != null) host.openSettings(); }
        });
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(WRAP, px(36));
        nb.topMargin = px(8);
        nb.gravity = Gravity.CENTER_HORIZONTAL;
        noModel.addView(noModelButton, nb);
        noModelNote = new TextView(c);
        noModelNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        noModelNote.setGravity(Gravity.CENTER);
        noModelNote.setText(R.string.ai_privacy_note);
        LinearLayout.LayoutParams nn = new LinearLayout.LayoutParams(MATCH, WRAP);
        nn.topMargin = px(6);
        noModel.addView(noModelNote, nn);
        noModelScroll.addView(noModel, new ViewGroup.LayoutParams(MATCH, WRAP));
        content.addView(noModelScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Campo no admitido
        disabledView = new TextView(c);
        disabledView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        disabledView.setGravity(Gravity.CENTER);
        disabledView.setPadding(px(24), px(14), px(24), px(14));
        disabledView.setText(R.string.ai_disabled_field);
        content.addView(disabledView, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Acciones: 3 + 3 + (abc + 1)
        gridScroll = scroll(c);
        grid = column(c);
        grid.setPadding(px(6), px(2), px(6), px(6));
        LinearLayout row1 = row(c);
        row1.addView(card(c, "✔", R.string.ai_action_fix, AiRunner.Action.FIX), cell(1f));
        row1.addView(card(c, "✎", R.string.ai_action_style, null), cell(1f));
        row1.addView(card(c, "≡", R.string.ai_action_summary, AiRunner.Action.SUMMARY), cell(1f));
        grid.addView(row1, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout row2 = row(c);
        row2.addView(card(c, "•", R.string.ai_action_bullets, AiRunner.Action.BULLETS), cell(1f));
        row2.addView(card(c, "▦", R.string.ai_action_table, AiRunner.Action.TABLE), cell(1f));
        row2.addView(card(c, "⇄", R.string.ai2_action_translate, AiRunner.Action.TRANSLATE), cell(1f));
        grid.addView(row2, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout row3 = row(c);
        gridAbc = new AbcKey(c);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(56), px(48));
        ap.setMargins(px(3), px(3), px(3), px(3));
        row3.addView(gridAbc, ap);
        row3.addView(card(c, "✧", R.string.ai_action_compose, AiRunner.Action.COMPOSE), cell(1f));
        grid.addView(row3, new LinearLayout.LayoutParams(MATCH, WRAP));
        gridScroll.addView(grid, new ViewGroup.LayoutParams(MATCH, WRAP));
        content.addView(gridScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Tonos (Estilo) e idiomas destino (Traducir): misma lista, se rellena al abrir cada submenu
        tonesScroll = scroll(c);
        tones = column(c);
        tones.setPadding(px(10), px(2), px(10), px(16));
        tonesScroll.addView(tones, new ViewGroup.LayoutParams(MATCH, WRAP));
        content.addView(tonesScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Generacion y resultado
        runView = column(c);
        phase = new TextView(c);
        phase.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        phase.setMaxLines(1);
        phase.setEllipsize(TextUtils.TruncateAt.END);
        phase.setPadding(px(16), px(2), px(16), px(2));
        runView.addView(phase, new LinearLayout.LayoutParams(MATCH, WRAP));
        outScroll = scroll(c);
        outScroll.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) userScrolled = true;
                return false;
            }
        });
        output = new TextView(c);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        output.setLineSpacing(0, 1.12f);
        output.setPadding(px(16), px(4), px(16), px(10));
        output.setTextIsSelectable(false);
        outScroll.addView(output, new ViewGroup.LayoutParams(MATCH, WRAP));
        runView.addView(outScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        content.addView(runView, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Error
        errorView = column(c);
        errorView.setGravity(Gravity.CENTER);
        errorText = new TextView(c);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(px(24), px(10), px(24), px(10));
        errorView.addView(errorText, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(errorView, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---------------------------------------------------------------- entrada de texto
        promptBar = new LinearLayout(c);
        promptBar.setOrientation(HORIZONTAL);
        promptBar.setGravity(Gravity.CENTER_VERTICAL);
        promptBar.setPadding(px(6), 0, px(6), 0);
        promptBack = new HeaderButton(c);
        promptBack.setBack(true);
        promptBack.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tap(v); onBack(); }
        });
        promptBar.addView(promptBack, new LinearLayout.LayoutParams(px(40), px(40)));
        promptIcon = new TextView(c);
        promptIcon.setText("✧");
        promptIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        promptIcon.setGravity(Gravity.CENTER);
        promptBar.addView(promptIcon, new LinearLayout.LayoutParams(px(28), px(40)));
        queryView = new TextView(c);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        queryView.setSingleLine(true);
        queryView.setEllipsize(TextUtils.TruncateAt.START);
        queryView.setPadding(px(4), 0, px(4), 0);
        promptBar.addView(queryView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        promptClear = new TextView(c);
        promptClear.setText("×");
        promptClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        promptClear.setGravity(Gravity.CENTER);
        promptClear.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tap(v); query.setLength(0); styleQuery(); }
        });
        promptBar.addView(promptClear, new LinearLayout.LayoutParams(px(40), px(40)));
        addView(promptBar, new LinearLayout.LayoutParams(MATCH, px(40)));

        contextRow = new LinearLayout(c);
        contextRow.setOrientation(HORIZONTAL);
        contextRow.setGravity(Gravity.CENTER_VERTICAL);
        contextRow.setPadding(px(14), 0, px(14), px(2));
        contextChip = new TextView(c);
        contextChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        contextChip.setTypeface(medium);
        contextChip.setPadding(px(10), px(3), px(10), px(3));
        contextChip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { tap(v); useContext = !useContext; styleContextChip(); }
        });
        contextRow.addView(contextChip, new LinearLayout.LayoutParams(WRAP, WRAP));
        addView(contextRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        miniKb = new AurebeshKeyboardView(c);
        miniKb.setToolbar(false);
        miniKb.setSuggestionBar(false);
        miniKb.setEnterAction(2);
        miniKb.setListener(new AurebeshKeyboardView.Listener() {
            @Override public void onText(String text) {
                if ("\n".equals(text)) { go(); return; }
                int max = promptKind == PromptKind.TONE ? TONE_MAX : promptKind == PromptKind.TARGET ? TARGET_MAX : PROMPT_MAX;
                if (query.length() + text.length() > max) return;
                query.append(text);
                styleQuery();
            }
            @Override public void onDelete() {
                int n = query.length();
                if (n == 0) return;
                int cut = n >= 2 && Character.isSurrogatePair(query.charAt(n - 2), query.charAt(n - 1)) ? 2 : 1;
                query.setLength(n - cut);
                styleQuery();
            }
            @Override public void onEnter() { go(); }
            @Override public void onHandSide(int mode) {}
            @Override public void onCursorMove(int steps, boolean select) {}
            @Override public void onDeleteWord() {}
            @Override public void onHide() {}
            @Override public void onResize(int heightPct, int bottomDp) {}
            @Override public void onPasteSuggestion() {}
            @Override public void onGlide(String best, java.util.List<String> alternatives) { onText(best); }
            @Override public void onSwitchKeyboard() {}
            @Override public void onSwitchLanguage() {}
            @Override public void onSuggestion(String word) {}
            @Override public void onToolbar(int tool) {}
        });
        addView(miniKb, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---------------------------------------------------------------- fila inferior
        bottom = new LinearLayout(c);
        bottom.setOrientation(HORIZONTAL);
        bottom.setPadding(px(6), px(4), px(6), px(6));
        abc = new AbcKey(c);
        bottom.addView(abc, new LinearLayout.LayoutParams(px(52), px(48)));
        cancelKey = key(c, R.string.ai_cancel, new OnClickListener() {
            @Override public void onClick(View v) { tap(v); cancel(); }
        });
        replaceKey = key(c, R.string.ai_replace, new OnClickListener() {
            @Override public void onClick(View v) {
                tap(v);
                if (host != null && result != null) { host.replaceText(result); host.closeTray(); }
            }
        });
        insertKey = key(c, R.string.ai_insert, new OnClickListener() {
            @Override public void onClick(View v) {
                tap(v);
                if (host != null && result != null) { host.insertText(result); host.closeTray(); }
            }
        });
        copyKey = key(c, R.string.ai_copy, new OnClickListener() {
            @Override public void onClick(View v) {
                tap(v);
                if (host != null && result != null) {
                    host.copyText(result);
                    Toast.makeText(getContext(), R.string.ai_copied, Toast.LENGTH_SHORT).show();
                }
            }
        });
        retryKey = key(c, R.string.ai_retry, new OnClickListener() {
            @Override public void onClick(View v) { tap(v); retry(); }
        });
        for (TextView k : keys) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(48), 1f);
            p.leftMargin = px(4);
            bottom.addView(k, p);
        }
        addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));

        buildTones();
        applyTheme(theme);
        enter(State.IDLE);
        attachRunner();                 // si el runner compartido tenia algo en vuelo, el panel lo retoma
    }

    // ------------------------------------------------------------------ API para el servicio

    void setHost(Host h) { host = h; }

    /** Idioma del teclado ("es", "en" o "de"): define el idioma de los mensajes y del teclado reducido. */
    void setLanguage(String l) {
        lang = "en".equals(l) ? "en" : "de".equals(l) ? "de" : "es";
        miniKb.setLanguage("de".equals(lang) ? "en" : lang);
    }

    /** Vibracion y sonido de las teclas (tambien del teclado reducido). */
    void setFeedback(boolean vibrate, boolean sound) {
        this.vibrate = vibrate;
        this.sound = sound;
        miniKb.applyPrefs(false, vibrate, false, true, theme.id, false, sound, 0.8f);
    }

    /** El campo actual admite el asistente (false en direcciones, correos, contrasenas). */
    void setFieldAllowed(boolean allowed) {
        fieldAllowed = allowed;
        if (!allowed && (state == State.IDLE || state == State.TONES || state == State.TARGETS || state == State.PROMPT
                || state == State.NO_MODEL)) {
            enter(State.DISABLED_FIELD);
        }
    }

    /** Abre el panel: lee el texto del campo y muestra las acciones (o el aviso de sin modelo). */
    void show() {
        refreshBadge();
        if (attached && runner.isBusy() && state == State.RUNNING) return;      // sigue generando: se muestra tal cual
        if (unseen && (state == State.RESULT || state == State.ERROR)) {        // termino con el panel oculto
            unseen = false;
            return;
        }
        readSource();
        result = null;
        if (!fieldAllowed) enter(State.DISABLED_FIELD);
        else if (!runner.available()) enter(State.NO_MODEL);
        else enter(State.IDLE);
    }

    /**
     * Para la generacion en curso; el panel vuelve a las acciones (o muestra el resultado parcial). Un panel
     * ya desenganchado ({@link #detach}) no toca la generacion, que es del panel nuevo.
     */
    void cancel() {
        if (attached && runner.isBusy()) runner.cancel();
        else if (state == State.RUNNING) enter(State.IDLE);
    }

    /** Este panel sigue una generacion en curso. */
    boolean isBusy() { return attached && runner.isBusy(); }

    /**
     * Suelta el runner compartido sin cancelar nada: cuando la vista se rehace, la generacion en curso (o su
     * resultado, si termina mientras tanto) la recoge el panel siguiente. Se llama solo tambien al salir de la
     * ventana. Con runner propio no hace falta.
     */
    void detach() {
        if (!attached) return;
        attached = false;
        runner.detach(callback);
    }

    /** Libera el modelo y el hilo de trabajo (al destruir el servicio). */
    void release() { if (ownsRunner) runner.release(); }

    /**
     * Se engancha al runner. Si tenia una generacion en vuelo (o un final que nadie recibio), el panel pasa a
     * "escribiendo" con la peticion original y recibe lo acumulado, las piezas y el final.
     */
    private void attachRunner() {
        if (attached) return;
        attached = true;
        if (runner.hasPending()) {
            lastRequest = runner.request();
            source = lastRequest == null ? "" : lastRequest.source;
            result = null;
            userScrolled = false;
            output.setText("");
            phase.setText(runner.isRemote() ? R.string.ai_connecting : R.string.ai_loading_model);
            refreshBadge();
            enter(State.RUNNING);
        }
        runner.attach(callback);
        // Lo que llego antes de que nadie pudiera verlo se conserva hasta que el usuario abra el panel
        if (state == State.RESULT || state == State.ERROR) unseen = true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!ownsRunner && !attached && !runner.hasListener()) attachRunner();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!ownsRunner) detach();      // la vista se rehace: la generacion sigue y la retoma el panel nuevo
    }

    /** Descarga el modelo de memoria sin cerrar el panel (memoria baja, teclado oculto). */
    void unloadModel() { runner.unloadModel(); }

    State state() { return state; }

    /** Colores del tema del teclado. */
    void applyTheme(Themes.Theme t) {
        theme = t == null ? Themes.DEFAULT : t;
        setBackgroundColor(theme.bg);
        title.setTextColor(theme.hint);
        sourceLine.setTextColor(theme.hint);
        back.setColor(theme.icon);
        back.setBackground(ripple(theme.specialBg, theme.pressed, px(20)));
        promptBack.setColor(theme.icon);
        promptBack.setBackground(ripple(theme.specialBg, theme.pressed, px(20)));
        badge.setTextColor(theme.glyph);
        badge.setBackground(ripple(theme.specialBg, theme.pressed, px(14)));
        noModelBody.setTextColor(theme.glyph);
        noModelNote.setTextColor(theme.hint);
        noModelButton.setTextColor(theme.onAccent);
        noModelButton.setBackground(ripple(theme.accent, theme.pressed, px(12)));
        disabledView.setTextColor(theme.hint);
        for (Card card : cards) card.restyle();
        for (int i = 0; i < toneChips.size(); i++) styleChip(toneChips.get(i));
        phase.setTextColor(theme.hint);
        output.setTextColor(theme.glyph);
        errorText.setTextColor(theme.glyph);
        promptIcon.setTextColor(theme.glyph);
        promptClear.setTextColor(theme.hint);
        styleQuery();
        styleContextChip();
        gridAbc.invalidate();
        abc.invalidate();
        for (TextView k : keys) styleKey(k, k == replaceKey);
        miniKb.applyPrefs(false, vibrate, false, true, theme.id, false, sound, 0.8f);
    }

    // ------------------------------------------------------------------ estados

    private void enter(State s) {
        state = s;
        boolean prompt = s == State.PROMPT;
        header.setVisibility(prompt ? GONE : VISIBLE);
        sourceLine.setVisibility(prompt || s == State.RUNNING || s == State.RESULT ? GONE : VISIBLE);
        content.setVisibility(prompt ? GONE : VISIBLE);
        promptBar.setVisibility(prompt ? VISIBLE : GONE);
        contextRow.setVisibility(prompt && promptKind == PromptKind.COMPOSE && !source.trim().isEmpty() ? VISIBLE : GONE);
        miniKb.setVisibility(prompt ? VISIBLE : GONE);
        noModelScroll.setVisibility(s == State.NO_MODEL ? VISIBLE : GONE);
        disabledView.setVisibility(s == State.DISABLED_FIELD ? VISIBLE : GONE);
        gridScroll.setVisibility(s == State.IDLE ? VISIBLE : GONE);
        tonesScroll.setVisibility(s == State.TONES || s == State.TARGETS ? VISIBLE : GONE);
        runView.setVisibility(s == State.RUNNING || s == State.RESULT ? VISIBLE : GONE);
        errorView.setVisibility(s == State.ERROR ? VISIBLE : GONE);
        bottom.setVisibility(s == State.IDLE || prompt ? GONE : VISIBLE);
        cancelKey.setVisibility(s == State.RUNNING ? VISIBLE : GONE);
        boolean res = s == State.RESULT;
        boolean canReplace = res && !(source.trim().isEmpty());
        replaceKey.setVisibility(canReplace ? VISIBLE : GONE);
        insertKey.setVisibility(res ? VISIBLE : GONE);
        copyKey.setVisibility(res ? VISIBLE : GONE);
        retryKey.setVisibility(res || s == State.ERROR ? VISIBLE : GONE);
        back.setBack(s == State.TONES || s == State.TARGETS);
        title.setText(s == State.TONES ? R.string.ai_action_style
                : s == State.TARGETS ? R.string.ai2_action_translate : R.string.ai_title);
        if (s == State.IDLE) {
            // Sin texto en el campo solo tiene sentido el Redactor
            boolean hasText = !source.trim().isEmpty();
            for (Card card : cards) card.setEnabled(card.action == AiRunner.Action.COMPOSE || hasText);
        }
        if (s == State.PROMPT) {
            miniKb.resetToLetters();
            styleQuery();
            styleContextChip();
        }
    }

    private void onBack() {
        switch (state) {
            case TONES:
            case TARGETS:
                enter(State.IDLE);
                break;
            case PROMPT:
                enter(promptKind == PromptKind.TONE ? State.TONES
                        : promptKind == PromptKind.TARGET ? State.TARGETS : State.IDLE);
                break;
            case RUNNING:
                runner.cancel();
                if (host != null) host.closeTray();
                break;
            default:
                if (host != null) host.closeTray();
        }
    }

    private void readSource() {
        CharSequence sel = host == null ? null : host.selectedText();
        CharSequence cur = host == null ? null : host.currentText();
        hadSelection = sel != null && sel.length() > 0;
        source = hadSelection ? sel.toString() : cur == null ? "" : cur.toString();
        int n = source.length();
        if (n == 0) sourceLine.setText(R.string.ai_source_empty);
        else if (n > runner.maxChars()) sourceLine.setText(getContext().getString(R.string.ai_source_truncated, runner.maxChars()));
        else sourceLine.setText(getContext().getString(hadSelection ? R.string.ai_source_selection : R.string.ai_source_field, n));
    }

    private void refreshBadge() { badge.setText(runner.engineLabel()); }

    /** Abre el submenu de tonos de "Estilo de escritura". */
    void showTones() {
        buildTones();
        enter(State.TONES);
    }

    /** Abre el submenu de idiomas destino de "Traducir". */
    void showTargets() {
        buildTargets();
        enter(State.TARGETS);
    }

    /** Abre la entrada de texto con el teclado reducido: Redactor (compose) o tono personalizado. */
    void showPrompt(boolean compose) { openPrompt(compose ? PromptKind.COMPOSE : PromptKind.TONE); }

    /** Abre la entrada de texto para escribir otro idioma destino de Traducir. */
    void showTargetPrompt() { openPrompt(PromptKind.TARGET); }

    private void openPrompt(PromptKind kind) {
        promptKind = kind;
        query.setLength(0);
        useContext = true;
        promptIcon.setText(kind == PromptKind.TONE ? "✎" : kind == PromptKind.TARGET ? "⇄" : "✧");
        enter(State.PROMPT);
    }

    /** Intro en el teclado reducido: lanza el tono personalizado, la traduccion a otro idioma o el Redactor. */
    private void go() {
        String q = query.toString().trim();
        if (q.isEmpty()) return;
        if (promptKind == PromptKind.TONE) {
            Prefs.get(getContext()).edit().putString(AiModels.LAST_TONE, q).apply();
            run(AiRunner.Action.STYLE_CUSTOM, q, null);
        } else if (promptKind == PromptKind.TARGET) {
            Prefs.get(getContext()).edit().putString(LAST_TARGET, q).apply();
            translate(q);
        } else {
            run(AiRunner.Action.COMPOSE, null, q);
        }
    }

    /**
     * Lanza una accion directamente (lee antes el texto del campo). {@code tone} solo para
     * STYLE_CUSTOM y {@code prompt} solo para COMPOSE; el resto admite null.
     */
    void run(AiRunner.Action a, String tone, String prompt) { run(a, tone, prompt, null); }

    /** Traduce el texto del campo a {@code target}: "es", "en", "de" u otro idioma tal como lo escribio el usuario. */
    void translate(String target) { run(AiRunner.Action.TRANSLATE, null, null, target); }

    private void run(AiRunner.Action a, String tone, String prompt, String target) {
        readSource();
        if (!runner.available()) { enter(State.NO_MODEL); return; }
        AiRunner.Request r = new AiRunner.Request(a, source);
        r.tone = tone;
        r.prompt = prompt;
        r.target = target;
        r.lang = lang;
        r.useContext = useContext;
        launch(r);
    }

    private void retry() {
        if (lastRequest == null) { enter(State.IDLE); return; }
        launch(lastRequest.copyForRetry());
    }

    private void launch(AiRunner.Request r) {
        lastRequest = r;
        result = null;
        userScrolled = false;
        output.setText("");
        phase.setText(runner.isRemote() ? R.string.ai_connecting : R.string.ai_loading_model);
        refreshBadge();
        enter(State.RUNNING);
        runner.run(r, callback);
    }

    private final AiRunner.Callback callback = new AiRunner.Callback() {
        @Override public void onPhase(CharSequence label) {
            if (state == State.RUNNING) phase.setText(label);
        }
        @Override public void onPiece(String soFar, int tokens, long elapsedMs) {
            if (state != State.RUNNING) return;
            output.setText(soFar);
            int words = soFar.trim().isEmpty() ? 0 : soFar.trim().split("\\s+").length;
            double rate = elapsedMs > 0 ? tokens * 1000.0 / elapsedMs : 0;
            phase.setText(getContext().getString(R.string.ai_writing, words, rate));
            scrollToEnd();
        }
        @Override public void onDone(String text, boolean weak) {
            result = text;
            output.setText(text);
            String label = actionName(lastRequest) + " · " + runner.engineLabel();
            if (weak) label = getContext().getString(R.string.ai_weak_result);
            phase.setText(label);
            unseen = getVisibility() != VISIBLE;
            enter(State.RESULT);
            outScroll.scrollTo(0, 0);
        }
        @Override public void onCancelled(String partialText) {
            if (partialText != null && partialText.trim().length() >= 20) {
                result = partialText;
                output.setText(partialText);
                phase.setText(R.string.ai_partial);
                unseen = getVisibility() != VISIBLE;
                enter(State.RESULT);
            } else {
                unseen = false;
                enter(State.IDLE);
            }
        }
        @Override public void onError(CharSequence message) {
            errorText.setText(message);
            unseen = getVisibility() != VISIBLE;
            enter(State.ERROR);
        }
    };

    private void scrollToEnd() {
        if (userScrolled) return;
        outScroll.post(new Runnable() {
            @Override public void run() { outScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private String actionName(AiRunner.Request r) {
        if (r == null) return "";
        switch (r.action) {
            case FIX: return getContext().getString(R.string.ai_action_fix);
            case STYLE_ORIGINAL: return getContext().getString(R.string.ai_tone_original);
            case STYLE_FORMAL: return getContext().getString(R.string.ai_tone_formal);
            case STYLE_RESPECTFUL: return getContext().getString(R.string.ai_tone_respectful);
            case STYLE_FRIENDLY: return getContext().getString(R.string.ai_tone_friendly);
            case STYLE_CUSTOM: return r.tone == null ? getContext().getString(R.string.ai_tone_custom) : r.tone;
            case SUMMARY: return getContext().getString(R.string.ai_action_summary);
            case BULLETS: return getContext().getString(R.string.ai_action_bullets);
            case TABLE: return getContext().getString(R.string.ai_action_table);
            case TRANSLATE: return getContext().getString(R.string.ai2_action_translate) + " · " + targetLabel(r.target);
            default: return getContext().getString(R.string.ai_action_compose);
        }
    }

    /** Etiqueta del idioma destino: el nombre propio para es/en/de, o lo que escribio el usuario. */
    private String targetLabel(String target) {
        String code = AiRunner.targetCode(target);
        if ("es".equals(code)) return getContext().getString(R.string.ai2_target_es);
        if ("en".equals(code)) return getContext().getString(R.string.ai2_target_en);
        if ("de".equals(code)) return getContext().getString(R.string.ai2_target_de);
        return target == null ? "" : target.trim();
    }

    // ------------------------------------------------------------------ tonos

    private void buildTones() {
        tones.removeAllViews();
        toneChips.clear();
        addTone(R.string.ai_tone_original, AiRunner.Action.STYLE_ORIGINAL, null);
        addTone(R.string.ai_tone_formal, AiRunner.Action.STYLE_FORMAL, null);
        addTone(R.string.ai_tone_respectful, AiRunner.Action.STYLE_RESPECTFUL, null);
        addTone(R.string.ai_tone_friendly, AiRunner.Action.STYLE_FRIENDLY, null);
        String last = Prefs.get(getContext()).getString(AiModels.LAST_TONE, null);
        if (last != null && !last.trim().isEmpty()) addTone(0, AiRunner.Action.STYLE_CUSTOM, last.trim());
        addTone(R.string.ai_tone_custom, null, null);
    }

    private void addTone(int labelRes, final AiRunner.Action action, final String customTone) {
        addChip(labelRes != 0 ? getContext().getString(labelRes) : "✎ " + customTone, new OnClickListener() {
            @Override public void onClick(View v) {
                tap(v);
                if (action == null) openPrompt(PromptKind.TONE);
                else run(action, customTone, null);
            }
        });
    }

    // ------------------------------------------------------------------ idiomas destino (Traducir)

    /** Mismo patron que los tonos: los tres idiomas de la interfaz, el ultimo escrito a mano y "Otro…". */
    private void buildTargets() {
        tones.removeAllViews();
        toneChips.clear();
        addTarget(R.string.ai2_target_es, "es");
        addTarget(R.string.ai2_target_en, "en");
        addTarget(R.string.ai2_target_de, "de");
        String last = Prefs.get(getContext()).getString(LAST_TARGET, null);
        if (last != null && !last.trim().isEmpty() && AiRunner.targetCode(last) == null) addTarget(0, last.trim());
        addTarget(R.string.ai2_target_other, null);
    }

    /** {@code target} null abre la entrada de texto; sin {@code labelRes} es un idioma escrito por el usuario. */
    private void addTarget(int labelRes, final String target) {
        addChip(labelRes != 0 ? getContext().getString(labelRes) : "⇄ " + target, new OnClickListener() {
            @Override public void onClick(View v) {
                tap(v);
                if (target == null) openPrompt(PromptKind.TARGET);
                else translate(target);
            }
        });
    }

    /** Fila de la lista de tonos / idiomas. */
    private void addChip(CharSequence label, OnClickListener l) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(medium);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(px(14), 0, px(14), 0);
        t.setText(label);
        t.setOnClickListener(l);
        styleChip(t);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, px(34));
        p.topMargin = px(2);
        tones.addView(t, p);
        toneChips.add(t);
    }

    private void styleChip(TextView t) {
        t.setTextColor(theme.glyph);
        t.setBackground(ripple(theme.keyBg, theme.pressed, theme.radiusDp * dp));
    }

    // ------------------------------------------------------------------ tarjetas de accion

    /** Tarjeta de accion: glifo y etiqueta sobre fondo de tecla. */
    private final class Card extends LinearLayout {
        final AiRunner.Action action;   // null = Estilo (abre el submenu de tonos)
        private final TextView glyph, label;
        private boolean on = true;

        Card(Context c, String icon, int labelRes, AiRunner.Action action) {
            super(c);
            this.action = action;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(10), 0, px(8), 0);
            glyph = new TextView(c);
            glyph.setText(icon);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            glyph.setGravity(Gravity.CENTER);
            glyph.setIncludeFontPadding(false);
            addView(glyph, new LinearLayout.LayoutParams(px(22), WRAP));
            label = new TextView(c);
            label.setText(labelRes);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTypeface(medium);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(px(6), 0, 0, 0);
            addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (!on) return;
                    tap(v);
                    if (Card.this.action == null) showTones();
                    else if (Card.this.action == AiRunner.Action.COMPOSE) openPrompt(PromptKind.COMPOSE);
                    else if (Card.this.action == AiRunner.Action.TRANSLATE) showTargets();
                    else run(Card.this.action, null, null);
                }
            });
        }

        @Override public void setEnabled(boolean enabled) {
            on = enabled;
            setAlpha(enabled ? 1f : 0.4f);
            setClickable(enabled);
        }

        void restyle() {
            glyph.setTextColor(theme.glyph);
            label.setTextColor(theme.glyph);
            setBackground(ripple(theme.keyBg, theme.pressed, px(10)));
        }
    }

    private Card card(Context c, String icon, int labelRes, AiRunner.Action action) {
        Card card = new Card(c, icon, labelRes, action);
        cards.add(card);
        return card;
    }

    private LinearLayout.LayoutParams cell(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(48), weight);
        p.setMargins(px(3), px(3), px(3), px(3));
        return p;
    }

    // ------------------------------------------------------------------ entrada

    private void styleQuery() {
        if (query.length() == 0) {
            queryView.setText(promptKind == PromptKind.TONE ? R.string.ai_tone_custom_hint
                    : promptKind == PromptKind.TARGET ? R.string.ai2_target_hint : R.string.ai_compose_hint);
            queryView.setTextColor(theme.hint);
        } else {
            queryView.setText(query + "|");
            queryView.setTextColor(theme.glyph);
        }
    }

    private void styleContextChip() {
        contextChip.setText(useContext ? R.string.ai_compose_uses_context : R.string.ai_compose_no_context);
        contextChip.setTextColor(useContext ? theme.onAccent : theme.glyph);
        contextChip.setBackground(ripple(useContext ? theme.accent : theme.specialBg, theme.pressed, px(12)));
    }

    // ------------------------------------------------------------------ piezas

    private TextView key(Context c, int labelRes, OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(labelRes);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(medium);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(px(4), 0, px(4), 0);
        t.setOnClickListener(l);
        keys.add(t);
        return t;
    }

    private void styleKey(TextView k, boolean primary) {
        k.setTextColor(primary ? theme.onAccent : theme.glyph);
        k.setBackground(ripple(primary ? theme.accent : theme.specialBg, theme.pressed, theme.radiusDp * dp));
    }

    private void tap(View v) {
        if (vibrate) Haptics.tap(v);               // respeta el interruptor y el nivel de vibracion
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(VERTICAL);
        return l;
    }

    private static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(HORIZONTAL);
        return l;
    }

    private static ScrollView scroll(Context c) {
        ScrollView s = new ScrollView(c);
        s.setVerticalScrollBarEnabled(false);
        s.setFillViewport(true);
        return s;
    }

    private RippleDrawable ripple(int base, int pressedColor, float radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(base);
        content.setCornerRadius(radius);
        if (base != 0 && theme.keyStroke != 0) content.setStroke(Math.max(1, px(1)), theme.keyStroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf((pressedColor & 0x00FFFFFF) | 0x66000000), content, mask);
    }

    private int px(float dps) { return Math.round(dps * dp); }

    /** Boton redondo de la cabecera: cierra (x) o vuelve atras (flecha). */
    private final class HeaderButton extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean isBack;

        HeaderButton(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * dp);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        void setColor(int color) { stroke.setColor(color); invalidate(); }

        void setBack(boolean b) {
            if (isBack == b) return;
            isBack = b;
            setContentDescription(b ? "back" : "close");
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, k = 5.5f * dp;
            if (isBack) {
                c.drawLine(cx - k, cy, cx + k, cy, stroke);
                c.drawLine(cx - k, cy, cx - k + 5 * dp, cy - 5 * dp, stroke);
                c.drawLine(cx - k, cy, cx - k + 5 * dp, cy + 5 * dp, stroke);
            } else {
                c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
                c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke);
            }
        }
    }

    /** Tecla "abc" dibujada en Aurebesh, como en el panel de emojis: vuelve a las teclas. */
    private final class AbcKey extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint latin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private boolean down;

        AbcKey(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            latin.setTextAlign(Paint.Align.CENTER);
            setContentDescription("abc");
        }

        @Override protected void onDraw(Canvas c) {
            float r = theme.radiusDp * dp;
            rect.set(0, 0, getWidth(), getHeight());
            fill.setColor(down ? theme.pressed : theme.specialBg);
            c.drawRoundRect(rect, r, r, fill);
            if (theme.keyStroke != 0) {
                stroke.setColor(theme.keyStroke);
                stroke.setStrokeWidth(dp);
                rect.inset(dp / 2f, dp / 2f);
                c.drawRoundRect(rect, r, r, stroke);
            }
            glyph.setColor(theme.glyph);
            latin.setColor(theme.glyph);
            float h = 10 * dp, cx = getWidth() / 2f, cy = getHeight() / 2f;
            List<Glyphs.Token> tokens = Glyphs.tokenize("abc", false);
            float total = 0;
            for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, latin);
            float x = cx - total / 2f;
            for (Glyphs.Token t : tokens) x += Glyphs.drawToken(c, t, x, cy - h / 2f, h, glyph, latin);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    tap(this);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
                    down = false;
                    invalidate();
                    if (inside && host != null) host.closeTray();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }

    /** Texto de ayuda para pruebas y capturas: estado actual en minusculas. */
    @Override public String toString() { return "AiTray[" + state.name().toLowerCase(Locale.ROOT) + "]"; }
}
