package com.aurebesh.teclado.llm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pruebas del tokenizador BPE contra un archivo de vocabulario real (ggml-vocab-*.gguf):
 *   1) los fixtures de llama.cpp (.inp/.out), si se pasan;
 *   2) frases propias (sentences.txt) frente a la referencia Python (ref_tokenize.py), con ida y vuelta;
 *   3) particion por tokens especiales de un prompt ChatML (linea CHATML de la referencia);
 *   4) destokenizacion en streaming de secuencias UTF-8 partidas entre tokens.
 *
 * Uso: TokenizerTest vocab.gguf frases.txt referencia.txt [fixtures.inp fixtures.out]
 */
public final class TokenizerTest {

    static int failures;

    public static void main(String[] args) throws Exception {
        File vocab = new File(args[0]);
        Gguf g = new Gguf(vocab, false);
        Tokenizer tk = new Tokenizer(g);
        System.out.println("== " + vocab.getName() + ": pre=" + tk.pre + " tokens=" + tk.vocabSize()
                + " add_bos=" + tk.addBos + " ignore_merges=" + tk.ignoreMerges + " eog=" + tk.eog);
        // Android (ICU) solo admite estas banderas de java.util.regex; UNICODE_CHARACTER_CLASS lanza
        // IllegalArgumentException("Unsupported flags: 256") en el telefono aunque funcione en el JVM.
        int androidFlags = java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.COMMENTS
                | java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.LITERAL | java.util.regex.Pattern.MULTILINE
                | java.util.regex.Pattern.UNICODE_CASE | java.util.regex.Pattern.UNIX_LINES;
        for (java.util.regex.Pattern p : tk.patterns()) {
            if ((p.flags() & ~androidFlags) != 0) {
                System.out.println("FALLO: patron con banderas no admitidas en Android: " + p.flags() + " " + p.pattern());
                failures++;
            }
        }

        List<String> sentences = new ArrayList<>();
        for (String line : readLines(new File(args[1]))) {
            if (!line.isEmpty()) sentences.add(line.replace("\\n", "\n").replace("\\t", "\t"));
        }
        List<String> ref = readLines(new File(args[2]));
        String chatml = null;
        List<String> refIds = new ArrayList<>();
        for (String l : ref) {
            if (l.startsWith("CHATML ")) chatml = l.substring(7).trim();
            else refIds.add(l);
        }
        if (refIds.size() != sentences.size()) throw new IOException("Referencia con " + refIds.size() + " lineas para " + sentences.size() + " frases");
        int ok = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String s = sentences.get(i);
            int[] got = tk.encode(s, false, false);
            int[] exp = parse(refIds.get(i));
            String back = tk.decode(got, 0, got.length);
            boolean good = Arrays.equals(got, exp) && back.equals(s);
            if (good) ok++;
            else {
                failures++;
                System.out.println("  FALLO frase " + i + ": " + RefTest.show(s));
                System.out.println("    java   " + Arrays.toString(got));
                System.out.println("    python " + Arrays.toString(exp));
                if (!back.equals(s)) System.out.println("    ida y vuelta: " + RefTest.show(back));
            }
        }
        System.out.println("  frases propias: " + ok + "/" + sentences.size() + " coinciden con la referencia Python (ids y texto recuperado)");

        if (chatml != null) {
            String prompt = "<|im_start|>system\nEres un asistente de escritura. Responde SOLO con el texto resultante.<|im_end|>\n"
                    + "<|im_start|>user\nCorrige la ortografía: ola k ase, nesecito ayuda 🙏<|im_end|>\n<|im_start|>assistant\n";
            int[] got = tk.encode(prompt, false, true);
            int[] exp = parse(chatml);
            boolean good = Arrays.equals(got, exp);
            int imStart = tk.idOf("<|im_start|>");
            boolean hasSpecial = imStart >= 0 && got.length > 0 && got[0] == imStart;
            // Sin parse_special, el texto del usuario no debe producir el token de control
            int[] plain = tk.encode("<|im_start|>", false, false);
            boolean noInject = true;
            for (int id : plain) if (id == imStart) noInject = false;
            System.out.println("  prompt ChatML con especiales: " + (good ? "OK" : "FALLO") + " (" + got.length + " tokens"
                    + (hasSpecial ? ", empieza por <|im_start|>=" + imStart : ", sin <|im_start|> en el vocabulario") + ")"
                    + "; texto de usuario sin inyeccion: " + (noInject ? "OK" : "FALLO"));
            if (!good || !noInject) {
                failures++;
                System.out.println("    java   " + Arrays.toString(got));
                System.out.println("    python " + Arrays.toString(exp));
            }
        }

        // Streaming UTF-8: generar byte a byte un texto con emoji y acentos
        String text = "ñandú 🐶 acción";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Tokenizer.Utf8Stream st = new Tokenizer.Utf8Stream();
        StringBuilder sb = new StringBuilder();
        int partial = 0;
        for (byte b : bytes) {
            int id = tk.idOf(String.valueOf(byteChar(b & 0xFF)));
            if (id < 0) throw new IOException("Falta el token de byte " + (b & 0xFF));
            String piece = st.push(tk, id);
            if (piece.isEmpty()) partial++;
            for (int i = 0; i < piece.length(); i++) {
                if (piece.charAt(i) == '�') {
                    failures++;
                    System.out.println("  FALLO: el streaming emitio un caracter de sustitucion");
                }
            }
            sb.append(piece);
        }
        sb.append(st.flush());
        boolean good = sb.toString().equals(text);
        System.out.println("  streaming UTF-8 byte a byte: " + (good ? "OK" : "FALLO") + " (" + partial + " piezas retenidas hasta completar secuencia)");
        if (!good) failures++;

        if (args.length >= 5) runFixtures(tk, new File(args[3]), new File(args[4]));

        System.out.println(failures == 0 ? "RESULTADO: OK" : "RESULTADO: FALLO (" + failures + ")");
        if (failures != 0) System.exit(1);
    }

    static char byteChar(int b) {
        boolean direct = (b >= 0x21 && b <= 0x7E) || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
        if (direct) return (char) b;
        int n = 0;
        for (int c = 0; c < b; c++) {
            boolean d = (c >= 0x21 && c <= 0x7E) || (c >= 0xA1 && c <= 0xAC) || (c >= 0xAE && c <= 0xFF);
            if (!d) n++;
        }
        return (char) (256 + n);
    }

    static void runFixtures(Tokenizer tk, File inp, File out) throws IOException {
        String all = new String(readAll(inp), StandardCharsets.UTF_8);
        String[] tests = all.split("\n__ggml_vocab_test__\n", -1);
        List<String> expected = readLines(out);
        int n = Math.min(tests.length, expected.size());
        int ok = 0;
        for (int i = 0; i < n; i++) {
            int[] got = tk.encode(tests[i], false, false);
            int[] exp = parse(expected.get(i));
            if (Arrays.equals(got, exp)) ok++;
            else {
                failures++;
                System.out.println("  FALLO fixture " + i + ": " + RefTest.show(tests[i].length() > 60 ? tests[i].substring(0, 60) : tests[i]));
                System.out.println("    java " + Arrays.toString(got));
                System.out.println("    ref  " + Arrays.toString(exp));
            }
        }
        System.out.println("  fixtures de llama.cpp: " + ok + "/" + n);
    }

    static int[] parse(String line) {
        line = line.trim();
        if (line.isEmpty()) return new int[0];
        String[] p = line.split("\\s+");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i]);
        return a;
    }

    static List<String> readLines(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        List<String> out = new ArrayList<>();
        String l;
        while ((l = r.readLine()) != null) out.add(l);
        r.close();
        return out;
    }

    static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        in.close();
        return b;
    }
}
