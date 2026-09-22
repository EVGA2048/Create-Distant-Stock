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
for y0 in (1,13):
 top,bottom=y0*2+9,y0*2+17
 # Paired grey values give the narrow printed track the same stepped
 # highlight/shadow language as Create's industrial texture details.
 d.line([(79,top+1),(85,top+1),(85,bottom+1),(89,bottom+1)],fill='#74786d',width=1)
 d.line([(79,top),(84,top),(84,bottom),(89,bottom)],fill='#bfc0ad',width=1)
 d.point((82,top),fill='#a3a693')
 d.point((84,top+5),fill='#a5a895')
 d.point((87,bottom),fill='#a5a895')
 for x,y in ((79,top),(89,bottom)):
  # Original cross terminals; change only the lower-right terminal colour.
  if y==bottom:
   palette=('#d4eff5','#c3e5ee','#daf3f8','#add5e3','#88b4c7')
  else:
   palette=('#fff9e6','#f7f0db','#fff9e6','#e8e4cf','#c9c5b0')
  for point,color in zip(((x,y-1),(x-1,y),(x,y),(x+1,y),(x,y+1)),palette):
   d.point(point,fill=color)
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
(OUT/'README.md').write_text('恢复 lemon-ping-package-side 的线条、明暗、图标大小和位置，仅将右下十字端点换成淡蓝色，左上保持暖白色。保留柠檬黄箱体，仅两个无胶带侧面带图标。旧版保留，未修改正式游戏资源。\n')
print(OUT/'preview.png')
