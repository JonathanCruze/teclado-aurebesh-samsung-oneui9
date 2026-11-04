package com.aurebesh.teclado.llm;

import java.io.File;
import java.util.Random;

/**
 * Banco de velocidad: carga un GGUF, prellena un prompt de 16 tokens y genera 64 tokens con
 * muestreo, midiendo tokens por segundo (prellenado y descodificacion por separado).
 *
 * Uso: Bench modelo.gguf [hilos=4] [tokens=64] [prompt=16]
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        File model = new File(args[0]);
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int nGen = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        int nPrompt = args.length > 3 ? Integer.parseInt(args[3]) : 16;
        long t0 = System.nanoTime();
        LlmEngine engine = LlmEngine.load(model, threads);
        long tLoad = System.nanoTime() - t0;
        System.out.println("== " + model.getName() + " (" + threads + " hilos, carga " + (tLoad / 1_000_000) + " ms)");
        System.out.println(engine.info());
        Model m = engine.model();
        Random rnd = new Random(3);
        int[] prompt = new int[nPrompt];
        for (int i = 0; i < nPrompt; i++) prompt[i] = rnd.nextInt(256);
        Sampler sampler = new Sampler(11);
        boolean[] hidden = new boolean[m.nVocab];
        for (int i = 0; i < m.nVocab; i++) hidden[i] = engine.tokenizer().isHidden(i);
        int[] history = new int[nPrompt + nGen];
        System.arraycopy(prompt, 0, history, 0, nPrompt);
        // Calentamiento breve (JIT) y despues la medida
        for (int round = 0; round < 2; round++) {
            int gen = round == 0 ? Math.min(8, nGen) : nGen;
            m.reset(nPrompt + gen);
            long a = System.nanoTime();
            float[] logits = m.forward(prompt, 0, nPrompt, true);
            long b = System.nanoTime();
            int hist = nPrompt;
            for (int i = 0; i < gen; i++) {
                int id = sampler.sample(logits, hidden, history, hist, 0.7f, 0.9f, 1.1f);
                history[hist++] = id;
                logits = m.forward(history, hist - 1, 1, true);
            }
            long c = System.nanoTime();
            if (round == 1) {
                double prefillS = (b - a) / 1e9, decodeS = (c - b) / 1e9;
                System.out.printf(java.util.Locale.ROOT,
                        "  prellenado: %d tokens en %.2f s = %.1f tok/s | descodificacion: %d tokens en %.2f s = %.2f tok/s (%.0f ms/token)%n",
                        nPrompt, prefillS, nPrompt / prefillS, gen, decodeS, gen / decodeS, 1000 * decodeS / gen);
            }
            m.releaseCache();
        }
        engine.close();
    }
}
