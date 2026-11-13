package com.aurebesh.teclado;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Fila de ajustes "Sugerir pegar la ultima captura de pantalla": titulo, subtitulo e interruptor con
 * el mismo aspecto que las filas de MainActivity. El interruptor se liga a la preferencia
 * {@link Prefs#SCREENSHOTS}; al encenderlo se pide el permiso de fotos si falta y solo queda
 * encendido cuando el acceso es completo (el acceso parcial de Android 14 no sirve para ver capturas
 * nuevas). Nada sale del telefono.
 */
final class ScreenshotSettingsRow {
    private ScreenshotSettingsRow() {}

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Ultimo interruptor construido, para actualizarlo tras el dialogo de permisos. */
    private static WeakReference<Switch> current = new WeakReference<>(null);
    /** Evita que un setChecked() programatico vuelva a disparar la logica del usuario. */
    private static boolean updating;

    /**
     * Construye la fila. El llamador la anade a su tarjeta (y pone antes su linea divisoria si procede).
     *
     * @param a   pantalla de ajustes
     * @param pal paleta del tema efectivo del teclado: texto, subtitulo, acento del interruptor y ondas
     */
    static View build(final Activity a, AppPalette pal) {
        final float dp = a.getResources().getDisplayMetrics().density;
        int ink = pal.text, secondary = pal.secondary;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(dp, 56));
        row.setPadding(px(dp, 18), px(dp, 12), px(dp, 14), px(dp, 12));
        row.setBackground(ripple(pal.ripple));

        // El interruptor es el elemento accesible; el texto solo lo describe.
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        String title = a.getString(R.string.shots_title);
        String sub = a.getString(R.string.shots_sub);
        col.addView(text(a, title, 16, ink), block(dp, 0));
        col.addView(text(a, sub, 13, secondary), block(dp, 2));
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final Switch sw = new Switch(a);
        sw.setContentDescription(title + ". " + sub);
        sw.setThumbTintList(pal.switchThumb());          // acento del tema encendido, como en MainActivity
        sw.setTrackTintList(pal.switchTrack());
        boolean on = Prefs.get(a).getBoolean(Prefs.SCREENSHOTS, false) && ScreenshotWatcher.mediaAccess(a) == 2;
        if (!on && Prefs.get(a).getBoolean(Prefs.SCREENSHOTS, false)) {
            Prefs.get(a).edit().putBoolean(Prefs.SCREENSHOTS, false).apply();   // permiso revocado desde Ajustes
        }
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (updating) return;
                if (!checked) {
                    Prefs.get(a).edit().putBoolean(Prefs.SCREENSHOTS, false).apply();
                    return;
                }
                if (ScreenshotWatcher.mediaAccess(a) == 2) {
                    Prefs.get(a).edit().putBoolean(Prefs.SCREENSHOTS, true).apply();
                    return;
                }
                // Sin permiso completo: se apaga hasta conocer la respuesta del dialogo.
                set(sw, false);
                Prefs.get(a).edit().putBoolean(Prefs.SCREENSHOTS, false).apply();
                try {
                    a.requestPermissions(ScreenshotWatcher.permissionsToRequest(), ScreenshotWatcher.REQUEST_CODE);
                } catch (RuntimeException e) {
                    Toast.makeText(a, R.string.shots_denied, Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(WRAP, WRAP);
        sp.leftMargin = px(dp, 12);
        row.addView(sw, sp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
        current = new WeakReference<>(sw);
        return row;
    }

    /**
     * Para llamar desde Activity.onRequestPermissionsResult. Actualiza la preferencia y el
     * interruptor; si el acceso quedo parcial o denegado para siempre, explica donde concederlo.
     */
    static void onPermissionResult(Activity a, int requestCode, String[] perms, int[] results) {
        if (requestCode != ScreenshotWatcher.REQUEST_CODE) return;
        int access = ScreenshotWatcher.mediaAccess(a);
        Prefs.get(a).edit().putBoolean(Prefs.SCREENSHOTS, access == 2).apply();
        Switch sw = current.get();
        if (sw != null) set(sw, access == 2);
        if (access == 2) return;
        if (access == 1) {
            Toast.makeText(a, R.string.shots_partial, Toast.LENGTH_LONG).show();
            return;
        }
        boolean permanent = perms != null && perms.length > 0 && !a.shouldShowRequestPermissionRationale(perms[0]);
        if (permanent) Toast.makeText(a, R.string.shots_denied, Toast.LENGTH_LONG).show();
    }

    /** Para llamar en onResume: el usuario puede haber revocado el permiso desde Ajustes. */
    static void refresh(Activity a) {
        boolean on = Prefs.get(a).getBoolean(Prefs.SCREENSHOTS, false) && ScreenshotWatcher.mediaAccess(a) == 2;
        if (!on) Prefs.get(a).edit().putBoolean(Prefs.SCREENSHOTS, false).apply();
        Switch sw = current.get();
        if (sw != null) set(sw, on);
    }

    private static void set(Switch sw, boolean checked) {
        if (sw.isChecked() == checked) return;
        updating = true;
        try { sw.setChecked(checked); } finally { updating = false; }
    }

    // ------------------------------------------------------------------ piezas (como en MainActivity)

    private static int px(float dp, float dps) { return Math.round(dps * dp); }

    private static LinearLayout.LayoutParams block(float dp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.topMargin = px(dp, topDp);
        return p;
    }

    private static TextView text(Activity a, String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private static RippleDrawable ripple(int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(0x00000000);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }
}
