"""Resonant Quartz armour, draft V7 -- the suit rebuilt out of the quartz it is named for.

V5 read the suit as *casing blocks*: flat panels, one rim, one bevel, one small window. Correct as
discipline, but it left the armour looking like concrete, and the material it is actually crafted
from -- ten polished ether quartz around a netherite core -- never appears anywhere on it.

V7 throws that reading out and takes the quartz as the material. The reference for what a Create
suit is allowed to spend was measured off the jar:

    vanilla diamond / iron     7-10 distinct colours, near-flat fills, no separation between plates
    create copper diving          52 colours, dense local shading, no rims around its faces
    create netherite diving       20 colours, same
    create cardboard              21 colours, same

So the budget is not six tones. The ramp is polished_ether_quartz.png's own eight colours,
verbatim -- #35536e .. #ffffff -- with nothing padded on either end. Padding the dark end with two
invented steps was an earlier mistake: it put the suit below the darkest colour in the material it
is made of, and the armour read as darker than its own quartz.

What create does *not* do is rim its faces. Read off the copper atlas, its chest face carries mid
tones down both side columns and puts its darks in straps across the middle: the suit is painted as
one continuous object under a single light. An earlier pass here did rim every face, and that is
exactly what made it read as a grid of tiles. The shell is shaded by orientation, bevelled by a
fraction of a step, and quantised onto an interpolated ramp; see glaze().

The chest carries no ornament at all. What the suit does have is the places where its plates stop
-- a hem under the chestplate, cuffs at the sleeves and boots. Without them it is skin-tight from
collar to sole and reads as a bodysuit rather than as armour.

Silhouette untouched: alpha is vanilla diamond's, byte for byte, asserted below.
"""
from __future__ import annotations

import io
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageColor, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts/concepts'))
from render_scene import box, render, move  # noqa: E402

OUT = HERE / 'textures'
OUT.mkdir(parents=True, exist_ok=True)
JAR = ROOT / 'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar'

# ---------------------------------------------------------------- the material
# Exactly the eight colours of polished_ether_quartz.png, dark to light, nothing added. An
# earlier pass padded the bottom with two invented steps (#22354d at luminance 51, #2f4d6b at 73)
# to get more contrast out of the shading -- which put the suit two full steps below the darkest
# colour in the material it is supposed to be made of. The armour read as darker than its own
# quartz. The raw and polished items share this same eight, they only differ in how they weight
# them: the polished one leans on #699dbc and #f3f6e9, which is why it looks the whiter of the two.
GLASS = ['#35536e', '#507b9b', '#699dbc', '#8dbbd6',
         '#b6d8e8', '#d9ebee', '#f3f6e9', '#ffffff']
GLINT = '#ffffff'          # the specular, on faces that point at the sky

NETS = dict(
    head=dict(east=(0, 8, 8, 16), north=(8, 8, 16, 16), west=(16, 8, 24, 16), south=(24, 8, 32, 16),
              up=(8, 0, 16, 8), down=(16, 0, 24, 8)),
    body=dict(east=(16, 20, 20, 32), north=(20, 20, 28, 32), west=(28, 20, 32, 32), south=(32, 20, 40, 32),
              up=(20, 16, 28, 20), down=(28, 16, 36, 20)),
    arm=dict(east=(40, 20, 44, 32), north=(44, 20, 48, 32), west=(48, 20, 52, 32), south=(52, 20, 56, 32),
             up=(44, 16, 48, 20), down=(48, 16, 52, 20)),
    leg=dict(east=(0, 20, 4, 32), north=(4, 20, 8, 32), west=(8, 20, 12, 32), south=(12, 20, 16, 32),
             up=(4, 16, 8, 20), down=(8, 16, 12, 20)),
)
STEPS = {(0, -1): 'up', (0, 1): 'down', (-1, 0): 'left', (1, 0): 'right'}

# Base ramp step per face orientation, light from the front-upper-left. This is what makes the
# model read as one solid object rather than a set of separately painted boxes: the top of the
# helmet and the shoulders are the same tone, the flanks are darker, the back is darker still.
ORIENT = {'up': 6, 'north': 4, 'west': 4, 'east': 3, 'south': 2, 'down': 1}


def vanilla(name):
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read('assets/minecraft/textures/' + name + '.png'))).convert('RGBA')


def paint(im, xy, color, alpha):
    """Recolour a texel. `alpha` is the mask to take the coverage from, or an int to write outright.

    Taking it from the target only works once the target has been laid down from the mask; before
    that every write stamps alpha 0 and the whole layer comes out empty. Glass mode needs to write
    its own alpha, so it passes the number directly.
    """
    a = alpha if isinstance(alpha, int) else alpha.getpixel(xy)[3]
    im.putpixel(xy, ImageColor.getrgb(color)[:3] + (a,))


# 4x4 ordered dither, kept for the 'dither' path only. It was the first answer to the banding and
# it is not the one that shipped: see quantise().
BAYER = ((0, 8, 2, 10), (12, 4, 14, 6), (3, 11, 1, 9), (15, 7, 13, 5))

BEVEL_LIP = 0.70      # a full step, but split across the edges so a corner can take both
BEVEL_SHOULDER = 0.30  # how far the bevel eases into the plate before it is flat
HEM_DROP = 1.5         # how far the turned-under edge of a plate falls

# Where a plate stops. Without one the suit is skin-tight from collar to sole and reads as a
# bodysuit rather than as armour -- which is the "something is off" that survived the palette fix.
# The hem is the plate's own thickness: the bottom row turns under and goes dark, the row above
# catches a little less light. Layer 1 owns the chestplate, the sleeves and the boots; layer 2's
# lower half is hidden under the boots, so it gets nothing.
HEM_ROWS = {(1, 'body'): 2, (1, 'arm'): 2, (1, 'leg'): 2}
PLATES = True       # False drops the hems and the suit goes back to reading as a bodysuit

# 'solid' paints the suit as polished stone. 'glass' makes it a transparent shell over the player's
# own skin: coverage is driven by how structural a texel is, so flat plate goes nearly clear and the
# bevels and hems are what carries the shape. The render path already exists -- HumanoidArmorLayerMixin
# forces entityTranslucent for anything named textures/models/armor/ether_casing_layer_*, which is how
# the cloak phases fade out. Vanilla armour is cutout, where alpha is binary.
OPACITY = 'glass'
ALPHA_FACE = 40        # a flat expanse of plate
ALPHA_GAIN = 90        # per unit of bevel or hem strength
ALPHA_SPEC = 235       # the sky-facing specular stays a solid highlight
GLASS_FLOOR = 2.5      # floor on the tone field, not a ramp index


def hem_drop(layer, part, rows_up, n):
    if not (PLATES and n):
        return 0.0
    return 0.0 if rows_up >= n else HEM_DROP * (1.0 - rows_up / n)


def interp_ramp(n):
    """GLASS resampled to n even steps, by straight interpolation between its poles.

    The poles are the ether quartz item's own colours and are untouched; everything between them is
    a mix of two neighbours, so no hue enters the suit that the material did not already have.
    """
    out = []
    for i in range(n):
        t = i / (n - 1) * (len(GLASS) - 1)
        a = int(t)
        b = min(len(GLASS) - 1, a + 1)
        fr = t - a
        ca, cb = ImageColor.getrgb(GLASS[a]), ImageColor.getrgb(GLASS[b])
        out.append('#%02x%02x%02x' % tuple(round(ca[k] + (cb[k] - ca[k]) * fr) for k in range(3)))
    return out


FINE = interp_ramp(25)
TRANSITION = 'fine'        # 'band' | 'dither' | 'fine' -- see quantise()


def quantise(f, x, y):
    """Continuous tone -> a colour, by whichever transition this draft is rendered with.

    band    round onto the nine poles. Crisp, and the gap between two of them is wide enough to
            read as a drawn line -- the version that got called crude.
    dither  4x4 ordered dither on the nine poles. Dissolves that line, but any texel sitting near
            a half step breaks 50/50 and the plate goes mottled, which is its own kind of wrong.
    fine    round onto a 17-step ramp interpolated from the same nine poles. No dither at all:
            neighbouring tones are close enough that the step stops showing.
    """
    if TRANSITION == 'fine':
        k = int(f / (len(GLASS) - 1) * (len(FINE) - 1) + 0.5)
        return FINE[max(0, min(len(FINE) - 1, k))]
    if TRANSITION == 'dither':
        f += (BAYER[y % 4][x % 4] + 0.5) / 16.0
    return GLASS[max(0, min(len(GLASS) - 1, int(f)))]


def glaze(src, seams=None, layer=1):
    """Shade the suit as one polished shell.

    An earlier pass rimmed every face, and that is what made it read as a grid of tiles: create's
    own copper diving suit does not do it. Read off the atlas, its chest face carries mid tones down
    both side columns and puts its darks in straps across the middle -- the suit is painted as a
    continuous object under one light.

    So there are no rim lines. Form comes from a continuous tone field:

      * a base per face ORIENTATION, so the box reads as a solid lit from the front-upper-left
      * a BEVEL along every face edge -- +0.7 per lit edge (up, left), -0.7 per shadow edge (down,
        right). Summing per edge rather than testing "is it lit" is what lets a corner reach +-1.4
        and actually read as a corner; an earlier version claimed that in a comment but the code
        only ever produced +-1.
      * a SHOULDER one texel in, where the bevel eases off, so the lip does not end on a hard edge
      * a slow gradient across the face, +0.9 to -0.9, so a wide plate is never a flat fill

    Every term is fractional, and the field is quantised onto a 17-step ramp interpolated from the
    nine poles -- not onto the poles themselves. Nine steps was the last problem: a face only spans
    about three of them, so each step was far enough from the next to read as a drawn line,
    and quantising there gave every plate visible bands. Dithering those bands was the second
    attempt and traded them for mottle. A finer ramp costs no new hue -- every step is a mix of two
    neighbouring poles -- and needs no dither at all.
    """
    seams = seams or {}
    im = Image.new('RGBA', src.size)
    for x in range(src.width):
        for y in range(src.height):
            c = src.getpixel((x, y))
            if c[3]:
                paint(im, (x, y), GLASS[3], ALPHA_FACE if OPACITY == 'glass' else src)
    for part, table in NETS.items():
        for name, (x0, y0, x1, y1) in table.items():
            inside = lambda p: x0 <= p[0] < x1 and y0 <= p[1] < y1 and src.getpixel(p)[3] > 0
            cells = {(x, y) for x in range(x0, x1) for y in range(y0, y1) if inside((x, y))}
            if not cells:
                continue
            base = ORIENT[name]
            w, h = x1 - x0, y1 - y0
            shoulder = min(w, h) >= 8          # a four-wide limb has no room for one

            def sides(p):
                """Which sides of this texel have no neighbour inside the face."""
                return {d for (dx, dy), d in STEPS.items() if (p[0] + dx, p[1] + dy) not in cells}

            # The lip is the ring of texels that form the edge; its sign is +lit / -shade, and the
            # shoulder is the ring just inside it, which eases off in the same direction.
            lip = {p for p in cells if sides(p)}
            lip_sign = {p: (1 if sides(p) & {'up', 'left'} else 0)
                           - (1 if sides(p) & {'down', 'right'} else 0) for p in lip}

            for (x, y) in sorted(cells):
                depth = min(x - x0, x1 - 1 - x, y - y0, y1 - 1 - y)
                if depth == 0:
                    e = sides((x, y))
                    bevel = BEVEL_LIP * (len(e & {'up', 'left'}) - len(e & {'down', 'right'}))
                elif shoulder:
                    bevel = BEVEL_SHOULDER * sum(lip_sign.get((x + dx, y + dy), 0) for dx, dy in STEPS)
                else:
                    bevel = 0.0
                u = (x - x0) / max(1, w - 1)
                v = (y - y0) / max(1, h - 1)
                f = base + bevel + 0.9 - 1.8 * (0.62 * u + 0.38 * v)
                hem = hem_drop(layer, part, y1 - 1 - y, HEM_ROWS.get((layer, part), 0))
                f -= hem
                if OPACITY == 'glass':
                    # A transparent shell is read by its edges, not by its surfaces. Coverage rides
                    # on how structural the texel is -- a flat expanse of plate is nearly clear, the
                    # bevel lip and the hems are what you actually see -- and the tint is held off
                    # the dark end, because a dark tint over skin does not read as glass, it reads
                    # as dirt.
                    a = int(min(255, ALPHA_FACE + (abs(bevel) + hem) * ALPHA_GAIN))
                    paint(im, (x, y), quantise(max(f, GLASS_FLOOR), x, y), a)
                else:
                    paint(im, (x, y), quantise(f, x, y), src)

            # The specular. Only on faces that actually point at the sky -- the helmet crown and the
            # shoulder tops -- where a polished surface would throw one. Down the front of the chest
            # it is not a highlight, it is an ornament, and the chest is meant to stay plain.
            if name == 'up' and min(w, h) >= 8:
                for (x, y) in sorted(cells):
                    if (x - x0, y - y0) in ((3, 2), (4, 2), (3, 3), (4, 3), (5, 2), (2, 2)):
                        paint(im, (x, y), GLINT, ALPHA_SPEC if OPACITY == 'glass' else src)

            for frac in seams.get(part, ()):
                sy = y0 + int(frac * (y1 - y0 - 1))
                for (x, y) in cells:
                    if y == sy:
                        paint(im, (x, y), quantise(base - 1.4, x, y), src)
    return im


# No seams and no ornaments: the suit is one shell, and the chest is meant to stay plain.
SEAMS = {1: {}, 2: {}}


def build():
    layers = {n: glaze(vanilla(f'models/armor/diamond_layer_{n}'), SEAMS[n], n) for n in (1, 2)}
    return layers


KINDS = ('helmet', 'chestplate', 'leggings', 'boots')


def icon(kind):
    """The 16x16 inventory sprite, same material and same rules.

    An icon is a silhouette, not a UV net, so there are no face orientations to read -- the sprite
    is shaded as one small object instead: a mid base, a slow diagonal gradient, and the same
    +-1 bevel traced along its own contour (up/left edges catch light, down/right turn away).
    """
    src = vanilla('item/diamond_' + kind).convert('RGBA')
    solid = lambda p: 0 <= p[0] < 16 and 0 <= p[1] < 16 and src.getpixel(p)[3] > 0
    xs = [x for x in range(16) for y in range(16) if solid((x, y))]
    ys = [y for x in range(16) for y in range(16) if solid((x, y))]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    out = Image.new('RGBA', (16, 16))
    for x in range(16):
        for y in range(16):
            if not solid((x, y)):
                continue
            edge = {d for (dx, dy), d in STEPS.items() if not solid((x + dx, y + dy))}
            bevel = (1 if edge & {'up', 'left'} else 0) - (1 if edge & {'down', 'right'} else 0)
            u = (x - x0) / max(1, x1 - x0)
            v = (y - y0) / max(1, y1 - y0)
            g = 1 if (0.62 * u + 0.38 * v) < 0.34 else (-1 if (0.62 * u + 0.38 * v) > 0.72 else 0)
            paint(out, (x, y), GLASS[max(0, min(len(GLASS) - 1, 4 + bevel + g))], src)
    assert out.getchannel('A').tobytes() == src.getchannel('A').tobytes(), kind
    return out


BACKGROUND = '#e6e3db'
SHEET_FONT = '/System/Library/Fonts/STHeiti Medium.ttc'


def sheet_for(layers, title, subtitle, name):
    mesh, textures = [], {}

    def cuboid(key, lo, hi, atlas=None, part=None, inflate=0, mirror=False):
        f = box(tuple(v - inflate for v in lo), tuple(v + inflate for v in hi), '')
        for direction, face in zip(('north', 'south', 'west', 'east', 'up', 'down'), f):
            k = key + '_' + direction
            if atlas is not None:
                tile = atlas.crop(NETS[part][direction])
                if mirror:
                    tile = tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            else:
                tile = Image.new('RGBA', (8, 8), '#909a98')
                if key == 'under_head':
                    tile = Image.new('RGBA', (8, 8), '#bbac94')
                    if direction == 'north':
                        d = ImageDraw.Draw(tile)
                        d.point((2, 3), fill='#54605e')
                        d.point((5, 3), fill='#54605e')
                        d.line((3, 6, 4, 6), fill='#96856f')
            textures[k] = tile
            face['material'] = k
            face['uv'] = [[0, 0], [tile.width, 0], [tile.width, tile.height], [0, tile.height]]
        mesh.extend(f)

    # Mannequin and inflation match V2-V5 so the drafts compare side by side.
    cuboid('under_head', (-4, 24, -4), (4, 32, 4))
    cuboid('under_body', (-4, 12, -2), (4, 24, 2))
    for side, sign in (('right', -1), ('left', 1)):
        start = len(mesh)
        lo, hi = ((-8, 12, -2), (-4, 24, 2)) if sign < 0 else ((4, 12, -2), (8, 24, 2))
        cuboid('under_' + side + 'arm', lo, hi)
        cuboid(side + 'arm', lo, hi, layers[1], 'arm', .96, sign > 0)
        mesh[start:] = move(mesh[start:], turns=[('z', sign * 5, (sign * 4, 24, 0))])
        lo, hi = ((-5.05, 0, -2), (-1.05, 12, 2)) if sign < 0 else ((1.05, 0, -2), (5.05, 12, 2))
        cuboid('under_' + side + 'leg', lo, hi)
        cuboid(side + 'leg', lo, hi, layers[2], 'leg', .5, sign > 0)
        cuboid(side + 'boot', lo, hi, layers[1], 'leg', 1, sign > 0)
    cuboid('chest', (-4, 12, -2), (4, 24, 2), layers[1], 'body', 1)
    cuboid('waist', (-4, 12, -2), (4, 24, 2), layers[2], 'body', .48)
    cuboid('helmet', (-4, 24, -4), (4, 32, 4), layers[1], 'head', 1)

    sh = Image.new('RGBA', (1320, 880), BACKGROUND)
    d = ImageDraw.Draw(sh)

    def label(x, y, value, size=20):
        d.text((x, y), value, font=ImageFont.truetype(SHEET_FONT, size), fill='#415f6b')

    label(35, 20, title, 32)
    label(36, 66, subtitle)
    for i, (view, yaw, caption) in enumerate((('front', 0, '正面'), ('three_quarter', -28, '四分之三视角'), ('back', 155, '后侧视角'))):
        pic = render(mesh, textures, size=(440, 650), yaw=yaw, pitch=7, center=(0, 16, 0), scale=17, background=BACKGROUND)
        pic = pic.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        pic.save(HERE / f'{name}_{view}.png')
        sh.alpha_composite(pic, (i * 440, 90))
        label(i * 440 + 175, 720, caption)
    for i, kind in enumerate(KINDS):
        sh.alpha_composite(icon(kind).resize((64, 64), Image.Resampling.NEAREST), (40 + i * 84, 770))
    label(400, 775, '物品图标', 17)
    for i, n in enumerate((1, 2)):
        sh.alpha_composite(layers[n].resize((256, 128), Image.Resampling.NEAREST), (700 + i * 300, 745))
    label(35, 850, '独立美术概念 / 未接入模组', 17)
    path = HERE / f'design_sheet_{name}.png'
    sh.save(path)
    return path


if __name__ == '__main__':
    layers = build()
    for n, im in layers.items():
        ref = vanilla(f'models/armor/diamond_layer_{n}')
        if OPACITY == 'glass':
            # Coverage is intentional, so the guard is that the same texels are present at all --
            # the silhouette is still vanilla diamond's, the armour is just see-through.
            assert all((im.getpixel((x, y))[3] > 0) == (ref.getpixel((x, y))[3] > 0)
                       for x in range(16) for y in range(16)), f'layer {n} changed shape'
        else:
            assert im.getchannel('A').tobytes() == ref.getchannel('A').tobytes(), \
                f'layer {n} alpha drifted from vanilla diamond'
        im.save(OUT / f'v7_layer_{n}.png')
    for kind in KINDS:
        icon(kind).save(OUT / f'v7_{kind}.png')
    print(sheet_for(
        layers,
        '谐振石英护甲 V7 / 磨制石英',
        '连续曲面着色 · 朝向定基调 + 边倒角 + 面内渐变 · 17 档插值色阶 · 胸前不加元素 · 轮廓与钻石护甲逐字节一致',
        'V7'))
