import sys,numpy as np; sys.path.insert(0,'/workspace/designer/ae_v19/tools'); import stats as S
A=[];B=[]
print("t(s) | ours dark red blue cyan white | ref dark red blue cyan white")
for i in range(24):
    o=S.cls(S.load(f'/tmp/v19o/s{i:03d}.png')); r=S.cls(S.load(f'/tmp/ae_v5_work/ref/r{i*15:03d}.png')); A.append(o); B.append(r)
    flag=" <8%" if min(o["red"],o["blue"],o["cyan"])<8 else ""
    print(f"{i*0.5:4.1f} |"," ".join(f"{v:5.1f}" for v in o.values()),"|"," ".join(f"{v:5.1f}" for v in r.values()),flag+(" <-- still" if i in (1,5,9) else ""))
for nm,L in (("ours",A),("ref",B)):
    print("MEAN",nm," ".join(f"{q}={np.mean([x[q] for x in L]):.1f}[{min(x[q] for x in L):.1f}-{max(x[q] for x in L):.1f}]" for q in L[0]))
print("frames with any colour <8%:",sum(min(o['red'],o['blue'],o['cyan'])<8 for o in A),"/24")
