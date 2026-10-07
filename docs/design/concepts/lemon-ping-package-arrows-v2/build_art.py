from pathlib import Path
import sys,json,colorsys
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
SOURCE=OUT.parent/'yellow-remote-package'
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model,render
def lemon(image):
 image=image.convert('RGBA'); pixels=[]
 for r,g,b,a in image.getdata():
  if a and b>r and g>r:
   h,l,s=colorsys.rgb_to_hls(r/255,g/255,b/255)
   r,g,b=[round(v*255) for v in colorsys.hls_to_rgb(.148,min(.94,l+.055),min(.88,s*2.65))]
  pixels.append((r,g,b,a))
 result=Image.new('RGBA',image.size);result.putdata(pixels)
 assert result.getchannel('A').tobytes()==image.getchannel('A').tobytes()
 return result
original=OUT.parent/'orange-remote-package'
base=lemon(Image.open(original/'remote_package_original.png'))
# Half-unit pixel grid for a crisp printed symbol; preserve original box texels.
im=base.resize((128,128),Image.Resampling.NEAREST)
d=ImageDraw.Draw(im)
# Only east/west side islands carry the mark; taped north/south stay clean.
# Matching stepped silhouettes, rotated 180 degrees for the return arrow.
arrow=['.......#...',
       '.......##..',
       '##########.',
       '###########',
       '##########.',
       '.......##..',
       '.......#...']
for y0 in (1,13):
 for ox,oy,rows,color in (
  (78,y0*2+6,arrow,'#59615b'),
  (80,y0*2+15,[r[::-1] for r in arrow[::-1]],'#f1e8cf')):
  for iy,row in enumerate(rows):
   for ix,value in enumerate(row):
    if value=='#':d.point((ox+ix,oy+iy),fill=color)
plain=base.resize((128,128),Image.Resampling.NEAREST)
for py in range(128):
 for px in range(128):
  if im.getpixel((px,py)) != plain.getpixel((px,py)):
   assert 72 <= px < 96 and (2 <= py < 26 or 26 <= py < 50)
im.save(OUT/'remote_package_ping.png')
lemon(Image.open(original/'remote_package_particle_original.png')).save(OUT/'remote_package_particle.png')
refs=CreateReferences()
assets=ROOT/'src/main/resources/assets/distantstock'
model=json.loads((assets/'models/item/remote_package_12x12.json').read_text())
def texture(name):
 ns,path=name.split(':',1)
 if ns=='distantstock':return im if path.endswith('/remote_package') else Image.open(OUT/'remote_package_particle.png').convert('RGBA')
 return refs.texture(name)
mesh,tex=load_minecraft_model(model,texture,refs.model)
render(mesh,tex,size=(480,480),yaw=-32,pitch=24,center=(8,6,8),scale=24).transpose(Image.Transpose.FLIP_LEFT_RIGHT).save(OUT/'preview.png')
im.crop((72,2,96,26)).resize((288,288),Image.Resampling.NEAREST).save(OUT/'side_pixels.png')
(OUT/'README.md').write_text('柠檬黄诊断包往返箭头方案：两个错开的配对短箭头，上方深灰向右、下方暖白向左，箭头轮廓为对称阶梯像素，无背景、无描边和端点。只印在东西两个无胶带侧面，保持现有箱体颜色、模型与 UV。使用半模型单位的硬边像素网格，原有箱体纹素不变。此前图标另存，未修改正式游戏资源。\n')
print(OUT/'preview.png')
