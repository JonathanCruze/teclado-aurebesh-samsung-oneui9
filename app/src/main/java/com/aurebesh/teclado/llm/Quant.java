package com.aurebesh.teclado.llm;

/**
 * Formatos de cuantizacion de ggml: tamanos de bloque, descuantizacion de una fila y productos
 * escalares fila-por-fila contra activaciones cuantizadas a int8 (estilo vec_dot_*_q8 de ggml).
 * Los pesos nunca se descuantizan enteros: cada hilo trabaja con una fila copiada a un byte[].
 *
 * Disposiciones de bloque transcritas de ggml-common.h / ggml-quants.c:
 *   Q4_0 {f16 d; u8 qs[16]} 18 B · Q4_1 {d; m; qs[16]} 20 B · Q5_0 {d; u8 qh[4]; qs[16]} 22 B ·
 *   Q5_1 {d; m; qh[4]; qs[16]} 24 B · Q8_0 {d; i8 qs[32]} 34 B · Q4_K {d; dmin; u8 scales[12]; qs[128]} 144 B ·
 *   Q5_K {d; dmin; scales[12]; qh[32]; qs[128]} 176 B · Q6_K {ql[128]; qh[64]; i8 sc[16]; d} 210 B.
 */
final class Quant {

    static final int F32 = 0, F16 = 1, Q4_0 = 2, Q4_1 = 3, Q5_0 = 6, Q5_1 = 7, Q8_0 = 8,
            Q4_K = 12, Q5_K = 13, Q6_K = 14, BF16 = 30;

    /** Tamano del bloque de activaciones int8 (coincide con los bloques de 32 de ggml). */
    static final int QB = 32;

    private Quant() {
    }

    static boolean supported(int t) {
        switch (t) {
            case F32: case F16: case BF16: case Q4_0: case Q4_1: case Q5_0: case Q5_1: case Q8_0:
            case Q4_K: case Q5_K: case Q6_K:
                return true;
            default:
                return false;
        }
    }

    static boolean isFloat(int t) {
        return t == F32 || t == F16 || t == BF16;
    }

    static int blockSize(int t) {
        switch (t) {
            case F32: case F16: case BF16: return 1;
            case Q4_0: case Q4_1: case Q5_0: case Q5_1: case Q8_0: return 32;
            case Q4_K: case Q5_K: case Q6_K: return 256;
            default: return 1;
        }
    }

    static int typeSize(int t) {
        switch (t) {
            case F32: return 4;
            case F16: case BF16: return 2;
            case Q4_0: return 18;
            case Q4_1: return 20;
            case Q5_0: return 22;
            case Q5_1: return 24;
            case Q8_0: return 34;
            case Q4_K: return 144;
            case Q5_K: return 176;
            case Q6_K: return 210;
            default: return 1;
        }
    }

    static String name(int t) {
        switch (t) {
            case F32: return "F32";
            case F16: return "F16";
            case BF16: return "BF16";
            case Q4_0: return "Q4_0";
            case Q4_1: return "Q4_1";
            case Q5_0: return "Q5_0";
            case Q5_1: return "Q5_1";
            case Q8_0: return "Q8_0";
            case Q4_K: return "Q4_K";
            case Q5_K: return "Q5_K";
            case Q6_K: return "Q6_K";
            case 10: return "Q2_K";
            case 11: return "Q3_K";
            default: return "tipo " + t;
        }
    }

    // ------------------------------------------------------------------ activaciones int8

    /**
     * Varios vectores de activacion (count vectores de n floats, n multiplo de 32) cuantizados a
     * bloques int8 de 32: q = round(x / d), d = max|x| / 127. Se guardan ademas las sumas por bloque
     * (para los minimos de Q4_1/Q5_1/Q4_K/Q5_K y el desplazamiento de Q4_0/Q5_0) y por medio bloque
     * (para los 16 grupos de Q6_K). Tambien conserva los floats originales para filas F32/F16/BF16.
     */
    static final class QVec {
        final int n;
        final int nb;           // n / 32
        final int count;
        final float[] f;        // count * n
        final byte[] q;         // count * n
        final float[] d;        // count * nb
        final int[] bsum;       // count * nb      suma de los 32 int8 del bloque
        final int[] hsum;       // count * nb * 2  suma de cada mitad de 16

        QVec(int n, int count) {
            this.n = n;
            this.nb = n / QB;
            this.count = count;
            this.f = new float[n * count];
            this.q = new byte[n * count];
            this.d = new float[nb * count];
            this.bsum = new int[nb * count];
            this.hsum = new int[nb * 2 * count];
        }

        /** Copia src[srcOff..srcOff+n) como vector t y lo cuantiza. */
        void set(float[] src, int srcOff, int t) {
            System.arraycopy(src, srcOff, f, t * n, n);
            quantize(t);
        }

        void quantize(int t) {
            int fo = t * n;
            int bo = t * nb;
            for (int b = 0; b < nb; b++) {
                int o = fo + b * QB;
                float amax = 0f;
                for (int j = 0; j < QB; j++) {
                    float a = Math.abs(f[o + j]);
                    if (a > amax) amax = a;
                }
                float dd = amax / 127f;
                float id = dd != 0f ? 1f / dd : 0f;
                int s0 = 0, s1 = 0;
                for (int j = 0; j < 16; j++) {
                    int v = Math.round(f[o + j] * id);
                    q[o + j] = (byte) v;
                    s0 += v;
                }
                for (int j = 16; j < QB; j++) {
                    int v = Math.round(f[o + j] * id);
                    q[o + j] = (byte) v;
                    s1 += v;
                }
                d[bo + b] = dd;
                bsum[bo + b] = s0 + s1;
                hsum[(bo + b) * 2] = s0;
                hsum[(bo + b) * 2 + 1] = s1;
            }
        }
    }

    /** True si el tipo tiene camino rapido con activaciones int8 (todos los cuantizados). */
    static boolean hasInt8Path(int type) {
        return !isFloat(type);
    }

    /** Producto escalar de la fila w (tipo type, n elementos) con el vector t de x. */
    static float dot(int type, byte[] w, int n, QVec x, int t) {
        switch (type) {
            case Q8_0: return dotQ8_0(w, n, x.q, t * n, x.d, t * x.nb);
            case Q4_0: return dotQ4_0(w, n, x.q, t * n, x.d, x.bsum, t * x.nb);
            case Q4_1: return dotQ4_1(w, n, x.q, t * n, x.d, x.bsum, t * x.nb);
            case Q5_0: return dotQ5_0(w, n, x.q, t * n, x.d, x.bsum, t * x.nb);
            case Q5_1: return dotQ5_1(w, n, x.q, t * n, x.d, x.bsum, t * x.nb);
            case Q4_K: return dotQ4_K(w, n, x.q, t * n, x.d, x.bsum, t * x.nb);
            case Q5_K: return dotQ5_K(w, n, x.q, t * n, x.d, x.bsum, t * x.nb);
            case Q6_K: return dotQ6_K(w, n, x.q, t * n, x.d, x.hsum, t * x.nb);
            case F32: return dotF32(w, n, x.f, t * n);
            case F16: return dotF16(w, n, x.f, t * n);
            case BF16: return dotBF16(w, n, x.f, t * n);
            default: throw new IllegalArgumentException("tipo " + type);
        }
    }

    static float dotF32(byte[] w, int n, float[] x, int xo) {
        float s0 = 0f, s1 = 0f, s2 = 0f, s3 = 0f;
        int j = 0;
        for (; j + 3 < n; j += 4) {
            int p = j << 2;
            s0 += Float.intBitsToFloat((w[p] & 0xFF) | (w[p + 1] & 0xFF) << 8 | (w[p + 2] & 0xFF) << 16 | w[p + 3] << 24) * x[xo + j];
            s1 += Float.intBitsToFloat((w[p + 4] & 0xFF) | (w[p + 5] & 0xFF) << 8 | (w[p + 6] & 0xFF) << 16 | w[p + 7] << 24) * x[xo + j + 1];
            s2 += Float.intBitsToFloat((w[p + 8] & 0xFF) | (w[p + 9] & 0xFF) << 8 | (w[p + 10] & 0xFF) << 16 | w[p + 11] << 24) * x[xo + j + 2];
            s3 += Float.intBitsToFloat((w[p + 12] & 0xFF) | (w[p + 13] & 0xFF) << 8 | (w[p + 14] & 0xFF) << 16 | w[p + 15] << 24) * x[xo + j + 3];
        }
        for (; j < n; j++) {
            int p = j << 2;
            s0 += Float.intBitsToFloat((w[p] & 0xFF) | (w[p + 1] & 0xFF) << 8 | (w[p + 2] & 0xFF) << 16 | w[p + 3] << 24) * x[xo + j];
        }
        return (s0 + s1) + (s2 + s3);
    }

    static float dotF16(byte[] w, int n, float[] x, int xo) {
        final float[] tab = Fp16.TABLE;
        float s0 = 0f, s1 = 0f, s2 = 0f, s3 = 0f;
        int j = 0;
        for (; j + 3 < n; j += 4) {
            int p = j << 1;
            s0 += tab[(w[p] & 0xFF) | (w[p + 1] & 0xFF) << 8] * x[xo + j];
            s1 += tab[(w[p + 2] & 0xFF) | (w[p + 3] & 0xFF) << 8] * x[xo + j + 1];
            s2 += tab[(w[p + 4] & 0xFF) | (w[p + 5] & 0xFF) << 8] * x[xo + j + 2];
            s3 += tab[(w[p + 6] & 0xFF) | (w[p + 7] & 0xFF) << 8] * x[xo + j + 3];
        }
        for (; j < n; j++) {
            int p = j << 1;
            s0 += tab[(w[p] & 0xFF) | (w[p + 1] & 0xFF) << 8] * x[xo + j];
        }
        return (s0 + s1) + (s2 + s3);
    }

    static float dotBF16(byte[] w, int n, float[] x, int xo) {
        float s = 0f;
        for (int j = 0; j < n; j++) {
            s += Fp16.bf16(w, j << 1) * x[xo + j];
        }
        return s;
    }

    static float dotQ8_0(byte[] w, int n, byte[] q, int qo, float[] d, int bo) {
        float sum = 0f;
        int nb = n >> 5;
        int wo = 0;
        for (int b = 0; b < nb; b++, wo += 34, qo += 32) {
            int a0 = 0, a1 = 0, a2 = 0, a3 = 0;
            int p = wo + 2;
            for (int j = 0; j < 32; j += 4) {
                a0 += w[p + j] * q[qo + j];
                a1 += w[p + j + 1] * q[qo + j + 1];
                a2 += w[p + j + 2] * q[qo + j + 2];
                a3 += w[p + j + 3] * q[qo + j + 3];
            }
            sum += Fp16.at(w, wo) * d[bo + b] * ((a0 + a1) + (a2 + a3));
        }
        return sum;
    }

    static float dotQ4_0(byte[] w, int n, byte[] q, int qo, float[] d, int[] bsum, int bo) {
        float sum = 0f;
        int nb = n >> 5;
        int wo = 0;
        for (int b = 0; b < nb; b++, wo += 18, qo += 32) {
            int a0 = 0, a1 = 0;
            int p = wo + 2;
            for (int j = 0; j < 16; j++) {
                int v = w[p + j];
                a0 += (v & 0xF) * q[qo + j];
                a1 += ((v >> 4) & 0xF) * q[qo + 16 + j];
            }
            int acc = a0 + a1 - 8 * bsum[bo + b];
            sum += Fp16.at(w, wo) * d[bo + b] * acc;
        }
        return sum;
    }

    static float dotQ4_1(byte[] w, int n, byte[] q, int qo, float[] d, int[] bsum, int bo) {
        float sum = 0f;
        int nb = n >> 5;
        int wo = 0;
        for (int b = 0; b < nb; b++, wo += 20, qo += 32) {
            int a0 = 0, a1 = 0;
            int p = wo + 4;
            for (int j = 0; j < 16; j++) {
                int v = w[p + j];
                a0 += (v & 0xF) * q[qo + j];
                a1 += ((v >> 4) & 0xF) * q[qo + 16 + j];
            }
            sum += d[bo + b] * (Fp16.at(w, wo) * (a0 + a1) + Fp16.at(w, wo + 2) * bsum[bo + b]);
        }
        return sum;
    }

    static float dotQ5_0(byte[] w, int n, byte[] q, int qo, float[] d, int[] bsum, int bo) {
        float sum = 0f;
        int nb = n >> 5;
        int wo = 0;
        for (int b = 0; b < nb; b++, wo += 22, qo += 32) {
            int qh = (w[wo + 2] & 0xFF) | (w[wo + 3] & 0xFF) << 8 | (w[wo + 4] & 0xFF) << 16 | (w[wo + 5] & 0xFF) << 24;
            int a0 = 0, a1 = 0;
            int p = wo + 6;
            for (int j = 0; j < 16; j++) {
                int v = w[p + j];
                int lo = (v & 0xF) | (((qh >>> j) << 4) & 0x10);
                int hi = ((v >> 4) & 0xF) | ((qh >>> (j + 12)) & 0x10);
                a0 += lo * q[qo + j];
                a1 += hi * q[qo + 16 + j];
            }
            int acc = a0 + a1 - 16 * bsum[bo + b];
            sum += Fp16.at(w, wo) * d[bo + b] * acc;
        }
        return sum;
    }

    static float dotQ5_1(byte[] w, int n, byte[] q, int qo, float[] d, int[] bsum, int bo) {
        float sum = 0f;
        int nb = n >> 5;
        int wo = 0;
        for (int b = 0; b < nb; b++, wo += 24, qo += 32) {
            int qh = (w[wo + 4] & 0xFF) | (w[wo + 5] & 0xFF) << 8 | (w[wo + 6] & 0xFF) << 16 | (w[wo + 7] & 0xFF) << 24;
            int a0 = 0, a1 = 0;
            int p = wo + 8;
            for (int j = 0; j < 16; j++) {
                int v = w[p + j];
                int lo = (v & 0xF) | (((qh >>> j) << 4) & 0x10);
                int hi = ((v >> 4) & 0xF) | ((qh >>> (j + 12)) & 0x10);
                a0 += lo * q[qo + j];
                a1 += hi * q[qo + 16 + j];
            }
            sum += d[bo + b] * (Fp16.at(w, wo) * (a0 + a1) + Fp16.at(w, wo + 2) * bsum[bo + b]);
        }
        return sum;
    }

    /** Escala (bits bajos) y minimo de 6 bits del sub-bloque j de Q4_K/Q5_K, empaquetados como sc | m << 8. */
    private static int scaleMinK4(byte[] s, int so, int j) {
        int sc, m;
        if (j < 4) {
            sc = s[so + j] & 63;
            m = s[so + j + 4] & 63;
        } else {
            sc = (s[so + j + 4] & 0xF) | (((s[so + j - 4] & 0xFF) >> 6) << 4);
            m = ((s[so + j + 4] & 0xFF) >> 4) | (((s[so + j] & 0xFF) >> 6) << 4);
        }
        return sc | (m << 8);
    }

    static float dotQ4_K(byte[] w, int n, byte[] q, int qo, float[] d, int[] bsum, int bo) {
        float sum = 0f;
        int nsb = n >> 8;    // superbloques de 256
        int wo = 0;
        for (int sb = 0; sb < nsb; sb++, wo += 144) {
            float dd = Fp16.at(w, wo);
            float dmin = Fp16.at(w, wo + 2);
            int so = wo + 4;
            int p = wo + 16;
            int xb = bo + sb * 8;        // primer bloque de activacion de este superbloque
            int xq = qo + sb * 256;
            for (int is = 0; is < 8; is += 2, p += 32, xq += 64) {
                int sm1 = scaleMinK4(w, so, is);
                int sm2 = scaleMinK4(w, so, is + 1);
                int a0 = 0, a1 = 0;
                for (int j = 0; j < 32; j++) {
                    int v = w[p + j];
                    a0 += (v & 0xF) * q[xq + j];
                    a1 += ((v >> 4) & 0xF) * q[xq + 32 + j];
                }
                sum += d[xb + is] * (dd * (sm1 & 0xFF) * a0 - dmin * (sm1 >> 8) * bsum[xb + is])
                        + d[xb + is + 1] * (dd * (sm2 & 0xFF) * a1 - dmin * (sm2 >> 8) * bsum[xb + is + 1]);
            }
        }
        return sum;
    }

    static float dotQ5_K(byte[] w, int n, byte[] q, int qo, float[] d, int[] bsum, int bo) {
        float sum = 0f;
        int nsb = n >> 8;
        int wo = 0;
        for (int sb = 0; sb < nsb; sb++, wo += 176) {
            float dd = Fp16.at(w, wo);
            float dmin = Fp16.at(w, wo + 2);
            int so = wo + 4;
            int qh = wo + 16;
            int p = wo + 48;
            int xb = bo + sb * 8;
            int xq = qo + sb * 256;
            int u1 = 1, u2 = 2;
            for (int is = 0; is < 8; is += 2, p += 32, xq += 64, u1 <<= 2, u2 <<= 2) {
                int sm1 = scaleMinK4(w, so, is);
                int sm2 = scaleMinK4(w, so, is + 1);
                int a0 = 0, a1 = 0;
                for (int j = 0; j < 32; j++) {
                    int v = w[p + j];
                    int h = w[qh + j];
                    int lo = (v & 0xF) + ((h & u1) != 0 ? 16 : 0);
                    int hi = ((v >> 4) & 0xF) + ((h & u2) != 0 ? 16 : 0);
                    a0 += lo * q[xq + j];
                    a1 += hi * q[xq + 32 + j];
                }
                sum += d[xb + is] * (dd * (sm1 & 0xFF) * a0 - dmin * (sm1 >> 8) * bsum[xb + is])
                        + d[xb + is + 1] * (dd * (sm2 & 0xFF) * a1 - dmin * (sm2 >> 8) * bsum[xb + is + 1]);
            }
        }
        return sum;
    }

    static float dotQ6_K(byte[] w, int n, byte[] q, int qo, float[] d, int[] hsum, int bo) {
        float sum = 0f;
        int nsb = n >> 8;
        int wo = 0;
        for (int sb = 0; sb < nsb; sb++, wo += 210) {
            float dd = Fp16.at(w, wo + 208);
            int xb = bo + sb * 8;
            int xq = qo + sb * 256;
            for (int half = 0; half < 2; half++) {
                int ql = wo + half * 64;
                int qh = wo + 128 + half * 32;
                int so = wo + 192 + half * 8;
                int x0 = xq + half * 128;
                // 8 grupos de 16 elementos: (q1: 0..31) (q2: 32..63) (q3: 64..95) (q4: 96..127)
                int g0 = 0, g1 = 0, g2 = 0, g3 = 0, g4 = 0, g5 = 0, g6 = 0, g7 = 0;
                for (int l = 0; l < 16; l++) {
                    int la = w[ql + l] & 0xFF, lb = w[ql + l + 32] & 0xFF, h = w[qh + l] & 0xFF;
                    g0 += ((la & 0xF) | ((h & 3) << 4)) * q[x0 + l];
                    g2 += ((lb & 0xF) | (((h >> 2) & 3) << 4)) * q[x0 + 32 + l];
                    g4 += ((la >> 4) | (((h >> 4) & 3) << 4)) * q[x0 + 64 + l];
                    g6 += ((lb >> 4) | ((h >> 6) << 4)) * q[x0 + 96 + l];
                }
                for (int l = 16; l < 32; l++) {
                    int la = w[ql + l] & 0xFF, lb = w[ql + l + 32] & 0xFF, h = w[qh + l] & 0xFF;
                    g1 += ((la & 0xF) | ((h & 3) << 4)) * q[x0 + l];
                    g3 += ((lb & 0xF) | (((h >> 2) & 3) << 4)) * q[x0 + 32 + l];
                    g5 += ((la >> 4) | (((h >> 4) & 3) << 4)) * q[x0 + 64 + l];
                    g7 += ((lb >> 4) | ((h >> 6) << 4)) * q[x0 + 96 + l];
                }
                int b = xb + half * 4;
                int hs = b * 2;
                // (q - 32) * x = q*x - 32 * suma(x) por grupo de 16
                float s = d[b] * (w[so] * (g0 - 32 * hsum[hs]) + w[so + 1] * (g1 - 32 * hsum[hs + 1]))
                        + d[b + 1] * (w[so + 2] * (g2 - 32 * hsum[hs + 2]) + w[so + 3] * (g3 - 32 * hsum[hs + 3]))
                        + d[b + 2] * (w[so + 4] * (g4 - 32 * hsum[hs + 4]) + w[so + 5] * (g5 - 32 * hsum[hs + 5]))
                        + d[b + 3] * (w[so + 6] * (g6 - 32 * hsum[hs + 6]) + w[so + 7] * (g7 - 32 * hsum[hs + 7]));
                sum += dd * s;
            }
        }
        return sum;
    }

    // ------------------------------------------------------------------ descuantizacion exacta

    /** Descuantiza una fila completa (n elementos) a out[outOff..outOff+n). Exacto respecto a ggml. */
    static void dequantRow(int type, byte[] w, int n, float[] out, int outOff) {
        switch (type) {
            case F32:
                for (int j = 0; j < n; j++) {
                    int p = j << 2;
                    out[outOff + j] = Float.intBitsToFloat((w[p] & 0xFF) | (w[p + 1] & 0xFF) << 8 | (w[p + 2] & 0xFF) << 16 | w[p + 3] << 24);
                }
                return;
            case F16:
                for (int j = 0; j < n; j++) out[outOff + j] = Fp16.at(w, j << 1);
                return;
            case BF16:
                for (int j = 0; j < n; j++) out[outOff + j] = Fp16.bf16(w, j << 1);
                return;
            case Q8_0:
                for (int b = 0, wo = 0, o = outOff; b < n >> 5; b++, wo += 34, o += 32) {
                    float d = Fp16.at(w, wo);
                    for (int j = 0; j < 32; j++) out[o + j] = w[wo + 2 + j] * d;
                }
                return;
            case Q4_0:
                for (int b = 0, wo = 0, o = outOff; b < n >> 5; b++, wo += 18, o += 32) {
                    float d = Fp16.at(w, wo);
                    for (int j = 0; j < 16; j++) {
                        int v = w[wo + 2 + j];
                        out[o + j] = ((v & 0xF) - 8) * d;
                        out[o + 16 + j] = (((v >> 4) & 0xF) - 8) * d;
                    }
                }
                return;
            case Q4_1:
                for (int b = 0, wo = 0, o = outOff; b < n >> 5; b++, wo += 20, o += 32) {
                    float d = Fp16.at(w, wo), m = Fp16.at(w, wo + 2);
                    for (int j = 0; j < 16; j++) {
                        int v = w[wo + 4 + j];
                        out[o + j] = (v & 0xF) * d + m;
                        out[o + 16 + j] = ((v >> 4) & 0xF) * d + m;
                    }
                }
                return;
            case Q5_0:
                for (int b = 0, wo = 0, o = outOff; b < n >> 5; b++, wo += 22, o += 32) {
                    float d = Fp16.at(w, wo);
                    int qh = (w[wo + 2] & 0xFF) | (w[wo + 3] & 0xFF) << 8 | (w[wo + 4] & 0xFF) << 16 | (w[wo + 5] & 0xFF) << 24;
                    for (int j = 0; j < 16; j++) {
                        int v = w[wo + 6 + j];
                        int lo = (v & 0xF) | (((qh >>> j) << 4) & 0x10);
                        int hi = ((v >> 4) & 0xF) | ((qh >>> (j + 12)) & 0x10);
                        out[o + j] = (lo - 16) * d;
                        out[o + 16 + j] = (hi - 16) * d;
                    }
                }
                return;
            case Q5_1:
                for (int b = 0, wo = 0, o = outOff; b < n >> 5; b++, wo += 24, o += 32) {
                    float d = Fp16.at(w, wo), m = Fp16.at(w, wo + 2);
                    int qh = (w[wo + 4] & 0xFF) | (w[wo + 5] & 0xFF) << 8 | (w[wo + 6] & 0xFF) << 16 | (w[wo + 7] & 0xFF) << 24;
                    for (int j = 0; j < 16; j++) {
                        int v = w[wo + 8 + j];
                        int lo = (v & 0xF) | (((qh >>> j) << 4) & 0x10);
                        int hi = ((v >> 4) & 0xF) | ((qh >>> (j + 12)) & 0x10);
                        out[o + j] = lo * d + m;
                        out[o + 16 + j] = hi * d + m;
                    }
                }
                return;
            case Q4_K:
                for (int sb = 0, wo = 0, o = outOff; sb < n >> 8; sb++, wo += 144) {
                    float d = Fp16.at(w, wo), dmin = Fp16.at(w, wo + 2);
                    int so = wo + 4, p = wo + 16;
                    for (int is = 0; is < 8; is += 2, p += 32) {
                        int sm1 = scaleMinK4(w, so, is), sm2 = scaleMinK4(w, so, is + 1);
                        float d1 = d * (sm1 & 0xFF), m1 = dmin * (sm1 >> 8);
                        float d2 = d * (sm2 & 0xFF), m2 = dmin * (sm2 >> 8);
                        for (int l = 0; l < 32; l++) out[o++] = d1 * (w[p + l] & 0xF) - m1;
                        for (int l = 0; l < 32; l++) out[o++] = d2 * ((w[p + l] >> 4) & 0xF) - m2;
                    }
                }
                return;
            case Q5_K:
                for (int sb = 0, wo = 0, o = outOff; sb < n >> 8; sb++, wo += 176) {
                    float d = Fp16.at(w, wo), dmin = Fp16.at(w, wo + 2);
                    int so = wo + 4, qh = wo + 16, p = wo + 48;
                    int u1 = 1, u2 = 2;
                    for (int is = 0; is < 8; is += 2, p += 32, u1 <<= 2, u2 <<= 2) {
                        int sm1 = scaleMinK4(w, so, is), sm2 = scaleMinK4(w, so, is + 1);
                        float d1 = d * (sm1 & 0xFF), m1 = dmin * (sm1 >> 8);
                        float d2 = d * (sm2 & 0xFF), m2 = dmin * (sm2 >> 8);
                        for (int l = 0; l < 32; l++) out[o++] = d1 * ((w[p + l] & 0xF) + ((w[qh + l] & u1) != 0 ? 16 : 0)) - m1;
                        for (int l = 0; l < 32; l++) out[o++] = d2 * (((w[p + l] >> 4) & 0xF) + ((w[qh + l] & u2) != 0 ? 16 : 0)) - m2;
                    }
                }
                return;
            case Q6_K:
                for (int sb = 0, wo = 0, o = outOff; sb < n >> 8; sb++, wo += 210, o += 256) {
                    float d = Fp16.at(w, wo + 208);
                    for (int half = 0; half < 2; half++) {
                        int ql = wo + half * 64, qh = wo + 128 + half * 32, so = wo + 192 + half * 8, y = o + half * 128;
                        for (int l = 0; l < 32; l++) {
                            int is = l >> 4;
                            int la = w[ql + l] & 0xFF, lb = w[ql + l + 32] & 0xFF, h = w[qh + l] & 0xFF;
                            int q1 = ((la & 0xF) | ((h & 3) << 4)) - 32;
                            int q2 = ((lb & 0xF) | (((h >> 2) & 3) << 4)) - 32;
                            int q3 = ((la >> 4) | (((h >> 4) & 3) << 4)) - 32;
                            int q4 = ((lb >> 4) | ((h >> 6) << 4)) - 32;
                            out[y + l] = d * w[so + is] * q1;
                            out[y + l + 32] = d * w[so + is + 2] * q2;
                            out[y + l + 64] = d * w[so + is + 4] * q3;
                            out[y + l + 96] = d * w[so + is + 6] * q4;
                        }
                    }
                }
                return;
            default:
                throw new IllegalArgumentException("tipo " + type);
        }
    }

    /** Producto escalar de dos vectores float. */
    static float dotFloat(float[] a, int ao, float[] b, int bo, int n) {
        float s0 = 0f, s1 = 0f, s2 = 0f, s3 = 0f;
        int j = 0;
        for (; j + 3 < n; j += 4) {
            s0 += a[ao + j] * b[bo + j];
            s1 += a[ao + j + 1] * b[bo + j + 1];
            s2 += a[ao + j + 2] * b[bo + j + 2];
            s3 += a[ao + j + 3] * b[bo + j + 3];
        }
        for (; j < n; j++) s0 += a[ao + j] * b[bo + j];
        return (s0 + s1) + (s2 + s3);
    }
}
