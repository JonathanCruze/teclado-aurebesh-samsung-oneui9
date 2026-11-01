import json, numpy as np, cv2
G=json.load(open('glyphs.json')); S=60
def mask_of(g):
    xs=[p[0] for poly in g['p'] for p in poly]; ys=[p[1] for poly in g['p'] for p in poly]
    x0,y0=min(xs),min(ys); W=int((max(xs)-x0)*S)+20; H=int((max(ys)-y0)*S)+20
    m=np.zeros((H,W),np.uint8)
    for poly in g['p']:
        mm=np.zeros_like(m); cv2.fillPoly(mm,[np.array([[10+(x-x0)*S,10+(y-y0)*S] for x,y in poly],np.int32)],1); m^=mm
    return m,(max(xs)-x0),(max(ys)-y0)
def stroke(m):
    dt=cv2.distanceTransform(m,cv2.DIST_L2,5)
    sk=cv2.ximgproc.thinning(m*255) if hasattr(cv2,'ximgproc') else None
    if sk is None:
        # esqueleto aproximado: maximos locales de la transformada
        k=cv2.dilate(dt,np.ones((7,7),np.uint8)); sk=((dt>=k-0.5)&(m>0)).astype(np.uint8)*255
    vals=dt[sk>0]*2/S
    return float(np.median(vals)), float(np.percentile(vals,90))
rows=[]
for k,g in G.items():
    m,w,h=mask_of(g); med,p90=stroke(m)
    rows.append((k,w,h,med,p90))
letters=[r for r in rows if len(r[0])==1 and r[0].isalpha() and r[0] in 'abcdefghijklmnopqrstuvwxyz']
digits=[r for r in rows if r[0].isdigit()]
import statistics
print("letras: grosor mediana %.2f (p10 %.2f, p90 %.2f), ancho medio %.1f"%(statistics.median(r[3] for r in letters), np.percentile([r[3] for r in letters],10), np.percentile([r[3] for r in letters],90), statistics.mean(r[1] for r in letters)))
print("digitos: grosor mediana %.2f, alto %.1f"%(statistics.median(r[3] for r in digits), statistics.mean(r[2] for r in digits)))
print("%-4s %6s %6s %7s"%('g','ancho','alto','grosor'))
for r in rows:
    if r[0] in 'abcdefghijklmnopqrstuvwxyz0123456789' and len(r[0])==1: continue
    print("%-4s %6.2f %6.2f %7.2f"%r[:4])
