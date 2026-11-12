#!/usr/bin/env python3
"""Construye el juego de glifos del teclado y la fuente "Aurek Teclado":
   base: font/base-silvinor-Aurebesh.ttf (OFL 1.1, SilvinoR) -> letras, numerales, puntuacion, signos, digrafos (ligaduras)
   propios (glyph_compose.py, al grosor de la base, 1.55): n~ con virgulilla, vocales con tilde aguda, grave, dieresis y
   circunflejo, a/o con virgulilla, o con barra, distinto y por mil; la barra invertida es el espejo de la /
   previa (sin integrar hasta que se aprueben, bandera INCLUDE_TIMES_DIVIDE): por (x) y entre
Salidas: tools/glyphs.json, promo/glyphs.json, app/.../GlyphData.java, font/AurekTeclado.ttf
Uso: python3 tools/build_font_set.py   (requiere fonttools, numpy, opencv)"""
import json, math, os, sys, shutil, numpy as np, cv2
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from glyph_compose import mirror_x, accents, symbols, times_divide, times, plus_minus, divide_candidates, currency
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC=os.path.join(ROOT,'font','base-silvinor-Aurebesh.ttf')
INCLUDE_TIMES=True           # por (x): el boceto del usuario (dos + en espejo con hueco de un trazo, girados 45 grados)
DIVIDE_CHOICE=None           # entre: '1a', '1b', '2' o '3' (glyph_compose.divide_candidates); None mientras el usuario elige
POUND_LETTER=None            # libra: 'l' o 'p' con dos barras; None mientras el usuario elige (queda la de la fuente base)

# ---------------- base: extraer contornos y metricas a unidades (altura de mayusculas = 10, y hacia abajo)
f=TTFont(SRC); upm=f['head'].unitsPerEm; cap=f['OS/2'].sCapHeight; U=10.0/cap
cmap=f.getBestCmap(); gs=f.getGlyphSet(); hmtx=f['hmtx']
def contours(gname):
    pen=RecordingPen(); gs[gname].draw(pen); polys=[]; cur=[]
    for op,args in pen.value:
        if op=='moveTo': cur=[args[0]]
        elif op=='lineTo': cur.append(args[0])
        elif op=='qCurveTo':
            pts=list(args); p0=cur[-1]; ctrls=pts[:-1]; end=pts[-1]
            if len(ctrls)>1:
                mids=[((ctrls[i][0]+ctrls[i+1][0])/2,(ctrls[i][1]+ctrls[i+1][1])/2) for i in range(len(ctrls)-1)]
                segs=[]; prev=p0
                for i,c in enumerate(ctrls):
                    e=mids[i] if i<len(mids) else end; segs.append((prev,c,e)); prev=e
            else: segs=[(p0,ctrls[0],end)]
            for a,c,e in segs:
                for t in np.linspace(0,1,7)[1:]:
                    cur.append(((1-t)**2*a[0]+2*(1-t)*t*c[0]+t*t*e[0],(1-t)**2*a[1]+2*(1-t)*t*c[1]+t*t*e[1]))
        elif op in ('closePath','endPath'):
            if len(cur)>2: polys.append(cur)
            cur=[]
    return polys
def to_units(polys, adv):
    pts=[p for poly in polys for p in poly]
    xmin=min(p[0] for p in pts); xmax=max(p[0] for p in pts)
    return {'w':round((xmax-xmin)*U,3),'p':[[[round((x-xmin)*U,3),round((cap-y)*U,3)] for x,y in poly] for poly in polys],
            'lsb':round(xmin*U,3),'adv':round(adv*U,3)}
G={}
for ch in 'abcdefghijklmnopqrstuvwxyz0123456789,.?!:;-()/$"\'`¿¡«»[]_|~@#%&*+=<>{}^\\±£¥°©®™':
    gn=cmap.get(ord(ch)); G[ch]=to_units(contours(gn), hmtx[gn][0])
# la barra invertida de la fuente es la / en espejo pero 0.25 unidades mas alta: se usa el espejo exacto de la /
G['\\']=dict(G['/'], p=mirror_x(G['/']['p'], G['/']['w']/2))
for lk in f['GSUB'].table.LookupList.Lookup:
    for st in lk.SubTable:
        st=getattr(st,'ExtSubTable',st)
        if st.LookupType==4:
            for first,ligs in st.ligatures.items():
                for L in ligs:
                    comps=[first]+list(L.Component)
                    s=''.join(chr(next(c for c,g in cmap.items() if g==n)) for n in comps)
                    if s.islower() and len(s)==2: G[s]=to_units(contours(L.LigGlyph), hmtx[L.LigGlyph][0])
G['“']=G['”']=G['"']; G['‘']=G['`']; G['’']=G["'"]; del G['`']
SPACE_ADV=round(hmtx[cmap[32]][0]*U,3)

# ---------------- propios: solo lo que la fuente no tiene (glyph_compose.py)
G.update(accents(G))          # n~, vocales con tilde aguda, grave, dieresis, circunflejo; a/o con virgulilla
G.update(symbols(G))          # o con barra, distinto, por mil
G.update(plus_minus(G))       # mas-menos: el + con la barra del - debajo (sustituye al de la fuente base)
G.update(currency(G, POUND_LETTER))   # euro y yen (y libra cuando se elija): la letra con dos barras en medio
if INCLUDE_TIMES: G.update(times(G))
if DIVIDE_CHOICE: G['\u00f7']=divide_candidates(G)[DIVIDE_CHOICE]

# ---------------- salidas: json, GlyphData.java
for p in ('tools/glyphs.json','promo/glyphs.json'): json.dump(G,open(os.path.join(ROOT,p),'w'))
def jstr(s): return '"'+''.join('\\"' if c=='"' else '\\\\' if c=='\\' else c if 32<=ord(c)<127 else '\\u%04x'%ord(c) for c in s)+'"'
keys=list(G)
def data(g):   # "w;lsb;adv;x,y x,y ...;x,y ..." (un poligono por campo)
    return ';'.join([str(g['w']),str(g['lsb']),str(g['adv'])]+[' '.join('%s,%s'%(x,y) for x,y in poly) for poly in g['p']])
out=["package com.aurebesh.teclado;","","/** Glifos Aurebesh vectorizados (unidades: 0 = borde superior, 10 = linea base; las marcas van por encima de 0).",
     " *  Base: fuente Aurebesh de SilvinoR (OFL 1.1) + marcas (n~, tildes, dieresis, circunflejo, virgulilla) y signos",
     " *  compuestos propios (o con barra, distinto, por mil). Generado por tools/build_font_set.py.",
     " *  DATA[i] = \"ancho;margenIzq;avance;poligono;poligono...\" con cada poligono como \"x,y x,y ...\". */",
     "final class GlyphData {","    private GlyphData() {}",
     "    static final float SPACE_ADV = %sf;"%SPACE_ADV,
     "    static final String[] KEYS = {"+", ".join(jstr(k) for k in keys)+"};",
     "    static final String[] DATA = {"]
for k in keys: out.append("        /* %s */ \"%s\","%(k.encode('unicode_escape').decode(), data(G[k])))
out+=["    };","}",""]
open(os.path.join(ROOT,'app/src/main/java/com/aurebesh/teclado/GlyphData.java'),'w').write("\n".join(out))

# ---------------- fuente TTF "Aurek Teclado" (OFL) con ligaduras
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
K=70.0  # unidades de fuente por unidad nuestra (altura de mayusculas 700/1000)
def gname(k):
    if len(k)==2: return k[0]+'_'+k[1]
    return 'uni%04X'%ord(k) if not k.isalnum() or ord(k)>127 else k
def orient(poly, clockwise):
    a=sum((poly[i][0]*poly[(i+1)%len(poly)][1]-poly[(i+1)%len(poly)][0]*poly[i][1]) for i in range(len(poly)))
    return poly if (a<0)==clockwise else poly[::-1]
def is_hole(poly, others):
    pt=(poly[0][0],poly[0][1])
    return any(cv2.pointPolygonTest(np.array(o,np.float32),pt,False)>0 for o in others if o is not poly)
glyphs={}; metrics={}; order=['.notdef','space']
pen=TTGlyphPen(None); glyphs['.notdef']=pen.glyph(); metrics['.notdef']=(int(6*K),0)
pen=TTGlyphPen(None); glyphs['space']=pen.glyph(); metrics['space']=(int(SPACE_ADV*K),0)
cmap_out={32:'space'}
for k,g in G.items():
    name=gname(k); order.append(name); pen=TTGlyphPen(None)
    polys=[[(x,10-y) for x,y in poly] for poly in g['p']]       # y hacia arriba
    for poly in polys:
        hole=is_hole(poly,polys); poly=orient(poly, clockwise=not hole)
        pen.moveTo((round((poly[0][0]+g['lsb'])*K),round(poly[0][1]*K)))
        for x,y in poly[1:]: pen.lineTo((round((x+g['lsb'])*K),round(y*K)))
        pen.closePath()
    glyphs[name]=pen.glyph(); metrics[name]=(int(round(g['adv']*K)),int(round(g['lsb']*K)))
    if len(k)==1:
        cmap_out[ord(k)]=name
        if k.isalpha() and k.upper()!=k: cmap_out[ord(k.upper())]=name
fb=FontBuilder(1000,isTTF=True); fb.setupGlyphOrder(order); fb.setupCharacterMap(cmap_out); fb.setupGlyf(glyphs)
fb.setupHorizontalMetrics(metrics); fb.setupHorizontalHeader(ascent=980,descent=-220)
fb.setupNameTable({'familyName':'Aurek Teclado','styleName':'Regular','uniqueFontIdentifier':'AurekTeclado-Regular',
    'fullName':'Aurek Teclado Regular','psName':'AurekTeclado-Regular','version':'Version 1.0',
    'copyright':'Copyright (c) 2022 SilvinoR (base Aurebesh, OFL 1.1); glifos adicionales (c) 2026 Teclado Aurebesh',
    'licenseDescription':'This Font Software is licensed under the SIL Open Font License, Version 1.1.',
    'licenseInfoURL':'https://openfontlicense.org'})
fb.setupOS2(sTypoAscender=980,sTypoDescender=-220,sTypoLineGap=0,usWinAscent=980,usWinDescent=220,sCapHeight=700,sxHeight=700,achVendID='AURK')
fb.setupPost()
liga=' '.join('sub %s %s by %s;'%(d[0],d[1],gname(d)) for d in sorted(k for k in G if len(k)==2))
addOpenTypeFeaturesFromString(fb.font, 'languagesystem DFLT dflt; languagesystem latn dflt; feature liga { %s } liga;'%liga)
fb.save(os.path.join(ROOT,'font','AurekTeclado.ttf'))
print('glifos:',len(G),'| espacio:',SPACE_ADV,'| fuente: font/AurekTeclado.ttf')
