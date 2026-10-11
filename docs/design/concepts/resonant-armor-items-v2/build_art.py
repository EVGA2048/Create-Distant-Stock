"""Opaque, faceted quartz inventory armour. Standalone concept assets."""
from pathlib import Path
from PIL import Image, ImageColor
import io
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
OUT = HERE / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)
KINDS = ('helmet', 'chestplate', 'leggings', 'boots')
SOURCE = [(8,37,32),(14,63,54),(26,170,167),(32,197,181),
          (74,237,217),(161,251,232),(255,255,255)]
COLORS = ['#35536e','#507b9b','#699dbc','#8dbbd6',
          '#b6d8e8','#f3f6e9','#ffffff']
RAMP = [ImageColor.getrgb(c) for c in COLORS]
PEARL = ImageColor.getrgb('#d9ebee')

def item(source, kind):
    out = Image.new('RGBA', (16,16))
    for y in range(16):
        for x in range(16):
            p = source.getpixel((x,y))
            if not p[3]:
                continue
            rank = SOURCE.index(p[:3])
            rgb = RAMP[rank]
            # Keep the hollow helmet opening and the original limb separation.
            # The broad quartz faces have a few clean facets, not framed panels.
            if rank == 4:
                if kind == 'helmet':
                    rgb = PEARL if x+y < 12 else RAMP[4]
                elif kind == 'chestplate':
                    rgb = PEARL if x+y <= 13 else RAMP[4]
                    if x >= 9 and y >= 9:
                        rgb = RAMP[3]
                elif kind == 'leggings':
                    rgb = PEARL if y <= 5 or x <= 5 else RAMP[4]
                else:
                    rgb = PEARL if x <= 6 or (x >= 10 and y <= 7) else RAMP[4]
            out.putpixel((x,y),(*rgb,255))

    def pixel(x,y,rgb):
        p = source.getpixel((x,y))
        if p[3] and SOURCE.index(p[:3]) >= 2:
            out.putpixel((x,y),(*rgb,255))

    # Sparse cut-surface highlights; each piece has a different reflection.
    if kind == 'helmet':
        for x,y in [(6,4),(7,4),(8,4)]:
            pixel(x,y,RAMP[5])
        pixel(9,5,PEARL)
    elif kind == 'chestplate':
        for x,y in [(5,7),(6,8),(7,9)]:
            pixel(x,y,RAMP[5] if x == 5 else PEARL)
        for x,y in [(9,10),(8,11),(7,12)]:
            pixel(x,y,RAMP[4])
    elif kind == 'leggings':
        for x,y in [(5,6),(5,7),(5,8)]:
            pixel(x,y,RAMP[5] if y == 6 else PEARL)
        pixel(10,9,PEARL)
    else:
        for x,y in [(3,11),(4,11),(11,11),(12,11)]:
            pixel(x,y,PEARL)
    assert out.getchannel('A').tobytes() == source.getchannel('A').tobytes()
    return out

icons = []
with zipfile.ZipFile(REPO/'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar') as jar:
    for kind in KINDS:
        source = Image.open(io.BytesIO(jar.read(f'assets/minecraft/textures/item/diamond_{kind}.png'))).convert('RGBA')
        im = item(source,kind)
        im.save(OUT/f'ether_casing_{kind}.png')
        icons.append(im)

for name,bg in [('preview','#deded4'),('preview_dark','#343d43')]:
    canvas = Image.new('RGBA',(896,256),bg)
    for i,im in enumerate(icons):
        canvas.alpha_composite(im.resize((224,224),Image.Resampling.NEAREST),(i*224,16))
    canvas.convert('RGB').save(HERE/f'{name}.png')
with zipfile.ZipFile(HERE/'resonant_armor_item_textures.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(OUT.glob('*.png')):
        archive.write(path,path.relative_to(HERE))
print(OUT)
