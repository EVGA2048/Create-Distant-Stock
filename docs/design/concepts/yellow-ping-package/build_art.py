from pathlib import Path
import sys,json,shutil
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
SOURCE=OUT.parent/'yellow-remote-package'
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model,render
base=Image.open(SOURCE/'remote_package_yellow.png').convert('RGBA')
# Half-unit pixel grid for a crisp printed symbol; preserve original box texels.
im=base.resize((128,128),Image.Resampling.NEAREST)
d=ImageDraw.Draw(im)
for y0 in (1,13,25):
 top,bottom=y0*2+9,y0*2+17
 # Paired grey values give the narrow printed track the same stepped
 # highlight/shadow language as Create's industrial texture details.
 d.line([(79,top+1),(85,top+1),(85,bottom+1),(89,bottom+1)],fill='#74786d',width=1)
 d.line([(79,top),(84,top),(84,bottom),(89,bottom)],fill='#bfc0ad',width=1)
 d.point((82,top),fill='#a3a693')
 d.point((84,top+5),fill='#a5a895')
 d.point((87,bottom),fill='#a5a895')
 for x,y in ((79,top),(89,bottom)):
  # Five hard-edged pixels form a compact cross with a warm white centre.
  d.point((x,y-1),fill='#fff9e6')
  d.point((x-1,y),fill='#f7f0db')
  d.point((x,y),fill='#fff9e6')
  d.point((x+1,y),fill='#e8e4cf')
  d.point((x,y+1),fill='#c9c5b0')
im.save(OUT/'remote_package_ping.png')
shutil.copy2(SOURCE/'remote_package_particle_yellow.png',OUT/'remote_package_particle.png')
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
(OUT/'README.md').write_text('黄色 ping 包诊断图标：无背景，暖白十字端点、灰色折线，中间垂直。端点有奶白高光、暖灰底缘；连线为灰色高光与暗边，局部降色模拟少量印刷磨损。标志约 6.5×5.5 模型单位，连线含暗边宽约 1，端点为 1.5×1.5 范围内的五像素十字。使用 128×128 图集及硬边像素，原箱体以最近邻放大，外观保持不变。四个侧面共用三个 UV 区域；不改变模型、顶底面或正式游戏资源。\n')
print(OUT/'preview.png')
