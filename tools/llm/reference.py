#!/usr/bin/env python3
"""Logits de referencia en numpy para una secuencia de tokens de un modelo GGUF (llama/qwen2/qwen3).

Uso: reference.py modelo.gguf salida.txt id1 id2 id3 ...
Salida: primera linea "T V"; despues T lineas con V logits (uno por posicion, en float32).

Los tensores se descuantizan con gguf-py (gguf.quants.dequantize), que es el oraculo; el paso
hacia delante sigue src/models/{llama,qwen2,qwen3}.cpp: RMSNorm, GQA, RoPE NORM (llama) o NEOX
(qwen2/qwen3), normas q/k de qwen3 antes de RoPE, sesgos QKV de qwen2, salida compartida con
token_embd cuando falta output.weight y attn_qkv fusionado.
"""
import sys

import numpy as np
from gguf import GGUFReader, GGMLQuantizationType as T
from gguf.quants import dequantize


def load(path):
    rd = GGUFReader(path)
    kv = {}
    for k, f in rd.fields.items():
        if len(f.types) == 1 and f.types[0] != 8 and f.types[0] != 9:
            kv[k] = f.parts[f.data[0]][0].item()
        elif len(f.types) == 1 and f.types[0] == 8:
            kv[k] = bytes(f.parts[f.data[0]]).decode("utf-8")
    tensors = {}
    for t in rd.tensors:
        if t.tensor_type in (T.F32, T.F16):
            x = np.asarray(t.data).astype(np.float32)
        else:
            x = dequantize(t.data, t.tensor_type)
        shape = [int(v) for v in reversed(t.shape.tolist())]
        tensors[t.name] = x.reshape(shape if len(shape) > 1 else -1)
    return kv, tensors


def rmsnorm(x, w, eps):
    return x / np.sqrt(np.mean(x * x, axis=-1, keepdims=True) + eps) * w


def rope(x, pos, base, n_rot, neox):
    Tn, H, D = x.shape
    inv = base ** (-np.arange(0, n_rot, 2, dtype=np.float64) / n_rot)
    ang = pos[:, None] * inv[None, :]
    c = np.cos(ang)[:, None, :].astype(np.float32)
    s = np.sin(ang)[:, None, :].astype(np.float32)
    out = x.copy()
    r = x[..., :n_rot]
    if neox:
        x1, x2 = r[..., : n_rot // 2], r[..., n_rot // 2:]
        out[..., :n_rot] = np.concatenate([x1 * c - x2 * s, x1 * s + x2 * c], axis=-1)
    else:
        x1, x2 = r[..., 0::2], r[..., 1::2]
        out[..., 0:n_rot:2] = x1 * c - x2 * s
        out[..., 1:n_rot:2] = x1 * s + x2 * c
    return out


def forward(kv, W, ids):
    A = kv["general.architecture"]
    n_embd = kv[A + ".embedding_length"]
    n_head = kv[A + ".attention.head_count"]
    n_kv = kv.get(A + ".attention.head_count_kv", n_head)
    n_layer = kv[A + ".block_count"]
    eps = kv[A + ".attention.layer_norm_rms_epsilon"]
    base = kv.get(A + ".rope.freq_base", 10000.0)
    hd = kv.get(A + ".attention.key_length", n_embd // n_head)
    n_rot = kv.get(A + ".rope.dimension_count", hd)
    neox = A != "llama"
    emb = W["token_embd.weight"]
    x = emb[ids]
    Tn = len(ids)
    pos = np.arange(Tn)
    mask = np.triu(np.full((Tn, Tn), -np.inf, np.float32), 1)
    for i in range(n_layer):
        p = "blk.%d." % i
        h = rmsnorm(x, W[p + "attn_norm.weight"], eps)
        if p + "attn_qkv.weight" in W:
            y = h @ W[p + "attn_qkv.weight"].T
            if p + "attn_qkv.bias" in W:
                y = y + W[p + "attn_qkv.bias"]
            q, k, v = np.split(y, [n_head * hd, n_head * hd + n_kv * hd], axis=-1)
        else:
            def lin(n):
                y = h @ W[p + n + ".weight"].T
                if p + n + ".bias" in W:
                    y = y + W[p + n + ".bias"]
                return y
            q, k, v = lin("attn_q"), lin("attn_k"), lin("attn_v")
        q = q.reshape(Tn, n_head, hd)
        k = k.reshape(Tn, n_kv, hd)
        v = v.reshape(Tn, n_kv, hd)
        if p + "attn_q_norm.weight" in W:
            q = rmsnorm(q, W[p + "attn_q_norm.weight"], eps)
            k = rmsnorm(k, W[p + "attn_k_norm.weight"], eps)
        q = rope(q, pos, base, n_rot, neox)
        k = rope(k, pos, base, n_rot, neox)
        rep = n_head // n_kv
        k = np.repeat(k, rep, axis=1)
        v = np.repeat(v, rep, axis=1)
        att = np.einsum("thd,shd->hts", q, k) / np.sqrt(hd) + mask
        att = np.exp(att - att.max(-1, keepdims=True))
        att /= att.sum(-1, keepdims=True)
        o = np.einsum("hts,shd->thd", att, v).reshape(Tn, n_head * hd)
        o = o @ W[p + "attn_output.weight"].T
        if p + "attn_output.bias" in W:
            o = o + W[p + "attn_output.bias"]
        x = x + o
        h = rmsnorm(x, W[p + "ffn_norm.weight"], eps)
        g = h @ W[p + "ffn_gate.weight"].T
        u = h @ W[p + "ffn_up.weight"].T
        x = x + ((g / (1 + np.exp(-g))) * u) @ W[p + "ffn_down.weight"].T
    x = rmsnorm(x, W["output_norm.weight"], eps)
    Wo = W["output.weight"] if "output.weight" in W else emb
    logits = x @ Wo.T
    if "output.bias" in W:
        logits = logits + W["output.bias"]
    return logits.astype(np.float32)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)
    kv, W = load(sys.argv[1])
    ids = [int(a) for a in sys.argv[3:]]
    logits = forward(kv, W, ids)
    with open(sys.argv[2], "w") as f:
        f.write("%d %d\n" % logits.shape)
        for row in logits:
            f.write(" ".join("%.7e" % v for v in row) + "\n")
    print("referencia", sys.argv[1], "arch", kv["general.architecture"], "logits", logits.shape,
          "argmax", [int(a) for a in logits.argmax(-1)])


if __name__ == "__main__":
    main()
