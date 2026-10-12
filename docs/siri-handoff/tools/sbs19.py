from PIL import Image
for i,s in ((1,'0.5'),(5,'2.5'),(9,'4.5')):
    o=Image.open(f'/tmp/v19o/s{i:03d}.png').convert('RGB'); r=Image.open(f'/tmp/ae_v5_work/ref/r{i*15:03d}.png').convert('RGB').resize(o.size)
    c=Image.new('RGB',(o.width*2,o.height)); c.paste(o,(0,0)); c.paste(r,(o.width,0)); c.save(f'/workspace/designer/ae_v19/ae_v19_sbs_{s}s.png')
