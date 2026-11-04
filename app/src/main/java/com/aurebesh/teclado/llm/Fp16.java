package com.aurebesh.teclado.llm;

/**
 * Conversion de medio punto flotante (IEEE 754 binary16) a float. Java 8 no trae
 * Float.float16ToFloat, asi que se construye una tabla de 65536 entradas al arrancar
 * (256 KB): la lectura de una escala fp16 queda en una indexacion.
 */
final class Fp16 {

    /** TABLE[bits & 0xFFFF] = valor float de ese patron fp16 (subnormales, infinitos y NaN incluidos). */
    static final float[] TABLE = new float[65536];

    static {
        for (int bits = 0; bits < 65536; bits++) {
            TABLE[bits] = convert(bits);
        }
    }

    private Fp16() {
    }

    /** Conversion exacta de un patron de 16 bits a float (se usa solo para rellenar la tabla). */
    static float convert(int bits) {
        int sign = (bits >>> 15) & 1;
        int exp = (bits >>> 10) & 0x1F;
        int mant = bits & 0x3FF;
        float v;
        if (exp == 0) {
            v = (float) (mant * Math.pow(2, -24));          // cero y subnormales
        } else if (exp == 31) {
            v = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else {
            v = (float) ((mant | 0x400) * Math.pow(2, exp - 25));
        }
        return sign == 1 ? -v : v;
    }

    /** Lee un fp16 little-endian en b[off], b[off+1]. */
    static float at(byte[] b, int off) {
        return TABLE[(b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)];
    }

    /** bfloat16 little-endian a float: son los 16 bits altos de un float. */
    static float bf16(byte[] b, int off) {
        return Float.intBitsToFloat(((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)) << 16);
    }
}
