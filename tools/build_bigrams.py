#!/usr/bin/env python3
"""Genera las tablas de bigramas del teclado (prediccion de la siguiente palabra y contexto para el
autocorrector): app/src/main/res/raw/bigrams_es.txt, bigrams_en.txt y bigrams_de.txt.

Formato de salida: una linea por palabra previa, `w1<TAB>w2 n w2 n ...` (las TOP seguidoras mas frecuentes,
n = veces ponderadas, n >= MIN); `<s>` es el inicio de frase. Solo se guardan palabras del diccionario del idioma
(words_xx.txt) y la tabla se corta cuando alcanza BUDGET bytes (las palabras previas mas frecuentes primero).

Ponderacion: cada corpus se normaliza a "veces por millon de palabras" y se multiplica por su peso, de modo que
los 11 millones de palabras de Wikipedia no ahoguen a las decenas de miles de frases coloquiales. Para que un
corpus pequeno y muy ponderado no cuele parejas vistas una sola vez, toda seguidora guardada debe aparecer al
menos MIN_RAW veces en el texto sin ponderar.

Corpus abiertos (todos se descargan de raw.githubusercontent.com; licencias en tools/dict/FUENTES-bigramas.md):
  - Common Voice sentence-collector.txt (CC0): frases cortas, coloquiales -> peso x3, el registro mas parecido al
    de un teclado.
  - Common Voice wiki.xx.txt (frases de Wikipedia, CC BY-SA 3.0): volumen, registro formal -> peso x1.
  - Universal Dependencies: UD_Spanish-GSD (web, CC BY-SA 4.0, x2), UD_Spanish-AnCora (prensa, CC BY 4.0, x1),
    UD_English-EWT (correos, resenas, blogs, CC BY-SA 4.0, x3), UD_German-GSD (web, CC BY-SA 4.0, x2).
  - Europarl (muestra de nltk_data, actas del Parlamento Europeo) x1.
  - SQuAD-es (MIT; Wikipedia traducida automaticamente): solo las preguntas, x0.5 (aporta bigramas interrogativos).
  - Priors de n-gramas ya contados: symspellpy frequency_bigramdictionary_en (MIT, Google Books x SCOWL) y
    orgtre/google-books-ngram-frequency 2grams (CC BY 3.0). Se escalan a una fraccion del total del idioma para que
    suavicen el nucleo formal sin dominar.
Fuentes descartadas por licencia: cess_esp (ambigua), conll2002 (solo investigacion), brown, nps_chat,
twitter_samples, reuters (no comerciales).

Uso:  python3 tools/build_bigrams.py [carpeta de descargas] [idiomas...]
      Sin carpeta usa $AUREBESH_CORPUS o tools/.cache/corpus. Lo ya descargado no se vuelve a bajar.
"""
import csv
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')
TOP = 15                      # seguidoras por palabra previa
MIN = 2                       # veces minimas de una seguidora
MIN_TOTAL = 3                 # total minimo de una palabra previa
MIN_RAW = 2                   # veces minimas de una seguidora en el texto sin ponderar
PER = 1000000.0               # cada corpus se normaliza a esta cantidad de palabras antes de aplicar su peso
BUDGET = 450 * 1024           # bytes maximos por tabla

GH = 'https://raw.githubusercontent.com/'
CV = GH + 'common-voice/common-voice/main/server/data/'
UD = GH + 'UniversalDependencies/'
ORGTRE = GH + 'orgtre/google-books-ngram-frequency/main/ngrams/'
EUROPARL = GH + 'nltk/nltk_data/gh-pages/packages/corpora/europarl_raw.zip'
SYMSPELL = GH + 'mammothb/symspellpy/master/symspellpy/frequency_bigramdictionary_en_243_342.txt'
SQUAD = GH + 'ccasimiro88/TranslateAlignRetrieve/master/SQuAD-es-v1.1/train-v1.1-es.json'

WORD = {
    'es': re.compile(r"[a-záéíóúüñ]+"),
    'en': re.compile(r"[a-z]+(?:'[a-z]+)?"),
    'de': re.compile(r"[a-zäöüß]+"),
}
END = re.compile(r'[.!?;:]|\s[-–—]\s|["“”«»()]')
# Trozos de direcciones web y correos que quedan sueltos al partir por el punto: cortan la cadena y no se cuentan.
URL_BITS = frozenset(['www', 'http', 'https', 'com', 'org', 'net', 'edu', 'gov', 'html', 'htm', 'php', 'jpg', 'png', 'gif'])

# (nombre de archivo, URL, tipo, peso por millon de palabras). Tipos: plain = una frase por linea; conllu = lineas "# text =";
# europarl = carpeta del zip de nltk; squad = preguntas del JSON; prior = lista "w1 w2 n" (peso = fraccion
# del total del idioma); prior_csv = CSV "ngram,freq" de orgtre (idem).
SOURCES = {
    'es': [
        ('cv_es_sentence-collector.txt', CV + 'es/sentence-collector.txt', 'plain', 3.0),
        ('cv_es_wiki.txt', CV + 'es/wiki.es.txt', 'plain', 1.0),
        ('ud_es_gsd-train.conllu', UD + 'UD_Spanish-GSD/master/es_gsd-ud-train.conllu', 'conllu', 2.0),
        ('ud_es_gsd-dev.conllu', UD + 'UD_Spanish-GSD/master/es_gsd-ud-dev.conllu', 'conllu', 2.0),
        ('ud_es_gsd-test.conllu', UD + 'UD_Spanish-GSD/master/es_gsd-ud-test.conllu', 'conllu', 2.0),
        ('ud_es_ancora-train.conllu', UD + 'UD_Spanish-AnCora/master/es_ancora-ud-train.conllu', 'conllu', 1.0),
        ('ud_es_ancora-dev.conllu', UD + 'UD_Spanish-AnCora/master/es_ancora-ud-dev.conllu', 'conllu', 1.0),
        ('ud_es_ancora-test.conllu', UD + 'UD_Spanish-AnCora/master/es_ancora-ud-test.conllu', 'conllu', 1.0),
        ('europarl_raw.zip', EUROPARL, 'europarl:spanish', 1.0),
        ('squad_es.json', SQUAD, 'squad', 0.5),
        ('orgtre_2grams_spanish.csv', ORGTRE + '2grams_spanish.csv', 'prior_csv', 0.05),
    ],
    'en': [
        ('cv_en_sentence-collector.txt', CV + 'en/sentence-collector.txt', 'plain', 3.0),
        ('cv_en_wiki.txt', CV + 'en/wiki.en.txt', 'plain', 1.0),
        ('cv_en_wiki-rerun.txt', CV + 'en/wiki.en.rerun-2021-11.txt', 'plain', 1.0),
        ('ud_en_ewt-train.conllu', UD + 'UD_English-EWT/master/en_ewt-ud-train.conllu', 'conllu', 3.0),
        ('ud_en_ewt-dev.conllu', UD + 'UD_English-EWT/master/en_ewt-ud-dev.conllu', 'conllu', 3.0),
        ('ud_en_ewt-test.conllu', UD + 'UD_English-EWT/master/en_ewt-ud-test.conllu', 'conllu', 3.0),
        ('europarl_raw.zip', EUROPARL, 'europarl:english', 1.0),
        ('symspell_bigrams_en.txt', SYMSPELL, 'prior', 0.10),
        ('orgtre_2grams_english.csv', ORGTRE + '2grams_english.csv', 'prior_csv', 0.05),
    ],
    'de': [
        ('cv_de_sentence-collector.txt', CV + 'de/sentence-collector.txt', 'plain', 3.0),
        ('cv_de_wiki.txt', CV + 'de/wiki.de.txt', 'plain', 1.0),
        ('ud_de_gsd-train.conllu', UD + 'UD_German-GSD/master/de_gsd-ud-train.conllu', 'conllu', 2.0),
        ('ud_de_gsd-dev.conllu', UD + 'UD_German-GSD/master/de_gsd-ud-dev.conllu', 'conllu', 2.0),
        ('ud_de_gsd-test.conllu', UD + 'UD_German-GSD/master/de_gsd-ud-test.conllu', 'conllu', 2.0),
        ('europarl_raw.zip', EUROPARL, 'europarl:german', 1.0),
        ('orgtre_2grams_german.csv', ORGTRE + '2grams_german.csv', 'prior_csv', 0.05),
    ],
}


def download(folder, name, url):
    """Descarga `url` a `folder/name` con curl si no existe ya; devuelve la ruta."""
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    tmp = path + '.part'
    print('  descargando', url)
    subprocess.check_call(['curl', '-fsSL', '--retry', '3', '-o', tmp, url])
    os.replace(tmp, path)
    return path


def split_sentences(text, word):
    """Trocea un texto en frases (por puntuacion fuerte) y devuelve listas de palabras en minusculas."""
    for s in END.split(text.lower()):
        toks = word.findall(s)
        if toks:
            yield toks


def read_plain(path, word):
    with open(path, encoding='utf-8', errors='ignore') as f:
        for line in f:
            for sent in split_sentences(line, word):
                yield sent


def read_conllu(path, word):
    with open(path, encoding='utf-8', errors='ignore') as f:
        for line in f:
            if line.startswith('# text = '):
                for sent in split_sentences(line[9:], word):
                    yield sent


def read_europarl(path, folder, word):
    """Muestra de Europarl de nltk_data: carpeta por idioma dentro del zip, un parrafo por linea."""
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            parts = info.filename.split('/')
            if len(parts) >= 3 and parts[1] == folder and not info.is_dir():
                text = z.read(info).decode('utf-8', errors='ignore')
                for line in text.splitlines():
                    for sent in split_sentences(line, word):
                        yield sent


def read_squad(path, word):
    """Solo las preguntas de SQuAD-es: los contextos son Wikipedia traducida automaticamente."""
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    for article in data['data']:
        for para in article['paragraphs']:
            for qa in para['qas']:
                for sent in split_sentences(qa['question'], word):
                    yield sent


def count_sentences(counts, raw, sentences, vocab, weight):
    """Cuenta los bigramas de un corpus (solo palabras del diccionario; una desconocida corta la cadena) y los
    suma a `counts` normalizados por millon de palabras y multiplicados por `weight`; `raw` acumula las veces
    reales. Devuelve el numero de palabras del corpus."""
    local = defaultdict(Counter)
    tokens = 0
    for sent in sentences:
        prev = '<s>'
        for w in sent:
            if w in vocab and w not in URL_BITS:
                if prev is not None:
                    local[prev][w] += 1
                prev = w
                tokens += 1
            else:
                prev = None
    if tokens == 0:
        return 0
    scale = weight * PER / tokens
    for prev, follow in local.items():
        c = counts[prev]
        r = raw[prev]
        for w, n in follow.items():
            c[w] += n * scale
            r[w] += n
    return tokens


def read_prior(path, kind):
    """Lista de bigramas ya contados: devuelve (w1, w2, n)."""
    with open(path, encoding='utf-8', errors='ignore') as f:
        if kind == 'prior_csv':
            for row in csv.reader(f):
                if len(row) != 2 or not row[1].isdigit():
                    continue
                parts = row[0].lower().split()
                if len(parts) == 2:
                    yield parts[0], parts[1], int(row[1])
        else:
            for line in f:
                parts = line.lower().split()
                if len(parts) == 3 and parts[2].isdigit():
                    yield parts[0], parts[1], int(parts[2])


def add_prior(counts, raw, rows, vocab, word, fraction, total):
    """Suma un prior escalado para que su masa sea `fraction` del total ponderado del idioma. Las parejas del
    prior cuentan como vistas MIN_RAW veces (ya vienen filtradas por frecuencia en origen)."""
    rows = [(a, b, n) for a, b, n in rows if a in vocab and b in vocab and word.fullmatch(a) and word.fullmatch(b)]
    mass = sum(n for _, _, n in rows)
    if not rows or mass == 0:
        return 0
    scale = fraction * total / mass
    for a, b, n in rows:
        counts[a][b] += n * scale
        raw[a][b] += MIN_RAW
    return len(rows)


def vocab(lang):
    with open(os.path.join(RAW, 'words_%s.txt' % lang), encoding='utf-8') as f:
        return set(w.strip() for w in f if w.strip())


def build(lang, folder):
    print('==', lang)
    words = vocab(lang)
    word = WORD[lang]
    counts = defaultdict(Counter)
    raw = defaultdict(Counter)
    tokens = 0
    priors = []
    for name, url, kind, weight in SOURCES[lang]:
        path = download(folder, name, url)
        if kind.startswith('prior'):
            priors.append((path, kind, weight))
            continue
        if kind == 'plain':
            sents = read_plain(path, word)
        elif kind == 'conllu':
            sents = read_conllu(path, word)
        elif kind.startswith('europarl:'):
            sents = read_europarl(path, kind.split(':', 1)[1], word)
        elif kind == 'squad':
            sents = read_squad(path, word)
        else:
            raise ValueError(kind)
        n = count_sentences(counts, raw, sents, words, weight)
        tokens += n
        print('  %-32s %10d tokens (peso x%g)' % (name, n, weight))
    total = sum(sum(c.values()) for c in counts.values())
    for path, kind, fraction in priors:
        n = add_prior(counts, raw, read_prior(path, kind), words, word, fraction, total)
        print('  %-32s %10d bigramas (prior %g)' % (os.path.basename(path), n, fraction))

    out = os.path.join(RAW, 'bigrams_%s.txt' % lang)
    header = ('# w1<TAB>w2 n ... (top %d seguidoras, n >= %d; <s> = inicio de frase). '
              'Generado por tools/build_bigrams.py\n' % (TOP, MIN))
    size = len(header.encode('utf-8'))
    lines = 0
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(header)
        for w1 in sorted(counts, key=lambda k: (-sum(counts[k].values()), k)):
            follow = [(w2, int(round(n))) for w2, n in counts[w1].most_common()
                      if raw[w1][w2] >= MIN_RAW and n >= MIN - 0.5][:TOP]
            if not follow or sum(n for _, n in follow) < MIN_TOTAL:
                continue
            line = w1 + '\t' + ' '.join('%s %d' % (w2, n) for w2, n in follow) + '\n'
            b = len(line.encode('utf-8'))
            if size + b > BUDGET:
                break
            f.write(line)
            size += b
            lines += 1
    print('%s: %d tokens, %d palabras previas distintas, %d guardadas -> %s (%d KB)'
          % (lang, tokens, len(counts), lines, os.path.relpath(out, ROOT), os.path.getsize(out) // 1024))


if __name__ == '__main__':
    args = sys.argv[1:]
    folder = None
    if args and args[0] not in SOURCES:
        folder = args.pop(0)
    if folder is None:
        folder = os.environ.get('AUREBESH_CORPUS') or os.path.join(ROOT, 'tools', '.cache', 'corpus')
    langs = args or ['es', 'en', 'de']
    for lang in langs:
        build(lang, folder)
