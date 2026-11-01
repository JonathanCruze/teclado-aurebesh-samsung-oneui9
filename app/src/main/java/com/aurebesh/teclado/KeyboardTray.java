package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * aparecen chips para pegar solo esa parte; la x borra y "Borrar todo" vacia.
 */
final class KeyboardTray extends LinearLayout {

    interface Host {
        void onTrayPick(ClipHistory.Entry entry, String text);   // text: el texto completo, la parte elegida o null (imagen)
        void onTrayRemove(ClipHistory.Entry entry);
        void onTrayClear();
        void onTrayClose();
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
                if (host != null) host.onTrayClear();
            }
        });
        header.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));

        close = new CloseButton(c);
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
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
        close.setBackground(ripple(0x00000000, theme.pressed, px(20)));
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
        for (ClipHistory.Entry e : items) list.addView(e.isImage() ? imageRow(e) : textRow(e), rowParams());
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
                if (host != null) host.onTrayPick(entry, pick);
            }
        });
        return r;
    }

    /** Boton x de una fila: quita la fila y avisa al anfitrion. */
    private TextView deleteButton(final ClipHistory.Entry entry, final View row) {
        TextView del = new TextView(getContext());
        del.setText("×");
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        del.setTextColor(theme.hint);
        del.setGravity(Gravity.CENTER);
        del.setContentDescription(getContext().getString(R.string.clipboard_clear));
        del.setBackground(ripple(0x00000000, theme.pressed, px(20)));
        del.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                items.remove(entry);
                list.removeView(row);
                if (items.isEmpty()) {
                    clear.setVisibility(GONE);
                    list.addView(emptyRow(), rowParams());
                }
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
            if (phone != null) chips.addView(chip(entry, R.string.clip_only_number, phone), chipParams());
            if (email != null) chips.addView(chip(entry, R.string.clip_only_email, email), chipParams());
            if (link != null) chips.addView(chip(entry, R.string.clip_only_link, link), chipParams());
            HorizontalScrollView scroll = new HorizontalScrollView(getContext());
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(chips, new ViewGroup.LayoutParams(WRAP, WRAP));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
            sp.topMargin = px(6);
            column.addView(scroll, sp);
        }
        r.addView(column, new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(deleteButton(entry, r), deleteParams());
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
        r.addView(deleteButton(entry, r), deleteParams());
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

    /** Boton de cierre: dibuja un teclado pequeno (marco, dos filas de puntos y la barra espaciadora). */
    private static final class CloseButton extends View {
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
