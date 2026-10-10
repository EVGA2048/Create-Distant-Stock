from pathlib import Path
from PIL import Image, ImageColor

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)

# Copper midtones sampled from Create's fluid tank. Native 16-pixel grid.
palette = {
    '.': (0, 0, 0, 0),
    'o': '#603c30', 'd': '#904931', 's': '#a75a40',
    'm': '#c26b4c', 'h': '#d67b5b', 'w': '#e3826c',
    'b': '#39342c', 'i': '#67665c', 'j': '#a4a594',
    'v': '#706f61',
    'a': (190, 192, 178, 24),
    'e': (235, 237, 221, 200),
    'f': (220, 224, 208, 95),
}
rows = [
    '................',
    '......bjjb......',
    '.....ohiid......',
    '...ohwwhhmso....',
    '..ohwhhmmmsso...',
    '..ohhmmsssdsdo..',
    '..osveaaaavmdo..',
    '..omvefaaavmdo..',
    '..omvafaaavmdo..',
    '..omvaaafavmdo..',
    '..omvaaafevmdo..',
    '..osvaaaaavmdo..',
    '..ohhmmsssdsdo..',
    '...ohhmmmsddo...',
    '....odsssdoo....',
    '................',
]
assert len(rows) == 16 and all(len(row) == 16 for row in rows)
sprite = Image.new('RGBA', (16, 16))
mask = Image.new('RGBA', (16, 16))
for y, row in enumerate(rows):
    for x, symbol in enumerate(row):
        color = palette[symbol]
        sprite.putpixel((x, y), ImageColor.getcolor(color, 'RGBA') if isinstance(color, str) else color)
        if symbol in 'aef':
            mask.putpixel((x, y), (255, 255, 255, 255))
sprite.save(OUT / 'portable_fluid_tank.png')
mask.save(OUT / 'portable_fluid_tank_fluid_mask.png')
for name, bg in [('preview', '#deded4'), ('preview_dark', '#30312e')]:
    canvas = Image.new('RGBA', (384, 384), bg)
    canvas.alpha_composite(sprite.resize((320, 320), Image.Resampling.NEAREST), (32, 32))
    canvas.convert('RGB').save(ROOT / f'{name}.png')
assert sprite.size == mask.size == (16, 16)
assert sum(p[3] > 0 for p in mask.getdata()) == 30
print(OUT / 'portable_fluid_tank.png')
