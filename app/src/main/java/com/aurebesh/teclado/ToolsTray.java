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
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuadricula "..." con todas las herramientas de la cinta (como la de Samsung Keyboard): tocar una la ejecuta;
 * en modo "Editar" se eligen y ordenan las que van en la cinta. Ocupa el sitio de las teclas, como las demas bandejas.
 *
 * Cuatro columnas, cada celda con el icono de {@link ToolIcons} y su nombre. Cabecera con titulo, "Editar"/"Listo"
 * y el mismo boton de cerrar que el portapapeles. En modo edicion tocar una celda la anade a la cinta o la quita
 * (las elegidas llevan un punto con su posicion), las flechas ‹ › de cada celda elegida la mueven en el orden, hay
 * como mucho {@link #MAX_BAR} en la cinta y "Listo" entrega el orden con {@link Host#onToolsOrder}. Las herramientas
 * que no tienen sentido ahora (available[i] = false) se ven atenuadas y no se ejecutan, aunque si se pueden elegir
 * para la cinta. La herramienta "..." (TOOL_MORE) nunca aparece: es la propia cuadricula.
 */
final class ToolsTray extends LinearLayout {
    interface Host {
        void onToolsPick(int tool);            // ejecutar una herramienta (ids TOOL_*)
        void onToolsOrder(int[] barItems);     // nuevo orden/eleccion de la cinta (se guarda en Prefs.TOOLBAR_ITEMS)
        void onToolsClose();
    }

    /** Iconos que caben en la cinta ademas del "...". */
    static final int MAX_BAR = 6;
    private static final int COLUMNS = 4;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final float DIM = 0.38f;

    private final Host host;
    private final float dp;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Themes.Theme theme = Themes.DEFAULT;
    private boolean vibrate = true, sound = true;

    private final TextView title;
    private final TextView edit;
    private final KeyboardTray.CloseButton close;
    private final TextView hint;
    private final LinearLayout grid;

    // Contenido mostrado (para reconstruirlo al cambiar de tema o de modo)
    private int[] allTools = new int[0];
    private boolean[] available = new boolean[0];
    private final List<Integer> bar = new ArrayList<>();      // orden de la cinta; en edicion, el provisional
    private final List<Cell> cells = new ArrayList<>();
    private boolean editing = false;
    private boolean shown = false;
    private String hintText;                                  // aviso del modo edicion (tope alcanzado), o null

    ToolsTray(Context c, Host host) {
        super(c);
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(px(44));
        header.setPadding(px(16), 0, px(6), 0);

        title = new TextView(c);
        title.setText(R.string.tools_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setAllCaps(true);
        title.setLetterSpacing(0.08f);
        title.setTypeface(medium);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        edit = new TextView(c);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        edit.setTypeface(medium);
        edit.setText(R.string.tools_edit);
        edit.setPadding(px(12), px(8), px(12), px(8));
        edit.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                if (editing) done();
                else setEditing(true);
            }
        });
        header.addView(edit, new LinearLayout.LayoutParams(WRAP, WRAP));

        close = new KeyboardTray.CloseButton(c);
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                editing = false;                            // cerrar en edicion descarta lo no confirmado con "Listo"
                if (ToolsTray.this.host != null) ToolsTray.this.host.onToolsClose();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(px(44), px(40)));
        addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        hint = new TextView(c);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(2);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setPadding(px(16), 0, px(16), px(4));
        hint.setVisibility(GONE);
        addView(hint, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        grid.setPadding(px(6), px(2), px(6), px(10));
        scroll.addView(grid, new ViewGroup.LayoutParams(MATCH, WRAP));
        addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        applyTheme(theme);
    }

    /** Muestra la cuadricula: barItems es la cinta actual, allTools el catalogo y available que herramientas tienen sentido ahora. */
    void show(int[] barItems, int[] allTools, boolean[] available) {
        List<Integer> tools = new ArrayList<>();
        List<Boolean> avail = new ArrayList<>();
        if (allTools != null) {
            for (int i = 0; i < allTools.length; i++) {
                if (allTools[i] == AurebeshKeyboardView.TOOL_MORE) continue;
                tools.add(allTools[i]);
                avail.add(available == null || i >= available.length || available[i]);
            }
        }
        this.allTools = new int[tools.size()];
        this.available = new boolean[tools.size()];
        for (int i = 0; i < tools.size(); i++) {
            this.allTools[i] = tools.get(i);
            this.available[i] = avail.get(i);
        }
        bar.clear();
        if (barItems != null) {                             // se muestra tal cual (aunque traiga mas de MAX_BAR); el tope rige al anadir
            for (int id : barItems) {
                if (id == AurebeshKeyboardView.TOOL_MORE || bar.contains(id) || indexOf(id) < 0) continue;
                bar.add(id);
            }
        }
        editing = false;
        hintText = null;
        shown = true;
        rebuild();
    }

    /** Colores del tema del teclado; si ya hay contenido, lo reconstruye con ellos. */
    void applyTheme(Themes.Theme t) {
        theme = t == null ? Themes.DEFAULT : t;
        setBackgroundColor(theme.bg);
        title.setTextColor(theme.hint);
        hint.setTextColor(theme.hint);
        edit.setTextColor(theme.icon);
        edit.setBackground(ripple(0x00000000, theme.pressed, px(8)));
        close.setColor(theme.icon);
        close.setBackground(ripple(theme.specialBg, theme.pressed, px(20)));
        if (shown) rebuild();
    }

    /** Las bandejas responden con {@link Haptics}, que lee las preferencias en cada toque; aqui solo se recuerdan. */
    void setFeedback(boolean vibrate, boolean sound) {
        this.vibrate = vibrate;
        this.sound = sound;
    }

    // ------------------------------------------------------------------ modo edicion

    private void setEditing(boolean on) {
        editing = on;
        hintText = null;
        rebuild();
    }

    /** "Listo": entrega el orden provisional y vuelve al modo normal. */
    private void done() {
        editing = false;
        hintText = null;
        if (host != null) host.onToolsOrder(barArray());
        rebuild();
    }

    private int[] barArray() {
        int[] out = new int[bar.size()];
        for (int i = 0; i < out.length; i++) out[i] = bar.get(i);
        return out;
    }

    /** Toque en una celda: en edicion, entra o sale de la cinta; si no, ejecuta la herramienta (si esta disponible). */
    private void cellTapped(Cell cell) {
        if (editing) {
            int at = bar.indexOf(cell.tool);
            if (at >= 0) {
                bar.remove(at);
                hintText = null;
            } else if (bar.size() >= MAX_BAR) {
                hintText = getContext().getString(R.string.tools_limit, MAX_BAR);
                Haptics.tick(cell);
            } else {
                bar.add(cell.tool);
                hintText = null;
            }
            refreshCells();
            return;
        }
        if (!cell.available) {
            Haptics.tick(cell);
            return;
        }
        Haptics.tap(cell);
        if (host != null) host.onToolsPick(cell.tool);
    }

    /** Flecha de una celda elegida: la mueve un puesto antes (left) o despues en la cinta. */
    private void move(Cell cell, boolean left) {
        int at = bar.indexOf(cell.tool);
        int to = left ? at - 1 : at + 1;
        if (at < 0 || to < 0 || to >= bar.size()) return;
        Haptics.tick(cell);
        bar.set(at, bar.get(to));
        bar.set(to, cell.tool);
        refreshCells();
    }

    private int indexOf(int tool) {
        for (int i = 0; i < allTools.length; i++) if (allTools[i] == tool) return i;
        return -1;
    }

    // ------------------------------------------------------------------ construccion

    private void rebuild() {
        edit.setText(editing ? R.string.tools_done : R.string.tools_edit);
        grid.removeAllViews();
        cells.clear();
        LinearLayout row = null;
        for (int i = 0; i < allTools.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(MATCH, WRAP);
                rp.topMargin = px(6);
                grid.addView(row, rp);
            }
            Cell cell = new Cell(getContext(), allTools[i], available[i]);
            cells.add(cell);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            cp.leftMargin = px(3);
            cp.rightMargin = px(3);
            row.addView(cell, cp);
        }
        // Huecos para que la ultima fila conserve el ancho de celda
        if (row != null) {
            for (int i = allTools.length % COLUMNS; i != 0 && i < COLUMNS; i++) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
                cp.leftMargin = px(3);
                cp.rightMargin = px(3);
                row.addView(new View(getContext()), cp);
            }
        }
        refreshCells();
    }

    /** Pone al dia marcas, flechas y el aviso del modo edicion sin rehacer las vistas. */
    private void refreshCells() {
        for (Cell c : cells) c.refresh();
        if (editing) {
            hint.setText(hintText != null ? hintText : getContext().getString(R.string.tools_count, bar.size(), MAX_BAR)
                    + " · " + getContext().getString(R.string.tools_edit_hint));
            hint.setVisibility(VISIBLE);
        } else {
            hint.setVisibility(GONE);
        }
    }

    /** Nombre visible de cada herramienta (recurso), 0 si no se conoce. */
    static int labelOf(int tool) {
        switch (tool) {
            case AurebeshKeyboardView.TOOL_EMOJI: return R.string.tools_label_emoji;
            case AurebeshKeyboardView.TOOL_CLIPBOARD: return R.string.tools_label_clipboard;
            case AurebeshKeyboardView.TOOL_HAND: return R.string.tools_label_hand;
            case AurebeshKeyboardView.TOOL_EDIT: return R.string.tools_label_edit;
            case AurebeshKeyboardView.TOOL_TRANSLATE: return R.string.tools_label_translate;
            case AurebeshKeyboardView.TOOL_SPLIT: return R.string.tools_label_split;
            case AurebeshKeyboardView.TOOL_STRIP: return R.string.immersive_title;   // "Todo en Aurebesh" (antes barra de traduccion)
            case AurebeshKeyboardView.TOOL_LANG: return R.string.tools_label_lang;
            case AurebeshKeyboardView.TOOL_RESIZE: return R.string.tools_label_resize;
            case AurebeshKeyboardView.TOOL_HIDE: return R.string.tools_label_hide;
            case AurebeshKeyboardView.TOOL_SETTINGS: return R.string.tools_label_settings;
            case AurebeshKeyboardView.TOOL_AI: return R.string.tools_label_ai;
            default: return 0;
        }
    }

    /** Fondo redondeado (con borde si el tema lo lleva) y ondulacion de pulsacion. */
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

    // ------------------------------------------------------------------ para pruebas

    boolean isEditing() { return editing; }

    /** Orden provisional de la cinta (en edicion) o el mostrado. */
    int[] barItems() { return barArray(); }

    View editButton() { return edit; }

    /** Celda de una herramienta, o null si no esta en la cuadricula. */
    View cellOf(int tool) {
        for (Cell c : cells) if (c.tool == tool) return c;
        return null;
    }

    /** Flecha ‹ (left) o › de una celda, para moverla en la cinta. */
    View arrowOf(int tool, boolean left) {
        Cell c = (Cell) cellOf(tool);
        return c == null ? null : left ? c.prev : c.next;
    }

    // ------------------------------------------------------------------ celda

    /** Celda: [‹][icono][›] arriba y el nombre debajo; el punto numerado marca su puesto en la cinta. */
    private final class Cell extends LinearLayout {
        final int tool;
        final boolean available;
        private final IconView icon;
        private final TextView label;
        final TextView prev, next;

        Cell(Context c, int tool, boolean available) {
            super(c);
            this.tool = tool;
            this.available = available;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, px(8), 0, px(8));
            setClickable(true);
            int name = labelOf(tool);
            String text = name != 0 ? c.getString(name) : String.valueOf(tool);
            setContentDescription(text);

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            prev = arrow(c, "‹", true);
            icon = new IconView(c, tool);
            next = arrow(c, "›", false);
            top.addView(prev, new LinearLayout.LayoutParams(px(22), px(40)));
            top.addView(icon, new LinearLayout.LayoutParams(px(40), px(40)));
            top.addView(next, new LinearLayout.LayoutParams(px(22), px(40)));
            addView(top, new LinearLayout.LayoutParams(WRAP, WRAP));

            label = new TextView(c);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(px(4), px(2), px(4), 0);
            addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));

            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { cellTapped(Cell.this); }
            });
        }

        private TextView arrow(Context c, String glyph, final boolean left) {
            TextView t = new TextView(c);
            t.setText(glyph);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setContentDescription(c.getString(left ? R.string.tools_move_left : R.string.tools_move_right));
            t.setVisibility(INVISIBLE);
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { move(Cell.this, left); }
            });
            return t;
        }

        /** Colores, atenuado, marca y flechas segun el tema, la disponibilidad y el modo. */
        void refresh() {
            int at = bar.indexOf(tool);
            boolean marked = editing && at >= 0;
            setBackground(ripple(marked ? theme.specialBg : theme.keyBg, theme.pressed, theme.radiusDp * dp));
            label.setTextColor(theme.glyph);
            icon.setMark(marked ? at + 1 : 0);
            float alpha = available || editing ? 1f : DIM;
            icon.setAlpha(alpha);
            label.setAlpha(alpha);
            boolean arrows = marked && bar.size() > 1;
            prev.setVisibility(arrows ? VISIBLE : INVISIBLE);
            next.setVisibility(arrows ? VISIBLE : INVISIBLE);
            prev.setTextColor(theme.icon);
            next.setTextColor(theme.icon);
            prev.setAlpha(at > 0 ? 1f : DIM);
            next.setAlpha(at >= 0 && at < bar.size() - 1 ? 1f : DIM);
            prev.setBackground(ripple(0x00000000, theme.pressed, px(11)));
            next.setBackground(ripple(0x00000000, theme.pressed, px(11)));
        }
    }

    /** Icono de la herramienta ({@link ToolIcons}) con, en edicion, un punto de acento con su numero en la cinta. */
    private final class IconView extends View {
        private final int tool;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mark = 0;

        IconView(Context c, int tool) {
            super(c);
            this.tool = tool;
            num.setTextAlign(Paint.Align.CENTER);
            num.setTypeface(medium);
            num.setTextSize(9.5f * dp);
        }

        void setMark(int m) {
            mark = m;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setColor(theme.icon);
            ToolIcons.draw(c, tool, cx, cy, 24 * dp, paint);
            if (mark > 0) {
                float r = 7 * dp, x = getWidth() - r - dp, y = r + dp;
                dot.setColor(theme.accent);
                c.drawCircle(x, y, r, dot);
                num.setColor(theme.onAccent);
                c.drawText(String.valueOf(mark), x, y - (num.ascent() + num.descent()) / 2f, num);
            }
        }
    }
}
