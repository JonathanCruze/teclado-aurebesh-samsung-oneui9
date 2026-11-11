package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel de edicion de texto (como el de Samsung Keyboard): cruceta de flechas con repeticion, Seleccionar (las
 * flechas extienden la seleccion), Seleccionar todo, Cortar, Copiar, Pegar, Deshacer, Rehacer, Inicio y Fin.
 *
 * Cabecera con el titulo (o "N caracteres seleccionados" cuando hay seleccion, via {@link #setSelection}) y el
 * boton de cerrar del portapapeles. A la izquierda la cruceta ◀ ▶ ▲ ▼ con "Seleccionar" en el centro: mantener una
 * flecha repite tras 420 ms cada 60 ms (como la tecla de borrar). "Seleccionar" conmuta: resaltado, las flechas e
 * Inicio/Fin van con select=true; cortar, copiar o pegar lo apagan. A la derecha, las acciones en dos columnas.
 * Cada toque responde con {@link Haptics#tap}; la repeticion, con {@link Haptics#tick}.
 */
final class EditTray extends LinearLayout {
    interface Host {
        /** Flecha: keyCode es KEYCODE_DPAD_LEFT/RIGHT/UP/DOWN; select = con mayusculas (extiende la seleccion). */
        void onEditMove(int keyCode, boolean select);
        /** Accion del menu contextual: android.R.id.selectAll / cut / copy / paste / undo / redo. */
        void onEditAction(int actionId);
        /** Inicio (end=false) o fin (end=true) del texto. */
        void onEditHome(boolean end, boolean select);
        void onEditClose();
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final long REPEAT_START_MS = 420, REPEAT_MS = 60;
    /** Ids propios de Inicio y Fin (no son acciones del menu contextual). */
    private static final int ACTION_HOME = -1, ACTION_END = -2;
    private static final float DIM = 0.5f;

    private final Host host;
    private final float dp;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Themes.Theme theme = Themes.DEFAULT;
    private boolean vibrate = true, sound = true;

    private final TextView title;
    private final KeyboardTray.CloseButton close;
    private final ArrowKey left, right, up, down;
    private final TextView select;
    private final List<TextView> actions = new ArrayList<>();
    private final TextView cut, copy;

    private boolean selecting = false;
    private int selection = -1;

    EditTray(Context c, Host host) {
        super(c);
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        // Cabecera
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(px(44));
        header.setPadding(px(16), 0, px(6), 0);
        title = new TextView(c);
        title.setText(R.string.edit_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setAllCaps(true);
        title.setLetterSpacing(0.08f);
        title.setTypeface(medium);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        close = new KeyboardTray.CloseButton(c);
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                if (EditTray.this.host != null) EditTray.this.host.onEditClose();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(px(44), px(40)));
        addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Cuerpo: cruceta a la izquierda, acciones a la derecha
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(HORIZONTAL);
        body.setPadding(px(6), 0, px(6), px(8));

        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(VERTICAL);
        up = new ArrowKey(c, KeyEvent.KEYCODE_DPAD_UP, c.getString(R.string.edit_up));
        left = new ArrowKey(c, KeyEvent.KEYCODE_DPAD_LEFT, c.getString(R.string.edit_left));
        right = new ArrowKey(c, KeyEvent.KEYCODE_DPAD_RIGHT, c.getString(R.string.edit_right));
        down = new ArrowKey(c, KeyEvent.KEYCODE_DPAD_DOWN, c.getString(R.string.edit_down));
        select = button(c, R.string.edit_select);
        select.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                selecting = !selecting;
                restyle();
            }
        });
        pad.addView(padRow(c, null, up, null), rowParams());
        pad.addView(padRow(c, left, select, right), rowParams());
        pad.addView(padRow(c, null, down, null), rowParams());
        body.addView(pad, new LinearLayout.LayoutParams(0, MATCH, 1f));

        LinearLayout acts = new LinearLayout(c);
        acts.setOrientation(VERTICAL);
        acts.setPadding(px(6), 0, 0, 0);
        TextView all = action(c, R.string.edit_select_all, android.R.id.selectAll);
        cut = action(c, R.string.edit_cut, android.R.id.cut);
        copy = action(c, R.string.edit_copy, android.R.id.copy);
        TextView paste = action(c, R.string.edit_paste, android.R.id.paste);
        TextView undo = action(c, R.string.edit_undo, android.R.id.undo);
        TextView redo = action(c, R.string.edit_redo, android.R.id.redo);
        TextView home = action(c, R.string.edit_home, ACTION_HOME);
        TextView end = action(c, R.string.edit_end, ACTION_END);
        acts.addView(pair(c, all, cut), rowParams());
        acts.addView(pair(c, copy, paste), rowParams());
        acts.addView(pair(c, undo, redo), rowParams());
        acts.addView(pair(c, home, end), rowParams());
        body.addView(acts, new LinearLayout.LayoutParams(0, MATCH, 1.25f));

        addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        applyTheme(theme);
    }

    /** Abre el panel: sin seleccion en marcha y con la cabecera del titulo (hasta que llegue setSelection). */
    void show() {
        selecting = false;
        selection = -1;
        restyle();
    }

    /** Colores del tema del teclado. */
    void applyTheme(Themes.Theme t) {
        theme = t == null ? Themes.DEFAULT : t;
        setBackgroundColor(theme.bg);
        title.setTextColor(theme.hint);
        close.setColor(theme.icon);
        close.setBackground(ripple(theme.specialBg, theme.pressed, px(20)));
        restyle();
    }

    /** Las bandejas responden con {@link Haptics}, que lee las preferencias en cada toque; aqui solo se recuerdan. */
    void setFeedback(boolean vibrate, boolean sound) {
        this.vibrate = vibrate;
        this.sound = sound;
    }

    /** Numero de caracteres seleccionados (para la cabecera), -1 si no se sabe. */
    void setSelection(int count) {
        selection = count;
        restyle();
    }

    // ------------------------------------------------------------------ acciones

    /** Flecha (una pulsacion o una repeticion): mueve el cursor, extendiendo la seleccion si "Seleccionar" esta activo. */
    private void arrow(int keyCode) {
        if (host != null) host.onEditMove(keyCode, selecting);
    }

    private void actionTapped(View v, int id) {
        Haptics.tap(v);
        if (host == null) return;
        if (id == ACTION_HOME || id == ACTION_END) {
            host.onEditHome(id == ACTION_END, selecting);
            return;
        }
        host.onEditAction(id);
        if (id == android.R.id.cut || id == android.R.id.copy || id == android.R.id.paste) {
            selecting = false;                              // la seleccion ya se uso
            restyle();
        }
    }

    // ------------------------------------------------------------------ estilo

    /** Cabecera, boton Seleccionar y colores de las teclas segun el tema, la seleccion y el modo. */
    private void restyle() {
        if (selection > 0) {
            title.setText(getResources().getQuantityString(R.plurals.edit_selected, selection, selection));
        } else {
            title.setText(R.string.edit_title);
        }
        if (selecting) {
            select.setBackground(ripple(theme.accent, theme.pressed, theme.radiusDp * dp));
            select.setTextColor(theme.onAccent);
        } else {
            select.setBackground(ripple(theme.specialBg, theme.pressed, theme.radiusDp * dp));
            select.setTextColor(theme.glyph);
        }
        for (TextView a : actions) {
            a.setBackground(ripple(theme.keyBg, theme.pressed, theme.radiusDp * dp));
            a.setTextColor(theme.glyph);
        }
        // Sin nada seleccionado, cortar y copiar no haran nada: se atenuan (siguen tocables por si el campo no avisa)
        float alpha = selection == 0 ? DIM : 1f;
        cut.setAlpha(alpha);
        copy.setAlpha(alpha);
        left.invalidate();
        right.invalidate();
        up.invalidate();
        down.invalidate();
    }

    private TextView button(Context c, int label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(medium);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(px(4), px(4), px(4), px(4));
        t.setClickable(true);
        t.setContentDescription(c.getString(label));
        return t;
    }

    private TextView action(Context c, int label, final int id) {
        TextView t = button(c, label);
        t.setTag(id);
        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { actionTapped(v, id); }
        });
        actions.add(t);
        return t;
    }

    /** Fila de la cruceta: tres huecos iguales; los nulos quedan vacios. */
    private LinearLayout padRow(Context c, View a, View b, View d) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.addView(a == null ? new View(c) : a, cellParams());
        row.addView(b == null ? new View(c) : b, cellParams());
        row.addView(d == null ? new View(c) : d, cellParams());
        return row;
    }

    private LinearLayout pair(Context c, View a, View b) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.addView(a, cellParams());
        row.addView(b, cellParams());
        return row;
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, 1f);
        p.leftMargin = px(3);
        p.rightMargin = px(3);
        return p;
    }

    /** Fila de teclas: 46 dp de minimo y crece a partes iguales si la bandeja es mas alta. */
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, px(46), 1f);
        p.topMargin = px(6);
        return p;
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

    boolean isSelecting() { return selecting; }

    View selectButton() { return select; }

    /** Flecha de la cruceta por KEYCODE_DPAD_*, o null. */
    View arrowKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return left;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return right;
            case KeyEvent.KEYCODE_DPAD_UP: return up;
            case KeyEvent.KEYCODE_DPAD_DOWN: return down;
            default: return null;
        }
    }

    /** Boton de una accion (android.R.id.cut, etc.; Inicio y Fin no tienen id publico), o null. */
    View actionButton(int actionId) {
        for (TextView a : actions) if (a.getTag() instanceof Integer && (Integer) a.getTag() == actionId) return a;
        return null;
    }

    // ------------------------------------------------------------------ flecha

    /** Flecha dibujada a mano con repeticion al mantener: una vez al bajar, luego cada 60 ms tras 420 ms. */
    private final class ArrowKey extends View {
        private final int keyCode;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean down = false;
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!down) return;
                Haptics.tick(ArrowKey.this);
                arrow(keyCode);
                handler.postDelayed(this, REPEAT_MS);
            }
        };

        ArrowKey(Context c, int keyCode, String description) {
            super(c);
            this.keyCode = keyCode;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
            setContentDescription(description);
        }

        @Override
        protected void onDraw(Canvas c) {
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
            // Punta de flecha (chevron) hacia su lado; con "Seleccionar" activo, del color de acento
            stroke.setColor(selecting ? theme.accent : theme.icon);
            stroke.setStrokeWidth(2.4f * dp);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, k = 5 * dp;
            path.reset();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    path.moveTo(cx + k * 0.6f, cy - k); path.lineTo(cx - k * 0.6f, cy); path.lineTo(cx + k * 0.6f, cy + k);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    path.moveTo(cx - k * 0.6f, cy - k); path.lineTo(cx + k * 0.6f, cy); path.lineTo(cx - k * 0.6f, cy + k);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    path.moveTo(cx - k, cy + k * 0.6f); path.lineTo(cx, cy - k * 0.6f); path.lineTo(cx + k, cy + k * 0.6f);
                    break;
                default:
                    path.moveTo(cx - k, cy - k * 0.6f); path.lineTo(cx, cy + k * 0.6f); path.lineTo(cx + k, cy - k * 0.6f);
                    break;
            }
            c.drawPath(path, stroke);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    Haptics.tap(this);
                    arrow(keyCode);
                    handler.removeCallbacks(repeat);
                    handler.postDelayed(repeat, REPEAT_START_MS);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    handler.removeCallbacks(repeat);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            down = false;
            handler.removeCallbacks(repeat);
        }
    }
}
