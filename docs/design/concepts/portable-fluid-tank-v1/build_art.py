from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)

# One cell is one native Minecraft item pixel; no smoothing.
palette = {
    '.': (0, 0, 0, 0),
    'o': '#54372c', 'd': '#814a34', 's': '#a66043',
    'm': '#c7815a', 'h': '#e5ad7b', 'w': '#f4d4a0',
    'b': '#43494a', 'c': '#919b96', 'g': '#d8ddd1',
    'v': '#567a85', 'a': '#91b5bd', 'e': '#c4dfe0',
    'l': '#92d8de', 't': '#5faebb', 'r': '#398497',
    'q': '#286274',
}
rows = [
    '................',
    '......bggb......',
    '....oohccdoo....',
    '...ohwwhhmmsd...',
    '..ohhmmmmssmso..',
    '..omssdddddsdo..',
    '..ohveaaaavmdo..',
    '..ohveaaaavmdo..',
    '..omellllltmdo..',
    '..omeltttrrmdo..',
    '..omeltttrqmdo..',
    '..osatrrrrqsdo..',
    '..ohmmmmsssmdo..',
    '...shhhmmssdo...',
    '....odddddoo....',
    '................',
]
assert len(rows) == 16 and all(len(row) == 16 for row in rows)
sprite = Image.new('RGBA', (16, 16))
for y, row in enumerate(rows):
    for x, color in enumerate(row):
        sprite.putpixel((x, y), Image.new('RGBA', (1, 1), palette[color]).getpixel((0, 0)))
sprite.save(OUT / 'portable_fluid_tank.png')

for name, bg in [('preview', '#e1e4dc'), ('preview_dark', '#282f35')]:
    canvas = Image.new('RGBA', (384, 384), bg)
    canvas.alpha_composite(sprite.resize((320, 320), Image.Resampling.NEAREST), (32, 32))
    canvas.convert('RGB').save(ROOT / f'{name}.png')

sprite.resize((512, 512), Image.Resampling.NEAREST).save(ROOT / 'portable_fluid_tank_large.png')
print(OUT / 'portable_fluid_tank.png')
