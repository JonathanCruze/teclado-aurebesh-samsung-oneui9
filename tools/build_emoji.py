#!/usr/bin/env python3
"""Genera app/src/main/res/raw/emoji.txt a partir del emoji-test.txt de Unicode
(https://unicode.org/Public/emoji/<version>/emoji-test.txt): una linea por subgrupo, en el orden
oficial de Unicode (grupo y subgrupo), con el nombre del subgrupo en espanol e ingles:

    grupo<TAB>subgrupo<TAB>nombre es<TAB>nombre en<TAB>emoji emoji ...

Uso:  python3 tools/build_emoji.py /ruta/a/emoji-test.txt
Se conservan las secuencias "fully-qualified" sin tono de piel; el grupo Component se omite.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw', 'emoji.txt')
GROUPS = {
    'Smileys & Emotion': 'smileys', 'People & Body': 'people', 'Animals & Nature': 'nature',
    'Food & Drink': 'food', 'Travel & Places': 'travel', 'Activities': 'activities',
    'Objects': 'objects', 'Symbols': 'symbols', 'Flags': 'flags',
}
SKIN = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
LINE = re.compile(r'^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#')

# Nombres de los subgrupos de Unicode (es, en)
NAMES = {
    'face-smiling': ('Caras sonrientes', 'Smiling faces'),
    'face-affection': ('Caras con cariño', 'Affectionate faces'),
    'face-tongue': ('Caras con lengua', 'Tongue faces'),
    'face-hand': ('Caras con manos', 'Faces with hands'),
    'face-neutral-skeptical': ('Caras neutras y escépticas', 'Neutral and skeptical faces'),
    'face-sleepy': ('Caras con sueño', 'Sleepy faces'),
    'face-unwell': ('Caras enfermas', 'Unwell faces'),
    'face-hat': ('Caras con sombrero', 'Faces with hats'),
    'face-glasses': ('Caras con gafas', 'Faces with glasses'),
    'face-concerned': ('Caras preocupadas', 'Concerned faces'),
    'face-negative': ('Caras enfadadas', 'Negative faces'),
    'face-costume': ('Caras disfrazadas', 'Costume faces'),
    'cat-face': ('Caras de gato', 'Cat faces'),
    'monkey-face': ('Caras de mono', 'Monkey faces'),
    'heart': ('Corazones', 'Hearts'),
    'emotion': ('Emociones', 'Emotion'),
    'hand-fingers-open': ('Manos abiertas', 'Open hands'),
    'hand-fingers-partial': ('Gestos con los dedos', 'Finger gestures'),
    'hand-single-finger': ('Un dedo', 'Single finger'),
    'hand-fingers-closed': ('Manos cerradas', 'Closed hands'),
    'hands': ('Dos manos', 'Hands'),
    'hand-prop': ('Manos con objetos', 'Hands with props'),
    'body-parts': ('Partes del cuerpo', 'Body parts'),
    'person': ('Personas', 'People'),
    'person-gesture': ('Gestos', 'Gestures'),
    'person-role': ('Profesiones', 'Roles'),
    'person-fantasy': ('Fantasía', 'Fantasy'),
    'person-activity': ('Actividades', 'Activities'),
    'person-sport': ('Deportes', 'Sports'),
    'person-resting': ('Descanso', 'Resting'),
    'family': ('Familias', 'Families'),
    'person-symbol': ('Símbolos de personas', 'People symbols'),
    'animal-mammal': ('Mamíferos', 'Mammals'),
    'animal-bird': ('Aves', 'Birds'),
    'animal-amphibian': ('Anfibios', 'Amphibians'),
    'animal-reptile': ('Reptiles', 'Reptiles'),
    'animal-marine': ('Animales marinos', 'Marine animals'),
    'animal-bug': ('Insectos', 'Bugs'),
    'plant-flower': ('Flores', 'Flowers'),
    'plant-other': ('Plantas', 'Plants'),
    'food-fruit': ('Frutas', 'Fruit'),
    'food-vegetable': ('Verduras', 'Vegetables'),
    'food-prepared': ('Comida preparada', 'Prepared food'),
    'food-asian': ('Comida asiática', 'Asian food'),
    'food-sweet': ('Dulces', 'Sweets'),
    'drink': ('Bebidas', 'Drinks'),
    'dishware': ('Vajilla', 'Dishware'),
    'place-map': ('Mapas', 'Maps'),
    'place-geographic': ('Paisajes', 'Geography'),
    'place-building': ('Edificios', 'Buildings'),
    'place-religious': ('Lugares religiosos', 'Religious places'),
    'place-other': ('Otros lugares', 'Other places'),
    'transport-ground': ('Transporte terrestre', 'Ground transport'),
    'transport-water': ('Transporte acuático', 'Water transport'),
    'transport-air': ('Transporte aéreo', 'Air transport'),
    'hotel': ('Hotel', 'Hotel'),
    'time': ('Hora', 'Time'),
    'sky & weather': ('Cielo y clima', 'Sky and weather'),
    'event': ('Eventos', 'Events'),
    'award-medal': ('Premios', 'Awards'),
    'sport': ('Deportes', 'Sports'),
    'game': ('Juegos', 'Games'),
    'arts & crafts': ('Arte y manualidades', 'Arts and crafts'),
    'clothing': ('Ropa', 'Clothing'),
    'sound': ('Sonido', 'Sound'),
    'music': ('Música', 'Music'),
    'musical-instrument': ('Instrumentos', 'Instruments'),
    'phone': ('Teléfonos', 'Phones'),
    'computer': ('Computadoras', 'Computers'),
    'light & video': ('Luz y video', 'Light and video'),
    'book-paper': ('Libros y papel', 'Books and paper'),
    'money': ('Dinero', 'Money'),
    'mail': ('Correo', 'Mail'),
    'writing': ('Escritura', 'Writing'),
    'office': ('Oficina', 'Office'),
    'lock': ('Candados', 'Locks'),
    'tool': ('Herramientas', 'Tools'),
    'science': ('Ciencia', 'Science'),
    'medical': ('Medicina', 'Medical'),
    'household': ('Hogar', 'Household'),
    'other-object': ('Otros objetos', 'Other objects'),
    'transport-sign': ('Señales de transporte', 'Transport signs'),
    'warning': ('Advertencias', 'Warnings'),
    'arrow': ('Flechas', 'Arrows'),
    'religion': ('Religión', 'Religion'),
    'zodiac': ('Zodiaco', 'Zodiac'),
    'av-symbol': ('Audio y video', 'Audio and video'),
    'gender': ('Género', 'Gender'),
    'math': ('Matemáticas', 'Math'),
    'punctuation': ('Puntuación', 'Punctuation'),
    'currency': ('Monedas', 'Currency'),
    'other-symbol': ('Otros símbolos', 'Other symbols'),
    'keycap': ('Teclas', 'Keycaps'),
    'alphanum': ('Letras y números', 'Letters and numbers'),
    'geometric': ('Figuras', 'Shapes'),
    'flag': ('Banderas', 'Flags'),
    'country-flag': ('Países', 'Countries'),
    'subdivision-flag': ('Regiones', 'Regions'),
}


def build(path):
    sections = []          # [grupo, subgrupo, [emojis]] en orden de aparicion
    group = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if line.startswith('# group:'):
                group = GROUPS.get(line.split(':', 1)[1].strip())
                continue
            if line.startswith('# subgroup:'):
                if group is not None:
                    sections.append([group, line.split(':', 1)[1].strip(), []])
                continue
            if group is None or not line or line.startswith('#') or not sections:
                continue
            m = LINE.match(line)
            if not m:
                continue
            cps = [int(c, 16) for c in m.group(1).split()]
            if any(c in SKIN for c in cps):
                continue
            sections[-1][2].append(''.join(chr(c) for c in cps))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    total = 0
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# grupo<TAB>subgrupo<TAB>nombre es<TAB>nombre en<TAB>emojis. Orden oficial de Unicode emoji-test.txt '
                '(fully-qualified, sin tonos de piel). Generado por tools/build_emoji.py\n')
        for g, s, emojis in sections:
            if not emojis:
                continue
            es, en = NAMES.get(s, (s, s))
            f.write('%s\t%s\t%s\t%s\t%s\n' % (g, s, es, en, ' '.join(emojis)))
            total += len(emojis)
    print('%d subgrupos, %d emojis -> %s' % (len([x for x in sections if x[2]]), total, os.path.relpath(OUT, ROOT)))
    missing = [s for _, s, e in sections if e and s not in NAMES]
    if missing:
        print('sin nombre:', missing)


if __name__ == '__main__':
    build(sys.argv[1])
