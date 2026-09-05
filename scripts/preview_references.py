"""Build a local material reference sheet; no reference images are shipped."""
import base64
import io
import json
import zipfile
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/art'
OUT.mkdir(parents=True, exist_ok=True)
CREATE = Path.home() / 'Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar'
images = []
for name in ('drone_port', 'drone_port_open'):
    model = json.loads((Path('/private/tmp/Create-Mobile-Packages/models') / (name + '.bbmodel')).read_text())
    for i, texture in enumerate(model['textures']):
        if texture.get('source', '').startswith('data:'):
            im = Image.open(io.BytesIO(base64.b64decode(texture['source'].split(',')[1]))).convert('RGBA')
            im.save(OUT / f'{name}_{i}.png')
            images.append((name, im))
with zipfile.ZipFile(CREATE) as archive:
    names = ['andesite_casing', 'brass_casing', 'packager', 'packager_side', 'packager_top', 'stock_link', 'factory_gauge', 'brass_funnel']
    for name in names:
        path = f'assets/create/textures/block/{name}.png'
        if path in archive.namelist():
            images.append((name, Image.open(io.BytesIO(archive.read(path))).convert('RGBA')))
    print('Reference textures:', [n for n in archive.namelist() if n.startswith('assets/create/textures/block/') and any(s in n for s in ['packager', 'factory_panel', 'factory_gauge'])])
sheet = Image.new('RGB', (960, ((len(images) + 3)//4)*275), '#282c2e')
draw = ImageDraw.Draw(sheet)
for index, (name, im) in enumerate(images):
    x, y = (index % 4)*240, (index//4)*275
    scale = max(1, min(220//im.width, 230//im.height))
    im = im.resize((im.width*scale, im.height*scale), Image.Resampling.NEAREST)
    sheet.paste(im, (x+10, y+25), im)
    draw.text((x+10,y+5), name, fill='white')
sheet.save(OUT / 'references.png')
print(OUT / 'references.png')
