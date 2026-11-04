package com.aurebesh.teclado;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;

/**
 * Sonido de las teclas: el clic del sistema o, si el usuario eligio uno, un archivo corto
 * copiado a files/keysound que se reproduce con SoundPool (baja latencia). El volumen va de 0
 * (silencio) a 100 (el maximo del efecto) y se aplica a los dos.
 */
final class KeySound {

    static final String FILE = "keysound";

    private static KeySound instance;

    private final Context app;
    private SoundPool pool;
    private int soundId = 0;
    private boolean loaded = false;
    private int volume = 100;      // 0-100 (preferencia "volumen del sonido")

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

    /** Volumen de las teclas, 0-100 (fuera de ese rango se recorta); con 0 no suena nada. */
    synchronized void setVolume(int percent) { volume = Math.max(0, Math.min(100, percent)); }

    /** Volumen actual, 0-100. */
    synchronized int volume() { return volume; }

    /** Hay un sonido personalizado listo para sonar. */
    synchronized boolean hasCustom() {
        return pool != null && soundId > 0 && loaded;
    }

    /**
     * Suena la tecla al volumen elegido (volumen / 100): el sonido personalizado si esta cargado; si no, el
     * efecto del sistema segun la tecla (codigos CODE_DELETE, CODE_ENTER y CODE_SPACE de AurebeshKeyboardView).
     * Con volumen 0 no suena nada.
     */
    void play(int keyCode) {
        try {
            SoundPool p;
            int id;
            float vol;
            synchronized (this) {
                vol = volume / 100f;
                if (vol <= 0f) return;
                p = pool;
                id = soundId;
                if (p != null && id > 0 && loaded) {
                    p.play(id, vol, vol, 1, 0, 1f);
                    return;
                }
            }
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            int fx = keyCode == -5 ? AudioManager.FX_KEYPRESS_DELETE
                    : keyCode == -4 ? AudioManager.FX_KEYPRESS_RETURN
                    : keyCode == -6 ? AudioManager.FX_KEYPRESS_SPACEBAR
                    : AudioManager.FX_KEYPRESS_STANDARD;
            audio.playSoundEffect(fx, vol);
        } catch (RuntimeException ignored) {
            // el sonido nunca debe tumbar el teclado
        }
    }
}
