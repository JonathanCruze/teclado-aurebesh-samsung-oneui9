#!/usr/bin/env python3
"""Mide una lista de palabras del teclado (words_es.txt) contra texto real etiquetado y contra otra lista.

Cobertura: que parte de los VERBOS y de los SUSTANTIVOS de los treebanks espanoles de Universal Dependencies
(UD_Spanish-AnCora, prensa, y UD_Spanish-GSD, web; licencias en tools/dict/LICENSE-UniversalDependencies.md) esta en
la lista, por tipos (formas distintas) y por apariciones. Se cuenta la forma tal como se escribe, en minusculas: un
verbo con pronombres pegados ("darselo" con su tilde) cuenta entero, como verbo. Los treebanks solo se usan para medir:
la lista no se construye con ellos.

Comparacion: con --anterior, cuantas palabras de la lista anterior siguen en la nueva y la correlacion de rangos
(Spearman) entre las dos para las que comparten.

Con --formas se guardan los verbos y sustantivos del texto (clase, forma y apariciones, separados por tabuladores)
para pasarselos al arnes DiccBench (-Ddicc.formas=archivo), que cuenta cuantos cambiaria el corrector.

Uso:  python3 tools/dict_cobertura.py <carpeta con los .conllu> <lista> [<otra lista>...] [--anterior <lista>]
                                      [--formas <archivo de salida>]
      Los .conllu son es_ancora-ud-{train,dev,test}.conllu y es_gsd-ud-{train,dev,test}.conllu, de
      https://raw.githubusercontent.com/UniversalDependencies/UD_Spanish-AnCora/master/ y .../UD_Spanish-GSD/master/
"""
import collections
import glob
import os
import re
import sys

PATTERN = re.compile(r'^[a-záéíóúüñ]+$')


def read_ud(folder):
    """Recuento de formas por clase: {('V' | 'N', forma): apariciones}. V es VERB o AUX; N es NOUN."""
    c = collections.Counter()
    for path in sorted(glob.glob(os.path.join(folder, '*.conllu'))):
        span_end = 0
        span_form = None
        span_first = True
        with open(path, encoding='utf-8') as f:
            for line in f:
                if not line.strip() or line[0] == '#':
                    span_end = 0
                    continue
                p = line.rstrip('\n').split('\t')
                if len(p) < 4 or '.' in p[0]:
                    continue
                if '-' in p[0]:                          # varias palabras escritas como una: "dárselo", "del"
                    span_end = int(p[0].split('-')[1])
                    span_form = p[1]
                    span_first = True
                    continue
                if int(p[0]) <= span_end:
                    if not span_first:
                        continue
                    span_first = False                   # cuenta la forma escrita, con la clase de su primera palabra
                    form, pos = span_form, p[3]
                else:
                    form, pos = p[1], p[3]
                kind = 'V' if pos in ('VERB', 'AUX') else 'N' if pos == 'NOUN' else None
                form = form.lower()
                if kind is not None and PATTERN.match(form):
                    c[(kind, form)] += 1
    return c


def read_list(path):
    with open(path, encoding='utf-8') as f:
        return [line.strip() for line in f if line.strip()]


def coverage(counts, words):
    """{clase: (tipos, tipos cubiertos, apariciones, apariciones cubiertas)}."""
    out = {}
    for kind in 'VN':
        forms = [(f, n) for (k, f), n in counts.items() if k == kind]
        out[kind] = (len(forms), sum(1 for f, n in forms if f in words),
                     sum(n for f, n in forms), sum(n for f, n in forms if f in words))
    return out


def spearman(a, b):
    """Correlacion de rangos entre dos listas, sobre las palabras que comparten (por su orden dentro de cada una)."""
    pos_b = dict((w, i) for i, w in enumerate(b))
    shared = [w for w in a if w in pos_b]
    n = len(shared)
    if n < 2:
        return n, 0.0
    order_b = sorted(shared, key=lambda w: pos_b[w])
    rank_b = dict((w, i) for i, w in enumerate(order_b))
    d2 = sum((i - rank_b[w]) ** 2 for i, w in enumerate(shared))
    return n, 1.0 - 6.0 * d2 / (n * (n * n - 1.0))


def main(args):
    previous = None
    if '--anterior' in args:
        i = args.index('--anterior')
        previous = read_list(args[i + 1])
        del args[i:i + 2]
    forms = None
    if '--formas' in args:
        i = args.index('--formas')
        forms = args[i + 1]
        del args[i:i + 2]
    if len(args) < 2:
        sys.exit(__doc__)
    counts = read_ud(args[0])
    if not counts:
        sys.exit('No hay archivos .conllu en ' + args[0])
    if forms:
        with open(forms, 'w', encoding='utf-8', newline='\n') as f:
            for (kind, form), n in sorted(counts.items(), key=lambda t: (-t[1], t[0])):
                f.write('%s\t%s\t%d\n' % (kind, form, n))
    for path in args[1:]:
        words = read_list(path)
        size = os.path.getsize(path)
        print('%s: %d palabras, %d bytes' % (os.path.basename(path), len(words), size))
        cov = coverage(counts, set(words))
        for kind, name in (('V', 'verbos'), ('N', 'sustantivos')):
            types, hit_types, tokens, hit_tokens = cov[kind]
            print('   %-11s tipos %5d/%5d = %5.2f %%   apariciones %6d/%6d = %5.2f %%'
                  % (name, hit_types, types, 100.0 * hit_types / types, hit_tokens, tokens, 100.0 * hit_tokens / tokens))
        if previous is not None:
            n, rho = spearman(previous, words)
            head = sum(1 for x, y in zip(previous, words) if x == y)
            print('   de las %d de la lista anterior siguen %d (%.2f %%); correlacion de rangos %.6f;'
                  ' coinciden en su misma linea %d' % (len(previous), n, 100.0 * n / len(previous), rho, head))


if __name__ == '__main__':
    main(sys.argv[1:])
