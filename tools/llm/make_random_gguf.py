#!/usr/bin/env python3
"""Genera modelos GGUF aleatorios para verificar el motor Java (sin acceso a Hugging Face).

Uso:
  make_random_gguf.py tiny  <carpeta>   -> tiny-llama.gguf, tiny-qwen2.gguf, tiny-qwen3.gguf, tiny-llama-qkv.gguf,
                                           tiny-qwen2-f16.gguf (solo F32/F16)
  make_random_gguf.py bench <carpeta>   -> bench-qwen2-q8_0.gguf y bench-qwen2-q4_0.gguf con la forma de
                                           Qwen2.5-0.5B (24 capas, n_embd 896, 14/2 cabezas, n_ff 4864, vocab 151936)

Los modelos pequenos mezclan todos los tipos admitidos: token_embd Q8_0, attn_q Q4_0, attn_k Q4_1,
attn_v Q5_0, attn_output Q5_1 (o Q8_0), ffn_gate Q4_K, ffn_up Q6_K, ffn_down Q5_K, normas F32, output F16 (BF16 en llama-qkv)
(qwen2 con sesgos QKV y output.weight propio; qwen3 con normas q/k y key_length; llama con embeddings
compartidas; llama-qkv con attn_qkv fusionado, alineacion 64 y sin head_count_kv).
gguf-py no cuantiza K-quants, asi que esos tensores se escriben como bloques crudos aleatorios validos.
Requiere: pip install gguf numpy
"""
import os
import sys

import numpy as np
from gguf import GGUFWriter, GGMLQuantizationType as T, GGML_QUANT_SIZES, TokenType
from gguf.quants import quantize

rng = np.random.default_rng(1)


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


def toy_vocab(arch):
    """256 tokens de byte (forma GPT-2) + especiales ChatML; algunas fusiones para probar el BPE."""
    toks = [B2U[b] for b in range(256)]
    types = [TokenType.NORMAL] * 256
    specials = ["<|endoftext|>", "<|im_start|>", "<|im_end|>"]
    if arch == "qwen3":
        specials += ["<think>", "</think>"]
    for s in specials:
        toks.append(s)
        types.append(TokenType.CONTROL)
    merges = ["h o", "ho l", "l a", "Ġ h", "e s", "Ġ e", "Ġe s"]
    for m in merges:
        toks.append(m.replace(" ", ""))
        types.append(TokenType.NORMAL)
    toks.append("[PAD%d]" % len(toks))
    types.append(TokenType.UNUSED)
    return toks, types, merges


def random_kquant(shape, t, scale=0.05):
    """Bloques K-quant aleatorios pero validos: los nibbles/escalas son uniformes y las escalas fp16
    d/dmin se eligen por bloque para que los pesos descuantizados tengan una desviacion ~scale
    (si se dejan al azar salen pesos de +-100 y las activaciones se desbordan)."""
    bs, nb = GGML_QUANT_SIZES[t]
    rows, cols = shape
    raw = rng.integers(0, 256, size=(rows, cols // bs * nb), dtype=np.uint8)
    nblk = rows * (cols // bs)
    if t in (T.Q4_K, T.Q5_K):
        qmax = 15 if t == T.Q4_K else 31
        # y = d*sc*q - dmin*m con sc,m en 0..63 y q en 0..qmax (medias 31.5 y qmax/2)
        d = rng.uniform(0.5, 1.5, nblk) * scale / (31.5 * qmax / 2 * 0.6)
        dmin = rng.uniform(0.5, 1.5, nblk) * scale / (31.5 * 31.5 * 0.6)
        dh = np.asarray(d, np.float16).view(np.uint8).reshape(nblk, 2)
        mh = np.asarray(dmin, np.float16).view(np.uint8).reshape(nblk, 2)
        flat = raw.reshape(nblk, nb)
        flat[:, 0:2] = dh
        flat[:, 2:4] = mh
    else:
        # Q6_K: y = d*sc*(q-32) con sc en -128..127 y q-32 en -32..31
        d = rng.uniform(0.5, 1.5, nblk) * scale / (64 * 16)
        dh = np.asarray(d, np.float16).view(np.uint8).reshape(nblk, 2)
        flat = raw.reshape(nblk, nb)
        flat[:, 208:210] = dh
    return raw


def add(w, name, x, t):
    x = np.ascontiguousarray(x.astype(np.float32))
    if t == T.F32:
        w.add_tensor(name, x)
    elif t == T.F16:
        w.add_tensor(name, x.astype(np.float16))
    elif t == T.BF16:
        w.add_tensor(name, quantize(x, T.BF16), raw_dtype=T.BF16)
    elif t in (T.Q4_K, T.Q5_K, T.Q6_K):
        w.add_tensor(name, random_kquant(x.shape, t), raw_dtype=t)
    else:
        w.add_tensor(name, quantize(x, t), raw_dtype=t)


def write_model(arch, path, n_embd=256, n_head=4, n_head_kv=2, n_ff=512, n_layer=2, tied=True,
                fused_qkv=False, alignment=None, omit_kv_heads=False, types=None, vocab=None, ctx=512):
    hd = n_embd // n_head
    w = GGUFWriter(path, arch)
    if alignment:
        w.add_custom_alignment(alignment)
    w.add_name("random-" + arch)
    w.add_block_count(n_layer)
    w.add_context_length(ctx)
    w.add_embedding_length(n_embd)
    w.add_feed_forward_length(n_ff)
    w.add_head_count(n_head)
    if not omit_kv_heads:
        w.add_head_count_kv(n_head_kv)
    w.add_layer_norm_rms_eps(1e-6)
    w.add_rope_freq_base(1000000.0 if arch != "llama" else 500000.0)
    if arch == "llama":
        w.add_rope_dimension_count(hd)
    if arch == "qwen3":
        w.add_key_length(hd)
        w.add_value_length(hd)
    if vocab is None:
        toks, ttypes, merges = toy_vocab(arch)
    else:
        toks, ttypes, merges = vocab
    n_vocab = len(toks)
    if arch == "llama":
        w.add_vocab_size(n_vocab)
    w.add_tokenizer_model("gpt2")
    w.add_tokenizer_pre("qwen2" if arch != "llama" else "smollm")
    w.add_token_list(toks)
    w.add_token_types(ttypes)
    w.add_token_merges(merges)
    w.add_bos_token_id(256 if n_vocab > 256 else 0)
    w.add_eos_token_id(258 if n_vocab > 258 else 0)
    w.add_add_bos_token(False)
    tmpl = "{% for message in messages %}<|im_start|>{{ message['role'] }}\n{{ message['content'] }}<|im_end|>\n{% endfor %}"
    if arch == "qwen3":
        tmpl += "{% if enable_thinking is defined and enable_thinking is false %}<think>\n\n</think>\n\n{% endif %}"
    w.add_chat_template(tmpl)

    ty = dict(embd=T.Q8_0, q=T.Q4_0, k=T.Q4_1, v=T.Q5_0, o=T.Q5_1, gate=T.Q4_K, up=T.Q6_K, down=T.Q5_K, out=T.F16)
    if types:
        ty.update(types)
    s = 0.05
    add(w, "token_embd.weight", rng.standard_normal((n_vocab, n_embd)) * 0.5, ty["embd"])
    add(w, "output_norm.weight", 1 + 0.1 * rng.standard_normal(n_embd), T.F32)
    if not tied:
        add(w, "output.weight", rng.standard_normal((n_vocab, n_embd)) * s, ty["out"])
    for i in range(n_layer):
        p = "blk.%d." % i
        add(w, p + "attn_norm.weight", 1 + 0.1 * rng.standard_normal(n_embd), T.F32)
        wq = rng.standard_normal((n_head * hd, n_embd)) * s
        wk = rng.standard_normal((n_head_kv * hd, n_embd)) * s
        wv = rng.standard_normal((n_head_kv * hd, n_embd)) * s
        if fused_qkv:
            add(w, p + "attn_qkv.weight", np.concatenate([wq, wk, wv], 0), ty["q"])
            add(w, p + "attn_qkv.bias", rng.standard_normal(n_head * hd + 2 * n_head_kv * hd) * s, T.F32)
        else:
            add(w, p + "attn_q.weight", wq, ty["q"])
            add(w, p + "attn_k.weight", wk, ty["k"])
            add(w, p + "attn_v.weight", wv, ty["v"])
            if arch == "qwen2":
                for n, dim in (("q", n_head * hd), ("k", n_head_kv * hd), ("v", n_head_kv * hd)):
                    add(w, p + "attn_%s.bias" % n, rng.standard_normal(dim) * s, T.F32)
        add(w, p + "attn_output.weight", rng.standard_normal((n_embd, n_head * hd)) * s, ty["o"])
        if arch == "qwen3":
            add(w, p + "attn_q_norm.weight", 1 + 0.1 * rng.standard_normal(hd), T.F32)
            add(w, p + "attn_k_norm.weight", 1 + 0.1 * rng.standard_normal(hd), T.F32)
        add(w, p + "ffn_norm.weight", 1 + 0.1 * rng.standard_normal(n_embd), T.F32)
        add(w, p + "ffn_gate.weight", rng.standard_normal((n_ff, n_embd)) * s, ty["gate"])
        add(w, p + "ffn_up.weight", rng.standard_normal((n_ff, n_embd)) * s, ty["up"])
        add(w, p + "ffn_down.weight", rng.standard_normal((n_embd, n_ff)) * s, ty["down"])
    w.write_header_to_file()
    w.write_kv_data_to_file()
    w.write_tensors_to_file()
    w.close()
    print("escrito", path, os.path.getsize(path), "bytes")


def write_bench(path, t):
    """Forma de Qwen2.5-0.5B-Instruct; vocabulario de juguete de 151936 entradas."""
    n_vocab = 151936
    toks = [B2U[b] for b in range(256)] + ["<|endoftext|>", "<|im_start|>", "<|im_end|>"]
    toks += ["t%d" % i for i in range(len(toks), n_vocab)]
    types = [TokenType.NORMAL] * 256 + [TokenType.CONTROL] * 3 + [TokenType.NORMAL] * (n_vocab - 259)
    ty = dict(embd=T.Q8_0, q=t, k=t, v=t, o=t, gate=t, up=t, down=t)
    write_model("qwen2", path, n_embd=896, n_head=14, n_head_kv=2, n_ff=4864, n_layer=24, tied=True,
                types=ty, vocab=(toks, types, ["h o"]), ctx=32768)


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("tiny", "bench"):
        print(__doc__)
        sys.exit(2)
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    if sys.argv[1] == "tiny":
        write_model("qwen3", os.path.join(out, "tiny-qwen3.gguf"), tied=True)
        write_model("qwen2", os.path.join(out, "tiny-qwen2.gguf"), tied=False)
        write_model("llama", os.path.join(out, "tiny-llama.gguf"), tied=True)
        write_model("llama", os.path.join(out, "tiny-llama-qkv.gguf"), tied=False, fused_qkv=True, alignment=64,
                    omit_kv_heads=True, n_head_kv=4, types=dict(embd=T.F32, q=T.Q8_0, o=T.Q8_0, out=T.BF16))
        f16 = dict(embd=T.F32, q=T.F16, k=T.F16, v=T.F16, o=T.F16, gate=T.F16, up=T.F16, down=T.F16, out=T.F16)
        write_model("qwen2", os.path.join(out, "tiny-qwen2-f16.gguf"), tied=False, types=f16)
    else:
        write_bench(os.path.join(out, "bench-qwen2-q8_0.gguf"), T.Q8_0)
        write_bench(os.path.join(out, "bench-qwen2-q4_0.gguf"), T.Q4_0)


if __name__ == "__main__":
    main()
