package com.aurebesh.teclado.llm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Prueba de la cache de prefijo (lado del ordenador, mismas fuentes del motor): tras una generacion, la cache KV
 * se conserva y la siguiente peticion con el mismo system prompt solo evalua lo que cambia. Los logits obtenidos
 * reutilizando el prefijo deben coincidir (1e-4) con los de evaluar el prompt completo desde cero.
 *
 * Uso: PrefixTest modelo.gguf [modelo2.gguf ...]
 *
 * Casos: (1) mismo system prompt y texto distinto, (2) prompt identico (se reevalua solo el ultimo token),
 * (3) prompt mas corto que es prefijo del anterior, (4) sin nada en comun (reinicio), (5) progreso por trozos
 * y cancelacion durante la lectura, (6) commonPrefix. Sale con codigo 1 si algo falla.
 */
public final class PrefixTest {

    static boolean failed;
    static final double TOL = 1e-4;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: PrefixTest modelo.gguf [modelo2.gguf ...]");
            System.exit(2);
        }
        unitCommonPrefix();
        for (String a : args) run(new File(a));
        System.out.println(failed ? "RESULTADO: FALLO" : "RESULTADO: OK");
        if (failed) System.exit(1);
    }

    static void run(File file) throws IOException {
        LlmEngine engine = LlmEngine.load(file, 2);
        System.out.println("== " + file.getName() + " (" + engine.architecture() + ")");
        try {
            Model m = engine.model();
            int[] a = prompt(engine, "Corrige la ortografía del siguiente texto.\n\nTexto:\nola ke ase, komo estas?");
            int[] b = prompt(engine, "Corrige la ortografía del siguiente texto.\n\nTexto:\nmañana bamos al sine");
            int[] c = prompt(engine, "Resume el siguiente texto en una frase.\n\nTexto:\nmañana bamos al sine");
            int common = LlmEngine.commonPrefix(a, b, Integer.MAX_VALUE);
            System.out.println("  prompts: " + a.length + " y " + b.length + " tokens, prefijo comun " + common);
            check(common > 8, "los prompts deberian compartir el system prompt y la plantilla");

            // Generacion normal: al terminar la cache sigue viva y recuerda lo evaluado
            LlmEngine.Options o = new LlmEngine.Options();
            o.maxTokens = 6;
            o.temperature = 0.7f;
            o.seed = 3;
            String out = engine.generateTokens(a, o, null, null);
            check(m.capacity() >= a.length + 6, "la cache deberia conservarse tras generar (capacidad " + m.capacity() + ")");
            check(engine.cachedTokens() >= a.length, "la cache deberia cubrir todo el prompt: " + engine.cachedTokens());
            check(engine.lastReused() == 0, "la primera generacion no reutiliza nada");
            System.out.println("  generacion de humo: " + show(out) + " · cache " + engine.cachedTokens() + " tokens");

            // (1) mismo system prompt, texto distinto; ademas hace falta mas sitio (8 > 6): la cache crece copiando el prefijo
            int capBefore = m.capacity();
            final List<int[]> progress = new ArrayList<>();
            float[] viaPrefix = copy(engine.prefill(b, 8, new LlmEngine.PromptListener() {
                @Override public boolean onPrompt(int done, int total) {
                    progress.add(new int[]{done, total});
                    return true;
                }
            }));
            check(engine.lastReused() == common, "reutilizadas " + engine.lastReused() + ", se esperaban " + common);
            check(m.capacity() >= b.length + 8 && m.capacity() > capBefore, "la cache deberia haber crecido: " + capBefore + " -> " + m.capacity());
            check(progress.get(0)[0] == common && progress.get(0)[1] == b.length, "el progreso empieza en el prefijo reutilizado");
            check(progress.get(progress.size() - 1)[0] == b.length, "el progreso termina en el total");
            for (int i = 1; i < progress.size(); i++) {
                check(progress.get(i)[0] > progress.get(i - 1)[0] && progress.get(i)[0] - progress.get(i - 1)[0] <= Model.CHUNK,
                        "el progreso avanza por trozos de hasta " + Model.CHUNK);
            }
            float[] full = fullEval(m, b, 8);
            report("mismo sistema, texto distinto", viaPrefix, full);
            check(engine.cachedTokens() == b.length, "tras prefill la cache cubre el prompt b: " + engine.cachedTokens());

            // (2) prompt identico: se reevalua solo el ultimo token
            viaPrefix = copy(engine.prefill(b, 8, null));
            check(engine.lastReused() == b.length - 1, "identico: reutilizadas " + engine.lastReused() + " de " + b.length);
            report("prompt identico", viaPrefix, full);

            // (3) prompt mas corto que es prefijo del anterior
            int[] shorter = Arrays.copyOf(b, b.length - 5);
            viaPrefix = copy(engine.prefill(shorter, 8, null));
            check(engine.lastReused() == shorter.length - 1, "prefijo corto: reutilizadas " + engine.lastReused());
            report("prompt mas corto (prefijo)", viaPrefix, fullEval(m, shorter, 8));

            // (4) otra instruccion: el system prompt sigue siendo comun, pero el usuario cambia desde el principio
            int commonC = LlmEngine.commonPrefix(shorter, c, Integer.MAX_VALUE);
            viaPrefix = copy(engine.prefill(c, 8, null));
            check(engine.lastReused() == Math.min(commonC, shorter.length - 1), "otra instruccion: reutilizadas " + engine.lastReused());
            report("otra instruccion", viaPrefix, fullEval(m, c, 8));

            // (4b) sin cache (trimMemory) se reinicia y no se reutiliza nada; el resultado es el mismo
            engine.trimMemory();
            check(m.capacity() == 0 && engine.cachedTokens() == 0, "trimMemory suelta la cache");
            viaPrefix = copy(engine.prefill(c, 8, null));
            check(engine.lastReused() == 0, "sin cache no se reutiliza nada");
            report("tras liberar la cache", viaPrefix, fullEval(m, c, 8));

            // (4c) generacion completa tras reutilizar: el texto generado con la misma semilla coincide con el de
            // evaluar desde cero (mismos logits => mismo muestreo)
            engine.trimMemory();
            o.seed = 11;
            String fresh = engine.generateTokens(a, o, null, null);
            engine.generateTokens(b, o, null, null);                  // deja en cache b + lo generado
            String reused = engine.generateTokens(a, o, null, null);  // reutiliza el prefijo comun con b
            check(engine.lastReused() == common, "generacion reutilizada: " + engine.lastReused());
            check(fresh.equals(reused), "el texto generado reutilizando el prefijo difiere: " + show(fresh) + " vs " + show(reused));
            System.out.println("  generacion identica reutilizando " + common + " posiciones: " + show(reused));

            // (5) cancelacion durante la lectura: devuelve null y la cache queda coherente con lo leido
            engine.trimMemory();
            final int[] calls = {0};
            float[] cancelled = engine.prefill(b, 8, new LlmEngine.PromptListener() {
                @Override public boolean onPrompt(int done, int total) {
                    return ++calls[0] < 2;          // cancela tras el primer trozo
                }
            });
            check(cancelled == null, "la cancelacion durante la lectura devuelve null");
            check(engine.cachedTokens() == Math.min(Model.CHUNK, b.length), "tras cancelar la cache guarda lo leido: " + engine.cachedTokens());
            viaPrefix = copy(engine.prefill(b, 8, null));
            check(engine.lastReused() == Math.min(Model.CHUNK, b.length) - 1 || engine.lastReused() == Math.min(Model.CHUNK, b.length),
                    "la siguiente lectura aprovecha lo leido antes de cancelar: " + engine.lastReused());
            report("tras una lectura cancelada", viaPrefix, fullEval(m, b, 8));

            // (5b) prefijo minusculo y sin sitio: no merece la pena copiar, se reinicia
            engine.trimMemory();
            int[] tiny = Arrays.copyOf(b, 3);
            engine.generateTokens(tiny, o, null, null);
            check(m.capacity() < b.length + 8, "la cache del prompt minusculo es pequena: " + m.capacity());
            viaPrefix = copy(engine.prefill(b, 8, null));
            check(engine.lastReused() == 0, "prefijo menor que " + LlmEngine.GROW_MIN_REUSE + " sin sitio: se reinicia, reutilizadas " + engine.lastReused());
            report("prefijo minusculo (reinicio)", viaPrefix, fullEval(m, b, 8));

            // generate() con cancelacion por el oyente de prompt: texto vacio y sin excepcion
            String none = engine.generateTokens(b, o, null, new LlmEngine.PromptListener() {
                @Override public boolean onPrompt(int done, int total) { return false; }
            });
            check(none.isEmpty(), "generate cancelado en la lectura devuelve texto vacio");
        } finally {
            engine.close();
        }
    }

    /** Logits del prompt completo evaluado desde cero (otro contexto, misma capacidad). */
    static float[] fullEval(Model m, int[] prompt, int maxNew) throws IOException {
        m.reset(prompt.length + maxNew);
        return copy(m.forward(prompt, 0, prompt.length, true));
    }

    static void report(String label, float[] got, float[] want) {
        if (got == null || want == null) {
            System.out.println("  FALLO " + label + ": logits nulos");
            failed = true;
            return;
        }
        double maxAbs = 0;
        for (int i = 0; i < want.length; i++) maxAbs = Math.max(maxAbs, Math.abs(got[i] - want[i]));
        boolean ok = maxAbs < TOL && argmax(got) == argmax(want);
        System.out.printf(java.util.Locale.ROOT, "  %-32s max|dif|=%.2e argmax %d/%d %s%n", label, maxAbs, argmax(got), argmax(want), ok ? "OK" : "FALLO");
        if (!ok) failed = true;
    }

    static void unitCommonPrefix() {
        check(LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2, 4}, 10) == 2, "commonPrefix basico");
        check(LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2, 3}, 10) == 3, "commonPrefix identico");
        check(LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2, 3}, 2) == 2, "commonPrefix con tope");
        check(LlmEngine.commonPrefix(new int[]{1, 2, 3}, new int[]{1, 2}, 10) == 2, "commonPrefix longitudes distintas");
        check(LlmEngine.commonPrefix(new int[]{5}, new int[]{1, 2}, 10) == 0, "commonPrefix sin nada en comun");
        check(LlmEngine.commonPrefix(new int[]{1}, null, 10) == 0, "commonPrefix con null");
        check(LlmEngine.commonPrefix(new int[]{1, 2}, new int[]{1, 2}, -3) == 0, "commonPrefix con tope negativo");
        check(LlmEngine.commonPrefix(new int[0], new int[]{1}, 10) == 0, "commonPrefix vacio");
        System.out.println("  commonPrefix OK");
    }

    static int[] prompt(LlmEngine engine, String user) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", "Eres un asistente de escritura. Respondes únicamente con el texto pedido."));
        msgs.add(new ChatMessage("user", user));
        return ChatTemplate.tokenize(engine.template().build(msgs), engine.tokenizer());
    }

    static float[] copy(float[] a) {
        return a == null ? null : Arrays.copyOf(a, a.length);
    }

    static int argmax(float[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("  FALLO: " + what);
            failed = true;
        }
    }

    static String show(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') sb.append("\\n");
            else if (ch < 32 || ch > 126) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.toString();
    }
}
