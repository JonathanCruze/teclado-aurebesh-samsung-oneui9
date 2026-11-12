#!/usr/bin/env python3
"""Previa a 2x de los glifos compuestos: el + actual junto a los por (x) y entre propuestos (aun sin integrar,
bandera INCLUDE_TIMES_DIVIDE de build_font_set.py), el = y el distinto, el % y el por mil, la o y la o con barra, y la
fila de acentos nuevos, cada uno con su etiqueta latina, sobre fondo oscuro (Noche) y claro (Estelar).
Uso: python3 tools/preview_glyphs.py [salida.png]   (lee tools/glyphs.json; requiere numpy, opencv, Pillow)"""
import json, os, sys, numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from glyph_compose import times_divide, bbox
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT=sys.argv[1] if len(sys.argv)>1 else os.path.join(ROOT,'promo','out','previa_glifos.png')
G=json.load(open(os.path.join(ROOT,'tools','glyphs.json'))); G.update(times_divide(G))

X=2                 # factor de la previa
S=8*X               # px por unidad (altura de letra = 10 unidades)
TOP=4.6; BOT=1.0    # unidades por encima del borde superior (marcas) y por debajo de la linea base
GAP=2.4*X*S/X; GROUP=5*S
ROWS=[[('+','actual'),('\u00d7','propuesto'),('\u00f7','propuesto'),None,('=','actual'),('\u2260','nuevo'),None,
       ('%','actual'),('\u2030','nuevo'),None,('o','actual'),('\u00f8','nuevo')],
      [('\u00e1','actual'),('\u00e0','nuevo'),('\u00e4','nuevo'),('\u00e2','nuevo'),('\u00e3','nuevo'),None,
       ('\u00f1','alargada'),('\u00f5','nuevo')]]
def font(size):
    for f in ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf','/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'):
        if os.path.exists(f): return ImageFont.truetype(f,size)
    return ImageFont.load_default()
F1,F2=font(13*X),font(9*X)

def mask(g,SS=4):
    """Mascara antialias del glifo (relleno par-impar), con la caja [-TOP, 10+BOT] en vertical."""
    w=int(np.ceil(g['w']*S))+2; h=int((TOP+10+BOT)*S)
    m=np.zeros((h*SS,w*SS),np.uint8)
    for poly in g['p']:
        pm=np.zeros_like(m); pts=np.array([[(1+x*S)*SS,((TOP+y)*S)*SS] for x,y in poly],np.int32)
        cv2.fillPoly(pm,[pts],1); m^=pm
    return cv2.resize(m.astype(np.float32),(w,h),interpolation=cv2.INTER_AREA)

def panel(bg,key,ink,sub):
    rows=[]
    for row in ROWS:
        items=[]; width=int(GAP)
        for it in row:
            if it is None: width+=GROUP; items.append(None); continue
            m=mask(G[it[0]]); items.append((it,m)); width+=m.shape[1]+int(GAP)
        rows.append((items,width))
    W=max(w for _,w in rows)+int(GAP); rowH=int((TOP+10+BOT)*S)+30*X
    img=Image.new('RGB',(W,rowH*len(rows)+14*X),bg); d=ImageDraw.Draw(img); arr=np.array(img)
    y=10*X
    for items,_ in rows:
        x=int(GAP)
        for it in items:
            if it is None: x+=GROUP; continue
            (ch,note),m=it; h,w=m.shape
            tile=arr[y:y+h,x:x+w].astype(np.float32); col=np.array(ink,np.float32)
            arr[y:y+h,x:x+w]=(tile*(1-m[...,None])+col*m[...,None]).astype(np.uint8)
            x+=w+int(GAP)
        y+=rowH
    img=Image.fromarray(arr); d=ImageDraw.Draw(img); y=10*X
    for items,_ in rows:
        x=int(GAP)
        for it in items:
            if it is None: x+=GROUP; continue
            (ch,note),m=it; h,w=m.shape; cx=x+w/2
            d.text((cx,y+h+2*X),ch,fill=ink,font=F1,anchor='mt'); d.text((cx,y+h+19*X),note,fill=sub,font=F2,anchor='mt')
            x+=w+int(GAP)
        y+=rowH
    return img
dark=panel((0x0E,0x14,0x19),None,(0xDC,0xE6,0xEE),(0x8F,0xA0,0xAE))      # Noche: fondo y tinta
light=panel((0xE3,0xE8,0xEE),None,(0x1B,0x28,0x38),(0x7A,0x87,0x94))     # Estelar
W=max(dark.width,light.width); title=16*X
out=Image.new('RGB',(W,dark.height+light.height+title),(0xE3,0xE8,0xEE)); d=ImageDraw.Draw(out)
d.text((8*X,4*X),'Previa de glifos a %dx: por (x) y entre propuestos, distinto, por mil, o con barra y acentos nuevos'%X,fill=(0x1B,0x28,0x38),font=F2)
out.paste(dark,(0,title)); out.paste(light,(0,title+dark.height))
os.makedirs(os.path.dirname(OUT),exist_ok=True); out.save(OUT); print('previa:',OUT,out.size)
