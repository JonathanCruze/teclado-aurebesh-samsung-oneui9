package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel de emojis que sustituye a las teclas dentro de la ventana del teclado: pestanas por
 * categoria (recientes primero), cuadricula desplazable y fila inferior con ABC, espacio y borrar.
 * Los emojis vienen de res/raw/emoji.txt (una categoria por linea: id, tabulador, emojis con espacios).
 */
final class EmojiTray extends LinearLayout {

    interface Host {
        void onEmoji(String emoji);
        void onEmojiDelete();
        void onEmojiSpace();
        void onEmojiClose();
    }

    private static final String[] TAB_ICONS = {"🕘", "😀", "🧑", "🐻", "🍔", "🚗", "⚽", "💡", "🔣", "🏁"};
    private static final String[] CATEGORY_IDS = {"smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols", "flags"};
    private static final int COLUMNS = 8;
    private static final int KEY_ABC = 0, KEY_SPACE = 1, KEY_DELETE = 2;
    private static List<List<String>> categories;      // cache: una lista por categoria, en el orden de CATEGORY_IDS

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private final float dp;
    private Themes.Theme theme = Themes.DEFAULT;
    private Host host;
    private final List<String> recent = new ArrayList<>();
    private final List<String> current = new ArrayList<>();
    private int selectedTab = 1;

    private final HorizontalScrollView tabScroll;
    private final List<TextView> tabViews = new ArrayList<>();
    private final GridView grid;
    private final TextView empty;
    private final EmojiAdapter adapter = new EmojiAdapter();
    private final KeyView abcKey, spaceKey, deleteKey;

    EmojiTray(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        // Pestanas de categoria
        tabScroll = new HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(c);
        tabs.setOrientation(HORIZONTAL);
        tabs.setPadding(px(4), 0, px(4), 0);
        for (int i = 0; i < TAB_ICONS.length; i++) {
            final int index = i;
            TextView tab = new TextView(c);
            tab.setText(TAB_ICONS[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            tab.setGravity(Gravity.CENTER);
            tab.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    selectTab(index);
                }
            });
            tabs.addView(tab, new LayoutParams(px(40), MATCH));
            tabViews.add(tab);
        }
        tabScroll.addView(tabs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, MATCH));
        addView(tabScroll, new LayoutParams(MATCH, px(40)));

        // Cuadricula (o aviso de recientes vacios)
        FrameLayout body = new FrameLayout(c);
        grid = new GridView(c);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(0);
        grid.setHorizontalSpacing(0);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                if (position < 0 || position >= current.size()) return;
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (host != null) host.onEmoji(current.get(position));
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));
        empty = new TextView(c);
        empty.setText(R.string.emoji_recent_empty);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(32), px(16), px(32), px(16));
        empty.setVisibility(GONE);
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        addView(body, new LayoutParams(MATCH, 0, 1f));

        // Fila inferior: ABC, espacio, borrar
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setPadding(px(6), px(4), px(6), px(6));
        abcKey = new KeyView(c, KEY_ABC);
        spaceKey = new KeyView(c, KEY_SPACE);
        deleteKey = new KeyView(c, KEY_DELETE);
        row.addView(abcKey, new LayoutParams(px(64), MATCH));
        LayoutParams sp = new LayoutParams(0, MATCH, 1f);
        sp.leftMargin = px(4);
        row.addView(spaceKey, sp);
        LayoutParams dl = new LayoutParams(px(64), MATCH);
        dl.leftMargin = px(4);
        row.addView(deleteKey, dl);
        addView(row, new LayoutParams(MATCH, px(58)));

        applyTheme(theme);
    }

    void setHost(Host h) { host = h; }

    /** Colores del tema en pestanas, celdas, teclas y fondo. */
    void applyTheme(Themes.Theme t) {
        theme = t;
        setBackgroundColor(t.bg);
        for (int i = 0; i < tabViews.size(); i++) styleTab(i);
        empty.setTextColor(t.hint);
        adapter.notifyDataSetChanged();
        abcKey.invalidate();
        spaceKey.invalidate();
        deleteKey.invalidate();
    }

    /** (Re)abre el panel; recientes primero (los mas nuevos al principio), o caritas si no hay. */
    void show(List<String> recentEmoji) {
        load(getContext());
        recent.clear();
        if (recentEmoji != null) recent.addAll(recentEmoji);
        selectTab(recent.isEmpty() ? 1 : 0);
        tabScroll.scrollTo(0, 0);
    }

    private void selectTab(int index) {
        selectedTab = index;
        for (int i = 0; i < tabViews.size(); i++) styleTab(i);
        current.clear();
        if (index == 0) current.addAll(recent);
        else if (categories != null && index - 1 < categories.size()) current.addAll(categories.get(index - 1));
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        boolean showEmpty = index == 0 && current.isEmpty();
        empty.setVisibility(showEmpty ? VISIBLE : GONE);
        grid.setVisibility(showEmpty ? INVISIBLE : VISIBLE);
    }

    private void styleTab(int i) {
        TextView tab = tabViews.get(i);
        if (i == selectedTab) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(theme.specialBg);
            bg.setCornerRadius(theme.radiusDp * dp);
            if (theme.keyStroke != 0) bg.setStroke(Math.max(1, px(1)), theme.keyStroke);
            tab.setBackground(bg);
        } else {
            tab.setBackground(null);
        }
    }

    private int px(float dps) { return Math.round(dps * dp); }

    /** Lee res/raw/emoji.txt una sola vez; una categoria que falte queda vacia. */
    private static synchronized void load(Context c) {
        if (categories != null) return;
        List<List<String>> cats = new ArrayList<>();
        for (int i = 0; i < CATEGORY_IDS.length; i++) cats.add(new ArrayList<String>());
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(R.raw.emoji), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab < 0) continue;
                    String id = line.substring(0, tab).trim();
                    for (int i = 0; i < CATEGORY_IDS.length; i++) {
                        if (!CATEGORY_IDS[i].equals(id)) continue;
                        for (String e : line.substring(tab + 1).trim().split(" ")) if (!e.isEmpty()) cats.get(i).add(e);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
            // sin archivo de emojis el panel queda vacio pero no falla
        }
        categories = cats;
    }

    /** Celdas de la cuadricula: un TextView por emoji, reciclado. */
    private final class EmojiAdapter extends BaseAdapter {
        @Override public int getCount() { return current.size(); }
        @Override public Object getItem(int position) { return current.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView cell;
            if (convertView instanceof TextView) {
                cell = (TextView) convertView;
            } else {
                cell = new TextView(getContext());
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                cell.setGravity(Gravity.CENTER);
                cell.setLayoutParams(new AbsListView.LayoutParams(MATCH, px(44)));
            }
            if (cell.getTag() != theme) {                      // colores del tema actual
                cell.setTextColor(theme.glyph);
                cell.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.pressed), null, new ColorDrawable(0xFFFFFFFF)));
                cell.setTag(theme);
            }
            cell.setText(current.get(position));
            return cell;
        }
    }

    /** Tecla de la fila inferior dibujada con Paint: ABC en Aurebesh, espacio o borrar (con repeticion). */
    private final class KeyView extends View {
        private final int type;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final List<Glyphs.Token> abc = Glyphs.tokenize("abc", false);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean pressed;
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!pressed || host == null) return;
                host.onEmojiDelete();
                handler.postDelayed(this, 60);
            }
        };

        KeyView(Context c, int type) {
            super(c);
            this.type = type;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            iconPaint.setStrokeWidth(2.6f * dp);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float r = theme.radiusDp * dp;
            rect.set(0, 0, getWidth(), getHeight());
            fill.setColor(pressed ? theme.pressed : type == KEY_SPACE ? theme.keyBg : theme.specialBg);
            c.drawRoundRect(rect, r, r, fill);
            if (theme.keyStroke != 0) {
                stroke.setColor(theme.keyStroke);
                rect.inset(dp / 2f, dp / 2f);
                c.drawRoundRect(rect, r, r, stroke);
            }
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (type == KEY_ABC) {
                float h = 10 * dp, total = 0;
                glyphPaint.setColor(theme.glyph);
                textPaint.setColor(theme.glyph);
                for (Glyphs.Token t : abc) total += Glyphs.tokenWidth(t, h, textPaint);
                float x = cx - total / 2f;
                for (Glyphs.Token t : abc) x += Glyphs.drawToken(c, t, x, cy - h / 2f, h, glyphPaint, textPaint);
            } else if (type == KEY_DELETE) {
                float w = 11 * dp, h = 7 * dp;                // mismo icono que la tecla de borrar del teclado
                iconPaint.setColor(theme.icon);
                path.reset();
                path.moveTo(cx - w, cy);
                path.lineTo(cx - w * 0.45f, cy - h);
                path.lineTo(cx + w, cy - h);
                path.lineTo(cx + w, cy + h);
                path.lineTo(cx - w * 0.45f, cy + h);
                path.close();
                c.drawPath(path, iconPaint);
                float x = cx + w * 0.2f, k = 2.6f * dp;
                c.drawLine(x - k, cy - k, x + k, cy + k, iconPaint);
                c.drawLine(x - k, cy + k, x + k, cy - k, iconPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (type == KEY_DELETE && host != null) {
                        host.onEmojiDelete();
                        handler.postDelayed(repeat, 420);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    handler.removeCallbacks(repeat);
                    if (host != null) {
                        if (type == KEY_ABC) host.onEmojiClose();
                        else if (type == KEY_SPACE) host.onEmojiSpace();
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    handler.removeCallbacks(repeat);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            pressed = false;
            handler.removeCallbacks(repeat);
        }
    }
}
