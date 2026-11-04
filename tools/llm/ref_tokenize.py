#!/usr/bin/env python3
"""Tokenizador BPE de referencia en Python (misma logica que llama-vocab.cpp: particion por
especiales, regex del pre-tokenizador, codificacion byte→caracter de GPT-2 y fusiones por rango)
para contrastar la implementacion Java.

Uso: ref_tokenize.py vocab.gguf frases.txt salida.txt
  frases.txt: una frase por linea; "\\n" y "\\t" se interpretan como salto de linea y tabulador.
  salida.txt: por cada frase una linea con los ids (sin BOS, sin especiales); al final una linea
  "CHATML <ids>" con la tokenizacion (parse_special=True) del prompt ChatML de prueba.
Requiere: pip install gguf regex
"""
import heapq
import sys

import regex
from gguf import GGUFReader

PRE = {
    "qwen2": r"(?:'[sS]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD])|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+",
    "llama-bpe": r"(?:'[sS]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD])|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}{1,3}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+",
    "gpt-2": r"'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)",
}
CHATML_PROMPT = ("<|im_start|>system\nEres un asistente de escritura. Responde SOLO con el texto resultante.<|im_end|>\n"
                 "<|im_start|>user\nCorrige la ortografía: ola k ase, nesecito ayuda 🙏<|im_end|>\n<|im_start|>assistant\n")


def byte_to_unicode():
    m = {}
    for a, b in ((0x21, 0x7E), (0xA1, 0xAC), (0xAE, 0xFF)):
        for ch in range(a, b + 1):
            m[ch] = chr(ch)
    n = 0
    for ch in range(256):
        if ch not in m:
            m[ch] = chr(256 + n)
            n += 1
    return m


B2U = byte_to_unicode()
U2B = {v: k for k, v in B2U.items()}


class Vocab:
    def __init__(self, path):
        r = GGUFReader(path)
        g = lambda k: r.fields[k]
        toks = g("tokenizer.ggml.tokens")
        self.tokens = [bytes(toks.parts[i]).decode("utf-8") for i in toks.data]
        tt = g("tokenizer.ggml.token_type")
        self.types = [int(tt.parts[i][0]) for i in tt.data]
        mg = g("tokenizer.ggml.merges")
        self.ranks = {}
        for rank, i in enumerate(mg.data):
            s = bytes(mg.parts[i]).decode("utf-8")
            sp = s.index(" ", 1)
            self.ranks[(s[:sp], s[sp + 1:])] = rank
        self.pre = g("tokenizer.ggml.pre").contents()
        self.t2i = {t: i for i, t in enumerate(self.tokens)}
        self.ignore_merges = self.pre in ("llama-bpe",)
        self.specials = sorted([i for i, t in enumerate(self.types) if t in (2, 3, 4)], key=lambda i: -len(self.tokens[i]))
        rx = PRE.get(self.pre, PRE["gpt-2"])
        self.rx = [regex.compile(rx)]
        if self.pre in ("smollm", "refact", "command-r", "codeshell", "exaone"):
            self.rx = [regex.compile(r"\p{N}"), regex.compile(PRE["gpt-2"])]

    def bpe_word(self, word):
        if self.ignore_merges and word in self.t2i:
            return [self.t2i[word]]
        syms = list(word)
        if not syms:
            return []
        nxt = list(range(1, len(syms) + 1))
        nxt[-1] = -1
        prv = list(range(-1, len(syms) - 1))
        alive = [True] * len(syms)
        pq = []

        def push(l, r):
            if l < 0 or r < 0:
                return
            rk = self.ranks.get((syms[l], syms[r]))
            if rk is not None:
                heapq.heappush(pq, (rk, l, r, syms[l] + syms[r]))

        for i in range(1, len(syms)):
            push(i - 1, i)
        while pq:
            rk, l, r, text = heapq.heappop(pq)
            if not alive[l] or not alive[r] or syms[l] + syms[r] != text:
                continue
            syms[l] = text
            alive[r] = False
            nxt[l] = nxt[r]
            if nxt[r] >= 0:
                prv[nxt[r]] = l
            push(prv[l], l)
            push(l, nxt[l])
        out = []
        i = 0
        while i != -1:
            s = syms[i]
            if s in self.t2i:
                out.append(self.t2i[s])
            else:
                for ch in s:
                    if ch in self.t2i:
                        out.append(self.t2i[ch])
            i = nxt[i]
        return out

    def split(self, text):
        pieces = [text]
        for rx in self.rx:
            nxt = []
            for p in pieces:
                last = 0
                for m in rx.finditer(p):
                    if m.start() > last:
                        nxt.append(p[last:m.start()])
                    if m.end() > m.start():
                        nxt.append(m.group())
                    last = m.end()
                if last < len(p):
                    nxt.append(p[last:])
            pieces = nxt
        return pieces

    def encode_fragment(self, text):
        ids = []
        for piece in self.split(text):
            w = "".join(B2U[b] for b in piece.encode("utf-8"))
            ids += self.bpe_word(w)
        return ids

    def encode(self, text, parse_special=False):
        frags = [("t", text)]
        if parse_special:
            for sid in self.specials:
                st = self.tokens[sid]
                nf = []
                for kind, f in frags:
                    if kind != "t":
                        nf.append((kind, f))
                        continue
                    while True:
                        k = f.find(st)
                        if k < 0:
                            if f:
                                nf.append(("t", f))
                            break
                        if k > 0:
                            nf.append(("t", f[:k]))
                        nf.append(("s", sid))
                        f = f[k + len(st):]
                        if not f:
                            break
                frags = nf
        ids = []
        for kind, f in frags:
            ids += [f] if kind == "s" else self.encode_fragment(f)
        return ids

    def decode(self, ids):
        out = bytearray()
        for i in ids:
            if self.types[i] in (2, 3, 5):
                continue
            t = self.tokens[i]
            if self.types[i] == 4:
                out += t.encode("utf-8")
                continue
            out += bytes(U2B[c] for c in t)
        return out.decode("utf-8", "replace")


def unescape(line):
    return line.replace("\\n", "\n").replace("\\t", "\t")


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    v = Vocab(sys.argv[1])
    lines = [unescape(l.rstrip("\n")) for l in open(sys.argv[2], encoding="utf-8") if l.rstrip("\n")]
    bad = 0
    with open(sys.argv[3], "w", encoding="utf-8") as f:
        for text in lines:
            ids = v.encode(text)
            if v.decode(ids) != text:
                bad += 1
                print("AVISO: la referencia no recupera la frase:", repr(text))
            f.write(" ".join(str(i) for i in ids) + "\n")
        f.write("CHATML " + " ".join(str(i) for i in v.encode(CHATML_PROMPT, parse_special=True)) + "\n")
    print("ref_tokenize:", sys.argv[1], "pre", v.pre, len(lines), "frases", "fallos de ida y vuelta", bad)


if __name__ == "__main__":
    main()
