package com.aurebesh.teclado;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reconocedor de escritura deslizando el dedo (estilo SHARK2): compara el trazo del usuario con la
 * polilinea ideal de cada palabra del diccionario (los centros de sus teclas) en dos canales, posicion
 * y forma, y suma la frecuencia de la palabra en la misma escala que {@code Dictionary} (ln del rango).
 * <p>
 * El indice se construye una vez por (diccionario, geometria de teclas): secuencias de teclas empaquetadas
 * en un {@code byte[]}, longitud ideal de cada palabra y cubos por (primera letra, ultima letra). Una vez
 * construido es inmutable; {@link #decode} esta sincronizado porque reutiliza sus buffers de trabajo y no
 * reserva memoria en el bucle caliente. Java puro, sin clases de Android: se prueba sin Robolectric.
 * Todo ocurre en el telefono.
 */
final class SwipeDecoder {

    // ------------------------------------------------------------------ constantes de ajuste (un solo sitio)

    /** Puntos a los que se remuestrean el trazo y cada plantilla. */
    static final int N = 32;
    /** Tunel sin coste alrededor de la plantilla, en anchos de tecla. */
    static final float TUNNEL = 0.25f;
    /** Desviacion del canal de posicion, en anchos de tecla. */
    static final float SIGMA_LOC = 0.35f;
    /** Desviacion del canal de forma (polilineas normalizadas, adimensional). */
    static final float SIGMA_SHAPE = 0.10f;
    /** Peso de los dos primeros y los dos ultimos puntos en el canal de posicion. */
    static final float END_WEIGHT = 3f;
    /** Radio, en anchos de tecla, para elegir las posibles primera y ultima tecla del trazo. */
    static final float END_RADIUS = 0.8f;
    /** Tolerancia de longitud: max(LEN_TOL_KEYS * keyW, LEN_TOL_FRAC * longitud del trazo). */
    static final float LEN_TOL_KEYS = 1.5f;
    static final float LEN_TOL_FRAC = 0.30f;
    /** Se descarta el gesto (no se escribe nada) si la mejor puntuacion o su error de posicion superan esto. */
    static final float REJECT_SCORE = 24f;
    static final float REJECT_LOC = 1.2f;
    /** Pausa minima (ms) y desplazamiento maximo (anchos de tecla) que insinuan una letra doble. */
    static final long DWELL_MS = 150;
    static final float DWELL_MOVE = 0.25f;
    /** La pausa se atribuye a la tecla cuyo centro queda a menos de esto (anchos de tecla). */
    static final float DWELL_RADIUS = 0.6f;
    /** Ajuste de puntuacion por pausa: a favor de la letra doble, en contra de la simple. */
    static final float DWELL_BONUS = 2.0f;
    /** Lado del bucle con el que se dibuja una letra doble en la plantilla (fraccion de la tecla). */
    static final float LOOP = 0.25f;
    /** Resultados devueltos (el primero se escribe; el resto van a la barra). */
    static final int MAX_RESULTS = 4;
    /** Longitud maxima de palabra indexada. */
    static final int MAX_WORD = 24;
    /** Rango ficticio de las palabras aprendidas por el usuario. */
    static final int PERSONAL_RANK = 2000;

    private static final int TOP = MAX_RESULTS + 4;     // candidatos conservados antes de filtrar
    private static final int MAX_DWELLS = 4;
    private static final int TEMPLATE_CAP = MAX_WORD * 4 + 4;

    // ------------------------------------------------------------------ tipos

    /** Instantanea de las teclas de letras de la vista: letra, centro en px, y ancho y alto de una tecla. */
    static final class Geometry {
        final char[] letter;
        final float[] cx, cy;
        final float keyW, keyH;
        final float keyY;        // unidad vertical: la menor de las dos (en vertical, igual que el ancho; en apaisado, el alto)
        private final int[] index = new int[512];

        Geometry(char[] letter, float[] cx, float[] cy, float keyW, float keyH) {
            this.letter = letter; this.cx = cx; this.cy = cy; this.keyW = keyW; this.keyH = keyH;
            this.keyY = Math.min(keyW, keyH);
            java.util.Arrays.fill(index, -1);
            for (int i = 0; i < letter.length; i++) if (letter[i] < index.length) index[letter[i]] = i;
        }

        /** Posicion de la letra en los arrays, o -1 si no tiene tecla. */
        int indexOf(char c) { return c < index.length ? index[c] : -1; }

        /** Tecla mas cercana a un punto (indice), o -1 si no hay teclas. */
        int nearest(float x, float y) {
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < letter.length; i++) {
                float dx = (cx[i] - x) / keyW, dy = (cy[i] - y) / keyY, d = dx * dx + dy * dy;   // en teclas
                if (d < bd) { bd = d; best = i; }
            }
            return best;
        }

        boolean sameAs(Geometry o) {
            if (o == null || o.letter.length != letter.length || o.keyW != keyW || o.keyH != keyH) return false;
            for (int i = 0; i < letter.length; i++) {
                if (o.letter[i] != letter[i] || o.cx[i] != cx[i] || o.cy[i] != cy[i]) return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ indice

    private final Dictionary dict;
    private final Geometry geo;
    private final int keys;                  // numero de teclas de la geometria
    private final byte[] seq;                // secuencias de teclas de todas las palabras, una tras otra
    private final int[] offset;              // offset[r]..offset[r+1]: secuencia de la palabra de rango r (vacia si no se indexa)
    private final float[] idealLen;          // longitud de la polilinea ideal, en px
    private final byte[] doubles;            // numero de letras dobles de cada palabra
    private final int[][] bucket;            // [primera * keys + ultima] -> rangos, de mas a menos frecuente

    // Buffers de trabajo (reutilizados; de ahi que decode este sincronizado)
    private final float[] ux = new float[N], uy = new float[N], nux = new float[N], nuy = new float[N];
    private final float[] tx = new float[TEMPLATE_CAP], ty = new float[TEMPLATE_CAP];
    private final float[] rx = new float[N], ry = new float[N], ntx = new float[N], nty = new float[N];
    private final int[] topRank = new int[TOP];
    private final float[] topScore = new float[TOP], topLoc = new float[TOP];
    private final String[] topWord = new String[TOP];      // solo para palabras aprendidas (sin rango)
    private final int[] dwellKeys = new int[MAX_DWELLS];
    private final int[] startKeys = new int[8], endKeys = new int[8];
    private final byte[] tmpSeq = new byte[MAX_WORD];
    private float lastLoc;                                   // error de posicion del ultimo candidato puntuado

    /** Construye el indice para un diccionario y una geometria (unas decenas de ms; hacerlo fuera del hilo principal). */
    SwipeDecoder(Dictionary dict, Geometry geo) {
        this.dict = dict;
        this.geo = geo;
        keys = geo.letter.length;
        int count = dict.size();
        offset = new int[count + 1];
        idealLen = new float[count];
        doubles = new byte[count];
        byte[] buf = new byte[count * 8];
        int[] bucketSize = new int[keys * keys];
        int pos = 0;
        for (int r = 0; r < count; r++) {
            offset[r] = pos;
            String plain = Dictionary.deaccent(dict.word(r));
            int len = plain.length();
            if (len < 2 || len > MAX_WORD) continue;
            boolean ok = true;
            int dbl = 0, m = 0;
            for (int i = 0; i < len && ok; i++) {
                char ch = plain.charAt(i);
                if (ch == '\u00df') {                             // eszett sin tecla propia: se desliza como "ss"
                    int k = geo.indexOf('s');
                    if (k < 0 || m + 2 > MAX_WORD) { ok = false; break; }
                    tmpSeq[m++] = (byte) k;
                    tmpSeq[m++] = (byte) k;
                    dbl++;
                    continue;
                }
                if (ch == '\u00fc' && geo.indexOf('\u00fc') < 0) ch = 'u';
                int k = geo.indexOf(ch);
                if (k < 0 || m >= MAX_WORD) ok = false;
                else {
                    tmpSeq[m] = (byte) k;
                    if (m > 0 && tmpSeq[m - 1] == k) dbl++;
                    m++;
                }
            }
            if (!ok) continue;
            len = m;
            if (pos + len > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            System.arraycopy(tmpSeq, 0, buf, pos, len);
            pos += len;
            idealLen[r] = polylineLength(tmpSeq, len);
            doubles[r] = (byte) dbl;
            bucketSize[tmpSeq[0] * keys + tmpSeq[len - 1]]++;
        }
        offset[count] = pos;
        seq = java.util.Arrays.copyOf(buf, pos);
        bucket = new int[keys * keys][];
        for (int b = 0; b < bucket.length; b++) bucket[b] = new int[bucketSize[b]];
        int[] fill = new int[keys * keys];
        for (int r = 0; r < count; r++) {
            int len = offset[r + 1] - offset[r];
            if (len == 0) continue;
            int b = seq[offset[r]] * keys + seq[offset[r + 1] - 1];
            bucket[b][fill[b]++] = r;
        }
    }

    /** El indice sirve para este diccionario y esta geometria tal cual. */
    boolean matches(Dictionary d, Geometry g) { return d == dict && geo.sameAs(g); }

    Geometry geometry() { return geo; }

    String language() { return dict.lang; }

    private float polylineLength(byte[] s, int len) {
        float l = 0;
        for (int i = 1; i < len; i++) {
            float dx = geo.cx[s[i]] - geo.cx[s[i - 1]], dy = geo.cy[s[i]] - geo.cy[s[i - 1]];
            l += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return l;
    }

    // ------------------------------------------------------------------ reconocimiento

    /**
     * Reconoce un trazo (puntos en px de la vista, tiempos en ms) y devuelve hasta {@link #MAX_RESULTS}
     * palabras, la mejor primero, en la forma del diccionario (con tildes; las contracciones inglesas con
     * apostrofo). Lista vacia si el gesto no se parece a ninguna palabra. Sin reservas en el bucle caliente.
     */
    synchronized List<String> decode(float[] x, float[] y, long[] t, int n) {
        List<String> out = new ArrayList<>(MAX_RESULTS + 1);
        if (n < 2 || keys == 0) return out;
        float keyW = geo.keyW;
        float len = 0;
        for (int i = 1; i < n; i++) {
            float dx = x[i] - x[i - 1], dy = y[i] - y[i - 1];
            len += (float) Math.sqrt(dx * dx + dy * dy);
        }
        if (len <= 0) return out;
        resample(x, y, n, ux, uy);
        normalize(ux, uy, nux, nuy, keyW);
        int ns = keysNear(x[0], y[0], END_RADIUS * keyW, startKeys);
        int ne = keysNear(x[n - 1], y[n - 1], END_RADIUS * keyW, endKeys);
        int dwells = findDwells(x, y, t, n, keyW, dwellKeys);
        float lenTol = Math.max(LEN_TOL_KEYS * keyW, LEN_TOL_FRAC * len);
        float loopLen = 2 * LOOP * (geo.keyW + geo.keyH) * 2;     // perimetro aproximado de un bucle
        int maxLetters = (int) (len / keyW) + 3;
        int found = 0;
        for (int a = 0; a < ns; a++) {
            for (int b = 0; b < ne; b++) {
                int[] ranks = bucket[startKeys[a] * keys + endKeys[b]];
                for (int ri = 0; ri < ranks.length; ri++) {
                    int r = ranks[ri];
                    int off = offset[r], wl = offset[r + 1] - off;
                    if (wl > maxLetters) continue;
                    float il = idealLen[r];
                    boolean lenOk = Math.abs(il - len) <= lenTol
                            || (doubles[r] > 0 && Math.abs(il + doubles[r] * loopLen - len) <= lenTol);
                    if (!lenOk) continue;
                    if (found >= TOP && Math.log(r + 1) - DWELL_BONUS * dwells >= topScore[TOP - 1]) continue;   // ni perfecto entraria
                    float s = score(off, wl, doubles[r], dwells);
                    s += (float) Math.log(r + 1);
                    found = insert(found, r, null, s, lastLoc);
                }
            }
        }
        // Palabras aprendidas: pocas, se recorren enteras con un rango ficticio
        Set<String> personal = dict.personal();
        if (!personal.isEmpty()) {
            float pLog = (float) Math.log(PERSONAL_RANK + 1);
            for (String p : personal) {
                String plain = Dictionary.deaccent(p);
                int wl = plain.length();
                if (wl < 2 || wl > MAX_WORD || wl > maxLetters || dict.rankOf(p) >= 0) continue;
                boolean ok = true;
                int dbl = 0, m = 0;
                for (int i = 0; i < wl && ok; i++) {
                    char ch = plain.charAt(i);
                    if (ch == '\u00df') {
                        int k = geo.indexOf('s');
                        if (k < 0 || m + 2 > MAX_WORD) { ok = false; break; }
                        tmpSeq[m++] = (byte) k; tmpSeq[m++] = (byte) k; dbl++;
                        continue;
                    }
                    if (ch == '\u00fc' && geo.indexOf('\u00fc') < 0) ch = 'u';
                    int k = geo.indexOf(ch);
                    if (k < 0 || m >= MAX_WORD || m >= maxLetters) ok = false;
                    else { tmpSeq[m] = (byte) k; if (m > 0 && tmpSeq[m - 1] == k) dbl++; m++; }
                }
                wl = m;
                if (!ok || wl < 2 || !contains(startKeys, ns, tmpSeq[0]) || !contains(endKeys, ne, tmpSeq[wl - 1])) continue;
                float il = polylineLength(tmpSeq, wl);
                if (Math.abs(il - len) > lenTol && !(dbl > 0 && Math.abs(il + dbl * loopLen - len) <= lenTol)) continue;
                float s = scoreSeq(tmpSeq, 0, wl, dbl, dwells) + pLog;
                found = insert(found, -1, p, s, lastLoc);
            }
        }
        if (found == 0) return out;
        // Filtro de palabras vetadas y rechazo del gesto
        int first = -1;
        for (int i = 0; i < found; i++) {
            String w = topWord[i] != null ? topWord[i] : dict.word(topRank[i]);
            if (Dictionary.noSuggest(w)) continue;
            first = i;
            break;
        }
        if (first < 0 || topScore[first] > REJECT_SCORE || topLoc[first] > REJECT_LOC) return out;
        for (int i = first; i < found && out.size() < MAX_RESULTS; i++) {
            String w = topWord[i] != null ? topWord[i] : dict.word(topRank[i]);
            if (Dictionary.noSuggest(w) || out.contains(w)) continue;
            out.add(w);
        }
        // La hermana con o sin tilde de la mejor (misma secuencia de teclas) siempre va justo despues
        if (topWord[first] == null) {
            String sibling = sibling(topRank[first]);
            if (sibling != null && !out.contains(sibling)) {
                if (out.size() >= MAX_RESULTS) out.remove(out.size() - 1);
                out.add(Math.min(1, out.size()), sibling);
            }
        }
        for (int i = 0; i < out.size(); i++) {
            String c = dict.contraction(out.get(i));
            if (c != null) out.set(i, c);
        }
        return out;
    }

    /** Palabra mas frecuente distinta de {@code r} con exactamente la misma secuencia de teclas, o null. */
    private String sibling(int r) {
        int off = offset[r], wl = offset[r + 1] - off;
        int[] ranks = bucket[seq[off] * keys + seq[off + wl - 1]];
        for (int i = 0; i < ranks.length; i++) {
            int o = ranks[i];
            if (o == r || offset[o + 1] - offset[o] != wl) continue;
            int oo = offset[o];
            boolean same = true;
            for (int k = 0; k < wl && same; k++) same = seq[oo + k] == seq[off + k];
            if (same) {
                String w = dict.word(o);
                if (!Dictionary.noSuggest(w)) return w;
            }
        }
        return null;
    }

    private static boolean contains(int[] arr, int n, int v) {
        for (int i = 0; i < n; i++) if (arr[i] == v) return true;
        return false;
    }

    /** Teclas cuyo centro queda a menos de {@code radius} del punto; si ninguna, la mas cercana. */
    private int keysNear(float x, float y, float radius, int[] out) {
        int n = 0;
        float rk = radius / geo.keyW;                       // radio en teclas: anisotropo (ancho y alto distintos)
        float r2 = rk * rk;
        for (int i = 0; i < keys && n < out.length; i++) {
            float dx = (geo.cx[i] - x) / geo.keyW, dy = (geo.cy[i] - y) / geo.keyY;
            if (dx * dx + dy * dy <= r2) out[n++] = i;
        }
        if (n == 0) { out[0] = geo.nearest(x, y); n = 1; }
        return n;
    }

    /**
     * Pausas del dedo (DWELL_MS sin moverse mas de DWELL_MOVE teclas) lejos de los extremos del trazo,
     * atribuidas a la tecla mas cercana. Devuelve cuantas teclas distintas anoto en {@code out}.
     */
    private int findDwells(float[] x, float[] y, long[] t, int n, float keyW, int[] out) {
        int count = 0;
        float maxMove2 = DWELL_MOVE * DWELL_MOVE;            // distancias al cuadrado en teclas
        float end2 = END_RADIUS * END_RADIUS;
        int i = 0;
        while (i < n - 1 && count < out.length) {
            int j = i + 1;
            boolean still = true;
            while (j < n && t[j] - t[i] < DWELL_MS) {
                if (keyDist2(x[i], y[i], x[j], y[j]) > maxMove2) { still = false; break; }
                j++;
            }
            if (!still || j >= n || keyDist2(x[i], y[i], x[j], y[j]) > maxMove2) { i++; continue; }
            while (j + 1 < n && keyDist2(x[i], y[i], x[j + 1], y[j + 1]) <= maxMove2) j++;
            float mx = (x[i] + x[j]) / 2f, my = (y[i] + y[j]) / 2f;
            if (keyDist2(mx, my, x[0], y[0]) > end2 && keyDist2(mx, my, x[n - 1], y[n - 1]) > end2) {
                int k = geo.nearest(mx, my);
                if (k >= 0 && keyDist2(mx, my, geo.cx[k], geo.cy[k]) <= DWELL_RADIUS * DWELL_RADIUS
                        && !contains(out, count, k)) out[count++] = k;
            }
            i = j + 1;
        }
        return count;
    }

    /** Distancia al cuadrado en unidades de tecla (ancho y alto por separado). */
    private float keyDist2(float ax, float ay, float bx, float by) {
        float dx = (ax - bx) / geo.keyW, dy = (ay - by) / geo.keyY;
        return dx * dx + dy * dy;
    }

    private static float dist2(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    /** Puntuacion geometrica de la palabra de secuencia seq[off..off+wl) (sin el termino de frecuencia). */
    private float score(int off, int wl, int dbl, int dwells) { return scoreSeq(seq, off, wl, dbl, dwells); }

    private float scoreSeq(byte[] s, int off, int wl, int dbl, int dwells) {
        int m = 0;
        for (int i = 0; i < wl; i++) { int k = s[off + i]; tx[m] = geo.cx[k]; ty[m] = geo.cy[k]; m++; }
        float best = channels(m);
        float bestLoc = lastLoc;
        if (dbl > 0) {                                   // variante con un bucle en cada letra doble
            m = 0;
            float lx = LOOP * geo.keyW, ly = LOOP * geo.keyH;
            for (int i = 0; i < wl; i++) {
                int k = s[off + i];
                float cx = geo.cx[k], cy = geo.cy[k];
                if (i > 0 && s[off + i - 1] == k) {
                    tx[m] = cx + lx; ty[m] = cy + ly; m++;
                    tx[m] = cx + lx; ty[m] = cy - ly; m++;
                    tx[m] = cx - lx; ty[m] = cy - ly; m++;
                    tx[m] = cx - lx; ty[m] = cy + ly; m++;
                } else { tx[m] = cx; ty[m] = cy; m++; }
            }
            float alt = channels(m);
            if (alt < best) { best = alt; bestLoc = lastLoc; }
        }
        lastLoc = bestLoc;
        for (int d = 0; d < dwells; d++) {               // una pausa insinua una letra doble en esa tecla
            int k = dwellKeys[d];
            boolean has = false, twice = false;
            for (int i = 0; i < wl; i++) {
                if (s[off + i] == k) { has = true; if (i > 0 && s[off + i - 1] == k) twice = true; }
            }
            if (twice) best -= DWELL_BONUS;
            else if (has) best += DWELL_BONUS;
        }
        return best;
    }

    /** Dos canales gaussianos sobre la plantilla tx,ty[0..m); deja el error de posicion en lastLoc. */
    private float channels(int m) {
        resample(tx, ty, m, rx, ry);
        normalize(rx, ry, ntx, nty, geo.keyW);
        float keyW = geo.keyW;
        float loc = 0, wsum = 0, shape = 0;
        for (int i = 0; i < N; i++) {
            float w = (i < 2 || i >= N - 2) ? END_WEIGHT : 1f;
            float ex = (ux[i] - rx[i]) / keyW, ey = (uy[i] - ry[i]) / geo.keyY;   // en teclas, cada eje con su tamano
            float d = (float) Math.sqrt(ex * ex + ey * ey) - TUNNEL;
            if (d > 0) loc += w * d;
            wsum += w;
            float sx = nux[i] - ntx[i], sy = nuy[i] - nty[i];
            shape += (float) Math.sqrt(sx * sx + sy * sy);
        }
        loc /= wsum;
        shape /= N;
        lastLoc = loc;
        float a = loc / SIGMA_LOC, b = shape / SIGMA_SHAPE;
        return 0.5f * (a * a + b * b);
    }

    /** Inserta en la lista ordenada de mejores (por puntuacion); devuelve el nuevo tamano. */
    private int insert(int found, int rank, String word, float score, float loc) {
        if (found >= TOP && score >= topScore[TOP - 1]) return found;
        int i = Math.min(found, TOP - 1);
        while (i > 0 && topScore[i - 1] > score) {
            topScore[i] = topScore[i - 1]; topRank[i] = topRank[i - 1]; topLoc[i] = topLoc[i - 1]; topWord[i] = topWord[i - 1];
            i--;
        }
        topScore[i] = score; topRank[i] = rank; topLoc[i] = loc; topWord[i] = word;
        return Math.min(found + 1, TOP);
    }

    // ------------------------------------------------------------------ geometria

    /** Remuestrea la polilinea (px,py)[0..m) a N puntos equidistantes en ox,oy. */
    static void resample(float[] px, float[] py, int m, float[] ox, float[] oy) {
        if (m <= 0) return;
        float total = 0;
        for (int i = 1; i < m; i++) {
            float dx = px[i] - px[i - 1], dy = py[i] - py[i - 1];
            total += (float) Math.sqrt(dx * dx + dy * dy);
        }
        ox[0] = px[0]; oy[0] = py[0];
        int k = 1;
        if (total > 0) {
            float step = total / (N - 1), acc = 0;
            float ax = px[0], ay = py[0];
            for (int i = 1; i < m && k < N; i++) {
                float bx = px[i], by = py[i];
                float dx = bx - ax, dy = by - ay;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                while (acc + d >= step && k < N && d > 0) {
                    float f = (step - acc) / d;
                    float qx = ax + f * dx, qy = ay + f * dy;
                    ox[k] = qx; oy[k] = qy; k++;
                    d -= step - acc;
                    acc = 0;
                    ax = qx; ay = qy;
                    dx = bx - ax; dy = by - ay;
                }
                acc += d;
                ax = bx; ay = by;
            }
        }
        while (k < N) { ox[k] = px[m - 1]; oy[k] = py[m - 1]; k++; }
    }

    /** Traslada al centroide y divide por max(ancho, alto, minScale): forma sin posicion ni tamano. */
    static void normalize(float[] x, float[] y, float[] ox, float[] oy, float minScale) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sx = 0, sy = 0;
        for (int i = 0; i < N; i++) {
            float px = x[i], py = y[i];
            if (px < minX) minX = px; if (px > maxX) maxX = px;
            if (py < minY) minY = py; if (py > maxY) maxY = py;
            sx += px; sy += py;
        }
        float mx = sx / N, my = sy / N;
        float s = Math.max(Math.max(maxX - minX, maxY - minY), minScale);
        for (int i = 0; i < N; i++) { ox[i] = (x[i] - mx) / s; oy[i] = (y[i] - my) / s; }
    }
}
