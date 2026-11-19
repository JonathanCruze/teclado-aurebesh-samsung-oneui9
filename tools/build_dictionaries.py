#!/usr/bin/env python3
"""Genera los diccionarios de autocorreccion del teclado:
    app/src/main/res/raw/words_es.txt, words_en.txt y words_de.txt (una palabra por linea, de mas a menos frecuente).

Fuente: listas de frecuencia de hermitdave/FrequencyWords (licencia MIT, tools/dict/LICENSE-FrequencyWords.md),
calculadas sobre los subtitulos de OpenSubtitles 2018 (corpus OPUS). El ingles y el aleman son las 40 000 formas mas
frecuentes de su lista. El espanol empieza igual y se amplia despues con formas validadas (conjugaciones, plurales,
diminutivos, verbos con pronombres, mexicanismos): vease tools/build_dict_es.py, que usa ademas la lista completa
es_full.txt y el diccionario Hunspell RLA-ES (tools/dict/LICENSE-RLA-ES.md, tools/dict/FUENTES-diccionario-es.md).

Uso:  python3 tools/build_dictionaries.py [carpeta] [idiomas...]
      La carpeta trae es_50k.txt, en_50k.txt y de_50k.txt y, para el espanol, es_full.txt, es_MX.dic, es_MX.aff,
      es_ES.dic y es_ES.aff. Sin carpeta, se descargan de GitHub. Sin idiomas, genera es, en y de.
"""
import os
import re
import sys
import urllib.request

import build_dict_es

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')
URL = 'https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/%s/%s_50k.txt'
LIMIT = 40000
RULES = {
    'es': (re.compile(r'^[a-záéíóúüñ]+$'), {'a', 'y', 'o', 'e', 'u'}),
    'en': (re.compile(r"^[a-z']+$"), {'a', 'i'}),
    # Aleman: vocales con dieresis y eszett; la lista viene en minusculas (los sustantivos pierden la mayuscula).
    'de': (re.compile(r'^[a-zäöüß]+$'), set()),
}


def source(lang, folder):
    name = '%s_50k.txt' % lang
    if folder:
        with open(os.path.join(folder, name), encoding='utf-8') as f:
            return f.read()
    return urllib.request.urlopen(URL % (lang, lang)).read().decode('utf-8')


def build(lang, folder):
    pattern, singles = RULES[lang]
    words, seen = [], set()
    for line in source(lang, folder).splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        w = parts[0]
        if not pattern.match(w) or w in seen:
            continue
        if len(w) == 1 and w not in singles:
            continue
        if w.startswith("'") or w.endswith("'") or "''" in w:
            continue
        seen.add(w)
        words.append(w)
        if len(words) >= LIMIT:
            break
    core = len(words)
    if lang == 'es':                                     # espanol: se amplia con formas validadas (build_dict_es.py)
        words, core = build_dict_es.build(words, folder)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, 'words_%s.txt' % lang)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(words[:core]) + '\n')
        if core < len(words):                            # una linea en blanco separa el nucleo de las formas nuevas:
            f.write('\n' + '\n'.join(words[core:]) + '\n')   # de ahi saca Dictionary.java el tamano del nucleo
    print('%s: %d palabras%s -> %s' % (lang, len(words), ' (%d de nucleo)' % core if core < len(words) else '',
                                      os.path.relpath(path, ROOT)))


if __name__ == '__main__':
    args = sys.argv[1:]
    folder = args.pop(0) if args and args[0] not in RULES else None
    # El espanol va el ultimo: su ampliacion consulta las listas inglesa y alemana ya generadas
    for lang in sorted(args or ('es', 'en', 'de'), key=lambda l: l == 'es'):
        build(lang, folder)
