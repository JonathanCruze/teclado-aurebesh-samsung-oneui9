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

# ---------------- simbolos del boceto del usuario (2.2.x): por, mas-menos, entre (tres lecturas), monedas
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
def _U(polys): return union([[p] for p in polys])
STEM_W=1.539   # asta del + Aurebesh: 1.54 de ancho y 1.80 de alto
STEM_L=1.803
def _bars(L): return [bar(0,-T,L,0), bar(0,T,L,0)]            # las dos barras del + y su espejo, hueco de un trazo
def _stems(gap=0.0):                                            # las dos astas, hacia fuera y alineadas (gap: despegadas)
    y=1.5*T+gap
    return [rect(-STEM_W/2,-(y+STEM_L),STEM_W/2,-y), rect(-STEM_W/2,y,STEM_W/2,y+STEM_L)]
X_OFFSET=1/3.   # desplazamiento de cada T a lo largo de su barra (fraccion de la barra): las astas no quedan en linea
def times(G):
    """Por: el + Aurebesh (una T) y su espejo, barras paralelas con un hueco de un trazo y las astas hacia fuera, con
    cada T desplazada un tercio de barra a lo largo de ella (como en el boceto del usuario), girado 45 grados; cuelga del
    borde superior como el +."""
    plus=G['+']; L=plus['w']; top=bbox(plus['p'])[1]; off=L*X_OFFSET/2; y=1.5*T
    pieces=[translate([bar(0,-T,L,0)],-off,0)[0], translate([bar(0,T,L,0)],off,0)[0],
            translate([rect(-STEM_W/2,-(y+STEM_L),STEM_W/2,-y)],-off,0)[0], translate([rect(-STEM_W/2,y,STEM_W/2,y+STEM_L)],off,0)[0]]
    return {'\u00d7':_metrics(_U(_hang(centered(_rot(pieces,-45),0,0),top)),plus)}
def plus_minus(G):
    """Mas-menos: el + con la barra del - debajo del asta, separada un trazo."""
    plus=G['+']; L=plus['w']; b=bbox(plus['p'])
    return {'\u00b1':_metrics(_U(copy_polys(plus['p'])+[rect(0,b[3]+T,L,b[3]+2*T)]),plus)}
def divide_candidates(G):
    """Entre, tres lecturas del boceto: '1' la x con las astas despegadas un trazo; '2' el * con un punto sobre el
    vertice (su asta hace de punto inferior); '3' el * boca abajo (cuenco con el asta arriba) y un punto debajo.
    La elegida se integra con DIVIDE_CHOICE en build_font_set.py."""
    plus=G['+']; L=plus['w']; top=bbox(plus['p'])[1]; ast=G['*']; b=bbox(ast['p']); cx=(2.05+3.59)/2
    out={}
    out['1']=_metrics(_U(_hang(centered(_rot(_bars(L)+_stems(T),-45),0,0),top)),plus)
    out['2']=_metrics(_U(copy_polys(ast['p'])+[_square(cx,b[1]-1.5*T)]),ast)
    flipped=[[[x,(b[1]+b[3])-y] for x,y in p][::-1] for p in ast['p']]
    out['3']=_metrics(_U(flipped+[_square(cx,b[3]+1.5*T)]),ast)
    return out
# monedas: dos palitos finos y horizontales (medio trazo, como los del simbolo de creditos $) que cruzan solo un trazo
# de la letra, donde los marco el usuario; los dos palitos terminan en los mismos limites aunque el trazo sea diagonal
TICK_W=0.74; TICK_OUT=1.23; TICK_GAP=0.76
def _hticks_aligned(letter_glyph, centers, cut):
    """Dos palitos horizontales centrados en (x,y) de centers sobre un trazo cuyo corte horizontal mide cut; ambos con la
    misma extension horizontal (la union de las dos) para que terminen al mismo limite."""
    half=(cut+2*TICK_OUT)/2
    x0=min(x-half for x,_ in centers); x1=max(x+half for x,_ in centers)
    bars_=[rect(x0,y-TICK_W/2,x1,y+TICK_W/2) for _,y in centers]
    return _metrics(union([letter_glyph['p']]+[[b] for b in bars_]),letter_glyph)
def _arm_x(y,p0,p1): return p0[0]+(y-p0[1])/(p1[1]-p0[1])*(p1[0]-p0[0])
_D=TICK_GAP/2+TICK_W/2
def pound(G, where='l_vertical'):
    """Libra: la l con los palitos en su barra vertical, algo por encima de la mitad (eleccion del usuario); tambien
    'l_diagonal' (perpendiculares a la diagonal) y 'p_vertical' por si se quiere cambiar."""
    if where=='l_diagonal':
        g=G['l']; a=math.radians(34.6); dx,dy=math.cos(a),math.sin(a); out=[]
        for sgn in (-1,1):
            off=sgn*_D; out.append(bar(2.32+dx*off,7.9+dy*off,1.5+2*TICK_OUT,34.6+90,t=TICK_W))
        return _metrics(union([g['p']]+[[t] for t in out]),g)
    if where=='p_vertical': return _hticks_aligned(G['p'],[(5.57,5.8-_D),(5.57,5.8+_D)],1.52)
    return _hticks_aligned(G['l'],[(5.2,5.8-_D),(5.2,5.8+_D)],1.54)
def _hticks_follow(letter_glyph, centers, cut):
    """Dos palitos horizontales de la misma longitud, cada uno centrado en su (x,y) (siguen el trazo si es diagonal)."""
    half=(cut+2*TICK_OUT)/2
    bars_=[rect(x-half,y-TICK_W/2,x+half,y+TICK_W/2) for x,y in centers]
    return _metrics(union([letter_glyph['p']]+[[b] for b in bars_]),letter_glyph)
def _overlap_area(letter_glyph, centers, cut, S=40):
    """Area (en unidades cuadradas) en que los palitos pisan la letra: solo deben cruzar su trazo (cut x grosor cada uno)."""
    half=(cut+2*TICK_OUT)/2; x0,y0,x1,y1=bbox(letter_glyph['p']); pad=4
    W=int((x1-x0+2)*S)+2*pad; H=int((y1-y0+2)*S)+2*pad
    def mk(polys, evenodd):
        m=np.zeros((H,W),np.uint8)
        for poly in polys:
            pm=np.zeros_like(m); cv2.fillPoly(pm,[np.array([[pad+(x-x0+1)*S,pad+(y-y0+1)*S] for x,y in poly],np.int32)],1)
            if evenodd: m^=pm
            else: m|=pm
        return m
    letter=mk(letter_glyph['p'],True)
    ticks_=mk([rect(x-half,y-TICK_W/2,x+half,y+TICK_W/2) for x,y in centers],False)
    return float((letter&ticks_).sum())/(S*S)
def _clear(letter_glyph, centers, cut, margin=0.5):
    """True si los palitos solo cruzan su trazo: el area pisada no supera la del cruce (con holgura) y, ensanchados
    margin a cada lado, tampoco tocan otro trazo."""
    base=2*TICK_W*cut
    if _overlap_area(letter_glyph,centers,cut)>base*1.25: return False
    wide=[(x,y) for x,y in centers]
    old=globals()['TICK_OUT']; globals()['TICK_OUT']=old+margin
    try: grown=_overlap_area(letter_glyph,wide,cut)
    finally: globals()['TICK_OUT']=old
    return grown<=base*1.25
def _highest_clear(letter_glyph, p0, p1, cut, top, bottom, step=0.1):
    """Centro mas alto del par de palitos entre top y bottom que no pisa otro trazo de la letra."""
    c=top
    while c<=bottom:
        centers=[(_arm_x(c-_D,p0,p1),c-_D),(_arm_x(c+_D,p0,p1),c+_D)]
        if _clear(letter_glyph,centers,cut): return c,centers
        c+=step
    centers=[(_arm_x(bottom-_D,p0,p1),bottom-_D),(_arm_x(bottom+_D,p0,p1),bottom+_D)]
    return bottom,centers
def euro(G):
    """Euro: los palitos en el brazo izquierdo de la e, en su mitad alta, sin pisar el otro brazo."""
    p0,p1=(0.75,2.3),(3.5,9.5); c,centers=_highest_clear(G['e'],p0,p1,1.62,5.5,7.0)   # desde la mitad alta, donde la aprobo el usuario
    return _hticks_follow(G['e'],centers,1.62)
def yen(G):
    """Yen: los palitos en el brazo derecho de la y, tan arriba como deje el trazo interior (sin montarse sobre el)."""
    p0,p1=(7.65,2.32),(4.43,10.0); c,centers=_highest_clear(G['y'],p0,p1,1.62,4.6,7.0)
    return _hticks_follow(G['y'],centers,1.62)
def currency(G, pound_where='l_vertical'):
    return {'\u20ac':euro(G), '\u00a5':yen(G), '\u00a3':pound(G,pound_where)}
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

# ---------------- (c) y (r) de la fuente base: mas aire entre la letra interior y el marco
# En la base, el marco es un cuadrado redondeado (anillo de medio trazo, T/2) y la letra de dentro es la c o la r al 50 por
# ciento, casi pegada al marco (0.4 arriba y abajo; la r a 0.1 del lado). Aqui la letra se encoge respecto al centro del
# marco y el marco se agranda conservando su grosor hasta que queda al menos un trazo de la base (T) de aire por los cuatro
# lados; los dos signos comparten el mismo marco para que no bailen de tamano.
BADGE_LETTER=0.72   # tanto por uno al que se encoge la letra interior (de 3.1x3.8 unidades pasa a 2.2x2.8; trazo 0.56)
BADGE_GAP=T         # aire minimo entre letra y marco: un trazo de la base
def _vectorize(m,x0,y0,S,pad,eps,tol,min_area):
    """Vuelve a vectorizar una mascara pintada con el marco (x0,y0), escala S y margen pad (como union)."""
    cs,_=cv2.findContours(m,cv2.RETR_CCOMP,cv2.CHAIN_APPROX_NONE); out=[]
    for c in cs:
        if cv2.contourArea(c)<min_area*S*S: continue
        a=snap(cv2.approxPolyDP(c,eps*S,True).reshape(-1,2).tolist(),tol*S)
        if len(a)>=3: out.append([[round(x0+(px-pad)/S,3),round(y0+(py-pad)/S,3)] for px,py in a])
    return out
def offset_polys(polys,d,S=64,eps=0.03,tol=0.06,min_area=0.1):
    """Desplaza d unidades hacia fuera (d<0: hacia dentro) el borde de la figura que forman polys (par-impar), por
    rasterizacion: se pinta, se dilata (o erosiona) con un disco de radio d y se vuelve a vectorizar. Las esquinas
    redondeadas conservan su forma (el radio crece d, como en un desplazamiento geometrico)."""
    x0,y0,x1,y1=bbox(polys); r=int(round(abs(d)*S)); pad=6+r; SH=8; F=1<<SH
    W=int(math.ceil((x1-x0)*S))+2*pad; H=int(math.ceil((y1-y0)*S))+2*pad
    m=np.zeros((H,W),np.uint8)
    for poly in polys:
        pm=np.zeros_like(m)
        pts=np.array([[round((pad+(x-x0)*S)*F),round((pad+(y-y0)*S)*F)] for x,y in poly],np.int32)
        cv2.fillPoly(pm,[pts],255,cv2.LINE_8,SH); m^=pm
    if r>0:
        k=cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(2*r+1,2*r+1))
        m=cv2.dilate(m,k) if d>0 else cv2.erode(m,k)
    return _vectorize(m,x0,y0,S,pad,eps,tol,min_area)
def badge_parts(g):
    """Separa un glifo "letra en un marco": (contorno exterior del marco, su agujero, poligonos de la letra). El marco son
    los dos poligonos de caja mas grande."""
    order=sorted(range(len(g['p'])),key=lambda i:-(lambda b:(b[2]-b[0])*(b[3]-b[1]))(bbox([g['p'][i]])))
    outer,inner=g['p'][order[0]],g['p'][order[1]]
    letter=[g['p'][i] for i in range(len(g['p'])) if i not in order[:2]]
    return outer,inner,letter
def badge_grow(g,k=BADGE_LETTER,gap=BADGE_GAP):
    """Cuanto hay que agrandar el marco de g (por lado) para que, con la letra encogida a k, quede gap de aire."""
    outer,inner,letter=badge_parts(g); ix0,iy0,ix1,iy1=bbox([inner]); lx0,ly0,lx1,ly1=bbox(letter)
    return max(0.0,(lx1-lx0)*k/2+gap-(ix1-ix0)/2,(ly1-ly0)*k/2+gap-(iy1-iy0)/2)
def badge_air(g,k=BADGE_LETTER,gap=BADGE_GAP,grow=None):
    """Encoge la letra interior a k respecto al centro del agujero del marco y agranda el marco grow unidades por lado
    conservando el grosor (sin grow, lo justo para que quede gap de aire). Devuelve el glifo con el mismo centro, el
    mismo margen izquierdo y el avance crecido lo que crece el ancho."""
    outer,inner,letter=badge_parts(g); ix0,iy0,ix1,iy1=bbox([inner]); cx,cy=(ix0+ix1)/2,(iy0+iy1)/2
    if grow is None: grow=badge_grow(g,k,gap)
    small=[[[round(x,3),round(y,3)] for x,y in p] for p in scale(letter,k,k,cx,cy)]
    if grow>0: outer=offset_polys([outer],grow)[0]; inner=offset_polys([inner],grow)[0]
    polys=[outer,inner]+small; x0=bbox(polys)[0]; polys=translate(polys,-x0,0)
    polys=[[[round(x,3),round(y,3)] for x,y in p] for p in polys]
    dw=bbox(polys)[2]-g['w']
    return derived(g,polys,w=g['w']+dw,adv=g['adv']+dw)
def badges(G,k=BADGE_LETTER,gap=BADGE_GAP):
    """(c) y (r) con aire: la letra al k y el mismo marco para los dos (el que pida el mas ancho, la r)."""
    grow=max(badge_grow(G[ch],k,gap) for ch in '©®')
    return {ch:badge_air(G[ch],k,gap,grow) for ch in '©®'}
