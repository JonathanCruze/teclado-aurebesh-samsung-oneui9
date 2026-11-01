package com.aurebesh.teclado;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renderiza el teclado (todos los temas), la burbuja, los simbolos, la app y el icono a PNG,
 * y comprueba que teclear "hola" produce "hola".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTest {

    private static final int W = 1080;
    private static final int PHONE_H = 2340;

    private Bitmap render(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, W, v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(W, v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    /** Renderiza con un alto fijo (bandejas que ocupan lo que ocupa el teclado). */
    private Bitmap renderExact(View v, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, h);
        Bitmap b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    private static void check(String got, String want, String what) {
        if (got == null ? want != null : !got.equals(want)) throw new AssertionError(what + ": " + got + " (esperado " + want + ")");
    }

    private void save(Bitmap b, String name) throws Exception {
        File out = new File(System.getProperty("shots.dir", "shots"), name);
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("SHOT " + out.getAbsolutePath() + " " + b.getWidth() + "x" + b.getHeight());
    }

    private void down(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
    }

    private void up(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        v.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0));
    }

    private void tap(View v, float x, float y) {
        down(v, x, y);
        up(v, x, y);
    }

    @Test
    public void screenshots() throws Exception {
        Application app = (Application) RuntimeEnvironment.getApplication();
        float dp = app.getResources().getDisplayMetrics().density;

        AurebeshKeyboardView kb = new AurebeshKeyboardView(app);
        final StringBuilder typed = new StringBuilder();
        kb.setListener(new AurebeshKeyboardView.Listener() {
            public void onText(String s) { typed.append(s); }
            public void onDelete() { if (typed.length() > 0) typed.setLength(typed.length() - 1); }
            public void onEnter() { typed.append("\n"); }
            public void onSwitchKeyboard() {}
            public void onSwitchLanguage() {}
            public void onSuggestion(String w) { typed.append("[").append(w).append("]"); }
            public void onToolbar(int t) { typed.append("<tool").append(t).append(">"); }
        });
        kb.setEnterAction(4);

        // Por tema: teclado de letras, pantalla ?123 y barra de traduccion
        for (Themes.Theme t : Themes.ALL) {
            kb.applyPrefs(false, false, false, true, t.id);
            save(render(kb), "tema_" + t.id + ".png");
            float bottomY = kb.getMeasuredHeight() - 30 * dp;
            tap(kb, 30 * dp, bottomY);                 // ?123
            save(render(kb), "simbolos_" + t.id + ".png");
            tap(kb, 30 * dp, bottomY);                 // abc
            kb.applyPrefs(false, false, true, true, t.id);
            kb.setStripText("que la fuerza te acompañe");
            save(render(kb), "barra_" + t.id + ".png");
            kb.setStripText("");
        }
        // Datapad con barra y letras latinas de ayuda
        kb.applyPrefs(true, false, true, true, Themes.DATAPAD.id);
        kb.setStripText("que la fuerza te acompañe");
        save(render(kb), "barra_datapad_pistas.png");
        kb.setStripText("");

        // Burbuja al mantener la "r"
        for (Themes.Theme t : Themes.ALL) {
            kb.applyPrefs(false, false, false, true, t.id);
            render(kb);
            AurebeshKeyboardView.Key r = kb.keyFor("r");
            down(kb, r.rect.centerX(), r.rect.centerY());
            save(render(kb), "burbuja_" + t.id + ".png");
            up(kb, r.rect.centerX(), r.rect.centerY());
            typed.setLength(0);
        }

        // Sin fila de numeros
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, false);
        save(render(kb), "sin_fila_numeros.png");
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true);

        // Menu de alternativas: sin fila de numeros, mantener "q" da 1 y mantener "e" ofrece 3 y acentos
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, false, false, 1f);
        render(kb);
        typed.setLength(0);
        AurebeshKeyboardView.Key q = kb.keyFor("q");
        down(kb, q.rect.centerX(), q.rect.centerY());
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(450));
        save(render(kb), "alternativas_q.png");
        up(kb, q.rect.centerX(), q.rect.centerY());
        AurebeshKeyboardView.Key e = kb.keyFor("e");
        down(kb, e.rect.centerX(), e.rect.centerY());
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(450));
        android.graphics.RectF second = kb.popupCellRect(1);
        long t = SystemClock.uptimeMillis();
        kb.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, second.centerX(), second.centerY(), 0));
        save(render(kb), "alternativas_e.png");
        up(kb, second.centerX(), second.centerY());
        System.out.println("ALTS=" + typed);
        if (!typed.toString().equals("1é")) throw new AssertionError("alternativas: " + typed);
        typed.setLength(0);

        // Tamanos compacto y extra; barra con signos de apertura
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 0.85f);
        save(render(kb), "tamano_compacto.png");
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1.3f);
        save(render(kb), "tamano_extra.png");
        kb.applyPrefs(false, false, true, true, Themes.ESTELAR.id, true, false, 1f);
        kb.setStripText("¿qué tal? ¡hola!");
        save(render(kb), "barra_signos.png");
        kb.setStripText("");
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id, true, false, 1f);

        // Apaisado: filas mas bajas
        RuntimeEnvironment.setQualifiers("es-rES-w914dp-h411dp-land-xxhdpi");
        AurebeshKeyboardView land = new AurebeshKeyboardView(app);
        land.applyPrefs(false, false, false, true, Themes.ESTELAR.id);
        land.setEnterAction(4);
        land.measure(View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        land.layout(0, 0, 2340, land.getMeasuredHeight());
        Bitmap lb = Bitmap.createBitmap(2340, land.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        land.draw(new Canvas(lb));
        save(lb, "apaisado_estelar.png");
        RuntimeEnvironment.setQualifiers("es-rES-w411dp-h914dp-xxhdpi");

        // Distribucion inglesa con dos idiomas activos: globo y etiqueta de idioma en el espacio
        kb.setLanguage("en");
        kb.setLanguageSwitch(true);
        kb.setSpaceLabel("English");
        save(render(kb), "teclado_ingles.png");
        kb.setLanguage("es");
        kb.setLanguageSwitch(false);
        kb.setSpaceLabel(null);

        // Barra de sugerencias del corrector (en Aurebesh): lo escrito entre comillas, la correccion resaltada
        kb.setSuggestionBar(true);
        kb.setSuggestions(java.util.Arrays.asList("\"fuersa\"", "fuerza", "fuera"), "fuerza");
        save(render(kb), "sugerencias.png");
        typed.setLength(0);
        tap(kb, 40 * dp, 20 * dp);
        if (!typed.toString().equals("[\"fuersa\"]")) throw new AssertionError("sugerencia: " + typed);
        kb.setSuggestions(null, null);
        kb.setSuggestionBar(false);

        // Barra de herramientas: emojis y portapapeles a la derecha
        render(kb);
        typed.setLength(0);
        tap(kb, W - 72 * dp, 20 * dp);
        tap(kb, W - 28 * dp, 20 * dp);
        if (!typed.toString().equals("<tool1><tool2>")) throw new AssertionError("barra: " + typed);
        typed.setLength(0);

        // Modo a una mano (derecha)
        kb.setHandMode(2);
        save(render(kb), "una_mano.png");
        kb.setHandMode(0);

        // Bandejas: historial del portapapeles (claro y noche) y acciones de texto
        int trayH = kb.getMeasuredHeight();
        KeyboardTray tray = new KeyboardTray(app);
        tray.setHost(new KeyboardTray.Host() {
            public void onTrayPick(KeyboardTray.Item item) { typed.append("{").append(item.title).append("}"); }
            public void onTrayRemove(KeyboardTray.Item item) {}
            public void onTrayClear() {}
            public void onTrayClose() {}
        });
        java.util.List<KeyboardTray.Item> clips = new java.util.ArrayList<>();
        clips.add(new KeyboardTray.Item("Que la Fuerza te acompañe", "1"));
        clips.add(new KeyboardTray.Item("Coordenadas del hangar 94: Mos Eisley, Tatooine. Llegamos en cinco minutos, no despeguen sin nosotros.", "2"));
        clips.add(new KeyboardTray.Item("https://ejemplo.com/holocron", "3"));
        tray.applyTheme(Themes.ESTELAR);
        tray.show("Portapapeles", clips, "Copia un texto y aparecerá aquí.");
        save(renderExact(tray, trayH), "portapapeles_estelar.png");
        tray.applyTheme(Themes.NOCHE);
        tray.show("Portapapeles", clips, "Copia un texto y aparecerá aquí.");
        save(renderExact(tray, trayH), "portapapeles_noche.png");
        tray.applyTheme(Themes.ESTELAR);
        tray.show("Portapapeles", new java.util.ArrayList<KeyboardTray.Item>(), "Copia un texto y aparecerá aquí. Toca uno para pegarlo.");
        save(renderExact(tray, trayH), "portapapeles_vacio.png");
        // Panel de emojis: recientes, categorias, ABC / espacio / borrar
        EmojiTray emojis = new EmojiTray(app);
        emojis.setHost(new EmojiTray.Host() {
            public void onEmoji(String e) { typed.append(e); }
            public void onEmojiDelete() { typed.append("<del>"); }
            public void onEmojiSpace() { typed.append(" "); }
            public void onEmojiClose() { typed.append("<abc>"); }
        });
        emojis.applyTheme(Themes.ESTELAR);
        emojis.show(java.util.Arrays.asList("\uD83D\uDE00", "\uD83D\uDE80", "\u2764\uFE0F", "\uD83D\uDC4D"));
        save(renderExact(emojis, trayH), "emojis_estelar.png");
        emojis.applyTheme(Themes.NOCHE);
        emojis.show(new java.util.ArrayList<String>());
        save(renderExact(emojis, trayH), "emojis_noche.png");

        // Diccionarios integrados: una edicion, tildes, completado, contracciones inglesas
        Dictionary es = Dictionary.get(app, "es");
        check(es.suggest("holq").correction, "hola", "holq");
        check(es.suggest("cancion").correction, "canción", "cancion");
        check(es.suggest("mas").correction, "más", "mas");
        check(es.suggest("tambien").correction, "también", "tambien");
        check(es.suggest("hola").correction, null, "hola");
        check(es.suggest("esta").correction, null, "esta");
        if (!es.suggest("hol").suggestions.contains("hola")) throw new AssertionError("completar hol: " + es.suggest("hol").suggestions);
        check(es.suggest("fuersa").correction, "fuerza", "fuersa");
        check(es.suggest("qiero").correction, "quiero", "qiero");
        check(es.suggest("holla").correction, "hola", "holla");
        check(es.suggest("mañana").correction, null, "mañana");
        Dictionary en = Dictionary.get(app, "en");
        check(en.suggest("teh").correction, "the", "teh");
        check(en.suggest("dont").correction, "don't", "dont");
        check(en.suggest("i").correction, "I", "i");
        check(en.suggest("don't").correction, null, "don't");
        check(en.suggest("hello").correction, null, "hello");
        System.out.println("DICT ok es=" + es.size() + " en=" + en.size() + " fuersa=" + es.suggest("fuersa").suggestions
                + " qiero=" + es.suggest("qiero").suggestions + " helo=" + en.suggest("helo").suggestions);

        // Teclear tocando las teclas
        kb.applyPrefs(false, false, false, true, Themes.ESTELAR.id);
        render(kb);
        typed.setLength(0);
        for (String ch : new String[]{"h", "o", "l", "a"}) {
            AurebeshKeyboardView.Key k = kb.keyFor(ch);
            tap(kb, k.rect.centerX(), k.rect.centerY());
        }
        System.out.println("TYPED=" + typed);
        if (!typed.toString().equals("hola")) throw new AssertionError("typed: " + typed);

        // La n con virgulilla y las vocales con tilde conservan su glifo; "ch" sigue siendo digrafo
        java.util.List<Glyphs.Token> tk = Glyphs.tokenize("Año chico, qué", true);
        String[] expect = {"a", "ñ", "o", null, "ch", "i", "c", "o", ",", null, "q", "u", "é"};
        if (tk.size() != expect.length) throw new AssertionError("tokens: " + tk.size());
        for (int i = 0; i < expect.length; i++) {
            if (expect[i] == null ? tk.get(i).glyph != null : !expect[i].equals(tk.get(i).glyph))
                throw new AssertionError("token " + i + ": " + tk.get(i).glyph);
        }
        java.util.List<Glyphs.Token> tk2 = Glyphs.tokenize("¿Qué?", true);
        String[] expect2 = {"¿", "q", "u", "é", "?"};
        for (int i = 0; i < expect2.length; i++) {
            if (!expect2[i].equals(tk2.get(i).glyph)) throw new AssertionError("token2 " + i + ": " + tk2.get(i).glyph);
        }
        for (String sym : new String[]{"+", "=", "#", "*", "%", "@", "&", "$"}) {
            if (!Glyphs.has(sym)) throw new AssertionError("sin glifo: " + sym);
        }
        System.out.println("TOKENS ok");

        // Pantalla de la app en claro y en noche: pagina completa y primera pantalla (Samsung One UI 9.0)
        ShadowBuild.setManufacturer("samsung");
        DeviceInfo.semOverride = 180000;
        Bitmap light = renderActivity();
        save(light, "app_claro.png");
        save(Bitmap.createBitmap(light, 0, 0, W, PHONE_H), "app_claro_primera.png");
        RuntimeEnvironment.setQualifiers("+night");
        Bitmap night = renderActivity();
        save(night, "app_noche.png");
        save(Bitmap.createBitmap(night, 0, 0, W, PHONE_H), "app_noche_primera.png");
        RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h914dp-xxhdpi");
        Bitmap english = renderActivity();
        save(english, "app_ingles.png");
        RuntimeEnvironment.setQualifiers("es-rES-w411dp-h914dp-xxhdpi");

        // En otro dispositivo la app avisa de que es experimental
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setModel("Pixel 9");
        DeviceInfo.semOverride = 0;
        save(Bitmap.createBitmap(renderActivity(), 0, 0, W, PHONE_H), "app_experimental.png");
        DeviceInfo.semOverride = null;

        // Icono adaptativo, compuesto como lo hace Android: capa de 108 dp vista por una
        // ventana de 72 dp (escala 1.5) con mascara circular.
        int size = 432;
        Bitmap ib = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(ib);
        android.graphics.Path mask = new android.graphics.Path();
        mask.addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW);
        ic.clipPath(mask);
        ic.drawColor(app.getColor(R.color.ic_launcher_background));
        android.graphics.drawable.Drawable fg = app.getDrawable(R.drawable.ic_launcher_foreground);
        int layer = Math.round(size * 1.5f);
        int off = (size - layer) / 2;
        fg.setBounds(off, off, off + layer, off + layer);
        fg.draw(ic);
        save(ib, "icono.png");
    }

    /** Renderiza MainActivity completa (todo el contenido desplazable), al menos una pantalla. */
    private Bitmap renderActivity() {
        MainActivity act = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = act.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(PHONE_H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, PHONE_H);
        android.widget.ScrollView scroll = (android.widget.ScrollView)
                ((android.view.ViewGroup) act.findViewById(android.R.id.content)).getChildAt(0);
        int full = Math.max(PHONE_H, scroll.getChildAt(0).getHeight());
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(full, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, full);
        Bitmap b = Bitmap.createBitmap(W, full, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }
}
