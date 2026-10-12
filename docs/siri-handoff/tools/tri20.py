import sys; from PIL import Image, ImageDraw
new,dst,tag=sys.argv[1:4]
for i,s in ((1,'0.5'),(5,'2.5'),(9,'4.5')):
    r=Image.open(f'/tmp/ae_v5_work/ref/r{i*15:03d}.png').convert('RGB').resize((870,870))
    a=Image.open(f'/tmp/v12o/s{i:03d}.png').convert('RGB'); b=Image.open(f'{new}/s{i:03d}.png').convert('RGB')
    c=Image.new('RGB',(870*3,870)); [c.paste(x,(870*k,0)) for k,x in enumerate((r,a,b))]
    d=ImageDraw.Draw(c)
    for k,l in enumerate(('reference',  'v12', tag)): d.text((870*k+16,12),f'{l}  t={s}s',fill=(200,200,200),font_size=28)
    c.save(f'{dst}/ae_{tag}_tri_{s}s.png')
