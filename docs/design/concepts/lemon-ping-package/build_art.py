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
# North and south share only this UV island: exactly two opposite faces.
y=64
ink='#596365'
d.line((79,y,89,y),fill=ink,width=2)
for x in (79,89):
 d.rectangle((x-2,y-2,x+2,y+2),fill=ink)
 d.rectangle((x-1,y-1,x+1,y+1),fill='#f4eedb')
 d.point((x-1,y-1),fill='#fff8e6')
 d.point((x+1,y+1),fill='#d8d1bd')
# Only the north/south island differs from the unmarked recolour.
plain=base.resize((128,128),Image.Resampling.NEAREST)
for py in range(128):
 for px in range(128):
  if im.getpixel((px,py)) != plain.getpixel((px,py)):
   assert 72 <= px < 96 and 50 <= py < 74
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
im.crop((72,50,96,74)).resize((288,288),Image.Resampling.NEAREST).save(OUT/'side_pixels.png')
(OUT/'README.md').write_text('柠檬黄诊断包概念：提高箱体明黄饱和度，区别于原版棕色纸箱。只在南北两个相对侧面绘制节点连接符号，东西面不印图标。图标为两个浅白方形节点、深灰描边与直线连接，无背景、无折线、无十字端点。保留原有箱体像素细节、尺寸和 UV。仅概念稿，未改正式资源；此前配色仍保留。\n')
print(OUT/'preview.png')
