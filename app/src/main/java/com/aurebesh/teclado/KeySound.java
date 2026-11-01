package com.aurebesh.teclado;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;

/**
 * Sonido de las teclas: el clic del sistema o, si el usuario eligio uno, un archivo corto
 * copiado a files/keysound que se reproduce con SoundPool (baja latencia).
 */
final class KeySound {

    static final String FILE = "keysound";

    private static KeySound instance;

    private final Context app;
    private SoundPool pool;
    private int soundId = 0;
    private boolean loaded = false;

    private KeySound(Context c) {
        app = c.getApplicationContext();
        reload(app);
    }

    /** Unica instancia (sobre el contexto de la aplicacion). */
    static synchronized KeySound get(Context c) {
        if (instance == null) instance = new KeySound(c);
        return instance;
    }

    /** Descarga el sonido anterior y, si existe files/keysound, lo carga. */
    synchronized void reload(Context c) {
        loaded = false;
        soundId = 0;
        if (pool != null) {
            try { pool.release(); } catch (RuntimeException ignored) {}
            pool = null;
        }
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        if (!f.isFile() || f.length() == 0) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
            pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override public void onLoadComplete(SoundPool p, int id, int status) {
                    synchronized (KeySound.this) {
                        if (p == pool && id == soundId && status == 0) loaded = true;
                    }
                }
            });
            soundId = pool.load(f.getAbsolutePath(), 1);
        } catch (RuntimeException e) {
            pool = null;
            soundId = 0;
        }
    }

    /** Hay un sonido personalizado listo para sonar. */
    synchronized boolean hasCustom() {
        return pool != null && soundId > 0 && loaded;
    }

    /**
     * Suena la tecla: el sonido personalizado si esta cargado; si no, el efecto del sistema
     * segun la tecla (codigos CODE_DELETE, CODE_ENTER y CODE_SPACE de AurebeshKeyboardView).
     */
    void play(int keyCode) {
        try {
            SoundPool p;
            int id;
            synchronized (this) {
                p = pool;
                id = soundId;
                if (p != null && id > 0 && loaded) {
                    p.play(id, 0.7f, 0.7f, 1, 0, 1f);
                    return;
                }
            }
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            int fx = keyCode == -5 ? AudioManager.FX_KEYPRESS_DELETE
                    : keyCode == -4 ? AudioManager.FX_KEYPRESS_RETURN
                    : keyCode == -6 ? AudioManager.FX_KEYPRESS_SPACEBAR
                    : AudioManager.FX_KEYPRESS_STANDARD;
            audio.playSoundEffect(fx, 0.5f);
        } catch (RuntimeException ignored) {
            // el sonido nunca debe tumbar el teclado
        }
    }
}
