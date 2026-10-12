import numpy as np, colorsys, sys
from PIL import Image
W=720; c=W/2; yy,xx=np.mgrid[:W,:W]; m=np.hypot(xx-c,yy-c)<0.42*W
def cls(a):   # same classifier as ae_v5/stats_v5.py (used through v9)
    p=a[m]; mx=p.max(1); mn=p.min(1); lit=mx>0.2; col=lit&((mx-mn)>0.08)
    q=p[col][::3]; h=np.array([colorsys.rgb_to_hsv(*x)[0]*360 for x in q]) if len(q) else np.zeros(1)
    f=lambda s:s.mean()*col.mean()*100
    return dict(dark=(~lit).mean()*100, red=f((h>=340)|(h<25)), blue=f((h>=205)&(h<280)), cyan=f((h>=150)&(h<205)), white=(mn>0.85).mean()*100)
def load(path): return np.asarray(Image.open(path).convert('RGB').resize((W,W))).astype(float)/255
