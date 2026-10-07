from pathlib import Path
import sys,json,hashlib
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model,render
refs=CreateReferences()
TARGET=OUT/'assets/distantstock/textures/block/frogport';TARGET.mkdir(parents=True,exist_ok=True)
REFERENCE=OUT/'reference';REFERENCE.mkdir(exist_ok=True)
# Only blue/violet painted steel receives a replacement palette. Bare metal,
# eyes, wood, mouth, tongue and existing indicator keep the Create source pixels.
keys=[(37,37,43),(43,43,49),(53,52,61),(61,60,72),(65,65,80),(68,72,90),(70,78,97),(73,86,105),(75,94,113),(86,107,129)]
palettes={
 'diagnostic':[(66,43,28),(80,51,29),(107,65,29),(130,78,30),(153,93,31),(174,108,33),(190,124,39),(206,141,49),(219,156,61),(236,181,86)],
 'cache':[(31,45,33),(37,54,38),(44,66,43),(51,79,48),(60,93,55),(70,108,62),(81,121,70),(95,137,80),(110,151,91),(139,177,116)]}
original={name:refs.texture('create:block/'+name) for name in ('port','port2')}
for name,im in original.items():im.save(REFERENCE/(name+'.png'))
report={}
for kind,palette in palettes.items():
 lut=dict(zip(keys,palette))
 for name,im in original.items():
  out=Image.new('RGBA',im.size)
  out.putdata([(*lut.get((r,g,b),(r,g,b)),a) if a else (r,g,b,a) for r,g,b,a in im.getdata()])
  assert out.size==(32,32)
  assert out.getchannel('A').tobytes()==im.getchannel('A').tobytes()
  for before,after in zip(im.getdata(),out.getdata()):
   if before[:3] not in lut:assert before==after
  path=TARGET/f'{kind}_{name}.png';out.save(path)
  report[path.name]={'size':list(out.size),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
# Preview uses the installed Create item geometry. Inverted duplicate elements
# are interior backfaces; skip them only in this offline exterior renderer.
model=refs.model('create:block/package_frogport/item')
model['elements']=[e for e in model['elements'] if all(b>a for a,b in zip(e['from'],e['to']))]
for kind in palettes:
 def texture(name):
  tail=name.split('/')[-1]
  if name in ('create:block/port','create:block/port2'):
   return Image.open(TARGET/f'{kind}_{tail}.png').convert('RGBA')
  return refs.texture(name)
 mesh,tex=load_minecraft_model(model,texture)
 pic=render(mesh,tex,size=(480,480),yaw=-32,pitch=22,center=(8,8,8),scale=18).transpose(Image.Transpose.FLIP_LEFT_RIGHT)
 pic.save(OUT/f'{kind}_preview.png')
 render(mesh,tex,size=(480,480),yaw=0,pitch=8,center=(8,8,8),scale=18).transpose(Image.Transpose.FLIP_LEFT_RIGHT).save(OUT/f'{kind}_front.png')
sheet=Image.new('RGBA',(960,480),'#e9e5da')
for i,kind in enumerate(palettes):sheet.alpha_composite(Image.open(OUT/f'{kind}_preview.png'),(i*480,0))
sheet.save(OUT/'preview.png')
(OUT/'checks.json').write_text(json.dumps(report,indent=2)+'\n')
(OUT/'README.md').write_text('蛙港配色贴图概念 V1\n\n诊断蛙港：橙黄烤漆。缓存蛙港：草绿色烤漆。四张 32×32 RGBA 贴图与现有模型命名兼容，位于 assets/distantstock/textures/block/frogport。\n\n来源为本机 Create 6.0.10 的 port.png / port2.png。仅替换蓝紫外壳色阶；保留金属包边、眼睛、口腔、舌头、木质底座和绿色指示灯。贴图尺寸、透明度和像素布局不变，按 Create 原始资源许可处理。\n\n预览使用 Create 物品模型，离线渲染中省略反向内部重复面；实际模型未改。没有覆盖现有贴图，也未改代码或注册。build_art.py 可复现。\n')
print(OUT/'preview.png')
