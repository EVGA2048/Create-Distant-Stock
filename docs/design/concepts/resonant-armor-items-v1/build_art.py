"""Inventory studies for the current polished-quartz armour; no production edits."""
from pathlib import Path
from PIL import Image, ImageColor, ImageDraw
import io
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
OUT = HERE / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)
PALETTE = ['#35536e', '#507b9b', '#699dbc', '#8dbbd6',
           '#b6d8e8', '#d9ebee', '#f3f6e9', '#ffffff']
RGB = [ImageColor.getrgb(c) for c in PALETTE]
KINDS = ('helmet', 'chestplate', 'leggings', 'boots')

def draw_item(mask, kind):
    cells = {(x, y) for y in range(16) for x in range(16) if mask.getpixel((x, y))[3]}
    out = Image.new('RGBA', (16, 16))
    for x, y in cells:
        top = (x, y-1) not in cells
        left = (x-1, y) not in cells
        right = (x+1, y) not in cells
        bottom = (x, y+1) not in cells
        if top:
            tone, alpha = 6 if x < 10 else 5, 255
        elif left:
            tone, alpha = 5, 245
        elif right or bottom:
            tone, alpha = (2 if right else 3), 240
        else:
            # Broad clear face; short diagonal reflection, no random sparkles.
            tone, alpha = 4, 170
            if (x+2, y) not in cells:
                tone, alpha = 3, 210
            if (x, y-2) not in cells:
                tone, alpha = 3, 220
            if 7 <= x+y <= 9:
                tone, alpha = 5, 225
            if x+y == 7:
                tone, alpha = 6, 245
        out.putpixel((x, y), (*RGB[tone], alpha))

    def pixel(x, y, tone, alpha=255):
        if (x, y) in cells:
            out.putpixel((x, y), (*RGB[tone], alpha))
    def line(x0, x1, y, tone, alpha=255):
        for x in range(x0, x1+1):
            pixel(x, y, tone, alpha)

    # Ground rims and plate boundaries follow the actual basic armour shapes.
    if kind == 'helmet':
        line(4, 10, 4, 7)
        line(5, 10, 5, 3, 230)
        for x, y in [(4,6),(4,7),(5,6)]:
            pixel(x,y,6,245)
        line(5, 9, 10, 5, 220)
    elif kind == 'chestplate':
        line(2,4,4,5)
        line(11,13,4,4)
        for x,y in [(5,6),(6,7),(7,8),(8,8),(9,7),(10,6)]:
            pixel(x,y,5,235)
        line(5,10,12,5,235)
        line(6,9,13,3,240)
    elif kind == 'leggings':
        line(4,11,3,7)
        line(4,11,4,3,230)
        for y in (7,8,9,10):
            pixel(5,y,5,215)
            pixel(10,y,4,200)
        line(4,6,12,5,225)
        line(9,11,12,4,225)
    else:
        line(4,6,4,7)
        line(10,12,4,6)
        line(4,6,5,3,230)
        line(10,12,5,3,230)
        line(2,5,11,6,240)
        line(11,14,11,5,235)
        line(2,5,12,4,225)
        line(11,14,12,3,225)
    assert {(x,y) for y in range(16) for x in range(16) if out.getpixel((x,y))[3]} == cells
    return out

icons = []
with zipfile.ZipFile(REPO / 'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar') as jar:
    for kind in KINDS:
        mask = Image.open(io.BytesIO(jar.read(f'assets/minecraft/textures/item/diamond_{kind}.png'))).convert('RGBA')
        icon = draw_item(mask, kind)
        icon.save(OUT / f'ether_casing_{kind}.png')
        icons.append(icon)

for filename, bg in [('preview', '#deded4'), ('preview_dark', '#343d43')]:
    sheet = Image.new('RGBA', (896, 256), bg)
    for i, icon in enumerate(icons):
        sheet.alpha_composite(icon.resize((224,224), Image.Resampling.NEAREST), (i*224,16))
    sheet.convert('RGB').save(HERE / f'{filename}.png')

# Readability check against the inventory's neutral slot colour at 2x scale.
slots = Image.new('RGBA', (192,48), '#c6c6c6')
for i, icon in enumerate(icons):
    ImageDraw.Draw(slots).rectangle((i*48+5,5,i*48+42,42), fill='#8b8b8b', outline='#373737')
    slots.alpha_composite(icon.resize((32,32),Image.Resampling.NEAREST),(i*48+8,8))
slots.convert('RGB').save(HERE / 'inventory_scale.png')
print(OUT)
