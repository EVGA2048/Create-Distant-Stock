#!/usr/bin/env python3
"""2D resonant canister item concepts.

Deliberately an item texture, not a block/model render.  The palettes are sampled from the
installed Create 6.0.10 item textures and the two Distant Stock fluid textures so the result sits
next to Create items rather than reading as a generic tech-mod prop.
"""
from pathlib import Path
import io
import zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
CREATE = Path.home() / 'Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar'
ASSETS = ROOT / 'src/main/resources/assets/distantstock'
OUT = HERE / 'renders2d'
OUT.mkdir(exist_ok=True)


def sample_create(path):
    with zipfile.ZipFile(CREATE) as z:
        return Image.open(io.BytesIO(z.read('assets/create/textures/' + path))).convert('RGBA')


def palette(im, count=8):
    # Ignore alpha and the rarest anti-alias pixels; Create item art is already hard pixel art.
    colors = im.convert('RGB').getcolors(im.width * im.height) or []
    colors.sort(reverse=True)
    vals = [c for n, c in colors if n >= 2]
    vals.sort(key=lambda c: sum(c))
    if len(vals) < count:
        vals += [(128, 128, 128)] * (count - len(vals))
    indexes = [round(i * (len(vals) - 1) / (count - 1)) for i in range(count)]
    return [vals[i] + (255,) for i in indexes]


AND = palette(sample_create('item/andesite_alloy.png'), 7)
BRASS = palette(sample_create('item/brass_sheet.png'), 7)
ETHER = palette(Image.open(ASSETS / 'textures/fluid/ether_still.png').convert('RGBA'), 7)
AMETHYST = palette(Image.open(ASSETS / 'textures/fluid/molten_amethyst_still.png').convert('RGBA'), 7)


def put_rect(im, box, color):
    ImageDraw.Draw(im).rectangle(box, fill=color)


def canister(fluid=None):
    """32x32 inventory icon: squat pressure cartridge, straight-on with a slight right face."""
    im = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    p = im.load()

    # Silhouette: deliberately squat and mechanical, not bottle-shaped.
    # dark backplate / right-side depth
    put_rect(im, (8, 7, 23, 25), AND[0])
    put_rect(im, (7, 9, 24, 23), AND[0])
    put_rect(im, (10, 5, 21, 26), AND[0])

    # Main andesite shell, with a small shaded right face.
    put_rect(im, (9, 8, 21, 24), AND[4])
    put_rect(im, (10, 7, 20, 24), AND[5])
    put_rect(im, (19, 8, 22, 24), AND[2])
    put_rect(im, (10, 23, 21, 25), AND[1])
    # clipped shoulders
    for xy in [(9, 8), (21, 8), (9, 24), (21, 24)]:
        p[xy] = AND[0]

    # Brass clamp bands: thick enough to survive 16x16-ish GUI scaling.
    put_rect(im, (8, 9, 22, 11), BRASS[2])
    put_rect(im, (9, 9, 20, 9), BRASS[5])
    put_rect(im, (8, 21, 22, 23), BRASS[2])
    put_rect(im, (9, 21, 20, 21), BRASS[5])
    p[22, 10] = BRASS[0]; p[22, 22] = BRASS[0]

    # Top fill coupling: flat Create-ish fitting, not a bottle neck.
    put_rect(im, (12, 4, 18, 6), BRASS[1])
    put_rect(im, (13, 3, 17, 4), BRASS[4])
    put_rect(im, (14, 2, 16, 3), AND[1])
    p[13, 3] = BRASS[2]; p[17, 3] = BRASS[0]

    # Bottom foot / keyed connector.
    put_rect(im, (11, 25, 19, 27), AND[1])
    put_rect(im, (13, 27, 17, 28), BRASS[2])

    # Narrow sight glass, framed in brass. One vertical stripe is enough to read at item scale.
    put_rect(im, (11, 12, 15, 20), BRASS[0])
    put_rect(im, (12, 13, 14, 19), (55, 68, 67, 255))
    # fluid level / empty reflection
    if fluid is None:
        put_rect(im, (13, 14, 13, 18), (132, 154, 150, 255))
        p[14, 13] = (187, 203, 197, 255)
    else:
        fp = ETHER if fluid == 'ether' else AMETHYST
        put_rect(im, (12, 14, 14, 19), fp[4])
        put_rect(im, (12, 14, 14, 15), fp[6])
        p[12, 18] = fp[2]; p[14, 19] = fp[2]

    # Two shell fasteners + a tiny right-side valve tab.
    p[18, 14] = BRASS[4]
    p[18, 18] = BRASS[1]
    put_rect(im, (22, 14, 24, 16), BRASS[1])
    p[24, 15] = BRASS[5]

    # Create-like highlight and deep pixels; no smooth gradients.
    p[10, 12] = AND[6]; p[10, 13] = AND[5]
    p[20, 13] = AND[1]; p[20, 18] = AND[1]
    p[9, 17] = AND[3]; p[21, 17] = AND[0]
    return im


variants = [('empty', None), ('ether', 'ether'), ('amethyst', 'amethyst')]
for name, fluid in variants:
    icon = canister(fluid)
    icon.save(OUT / f'{name}_32.png')
    icon.resize((320, 320), Image.Resampling.NEAREST).save(OUT / f'{name}_preview.png')

# Neutral comparison sheet. The art itself remains transparent.
sheet = Image.new('RGBA', (1120, 440), '#e9e8df')
draw = ImageDraw.Draw(sheet)
font_path = '/System/Library/Fonts/STHeiti Medium.ttc'
font = ImageFont.truetype(font_path, 22)
small = ImageFont.truetype(font_path, 16)
draw.text((30, 22), '谐振介质罐 · 2D Item Texture', font=ImageFont.truetype(font_path, 30), fill='#35413b')
draw.text((32, 62), '32×32 像素物品图标 · Create 安山合金 / 黄铜调色 · 不使用 3D 模型', font=small, fill='#717a6a')
labels = [('空罐', 'empty'), ('以太凝液', 'ether'), ('熔融紫水晶', 'amethyst')]
for i, (label, name) in enumerate(labels):
    x = 38 + i * 360
    prev = Image.open(OUT / f'{name}_preview.png')
    sheet.alpha_composite(prev, (x, 92))
    draw.text((x + 118, 402), label, font=font, fill='#35413b')
sheet.save(HERE / 'item_sheet_2d.png')
print(HERE / 'item_sheet_2d.png')
