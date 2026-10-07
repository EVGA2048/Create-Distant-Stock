from pathlib import Path
import sys,json,colorsys
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
SOURCE=OUT.parent/'yellow-remote-package'
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model,render
def honey(image):
 image=image.convert('RGBA'); pixels=[]
 for r,g,b,a in image.getdata():
  if a and b>r and g>r:
   h,l,s=colorsys.rgb_to_hls(r/255,g/255,b/255)
   r,g,b=[round(v*255) for v in colorsys.hls_to_rgb(.105,min(.94,l+.025),min(.74,s*1.9))]
  pixels.append((r,g,b,a))
 result=Image.new('RGBA',image.size);result.putdata(pixels)
 assert result.getchannel('A').tobytes()==image.getchannel('A').tobytes()
 return result
original=OUT.parent/'orange-remote-package'
base=honey(Image.open(original/'remote_package_original.png'))
# Half-unit pixel grid for a crisp printed symbol; preserve original box texels.
im=base.resize((128,128),Image.Resampling.NEAREST)
d=ImageDraw.Draw(im)
for y0 in (1,13,25):
 top,bottom=y0*2+9,y0*2+17
 # Flat warm ink, with sparse faded texels rather than a metallic bevel.
 d.line([(79,top+1),(85,top+1),(85,bottom+1),(89,bottom+1)],fill='#736049',width=1)
 d.line([(79,top),(84,top),(84,bottom),(89,bottom)],fill='#736049',width=1)
 d.point((82,top),fill='#947851')
 d.point((84,top+5),fill='#8b7352')
 d.point((87,bottom),fill='#8b7352')
 for x,y in ((79,top),(89,bottom)):
  # Five hard-edged pixels form a compact cross with a warm white centre.
  d.point((x,y-1),fill='#efe1c0')
  d.point((x-1,y),fill='#e9d8b3')
  d.point((x,y),fill='#efe1c0')
  d.point((x+1,y),fill='#e4d2ad')
  d.point((x,y+1),fill='#d7c49d')
im.save(OUT/'remote_package_ping.png')
honey(Image.open(original/'remote_package_particle_original.png')).save(OUT/'remote_package_particle.png')
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
(OUT/'README.md').write_text('蜂蜜黄 ping 包配色概念。原包裹青蓝色映射为暖蜂蜜黄，保留原像素层次与透明度；封箱带保持较浅麦色。侧面为无背景深棕灰油墨折线与象牙白十字端点，少量褪色像素，无金属高光边。保持上一版图标尺寸、模型与 UV，原黄色方案另存于 yellow-ping-package。仅概念稿，未修改正式游戏资源。\n')
print(OUT/'preview.png')
