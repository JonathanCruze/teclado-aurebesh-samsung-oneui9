#!/usr/bin/env python3
"""Hoja de diseno de las comillas angulares (elegida y alternativas), el menor/mayor o igual y las rayas.
Uso: python3 tools/preview_comillas.py   (lee tools/glyphs.json; escribe promo/out/previa_comillas.png)"""
import json, os, sys, numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT,'tools'))
from glyph_compose import guillemets
G=json.load(open(os.path.join(ROOT,'tools','glyphs.json')))
BG=(0xF6,0xF2,0xEA); CARD=(0xE7,0xDA,0xC0); INK=(0x6F,0x4D,0x2E); SUB=(0x85,0x69,0x4A)
def font(size,bold=False):
    f='/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf'%('-Bold' if bold else '')
    return ImageFont.truetype(f,size)
def mask(g,S,SS=4,top=1.0,bot=1.0):
    w=int(np.ceil(g['w']*S))+2; h=int((top+10+bot)*S)
    m=np.zeros((h*SS,w*SS),np.uint8)
    for poly in g['p']:
        pm=np.zeros_like(m); pts=np.array([[(1+x*S)*SS,((top+y)*S)*SS] for x,y in poly],np.int32)
        cv2.fillPoly(pm,[pts],1); m^=pm
    return cv2.resize(m.astype(np.float32),(w,h),interpolation=cv2.INTER_AREA)
def draw_run(arr,x,y,glyphs,S,gap=1.6,ink=INK):
    for g in glyphs:
        if g is None: x+=int(3.2*S); continue
        m=mask(g,S); h,w=m.shape
        tile=arr[y:y+h,x:x+w].astype(np.float32); col=np.array(ink,np.float32)
        arr[y:y+h,x:x+w]=(tile*(1-m[...,None])+col*m[...,None]).astype(np.uint8)
        x+=w+int(gap*S)
    return x
def run_width(glyphs,S,gap=1.6):
    x=0
    for g in glyphs:
        if g is None: x+=int(3.2*S); continue
        x+=int(np.ceil(g['w']*S))+2+int(gap*S)
    return x
def word(s):
    out=[]; i=0
    while i<len(s):
        if s[i]==' ': out.append(None); i+=1; continue
        out.append(G[s[i]]); i+=1
    return out
W=1560; H=1440
img=Image.new('RGB',(W,H),BG); d=ImageDraw.Draw(img)
d.text((40,30),'Comillas « », menor/mayor o igual y rayas',fill=INK,font=font(34,True))
d.text((40,78),'Hechos con piezas que el Aurebesh ya tiene. Recuadro marcado = lo que lleva la versión; a su derecha, alternativas.',fill=SUB,font=font(18))
# cards
rows=[]
VAR=[('dos_cunas','elegido: la cuña del < dos veces, un solo techo'),('cuna_corta','segunda cuña recortada (más estrecha)'),('apiladas','cuñas apiladas (se parece al ≤)')]
y0=125; S=10
labels=[('« »','comillas angulares','como la rr: la pieza del <, repetida')]
arr=None
def card(x,y,w,h): d.rounded_rectangle((x,y,x+w,y+h),radius=18,fill=CARD)
# fila 1
d.text((40,y0+48),'« »',fill=INK,font=font(30,True)); d.text((40,y0+90),'comillas angulares',fill=SUB,font=font(16)); d.text((40,y0+112),'la cuña del  <  repetida,',fill=SUB,font=font(16)); d.text((40,y0+134),'como la rr repite su diagonal',fill=SUB,font=font(16))
xs=[400,860,1210]; ws=[420,320,320]
card(xs[0],y0,ws[0],210)
runs=[]
for (how,note),x in zip(VAR,xs):
    q=guillemets(G,how); runs.append((x+22,y0+34,[q['«'],q['»']],note,how=='dos_cunas'))
# fila 2: menor o igual
y1=y0+240
d.text((40,y1+48),'≤ ≥',fill=INK,font=font(30,True)); d.text((40,y1+90),'menor / mayor o igual',fill=SUB,font=font(16)); d.text((40,y1+112),'son los glifos que la fuente',fill=SUB,font=font(16)); d.text((40,y1+134),'traía puestos en « »',fill=SUB,font=font(16))
card(xs[0],y1,ws[0],210)
runs.append((xs[0]+22,y1+34,[G['≤'],G['≥']],'elegido: al mantener  <  y  >',True))
runs.append((xs[1]+22,y1+34,[G['<'],G['>']],'para comparar: < y > (sin la raya)',False))
# fila 3: rayas
y2=y1+240
d.text((40,y2+48),'– —',fill=INK,font=font(30,True)); d.text((40,y2+90),'raya corta y raya larga',fill=SUB,font=font(16)); d.text((40,y2+112),'el guion, vez y media',fill=SUB,font=font(16)); d.text((40,y2+134),'y dos veces de largo',fill=SUB,font=font(16))
card(xs[0],y2,ws[0],210)
runs.append((xs[0]+22,y2+34,[G['-'],G['–'],G['—']],'elegido: al mantener el guion salen – y —',True))
runs.append((xs[1]+22,y2+34,[G['•'],None,G['æ']],'de paso: • (punto de lista) y æ',False))
arr=np.array(img)
for x,y,gl,note,chosen in runs: draw_run(arr,x,y,gl,S,gap=2.4)
img=Image.fromarray(arr); d=ImageDraw.Draw(img)
for x,y,gl,note,chosen in runs: d.text((x-4,y+12*S+22),note,fill=INK if chosen else SUB,font=font(15,chosen))
# muestras en contexto
y3=y2+250
d.text((40,y3),'En contexto',fill=INK,font=font(22,True))
arr=np.array(img); S2=5; yy=y3+44
samples=[]
for how,label in (('dos_cunas','elegido'),('cuna_corta','cuña corta'),('apiladas','apiladas')):
    q=guillemets(G,how); samples.append(([q['«']]+word('que la fuerza')+[q['»']],'«que la fuerza»  ·  '+label))
samples.append((word('x')+[None,G['≤'],None]+word('10')+[None,None]+word('y')+[None,G['≥'],None]+word('3'),'x ≤ 10    y ≥ 3'))
samples.append((word('1990')+[G['–']]+word('2005')+[None,None]+word('si')+[None,G['—'],None]+word('dijo')+[None,G['—'],None]+word('no'),'1990–2005    sí — dijo — no'))
pos=[(40,yy),(40,yy+105),(40,yy+210),(40,yy+315),(40,yy+420)]
for (gl,label),(x,y) in zip(samples,pos): draw_run(arr,x,y,gl,S2,gap=1.5)
img=Image.fromarray(arr); d=ImageDraw.Draw(img)
for (gl,label),(x,y) in zip(samples,pos): d.text((x,y+12*S2+6),label,fill=SUB,font=font(15))
out=os.path.join(ROOT,'promo','out','previa_comillas.png'); img.save(out); print(out,img.size)
