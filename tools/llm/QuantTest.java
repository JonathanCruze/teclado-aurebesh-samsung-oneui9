package com.aurebesh.teclado.llm;

import java.util.Random;

/**
 * Prueba unitaria de los nucleos de producto escalar: para cada tipo cuantizado se generan filas
 * aleatorias validas (escalas fp16 pequenas) y activaciones gaussianas, y se compara el camino
 * rapido (activaciones int8) con el exacto (descuantizar la fila y multiplicar en float). El error
 * relativo respecto a ||w||·||x|| debe quedar en el orden del redondeo int8 (~1e-3); un error de
 * disposicion de bits daria errores de 0.1-1.
 *
 * Uso: QuantTest [n=1024] [repeticiones=64]
 */
public final class QuantTest {

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int[] types = {Quant.Q8_0, Quant.Q4_0, Quant.Q4_1, Quant.Q5_0, Quant.Q5_1, Quant.Q4_K, Quant.Q5_K, Quant.Q6_K,
                Quant.F16, Quant.BF16, Quant.F32};
        Random rnd = new Random(42);
        boolean failed = false;
        Quant.QVec x = new Quant.QVec(n, 1);
        float[] deq = new float[n];
        float[] xrec = new float[n];
        for (int type : types) {
            int rowBytes = n / Quant.blockSize(type) * Quant.typeSize(type);
            byte[] row = new byte[rowBytes];
            double worst = 0, mean = 0, worstRecon = 0;
            for (int r = 0; r < reps; r++) {
                rnd.nextBytes(row);
                fixScales(type, row, n, rnd);
                for (int j = 0; j < n; j++) x.f[j] = (float) rnd.nextGaussian() * (1 + (j % 37 == 0 ? 6 : 0));
                x.quantize(0);
                Quant.dequantRow(type, row, n, deq, 0);
                double exact = Quant.dotFloat(deq, 0, x.f, 0, n);
                double fast = Quant.dot(type, row, n, x, 0);
                // Activaciones reconstruidas desde int8: el nucleo debe calcular exactamente w·(q·d)
                for (int j = 0; j < n; j++) xrec[j] = x.q[j] * x.d[j / Quant.QB];
                double recon = Quant.dotFloat(deq, 0, xrec, 0, n);
                double nw = 0, nx = 0;
                for (int j = 0; j < n; j++) {
                    nw += (double) deq[j] * deq[j];
                    nx += (double) x.f[j] * x.f[j];
                }
                double norm = Math.sqrt(nw) * Math.sqrt(nx) + 1e-30;
                double rel = Math.abs(fast - exact) / norm;
                worst = Math.max(worst, rel);
                mean += rel / reps;
                worstRecon = Math.max(worstRecon, Math.abs(fast - recon) / norm);
            }
            boolean floatType = Quant.isFloat(type);
            double tol = floatType ? 1e-6 : 1e-2;
            boolean ok = worst < tol && (floatType || worstRecon < 2e-6);   // los tipos float no usan int8
            if (!ok) failed = true;
            System.out.printf(java.util.Locale.ROOT,
                    "  %-5s n=%d: int8 vs exacto max=%.2e medio=%.2e | int8 vs activaciones reconstruidas max=%.2e  %s%n",
                    Quant.name(type), n, worst, mean, worstRecon, ok ? "OK" : "FALLO");
        }
        System.out.println(failed ? "RESULTADO: FALLO" : "RESULTADO: OK");
        if (failed) System.exit(1);
    }

    /** Pone escalas fp16 pequenas y finitas en cada bloque (bits de exponente bajos, mantisa aleatoria). */
    static void fixScales(int type, byte[] row, int n, Random rnd) {
        int bs = Quant.blockSize(type), ts = Quant.typeSize(type);
        for (int b = 0; b < n / bs; b++) {
            int o = b * ts;
            switch (type) {
                case Quant.Q8_0: case Quant.Q4_0: case Quant.Q5_0:
                    half(row, o, 0x1C00, rnd);           // ~2^-8
                    break;
                case Quant.Q4_1: case Quant.Q5_1:
                    half(row, o, 0x1C00, rnd);
                    half(row, o + 2, 0x1800 | (rnd.nextBoolean() ? 0x8000 : 0), rnd);
                    break;
                case Quant.Q4_K: case Quant.Q5_K:
                    half(row, o, 0x0C00, rnd);           // ~2^-12 (escalas de 6 bits x nibbles)
                    half(row, o + 2, 0x0C00, rnd);
                    break;
                case Quant.Q6_K:
                    half(row, o + 208, 0x0400, rnd);     // ~2^-14 (escalas con signo de 8 bits)
                    break;
                case Quant.F16:
                    for (int j = 0; j < bs; j++) half(row, o + 2 * j, 0x3800 | (rnd.nextBoolean() ? 0x8000 : 0), rnd);
                    break;
                case Quant.BF16: {
                    float f = (float) rnd.nextGaussian();
                    int bits = Float.floatToIntBits(f) >>> 16;
                    row[o] = (byte) bits;
                    row[o + 1] = (byte) (bits >> 8);
                    break;
                }
                case Quant.F32: {
                    int bits = Float.floatToIntBits((float) rnd.nextGaussian());
                    row[o] = (byte) bits;
                    row[o + 1] = (byte) (bits >> 8);
                    row[o + 2] = (byte) (bits >> 16);
                    row[o + 3] = (byte) (bits >> 24);
                    break;
                }
                default:
            }
        }
    }

    static void half(byte[] row, int off, int base, Random rnd) {
        int bits = base | rnd.nextInt(0x400);
        row[off] = (byte) bits;
        row[off + 1] = (byte) (bits >> 8);
    }
}
