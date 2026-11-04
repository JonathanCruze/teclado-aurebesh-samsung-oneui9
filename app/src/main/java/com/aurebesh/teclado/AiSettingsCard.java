package com.aurebesh.teclado;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Tarjeta "Inteligencia" para la pantalla de ajustes: motor (modelo local / servidor propio), lista
 * de modelos con tamano y estado (no descargado, descargando x %, listo) y botones Descargar /
 * Cancelar / Usar / Borrar, importacion de un GGUF propio, campos del servidor (URL, clave, modelo)
 * con "Probar conexion", y la nota de privacidad.
 *
 * <p>Uso desde MainActivity: {@code root.addView(AiSettingsCard.build(this, ink, secondary, card, divider))}
 * y, en {@code onActivityResult}, {@code AiSettingsCard.onActivityResult(this, requestCode, resultCode, data)}.
 * La importacion usa el codigo de peticion {@link #REQUEST_IMPORT} (42).
 */
final class AiSettingsCard {
    private AiSettingsCard() {}

    static final int REQUEST_IMPORT = 42;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static WeakReference<Card> current = new WeakReference<>(null);

    /** Construye la tarjeta con los colores de la pantalla de ajustes. */
    static View build(final Activity a, int inkColor, int secondaryColor, int cardColor, int dividerColor) {
        Card c = new Card(a, inkColor, secondaryColor, cardColor, dividerColor);
        current = new WeakReference<>(c);
        return c;
    }

    /** Resultado del selector de documentos: copia el GGUF elegido a la carpeta de modelos. */
    static void onActivityResult(final Activity a, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        final Card card = current.get();
        if (card != null) card.importing(true, 0, -1);
        AiModels.importGguf(a, uri, new AiModels.Listener() {
            @Override public void onProgress(long done, long total) {
                Card c = current.get();
                if (c != null) c.importing(true, done, total);
            }
            @Override public void onFinished(File file) {
                Toast.makeText(a, R.string.ai_imported, Toast.LENGTH_SHORT).show();
                Card c = current.get();
                if (c != null) { c.importing(false, 0, -1); c.refresh(); }
            }
            @Override public void onFailed(int messageRes, String detail) {
                Toast.makeText(a, a.getString(messageRes) + (detail == null ? "" : " · " + detail), Toast.LENGTH_LONG).show();
                Card c = current.get();
                if (c != null) { c.importing(false, 0, -1); c.refresh(); }
            }
        });
    }

    /** La tarjeta en si. */
    private static final class Card extends LinearLayout {
        private final Activity a;
        private final int ink, secondary, card, divider;
        private final float dp;
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Handler main = new Handler(Looper.getMainLooper());

        private final TextView localChip, remoteChip;
        private final LinearLayout models;
        private final TextView importRow;
        private final ProgressBar importBar;
        private final LinearLayout server;
        private final EditText url, key, model;
        private final TextView testResult;
        private boolean filling;

        Card(Activity a, int ink, int secondary, int card, int divider) {
            super(a);
            this.a = a; this.ink = ink; this.secondary = secondary; this.card = card; this.divider = divider;
            dp = getResources().getDisplayMetrics().density;
            setOrientation(VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(card);
            bg.setCornerRadius(px(16));
            setBackground(bg);

            // Motor
            LinearLayout mode = new LinearLayout(a);
            mode.setOrientation(HORIZONTAL);
            mode.setGravity(Gravity.CENTER_VERTICAL);
            mode.setPadding(px(18), px(14), px(18), px(12));
            mode.addView(text(a.getString(R.string.ai_engine), 15, ink, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
            localChip = chip(R.string.ai_engine_local, new OnClickListener() {
                @Override public void onClick(View v) { AiModels.setMode(a, AiModels.MODE_LOCAL); refresh(); }
            });
            remoteChip = chip(R.string.ai_engine_remote, new OnClickListener() {
                @Override public void onClick(View v) { switchToRemote(); }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(WRAP, WRAP);
            cp.leftMargin = px(6);
            mode.addView(localChip, cp);
            mode.addView(remoteChip, cp);
            addView(mode, new LinearLayout.LayoutParams(MATCH, WRAP));
            addView(dividerLine(), new LinearLayout.LayoutParams(MATCH, Math.max(1, px(1))));

            // Modelos locales
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(VERTICAL);
            head.setPadding(px(18), px(12), px(18), px(4));
            head.addView(text(a.getString(R.string.ai_models_title), 15, ink, true), new LinearLayout.LayoutParams(MATCH, WRAP));
            head.addView(text(a.getString(R.string.ai_models_sub), 13, secondary, false), new LinearLayout.LayoutParams(MATCH, WRAP));
            addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));
            models = new LinearLayout(a);
            models.setOrientation(VERTICAL);
            addView(models, new LinearLayout.LayoutParams(MATCH, WRAP));

            importRow = text(a.getString(R.string.ai_import), 15, ink, true);
            importRow.setPadding(px(18), px(14), px(18), px(14));
            importRow.setBackground(ripple(0));
            importRow.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        Card.this.a.startActivityForResult(i, REQUEST_IMPORT);
                    } catch (Exception e) {
                        Toast.makeText(Card.this.a, R.string.ai_import_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            addView(importRow, new LinearLayout.LayoutParams(MATCH, WRAP));
            importBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            importBar.setMax(100);
            importBar.setVisibility(GONE);
            LinearLayout.LayoutParams ib = new LinearLayout.LayoutParams(MATCH, px(4));
            ib.leftMargin = px(18); ib.rightMargin = px(18); ib.bottomMargin = px(10);
            addView(importBar, ib);
            addView(dividerLine(), new LinearLayout.LayoutParams(MATCH, Math.max(1, px(1))));

            // Servidor propio
            server = new LinearLayout(a);
            server.setOrientation(VERTICAL);
            server.setPadding(px(18), px(12), px(18), px(6));
            server.addView(text(a.getString(R.string.ai_server_title), 15, ink, true), new LinearLayout.LayoutParams(MATCH, WRAP));
            server.addView(text(a.getString(R.string.ai_server_sub), 13, secondary, false), new LinearLayout.LayoutParams(MATCH, WRAP));
            url = field(a.getString(R.string.ai_url_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            key = field(a.getString(R.string.ai_key_optional), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            model = field(a.getString(R.string.ai_remote_model), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            server.addView(url, fieldParams());
            server.addView(key, fieldParams());
            server.addView(model, fieldParams());
            TextWatcher save = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int n) {}
                @Override public void afterTextChanged(Editable s) {
                    if (filling) return;
                    AiModels.setRemote(Card.this.a, url.getText().toString(), key.getText().toString(), model.getText().toString());
                }
            };
            url.addTextChangedListener(save);
            key.addTextChangedListener(save);
            model.addTextChangedListener(save);
            LinearLayout testRow = new LinearLayout(a);
            testRow.setOrientation(HORIZONTAL);
            testRow.setGravity(Gravity.CENTER_VERTICAL);
            Button test = button(R.string.ai_test, false);
            test.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { testConnection(); }
            });
            testRow.addView(test, new LinearLayout.LayoutParams(WRAP, WRAP));
            testResult = text("", 13, secondary, false);
            testResult.setPadding(px(12), 0, 0, 0);
            testRow.addView(testResult, new LinearLayout.LayoutParams(0, WRAP, 1f));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(MATCH, WRAP);
            tp.topMargin = px(8);
            server.addView(testRow, tp);
            addView(server, new LinearLayout.LayoutParams(MATCH, WRAP));
            addView(dividerLine(), new LinearLayout.LayoutParams(MATCH, Math.max(1, px(1))));

            // Privacidad
            TextView privacy = text(a.getString(R.string.ai_privacy_footer), 13, secondary, false);
            privacy.setPadding(px(18), px(12), px(18), px(6));
            addView(privacy, new LinearLayout.LayoutParams(MATCH, WRAP));
            TextView credits = text(a.getString(R.string.ai_credits), 12, secondary, false);
            credits.setPadding(px(18), 0, px(18), px(16));
            addView(credits, new LinearLayout.LayoutParams(MATCH, WRAP));

            refresh();
        }

        /** Vuelve a leer preferencias y archivos y reconstruye la lista de modelos. */
        void refresh() {
            boolean remote = AiModels.remote(a);
            styleChip(localChip, !remote);
            styleChip(remoteChip, remote);
            server.setAlpha(remote ? 1f : 0.55f);
            filling = true;
            setIfDifferent(url, AiModels.remoteUrl(a));
            setIfDifferent(key, AiModels.remoteKey(a));
            setIfDifferent(model, AiModels.remoteModel(a));
            filling = false;
            models.removeAllViews();
            List<AiModels.Entry> entries = AiModels.entries(a);
            for (int i = 0; i < entries.size(); i++) {
                models.addView(new ModelRow(entries.get(i)), new LinearLayout.LayoutParams(MATCH, WRAP));
            }
        }

        void importing(boolean on, long done, long total) {
            importBar.setVisibility(on ? VISIBLE : GONE);
            importBar.setIndeterminate(on && total <= 0);
            if (total > 0) importBar.setProgress((int) (done * 100 / total));
            importRow.setEnabled(!on);
            importRow.setAlpha(on ? 0.5f : 1f);
        }

        private void switchToRemote() {
            if (!Prefs.get(a).getBoolean(AiModels.REMOTE_WARNED, false)) {
                new AlertDialog.Builder(a)
                        .setTitle(R.string.ai_engine_remote)
                        .setMessage(R.string.ai_remote_warning)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                Prefs.get(a).edit().putBoolean(AiModels.REMOTE_WARNED, true).apply();
                                AiModels.setMode(a, AiModels.MODE_REMOTE);
                                refresh();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            AiModels.setMode(a, AiModels.MODE_REMOTE);
            refresh();
        }

        private void testConnection() {
            final String u = url.getText().toString().trim();
            final String k = key.getText().toString();
            if (u.isEmpty()) { testResult.setText(a.getString(R.string.ai_test_fail, "URL")); return; }
            testResult.setText(R.string.ai_connecting);
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    String msg;
                    try {
                        List<String> list = AiRemote.models(u, k);
                        msg = a.getString(R.string.ai_test_ok, list.size());
                        if (!list.isEmpty()) {
                            final String first = list.get(0);
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (model.getText().toString().trim().isEmpty()) model.setText(first);
                                }
                            });
                        }
                    } catch (IOException e) {
                        msg = a.getString(R.string.ai_test_fail, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    } catch (RuntimeException e) {
                        msg = a.getString(R.string.ai_test_fail, e.getClass().getSimpleName());
                    }
                    final String m = msg;
                    main.post(new Runnable() {
                        @Override public void run() { testResult.setText(m); }
                    });
                }
            }, "ai-test");
            t.setDaemon(true);
            t.start();
        }

        // -------------------------------------------------------------- fila de modelo

        /** Fila de un modelo: nombre, detalle, estado y botones segun este descargado o no. */
        private final class ModelRow extends LinearLayout implements AiModels.Listener {
            private final AiModels.Entry entry;
            private final TextView state;
            private final ProgressBar bar;
            private final LinearLayout buttons;

            ModelRow(final AiModels.Entry e) {
                super(a);
                entry = e;
                setOrientation(VERTICAL);
                setPadding(px(18), px(10), px(18), px(10));
                LinearLayout top = new LinearLayout(a);
                top.setOrientation(HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout names = new LinearLayout(a);
                names.setOrientation(VERTICAL);
                String name = e.name + (e.selected ? "  ✓" : "");
                names.addView(text(name, 15, ink, e.selected), new LinearLayout.LayoutParams(MATCH, WRAP));
                StringBuilder sub = new StringBuilder(AiModels.sizeLabel(e.size));
                if (e.preset != null) {
                    sub.append(" · ").append(a.getString(e.preset.note));
                    if (e.preset.recommended) sub.append(" · ").append(a.getString(R.string.ai_recommended));
                    sub.append(" · ").append(e.preset.license);
                } else {
                    sub.append(" · ").append(a.getString(R.string.ai_imported_file));
                }
                names.addView(text(sub.toString(), 13, secondary, false), new LinearLayout.LayoutParams(MATCH, WRAP));
                state = text("", 13, secondary, false);
                names.addView(state, new LinearLayout.LayoutParams(MATCH, WRAP));
                top.addView(names, new LinearLayout.LayoutParams(0, WRAP, 1f));
                buttons = new LinearLayout(a);
                buttons.setOrientation(HORIZONTAL);
                buttons.setGravity(Gravity.CENTER_VERTICAL);
                top.addView(buttons, new LinearLayout.LayoutParams(WRAP, WRAP));
                addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));
                bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(100);
                bar.setVisibility(GONE);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, px(4));
                bp.topMargin = px(8);
                addView(bar, bp);
                AiModels.Download d = AiModels.active(e.file);
                if (d != null) {
                    d.listener = this;
                    showDownloading(d.done, d.total);
                } else {
                    showIdle();
                }
            }

            private void showIdle() {
                buttons.removeAllViews();
                bar.setVisibility(GONE);
                if (entry.installed) {
                    state.setText(entry.selected ? R.string.ai_in_use : R.string.ai_ready);
                    if (!entry.selected) {
                        Button use = button(R.string.ai_use, true);
                        use.setOnClickListener(new OnClickListener() {
                            @Override public void onClick(View v) { AiModels.select(a, entry.file); refresh(); }
                        });
                        buttons.addView(use, buttonParams());
                    }
                    Button del = button(R.string.ai_delete, false);
                    del.setOnClickListener(new OnClickListener() {
                        @Override public void onClick(View v) { confirmDelete(); }
                    });
                    buttons.addView(del, buttonParams());
                } else {
                    state.setText(R.string.ai_not_downloaded);
                    if (entry.preset != null) {
                        Button dl = button(R.string.ai_download, true);
                        dl.setOnClickListener(new OnClickListener() {
                            @Override public void onClick(View v) {
                                AiModels.Download d = AiModels.download(a, entry.preset, ModelRow.this);
                                showDownloading(d.done, d.total);
                            }
                        });
                        buttons.addView(dl, buttonParams());
                    }
                }
            }

            private void showDownloading(long done, long total) {
                if (buttons.getChildCount() != 1 || !(buttons.getChildAt(0).getTag() instanceof String)) {
                    buttons.removeAllViews();
                    Button cancel = button(R.string.ai_cancel_download, false);
                    cancel.setTag("cancel");
                    cancel.setOnClickListener(new OnClickListener() {
                        @Override public void onClick(View v) {
                            AiModels.Download d = AiModels.active(entry.file);
                            if (d != null) d.cancel();
                            showIdle();
                            state.setText(R.string.ai_download_paused);
                        }
                    });
                    buttons.addView(cancel, buttonParams());
                }
                bar.setVisibility(VISIBLE);
                int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                bar.setIndeterminate(total <= 0);
                bar.setProgress(pct);
                state.setText(a.getString(R.string.ai_downloading, pct));
            }

            private void confirmDelete() {
                new AlertDialog.Builder(a)
                        .setMessage(a.getString(R.string.ai_delete_confirm, entry.name, AiModels.sizeLabel(entry.size)))
                        .setPositiveButton(R.string.ai_delete, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                AiModels.delete(a, entry.file);
                                refresh();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }

            @Override public void onProgress(long done, long total) { showDownloading(done, total); }

            @Override public void onFinished(File file) {
                Toast.makeText(a, R.string.ai_download_done, Toast.LENGTH_SHORT).show();
                refresh();
            }

            @Override public void onFailed(int messageRes, String detail) {
                Toast.makeText(a, a.getString(messageRes) + (detail == null ? "" : " · " + detail), Toast.LENGTH_LONG).show();
                refresh();
            }
        }

        // -------------------------------------------------------------- piezas

        private TextView text(String s, int sp, int color, boolean bold) {
            TextView t = new TextView(a);
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            if (bold) t.setTypeface(medium);
            t.setLineSpacing(0, 1.2f);
            return t;
        }

        private TextView chip(int label, OnClickListener l) {
            TextView t = text(a.getString(label), 13, ink, true);
            t.setPadding(px(12), px(6), px(12), px(6));
            t.setOnClickListener(l);
            return t;
        }

        private void styleChip(TextView t, boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selected ? ink : divider);
            bg.setCornerRadius(px(14));
            t.setBackground(bg);
            t.setTextColor(selected ? card : ink);
        }

        private EditText field(String hint, int inputType) {
            EditText e = new EditText(a);
            e.setHint(hint);
            e.setInputType(inputType);
            e.setSingleLine(true);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            e.setTextColor(ink);
            e.setHintTextColor(secondary);
            return e;
        }

        private LinearLayout.LayoutParams fieldParams() {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
            p.topMargin = px(2);
            return p;
        }

        private LinearLayout.LayoutParams buttonParams() {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, px(36));
            p.leftMargin = px(6);
            return p;
        }

        private Button button(int label, boolean primary) {
            Button b = new Button(a);
            b.setText(label);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setTypeface(medium);
            b.setStateListAnimator(null);
            b.setMinHeight(0);
            b.setMinimumHeight(px(36));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(px(14), 0, px(14), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(primary ? ink : divider);
            bg.setCornerRadius(px(12));
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x22000000), bg, null));
            b.setTextColor(primary ? card : ink);
            return b;
        }

        private View dividerLine() {
            View v = new View(a);
            v.setBackgroundColor(divider);
            return v;
        }

        private RippleDrawable ripple(int base) {
            GradientDrawable content = new GradientDrawable();
            content.setColor(base);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            return new RippleDrawable(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x1A000000), content, mask);
        }

        private static void setIfDifferent(EditText e, String s) {
            if (!e.getText().toString().equals(s)) e.setText(s);
        }

        private int px(float dps) { return Math.round(dps * dp); }
    }
}
