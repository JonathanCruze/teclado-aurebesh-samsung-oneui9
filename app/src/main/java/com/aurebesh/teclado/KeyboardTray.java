package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Bandeja que sustituye a las teclas dentro de la ventana del teclado: el historial del
 * portapapeles (textos e imagenes). Tocar pega; bajo un texto con telefono, correo o enlace
 * aparecen chips para pegar solo esa parte; la x borra y "Borrar todo" vacia. Cada toque responde con
 * {@link Haptics#tap}, que obedece a "Vibracion al pulsar" y al nivel elegido.
 *
 * Fijados (2.0): mantener pulsada una fila, o tocar su chincheta (junto a la x), la fija o la suelta a traves de
 * {@link Host#onTrayPin}; las fijadas van arriba bajo la cabecera "Fijados", separadas del resto por una linea.
 * "Borrar todo" solo vacia las sueltas; cuando ya solo quedan fijadas, el boton pasa a "Borrar tambien los
 * fijados" (segundo toque), que las quita una a una con {@link Host#onTrayRemove} y remata con {@link Host#onTrayClear}.
 */
final class KeyboardTray extends LinearLayout {

    interface Host {
        void onTrayPick(ClipHistory.Entry entry, String text);   // text: el texto completo, la parte elegida o null (imagen)
        void onTrayRemove(ClipHistory.Entry entry);
        void onTrayClear();
        void onTrayClose();
        /**
         * Chincheta o pulsacion larga sobre una fila: fijar (pinned=true) o soltar la entrada. El anfitrion guarda
         * el cambio con {@link ClipHistory#setPinned} y vuelve a mostrar la lista con {@link #show}; la bandeja no
         * toca el historial por su cuenta. {@code entry} llega con el estado que tenia antes del toque.
         */
        void onTrayPin(ClipHistory.Entry entry, boolean pinned);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int PIECE_CHARS = 24;

    private final float dp;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Themes.Theme theme = Themes.DEFAULT;
    private Host host;

    private final TextView title;
    private final TextView clear;
    private final CloseButton close;
    private final LinearLayout list;

    // Contenido mostrado, para reconstruirlo si cambia el tema
    private final List<ClipHistory.Entry> items = new ArrayList<>();
    private CharSequence emptyText;
    private boolean shown = false;

    KeyboardTray(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(px(44));
        header.setPadding(px(16), 0, px(6), 0);

        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setAllCaps(true);
        title.setLetterSpacing(0.08f);
        title.setTypeface(medium);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        clear = new TextView(c);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clear.setTypeface(medium);
        clear.setText(R.string.clipboard_clear);
        clear.setPadding(px(12), px(8), px(12), px(8));
        clear.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                clearTapped();
            }
        });
        header.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));

        close = new CloseButton(c);
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                if (host != null) host.onTrayClose();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(px(44), px(40)));
        addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(px(8), px(2), px(8), px(10));
        scroll.addView(list, new ViewGroup.LayoutParams(MATCH, WRAP));
        addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        applyTheme(theme);
    }

    void setHost(Host h) { host = h; }

    /** Colores del tema del teclado; si ya hay contenido, lo reconstruye con ellos. */
    void applyTheme(Themes.Theme t) {
        theme = t == null ? Themes.DEFAULT : t;
        setBackgroundColor(theme.bg);
        title.setTextColor(theme.hint);
        clear.setTextColor(theme.icon);
        clear.setBackground(ripple(0x00000000, theme.pressed, px(8)));
        close.setColor(theme.icon);
        close.setBackground(ripple(theme.specialBg, theme.pressed, px(20)));
        if (shown) rebuild();
    }

    /** Muestra los elementos; si no hay ninguno se muestra {@code emptyText} centrado. */
    void show(CharSequence titleText, List<ClipHistory.Entry> newItems, CharSequence emptyText) {
        this.emptyText = emptyText;
        items.clear();
        if (newItems != null) items.addAll(newItems);
        title.setText(titleText == null ? "" : titleText);
        shown = true;
        rebuild();
    }

    private void rebuild() {
        clear.setVisibility(items.isEmpty() ? GONE : VISIBLE);
        list.removeAllViews();
        if (items.isEmpty()) {
            list.addView(emptyRow(), rowParams());
            return;
        }
        List<ClipHistory.Entry> pinned = new ArrayList<>();
        List<ClipHistory.Entry> rest = new ArrayList<>();
        for (ClipHistory.Entry e : items) (e.pinned ? pinned : rest).add(e);
        // Solo quedan fijados: "Borrar todo" no haria nada, asi que el boton ofrece borrarlos tambien
        clear.setText(rest.isEmpty() ? R.string.clip_clear_pinned : R.string.clipboard_clear);
        if (!pinned.isEmpty()) {
            list.addView(sectionHeader(getContext().getString(R.string.clip_pinned)), rowParams());
            for (ClipHistory.Entry e : pinned) list.addView(e.isImage() ? imageRow(e) : textRow(e), rowParams());
            if (!rest.isEmpty()) list.addView(divider(), dividerParams());
        }
        for (ClipHistory.Entry e : rest) list.addView(e.isImage() ? imageRow(e) : textRow(e), rowParams());
    }

    /** Cabecera pequena de seccion ("Fijados"), con el estilo del titulo. */
    private TextView sectionHeader(CharSequence text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setTypeface(medium);
        t.setTextColor(theme.hint);
        t.setPadding(px(8), px(2), px(8), 0);
        return t;
    }

    /** Linea fina entre los fijados y el resto. */
    private View divider() {
        View v = new View(getContext());
        v.setBackgroundColor((theme.hint & 0x00FFFFFF) | 0x55000000);
        return v;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, Math.max(1, px(1)));
        p.topMargin = px(10);
        p.bottomMargin = px(4);
        p.leftMargin = px(6);
        p.rightMargin = px(6);
        return p;
    }

    /** "Borrar todo": vacia las sueltas; si ya solo hay fijadas, las quita una a una y luego avisa de vaciar. */
    private void clearTapped() {
        if (host == null) return;
        boolean onlyPinned = true;
        for (ClipHistory.Entry e : items) if (!e.pinned) { onlyPinned = false; break; }
        if (onlyPinned) {
            for (ClipHistory.Entry e : new ArrayList<>(items)) host.onTrayRemove(e);
        }
        host.onTrayClear();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.topMargin = px(6);
        return p;
    }

    private TextView emptyRow() {
        TextView t = new TextView(getContext());
        t.setText(emptyText == null ? "" : emptyText);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(theme.hint);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(px(32), px(36), px(32), px(16));
        return t;
    }

    /** Fila base: fondo de tecla redondeado con borde si el tema lo lleva, y ondulacion al pulsar. */
    private LinearLayout row(final ClipHistory.Entry entry, final String pick) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(48));
        r.setPadding(px(14), px(10), px(14), px(10));
        r.setBackground(ripple(theme.keyBg, theme.pressed, theme.radiusDp * dp));
        r.setClickable(true);
        r.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                if (host != null) host.onTrayPick(entry, pick);
            }
        });
        r.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                togglePin(v, entry);
                return true;
            }
        });
        return r;
    }

    /** Fijar o soltar la fila (pulsacion larga o chincheta): lo decide y lo guarda el anfitrion. */
    private void togglePin(View v, ClipHistory.Entry entry) {
        Haptics.tap(v);
        if (host != null) host.onTrayPin(entry, !entry.pinned);
    }

    /** Chincheta junto a la x: rellena si la fila esta fijada, solo contorno si no; tocarla alterna. */
    private PinButton pinButton(final ClipHistory.Entry entry) {
        PinButton pin = new PinButton(getContext());
        pin.setPinned(entry.pinned);
        pin.setColor(entry.pinned ? theme.accent : theme.hint);
        pin.setContentDescription(getContext().getString(entry.pinned ? R.string.clip_unpin : R.string.clip_pin));
        pin.setBackground(ripple(0x00000000, theme.pressed, px(20)));
        pin.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { togglePin(v, entry); }
        });
        return pin;
    }

    private LinearLayout.LayoutParams pinParams() {
        return new LinearLayout.LayoutParams(px(36), MATCH);
    }

    /** Boton x de una fila: quita la fila y avisa al anfitrion. */
    private TextView deleteButton(final ClipHistory.Entry entry) {
        TextView del = new TextView(getContext());
        del.setText("×");
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        del.setTextColor(theme.hint);
        del.setGravity(Gravity.CENTER);
        del.setContentDescription(getContext().getString(R.string.clipboard_clear));
        del.setBackground(ripple(0x00000000, theme.pressed, px(20)));
        del.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                items.remove(entry);
                rebuild();                                 // tambien quita la cabecera "Fijados" o la linea si ya no hacen falta
                if (host != null) host.onTrayRemove(entry);
            }
        });
        return del;
    }

    private LinearLayout.LayoutParams deleteParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(40), MATCH);
        p.leftMargin = px(4);
        return p;
    }

    /** Texto copiado, con chips para pegar solo el telefono, el correo o el enlace que contenga. */
    private LinearLayout textRow(final ClipHistory.Entry entry) {
        final LinearLayout r = row(entry, entry.text);
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);

        TextView text = new TextView(getContext());
        text.setText(entry.text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setTextColor(theme.glyph);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(text, new LinearLayout.LayoutParams(MATCH, WRAP));

        String phone = ClipHistory.phoneIn(entry.text);
        String email = ClipHistory.emailIn(entry.text);
        String link = ClipHistory.linkIn(entry.text);
        if (phone != null || email != null || link != null) {
            LinearLayout chips = new LinearLayout(getContext());
            chips.setOrientation(HORIZONTAL);
            chips.setPadding(0, 0, px(12), 0);
            chips.setClipToPadding(false);
            if (phone != null) chips.addView(chip(entry, R.string.clip_only_number, phone), chipParams());
            if (email != null) chips.addView(chip(entry, R.string.clip_only_email, email), chipParams());
            if (link != null) chips.addView(chip(entry, R.string.clip_only_link, link), chipParams());
            HorizontalScrollView scroll = new HorizontalScrollView(getContext());
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setHorizontalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(px(24));
            scroll.addView(chips, new ViewGroup.LayoutParams(WRAP, WRAP));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
            sp.topMargin = px(6);
            column.addView(scroll, sp);
        }
        r.addView(column, new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(pinButton(entry), pinParams());
        r.addView(deleteButton(entry), deleteParams());
        return r;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.rightMargin = px(6);
        return p;
    }

    /** Chip "Solo el numero · 55 1234 5678": pega solo esa parte, sin disparar el toque de la fila. */
    private TextView chip(final ClipHistory.Entry entry, int label, final String piece) {
        TextView t = new TextView(getContext());
        String shown = piece.length() > PIECE_CHARS ? piece.substring(0, PIECE_CHARS - 1) + "…" : piece;
        t.setText(getContext().getString(label) + " · " + shown);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(medium);
        t.setTextColor(theme.glyph);
        t.setSingleLine(true);
        t.setPadding(px(10), px(5), px(10), px(5));
        t.setBackground(ripple(theme.specialBg, theme.pressed, px(8)));
        t.setClickable(true);
        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.tap(v);
                if (host != null) host.onTrayPick(entry, piece);
            }
        });
        return t;
    }

    /** Imagen copiada: miniatura con esquinas redondeadas; tocarla la pega. */
    private LinearLayout imageRow(final ClipHistory.Entry entry) {
        final LinearLayout r = row(entry, null);
        ImageView img = new ImageView(getContext());
        img.setContentDescription(getContext().getString(R.string.clip_image));
        img.setAdjustViewBounds(true);
        img.setMaxHeight(px(96));
        img.setScaleType(ImageView.ScaleType.FIT_START);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(theme.specialBg);
        shape.setCornerRadius(px(8));
        img.setBackground(shape);
        img.setClipToOutline(true);
        Bitmap b = ClipHistory.thumbnail(entry, 320);
        if (b != null) img.setImageBitmap(b);
        else img.setMinimumHeight(px(48));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(WRAP, WRAP);
        r.addView(img, ip);
        r.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(pinButton(entry), pinParams());
        r.addView(deleteButton(entry), deleteParams());
        return r;
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

    /** Chincheta: cabeza, cuello y punta; rellena si esta fijada, en contorno si no. */
    private static final class PinButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float dp;
        private boolean pinned;

        PinButton(Context c) {
            super(c);
            dp = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(1.8f * dp);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        void setPinned(boolean p) { pinned = p; invalidate(); }

        void setColor(int color) { paint.setColor(color); invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float s = 7 * dp;                              // media altura del dibujo
            paint.setStyle(pinned ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            path.reset();
            path.moveTo(cx - s * 0.55f, cy - s);           // cabeza (trapecio ancho arriba)
            path.lineTo(cx + s * 0.55f, cy - s);
            path.lineTo(cx + s * 0.4f, cy - s * 0.1f);
            path.lineTo(cx + s * 0.85f, cy + s * 0.25f);  // hombros
            path.lineTo(cx - s * 0.85f, cy + s * 0.25f);
            path.lineTo(cx - s * 0.4f, cy - s * 0.1f);
            path.close();
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            c.drawLine(cx, cy + s * 0.25f, cx, cy + s, paint);   // punta
        }
    }

    /**
     * Boton de cierre: dibuja un teclado pequeno (marco, dos filas de puntos y la barra espaciadora). Lo comparten
     * las demas bandejas (herramientas, edicion) para que todas cierren igual.
     */
    static final class CloseButton extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float dp;

        CloseButton(Context c) {
            super(c);
            dp = getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * dp);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            fill.setStyle(Paint.Style.FILL);
            setClickable(true);
            setFocusable(true);
        }

        void setColor(int color) {
            stroke.setColor(color);
            fill.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float w = 10 * dp, h = 6.5f * dp;
            rect.set(cx - w, cy - h, cx + w, cy + h);
            c.drawRoundRect(rect, 2.5f * dp, 2.5f * dp, stroke);
            float r = 0.9f * dp;
            for (int row = 0; row < 2; row++) {
                float y = cy - h + (row == 0 ? 3.6f : 6.8f) * dp;
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * 5 * dp, y, r, fill);
            }
            rect.set(cx - 5 * dp, cy + h - 3.2f * dp, cx + 5 * dp, cy + h - 1.6f * dp);
            c.drawRoundRect(rect, 0.8f * dp, 0.8f * dp, fill);
        }
    }
}
