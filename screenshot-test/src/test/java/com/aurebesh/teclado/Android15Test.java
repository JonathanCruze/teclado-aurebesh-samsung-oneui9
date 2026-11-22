package com.aurebesh.teclado;

import android.app.Application;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowInsets;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * La app apunta a Android 15 (API 35) para que Play Protect no la tome por una app hecha para un Android antiguo, sin
 * cambiar como se dibujan sus ventanas: el tema de la app y el del servicio de teclado llevan la exclusion del dibujo de
 * borde a borde (android.R.attr.windowOptOutEdgeToEdgeEnforcement) y, por si algun sistema dibujara asi la ventana, la
 * app baja su contenido de la barra de estado y el teclado sube el alto de la barra de navegacion.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Android15Test {

    /** android.R.attr.windowOptOutEdgeToEdgeEnforcement, el mismo identificador de tools/plataforma35. */
    private static final int OPT_OUT = 0x0101069a;

    private Application app;

    @Before
    public void setUp() {
        app = (Application) RuntimeEnvironment.getApplication();
        Prefs.get(app).edit().clear().apply();
        Prefs.ui(app).edit().clear().apply();
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, null);
    }

    private static boolean optOut(android.content.res.Resources.Theme theme) {
        TypedArray t = theme.obtainStyledAttributes(new int[]{OPT_OUT});
        try {
            return t.getBoolean(0, false);
        } finally {
            t.recycle();
        }
    }

    @Test
    public void elIdentificadorDelComplementoEsElDeLaPlataforma() {
        assertEquals(android.R.attr.windowOptOutEdgeToEdgeEnforcement, OPT_OUT);
    }

    @Test
    public void laAppYElTecladoNoSeDibujanDeBordeABorde() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue("tema de la app (claro u oscuro segun el tema del teclado)", optOut(a.getTheme()));
        for (int style : new int[]{R.style.AppTheme, R.style.AppTheme_Claro, R.style.AppTheme_Oscuro, R.style.TemaTeclado}) {
            assertTrue(app.getResources().getResourceEntryName(style), optOut(new ContextThemeWrapper(app, style).getTheme()));
        }
        FlujoCorrectorTest.TestIME ime = Robolectric.buildService(FlujoCorrectorTest.TestIME.class).create().get();
        assertTrue("el servicio crea su ventana con el tema del teclado", optOut(ime.getTheme()));
        // El tema del teclado sigue siendo el del sistema: mismo fondo y misma animacion de ventana que Theme.DeviceDefault.InputMethod
        int[] same = {android.R.attr.windowBackground, android.R.attr.windowAnimationStyle, android.R.attr.windowIsFloating};
        TypedArray ours = new ContextThemeWrapper(app, R.style.TemaTeclado).getTheme().obtainStyledAttributes(same);
        TypedArray system = new ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault_InputMethod).getTheme().obtainStyledAttributes(same);
        for (int i = 0; i < same.length; i++) {
            assertEquals("atributo " + i, system.getResourceId(i, 0), ours.getResourceId(i, 0));
            assertEquals("atributo " + i, system.getString(i), ours.getString(i));
        }
        ours.recycle();
        system.recycle();
    }

    @Test
    public void siLaVentanaFueraDeBordeABordeNadaQuedaBajoLasBarras() {
        // El teclado: sube el alto de la barra de navegacion que el sistema no haya descontado, con el fondo del tema debajo
        assertEquals(0, AurebeshIME.navInset(null));
        assertEquals(0, AurebeshIME.navInset(new WindowInsets.Builder().build()));
        WindowInsets nav = new WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 126)).build();
        assertEquals(126, AurebeshIME.navInset(nav));
        FlujoCorrectorTest.TestIME ime = Robolectric.buildService(FlujoCorrectorTest.TestIME.class).create().get();
        View root = ime.onCreateInputView();
        assertEquals("sin barra por descontar, el teclado no cambia", 0, root.getPaddingBottom());
        root.dispatchApplyWindowInsets(nav);
        assertEquals(126, root.getPaddingBottom());
        assertTrue(root.getBackground() instanceof ColorDrawable);
        assertEquals(Themes.byId(Prefs.theme(app)).bg, ((ColorDrawable) root.getBackground()).getColor());
        root.dispatchApplyWindowInsets(new WindowInsets.Builder().build());
        assertEquals(0, root.getPaddingBottom());
        // La app: el contenido baja el alto de la barra de estado
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View content = a.findViewById(android.R.id.content);
        assertEquals(0, content.getPaddingTop());
        content.dispatchApplyWindowInsets(new WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 90, 0, 0)).build());
        assertEquals(90, content.getPaddingTop());
        content.dispatchApplyWindowInsets(new WindowInsets.Builder().build());
        assertEquals(0, content.getPaddingTop());
    }
}
