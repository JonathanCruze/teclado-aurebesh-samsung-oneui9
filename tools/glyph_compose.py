#!/usr/bin/env python3
"""Glifos propios compuestos sobre los de la fuente base (unidades: altura de letra = 10, y hacia abajo; las
marcas van por encima de 0). Piezas (rectangulos y barras), transformaciones de poligonos, union por
rasterizacion (las piezas se pintan, se unen y se vuelven a vectorizar limpias, como en symbols_design.py)
y los disenos: marcas (aguda, grave, dieresis, circunflejo, virgulilla), o con barra, distinto, por mil y la
previa de por y entre. Lo usa build_font_set.py; preview_glyphs.py dibuja con el la previa."""
import math, numpy as np, cv2

T=1.55        # grosor de trazo de la base: el de las marcas propias
LETTER_TOP=2.315   # borde superior de las letras en unidades (la caja va de 0 a 10; las letras ocupan 2.315-10)
MARK_GAP=T    # aire entre la letra y su marca: un trazo (hasta la 2.2 las marcas flotaban a 3.5 unidades)
MARK_Y=LETTER_TOP-MARK_GAP-T/2   # centro vertical de las marcas (tilde aguda, grave, dieresis, circunflejo)
TILDE_S=1.9   # lado del cuadrado de la n~ original; la virgulilla mide 1.5 veces mas de ancho

# ---------------- piezas y transformaciones
def rect(x0,y0,x1,y1): return [[x0,y0],[x1,y0],[x1,y1],[x0,y1]]
def bar(cx,cy,length,angle_deg,t=T):
    """Barra centrada en (cx,cy) de longitud length y grosor t, girada angle_deg (0 = horizontal)."""
    a=math.radians(angle_deg); dx,dy=math.cos(a),math.sin(a); nx,ny=-dy,dx
    L=length/2; h=t/2
    return [[cx+dx*L+nx*h,cy+dy*L+ny*h],[cx-dx*L+nx*h,cy-dy*L+ny*h],[cx-dx*L-nx*h,cy-dy*L-ny*h],[cx+dx*L-nx*h,cy+dy*L-ny*h]]
def translate(polys,dx,dy): return [[[x+dx,y+dy] for x,y in p] for p in polys]
def scale(polys,sx,sy,ox=0.0,oy=0.0):
    """Escala respecto al punto (ox,oy)."""
    return [[[ox+(x-ox)*sx,oy+(y-oy)*sy] for x,y in p] for p in polys]
def mirror_x(polys,axis):
    """Espejo horizontal respecto a la vertical x = axis (se invierte el sentido para conservar la orientacion)."""
    return [[[2*axis-x,y] for x,y in p][::-1] for p in polys]
def bbox(polys):
    pts=[q for p in polys for q in p]
    return min(q[0] for q in pts),min(q[1] for q in pts),max(q[0] for q in pts),max(q[1] for q in pts)
def centered(polys,cx,cy):
    """Traslada los poligonos para que el centro de su caja quede en (cx,cy)."""
    x0,y0,x1,y1=bbox(polys); return translate(polys,cx-(x0+x1)/2,cy-(y0+y1)/2)
def copy_polys(polys): return [[list(q) for q in p] for p in polys]

# ---------------- union por rasterizacion
def snap(poly,tol):
    """Endereza lados casi verticales u horizontales y quita vertices casi colineales."""
    P=[list(p) for p in poly]; n=len(P)
    for _ in range(3):
        for i in range(n):
            a,c=P[i],P[(i+1)%n]; dx,dy=c[0]-a[0],c[1]-a[1]
            if abs(dx)<=tol and abs(dy)>tol: m=(a[0]+c[0])/2; a[0]=c[0]=m
            elif abs(dy)<=tol and abs(dx)>tol: m=(a[1]+c[1])/2; a[1]=c[1]=m
    out=[]
    for i in range(n):
        p0,p1,p2=P[i-1],P[i],P[(i+1)%n]
        cross=(p1[0]-p0[0])*(p2[1]-p1[1])-(p1[1]-p0[1])*(p2[0]-p1[0])
        l1=math.hypot(p1[0]-p0[0],p1[1]-p0[1]); l2=math.hypot(p2[0]-p1[0],p2[1]-p1[1])
        if l1 and l2 and abs(cross)/(l1*l2)<0.04: continue
        out.append(p1)
    return out

def union(groups,S=64,eps=0.03,tol=0.06,min_area=0.1):
    """groups: listas de poligonos. Dentro de un grupo se combinan par-impar (un glifo con sus agujeros) y los
    grupos se unen entre si. Devuelve los poligonos del resultado (contornos y agujeros) en el mismo marco."""
    x0,y0,x1,y1=bbox([p for g in groups for p in g]); pad=4; SH=8; F=1<<SH
    W=int(math.ceil((x1-x0)*S))+2*pad; H=int(math.ceil((y1-y0)*S))+2*pad
    m=np.zeros((H,W),np.uint8)
    def fill(img,poly):
        pts=np.array([[round((pad+(x-x0)*S)*F),round((pad+(y-y0)*S)*F)] for x,y in poly],np.int32)
        cv2.fillPoly(img,[pts],255,cv2.LINE_8,SH)
    for g in groups:
        gm=np.zeros_like(m)
        for poly in g:
            pm=np.zeros_like(m); fill(pm,poly); gm^=pm
        m|=gm
    cs,_=cv2.findContours(m,cv2.RETR_CCOMP,cv2.CHAIN_APPROX_NONE)
    out=[]
    for c in cs:
        if cv2.contourArea(c)<min_area*S*S: continue
        a=snap(cv2.approxPolyDP(c,eps*S,True).reshape(-1,2).tolist(),tol*S)
        if len(a)>=3: out.append([[round(x0+(px-pad)/S,3),round(y0+(py-pad)/S,3)] for px,py in a])
    return out

# ---------------- glifos derivados
def derived(base,polys,w=None,adv=None):
    """Copia de las metricas de base con otros poligonos (y otro ancho o avance si se dan)."""
    g=dict(base); g['p']=polys
    if w is not None: g['w']=round(w,3)
    if adv is not None: g['adv']=round(adv,3)
    return g
def with_mark(base,marks): return derived(base,copy_polys(base['p'])+marks)
def apex_x(g):
    """Centro horizontal del remate superior del glifo (donde la n lleva su cuadrado)."""
    pts=[p for poly in g['p'] for p in poly]; top=min(p[1] for p in pts)
    xs=[p[0] for p in pts if p[1]<=top+0.6]; return (min(xs)+max(xs))/2
def glyph_center_y(g): x0,y0,x1,y1=bbox(g['p']); return (y0+y1)/2

# marcas: todas con el grosor T y centradas en altura en MARK_Y
def acute(w):
    """Tilde aguda: barra a 45 grados de longitud 3 a la derecha del centro (igual que en la version 2.1)."""
    cx_,cy_,L,t=0.64*w,MARK_Y,3.0,T; dx=L/2*0.7071; dy=L/2*0.7071; x0,y0,x1,y1=cx_-dx,cy_+dy,cx_+dx,cy_-dy; h=t/0.7071/2
    return [[[x0,y0-h],[x1,y1-h],[x1,y1+h],[x0,y0+h]]]
def grave(w):
    """Acento grave: la tilde aguda reflejada en espejo horizontal respecto al centro del glifo."""
    return mirror_x(acute(w),w/2)
def diaeresis(w):
    """Dieresis: dos cuadrados de lado T separados T, centrados sobre el glifo."""
    return [rect(cx-T/2,MARK_Y-T/2,cx+T/2,MARK_Y+T/2) for cx in (w/2-T,w/2+T)]
def circumflex(w,caret):
    """Circunflejo ("casita"): el glifo de ^ de la fuente, al ancho de la dieresis (3T) y con la altura de la
    marca (alto del ^ sin escalar, para que el grosor del vertice siga siendo el de la base), centrado encima."""
    x0,y0,x1,y1=bbox(caret['p']); sx=3*T/(x1-x0)
    return centered(scale(copy_polys(caret['p']),sx,1.0),w/2,MARK_Y)
def tilde(ax):
    """Virgulilla: el cuadrado de la n~ de la 2.1 (lado TILDE_S) alargado un 50 por ciento, centrado en ax."""
    hw=1.5*TILDE_S/2; y1=LETTER_TOP-MARK_GAP; y0=y1-TILDE_S
    return [[[ax-hw,y0],[ax+hw,y0],[ax+hw,y1],[ax-hw,y1]]]

def accents(G):
    """n~, vocales con tilde aguda, grave, dieresis, circunflejo y a/o con virgulilla, cada una sobre su letra."""
    out={}
    out['ñ']=with_mark(G['n'],tilde(apex_x(G['n'])))
    for v,acc in zip('aeiou','áéíóú'): out[acc]=with_mark(G[v],acute(G[v]['w']))
    for v,acc in zip('aeiou','àèìòù'): out[acc]=with_mark(G[v],grave(G[v]['w']))
    for v,acc in zip('aeiou','äëïöü'): out[acc]=with_mark(G[v],diaeresis(G[v]['w']))
    for v,acc in zip('aeiou','âêîôû'): out[acc]=with_mark(G[v],circumflex(G[v]['w'],G['^']))
    for v,acc in zip('ao','ãõ'): out[acc]=with_mark(G[v],tilde(apex_x(G[v])))
    return out

# signos compuestos
def slashed(base,slash,k=None):
    """base con la / de la fuente superpuesta, centrada y escalada k veces (sin k, a la altura de base: la cruza
    de abajo-izquierda a arriba-derecha de borde a borde); se unen por rasterizacion."""
    if k is None: b=bbox(base['p']); l=bbox(slash['p']); k=(b[3]-b[1])/(l[3]-l[1])
    s=centered(scale(copy_polys(slash['p']),k,k),base['w']/2,glyph_center_y(base))
    return derived(base,union([base['p'],s]))
def per_mille(pct):
    """Por mil: el % con su cuadrado inferior derecho duplicado a la derecha, a la misma distancia que separa
    los dos cuadrados del %."""
    boxes=[p for p in pct['p'] if bbox([p])[1]>=6]           # cuadrado inferior y su agujero
    top=[p for p in pct['p'] if bbox([p])[3]<=6 and bbox([p])[2]<3]   # cuadrado superior (izquierda)
    bx0,_,bx1,_=bbox(boxes); gap=bx0-bbox(top)[2]; dx=(bx1-bx0)+gap
    return derived(pct,copy_polys(pct['p'])+translate(boxes,dx,0),w=pct['w']+dx,adv=pct['adv']+dx)
def symbols(G):
    return {'ø':slashed(G['o'],G['/']), '≠':slashed(G['='],G['/'],1.0), '‰':per_mille(G['%'])}

def times_divide(G):
    """Por (x) y entre: las dos barras del + Aurebesh (su longitud y el grosor T) giradas 45 grados formando una X,
    colgando del borde superior de la letra como el + y el =. El entre es la misma X con cada barra partida en
    dos trozos separados en el centro por T (los trazos del + se cruzan sin hueco)."""
    plus=G['+']; L=plus['w']; D=(L+T)/math.sqrt(2); cx=D/2; cy=bbox(plus['p'])[1]+D/2
    rsb=plus['adv']-plus['lsb']-plus['w']
    def metrics(polys): return {'w':round(D,3),'p':polys,'lsb':plus['lsb'],'adv':round(plus['lsb']+D+rsb,3)}
    times=metrics(union([[bar(cx,cy,L,45)],[bar(cx,cy,L,-45)]]))
    Lp=(L-T)/2; d=T/2+Lp/2; pieces=[]
    for ang in (45,-45):
        a=math.radians(ang)
        for sgn in (1,-1): pieces.append([bar(cx+sgn*d*math.cos(a),cy+sgn*d*math.sin(a),Lp,ang)])
    return {'×':times,'÷':metrics(union(pieces))}

# ---------------- simbolos del boceto del usuario (2.2.1): por, mas-menos, entre (cuatro lecturas), monedas
def _rot(polys, ang, cx=0.0, cy=0.0):
    a=math.radians(ang); c,s_=math.cos(a),math.sin(a)
    return [[[cx+(x-cx)*c-(y-cy)*s_, cy+(x-cx)*s_+(y-cy)*c] for x,y in p] for p in polys]
def _square(cx,cy,side=None):
    side=T if side is None else side
    return rect(cx-side/2,cy-side/2,cx+side/2,cy+side/2)
def _metrics(polys, base):
    """Poligonos pegados al borde izquierdo con las metricas laterales de base."""
    x0,y0,x1,y1=bbox(polys); polys=translate(polys,-x0,0); rsb=base['adv']-base['lsb']-base['w']
    return {'w':round(x1-x0,3),'p':polys,'lsb':base['lsb'],'adv':round(base['lsb']+(x1-x0)+rsb,3)}
def _hang(polys, top):
    """Traslada para que el borde superior quede en top (los signos cuelgan del borde superior como + y =)."""
    x0,y0,x1,y1=bbox(polys); return translate(polys,0,top-y0)
def _two_bars(L, gap=None):
    gap=T if gap is None else gap
    return [bar(0,-(gap/2+T/2),L,0), bar(0,(gap/2+T/2),L,0)]
def times(G):
    """Por: el + Aurebesh (una T) dos veces en espejo, con un hueco de un trazo entre las dos barras y las dos astas
    unidas en un puente, girado 45 grados (como lo boceto el usuario)."""
    plus=G['+']; L=plus['w']; top=bbox(plus['p'])[1]
    pieces=_two_bars(L)+[bar(0,0,3*T,90,t=1.539)]
    polys=_hang(centered(_rot(pieces,-45),0,0),top)
    return {'\u00d7':_metrics(union([[p] for p in polys]),plus)}
def plus_minus(G):
    """Mas-menos: el + con la barra del - debajo del asta, separada un trazo."""
    plus=G['+']; L=plus['w']; b=bbox(plus['p'])
    polys=copy_polys(plus['p'])+[rect(0,b[3]+T,L,b[3]+2*T)]
    return {'\u00b1':_metrics(union([[p] for p in polys]),plus)}
def divide_candidates(G):
    """Entre, cuatro lecturas del boceto: 1a dos barras (sin puente) con un punto a cada lado; 1b dos barras con un
    punto en cada extremo; 2 el + con un punto sobre la barra y otro bajo el asta; 3 un cuenco con un punto dentro
    y otro debajo. La elegida se integra con DIVIDE_CHOICE en build_font_set.py."""
    plus=G['+']; L=plus['w']; b=bbox(plus['p']); top=b[1]; out={}
    pieces=_two_bars(L)+[_square(0,-(3*T)), _square(0,3*T)]
    out['1a']=_metrics(union([[p] for p in _hang(centered(_rot(pieces,-45),0,0),top)]),plus)
    d=L/2+1.5*T; pieces=_two_bars(L)+[_square(-d,0), _square(d,0)]
    out['1b']=_metrics(union([[p] for p in _hang(centered(_rot(pieces,-45),0,0),top)]),plus)
    polys=copy_polys(plus['p'])+[_square(L/2, top-1.5*T), _square(L/2, b[3]+1.5*T)]
    out['2']=_metrics(union([[p] for p in polys]),plus)
    sag=1.6; R=(L*L/(8*sag))+sag/2; cyc=top+T/2+R-sag; a0=math.asin((L/2)/R); po=[]; pi_=[]
    for i in range(25):
        a=-a0+2*a0*i/24
        po.append([L/2+R*math.sin(a), cyc+R*math.cos(a)]); pi_.append([L/2+(R-T)*math.sin(a), cyc+(R-T)*math.cos(a)])
    yb=cyc+R
    polys=[po+pi_[::-1]]+[_square(L/2, yb-T-sag/2-0.2), _square(L/2, yb+1.5*T)]
    out['3']=_metrics(union([[p] for p in polys]),plus)
    return out
def two_lines(G, letter, out=0.7, gap=0.9):
    """La letra con dos barras horizontales en medio (euro, yen, libra), que asoman out a cada lado."""
    g=G[letter]; x0,y0,x1,y1=bbox(g['p']); ym=(y0+y1)/2
    b1=rect(x0-out, ym-gap/2-T, x1+out, ym-gap/2); b2=rect(x0-out, ym+gap/2, x1+out, ym+gap/2+T)
    return _metrics(union([g['p'],[b1],[b2]]),g)
def currency(G, pound_letter=None):
    """Euro (e con dos barras) y yen (y con dos barras); la libra solo cuando se elija su letra (l o p)."""
    out={'\u20ac':two_lines(G,'e'), '\u00a5':two_lines(G,'y')}
    if pound_letter: out['\u00a3']=two_lines(G,pound_letter)
    return out
def times_divide(G):
    """Compatibilidad con la previa de la 2.2: por y la lectura 1a del entre."""
    out=dict(times(G)); out['\u00f7']=divide_candidates(G)['1a']; return out

