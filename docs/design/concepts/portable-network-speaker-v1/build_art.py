from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'assets/distantstock/textures/item'
OUT.mkdir(parents=True, exist_ok=True)

# Compact receiver: andesite metal, aged brass rim, amber tuning window.
C = {
    'outline': '#302e28', 'brass_shadow': '#76462d',
    'brass': '#b57849', 'brass_light': '#d3a155', 'brass_high': '#ffeb94',
    'body_dark': '#505351', 'body': '#60635f', 'body_light': '#828784',
    'metal_high': '#9aa49d', 'slot': '#292e2d', 'glass': '#654e32',
    'amber': '#c18e49', 'needle': '#fff9c7', 'green': '#bbd5a1',
}
im = Image.new('RGBA', (16, 16))
d = ImageDraw.Draw(im)
def rect(box, color):
    d.rectangle(box, fill=C[color])
def point(x, y, color):
    d.point((x, y), fill=C[color])

# Short aerial and knurled top control, kept inside the inventory tile.
rect((4, 1, 4, 3), 'body_light')
point(4, 1, 'metal_high')
rect((10, 2, 11, 3), 'outline')
point(10, 2, 'metal_high')
d.polygon([(3,3),(12,3),(13,4),(13,12),(12,13),(3,13),(2,12),(2,4)], fill=C['outline'])
rect((3, 4, 12, 12), 'brass_shadow')
rect((3, 4, 11, 11), 'brass')
rect((3, 4, 11, 4), 'brass_light')
rect((3, 5, 3, 11), 'brass_light')
point(3, 4, 'brass_high')
rect((4, 5, 11, 11), 'body_dark')
rect((4, 5, 10, 10), 'body')

# Small analog dial above the grille rather than a modern digital display.
rect((4, 5, 9, 6), 'glass')
rect((5, 5, 8, 5), 'amber')
point(7, 5, 'needle')
point(7, 6, 'brass_light')
point(11, 5, 'green')
point(11, 6, 'outline')

# Three horizontal stamped grille slots and one recessed tuning wheel.
for y in (8, 10):
    rect((4, y-1, 9, y-1), 'body_light')
    rect((4, y, 9, y), 'slot')
rect((4, 11, 9, 11), 'slot')
rect((10, 8, 11, 10), 'outline')
point(11, 8, 'metal_high')
point(11, 9, 'body_light')
point(11, 10, 'body_dark')
rect((4, 12, 10, 12), 'brass')
point(3, 12, 'brass_high')
point(11, 12, 'brass_light')
im.save(OUT / 'portable_network_speaker.png')
for name, bg in [('preview', '#deded4'), ('preview_dark', '#30312e')]:
    canvas = Image.new('RGBA', (384, 384), bg)
    canvas.alpha_composite(im.resize((320,320), Image.Resampling.NEAREST), (32,32))
    canvas.convert('RGB').save(ROOT / f'{name}.png')
assert im.size == (16,16) and im.getpixel((0,0))[3] == 0
print(OUT / 'portable_network_speaker.png')
