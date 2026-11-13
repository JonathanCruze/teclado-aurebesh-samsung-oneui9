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

    private static String hex(int c) { return String.format("#%08X", c); }

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
            public void onGlide(String best, java.util.List<String> alts) { typed.append("<glide:").append(best).append(">"); }
            public void onToolbar(int t) { typed.append("<tool").append(t).append(">"); }
            public void onHandSide(int m) { typed.append("<side").append(m).append(">"); }
            public void onPasteSuggestion() { typed.append("<paste>"); }
            public void onCursorMove(int steps, boolean select) { typed.append("<cur").append(steps).append(select ? "s" : "").append(">"); }
            public void onDeleteWord() { typed.append("<delword>"); }
            public void onHide() { typed.append("<hide>"); }
            public void onResize(int heightPct, int bottomDp) { typed.append("<resize").append(heightPct).append(",").append(bottomDp).append(">"); }
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
        tap(kb, 28 * dp, 20 * dp);                     // boton de inteligencia a la izquierda
        if (!typed.toString().equals("<tool5>")) throw new AssertionError("inteligencia: " + typed);
        typed.setLength(0);
        tap(kb, 76 * dp, 20 * dp);                     // primer chip, tras el boton
        if (!typed.toString().equals("[\"fuersa\"]")) throw new AssertionError("sugerencia: " + typed);
        kb.setSuggestions(null, null);
        kb.setSuggestionBar(false);

        // Barra de herramientas: de fabrica emojis, portapapeles, una mano, edicion, tamano y, siempre al final, "..."
        render(kb);
        typed.setLength(0);
        tap(kb, W - 248 * dp, 20 * dp);
        tap(kb, W - 204 * dp, 20 * dp);
        tap(kb, W - 28 * dp, 20 * dp);
        if (!typed.toString().equals("<tool1><tool2><tool14>")) throw new AssertionError("barra: " + typed);
        typed.setLength(0);
        // La cinta no lleva el margen de las teclas (el 2,1 % del ancho, nunca menos de 8 dp: a 360 dp son 8 dp justos):
        // a 8 dp del borde aun se tocan el boton de inteligencia y el icono de una mano
        float km0 = kb.keyMargin();
        if (km0 < 8 * dp - 0.01f) throw new AssertionError("margen de teclas menor que 8 dp: " + km0);
        tap(kb, 8 * dp, 20 * dp);
        tap(kb, W - 8 * dp, 20 * dp);
        if (!typed.toString().equals("<tool5><tool14>")) throw new AssertionError("cinta al borde: " + typed);   // ✦ y "..." en los bordes
        typed.setLength(0);
        float modeY = kb.getMeasuredHeight() - 30 * dp;
        tap(kb, 30 * dp, modeY);                       // ?123
        render(kb);
        save(render(kb), "simbolos_engrane.png");
        tap(kb, W - 72 * dp, 20 * dp);                 // el engrane va justo antes del "..." (ultimo icono de la cinta)
        if (!typed.toString().equals("<tool3>")) throw new AssertionError("engrane: " + typed);
        typed.setLength(0);
        // Signos como en Samsung Keyboard: , ; : ? en la cuarta fila y . en la fila inferior; 1/2 abre la segunda pagina
        AurebeshKeyboardView.Key comma = kb.keyFor(","), semi = kb.keyFor(";"), colon = kb.keyFor(":"), quest = kb.keyFor("?"), dot = kb.keyFor(".");
        AurebeshKeyboardView.Key page = kb.keyFor(AurebeshKeyboardView.CODE_PAGE);
        if (comma == null || semi == null || colon == null || quest == null || dot == null || page == null) throw new AssertionError("signos");
        if (!(comma.rect.top == page.rect.top && semi.rect.top == page.rect.top && colon.rect.top == page.rect.top && quest.rect.top == page.rect.top))
            throw new AssertionError("fila de signos");
        if (!(dot.rect.top > page.rect.top && comma.rect.left < quest.rect.left)) throw new AssertionError("orden de signos");
        for (String ch : new String[]{",", ";", ":", "?", "."}) { AurebeshKeyboardView.Key k = kb.keyFor(ch); tap(kb, k.rect.centerX(), k.rect.centerY()); }
        if (!typed.toString().equals(",;:?.")) throw new AssertionError("signos tecleados: " + typed);
        typed.setLength(0);
        tap(kb, page.rect.centerX(), page.rect.centerY());   // 1/2 -> 2/2
        render(kb);
        save(render(kb), "simbolos_2.png");
        if (kb.keyFor("ch") == null || kb.keyFor("\u00bf") == null || kb.keyFor("\u20ac") == null) throw new AssertionError("pagina 2");
        AurebeshKeyboardView.Key page2 = kb.keyFor(AurebeshKeyboardView.CODE_PAGE);
        tap(kb, page2.rect.centerX(), page2.rect.centerY());   // 2/2 -> 1/2
        render(kb);
        if (kb.keyFor("ch") != null) throw new AssertionError("vuelta a 1/2");
        tap(kb, 30 * dp, modeY);                       // abc
        render(kb);

        // Modo a una mano (derecha): boton de la barra activo y flecha en el margen para cambiar de lado
        kb.setHandMode(2);
        save(render(kb), "una_mano.png");
        // El margen se aplica dentro de la caja del 78 % y la cinta acompana a la caja sin ese margen
        float km1 = kb.keyMargin();
        assertNear("una mano: q tras el margen de la caja", kb.keyFor("q").rect.left, 0.22f * W + km1);
        assertNear("una mano: p al margen derecho", kb.keyFor("p").rect.right, W - km1);
        typed.setLength(0);
        tap(kb, 0.22f * W + 8 * dp, 20 * dp);
        if (!typed.toString().equals("<tool5>")) throw new AssertionError("una mano, inteligencia: " + typed);
        kb.setHandMode(1);
        render(kb);
        assertNear("una mano izq.: q al margen", kb.keyFor("q").rect.left, km1);
        assertNear("una mano izq.: p al borde de la caja", kb.keyFor("p").rect.right, 0.78f * W - km1);
        kb.setHandMode(2);
        render(kb);
        typed.setLength(0);
        tap(kb, 0.11f * W, kb.getMeasuredHeight() / 2f + 10 * dp);
        if (!typed.toString().equals("<side1>")) throw new AssertionError("lado: " + typed);
        typed.setLength(0);
        kb.setHandMode(0);

        // Teclado dividido (Fold desplegado): dos mitades con hueco y dos espacios; se teclea igual
        kb.setSplitMode(1);
        save(render(kb), "dividido.png");
        int spaces = 0;
        for (String o : new String[]{"q", "t", "y", "p", "a", "g", "h", "z", "v", "b", "m"}) if (kb.keyFor(o) == null) throw new AssertionError("dividido sin " + o);
        if (kb.keyFor("t").rect.right > kb.keyFor("y").rect.left - 60 * dp) throw new AssertionError("hueco central");
        // Geometria de Samsung Keyboard en el Fold: a-g media tecla hacia el hueco, h-ñ media tecla hacia el hueco,
        // z bajo s, b bajo j, mayusculas pegada al borde izquierdo y borrar al derecho (1,5 teclas)
        float u = kb.keyFor("q").rect.width();
        assertNear("a escalonada", kb.keyFor("a").rect.left, kb.keyFor("q").rect.left + u / 2);
        assertNear("h escalonada", kb.keyFor("h").rect.left, kb.keyFor("y").rect.left - u / 2);
        assertNear("z bajo s", kb.keyFor("z").rect.left, kb.keyFor("s").rect.left);
        assertNear("b bajo j", kb.keyFor("b").rect.left, kb.keyFor("j").rect.left);
        assertNear("mayusculas al borde", kb.keyFor(AurebeshKeyboardView.CODE_SHIFT).rect.left, kb.keyFor("q").rect.left);
        assertNear("mayusculas 1,5", kb.keyFor(AurebeshKeyboardView.CODE_SHIFT).rect.width(), 1.5f * u);
        assertNear("borrar al borde", kb.keyFor(AurebeshKeyboardView.CODE_DELETE).rect.right, kb.keyFor("p").rect.right);
        assertNear("ñ bajo el hueco", kb.keyFor("ñ").rect.right, kb.keyFor("p").rect.right - u / 2);
        assertNear("intro al borde", kb.keyFor(AurebeshKeyboardView.CODE_ENTER).rect.right, kb.keyFor("p").rect.right);
        assertNear("?123 al borde", kb.keyFor(AurebeshKeyboardView.CODE_MODE).rect.left, kb.keyFor("q").rect.left);
        if (kb.keyFor(".").rect.left < kb.keyFor("y").rect.left) throw new AssertionError("punto en la mitad derecha");
        // Margen exterior de las dos mitades: la primera tecla empieza en keyMargin() y la ultima acaba en W - keyMargin()
        float km = kb.keyMargin();
        assertNear("margen del 2,1 % del ancho (minimo 8 dp)", km, Math.max(8 * dp, 0.021f * W));
        assertNear("q al margen", kb.keyFor("q").rect.left, km);
        if (kb.keyFor("1") != null) assertNear("1 al margen", kb.keyFor("1").rect.left, km);
        assertNear("?123 al margen", kb.keyFor(AurebeshKeyboardView.CODE_MODE).rect.left, km);
        assertNear("p al margen derecho", kb.keyFor("p").rect.right, W - km);
        assertNear("borrar al margen derecho", kb.keyFor(AurebeshKeyboardView.CODE_DELETE).rect.right, W - km);
        assertNear("intro al margen derecho", kb.keyFor(AurebeshKeyboardView.CODE_ENTER).rect.right, W - km);
        typed.setLength(0);
        for (String ch : new String[]{"h", "o", "l", "a"}) { AurebeshKeyboardView.Key k = kb.keyFor(ch); tap(kb, k.rect.centerX(), k.rect.centerY()); }
        if (!typed.toString().equals("hola")) throw new AssertionError("dividido teclea: " + typed);
        typed.setLength(0);
        AurebeshKeyboardView wide = new AurebeshKeyboardView(app);
        wide.applyPrefs(false, false, false, true, Themes.NOCHE.id);
        wide.setEnterAction(4);
        wide.setSplitMode(1);
        wide.measure(View.MeasureSpec.makeMeasureSpec(2176, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        wide.layout(0, 0, 2176, wide.getMeasuredHeight());
        Bitmap wb = Bitmap.createBitmap(2176, wide.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        wide.draw(new Canvas(wb));
        save(wb, "dividido_fold.png");
        // Fold (725 dp): margen del 5 % como Samsung, mitades del 37,5 % del ancho y hueco central del 15 %
        float mw = wide.keyMargin();
        assertNear("Fold: margen = 5 % del ancho", mw, 2176 * 0.05f);
        if (!(mw > 8 * dp)) throw new AssertionError("Fold: el 5 % deberia superar los 8 dp: " + mw);
        assertNear("Fold: q al margen", wide.keyFor("q").rect.left, mw);
        assertNear("Fold: mayusculas al margen", wide.keyFor(AurebeshKeyboardView.CODE_SHIFT).rect.left, mw);
        assertNear("Fold: p al margen derecho", wide.keyFor("p").rect.right, 2176 - mw);
        assertNear("Fold: borrar al margen derecho", wide.keyFor(AurebeshKeyboardView.CODE_DELETE).rect.right, 2176 - mw);
        assertNear("Fold: borde interior izquierdo al 42,5 %", wide.keyFor("g").rect.right, 2176 * 0.425f);
        assertNear("Fold: borde interior derecho al 57,5 %", wide.keyFor("h").rect.left, 2176 * 0.575f);
        kb.setSplitMode(2);
        render(kb);
        if (kb.isSplit()) throw new AssertionError("dividido apagado");
        kb.setSplitMode(0);
        render(kb);
        // Pantalla estrecha (720 px): el margen no baja de 8 dp (el 2,1 % serian 5 dp)
        AurebeshKeyboardView narrow = new AurebeshKeyboardView(app);
        narrow.applyPrefs(false, false, false, true, Themes.ESTELAR.id);
        narrow.setEnterAction(4);
        narrow.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        narrow.layout(0, 0, 720, narrow.getMeasuredHeight());
        assertNear("margen minimo de 8 dp", narrow.keyMargin(), 8 * dp);
        assertNear("estrecha: q al margen", narrow.keyFor("q").rect.left, 8 * dp);
        assertNear("estrecha: p al margen derecho", narrow.keyFor("p").rect.right, 720 - 8 * dp);

        // Chips de emoji en la barra de sugerencias; los iconos se esconden tras "..." mientras hay chips
        kb.setSuggestions(java.util.Arrays.asList("perro", "perros", "\uD83D\uDC36", "\uD83D\uDC15"), null);
        save(render(kb), "sugerencias_emoji.png");
        typed.setLength(0);
        tap(kb, W - 28 * dp, 20 * dp);                 // "..." -> aparecen los iconos
        render(kb);
        tap(kb, W - 248 * dp, 20 * dp);                // emojis: el primero de la cinta de fabrica
        if (!typed.toString().equals("<tool1>")) throw new AssertionError("mas: " + typed);
        typed.setLength(0);
        kb.setSuggestions(null, null);

        // Chip "Pegar" con lo recien copiado: texto e imagen
        kb.setPasteSuggestion(null, "Que la Fuerza te acompañe, siempre", "Pegar");
        save(render(kb), "pegar_texto.png");
        tap(kb, 90 * dp, 20 * dp);
        if (!typed.toString().equals("<paste>")) throw new AssertionError("pegar: " + typed);
        typed.setLength(0);
        Bitmap thumb = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
        thumb.eraseColor(0xFF7FD3F0);
        kb.setPasteSuggestion(thumb, null, "Pegar");
        save(render(kb), "pegar_imagen.png");
        kb.setPasteSuggestion(null, null, null);
        java.util.List<String> dogs = EmojiSuggest.lookup(app, "perro", 3);
        if (!dogs.contains("\uD83D\uDC36") && !dogs.contains("\uD83D\uDC15")) throw new AssertionError("emoji perro: " + dogs);
        if (!EmojiSuggest.lookup(app, "pizza", 2).contains("\uD83C\uDF55")) throw new AssertionError("emoji pizza");
        if (!EmojiSuggest.lookup(app, "dog", 3).contains("\uD83D\uDC15") && !EmojiSuggest.lookup(app, "dog", 3).contains("\uD83D\uDC36")) throw new AssertionError("emoji dog: " + EmojiSuggest.lookup(app, "dog", 3));
        System.out.println("EMOJISUG " + dogs.size() + " " + EmojiSuggest.lookup(app, "corazon", 3).size());

        // Bandejas: historial del portapapeles (claro y noche) y acciones de texto
        int trayH = kb.getMeasuredHeight();
        KeyboardTray tray = new KeyboardTray(app);
        tray.setHost(new KeyboardTray.Host() {
            public void onTrayPick(ClipHistory.Entry entry, String text) { typed.append("{").append(text).append("}"); }
            public void onTrayRemove(ClipHistory.Entry entry) {}
            public void onTrayClear() {}
            public void onTrayClose() {}
            public void onTrayPin(ClipHistory.Entry entry, boolean pinned) {}
        });
        File clipsDir = new File(app.getFilesDir(), ClipHistory.DIR);
        clipsDir.mkdirs();
        File png = new File(clipsDir, "prueba.png");
        Bitmap sample = Bitmap.createBitmap(360, 200, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(sample);
        sc.drawColor(0xFF1B2838);
        android.graphics.Paint sp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        sp.setColor(0xFF7FD3F0);
        sc.drawCircle(180, 100, 70, sp);
        try (FileOutputStream fos = new FileOutputStream(png)) { sample.compress(Bitmap.CompressFormat.PNG, 100, fos); }
        java.util.List<ClipHistory.Entry> clips = new java.util.ArrayList<>();
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.TEXT, "Llámame al 55 1234 5678 o escribe a hola@ejemplo.com", null, null));
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.IMAGE, null, png.getAbsolutePath(), "image/png"));
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.TEXT, "Coordenadas del hangar 94: Mos Eisley, Tatooine. Llegamos en cinco minutos, no despeguen sin nosotros.", null, null));
        clips.add(new ClipHistory.Entry(ClipHistory.Entry.TEXT, "Mira https://ejemplo.com/holocron cuando puedas", null, null));
        if (ClipHistory.thumbnail(clips.get(1), 320) == null) throw new AssertionError("miniatura del portapapeles");
        tray.applyTheme(Themes.ESTELAR);
        tray.show("Portapapeles", clips, "Copia un texto y aparecerá aquí.");
        save(renderExact(tray, trayH), "portapapeles_estelar.png");
        tray.applyTheme(Themes.NOCHE);
        tray.show("Portapapeles", clips, "Copia un texto y aparecerá aquí.");
        save(renderExact(tray, trayH), "portapapeles_noche.png");
        tray.applyTheme(Themes.ESTELAR);
        tray.show("Portapapeles", new java.util.ArrayList<ClipHistory.Entry>(), "Copia un texto y aparecerá aquí. Toca uno para pegarlo.");
        save(renderExact(tray, trayH), "portapapeles_vacio.png");
        check(ClipHistory.phoneIn("Llámame al 55 1234 5678 mañana"), "55 1234 5678", "telefono");
        check(ClipHistory.phoneIn("55 1234 5678"), null, "telefono solo");
        check(ClipHistory.emailIn("escribe a hola@ejemplo.com ya"), "hola@ejemplo.com", "correo");
        check(ClipHistory.linkIn("mira https://ejemplo.com/x ahora"), "https://ejemplo.com/x", "enlace");
        System.out.println("CLIPS ok");

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
        emojis.applyTheme(Themes.ESTELAR);
        emojis.show(new java.util.ArrayList<String>());
        emojis.openSearch("cora");
        save(renderExact(emojis, trayH), "emojis_busqueda.png");
        emojis.closeSearch();
        ShadowLooper.shadowMainLooper().idle();
        if (!EmojiTray.search(app, "corazon", 10).contains("\u2764\uFE0F")) throw new AssertionError("buscar corazon: " + EmojiTray.search(app, "corazon", 10));
        if (!EmojiTray.search(app, "mexico", 10).contains("\uD83C\uDDF2\uD83C\uDDFD")) throw new AssertionError("buscar mexico: " + EmojiTray.search(app, "mexico", 10));
        if (!EmojiTray.search(app, "pizza", 10).contains("\uD83C\uDF55")) throw new AssertionError("buscar pizza");
        if (!EmojiTray.search(app, "laugh", 10).contains("\uD83D\uDE02")) throw new AssertionError("buscar laugh: " + EmojiTray.search(app, "laugh", 10));
        System.out.println("EMOJI ok " + EmojiTray.search(app, "corazon", 6) + " " + EmojiTray.search(app, "piza", 4));

        // Panel de inteligencia: sin modelo, acciones, tonos y redactor (con un archivo de modelo simulado)
        final String[] field = {"hola, esto es un texto de prueva con algunos herrores de ortografia. lo escribo rapido sin tildes."};
        AiTray.Host aiHost = new AiTray.Host() {
            public CharSequence currentText() { return field[0]; }
            public CharSequence selectedText() { return null; }
            public void replaceText(CharSequence t) {}
            public void insertText(CharSequence t) {}
            public void copyText(CharSequence t) {}
            public void closeTray() {}
            public void openSettings() {}
        };
        AiTray aiTray = new AiTray(app);
        aiTray.setHost(aiHost);
        aiTray.setLanguage("es");
        aiTray.applyTheme(Themes.ESTELAR);
        aiTray.show();
        if (aiTray.state() != AiTray.State.NO_MODEL) throw new AssertionError("ia sin modelo: " + aiTray.state());
        save(renderExact(aiTray, trayH), "ia_sin_modelo.png");
        File fakeModel = new File(AiModels.dir(app), AiModels.DEFAULT.file);
        try (FileOutputStream f = new FileOutputStream(fakeModel)) { f.write("GGUF".getBytes("UTF-8")); }
        AiModels.select(app, fakeModel.getName());
        aiTray.show();
        if (aiTray.state() != AiTray.State.IDLE) throw new AssertionError("ia acciones: " + aiTray.state());
        save(renderExact(aiTray, trayH), "ia_acciones.png");
        aiTray.applyTheme(Themes.DATAPAD);
        aiTray.showTones();
        save(renderExact(aiTray, trayH), "ia_tonos_datapad.png");
        aiTray.applyTheme(Themes.NOCHE);
        aiTray.showPrompt(true);
        save(renderExact(aiTray, trayH), "ia_redactor_noche.png");
        aiTray.release();
        ShadowLooper.shadowMainLooper().idle();
        System.out.println("IA ok");

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
        check(es.suggest("nesecito").correction, "necesito", "nesecito (dos ediciones)");
        check(es.suggest("probalemnte").correction, "probablemente", "probalemnte (dos ediciones)");
        if (!es.suggest("qier").suggestions.contains("quiero")) throw new AssertionError("completado difuso qier: " + es.suggest("qier").suggestions);
        es.setPersonal(java.util.Arrays.asList("aurebesh"));
        check(es.suggest("aurebesh").correction, null, "palabra aprendida");
        if (!es.suggest("aure").suggestions.contains("aurebesh")) throw new AssertionError("completar aprendida: " + es.suggest("aure").suggestions);
        es.setPersonal(null);
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

        if (fakeModel.exists()) AiModels.delete(app, fakeModel.getName());
        ShadowLooper.shadowMainLooper().idle();

        // Pantalla de la app en claro y en noche: pagina completa y primera pantalla (Samsung One UI 9.0). La app se viste
        // con el tema efectivo del teclado: de fabrica Nucleo (claro, fijo), y de noche solo cambia con un automatico
        // (Republica: Borde Exterior de noche); un tema fijo no sigue al modo oscuro.
        ShadowBuild.setManufacturer("samsung");
        DeviceInfo.semOverride = 180000;
        Bitmap light = renderActivity();
        check(hex(light.getPixel(5, 5)), hex(Themes.ESTELAR.bg), "fondo de la app de fabrica (Nucleo)");
        save(light, "app_claro.png");
        save(Bitmap.createBitmap(light, 0, 0, W, PHONE_H), "app_claro_primera.png");
        RuntimeEnvironment.setQualifiers("+night");
        check(hex(renderActivity().getPixel(5, 5)), hex(Themes.ESTELAR.bg), "un tema fijo no cambia de noche");
        Prefs.get(app).edit().putString(Prefs.THEME, Prefs.THEME_AUTO).apply();
        Bitmap night = renderActivity();
        check(hex(night.getPixel(5, 5)), hex(Themes.NOCHE.bg), "fondo de la app con Republica de noche (Borde Exterior)");
        save(night, "app_noche.png");
        save(Bitmap.createBitmap(night, 0, 0, W, PHONE_H), "app_noche_primera.png");
        Prefs.get(app).edit().remove(Prefs.THEME).apply();   // de vuelta al tema de fabrica para las capturas siguientes
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

    private static void assertNear(String what, float actual, float expected) {
        if (Math.abs(actual - expected) > 2.5f) throw new AssertionError(what + ": " + actual + " vs " + expected);
    }
}
