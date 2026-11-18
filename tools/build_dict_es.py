#!/usr/bin/env python3
"""Diccionario espanol ampliado: lo invoca tools/build_dictionaries.py al generar words_es.txt.

Parte de la lista de siempre (las 40 000 formas mas frecuentes de los subtitulos, que quedan tal cual y en su orden)
y le anade, de mas a menos frecuente, formas validas del espanol general y del de Mexico:

  1. Frecuencia: es_full.txt de hermitdave/FrequencyWords (recuentos de OpenSubtitles 2018; licencia MIT,
     tools/dict/LICENSE-FrequencyWords.md).
  2. Validez: el diccionario Hunspell de RLA-ES en sus variantes es_MX y es_ES (tools/dict/LICENSE-RLA-ES.md), que
     se expande aqui mismo con sus reglas de afijos: conjugaciones, plurales, femeninos, derivados y encliticos. Solo
     entran formas que el diccionario acepta, mas tres familias que no trae completas y que se validan por regla *y*
     por uso (tienen que aparecer en los subtitulos): verbo + pronombres encliticos con su tilde correcta ("empujalo"
     no, "empújalo" si); diminutivos, golpes en -azo, superlativos y adverbios en -mente de una palabra valida
     ("cafecito", "portazo", "carísimo"); y compuestos con prefijo ("exnovio", "superpoder").
  3. Mexico: las entradas que es_MX trae y es_ES no (mexicanismos y americanismos: "jitomate", "pozole", "tianguis")
     entran con que aparezcan unas pocas veces; y tools/dict/es_extra.dic, una lista corta escrita a mano con el formato
     de Hunspell, anade lo que RLA-ES no trae ("chamaco", "checar", "chela", "popote", "mariachi"...).
  4. Paradigmas: de los verbos mas usados entran todas las formas simples aunque no salgan en los subtitulos
     (todas las personas, "ustedes" incluida, de presente, preteritos, imperfecto, futuro, condicional, subjuntivos,
     gerundio y participios), y de cualquier palabra frecuente, sus flexiones con que aparezcan un par de veces.

Politica para que una forma rara pero valida no estropee la correccion (vease tambien Dictionary.java):

  a. No entra la forma sin tilde de otra mucho mas frecuente ("tenia" frente a "tenía", "ingles" frente a "inglés",
     "medico" frente a "médico"): quien la escribe casi siempre quiere la acentuada, y si no esta en la lista el
     teclado le pone la tilde.
  b. No entra el futuro de subjuntivo de los verbos en -ar ("hablare", "hablares", "hablaren"): es arcaico y es justo
     lo que se teclea al escribir sin tilde "hablaré", "hablarás" o "hablarán".
  c. No entra la forma que esta a una errata de una palabra de siempre muchisimo mas frecuente: a una falta de
     ortografia ("ablando" frente a "hablando", "carera" frente a "carrera"), a un desliz al teclear ("vanos" frente
     a "vamos") o, con mucha mas diferencia, a una letra cualquiera. Se seguira corrigiendo como hasta ahora.
  d. No entra nada que choque con el lexico Star Wars, ni lo que diga tools/dict/es_excluir.txt, ni la flexion de
     una palabra rara que ademas es una palabra corriente en ingles o aleman ("tells", "oven"): su recuento en los
     subtitulos no es espanol.

Todo lo anterior solo afecta a las formas nuevas. De las 40 000 de siempre solo se quita la docena de erratas con
tilde de tools/dict/es_quitar.txt; las demas quedan como estaban y en el mismo orden.

Para revisar a mano que entra y que no: con la variable de entorno DICC_INFORME=<archivo> se escribe ahi cada forma
nueva con su motivo y su recuento, y cada descartada con la politica que la saco.
"""
import os
import re
import sys
import urllib.request
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT = os.path.join(ROOT, 'tools', 'dict')
URL_FULL = 'https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_full.txt'
# Etiqueta fija del repositorio de diccionarios de LibreOffice (RLA-ES 2.9), para que la lista sea reproducible
URL_RLA = 'https://raw.githubusercontent.com/LibreOffice/dictionaries/libreoffice-25.8.1.1/es/%s'

PATTERN = re.compile(r'^[a-záéíóúüñ]+$')
SINGLES = {'a', 'y', 'o', 'e', 'u'}

# ---------------------------------------------------------------- umbrales (un solo sitio)
T_VALID = 14        # recuento minimo de una forma que RLA-ES (es_MX) acepta
T_SPAIN = 36        # ... si solo la acepta es_ES (sobre todo, las formas de "vosotros")
T_ENCL = 10         # verbo + encliticos (por RLA-ES o por la regla propia)
T_DERIV = 8         # diminutivos, golpes en -azo, superlativos y adverbios en -mente por regla propia
T_PREFIX = 20       # compuestos con prefijo por regla propia (los de una sola pelicula no interesan)
T_FLEX = 4          # flexion (conjugacion, plural, femenino) de una palabra frecuente
C_LEMMA = 150       # recuento desde el que una palabra cuenta como frecuente para T_FLEX
C_BASE = 200        # recuento minimo de la palabra de la que sale un derivado por regla propia
C_MEXICO = 5        # recuento minimo, sumando sus formas, de una entrada que solo trae es_MX
K_VERBS = 1000      # verbos (por recuento del gerundio, que no se confunde con nada) con el paradigma completo
MIN_LEN = 4         # las formas nuevas mas cortas no entran (las corrientes ya estan y las demas tapan erratas)
# Politica a: la forma con tilde gana si es tantas veces mas frecuente que la que no la lleva. En los subtitulos se
# narra en pasado y en un chat se habla en presente: "borro" frente a "borró" o "llame" frente a "llamé" necesitan
# mucha mas diferencia que "genero" frente a "género" o "encantara" frente a "encantará".
ACCENT_RATIO = 4
ACCENT_RATIO_O = 12
ACCENT_RATIO_E = 8
# Politica c: la forma nueva (escrita sin tildes) se descarta si una palabra de siempre esta a una errata de ella y
# es tantas veces mas frecuente. La medida sale de los propios subtitulos: una falta de ortografia aparece una vez
# por cada cien a mil de la palabra bien escrita ("exelente" 1/375, "estubo" 1/270, "hiso" 1/1400) y un desliz al
# teclear, una por cada dos mil a diez mil ("simpre" 1/2300, "entoces" 1/4000). Una forma valida que sale menos que
# eso junto a su vecina es, casi siempre que aparece, esa errata.
TYPO_RATIO_SPELL = 150       # letra que suena igual (b/v, s/c/z, g/j, y/i, x/s, n/ñ, k), hache, erre o ele sencilla
TYPO_RATIO_SLIP = 3000       # tecla vecina, tecla repetida, dos letras bailadas, letra que falta
TYPO_RATIO_FAR = 20000       # cualquier otra letra cambiada o de mas
RULE_FACTOR = 10             # lo validado solo por regla propia cede con diez veces menos diferencia

VERB_FLAGS = set('REIX')
# Lo que es_ES acepta y es_MX no son casi siempre formas de "vosotros"; de ahi solo se toman las que lo parecen
VOSOTROS = re.compile(r'(áis|éis|ais|eis|ís|steis|[aeií]d|[aeí]os|[aeií]d(me|nos|l[oae]s?|[mn]?os?l[oae]s?)|[aeií]ros)$')
ACCENTS = {'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u'}
MARK = {'a': 'á', 'e': 'é', 'i': 'í', 'o': 'ó', 'u': 'ú'}
ROWS = ('qwertyuiop', 'asdfghjklñ', 'zxcvbnm')
ALPHABET = 'abcdefghijklmnopqrstuvwxyzñ'


def log(msg):
    print('   ' + msg)


def fetch(folder, name, url):
    if folder:
        with open(os.path.join(folder, name), encoding='utf-8') as f:
            return f.read()
    return urllib.request.urlopen(url).read().decode('utf-8')


def fold(s):
    """Sin tildes y con la dieresis plegada: la forma de comparacion de Dictionary.java en espanol."""
    for a, b in ACCENTS.items():
        if a in s:
            s = s.replace(a, b)
    return s.replace('ü', 'u')


# ---------------------------------------------------------------- Hunspell: reglas de afijos y expansion

class Rule(object):
    __slots__ = ('strip', 'add', 'cont', 'rx', 'cross')


def flags_of(s):
    return [c for c in s if c != '\ufe0f']         # los emojis de RLA-ES pueden llevar selector de variante


def parse_aff(text):
    """Reglas PFX/SFX de un .aff con FLAG UTF-8: {(tipo, flag): [Rule]}."""
    rules = defaultdict(list)
    cross = {}
    for line in text.splitlines():
        p = line.split()
        if len(p) < 4 or p[0] not in ('PFX', 'SFX'):
            continue
        flag = flags_of(p[1])[0]
        if len(p) == 4 and p[2] in ('Y', 'N') and p[3].isdigit():
            cross[(p[0], flag)] = p[2] == 'Y'
            continue
        r = Rule()
        r.strip = '' if p[2] == '0' else p[2]
        add, _, cont = p[3].partition('/')
        r.add = '' if add == '0' else add
        r.cont = flags_of(cont)
        cond = p[4] if len(p) > 4 else '.'
        if cond == '.':
            r.rx = None
        elif p[0] == 'SFX':
            r.rx = re.compile('(?:' + cond + ')$')
        else:
            r.rx = re.compile('(?:' + cond + ')')
        r.cross = cross.get((p[0], flag), True)
        rules[(p[0], flag)].append(r)
    return rules


def parse_dic(text):
    """Entradas de un .dic: [(palabra, [flags])]. Admite comentarios con # y lineas vacias (es_extra.dic)."""
    out = []
    first = True
    for line in text.splitlines():
        line = line.split('#')[0].strip()
        if first and line:
            first = False
            if line.isdigit():
                continue
        if not line:
            continue
        line = line.split('\t')[0].split(' ')[0]
        w, _, fl = line.partition('/')
        out.append((w, flags_of(fl)))
    return out


def sfx(word, r):
    if r.strip and not word.endswith(r.strip):
        return None
    if r.rx is not None and not r.rx.search(word):
        return None
    return (word[:len(word) - len(r.strip)] if r.strip else word) + r.add


def pfx(word, r):
    if r.strip and not word.startswith(r.strip):
        return None
    if r.rx is not None and not r.rx.match(word):
        return None
    return r.add + word[len(r.strip):]


def expand(word, flags, rules, out):
    """Formas de una entrada: anade a out tuplas (forma, lema, flag del sufijo, flag del segundo sufijo, flag del prefijo)."""
    out.append((word, word, '', '', ''))
    derived = []
    for f in flags:
        for r in rules.get(('SFX', f), ()):
            s = sfx(word, r)
            if s is None:
                continue
            derived.append((s, f, '', r.cross))
            out.append((s, word, f, '', ''))
            for f2 in r.cont:                               # sufijo doble: "ción/S" -> "ciones"
                for r2 in rules.get(('SFX', f2), ()):
                    s2 = sfx(s, r2)
                    if s2 is not None:
                        derived.append((s2, f, f2, r.cross and r2.cross))
                        out.append((s2, word, f, f2, ''))
    for f in flags:
        for r in rules.get(('PFX', f), ()):
            p = pfx(word, r)
            if p is None:
                continue
            out.append((p, word, '', '', f))
            if not r.cross:
                continue
            for s, f1, f2, cr in derived:
                if cr:
                    ps = pfx(s, r)
                    if ps is not None:
                        out.append((ps, word, f1, f2, f))


class Lexicon(object):
    """Formas que acepta un diccionario Hunspell, con su procedencia."""

    def __init__(self, dic_text, aff_rules):
        self.forms = defaultdict(list)                 # forma -> [(lema, flag, flag2, prefijo)]
        self.entries = parse_dic(dic_text)
        buf = []
        for w, fl in self.entries:
            if not PATTERN.match(w):                   # nombres propios, siglas y entradas con guion o cifras: fuera
                continue
            del buf[:]
            expand(w, fl, aff_rules, buf)
            for t in buf:
                if PATTERN.match(t[0]):
                    self.forms[t[0]].append(t[1:])

    def __contains__(self, w):
        return w in self.forms


# ---------------------------------------------------------------- silabas y acento

VOWELS = set('aeiouáéíóúü')
STRONG = set('aeoáéó')


def nuclei(w):
    """Nucleos silabicos de una palabra: lista de listas de indices de vocales (diptongos juntos, hiatos separados)."""
    out = []
    cur = None
    n = len(w)
    for i, ch in enumerate(w):
        vowel = ch in VOWELS
        if ch == 'u' and i > 0 and w[i - 1] in 'qg' and i + 1 < n and w[i + 1] in 'eiéí':
            vowel = False                                # "que", "gui": la u no suena
        if ch == 'y' and i == n - 1 and i > 0 and w[i - 1] in VOWELS:
            vowel = True                                 # "voy", "estoy": cierra el diptongo
        if not vowel:
            cur = None
            continue
        if cur is None:
            cur = [i]
            out.append(cur)
            continue
        prev = w[cur[-1]]
        split = ch in 'íú' or prev in 'íú' or (ch in STRONG and any(w[k] in STRONG for k in cur))
        if split:
            cur = [i]
            out.append(cur)
        else:
            cur.append(i)
    return out


def stress(w):
    """(numero de nucleos, posicion del nucleo tonico, indice de la vocal tonica) segun la ortografia."""
    nuc = nuclei(w)
    if not nuc:
        return 0, -1, -1
    k = None
    for j, nu in enumerate(nuc):
        if any(w[i] in ACCENTS for i in nu):
            k = j
    if k is None:
        if len(nuc) == 1:
            k = 0
        elif w[-1] in 'aeiouns':
            k = len(nuc) - 2
        else:
            k = len(nuc) - 1
    nu = nuc[k]
    idx = None
    for i in nu:
        if w[i] in ACCENTS:
            idx = i
    if idx is None:
        for i in nu:
            if w[i] in STRONG:
                idx = i
    if idx is None:
        idx = nu[-1]
    return len(nuc), k, idx


CLITICS1 = ('me', 'te', 'se', 'nos', 'lo', 'la', 'los', 'las', 'le', 'les')
CLITICS2 = tuple(a + b for a in ('me', 'te', 'se', 'nos') for b in ('lo', 'la', 'los', 'las', 'le', 'les')) \
    + ('seme', 'sete', 'senos', 'teme', 'tenos')
TAILS = [(t, 1) for t in CLITICS1] + [(t, 2) for t in CLITICS2]


def enclitic(host, tail, count):
    """Une un verbo y sus pronombres con la tilde que toca: da+melo -> dámelo, hacer+lo -> hacerlo, vamos+nos -> vámonos."""
    total, k, idx = stress(host)
    if idx < 0:
        return None
    if host.endswith('mos') and (tail.startswith('nos') or tail.startswith('se')):
        host = host[:-1]                                 # sentemos + nos -> sentémonos
    after = total - 1 - k + count                        # silabas detras de la tonica
    ch = host[idx]
    plain = ACCENTS.get(ch, ch)
    hiatus = ch in 'íú' and ((idx > 0 and host[idx - 1] in STRONG) or (idx + 1 < len(host) and host[idx + 1] in STRONG))
    mark = after >= 2 or hiatus
    return host[:idx] + (MARK.get(plain, plain) if mark else plain) + host[idx + 1:] + tail


# Imperativos irregulares y de verbos que RLA-ES trae como palabras sueltas (dar, ir, ser, estar...)
IMPERATIVES = ('da', 'dé', 'den', 'demos', 'di', 'haz', 'pon', 'sal', 'ten', 'ven', 've', 'sé', 'vaya', 'vayan',
               'vamos', 'vayamos', 'está', 'esté', 'estén', 'estemos', 'sea', 'sean', 'seamos', 'oye', 'oiga',
               'oigan', 'oigamos')
STANDALONE_INF = ('dar', 'ir', 'ser', 'estar', 'haber')
STANDALONE_GER = ('dando', 'yendo', 'siendo', 'estando', 'habiendo')


def is_host(h, hosts):
    """Forma verbal a la que hoy se le pegan pronombres: infinitivo, gerundio o imperativo (presentes)."""
    kind = hosts.get(h)
    if kind is None:
        return False
    if kind != 'f':
        return True                                      # infinitivo, gerundio o imperativo de la tabla
    total, k, idx = stress(h)
    if total < 2:
        return False                                     # monosilabos: solo los de IMPERATIVES ("dante" no es dan + te)
    if h[idx] in ACCENTS and k == total - 1:
        return h.endswith(('én', 'ón'))                   # agudas con tilde: "detén", "propón"; no "hablé", "hablará"
    return True


def enclitic_host(w, hosts):
    """Si w es verbo + pronombres bien acentuado devuelve el verbo; si no, None."""
    for tail, count in TAILS:
        if not w.endswith(tail) or len(w) - len(tail) < 2:
            continue
        stem = w[:len(w) - len(tail)]
        cands = {stem, fold(stem)}
        if tail.startswith('nos') or tail.startswith('se'):
            cands.add(stem + 's')
            cands.add(fold(stem) + 's')
        for a, b in ACCENTS.items():                    # la tilde propia del verbo que se pierde: dé + me -> deme
            for h in list(cands):
                if h.endswith(b) or h.endswith(b + 'n'):
                    i = h.rfind(b)
                    cands.add(h[:i] + a + h[i + 1:])
        for h in cands:
            if is_host(h, hosts) and enclitic(h, tail, count) == w:
                return h
    return None


# ---------------------------------------------------------------- derivados por regla propia

def soft(stem):
    """Raices posibles de una palabra a la que se le anade un sufijo que empieza por e o i, deshaciendo el cambio
    ortografico: chiqu- -> chic-, amigu- -> amig-, agü- -> agu-, pedac- -> pedaz-. Una raiz en z o en g no vale:
    "pedazito" y "amigito" estan mal escritas."""
    if stem.endswith('qu'):
        return [stem[:-2] + 'c', stem]
    if stem.endswith('gü'):
        return [stem[:-2] + 'gu']
    if stem.endswith('gu'):
        return [stem[:-1], stem]
    if stem.endswith('c'):
        return [stem[:-1] + 'z']
    if stem.endswith(('z', 'g')):
        return []
    return [stem]


# Sufijos productivos: (terminacion, tipo). El diminutivo en -illo a secas no esta: en Mexico apenas se usa
# fuera de las palabras ya hechas (que RLA-ES trae) y cuela apellidos y nombres de lugar ("murillo", "hermosillo").
DIM = [(s + e, kind) for s, kind in (('it', 'v'), ('cit', 'c'), ('ecit', 'e'), ('cill', 'c'), ('ecill', 'e'))
       for e in ('o', 'a', 'os', 'as')]
# Los aumentativos en -ón y -ote no van por regla: son tambien finales de apellidos, nombres de lugar y palabras de otros
# idiomas ("sedona", "remote", "garzón"). Los corrientes que RLA-ES no trae estan en es_extra.dic.
AUG = [(e, 'v') for e in ('azo', 'aza', 'azos', 'azas')] + [('ísim' + e, 's') for e in ('o', 'a', 'os', 'as')]
PREFIXES = ('ex', 'super', 'anti', 'auto', 'semi', 'mini', 'micro', 'multi', 'mega', 'ultra', 'hiper', 'contra',
            'sobre', 'pre', 'sub', 'inter', 'extra', 'infra', 'pos', 'post', 'vice', 'video', 'tele', 'foto', 'radio',
            'ciber', 'bio', 'eco', 'narco', 'neo', 'seudo', 'tecno', 'electro', 'hidro', 'termo', 'aero', 'agro',
            'geo', 'psico', 'socio', 'cuasi', 'archi', 'requete')


def derived_base(w, bases, singular, count):
    """Palabra valida de la que w deriva por una regla productiva (diminutivo, golpe en -azo, superlativo, -mente, prefijo), o None.

    bases: forma sin tildes -> palabra valida que no es un verbo (sustantivos, adjetivos, adverbios, participios);
    singular: entradas del diccionario tal cual (no plurales); count(w): recuento en los subtitulos.
    Un prefijado se devuelve como "prefijo+palabra"."""
    def known(b):
        k = bases.get(fold(b)) if len(b) >= 3 else None
        return k if k is not None and len(k) >= 3 and count(k) >= C_BASE else None

    for suffix, kind in DIM + AUG:
        if not w.endswith(suffix) or len(w) - len(suffix) < 2:
            continue
        stem = w[:len(w) - len(suffix)]
        tries = []
        if kind == 'v':                                  # perr-ito, cas-ita, papel-ito, golp-azo
            for st in (soft(stem) if suffix[0] == 'i' else [stem]):
                tries += [st + 'o', st + 'a', st + 'e', st + 'os', st + 'as']
                if not st.endswith('s') or st in singular:
                    tries.append(st)                     # "papel", "dios"; no "pobres" ("pobresita" es "pobrecita")
        elif kind == 'c':                                # joven-cito, mujer-cita, pobre-cito, café-cito
            if stem[-1] in 'nreéáóúao':
                tries.append(stem)
        elif kind == 'e':                                # flor-ecita, pan-ecito, viej-ecito, luc-ecita
            for st in soft(stem):
                tries += [st, st + 'o', st + 'a']
        else:                                            # superlativo: car-ísimo, riqu-ísimo, amabil-ísimo, jovenc-ísimo
            for st in soft(stem):
                tries += [st + 'o', st + 'a', st + 'e', st]
            if stem.endswith('bil'):
                tries.append(stem[:-3] + 'ble')
            if stem.endswith('c'):
                tries.append(stem[:-1])
        for b in tries:
            k = known(b)
            if k is not None:
                return k
    if w.endswith('mente') and len(w) > 8:
        b = bases.get(fold(w[:-5]))
        if b is not None and b == w[:-5]:                # el adjetivo conserva su tilde: rápidamente
            return b
    if count(w) < T_PREFIX:
        return None
    for p in PREFIXES:
        if not w.startswith(p):
            continue
        rest = w[len(p):]
        if p[-1] in 'aeiou' and rest.startswith('rr'):   # antirrobo, autorretrato
            rest = rest[1:]
        elif rest.startswith('r') and p[-1] in 'aeiou':
            continue
        if len(rest) >= 5 and bases.get(fold(rest)) == rest and count(rest) >= C_BASE:
            return p + '+' + rest
    return None


# ---------------------------------------------------------------- politica c: erratas corrientes

def neighbors():
    nb = {}
    off = (0.0, 0.5, 1.5)
    for r, row in enumerate(ROWS):
        for i, ch in enumerate(row):
            x = i + off[r]
            s = ''
            for r2 in range(max(0, r - 1), min(len(ROWS), r + 2)):
                for j, c2 in enumerate(ROWS[r2]):
                    if (r2 != r or j != i) and abs(j + off[r2] - x) <= 1.01:
                        s += c2
            nb[ch] = s
    return nb


NEIGHBORS = neighbors()


def misspelling(w, i, ch):
    """Cambiar la letra i de w por ch es una falta de ortografia corriente: suenan igual en ese sitio."""
    c = w[i]
    nxt = w[i + 1] if i + 1 < len(w) else ''
    pair = c + ch if c < ch else ch + c
    if pair in ('bv', 'sz', 'iy', 'sx', 'nñ', 'ck', 'kq'):
        return True
    if pair in ('cs', 'cz', 'gj'):                       # ce/se, ci/zi, ge/je: solo delante de e o i
        return nxt in 'ei'
    return pair == 'mn' and nxt in 'bpv'                 # "tanbien", "enpezar"


def edits(w):
    """Variantes de w a una edicion: (variante, clase). Clase 0, falta de ortografia; 1, desliz al teclear; 2, lo demas."""
    n = len(w)
    for i in range(n):                                   # a w le sobra una letra
        ch = w[i]
        if ch == 'h' and not (i > 0 and w[i - 1] == 'c'):
            kind = 0                                     # una hache de mas: "hiba", "hera"
        elif (i > 0 and ch == w[i - 1]) or (i + 1 < n and ch == w[i + 1]):
            kind = 1                                     # tecla repetida
        else:
            kind = 2
        yield w[:i] + w[i + 1:], kind
    for i in range(n - 1):                               # dos letras bailadas
        if w[i] != w[i + 1]:
            yield w[:i] + w[i + 1] + w[i] + w[i + 2:], 1
    for i in range(n):                                   # una letra por otra
        c = w[i]
        close = NEIGHBORS.get(c, '')
        for ch in ALPHABET:
            if ch != c:
                yield w[:i] + ch + w[i + 1:], 0 if misspelling(w, i, ch) else 1 if ch in close else 2
    for i in range(n + 1):                               # a w le falta una letra
        for ch in ALPHABET:
            if ch == 'h' and not (i > 0 and w[i - 1] == 'c'):
                kind = 0                                 # la hache que falta: "ablando", "oras"
            elif ch in 'rl' and ((i > 0 and w[i - 1] == ch) or (i < n and w[i] == ch)):
                kind = 0                                 # erre o ele sencilla por doble: "carera", "levar"
            else:
                kind = 1
            yield w[:i] + ch + w[i:], kind


# ---------------------------------------------------------------- construccion

def enclitic_flag(f):
    """Clases de RLA-ES que pegan pronombres al verbo (letras con diacritico, de la À a la ú)."""
    return len(f) == 1 and '\u00c0' <= f <= '\u00ff'


def enclitic_prov(form, prov):
    lemma, f1, f2, pf = prov
    return enclitic_flag(f1) or (f1 == 'R' and form.endswith('se') and (form[:-2] == lemma or form.endswith('ndose')))


def build(base, folder):
    """Lista completa: las palabras de base (en su orden) y detras las nuevas, de mas a menos frecuente."""
    counts = {}
    order = {}
    for line in fetch(folder, 'es_full.txt', URL_FULL).splitlines():
        p = line.split()
        if len(p) != 2 or not PATTERN.match(p[0]) or p[0] in counts:
            continue
        if len(p[0]) == 1 and p[0] not in SINGLES:
            continue
        counts[p[0]] = int(p[1])
        order[p[0]] = len(order)
    log('es_full: %d formas con solo letras' % len(counts))

    aff_mx = parse_aff(fetch(folder, 'es_MX.aff', URL_RLA % 'es_MX.aff'))
    mx = Lexicon(fetch(folder, 'es_MX.dic', URL_RLA % 'es_MX.dic'), aff_mx)
    es = Lexicon(fetch(folder, 'es_ES.dic', URL_RLA % 'es_ES.dic'), parse_aff(fetch(folder, 'es_ES.aff', URL_RLA % 'es_ES.aff')))
    with open(os.path.join(DICT, 'es_extra.dic'), encoding='utf-8') as f:
        extra = Lexicon(f.read(), aff_mx)
    lexicons = (mx, es, extra)
    log('RLA-ES: %d formas es_MX, %d es_ES; es_extra.dic: %d' % (len(mx.forms), len(es.forms), len(extra.forms)))

    with open(os.path.join(DICT, 'es_quitar.txt'), encoding='utf-8') as f:
        removed = set(line.split('#')[0].strip() for line in f) - {''}
    base = [w for w in base if w not in removed]
    log('de la lista de siempre se quitan %d erratas con tilde (es_quitar.txt)' % len(removed))
    base_set = set(base)

    def valid(w):
        return w in mx or w in es or w in extra

    def count(w):
        return counts.get(w, 0)

    def provs(w):
        for lex in lexicons:
            for prov in lex.forms.get(w, ()):
                yield prov

    # ---- verbos: formas simples por lema, y a que formas se les pegan pronombres
    verb_forms = defaultdict(set)                        # infinitivo -> formas de R, E, I, X y D (sin prefijos)
    verb_form_set = set()                                # formas personales y gerundios de cualquier verbo
    hosts = {}                                           # forma -> 'i' infinitivo, 'g' gerundio, 'f' forma personal, 'm' imperativo
    for lex in lexicons:
        for form, ps in lex.forms.items():
            for prov in ps:
                lemma, f1, f2, pf = prov
                if f2:
                    continue
                if f1 in VERB_FLAGS:
                    verb_form_set.add(form)
                    if not pf:
                        verb_forms[lemma].add(form)
                    if enclitic_prov(form, prov):
                        continue
                    if form.endswith(('ando', 'endo')):
                        hosts[form] = 'g'
                    else:
                        hosts.setdefault(form, 'f')
                elif f1 == 'D' and not pf:
                    verb_forms[lemma].add(form)
        for w, fl in lex.entries:
            if VERB_FLAGS & set(fl) and PATTERN.match(w):
                verb_forms[w].add(w)
                hosts[w] = 'i'
                for f in fl:                               # el mismo verbo con sus prefijos: deshacer, rehacer
                    for r in aff_mx.get(('PFX', f), ()):
                        p = pfx(w, r)
                        if p is not None:
                            hosts[p] = 'i'
    for w in STANDALONE_INF:
        hosts[w] = 'i'
    for w in STANDALONE_GER:
        hosts[w] = 'g'
    for w in IMPERATIVES:
        hosts[w] = 'm'

    # ---- series que no entran en el paradigma: futuro de subjuntivo ("hablare") e imperfecto en -se ("hablase")
    fut_subj = {}                                        # forma -> su tercera persona del plural del preterito
    subj_se = set()
    past3 = set(f for f in verb_form_set if f.endswith(('aron', 'eron')))
    past3.update(('dieron', 'fueron', 'estuvieron', 'hubieron'))
    for p in past3:
        st = p[:-3]
        for e in ('re', 'res', 'ren', 'reis'):
            fut_subj[st + e] = p
        subj_se.update((st + 'se', st + 'ses', st + 'sen', st + 'seis'))
        i = max(st.rfind(v) for v in 'aeo')
        if i >= 0:
            marked = st[:i] + MARK[st[i]] + st[i + 1:]
            fut_subj[marked + 'remos'] = p
            subj_se.add(marked + 'semos')

    def only_fut_subj(w):
        """La forma solo existe como futuro de subjuntivo (no es ademas otra cosa: "pare", "prepare")."""
        p = fut_subj.get(w)
        if p is None:
            return False
        seen = False
        for lemma, f1, f2, pf in provs(w):
            seen = True
            if f1 not in VERB_FLAGS or f2 or p not in verb_forms.get(lemma, ()):
                return False
        return seen

    # politica b: futuro de subjuntivo en -ar que es tambien el futuro escrito sin tilde
    clash = set(f for f in fut_subj if f.endswith(('are', 'ares', 'aren'))
                and (f[:-1] + 'é' in verb_form_set or f[:-2] + 'ás' in verb_form_set or f[:-2] + 'án' in verb_form_set))

    # ---- palabras validas que no son verbos conjugados: bases de diminutivos, aumentativos, superlativos...
    infinitives = set(l for l in verb_forms if l.endswith(('ar', 'er', 'ir', 'ír')))
    singular = set(fold(w) for lex in lexicons for w, fl in lex.entries)
    bases = {}
    for lex in lexicons:
        for form, ps in lex.forms.items():
            if form not in infinitives and any(f1 not in VERB_FLAGS and not enclitic_flag(f1) for lemma, f1, f2, pf in ps):
                key = fold(form)
                if key not in bases or count(form) > count(bases[key]):
                    bases[key] = form

    # ---- candidatas: lo que aparece en los subtitulos y es valido por diccionario o por regla
    chosen = {}                                          # forma -> motivo

    def take(w, why):
        if w not in base_set and w not in chosen:
            chosen[w] = why

    frequent = set(w for w, c in counts.items() if c >= C_LEMMA and valid(w))

    def flexion_of_frequent(w):
        """Conjugacion, plural o femenino de una palabra frecuente (no el imperfecto en -se ni verbo con pronombre)."""
        if w in subj_se:
            return False
        for prov in provs(w):
            lemma, f1, f2, pf = prov
            if not pf and not f2 and (f1 in VERB_FLAGS or f1 in ('S', 'G', 'D')) and lemma in frequent \
                    and not enclitic_prov(w, prov):
                return True
        return False

    def is_enclitic(w):
        ps = list(provs(w))
        return bool(ps) and all(enclitic_prov(w, prov) for prov in ps)

    # Mexico: las entradas que trae es_MX y no es_ES (mexicanismos y americanismos) entran con que se hayan visto
    # C_MEXICO veces entre todas sus formas; los subtitulos, hechos sobre todo en Espana y Argentina, las traen poco
    spain = set(w for w, fl in es.entries)
    mexican = set(w for w, fl in mx.entries if w not in spain and PATTERN.match(w))
    family = defaultdict(int)
    for form, c in counts.items():
        for lemma in set(lemma for lemma, f1, f2, pf in mx.forms.get(form, ()) if lemma in mexican and not pf):
            family[lemma] += c
    seen = set(lemma for lemma, c in family.items() if c >= C_MEXICO)

    def mexican_form(w, c):
        for prov in mx.forms.get(w, ()):
            lemma, f1, f2, pf = prov
            if lemma not in seen or pf or f2 or w in subj_se or enclitic_prov(w, prov):
                continue
            if f1 in ('', 'S', 'G') or (c > 0 and (f1 in VERB_FLAGS or f1 == 'D')):
                return True
        return False

    derived = {}
    encl = {}
    mx_flags = dict(mx.entries)
    for lemma in sorted(seen):                           # tambien los plurales y femeninos que no hayan salido nunca
        buf = []
        expand(lemma, [f for f in mx_flags[lemma] if f in 'SG'], aff_mx, buf)
        for t in buf:
            if PATTERN.match(t[0]) and len(t[0]) >= MIN_LEN and t[0] not in counts and mexican_form(t[0], 0):
                take(t[0], 'mexico')
    for w, c in counts.items():
        if w in base_set or (len(w) < MIN_LEN and w not in extra):
            continue
        if w in mx or w in extra:
            if w in mx and mexican_form(w, c):
                take(w, 'mexico')
            elif c >= T_VALID:
                take(w, 'rla')
            elif c >= T_ENCL and is_enclitic(w):
                take(w, 'enclitico')
            elif c >= T_FLEX and flexion_of_frequent(w):
                take(w, 'flexion')
        elif w in es:
            if c >= T_SPAIN and VOSOTROS.search(w):
                take(w, 'rla-es')
        elif c >= min(T_ENCL, T_DERIV):
            h = enclitic_host(w, hosts) if c >= T_ENCL else None
            if h is not None:
                encl[w] = h
                take(w, 'enclitico')
            elif c >= T_DERIV:
                b = derived_base(w, bases, singular, count)
                if b is not None:
                    derived[w] = b
                    take(w, 'derivada')
    # una segunda vuelta: derivados de derivados ("chiquitito", "toditito")
    second = dict((fold(w), w) for w in derived)
    for w, c in counts.items():
        if c >= T_DERIV and w not in base_set and w not in chosen and not valid(w):
            b = derived_base(w, second, singular, count)
            if b is not None:
                derived[w] = b
                take(w, 'derivada')

    # ---- es_extra.dic: todo lo que no es verbo entra tal cual; los verbos, con su paradigma
    extra_verbs = []
    for w, fl in extra.entries:
        if VERB_FLAGS & set(fl):
            extra_verbs.append(w)
        else:
            buf = []
            expand(w, fl, aff_mx, buf)
            for t in buf:
                if PATTERN.match(t[0]):
                    take(t[0], 'extra')
    for w in list(chosen):
        if w in extra and chosen[w] != 'extra' and not any(f1 in VERB_FLAGS for lemma, f1, f2, pf in extra.forms[w]):
            chosen[w] = 'extra'

    # ---- paradigmas completos de los verbos mas usados y de los de es_extra.dic
    def gerund(lemma):
        g = [f for f in verb_forms[lemma] if f.endswith(('ando', 'iendo', 'yendo'))]
        return max(g, key=len) if g else None

    usage = {}
    for lemma in verb_forms:
        g = gerund(lemma)
        if g is not None and (lemma in mx or lemma in extra):
            usage[lemma] = min(count(g), count(lemma))   # el menor de los dos: "memorando" y "solar" no son verbos
    ranked = sorted((l for l in usage if usage[l] > 0), key=lambda l: (-usage[l], l))
    top = ranked[:K_VERBS] + [v for v in extra_verbs if v not in ranked[:K_VERBS]]
    fill_order = {}
    for vi, lemma in enumerate(top):
        stem = lemma[:-2]
        voseo = (stem + 'á', stem + 'ás') if lemma.endswith('ar') else (stem + 'é', stem + 'és') if lemma.endswith('er') \
            else (stem + 'ís',)
        for f in sorted(verb_forms[lemma]):
            if f in fut_subj or f in subj_se or f in voseo or len(f) < MIN_LEN:
                continue
            if f.endswith('se') and (f[:-2] == lemma or f.endswith('ndose')):
                continue                                 # "lavarse", "lavándose": solo si se usan
            if (f not in mx and f not in extra) or VOSOTROS.search(f):
                continue                                 # "vosotros": lo que haya salido arriba por su uso
            if f.endswith(('da', 'dos', 'das')) and f not in verb_form_set and count(f) == 0:
                continue                                 # participios en femenino o plural que nadie usa ("tosidas")
            fill_order.setdefault(f, (vi, f))
            take(f, 'extra' if lemma in extra_verbs else 'paradigma')

    # ---- exclusiones (solo sobre lo nuevo)
    saga = set()
    with open(os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw', 'starwars_words.txt'), encoding='utf-8') as f:
        for line in f:
            w = line.strip().lower()
            if w:
                saga.add(fold(w))
    banned = set()
    with open(os.path.join(DICT, 'es_excluir.txt'), encoding='utf-8') as f:
        for line in f:
            w = line.split('#')[0].strip()
            if w:
                banned.add(w)

    # Palabras corrientes del ingles y del aleman (las listas del propio teclado): los subtitulos traen frases sueltas en
    # esos idiomas y sus recuentos hinchan los de formas espanolas que se escriben igual ("tells", "oven", "bread")
    foreign = set()
    for name, first in (('words_en.txt', 15000), ('words_de.txt', 8000)):
        path = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw', name)
        if not os.path.exists(path):
            sys.exit('Falta %s: genera antes el ingles y el aleman (build_dictionaries.py sin idiomas los hace por orden)' % name)
        with open(path, encoding='utf-8') as f:
            foreign.update(line.strip() for line in list(f)[:first])

    def looks_foreign(w, why):
        """Es una palabra corriente en ingles o aleman y en espanol solo la flexion de una palabra rara (o de otra de esas)."""
        if w not in foreign or why in ('extra', 'derivada'):
            return False
        if why == 'rla-es':
            return True                                  # "responded", "salad": imperativos de vosotros solo sobre el papel
        for lemma, f1, f2, pf in provs(w):
            inflection = not pf and not f2 and (f1 in VERB_FLAGS or f1 in ('S', 'G', 'D'))
            if not inflection or (count(lemma) >= C_LEMMA and lemma not in foreign):
                return False                             # entrada propia o derivada ("portable", "inaudible") o flexion de
        return True                                      # una palabra corriente del espanol ("votes", "sane", "armen")

    by_fold = defaultdict(list)                          # forma plegada -> formas de siempre o nuevas
    for w in base:
        by_fold[fold(w)].append(w)
    for w in chosen:
        by_fold[fold(w)].append(w)
    known_fold = {}                                      # forma plegada de lo de siempre y lo que RLA-ES acepta -> recuento
    base_fold = {}                                       # lo mismo, solo con las palabras de siempre
    for w, c in counts.items():
        if c >= 200 and (w in base_set or valid(w)):
            k = fold(w)
            if c > known_fold.get(k, 0):
                known_fold[k] = c
            if w in base_set and c > base_fold.get(k, 0):
                base_fold[k] = c
    TYPO_RATIOS = (TYPO_RATIO_SPELL, TYPO_RATIO_SLIP, TYPO_RATIO_FAR)

    dropped = defaultdict(list)
    for w in sorted(chosen):
        c = max(count(w), 1)
        why = chosen[w]
        by_rule = why in ('derivada', 'enclitico') and not valid(w)
        reason = None
        if w in banned:
            reason = 'lista'
        elif fold(w) in saga:
            reason = 'starwars'
        elif w in fut_subj and (w in clash or why in ('flexion', 'paradigma')) and only_fut_subj(w):
            reason = 'futuro de subjuntivo'
        elif looks_foreign(w, why):
            reason = 'extranjera'
        if reason is None and why != 'extra' and (w == fold(w) or by_rule):
            # politica a: la misma palabra con otra acentuacion es mucho mas frecuente. Lo validado solo por regla y
            # los verbos con pronombres ceden enseguida: "agachate" (voseo) frente a "agáchate", "llevarón".
            for s in by_fold[fold(w)]:
                if s == w:
                    continue
                if by_rule or why == 'enclitico' or is_enclitic(w):
                    ratio = 0.25
                elif w.endswith('o') and s == w[:-1] + 'ó':
                    ratio = ACCENT_RATIO_O
                elif w.endswith('e') and s == w[:-1] + 'é':
                    ratio = ACCENT_RATIO_E
                else:
                    ratio = ACCENT_RATIO
                if count(s) >= ratio * c:
                    reason = 'tilde'
                    break
        if reason is None and why != 'extra' and w == fold(w):
            # politica c: a una errata de una palabra de siempre muchisimo mas frecuente
            for v, kind in edits(w):
                if base_fold.get(v, 0) >= TYPO_RATIOS[kind] * c:
                    reason = 'errata'
                    break
        if reason is None and why == 'derivada':
            # lo validado solo por regla cede antes: "esquisita" (exquisita), "especiamente" (especialmente); y un
            # prefijado, con cualquier letra de diferencia: "aeropuesto" (aeropuerto), "exraña" (extraña)
            fw = fold(w)
            prefixed = '+' in derived.get(w, '')
            for v, kind in edits(fw):
                k = known_fold.get(v, 0)
                if v == fw + 's' or v + 's' == fw:       # su propio plural o singular
                    continue
                if (prefixed and k >= 20 * c) or (kind < 2 and k * RULE_FACTOR >= TYPO_RATIOS[kind] * c):
                    reason = 'errata'
                    break
        if reason is not None:
            dropped[reason].append(w)
            del chosen[w]

    reasons = defaultdict(int)
    for w, why in chosen.items():
        reasons[why] += 1
    counted = sorted((w for w in chosen if w in counts), key=lambda w: (-counts[w], order[w]))
    uncounted = sorted((w for w in chosen if w not in counts), key=lambda w: fill_order.get(w, (len(top), w)))
    words = list(base) + counted + uncounted
    log('nuevas: %d (%s)' % (len(chosen), ', '.join('%s %d' % kv for kv in sorted(reasons.items()))))
    log('sin recuento en los subtitulos (van al final): %d' % len(uncounted))
    log('descartadas: ' + ', '.join('%s %d' % (k, len(v)) for k, v in sorted(dropped.items())))
    report = os.environ.get('DICC_INFORME')
    if report:                                           # informe de trabajo para revisar a mano que entra y que no
        with open(report, 'w', encoding='utf-8') as f:
            for w in counted + uncounted:
                f.write('%s\t%s\t%d\t%s\n' % (w, chosen[w], count(w), encl.get(w) or derived.get(w) or ''))
            for k, v in sorted(dropped.items()):
                for w in v:
                    f.write('%s\t-%s\t%d\t\n' % (w, k, count(w)))
    return words
