#!/usr/bin/env python3
"""Genera app/src/main/res/raw/emoji.txt (una categoria por linea: id<TAB>emoji emoji ...) a partir del
emoji-test.txt de Unicode (https://unicode.org/Public/emoji/<version>/emoji-test.txt).

Uso:  python3 tools/build_emoji.py /ruta/a/emoji-test.txt
Se conservan las secuencias "fully-qualified" sin tono de piel; el grupo Component se omite.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw', 'emoji.txt')
GROUPS = {
    'Smileys & Emotion': 'smileys',
    'People & Body': 'people',
    'Animals & Nature': 'nature',
    'Food & Drink': 'food',
    'Travel & Places': 'travel',
    'Activities': 'activities',
    'Objects': 'objects',
    'Symbols': 'symbols',
    'Flags': 'flags',
}
ORDER = ['smileys', 'people', 'nature', 'food', 'travel', 'activities', 'objects', 'symbols', 'flags']
SKIN = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
LINE = re.compile(r'^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E(\d+\.\d+)\s+(.*)$')


def build(path):
    cats = {k: [] for k in ORDER}
    group = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if line.startswith('# group:'):
                group = GROUPS.get(line.split(':', 1)[1].strip())
                continue
            if group is None or not line or line.startswith('#'):
                continue
            m = LINE.match(line)
            if not m:
                continue
            cps = [int(c, 16) for c in m.group(1).split()]
            if any(c in SKIN for c in cps):
                continue
            cats[group].append(''.join(chr(c) for c in cps))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Emojis por categoria (Unicode emoji-test.txt, fully-qualified, sin tonos de piel). Generado por tools/build_emoji.py\n')
        for k in ORDER:
            f.write(k + '\t' + ' '.join(cats[k]) + '\n')
    for k in ORDER:
        print('%-11s %4d' % (k, len(cats[k])))
    print('->', os.path.relpath(OUT, ROOT))


if __name__ == '__main__':
    build(sys.argv[1])
