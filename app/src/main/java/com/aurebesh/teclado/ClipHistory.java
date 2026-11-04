package com.aurebesh.teclado;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Patterns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Historial del portapapeles: textos e imagenes copiados, del mas reciente al mas antiguo.
 * La lista se guarda como JSON en su propio archivo de preferencias ({@link #FILE}; la primera vez se
 * muda desde el principal) y las imagenes como archivos en files/clips/, solo en este telefono.
 * Todo leer-modificar-guardar va bajo el cerrojo de la clase: el IME anade textos desde el hilo
 * principal y el vigilante de capturas anade imagenes desde otro. Tambien localiza telefonos,
 * correos y enlaces dentro de un texto para poder pegar solo esa parte.
 */
final class ClipHistory {
    private ClipHistory() {}

    static final String KEY = "clips";
    /** Archivo de preferencias propio: asi el historial no pasa por el oyente de ajustes del teclado. */
    static final String FILE = "aurebesh_clips";
    static final int MAX = 20;
    static final int MAX_IMAGES = 10;
    static final String DIR = "clips";
    private static final int MAX_LENGTH = 5000;
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    /** Un elemento del historial: texto copiado o imagen guardada en files/clips/. */
    static final class Entry {
        static final int TEXT = 0;
        static final int IMAGE = 1;

        final int type;
        final String text;    // texto (TEXT)
        final String path;    // ruta absoluta del archivo (IMAGE)
        final String mime;    // tipo de la imagen (IMAGE)

        Entry(int type, String text, String path, String mime) {
            this.type = type;
            this.text = text;
            this.path = path;
            this.mime = mime;
        }

        boolean isImage() { return type == IMAGE; }

        File file() { return path == null ? null : new File(path); }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return type == e.type && eq(text, e.text) && eq(path, e.path);
        }

        @Override public int hashCode() {
            return 31 * (31 * type + (text == null ? 0 : text.hashCode())) + (path == null ? 0 : path.hashCode());
        }

        private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    /**
     * Preferencias del historial. Si el archivo propio aun no tiene la clave y el principal ("aurebesh") si,
     * la copia una sola vez y la quita del principal (instalaciones anteriores a 1.10).
     */
    static SharedPreferences prefs(Context c) {
        SharedPreferences own = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!own.contains(KEY)) {
            synchronized (ClipHistory.class) {
                SharedPreferences main = Prefs.get(c);
                String old = own.contains(KEY) ? null : main.getString(KEY, null);
                if (old != null) {
                    own.edit().putString(KEY, old).commit();   // primero el nuevo; si falla, el viejo sigue ahi
                    main.edit().remove(KEY).apply();
                }
            }
        }
        return own;
    }

    /** Elementos guardados, del mas reciente al mas antiguo; las imagenes cuyo archivo ya no existe se omiten. */
    static List<Entry> items(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Object o = a.opt(i);
                if (o instanceof JSONObject) {
                    JSONObject j = (JSONObject) o;
                    int type = j.optInt("t", Entry.TEXT);
                    if (type == Entry.IMAGE) {
                        String path = j.optString("p", null);
                        if (path != null && new File(path).isFile()) out.add(new Entry(Entry.IMAGE, null, path, j.optString("m", "image/*")));
                    } else {
                        String text = j.optString("v", null);
                        if (text != null && !text.isEmpty()) out.add(new Entry(Entry.TEXT, text, null, null));
                    }
                } else if (o instanceof String) {          // formato antiguo: solo textos
                    String s = (String) o;
                    if (!s.isEmpty()) out.add(new Entry(Entry.TEXT, s, null, null));
                }
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    /** Anade un texto al principio; si ya estaba, lo sube; nunca mas de MAX elementos. */
    static void addText(Context c, CharSequence text) {
        if (text == null) return;
        String s = text.toString();
        if (s.trim().isEmpty()) return;
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH);
        Entry e = new Entry(Entry.TEXT, s, null, null);
        synchronized (ClipHistory.class) {
            List<Entry> l = items(c);
            l.remove(e);
            l.add(0, e);
            trim(l);
            save(c, l);
        }
    }

    /**
     * Copia la imagen del portapapeles a files/clips/ y la pone al principio. Devuelve null si no se
     * pudo leer, si el tipo no es png/jpeg/gif/webp o si pesa mas de 8 MB.
     */
    static Entry addImage(Context c, Uri uri, String mime) {
        if (uri == null) return null;
        String ext = extensionFor(mime);
        if (ext == null) return null;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        File out = new File(d, System.currentTimeMillis() + "." + ext);
        InputStream in = null;
        OutputStream os = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            os = new FileOutputStream(out);
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_IMAGE_BYTES) {
                    os.close();
                    os = null;
                    out.delete();
                    return null;
                }
                os.write(buf, 0, n);
            }
        } catch (IOException | SecurityException e) {
            out.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(os);
        }
        Entry e = new Entry(Entry.IMAGE, null, out.getAbsolutePath(), mime);
        synchronized (ClipHistory.class) {                 // la copia va fuera del cerrojo; la lista, dentro
            if (!out.isFile()) return null;                // clear() vacio la carpeta mientras se copiaba
            List<Entry> l = items(c);
            l.add(0, e);
            trim(l);
            save(c, l);
        }
        return e;
    }

    /** Quita el elemento (y borra su archivo si es una imagen). */
    static void remove(Context c, Entry e) {
        if (e == null) return;
        synchronized (ClipHistory.class) {
            List<Entry> l = items(c);
            if (l.remove(e)) save(c, l);
            if (e.isImage()) deleteFile(e);
        }
    }

    /** Vacia el historial y borra todas las imagenes guardadas. */
    static void clear(Context c) {
        synchronized (ClipHistory.class) {
            File[] files = dir(c).listFiles();
            if (files != null) for (File f : files) f.delete();
            save(c, new ArrayList<Entry>());
        }
    }

    /** Deja como mucho MAX elementos y MAX_IMAGES imagenes, borrando los archivos sobrantes (con el cerrojo tomado). */
    private static void trim(List<Entry> l) {
        int images = 0;
        for (Iterator<Entry> it = l.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!e.isImage()) continue;
            images++;
            if (images > MAX_IMAGES) {
                deleteFile(e);
                it.remove();
            }
        }
        while (l.size() > MAX) {
            Entry last = l.remove(l.size() - 1);
            if (last.isImage()) deleteFile(last);
        }
    }

    private static void deleteFile(Entry e) {
        File f = e.file();
        if (f != null) f.delete();
    }

    /** Guarda la lista en el archivo propio (con el cerrojo tomado). */
    private static void save(Context c, List<Entry> l) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : l) {
                JSONObject j = new JSONObject();
                j.put("t", e.type);
                if (e.isImage()) {
                    j.put("p", e.path);
                    j.put("m", e.mime == null ? "image/*" : e.mime);
                } else {
                    j.put("v", e.text);
                }
                a.put(j);
            }
        } catch (JSONException ignored) {
            // no deberia ocurrir con claves fijas
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    private static String extensionFor(String mime) {
        if (mime == null) return null;
        String m = mime.toLowerCase();
        if (m.startsWith("image/png")) return "png";
        if (m.startsWith("image/jpeg") || m.startsWith("image/jpg")) return "jpg";
        if (m.startsWith("image/gif")) return "gif";
        if (m.startsWith("image/webp")) return "webp";
        return null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ partes de un texto

    /** Numero de telefono: digitos con espacios, guiones, puntos o parentesis, opcionalmente con + delante. */
    private static final Pattern PHONE = Pattern.compile("\\+?\\(?\\d[\\d\\s().\\-]{5,22}\\d");
    private static final Pattern DATE = Pattern.compile("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}");
    private static final String TRAILING = ".,;:!?)»”'\"";

    /** Primer telefono (de 7 a 15 digitos) dentro del texto, o null si no hay o si es todo el texto. */
    static String phoneIn(String text) {
        if (text == null) return null;
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String candidate = m.group().trim();
            int digits = 0;
            for (int i = 0; i < candidate.length(); i++) if (Character.isDigit(candidate.charAt(i))) digits++;
            if (digits < 7 || digits > 15 || DATE.matcher(candidate).matches()) continue;   // una fecha no es un telefono
            return candidate.equals(text.trim()) ? null : candidate;
        }
        return null;
    }

    /** Primer correo dentro del texto, o null si no hay o si es todo el texto. */
    static String emailIn(String text) {
        if (text == null) return null;
        Matcher m = Patterns.EMAIL_ADDRESS.matcher(text);
        if (!m.find()) return null;
        String piece = stripTrailing(m.group());
        return piece.isEmpty() || piece.equals(text.trim()) ? null : piece;
    }

    /** Primer enlace (con punto) dentro del texto, o null si no hay o si es todo el texto. */
    static String linkIn(String text) {
        if (text == null) return null;
        Matcher m = Patterns.WEB_URL.matcher(text);
        while (m.find()) {
            String piece = stripTrailing(m.group());
            if (piece.indexOf('.') < 0) continue;
            boolean partOfEmail = (m.start() > 0 && text.charAt(m.start() - 1) == '@')
                    || (m.end() < text.length() && text.charAt(m.end()) == '@');
            if (partOfEmail) continue;                     // el dominio de un correo no es un enlace
            return piece.equals(text.trim()) ? null : piece;
        }
        return null;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && TRAILING.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }

    // ------------------------------------------------------------------ miniaturas

    /** Decodifica la imagen reducida para que su lado mayor ronde maxPx; null si falla. */
    static Bitmap thumbnail(Entry e, int maxPx) {
        File f = e == null ? null : e.file();
        if (f == null || !f.isFile() || maxPx <= 0) return null;
        try {
            Bitmap b = null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int longest = Math.max(o.outWidth, o.outHeight);
            if (longest > 0) {
                int sample = 1;
                while (longest / (sample * 2) >= maxPx) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            }
            if (b == null) {                                   // sin medidas previas: leer el archivo entero
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                try { b = BitmapFactory.decodeStream(in); } finally { in.close(); }
            }
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight(), big = Math.max(w, h);
            if (big > maxPx * 2) {
                float k = (float) maxPx * 2 / big;
                b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
            }
            return b;
        } catch (java.io.IOException ex) {
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
