#!/usr/bin/env python3
"""Genera app/src/main/res/raw/bigrams_es.txt y bigrams_en.txt (prediccion de la siguiente palabra y
contexto para el autocorrector) a partir de corpus abiertos de nltk_data (GitHub):
  es: europarl_raw/spanish (Europarl, parlamento europeo), cess_esp (treebank), conll2002/esp.* (noticias)
  en: europarl_raw/english, brown (texto variado), webtext (conversacional)
Formato de salida: una linea por palabra previa: `w1<TAB>w2 n w2 n ...` (hasta TOP seguidoras, n = veces),
solo palabras del diccionario (words_xx.txt); `<s>` es el inicio de frase.

Uso:  python3 tools/build_bigrams.py <carpeta con los corpus descomprimidos>
"""
import glob
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')
TOP = 12
MIN = 2
WORD = re.compile(r"[a-záéíóúüñ]+(?:'[a-z]+)?")
END = re.compile(r'[.!?;:]')


def sentences_plain(paths, encoding='utf-8'):
    for p in paths:
        with open(p, encoding=encoding, errors='ignore') as f:
            for line in f:
                for s in END.split(line.lower()):
                    yield WORD.findall(s)


def sentences_brown(folder):
    for p in sorted(glob.glob(os.path.join(folder, 'c[a-r]??'))):
        with open(p, encoding='utf-8', errors='ignore') as f:
            for line in f:
                words = [t.rsplit('/', 1)[0].lower() for t in line.split()]
                sent = []
                for w in words:
                    if w in ('.', '!', '?', ';', ':'):
                        if sent:
                            yield sent
                        sent = []
                    elif WORD.fullmatch(w):
                        sent.append(w)
                if sent:
                    yield sent


def sentences_conll(paths):
    for p in paths:
        sent = []
        with open(p, encoding='latin-1', errors='ignore') as f:
            for line in f:
                parts = line.split()
                if not parts:
                    if sent:
                        yield sent
                    sent = []
                    continue
                w = parts[0].lower()
                if w in ('.', '!', '?', ';', ':'):
                    if sent:
                        yield sent
                    sent = []
                elif WORD.fullmatch(w):
                    sent.append(w)
        if sent:
            yield sent


def sentences_cess(folder):
    # cess_esp: una palabra por linea "(tag palabra)"? El formato es de constituyentes; extraemos hojas.
    leaf = re.compile(r'\(([^()\s]+)\s+([^()\s]+)\)')
    for p in sorted(glob.glob(os.path.join(folder, '*.tbf'))):
        with open(p, encoding='utf-8', errors='ignore') as f:
            sent = []
            for line in f:
                for tag, w in leaf.findall(line):
                    w = w.lower().replace('_', ' ')
                    if tag.startswith('F') and w in ('.', '!', '?', ';', ':'):
                        if sent:
                            yield sent
                        sent = []
                    else:
                        for tok in WORD.findall(w):
                            sent.append(tok)
            if sent:
                yield sent


def build(lang, sentences, vocab):
    counts = defaultdict(Counter)
    total = 0
    for sent in sentences:
        prev = '<s>'
        for w in sent:
            if w in vocab:
                counts[prev][w] += 1
                prev = w
                total += 1
            else:
                prev = None
                if prev is None:
                    prev = '<s>'
    out = os.path.join(RAW, 'bigrams_%s.txt' % lang)
    lines = 0
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# w1<TAB>w2 n ... (top %d seguidoras, n >= %d). Generado por tools/build_bigrams.py\n' % (TOP, MIN))
        for w1 in sorted(counts, key=lambda k: -sum(counts[k].values())):
            follow = [(w2, n) for w2, n in counts[w1].most_common(TOP) if n >= MIN]
            if not follow:
                continue
            f.write(w1 + '\t' + ' '.join('%s %d' % (w2, n) for w2, n in follow) + '\n')
            lines += 1
    print('%s: %d tokens, %d palabras previas -> %s (%d KB)' % (lang, total, lines, os.path.relpath(out, ROOT), os.path.getsize(out) // 1024))


def vocab(lang):
    with open(os.path.join(RAW, 'words_%s.txt' % lang), encoding='utf-8') as f:
        return set(w.strip() for w in f if w.strip())


if __name__ == '__main__':
    base = sys.argv[1]
    es = list(sentences_plain(sorted(glob.glob(os.path.join(base, 'europarl_raw', 'spanish', '*.es')))))
    es += list(sentences_conll(sorted(glob.glob(os.path.join(base, 'conll2002', 'esp.*')))))
    es += list(sentences_cess(os.path.join(base, 'cess_esp')))
    build('es', es, vocab('es'))
    en = list(sentences_plain(sorted(glob.glob(os.path.join(base, 'europarl_raw', 'english', '*.en')))))
    en += list(sentences_brown(os.path.join(base, 'brown')))
    en += list(sentences_plain(sorted(glob.glob(os.path.join(base, 'webtext', '*.txt')))))
    build('en', en, vocab('en'))
