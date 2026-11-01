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
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Panel de emojis que sustituye a las teclas. Sigue la clasificacion oficial de Unicode
 * (emoji-test.txt): pestanas por grupo (recientes mas los nueve grupos) y, dentro de cada grupo,
 * una cabecera por subgrupo en el orden canonico. La lista es continua: la pestana marcada sigue
 * al desplazamiento y tocar una pestana salta a su grupo. Abajo: abc, espacio y borrar.
 */
final class EmojiTray extends LinearLayout {

    interface Host {
        void onEmoji(String emoji);
        void onEmojiDelete();
        void onEmojiSpace();
        void onEmojiClose();
    }

    private static final int COLUMNS = 8;
    private static final String RECENT = "recent";
    private static final String[] GROUPS = {RECENT, "smileys", "people", "nature", "food", "travel",
            "activities", "objects", "symbols", "flags"};
    private static final String[] ICONS = {"🕘", "😀", "🧑", "🐻", "🍔", "🚗", "⚽", "💡", "🔣", "🏁"};

    /** Subgrupo de Unicode: grupo, nombre en espanol e ingles y emojis en orden canonico. */
    private static final class Section {
        final String group, nameEs, nameEn;
        final List<String> emoji = new ArrayList<>();
        Section(String group, String nameEs, String nameEn) {
            this.group = group; this.nameEs = nameEs; this.nameEn = nameEn;
        }
    }

    /** Fila de la lista: cabecera de subgrupo, mensaje (recientes vacios) o hasta COLUMNS emojis. */
    private static final class Row {
        final String group, header, message;
        final List<String> emoji;
        Row(String group, String header, String message, List<String> emoji) {
            this.group = group; this.header = header; this.message = message; this.emoji = emoji;
        }
    }

    private static List<Section> sections;      // recurso leido una sola vez

    private final float dp;
    private Themes.Theme theme = Themes.DEFAULT;
    private Host host;
    private final List<Row> rows = new ArrayList<>();
    private final int[] groupStart = new int[GROUPS.length];
    private final TextView[] tabs = new TextView[GROUPS.length];
    private final HorizontalScrollView tabScroll;
    private final ListView list;
    private final KeyView abc, space, delete;
    private int currentTab = -1;

    EmojiTray(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        // Pestanas de grupo
        tabScroll = new HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(c);
        tabRow.setOrientation(HORIZONTAL);
        tabRow.setPadding(px(4), 0, px(4), 0);
        for (int i = 0; i < GROUPS.length; i++) {
            final int g = i;
            TextView t = new TextView(c);
            t.setText(ICONS[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            t.setGravity(Gravity.CENTER);
            t.setContentDescription(GROUPS[i]);
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (groupStart[g] >= 0) list.setSelection(groupStart[g]);
                    highlight(g);
                }
            });
            tabRow.addView(t, new LinearLayout.LayoutParams(px(40), px(40)));
            tabs[i] = t;
        }
        tabScroll.addView(tabRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)));
        addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));

        // Lista continua: cabeceras de subgrupo y filas de emojis
        list = new ListView(c);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (first >= 0 && first < rows.size()) highlight(groupIndex(rows.get(first).group));
            }
        });
        addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Fila inferior
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(HORIZONTAL);
        bottom.setPadding(px(6), px(4), px(6), px(6));
        abc = new KeyView(c, KeyView.ABC);
        space = new KeyView(c, KeyView.SPACE);
        delete = new KeyView(c, KeyView.DELETE);
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(px(64), px(48));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, px(48), 1f);
        mid.leftMargin = px(4);
        mid.rightMargin = px(4);
        bottom.addView(abc, side);
        bottom.addView(space, mid);
        bottom.addView(delete, new LinearLayout.LayoutParams(px(64), px(48)));
        addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        applyTheme(theme);
    }

    void setHost(Host h) { host = h; }

    /** Colores del tema del teclado. */
    void applyTheme(Themes.Theme t) {
        theme = t == null ? Themes.DEFAULT : t;
        setBackgroundColor(theme.bg);
        for (int i = 0; i < tabs.length; i++) styleTab(i);
        abc.invalidate();
        space.invalidate();
        delete.invalidate();
        adapter.notifyDataSetChanged();
    }

    /** Construye las filas (recientes primero) y abre el panel en recientes o, si no hay, en caritas. */
    void show(List<String> recent) {
        List<Section> data = load(getContext());
        boolean es = "es".equals(Locale.getDefault().getLanguage());
        rows.clear();
        for (int i = 0; i < GROUPS.length; i++) groupStart[i] = -1;
        groupStart[0] = 0;
        rows.add(new Row(RECENT, getContext().getString(R.string.emoji_recent_title), null, null));
        if (recent == null || recent.isEmpty()) {
            rows.add(new Row(RECENT, null, getContext().getString(R.string.emoji_recent_empty), null));
        } else {
            addChunks(RECENT, recent);
        }
        for (Section s : data) {
            int g = groupIndex(s.group);
            if (g < 0 || s.emoji.isEmpty()) continue;
            if (groupStart[g] < 0) groupStart[g] = rows.size();
            rows.add(new Row(s.group, es ? s.nameEs : s.nameEn, null, null));
            addChunks(s.group, s.emoji);
        }
        adapter.notifyDataSetChanged();
        int start = recent == null || recent.isEmpty() ? Math.max(0, groupStart[1]) : 0;
        list.setSelection(start);
        highlight(groupIndex(rows.get(start).group));
    }

    private void addChunks(String group, List<String> emoji) {
        for (int i = 0; i < emoji.size(); i += COLUMNS) {
            rows.add(new Row(group, null, null, emoji.subList(i, Math.min(emoji.size(), i + COLUMNS))));
        }
    }

    private static int groupIndex(String group) {
        for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i].equals(group)) return i;
        return -1;
    }

    private void highlight(int g) {
        if (g < 0 || g == currentTab) return;
        currentTab = g;
        for (int i = 0; i < tabs.length; i++) styleTab(i);
        final TextView tab = tabs[g];
        tabScroll.post(new Runnable() {
            @Override public void run() {
                int target = tab.getLeft() - (tabScroll.getWidth() - tab.getWidth()) / 2;
                tabScroll.smoothScrollTo(Math.max(0, target), 0);
            }
        });
    }

    private void styleTab(int i) {
        if (i == currentTab) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(theme.specialBg);
            bg.setCornerRadius(theme.radiusDp * dp);
            if (theme.keyStroke != 0) bg.setStroke(Math.max(1, px(1)), theme.keyStroke);
            tabs[i].setBackground(bg);
        } else {
            tabs[i].setBackground(null);
        }
    }

    /** Lee el recurso una sola vez: una linea por subgrupo (grupo, subgrupo, nombre es, nombre en, emojis). */
    private static synchronized List<Section> load(Context c) {
        if (sections != null) return sections;
        List<Section> out = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getResources().openRawResource(R.raw.emoji), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] f = line.split("\t");
                    if (f.length < 5) continue;
                    Section s = new Section(f[0], f[2], f[3]);
                    for (String e : f[4].trim().split(" ")) if (!e.isEmpty()) s.emoji.add(e);
                    out.add(s);
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // sin datos el panel queda vacio
        }
        sections = out;
        return out;
    }

    // ------------------------------------------------------------------ filas

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return false; }
        @Override public int getItemViewType(int i) {
            Row r = rows.get(i);
            return r.header != null ? 0 : r.message != null ? 1 : 2;
        }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            Row r = rows.get(i);
            if (r.header != null) {
                TextView t = convert instanceof TextView ? (TextView) convert : textRow(12, true);
                t.setText(r.header);
                t.setTextColor(theme.hint);
                return t;
            }
            if (r.message != null) {
                TextView t = convert instanceof TextView ? (TextView) convert : textRow(14, false);
                t.setText(r.message);
                t.setTextColor(theme.hint);
                return t;
            }
            EmojiRow row = convert instanceof EmojiRow ? (EmojiRow) convert : new EmojiRow(getContext());
            row.bind(r.emoji);
            return row;
        }
    };

    private TextView textRow(int sp, boolean header) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (header) {
            t.setAllCaps(true);
            t.setLetterSpacing(0.06f);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t.setPadding(px(12), px(12), px(12), px(4));
        } else {
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(24), px(14), px(24), px(14));
        }
        return t;
    }

    /** Fila de hasta COLUMNS celdas; cada celda escribe su emoji al tocarla. */
    private final class EmojiRow extends LinearLayout {
        private final TextView[] cells = new TextView[COLUMNS];

        EmojiRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            for (int j = 0; j < COLUMNS; j++) {
                TextView t = new TextView(c);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                t.setGravity(Gravity.CENTER);
                t.setIncludeFontPadding(false);
                t.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (host != null) host.onEmoji(((TextView) v).getText().toString());
                    }
                });
                addView(t, new LinearLayout.LayoutParams(0, px(44), 1f));
                cells[j] = t;
            }
        }

        void bind(List<String> emoji) {
            for (int j = 0; j < COLUMNS; j++) {
                boolean has = j < emoji.size();
                cells[j].setText(has ? emoji.get(j) : "");
                cells[j].setVisibility(has ? VISIBLE : INVISIBLE);
                cells[j].setTextColor(theme.glyph);
                GradientDrawable mask = new GradientDrawable();
                mask.setColor(0xFFFFFFFF);
                mask.setCornerRadius(px(8));
                cells[j].setBackground(new RippleDrawable(
                        ColorStateList.valueOf((theme.pressed & 0x00FFFFFF) | 0x80000000), null, mask));
            }
        }
    }

    // ------------------------------------------------------------------ teclas inferiores

    /** Tecla dibujada a mano: abc (en Aurebesh), espacio o borrar (con repeticion al mantener). */
    private final class KeyView extends View {
        static final int ABC = 0, SPACE = 1, DELETE = 2;
        private final int kind;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint latin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean down = false;
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!down || host == null) return;
                host.onEmojiDelete();
                handler.postDelayed(this, 60);
            }
        };

        KeyView(Context c, int kind) {
            super(c);
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            latin.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = theme.radiusDp * dp;
            rect.set(0, 0, getWidth(), getHeight());
            fill.setColor(down ? theme.pressed : kind == SPACE ? theme.keyBg : theme.specialBg);
            c.drawRoundRect(rect, r, r, fill);
            if (theme.keyStroke != 0) {
                stroke.setColor(theme.keyStroke);
                stroke.setStrokeWidth(dp);
                rect.inset(dp / 2f, dp / 2f);
                c.drawRoundRect(rect, r, r, stroke);
            }
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (kind == ABC) {
                glyph.setColor(theme.glyph);
                latin.setColor(theme.glyph);
                float h = 10 * dp;
                List<Glyphs.Token> tokens = Glyphs.tokenize("abc", false);
                float total = 0;
                for (Glyphs.Token t : tokens) total += Glyphs.tokenWidth(t, h, latin);
                float x = cx - total / 2f;
                for (Glyphs.Token t : tokens) x += Glyphs.drawToken(c, t, x, cy - h / 2f, h, glyph, latin);
            } else if (kind == DELETE) {
                stroke.setColor(theme.icon);
                stroke.setStrokeWidth(2.6f * dp);
                float w = 11 * dp, hh = 7 * dp;
                path.reset();
                path.moveTo(cx - w, cy);
                path.lineTo(cx - w * 0.45f, cy - hh);
                path.lineTo(cx + w, cy - hh);
                path.lineTo(cx + w, cy + hh);
                path.lineTo(cx - w * 0.45f, cy + hh);
                path.close();
                c.drawPath(path, stroke);
                float x = cx + w * 0.2f, k = 2.6f * dp;
                c.drawLine(x - k, cy - k, x + k, cy + k, stroke);
                c.drawLine(x - k, cy + k, x + k, cy - k, stroke);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (kind == DELETE && host != null) {
                        host.onEmojiDelete();
                        handler.postDelayed(repeat, 420);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
                    down = false;
                    handler.removeCallbacks(repeat);
                    if (inside && host != null) {
                        if (kind == ABC) host.onEmojiClose();
                        else if (kind == SPACE) host.onEmojiSpace();
                    }
                    invalidate();
                    return true;
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

    private int px(float dps) { return Math.round(dps * dp); }
}
