package com.aurebesh.teclado.llm;

import java.util.Arrays;
import java.util.Random;

/**
 * Muestreo del siguiente token: penalizacion por repeticion (ultimos 64 tokens), temperatura y
 * nucleo (top-p). Con temperatura 0 es voraz. Los logits de tokens ocultos (relleno, controles
 * que no son fin de generacion) se ponen a -infinito antes de muestrear.
 */
final class Sampler {

    static final int REPEAT_WINDOW = 64;
    /** Los candidatos a mas de LOGIT_WINDOW por debajo del maximo no se consideran (e^-20 ≈ 2e-9). */
    private static final float LOGIT_WINDOW = 20f;

    private final Random random;
    private int[] candIdx = new int[1024];
    private float[] candLogit = new float[1024];
    private long[] sortKeys = new long[1024];

    Sampler(long seed) {
        random = new Random(seed == 0 ? System.nanoTime() ^ 0x5DEECE66DL : seed);
    }

    /**
     * @param logits   logits del modelo (se modifican)
     * @param hidden   ids que nunca se eligen
     * @param history  tokens ya generados/consumidos (para la penalizacion)
     * @param histLen  cuantos de history son validos
     */
    int sample(float[] logits, boolean[] hidden, int[] history, int histLen, float temperature, float topP,
               float repeatPenalty) {
        int n = logits.length;
        if (repeatPenalty != 1f && repeatPenalty > 0f) {
            int from = Math.max(0, histLen - REPEAT_WINDOW);
            for (int i = from; i < histLen; i++) {
                int id = history[i];
                if (id < 0 || id >= n) continue;
                float l = logits[id];
                logits[id] = l > 0 ? l / repeatPenalty : l * repeatPenalty;
            }
        }
        float max = Float.NEGATIVE_INFINITY;
        int argmax = 0;
        for (int i = 0; i < n; i++) {
            if (hidden != null && hidden[i]) continue;
            float l = logits[i];
            if (l > max) {
                max = l;
                argmax = i;
            }
        }
        if (temperature <= 0f || max == Float.NEGATIVE_INFINITY) return argmax;
        // Candidatos cercanos al maximo (el resto tiene probabilidad despreciable).
        float cutoff = max - LOGIT_WINDOW * Math.max(temperature, 1f);
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (hidden != null && hidden[i]) continue;
            float l = logits[i];
            if (l >= cutoff) {
                if (c == candIdx.length) {
                    candIdx = Arrays.copyOf(candIdx, c * 2);
                    candLogit = Arrays.copyOf(candLogit, c * 2);
                    sortKeys = Arrays.copyOf(sortKeys, c * 2);
                }
                candIdx[c] = i;
                candLogit[c] = l;
                c++;
            }
        }
        // Orden descendente por logit: clave = bits del float (negado para ordenar al reves) | indice.
        for (int i = 0; i < c; i++) {
            int bits = Float.floatToIntBits(candLogit[i]);
            int ordered = bits >= 0 ? bits : bits ^ 0x7FFFFFFF;    // orden total de floats como int
            sortKeys[i] = ((long) (~ordered) << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(sortKeys, 0, c);
        double invT = 1.0 / temperature;
        double sum = 0;
        float[] tmp = new float[c];
        for (int k = 0; k < c; k++) {
            int i = (int) sortKeys[k];
            tmp[k] = (float) Math.exp((candLogit[i] - max) * invT);
            sum += tmp[k];
        }
        int keep = c;
        if (topP < 1f) {
            double acc = 0;
            double target = topP * sum;
            for (int k = 0; k < c; k++) {
                acc += tmp[k];
                if (acc >= target) {
                    keep = k + 1;
                    break;
                }
            }
        }
        double total = 0;
        for (int k = 0; k < keep; k++) total += tmp[k];
        double r = random.nextDouble() * total;
        double acc = 0;
        for (int k = 0; k < keep; k++) {
            acc += tmp[k];
            if (r < acc) return candIdx[(int) sortKeys[k]];
        }
        return candIdx[(int) sortKeys[keep - 1]];
    }
}
