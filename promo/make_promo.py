#!/usr/bin/env python3
"""Genera las imagenes promocionales a partir de las capturas reales (screenshots/).

Uso:  python3 promo/make_promo.py        -> promo/out/*.png
Requiere Pillow y numpy; usa la fuente Inter del sistema.
"""
import json, os, sys, unicodedata
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(ROOT, '..', 'screenshots')
OUT = os.path.join(ROOT, 'out')
os.makedirs(OUT, exist_ok=True)

FONT_DIR = '/usr/share/fonts/opentype/inter'
def font(name, size):
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)
DISPLAY = lambda s: font('InterDisplay-SemiBold.otf', s)
MEDIUM = lambda s: font('Inter-Medium.otf', s)
REGULAR = lambda s: font('Inter-Regular.otf', s)

GLYPHS = json.load(open(os.path.join(ROOT, 'glyphs.json')))
DIGRAPHS = ['ch', 'ae', 'eo', 'kh', 'ng', 'oo', 'sh', 'th']

# Paletas (las mismas de Themes.java)
ESTELAR = dict(bg='#E3E8EE', card='#FFFFFF', ink='#1B2838', sec='#5B6B7B')
NOCHE = dict(bg='#0E1419', card='#18222B', ink='#DCE6EE', sec='#A7B4C0')
DATAPAD = dict(bg='#060A0E', card='#0C1920', ink='#7FD3F0', sec='#5FAFC9', stroke='#1C4556')
IMPERIAL = dict(bg='#1C1F23', card='#2B3035', ink='#F2F4F6', sec='#C7CCD2')
ARENA = dict(bg='#E6DCC8', card='#F8F2E7', ink='#3A2C1E', sec='#6B5A48')
PAGE = dict(bg='#EDF0F3', card='#FFFFFF', ink='#1B2838', sec='#5B6B7B')

W, H = 1080, 1920
MARGIN = 96


# ---------------------------------------------------------------- utilidades

def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def tokenize(text):
    s = unicodedata.normalize('NFD', text.lower())
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    out, i = [], 0
    while i < len(s):
        two = s[i:i + 2]
        if two in DIGRAPHS:
            out.append(two); i += 2; continue
        out.append(s[i]); i += 1
    return out


def aurebesh_width(text, h):
    w = 0
    for t in tokenize(text):
        if t == ' ':
            w += 6 * h / 10
        elif t in GLYPHS:
            w += (GLYPHS[t]['w'] + 2.2) * h / 10
    return w


def draw_aurebesh(img, text, x, y, h, color):
    """Dibuja texto en Aurebesh con la caja de letra [y, y+h]. Relleno par-impar."""
    mask = np.zeros((img.height, img.width), bool)
    s = h / 10
    for t in tokenize(text):
        if t == ' ':
            x += 6 * s; continue
        if t not in GLYPHS:
            continue
        g = GLYPHS[t]
        for poly in g['p']:
            m = Image.new('1', (img.width, img.height), 0)
            ImageDraw.Draw(m).polygon([(x + px * s, y + py * s) for px, py in poly], fill=1)
            mask ^= np.array(m)
        x += (g['w'] + 2.2) * s
    layer = Image.new('RGBA', img.size, color + (0,))
    layer.putalpha(Image.fromarray((mask * 255).astype('uint8')))
    img.alpha_composite(layer)
    return x


def rounded_mask(size, r):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=r, fill=255)
    return m


def shadow(canvas, box, r, blur=40, alpha=90, dy=18):
    x0, y0, x1, y1 = box
    sh = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((x0, y0 + dy, x1, y1 + dy), radius=r, fill=(0, 0, 0, alpha))
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(sh)


def paste_rounded(canvas, img, xy, r, with_shadow=True):
    img = img.convert('RGBA')
    if with_shadow:
        shadow(canvas, (xy[0], xy[1], xy[0] + img.width, xy[1] + img.height), r)
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    layer.paste(img, xy)
    m = Image.new('L', canvas.size, 0)
    m.paste(rounded_mask(img.size, r), xy)
    layer.putalpha(m)
    canvas.alpha_composite(layer)


def fit(img, width):
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def wrap(draw, text, fnt, max_w):
    words, lines, cur = text.split(), [], ''
    for w in words:
        t = (cur + ' ' + w).strip()
        if draw.textlength(t, font=fnt) <= max_w:
            cur = t
        else:
            lines.append(cur); cur = w
    if cur:
        lines.append(cur)
    return lines


def headline(canvas, title, sub, pal, y=150, title_size=96, sub_size=38):
    d = ImageDraw.Draw(canvas)
    ink, sec = hexrgb(pal['ink']), hexrgb(pal['sec'])
    draw_aurebesh(canvas, 'aurebesh', MARGIN, y, 28, ink)
    y += 28 + 44
    for line in wrap(d, title, DISPLAY(title_size), W - 2 * MARGIN):
        d.text((MARGIN - 4, y), line, font=DISPLAY(title_size), fill=ink)
        y += int(title_size * 1.08)
    y += 22
    for line in wrap(d, sub, REGULAR(sub_size), W - 2 * MARGIN - 40):
        d.text((MARGIN, y), line, font=REGULAR(sub_size), fill=sec)
        y += int(sub_size * 1.4)
    return y


def new_canvas(pal, size=(W, H)):
    return Image.new('RGBA', size, hexrgb(pal['bg']) + (255,))


# ---------------------------------------------------------------- telefono y chat

def chat_screen(pal, keyboard, incoming, outgoing, composer, height=2340, dark=False):
    """Pantalla de un chat: barra, dos mensajes, campo de texto y el teclado real abajo."""
    scr = Image.new('RGBA', (W, height), hexrgb(pal['bg']) + (255,))
    d = ImageDraw.Draw(scr)
    ink, sec, card = hexrgb(pal['ink']), hexrgb(pal['sec']), hexrgb(pal['card'])
    # barra de estado
    d.text((64, 44), '20:34', font=MEDIUM(34), fill=ink)
    d.rounded_rectangle((W - 120, 52, W - 68, 78), radius=6, outline=ink, width=3)
    d.rounded_rectangle((W - 116, 56, W - 80, 74), radius=3, fill=ink)
    d.rectangle((W - 66, 60, W - 62, 70), fill=ink)
    # cabecera
    d.rectangle((0, 110, W, 230), fill=card)
    d.ellipse((64, 134, 136, 206), fill=hexrgb(pal['sec']))
    d.text((100, 170), 'H', font=MEDIUM(36), fill=card, anchor='mm')
    d.text((166, 150), 'Hangar 7', font=MEDIUM(38), fill=ink)
    d.text((166, 196), 'en línea', font=REGULAR(26), fill=sec)
    # mensajes
    kb_h = keyboard.height
    composer_h = 120
    y = 290
    bubble_font = REGULAR(36)
    for who, text in (('in', incoming), ('out', outgoing)):
        lines = wrap(d, text, bubble_font, 620)
        bw = max(d.textlength(l, font=bubble_font) for l in lines) + 56
        bh = len(lines) * 50 + 36
        if who == 'in':
            box = (64, y, 64 + bw, y + bh); fill, color = card, ink
        else:
            box = (W - 64 - bw, y, W - 64, y + bh); fill, color = ink, hexrgb(pal['bg'])
        d.rounded_rectangle(box, radius=28, fill=fill)
        ty = y + 18
        for l in lines:
            d.text((box[0] + 28, ty), l, font=bubble_font, fill=color); ty += 50
        y += bh + 20
    # campo de texto, justo encima del teclado
    cy0 = height - kb_h - composer_h - 24
    d.rounded_rectangle((40, cy0, W - 40, cy0 + composer_h), radius=36, fill=card)
    if dark:
        d.rounded_rectangle((40, cy0, W - 40, cy0 + composer_h), radius=36, outline=hexrgb(pal.get('stroke', pal['sec'])), width=2)
    d.text((88, cy0 + 60), composer, font=REGULAR(38), fill=ink, anchor='lm')
    tx = 88 + d.textlength(composer, font=REGULAR(38)) + 6
    d.rectangle((tx, cy0 + 34, tx + 4, cy0 + 86), fill=ink)
    scr.alpha_composite(keyboard.convert('RGBA'), (0, height - kb_h))
    return scr


def phone(canvas, screen, width, top, crop='top', frame=(11, 15, 20)):
    """Marco de telefono. crop='top': el borde superior queda fuera del lienzo; 'bottom': el inferior."""
    bezel = 18
    scr = fit(screen, width - 2 * bezel)
    ph_w, ph_h = width, scr.height + 2 * bezel
    x = (W - ph_w) // 2
    y = top
    r_out, r_in = 92, 74
    # sombra y marco
    shadow(canvas, (x, y, x + ph_w, y + ph_h), r_out, blur=60, alpha=120, dy=30)
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((x, y, x + ph_w, y + ph_h), radius=r_out, fill=frame + (255,))
    d.rounded_rectangle((x + 2, y + 2, x + ph_w - 2, y + ph_h - 2), radius=r_out - 2, outline=(255, 255, 255, 40), width=2)
    paste_rounded(canvas, scr, (x + bezel, y + bezel), r_in, with_shadow=False)
    return (x, y, x + ph_w, y + ph_h)


def phone_below(canvas, screen, y_end, frame=(11, 15, 20), max_w=760):
    """Telefono entero entre el final del titular y el pie, escalado para que quepa."""
    top = y_end + 44
    ph_h = H - 80 - top
    ph_w = min(max_w, round((ph_h - 36) * W / screen.height) + 36)
    return phone(canvas, screen, ph_w, top, frame=frame)


def load(name):
    return Image.open(os.path.join(SHOTS, name)).convert('RGBA')


# ---------------------------------------------------------------- imagenes

def img_hero():
    pal = ESTELAR
    c = new_canvas(pal)
    y = headline(c, 'Escribe en Aurebesh.', 'El alfabeto de Star Wars en tu teclado Android. Tú ves glifos; tus amigos leen letras normales.', pal)
    screen = chat_screen(PAGE, load('barra_estelar.png'), '¿Ya estás en el hangar?', 'Casi. Dame cinco minutos.', 'Que la Fuerza te acompañe', height=1900)
    phone_below(c, screen, y)
    return c


def img_themes():
    pal = NOCHE
    c = new_canvas(pal)
    y = headline(c, 'Cinco temas.', 'Estelar, Noche, Imperial, Arena y Datapad. Elige uno en la app y el teclado cambia al instante.', pal)
    order = ['estelar', 'noche', 'imperial', 'arena', 'datapad']
    card_w = 900
    step = 170
    card_h = round(780 * card_w / W)
    y0 = y + 44
    step = min(step, (H - 100 - card_h - y0) // 4)
    for i, t in enumerate(order):
        im = fit(load('tema_%s.png' % t), card_w)
        paste_rounded(c, im, ((W - card_w) // 2, y0 + i * step), 32)
    return c


def img_datapad():
    pal = DATAPAD
    c = new_canvas(pal)
    y = headline(c, 'Modo datapad.', 'Teclas trazadas, glifos luminosos y una barra que traduce lo que escribes, como en una consola de a bordo.', pal)
    screen = chat_screen(dict(bg='#0A1016', card='#0F1C25', ink='#7FD3F0', sec='#3F8AA3', stroke='#1C4556'),
                         load('barra_datapad.png'), '¿Tienes las coordenadas?', 'Las envío ahora.', 'Que la Fuerza te acompañe', height=1900, dark=True)
    phone_below(c, screen, y, frame=(6, 10, 14))
    return c


def img_learn():
    pal = ARENA
    c = new_canvas(pal)
    y = headline(c, 'Aprende el alfabeto sin estudiar.', 'Mantén una tecla y verás el glifo grande con su letra latina.', pal)
    kb = load('burbuja_arena.png')
    detail = fit(kb.crop((0, 0, 700, 430)), 820)
    paste_rounded(c, detail, ((W - 820) // 2, y + 50), 36)
    full = fit(kb, 900)
    paste_rounded(c, full, ((W - 900) // 2, y + 50 + detail.height + 32), 32)
    return c


def img_symbols():
    pal = IMPERIAL
    c = new_canvas(pal)
    y = headline(c, 'Numerales y dígrafos de verdad.', 'Del 0 al 9, los ocho dígrafos (ch, sh, th, ae, eo, kh, ng, oo), los signos y hasta el símbolo de créditos.', pal)
    kb = fit(load('simbolos_imperial.png'), 980)
    paste_rounded(c, kb, ((W - 980) // 2, y + 80), 32)
    # leyenda: los ocho digrafos con su equivalencia
    d = ImageDraw.Draw(c)
    ink, sec = hexrgb(pal['ink']), hexrgb(pal['sec'])
    ly = y + 80 + kb.height + 120
    pairs = ['ch', 'sh', 'th', 'ae', 'eo', 'kh', 'ng', 'oo']
    gh = 64
    total = sum(aurebesh_width(p, gh) for p in pairs) + 44 * (len(pairs) - 1)
    x = (W - total) // 2
    for p in pairs:
        w = aurebesh_width(p, gh)
        draw_aurebesh(c, p, x, ly, gh, ink)
        d.text((x + (w - 2.2 * gh / 10) / 2, ly + gh + 26), p, font=REGULAR(30), fill=sec, anchor='ma')
        x += w + 44
    return c


def img_private():
    pal = PAGE
    c = new_canvas(pal)
    headline(c, '41 KB. Sin internet. Sin permisos.', 'Lo que escribes se queda en tu teléfono. Instálalo, elige tema y listo.', pal)
    screen = load('app_claro_primera.png')
    phone(c, screen, 760, 640, crop='bottom')
    return c


def img_feature():
    """Grafico destacado 1024x500 (cabecera de ficha)."""
    pal = dict(bg='#1B2838', card='#243546', ink='#FFFFFF', sec='#B9C3CE')
    c = new_canvas(pal, (1024, 500))
    d = ImageDraw.Draw(c)
    draw_aurebesh(c, 'aurebesh', 72, 100, 48, hexrgb(pal['ink']))
    d.text((72, 220), 'Teclado Aurebesh', font=DISPLAY(56), fill=hexrgb(pal['ink']))
    d.text((72, 300), 'El alfabeto de Star Wars en tu teclado.', font=REGULAR(28), fill=hexrgb(pal['sec']))
    d.text((72, 342), 'Cinco temas. 41 KB. Sin internet.', font=REGULAR(28), fill=hexrgb(pal['sec']))
    kb = fit(load('tema_datapad.png'), 420)
    paste_rounded(c, kb, (640, 172), 24)
    return c


IMAGES = [
    ('01_escribe_en_aurebesh', img_hero),
    ('02_cinco_temas', img_themes),
    ('03_modo_datapad', img_datapad),
    ('04_aprende_el_alfabeto', img_learn),
    ('05_numerales_y_digrafos', img_symbols),
    ('06_ligero_y_privado', img_private),
    ('07_grafico_destacado_1024x500', img_feature),
]

if __name__ == '__main__':
    only = sys.argv[1:]
    for name, fn in IMAGES:
        if only and not any(o in name for o in only):
            continue
        im = fn().convert('RGB')
        path = os.path.join(OUT, name + '.png')
        im.save(path, optimize=True)
        print('PROMO', path, im.size)
