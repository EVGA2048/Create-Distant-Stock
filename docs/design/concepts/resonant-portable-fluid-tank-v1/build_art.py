from pathlib import Path
from PIL import Image, ImageColor

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)

# Ether-quartz palette, retaining the approved symmetric tank silhouette.
palette = {
    '.': (0, 0, 0, 0),
    'o': '#35536e', 'd': '#507b9b', 's': '#699dbc',
    'm': '#b6d8e8', 'h': '#d9ebee', 'w': '#f3f6e9',
    'b': '#507b9b', 'i': '#8dbbd6', 'j': '#f3f6e9',
    'v': '#8dbbd6',
    'a': (190, 192, 178, 24),
    'e': (235, 237, 221, 200),
    'f': (220, 224, 208, 95),
}
rows = [
    '................',
    '......bjjb......',
    '.....ohiido.....',
    '....ohwhmsdo....',
    '...ohwhmmssdo...',
    '...owhmmsshdo...',
    '...osveaaavdo...',
    '...omvefaavdo...',
    '...omvafaavdo...',
    '...omvaaafvdo...',
    '...omvaafevdo...',
    '...osvaaaavdo...',
    '...owhmmsshdo...',
    '....ohmmsddo....',
    '.....odssdo.....',
    '................',
]
assert len(rows) == 16 and all(len(row) == 16 for row in rows)
assert all([c != '.' for c in row] == [c != '.' for c in row[::-1]] for row in rows)
assert all([c in 'aef' for c in row] == [c in 'aef' for c in row[::-1]] for row in rows)
sprite = Image.new('RGBA', (16, 16))
mask = Image.new('RGBA', (16, 16))
for y, row in enumerate(rows):
    for x, symbol in enumerate(row):
        color = palette[symbol]
        sprite.putpixel((x, y), ImageColor.getcolor(color, 'RGBA') if isinstance(color, str) else color)
        if symbol in 'aef':
            mask.putpixel((x, y), (255, 255, 255, 255))
sprite.save(OUT / 'resonant_portable_fluid_tank.png')
mask.save(OUT / 'resonant_portable_fluid_tank_fluid_mask.png')
for name, bg in [('preview', '#deded4'), ('preview_dark', '#30312e')]:
    canvas = Image.new('RGBA', (384, 384), bg)
    canvas.alpha_composite(sprite.resize((320, 320), Image.Resampling.NEAREST), (32, 32))
    canvas.convert('RGB').save(ROOT / f'{name}.png')
assert sprite.size == mask.size == (16, 16)
assert sum(p[3] > 0 for p in mask.getdata()) == 24
comparison = Image.new('RGBA', (960, 320), '#deded4')
base = Image.open(ROOT.parent / 'portable-fluid-tank-v4/assets/distantstock/textures/item/portable_fluid_tank.png').convert('RGBA')
reinforced = Image.open(ROOT.parent / 'reinforced-portable-fluid-tank-v1/assets/distantstock/textures/item/reinforced_portable_fluid_tank.png').convert('RGBA')
for x, item in [(0, base), (320, reinforced), (640, sprite)]:
    comparison.alpha_composite(item.resize((288, 288), Image.Resampling.NEAREST), (x + 16, 16))
comparison.convert('RGB').save(ROOT / 'comparison.png')
assert mask.getchannel('A').tobytes() == Image.open(ROOT.parent / 'portable-fluid-tank-v4/assets/distantstock/textures/item/portable_fluid_tank_fluid_mask.png').getchannel('A').tobytes()
print(OUT / 'resonant_portable_fluid_tank.png')
