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

        // Distribucion inglesa: sin tecla n~, "," da "!", etiqueta de idioma en el espacio
        kb.setLanguage("en");
        kb.setSpaceLabel("English");
        save(render(kb), "teclado_ingles.png");
        kb.setLanguage("es");
        kb.setSpaceLabel(null);

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

        // Pantalla de la app en claro y en noche: pagina completa y primera pantalla
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
