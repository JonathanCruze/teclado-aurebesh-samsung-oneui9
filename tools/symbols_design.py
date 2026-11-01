"""Glifos Aurebesh para los simbolos que el alfabeto no tiene (+ = * % # @ &): se componen con barras y
cuadrados, se unen por rasterizacion y se vectorizan limpios."""
import json, math, numpy as np, cv2
S=40  # px por unidad
T=1.7 # grosor de trazo (unidades)

def rect(x0,y0,x1,y1): return [[x0,y0],[x1,y0],[x1,y1],[x0,y1]]
def bar(cx,cy,length,angle_deg,t=T):
    a=math.radians(angle_deg); dx,dy=math.cos(a),math.sin(a); nx,ny=-dy,dx
    L=length/2; h=t/2
    return [[cx+dx*L+nx*h,cy+dy*L+ny*h],[cx-dx*L+nx*h,cy-dy*L+ny*h],[cx-dx*L-nx*h,cy-dy*L-ny*h],[cx+dx*L-nx*h,cy+dy*L-ny*h]]
def diamond_ring(cx,cy,r,t=T):
    outer=[[cx,cy-r],[cx+r,cy],[cx,cy+r],[cx-r,cy]]; ri=r-t*1.25
    inner=[[cx,cy-ri],[cx+ri,cy],[cx,cy+ri],[cx-ri,cy]]
    return [outer,inner]  # el anillo sale de la union par-impar

def snap(poly,tol):
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

def vectorize(w, pieces, evenodd_pieces=()):
    """pieces: poligonos que se UNEN; evenodd_pieces: grupos (outer, inner) que forman anillos."""
    H=int(10*S)+8; W=int(w*S)+8
    m=np.zeros((H,W),np.uint8)
    for poly in pieces:
        cv2.fillPoly(m,[np.array([[4+x*S,4+y*S] for x,y in poly],np.int32)],255)
    for group in evenodd_pieces:
        g=np.zeros_like(m)
        for poly in group:
            pm=np.zeros_like(m); cv2.fillPoly(pm,[np.array([[4+x*S,4+y*S] for x,y in poly],np.int32)],255); g^=pm
        m|=g
    cs,_=cv2.findContours(m,cv2.RETR_CCOMP,cv2.CHAIN_APPROX_NONE)
    polys=[]
    for c in cs:
        if cv2.contourArea(c)<0.3*S*S: continue
        a=snap(cv2.approxPolyDP(c,0.045*S,True).reshape(-1,2).tolist(),0.06*S)
        polys.append([[round((p[0]-4)/S,3),round((p[1]-4)/S,3)] for p in a])
    return {'w':round(w,3),'p':polys}

G={}
G['+']=vectorize(7,[rect(0,5-T/2,7,5+T/2), rect(3.5-T/2,1.5,3.5+T/2,8.5)])
G['=']=vectorize(7,[rect(0,2.6,7,2.6+T), rect(0,7.4-T,7,7.4)])
G['#']=vectorize(8.2,[rect(0,2.3,8.2,2.3+T), rect(0,7.7-T,8.2,7.7), bar(2.6,5,10.2,97), bar(5.6,5,10.2,97)])
G['*']=vectorize(7.6,[bar(3.8,5,8.6,90), bar(3.8,5,8.6,30), bar(3.8,5,8.6,150)])
G['%']=vectorize(8.6,[rect(0,0,2.7,2.7), rect(5.9,7.3,8.6,10), bar(4.3,5,12.2,-50,1.6)])
G['@']=vectorize(8.6,[rect(0,0,8.6,T), rect(0,0,T,10), rect(0,10-T,8.6,10), rect(8.6-T,0,8.6,4.2), rect(3.4,3.7,5.8,6.3)])
G['&']=vectorize(8.4,[rect(6.2,10-T,8.4,10)],evenodd_pieces=[diamond_ring(3.6,3.1,3.1), diamond_ring(3.6,6.9,3.1)])
json.dump(G,open('symbols_glyphs.json','w'))

# vista previa grande y pequena
def render(items,Sc,pad,H):
    tot=sum(it['w']*Sc+pad for _,it in items)+pad
    img=np.full((H,int(tot),3),255,np.uint8); x=pad; y=(H-10*Sc)//2
    for name,it in items:
        mask=np.zeros(img.shape[:2],np.uint8)
        for poly in it['p']:
            mm=np.zeros_like(mask); cv2.fillPoly(mm,[np.array([[x+px*Sc,y+py*Sc] for px,py in poly],np.int32)],1); mask^=mm
        img[mask==1]=(56,40,27)
        if Sc>8: cv2.putText(img,name,(int(x),int(y+10*Sc+34)),cv2.FONT_HERSHEY_SIMPLEX,0.9,(0,0,0),2)
        x+=it['w']*Sc+pad
    return img
items=list(G.items())
big=render(items,20,60,300); small=render(items,6,24,90)
canvas=np.full((big.shape[0]+small.shape[0]+20,max(big.shape[1],small.shape[1]),3),255,np.uint8)
canvas[:big.shape[0],:big.shape[1]]=big; canvas[big.shape[0]+20:,:small.shape[1]]=small
cv2.imwrite('symbols_preview.png',canvas); print({k:len(v['p']) for k,v in G.items()})
