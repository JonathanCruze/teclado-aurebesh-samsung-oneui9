package com.aurebesh.teclado;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Panel de emojis que sustituye a las teclas. Sigue la clasificacion oficial de Unicode
 * (emoji-test.txt): pestanas por grupo (recientes mas los nueve grupos) y, dentro de cada grupo,
 * una cabecera por subgrupo en el orden canonico. La lista es continua: la pestana marcada sigue
 * al desplazamiento y tocar una pestana salta a su grupo. Abajo: abc, espacio y borrar.
 *
 * Busqueda (lupa a la izquierda de las pestanas): se escribe con un teclado Aurebesh reducido y
 * se busca en los nombres y palabras clave de CLDR en espanol e ingles (emoji_names.txt), sin
 * distinguir mayusculas ni tildes: primero palabras exactas del nombre, luego palabras clave
 * exactas, luego por prefijo y por ultimo aproximadas (una letra de diferencia). Con varias
 * palabras, todas deben coincidir.
 */
final class EmojiTray extends LinearLayout {

    interface Host {
        void onEmoji(String emoji);
        void onEmojiDelete();
        void onEmojiSpace();
        void onEmojiClose();
    }

    private static final int COLUMNS = 8;
    private static final int MAX_RESULTS = 48;
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

    /** Fila de la lista: cabecera de subgrupo, mensaje o hasta COLUMNS emojis. */
    private static final class Row {
        final String group, header, message;
        final List<String> emoji;
        Row(String group, String header, String message, List<String> emoji) {
            this.group = group; this.header = header; this.message = message; this.emoji = emoji;
        }
    }

    /** Emoji con sus palabras de busqueda normalizadas: las del nombre (es + en) y todas (con claves). */
    private static final class Named {
        final String emoji;
        final String[] names, words;
        Named(String emoji, String[] names, String[] words) { this.emoji = emoji; this.names = names; this.words = words; }
    }

    private static List<Section> sections;      // recursos leidos una sola vez
    private static List<Named> named;

    private final float dp;
    private Themes.Theme theme = Themes.DEFAULT;
    private Host host;
    private String lang = "es";
    private boolean vibrate = true, sound = true;

    // Modo normal
    private final List<Row> rows = new ArrayList<>();
    private final RowsAdapter adapter = new RowsAdapter(rows);
    private final int[] groupStart = new int[GROUPS.length];
    private final TextView[] tabs = new TextView[GROUPS.length];
    private final TextView searchButton;
    private final LinearLayout tabBar;
    private final HorizontalScrollView tabScroll;
    private final ListView list;
    private final LinearLayout bottom;
    private final KeyView abc, space, delete;
    private int currentTab = -1;

    // Modo busqueda
    private boolean searching = false;
    private final StringBuilder query = new StringBuilder();
    private final List<Row> resultRows = new ArrayList<>();
    private final RowsAdapter resultAdapter = new RowsAdapter(resultRows);
    private final LinearLayout searchBar;
    private final TextView searchIcon, queryView, searchClose;
    private final ListView resultList;
    private final AurebeshKeyboardView miniKb;

    EmojiTray(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        // Lupa fija y pestanas de grupo desplazables
        tabBar = new LinearLayout(c);
        tabBar.setOrientation(HORIZONTAL);
        searchButton = tab(c, "🔍");
        searchButton.setContentDescription("search");
        searchButton.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                openSearch("");
            }
        });
        tabBar.addView(searchButton, new LinearLayout.LayoutParams(px(40), px(40)));
        tabScroll = new HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(c);
        tabRow.setOrientation(HORIZONTAL);
        tabRow.setPadding(0, 0, px(4), 0);
        for (int i = 0; i < GROUPS.length; i++) {
            final int g = i;
            TextView t = tab(c, ICONS[i]);
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
        tabBar.addView(tabScroll, new LinearLayout.LayoutParams(0, px(40), 1f));
        tabBar.setPadding(px(4), 0, 0, 0);
        addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));

        // Lista continua: cabeceras de subgrupo y filas de emojis
        list = plainList(c);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (first >= 0 && first < rows.size()) highlight(groupIndex(rows.get(first).group));
            }
        });
        addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Fila inferior
        bottom = new LinearLayout(c);
        bottom.setOrientation(HORIZONTAL);
        bottom.setPadding(px(6), px(4), px(6), px(6));
        abc = new KeyView(c, KeyView.ABC);
        space = new KeyView(c, KeyView.SPACE);
        delete = new KeyView(c, KeyView.DELETE);
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, px(48), 1f);
        mid.leftMargin = px(4);
        mid.rightMargin = px(4);
        bottom.addView(abc, new LinearLayout.LayoutParams(px(64), px(48)));
        bottom.addView(space, mid);
        bottom.addView(delete, new LinearLayout.LayoutParams(px(64), px(48)));
        addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Busqueda: barra con la consulta, resultados y teclado reducido
        searchBar = new LinearLayout(c);
        searchBar.setOrientation(HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(px(4), 0, px(4), 0);
        searchIcon = tab(c, "🔍");
        searchBar.addView(searchIcon, new LinearLayout.LayoutParams(px(40), px(40)));
        queryView = new TextView(c);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        queryView.setSingleLine(true);
        queryView.setEllipsize(TextUtils.TruncateAt.START);
        queryView.setPadding(px(4), 0, px(4), 0);
        searchBar.addView(queryView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchClose = tab(c, "×");
        searchClose.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                closeSearch();
            }
        });
        searchBar.addView(searchClose, new LinearLayout.LayoutParams(px(40), px(40)));
        searchBar.setVisibility(GONE);
        addView(searchBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));

        resultList = plainList(c);
        resultList.setAdapter(resultAdapter);
        resultList.setVisibility(GONE);
        addView(resultList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        miniKb = new AurebeshKeyboardView(c);
        miniKb.setToolbar(false);
        miniKb.setEnterAction(1);
        miniKb.setListener(new AurebeshKeyboardView.Listener() {
            @Override public void onText(String text) {
                if ("\n".equals(text)) return;
                query.append(text);
                refreshSearch();
            }
            @Override public void onDelete() {
                int n = query.length();
                if (n == 0) return;
                int cut = n >= 2 && Character.isSurrogatePair(query.charAt(n - 2), query.charAt(n - 1)) ? 2 : 1;
                query.setLength(n - cut);
                refreshSearch();
            }
            @Override public void onEnter() {}
            @Override public void onHandSide(int mode) {}
            @Override public void onPasteSuggestion() {}
            @Override public void onSwitchKeyboard() {}
            @Override public void onSwitchLanguage() {}
            @Override public void onSuggestion(String word) {}
            @Override public void onGlide(String best, java.util.List<String> alternatives) {}
            @Override public void onToolbar(int tool) {}
        });
        miniKb.setVisibility(GONE);
        addView(miniKb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        applyTheme(theme);
    }

    void setHost(Host h) { host = h; }

    /** Idioma del teclado reducido de la busqueda ("es" o "en"). */
    void setLanguage(String l) {
        lang = "en".equals(l) ? "en" : "es";
        miniKb.setLanguage(lang);
    }

    /** Vibracion y sonido del teclado reducido de la busqueda. */
    void setFeedback(boolean vibrate, boolean sound) {
        this.vibrate = vibrate;
        this.sound = sound;
        miniKb.applyPrefs(false, vibrate, false, true, theme.id, false, sound, 0.8f);
    }

    /** Colores del tema del teclado. */
    void applyTheme(Themes.Theme t) {
        theme = t == null ? Themes.DEFAULT : t;
        setBackgroundColor(theme.bg);
        for (int i = 0; i < tabs.length; i++) styleTab(tabs[i], i == currentTab);
        styleTab(searchButton, false);
        searchButton.setTextColor(theme.glyph);
        searchIcon.setTextColor(theme.glyph);
        searchClose.setTextColor(theme.hint);
        styleQuery();
        abc.invalidate();
        space.invalidate();
        delete.invalidate();
        miniKb.applyPrefs(false, vibrate, false, true, theme.id, false, sound, 0.8f);
        adapter.notifyDataSetChanged();
        resultAdapter.notifyDataSetChanged();
    }

    /** Construye las filas (recientes primero) y abre el panel en recientes o, si no hay, en caritas. */
    void show(List<String> recent) {
        closeSearch();
        List<Section> data = load(getContext());
        boolean es = "es".equals(Locale.getDefault().getLanguage());
        rows.clear();
        for (int i = 0; i < GROUPS.length; i++) groupStart[i] = -1;
        groupStart[0] = 0;
        rows.add(new Row(RECENT, getContext().getString(R.string.emoji_recent_title), null, null));
        if (recent == null || recent.isEmpty()) {
            rows.add(new Row(RECENT, null, getContext().getString(R.string.emoji_recent_empty), null));
        } else {
            addChunks(rows, RECENT, recent);
        }
        for (Section s : data) {
            int g = groupIndex(s.group);
            if (g < 0 || s.emoji.isEmpty()) continue;
            if (groupStart[g] < 0) groupStart[g] = rows.size();
            rows.add(new Row(s.group, es ? s.nameEs : s.nameEn, null, null));
            addChunks(rows, s.group, s.emoji);
        }
        adapter.notifyDataSetChanged();
        int start = recent == null || recent.isEmpty() ? Math.max(0, groupStart[1]) : 0;
        list.setSelection(start);
        highlight(groupIndex(rows.get(start).group));
    }

    // ------------------------------------------------------------------ busqueda

    /** Entra en modo busqueda con la consulta dada (puede ser vacia). */
    void openSearch(String q) {
        searching = true;
        query.setLength(0);
        if (q != null) query.append(q);
        tabBar.setVisibility(GONE);
        list.setVisibility(GONE);
        bottom.setVisibility(GONE);
        searchBar.setVisibility(VISIBLE);
        resultList.setVisibility(VISIBLE);
        miniKb.setVisibility(VISIBLE);
        miniKb.resetToLetters();
        refreshSearch();
    }

    /** Vuelve a las pestanas y la lista por categorias. */
    void closeSearch() {
        if (!searching) return;
        searching = false;
        query.setLength(0);
        searchBar.setVisibility(GONE);
        resultList.setVisibility(GONE);
        miniKb.setVisibility(GONE);
        tabBar.setVisibility(VISIBLE);
        list.setVisibility(VISIBLE);
        bottom.setVisibility(VISIBLE);
    }

    private void refreshSearch() {
        styleQuery();
        resultRows.clear();
        String q = query.toString().trim();
        if (!q.isEmpty()) {
            List<String> found = search(getContext(), q, MAX_RESULTS);
            if (found.isEmpty()) resultRows.add(new Row("", null, getContext().getString(R.string.emoji_search_empty), null));
            else addChunks(resultRows, "", found);
        }
        resultAdapter.notifyDataSetChanged();
        resultList.setSelection(0);
    }

    private void styleQuery() {
        if (query.length() == 0) {
            queryView.setText(R.string.emoji_search_hint);
            queryView.setTextColor(theme.hint);
        } else {
            queryView.setText(query + "|");
            queryView.setTextColor(theme.glyph);
        }
    }

    /**
     * Emojis cuyo nombre o palabras clave (es + en) coinciden con la consulta. Por niveles: palabra
     * exacta del nombre, palabra clave exacta, prefijo, aproximada (una letra de diferencia, con
     * consultas de 4 letras o mas); con varias palabras todas deben coincidir. Dentro de cada nivel
     * se conserva el orden de Unicode.
     */
    static List<String> search(Context c, String query, int max) {
        List<String> out = new ArrayList<>();
        String[] qs = normalize(query).trim().split("\\s+");
        if (qs.length == 0 || qs[0].isEmpty()) return out;
        List<Named> all = loadNames(c);
        List<Hit> hits = new ArrayList<>();
        int order = 0;
        for (Named n : all) {
            int worst = 0, pos = Integer.MAX_VALUE;
            for (String q : qs) {
                int best = 4;
                for (int i = 0; i < n.names.length; i++) {
                    if (n.names[i].equals(q)) { best = 0; if (i < pos) pos = i; break; }
                }
                if (best > 0) {
                    for (String w : n.words) {
                        int tier = w.equals(q) ? 1 : w.startsWith(q) ? 2 : (q.length() >= 4 && within1(w, q)) ? 3 : 4;
                        if (tier < best) best = tier;
                        if (best == 1) break;
                    }
                }
                if (best == 4) { worst = 4; break; }
                if (best > worst) worst = best;
            }
            if (worst < 4) hits.add(new Hit(n.emoji, worst, pos, n.names.length, order++));
        }
        // Mejor nivel primero; dentro del nivel, la consulta al principio del nombre y nombres cortos
        // ("corazón rojo" antes que "cara sonriendo con ojos de corazón"); luego el orden de Unicode.
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit x, Hit y) {
                if (x.tier != y.tier) return x.tier - y.tier;
                if (x.pos != y.pos) return x.pos - y.pos;
                if (x.len != y.len) return x.len - y.len;
                return x.order - y.order;
            }
        });
        for (Hit h : hits) {
            if (out.size() >= max) break;
            out.add(h.emoji);
        }
        return out;
    }

    /** Coincidencia de la busqueda con su nivel y posicion para ordenar. */
    private static final class Hit {
        final String emoji; final int tier, pos, len, order;
        Hit(String emoji, int tier, int pos, int len, int order) {
            this.emoji = emoji; this.tier = tier; this.pos = pos; this.len = len; this.order = order;
        }
    }

    /** Minusculas sin tildes ni dieresis; la n con virgulilla pasa a n (solo para comparar). */
    private static String normalize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            switch (ch) {
                case 'á': case 'à': case 'ä': case 'â': ch = 'a'; break;
                case 'é': case 'è': case 'ë': case 'ê': ch = 'e'; break;
                case 'í': case 'ì': case 'ï': case 'î': ch = 'i'; break;
                case 'ó': case 'ò': case 'ö': case 'ô': ch = 'o'; break;
                case 'ú': case 'ù': case 'ü': case 'û': ch = 'u'; break;
                case 'ñ': ch = 'n'; break;
                default: break;
            }
            b.append(ch);
        }
        return b.toString();
    }

    /** Distancia de edicion menor o igual que 1 (letra de mas, de menos o cambiada). */
    private static boolean within1(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0, j = 0, edits = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (la > lb) i++;
            else if (lb > la) j++;
            else { i++; j++; }
        }
        return edits + (la - i) + (lb - j) <= 1;
    }

    /** Lee emoji_names.txt una sola vez: palabras normalizadas de nombres y claves en es e ingles. */
    private static synchronized List<Named> loadNames(Context c) {
        if (named != null) return named;
        List<Named> out = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getResources().openRawResource(R.raw.emoji_names), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] f = line.split("\t");
                    if (f.length < 2) continue;
                    List<String> names = new ArrayList<>();
                    List<String> words = new ArrayList<>();
                    for (int k = 1; k < f.length; k++) {
                        int bar = f[k].indexOf('|');                     // el nombre va antes de la primera barra
                        String name = bar < 0 ? f[k] : f[k].substring(0, bar);
                        for (String token : normalize(name).split("[^\\p{L}\\p{N}]+")) {
                            if (!token.isEmpty() && !names.contains(token)) names.add(token);
                        }
                        for (String token : normalize(f[k]).split("[^\\p{L}\\p{N}]+")) {
                            if (!token.isEmpty() && !words.contains(token)) words.add(token);
                        }
                    }
                    out.add(new Named(f[0], names.toArray(new String[0]), words.toArray(new String[0])));
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // sin nombres no hay busqueda
        }
        named = out;
        return out;
    }

    // ------------------------------------------------------------------ datos y filas

    private static void addChunks(List<Row> into, String group, List<String> emoji) {
        for (int i = 0; i < emoji.size(); i += COLUMNS) {
            into.add(new Row(group, null, null, new ArrayList<>(emoji.subList(i, Math.min(emoji.size(), i + COLUMNS)))));
        }
    }

    private static int groupIndex(String group) {
        for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i].equals(group)) return i;
        return -1;
    }

    private void highlight(int g) {
        if (g < 0 || g == currentTab) return;
        currentTab = g;
        for (int i = 0; i < tabs.length; i++) styleTab(tabs[i], i == currentTab);
        final TextView tab = tabs[g];
        tabScroll.post(new Runnable() {
            @Override public void run() {
                int target = tab.getLeft() - (tabScroll.getWidth() - tab.getWidth()) / 2;
                tabScroll.smoothScrollTo(Math.max(0, target), 0);
            }
        });
    }

    private TextView tab(Context c, String icon) {
        TextView t = new TextView(c);
        t.setText(icon);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void styleTab(TextView t, boolean selected) {
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(theme.specialBg);
            bg.setCornerRadius(theme.radiusDp * dp);
            if (theme.keyStroke != 0) bg.setStroke(Math.max(1, px(1)), theme.keyStroke);
            t.setBackground(bg);
        } else {
            t.setBackground(null);
        }
    }

    private ListView plainList(Context c) {
        ListView l = new ListView(c);
        l.setDivider(null);
        l.setDividerHeight(0);
        l.setVerticalScrollBarEnabled(false);
        l.setSelector(new ColorDrawable(0));
        l.setCacheColorHint(0);
        return l;
    }

    /** Lee emoji.txt una sola vez: una linea por subgrupo (grupo, subgrupo, nombre es, nombre en, emojis). */
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

    /** Adaptador de filas (cabecera, mensaje o emojis) sobre una lista concreta. */
    private final class RowsAdapter extends BaseAdapter {
        private final List<Row> source;
        RowsAdapter(List<Row> source) { this.source = source; }
        @Override public int getCount() { return source.size(); }
        @Override public Object getItem(int i) { return source.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return false; }
        @Override public int getItemViewType(int i) {
            Row r = source.get(i);
            return r.header != null ? 0 : r.message != null ? 1 : 2;
        }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            Row r = source.get(i);
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
    }

    private TextView textRow(int sp, boolean header) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (header) {
            t.setAllCaps(true);
            t.setLetterSpacing(0.06f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
