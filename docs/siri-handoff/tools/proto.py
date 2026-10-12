# ae-v11 python prototype of the petal-mesh renderer (mirrors the Kotlin port; used for tuning + overlap checks)
import numpy as np
from math import *
CAM=5.85
# petal table: colour, group(0:+45,1:-30), twist range deg (lo,hi), bend radius (R) or 0, fixed orientation euler (x,y,z rad), wiggle seed
COL={'r':(0.878,0.157,0.235),'b':(0.29,0.482,1.0),'c':(0.24,0.86,0.80)}
P=[dict(c='r',g=0,tw=(0,20),bend=0.9,e=(0.3,0.2,1.2),s=1.0),
   dict(c='r',g=1,tw=(150,200),bend=0.0,e=(1.1,0.4,4.6),s=2.0),
   dict(c='b',g=1,tw=(0,20),bend=0.9,back=True,e=(0.5,2.6,3.3),s=3.0),
   dict(c='b',g=0,tw=(150,200),bend=0.0,e=(1.3,1.9,0.5),s=4.0),
   dict(c='c',g=0,tw=(0,20),bend=0.9,e=(2.4,0.9,5.5),s=5.0),
   dict(c='c',g=1,tw=(170,190),bend=0.0,e=(0.9,2.2,2.6),s=6.0)]
AX=[np.array([0.35,0.85,0.40]),np.array([-0.55,0.30,0.78])]
W=[45.0,-30.0]
A,B=0.88,0.566
OFF=0.25
WIDE_A=0.45; BLADE_AZ=dict(r=90.0,b=210.0,c=330.0); BLADE_OFF=0.2; BLADE_ROLL=0.0; WIDE_TILT=35.0; WIDE_UP=0.25; BACK_BEND=0.15; SAT=0.85; BACK_S=1.1; BACK_G=0.5; BACK_OFF=(0.0,-0.35,-0.55)   # petal centre shifted along its long axis so one end sits near the ball centre   # semi axes (1.4R x 0.9R)
NR,NT=14,48
def rotAxis(a,th):
    a=a/np.linalg.norm(a); c,s=cos(th),sin(th); x,y,z=a
    return np.array([[c+x*x*(1-c),x*y*(1-c)-z*s,x*z*(1-c)+y*s],[y*x*(1-c)+z*s,c+y*y*(1-c),y*z*(1-c)-x*s],[z*x*(1-c)-y*s,z*y*(1-c)+x*s,c+z*z*(1-c)]])
def eul(e):
    return rotAxis(np.array([0,0,1.]),e[2])@rotAxis(np.array([0,1.,0]),e[1])@rotAxis(np.array([1.,0,0]),e[0])
def wig(t,seed):  # loopable wiggle, base period 5 s (+ 2 harmonics), range ~[-1,1]
    return (0.6*sin(2*pi*t/5+seed*1.7)+0.3*sin(2*pi*2*t/5+seed*2.9)+0.1*sin(2*pi*3*t/5+seed*4.1))
rho=np.concatenate([np.linspace(0,0.84,9),[0.88,0.92,0.94,0.96,0.97,0.985,1.0]])
th=np.linspace(0,2*pi,NT+1)[:-1]
RH,TH=np.meshgrid(rho,th,indexing='ij')
def tex_alpha(r):   # inverted circle mask: 0 centre -> 1 edge, outer feather 0.84..1.0
    return np.clip(r,0,1)**1.5*(1-np.clip((r-0.84)/0.16,0,1)**2)
def petal_mesh(p,t):
    u=RH*np.cos(TH); v=RH*np.sin(TH); x=A*u; y=B*v*(np.sqrt(np.maximum(1-u*u,0)) if p['tw'][1]>100 else np.where(u>0,np.maximum(1-u*u,0)**0.2,1.0)); z=np.zeros_like(x)   # leaf: pointed tips
    w=0.5+0.5*wig(t,p['s']); twd=p['tw'][0]+(p['tw'][1]-p['tw'][0])*w
    if p['tw'][1]>100: ph=pi/2-radians(twd)/2*np.sign(u)*(1-(1-np.abs(u))**2)   # twist concentrated at the centre: side-on there, ends face-on
    else: ph=radians(twd)*u/2
    y,z=y*np.cos(ph)-z*np.sin(ph), y*np.sin(ph)+z*np.cos(ph)
    if p.get('back'): z=z+BACK_BEND*u*u
    if p['bend']>0 and not p.get('back'):
        rb=p['bend']*(1+0.15*wig(t,p['s']+10))
        a=np.maximum(x,0)/rb; x,z=np.where(x>0,rb*np.sin(a),x), z+rb*(1-np.cos(a))   # one-sided curl
    x=x+OFF
    pts=np.stack([x,y,z],-1)
    if p.get('back'): return pts*BACK_S+np.array(BACK_OFF)
    Z=np.array([0,0,1.]);Y=np.array([0,1.,0]);X=np.array([1.,0,0])
    if p['tw'][1]>100:   # blade: long axis in the screen plane at a fixed azimuth (+-25 deg drift), small tilt / roll wobble
        az=radians(BLADE_AZ[p['c']]+25*wig(t,p['s']+20)); R=rotAxis(Z,az)@rotAxis(Y,radians(15*wig(t,p['s']+30)))@rotAxis(X,radians(BLADE_ROLL+20*wig(t,p['s']+40)))
        return (pts+np.array([BLADE_OFF,0,0]))@R.T
    # wide petal: spins in the screen plane (group rate), tilt wobble, centre raised to cover the upper hemisphere
    R=rotAxis(Z,radians(W[p['g']])*t+p['e'][2])@rotAxis(X,radians(WIDE_TILT+15*wig(t,p['s']+50)))
    return pts@R.T+np.array([0,WIDE_UP,0])
    return pts@R.T
def project(pts,S):
    s=CAM/(CAM-pts[...,2]); Rp=0.45*S
    return np.stack([S/2+pts[...,0]*s*Rp, S/2-pts[...,1]*s*Rp],-1)
def raster(P2,val,S,out,mode='screen'):
    # P2: (NR,NT,2), val: (NR,NT,3) premult colour; triangles over grid (wrap in theta)
    nr,nt=P2.shape[:2]
    tris=[]
    for i in range(nr-1):
        for j in range(nt):
            j2=(j+1)%nt
            tris.append(((i,j),(i+1,j),(i+1,j2))); tris.append(((i,j),(i+1,j2),(i,j2)))
    for tri in tris:
        q=np.array([P2[a] for a in tri]); c=np.array([val[a] for a in tri])
        x0,y0=np.floor(q.min(0)).astype(int); x1,y1=np.ceil(q.max(0)).astype(int)
        x0=max(x0,0);y0=max(y0,0);x1=min(x1,S-1);y1=min(y1,S-1)
        if x1<x0 or y1<y0: continue
        yy,xx=np.mgrid[y0:y1+1,x0:x1+1]+0.5
        (ax,ay),(bx,by),(cx,cy)=q
        d=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
        if abs(d)<1e-9: continue
        l1=((by-cy)*(xx-cx)+(cx-bx)*(yy-cy))/d; l2=((cy-ay)*(xx-cx)+(ax-cx)*(yy-cy))/d; l3=1-l1-l2
        m=(l1>=0)&(l2>=0)&(l3>=0)
        if not m.any(): continue
        col=l1[...,None]*c[0]+l2[...,None]*c[1]+l3[...,None]*c[2]
        reg=out[y0:y1+1,x0:x1+1]
        if mode=='screen': new=reg+col-reg*col
        else: new=np.maximum(reg,col)
        reg[m]=new[m]
from scipy.ndimage import gaussian_filter
lin=lambda v:(v/255.0)**2.2
CFG=dict(k_thick=2.0,expo=1.6,core=1.0,core_r=0.20,glow=[0.05,0.04,0.03,0.025,0.02,0.015],glow_thr=0.25,rim=0.25,op=0.78)
def petal_layer(t,S,only=None,flat=False):
    out=np.zeros((S,S,3))
    for k,p in enumerate(P):
        if only is not None and k!=only: continue
        pts=petal_mesh(p,t)
        du=np.gradient(pts,axis=0); dv=np.gradient(pts,axis=1)
        n=np.cross(du,dv); nn=np.linalg.norm(n,axis=-1,keepdims=True); n=n/np.maximum(nn,1e-9)
        nz=np.abs(n[...,2]); nz[0]=1
        thick=1+CFG['k_thick']*(1-nz)**2
        d=CAM-pts[...,2]; fog=1-np.clip((d-CAM)/(2.17*1.3),0,1)   # AE fog start 2700 = camera distance, range 1000 = 2.17 R
        fw=0.03 if p['tw'][1]>100 else 0.08
        uu=RH*np.cos(TH); sax=np.abs(RH*np.sin(TH))/np.maximum(np.sqrt(np.maximum(1-uu*uu,0)),1e-6); sax=np.clip(sax,0,1)
        wu=np.where(uu>0,np.maximum(1-uu*uu,0)**0.2,1.0)*np.sqrt(np.maximum(1-uu*uu,0)); sax=np.clip(np.abs(RH*np.sin(TH))/np.maximum(np.sqrt(np.maximum(1-uu*uu,0)),1e-6),0,1)
        def sm(a,b,v): v=np.clip((v-a)/(b-a),0,1); return v*v*(3-2*v)
        band=np.where(sax<0.8,0.2+0.8*sm(0,0.8,sax),1-sm(0.8,1.0,sax))
        a=band*(1-np.clip((RH-(1-fw))/fw,0,1)**2)*(1.0 if p['tw'][1]>100 else WIDE_A); a[0]=0.2*(1.0 if p['tw'][1]>100 else WIDE_A)
        if p.get('back'): a=(1-sm(0,1,RH))*WIDE_A
        c=np.array([lin(255*x) for x in COL[p['c']]])*CFG.get('gain',{}).get(p['c'],1.0)
        c=c*(BACK_G if p.get('back') else 1.0)
        lc=c@np.array([0.2126,0.7152,0.0722]); c=lc+SAT*(c-lc)
        if flat: val=np.where((a>0.02)[...,None],1.0,0.0)*np.ones(3)
        else: val=np.clip(c[None,None]*(a*CFG['op']*fog*thick*CFG['expo'])[...,None],0,1)
        raster(project(pts,S),val,S,out,'max' if flat else 'screen')
    return out
def frame(t,S):
    yy,xx=np.mgrid[:S,:S]+0.5; R=0.45*S; x=np.hypot(xx-S/2,yy-S/2)/R
    e=np.array([0.00168,0.00141,0.00370])
    def ss(a,b,v): v=np.clip((v-a)/(b-a),0,1); return v*v*(3-2*v)
    bg=(e[None,None]*ss(0,0.9,x)[...,None])*(1-ss(0.9,1,x))[...,None]*(x<0.985)[...,None]
    L=petal_layer(t,S)*(1-ss(CFG.get('fade0',0.90),CFG.get('fade1',0.97),x))[...,None]
    img=bg+L-bg*L
    # additive white core
    img+=CFG['core']*np.exp(-(x/CFG['core_r'])**2*3)[...,None]*np.array([0.85,0.97,1.0])
    # multi-level glow on the hot part of the scene
    hot=np.maximum(img-CFG['glow_thr'],0)
    sc=R/461.0
    for rad,w in zip([30,57,80,111,150,200],CFG['glow']): img+=w*gaussian_filter(hot,sigma=(rad*sc/2,rad*sc/2,0))
    # glass rim light: red upper-left -> blue lower-right, band 0.97..1.0 R
    tt=np.clip(((xx-S/2)*0.7071+(yy-S/2)*0.7071)/(x*R+1e-6)*0.5+0.5,0,1)
    rc=(1-tt)[...,None]*np.array([1.0,0.231,0.361])+tt[...,None]*np.array([0.231,0.482,1.0])
    band=np.exp(-0.5*((x-0.975)/CFG.get('rim_s',0.0065))**2)   # 0.03R band blurred by ~0.02R
    img+=CFG['rim']*band[...,None]*rc
    img*=(x<1.0)[...,None]
    return np.clip(img,0,1)**(1/2.2)
def load_best(path='/workspace/designer/ae_v16/tools/best.json'):
    import json; global AX,OFF
    c=json.load(open(path))['cand']
    for k,p in enumerate(P): p['e']=tuple(c['e'][k])
    AX=[np.array(a) for a in c['ax']]; OFF=c['off']
