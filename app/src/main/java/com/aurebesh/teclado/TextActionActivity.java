package com.aurebesh.teclado;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;

/**
 * Trampolin invisible para las acciones de texto: abre la app elegida (o el selector del sistema)
 * con ACTION_PROCESS_TEXT y, si devuelve texto, se lo pasa al teclado para que sustituya lo escrito.
 */
public final class TextActionActivity extends Activity {

    static final String EXTRA_TEXT = "text";
    static final String EXTRA_PACKAGE = "pkg";
    static final String EXTRA_CLASS = "cls";

    private static final int REQUEST_PROCESS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;   // la app destino ya esta abierta; esperamos su resultado
        Intent src = getIntent();
        CharSequence text = src != null ? src.getCharSequenceExtra(EXTRA_TEXT) : null;
        if (text == null) text = "";
        Intent p = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        String pkg = src != null ? src.getStringExtra(EXTRA_PACKAGE) : null;
        String cls = src != null ? src.getStringExtra(EXTRA_CLASS) : null;
        try {
            if (pkg != null && cls != null) {
                p.setClassName(pkg, cls);
                startActivityForResult(p, REQUEST_PROCESS);
            } else {
                startActivityForResult(Intent.createChooser(p, getString(R.string.actions_title)), REQUEST_PROCESS);
            }
        } catch (ActivityNotFoundException e) {
            finish();
        } catch (SecurityException e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROCESS && resultCode == RESULT_OK && data != null) {
            CharSequence out = data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (out != null) AurebeshIME.deliverProcessedText(out);
        }
        finish();
    }
}
