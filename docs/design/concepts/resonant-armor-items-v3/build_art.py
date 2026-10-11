"""Add folded quartz elytra behind the approved opaque chestplate icon."""
from pathlib import Path
from PIL import Image, ImageDraw
import zipfile

HERE = Path(__file__).resolve().parent
BASE = HERE.parent / 'resonant-armor-items-v2/assets/distantstock/textures/item'
OUT = HERE / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)
KINDS = ('helmet', 'chestplate', 'leggings', 'boots')
icons = []
for kind in KINDS:
    im = Image.open(BASE / f'ether_casing_{kind}.png').convert('RGBA')
    if kind == 'chestplate':
        wings = Image.new('RGBA', (16,16))
        d = ImageDraw.Draw(wings)
        # Paired tapered membranes, continuous faces with a leading-edge glint.
        left = [(1,4),(4,6),(4,13),(3,15),(2,14),(1,11),(0,8),(0,5)]
        d.polygon(left, fill='#35536e')
        d.polygon([(1,5),(3,7),(3,13),(2,12),(1,9)], fill='#8dbbd6')
        d.line([(1,5),(1,8),(2,11),(3,14)], fill='#d9ebee', width=1)
        for y in range(16):
            for x in range(8):
                if wings.getpixel((x,y))[3]:
                    wings.putpixel((15-x,y), wings.getpixel((x,y)))
        wings.alpha_composite(im)
        im = wings
    im.save(OUT / f'ether_casing_{kind}.png')
    icons.append(im)

for name,bg in [('preview','#deded4'),('preview_dark','#343d43')]:
    sheet = Image.new('RGBA',(896,256),bg)
    for i,im in enumerate(icons):
        sheet.alpha_composite(im.resize((224,224),Image.Resampling.NEAREST),(224*i,16))
    sheet.convert('RGB').save(HERE/f'{name}.png')
chest = Image.new('RGBA',(384,384),'#343d43')
chest.alpha_composite(icons[1].resize((320,320),Image.Resampling.NEAREST),(32,32))
chest.convert('RGB').save(HERE/'chestplate_preview.png')
with zipfile.ZipFile(HERE/'resonant_armor_item_textures.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(OUT.glob('*.png')):
        archive.write(path,path.relative_to(HERE))
assert all(im.size == (16,16) and set(im.getchannel('A').getdata()) <= {0,255} for im in icons)
print(OUT)
