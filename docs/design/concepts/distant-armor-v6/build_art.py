"""Resonant Quartz armour, draft V6 -- three ways to let the ether show.

V5 settled the shape: the suit is casing plates on an untouched vanilla silhouette, and the
only ornament was a single 2x2 window on the chest. That discipline holds here. What V5 left
unsaid is the material. The suit is called *Resonant Quartz*, it is crafted from ten polished
ether quartz around a netherite core, and every one of its abilities is paid for out of a
medium reserve -- and none of that is visible. The armour reads as concrete.

These three drafts all keep the casing frame, the diamond alpha byte for byte, and the "no
new colours" rule. They differ in how much of the ether is allowed through:

  A  conduits   casing throughout; thin medium channels run chest -> limbs, and the chest
                window grows into a gauge. Roughly a dozen texels of ether. Nearest to V5.
  B  insets     every face's quiet centre becomes an ether-quartz pane; the casing becomes a
                frame. The suit turns grey-framed blue crystal. Strongest material read.
  C  graded     head and chest get the quartz panes, matching the two pieces that actually
                have unique abilities; legs and boots stay casing and gain a knee strip. The
                lower half gets an identity for the first time.

The quartz ramp is sampled off the ether quartz item so the pane tones belong to the material
rather than being invented, and it sits deliberately UNDER the casing centre in luminance --
a pane that outshines its own frame turns the suit into a set of glowing windows, which the
line has ruled out ("不发光·无光晕·无新配色", ether-lineup-v2).
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
BLOCKS = ROOT / 'src/main/resources/assets/distantstock/textures/block/tower'

# ---------------------------------------------------------------- casing, straight from V5
FRAME_LIGHT, FRAME_DARK = '#8599a2', '#758a94'
CORNER = {'tl': '#758a94', 'tr': '#667c87', 'bl': '#667c87', 'br': '#5b727e'}
BEVEL_LIGHT = ['#c5d4da', '#d4e2e7', '#c5d4da', '#b8c8cf']
BEVEL_DARK = ['#a9bac2', '#a9bac2', '#97a9b2', '#a9bac2']
CENTRE = [(184, 200, 207), (192, 207, 214), (197, 210, 217),
          (205, 218, 223), (210, 222, 227), (214, 226, 230)]
GROOVE = '#617883'
ETHER, ETHER_GLINT, ETHER_LOW = '#b7e0ed', '#e8fbff', '#acdcf1'
ICON = [(91, 114, 126), (117, 138, 148), (133, 153, 162),
        (169, 186, 194), (197, 212, 218), (214, 226, 230)]

# ---------------------------------------------------------------- the quartz, off the item
# The pane has to separate from its frame on CHROMA, not on brightness. A first pass kept the
# quartz desaturated so it could not outshine the casing -- the result was a suit of washed grey
# that read as "V5 in blue", because the casing centre (luminance 195-223) and a pale blue-grey
# occupy the same tonal band. Saturation is what carries it; the top step still stops at 225, at
# the casing centre's own weight, so nothing emits ("不发光·无光晕·无新配色", ether-lineup-v2).
QUARTZ = ['#4a708f', '#5b8cae', '#6da5c7', '#88bdd9', '#a8d3e8', '#c9e6f4']
PANE_FLAT = '#6da5c7'
# One constant tone, deliberately. Cycling a few blues along the run turned the conduit into a
# barber's pole: each texel already sits on a differently-lit part of the vanilla arm, so the
# colour changed twice as fast as the line and it read as a dashed stripe, not plumbing.
CONDUIT = '#7fb8d4'
RESERVOIR = ['#57758c', '#7fb8d4', ETHER_LOW, ETHER, ETHER_GLINT]


def vanilla(name):
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read('assets/minecraft/textures/' + name + '.png'))).convert('RGBA')


def paint(im, xy, color):
    """Recolour an existing texel, keeping its alpha."""
    im.putpixel(xy, ImageColor.getrgb(color)[:3] + (im.getpixel(xy)[3],))


def luminance(c):
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]


def ramp(src, palette):
    values = sorted(luminance(c) for c in src.getdata() if c[3])
    low, high = values[0], values[-1]

    def pick(c):
        t = (luminance(c) - low) / max(1, high - low)
        return palette[min(len(palette) - 1, int(t * (len(palette) - .01)))]
    return pick


def net(u, v, w, h, d):
    return dict(east=(u, v + d, u + d, v + d + h), north=(u + d, v + d, u + d + w, v + d + h),
                west=(u + d + w, v + d, u + 2 * d + w, v + d + h), south=(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
                up=(u + d, v, u + d + w, v + d), down=(u + d + w, v, u + d + 2 * w, v + d))


NETS = dict(head=net(0, 0, 8, 8, 8), body=net(16, 16, 8, 12, 4), arm=net(40, 16, 4, 12, 4), leg=net(0, 16, 4, 12, 4))
STEPS = {(0, -1): 'up', (0, 1): 'down', (-1, 0): 'left', (1, 0): 'right'}

# Which faces carry a quartz pane in draft B (every face) and draft C (the two instrument pieces).
INSTRUMENT = {'head', 'body'}


def faces():
    """(part, face-name, rect, cells) for every face rectangle on the atlas."""
    for part, table in NETS.items():
        for name, (x0, y0, x1, y1) in table.items():
            yield part, name, (x0, y0, x1, y1)


def panelise(src, centre_palette, pane=None, pane_parts=None, veins=()):
    """Casing panels over the vanilla mask; a quartz pane in the centre where allowed.

    Rings are measured inwards from the face rectangle *and* from the vanilla cut-outs, so the
    helmet opening and boot cuffs pick up a rim and read as cuts in a casing plate rather than
    holes with a raw edge. Faces narrower than eight texels get no bevel -- at four across, a rim
    plus a bevel leaves no surface and the arms turn into solid outline.
    """
    im = Image.new('RGBA', src.size)
    centre = ramp(src, centre_palette)
    pane_ramp = ramp(src, pane) if pane else None
    for x in range(src.width):
        for y in range(src.height):
            c = src.getpixel((x, y))
            if c[3]:
                im.putpixel((x, y), centre(c) + (c[3],))

    for part, name, rect in faces():
        x0, y0, x1, y1 = rect
        inside = lambda p: x0 <= p[0] < x1 and y0 <= p[1] < y1 and src.getpixel(p)[3] > 0
        cells = [(x, y) for x in range(x0, x1) for y in range(y0, y1) if inside((x, y))]
        if not cells:
            continue
        bevel = min(x1 - x0, y1 - y0) >= 8
        ring = {}
        for p in cells:
            if any(not inside((p[0] + dx, p[1] + dy)) for dx, dy in STEPS):
                ring[p] = 0
        for p in cells:
            if p not in ring and any(ring.get((p[0] + dx, p[1] + dy)) == 0 for dx, dy in STEPS):
                ring[p] = 1
        for (x, y), k in ring.items():
            if k == 1 and not bevel:
                continue
            towards = {n for (dx, dy), n in STEPS.items()
                       if (not inside((x + dx, y + dy)) if k == 0 else ring.get((x + dx, y + dy)) == k - 1)}
            light = bool(towards & {'up', 'left'})
            dark = bool(towards & {'down', 'right'})
            if k == 0:
                vertical = 't' if 'up' in towards else 'b' if 'down' in towards else ''
                horizontal = 'l' if 'left' in towards else 'r' if 'right' in towards else ''
                color = (CORNER[vertical + horizontal] if vertical and horizontal
                         else FRAME_LIGHT if light else FRAME_DARK)
            else:
                color = (BEVEL_DARK if dark and not light else BEVEL_LIGHT)[(x + y) % 4]
            paint(im, (x, y), color)

        # --- draft B / C: the quiet centre becomes a pane
        # On a four-wide face the k=1 ring is never painted, so it must not count as border
        # either -- otherwise the pane would be two texels wide out of four and the arms and
        # legs would come out as solid outline again.
        if pane and (pane_parts is None or part in pane_parts):
            inner = [p for p in cells if ring.get(p, -1) != 0 and not (bevel and ring.get(p) == 1)]
            if inner:
                for (x, y) in inner:
                    # A four-wide face leaves a two-texel pane. Ramping two texels against each
                    # other stripes them like barcode, so narrow faces get one flat crystal tone.
                    tone = pane_ramp(src.getpixel((x, y))) if bevel else PANE_FLAT
                    paint(im, (x, y), tone)

    # --- draft A: medium channels
    for path in veins:
        for (x, y) in path:
            if im.getpixel((x, y))[3]:
                paint(im, (x, y), CONDUIT)
    return im


def reservoir(im, x, y, w=4, h=4):
    """A quartz gauge in a groove: the medium made visible at the chest.

    V5's window was 2x2 -- a detail. At 4x4 with a groove around it the chest reads as a
    sight glass on a reservoir, which is what the canister loop actually is.
    """
    for dx in range(-1, w + 1):
        for dy in range(-1, h + 1):
            p = (x + dx, y + dy)
            if not im.getpixel(p)[3]:
                continue          # the gauge may run off the lit part of the plate; clip, never punch
            edge = dx in (-1, w) or dy in (-1, h)
            if edge:
                paint(im, p, GROOVE)
            else:
                t = (dy + h * dx / max(1, w - 1)) / max(1, h - 1) if w > 1 else 0
                paint(im, p, RESERVOIR[min(len(RESERVOIR) - 1, int(t * (len(RESERVOIR) - .01)))])


def icons(kind, pane_parts=(), veins=()):
    """The 16x16 inventory icon, same rules at icon scale."""
    src = vanilla('item/diamond_' + kind)
    pick = ramp(src, ICON)
    icon = src.copy()
    for x in range(16):
        for y in range(16):
            c = src.getpixel((x, y))
            if c[3]:
                icon.putpixel((x, y), pick(c) + (c[3],))
    if kind == 'chestplate':
        reservoir(icon, 7, 9)
    elif kind in pane_parts:
        qp = ramp(src, QUARTZ)
        for x in range(16):
            for y in range(16):
                c = src.getpixel((x, y))
                if c[3] and luminance(c) > 150:      # only the lit flats become quartz
                    paint(icon, (x, y), qp(c))
    assert icon.getchannel('A').tobytes() == src.getchannel('A').tobytes()
    return icon


# ---------------------------------------------------------------- the sheet
BACKGROUND = '#e6e3db'
SHEET_FONT = '/System/Library/Fonts/STHeiti Medium.ttc'


def build(layers, icon_of, title, subtitle, name):
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

    sheet = Image.new('RGBA', (1320, 880), BACKGROUND)
    d = ImageDraw.Draw(sheet)

    def label(x, y, value, size=20):
        d.text((x, y), value, font=ImageFont.truetype(SHEET_FONT, size), fill='#415f6b')

    label(35, 20, title, 32)
    label(36, 66, subtitle)
    for i, (view, yaw, caption) in enumerate((('front', 0, '正面'), ('three_quarter', -28, '四分之三视角'), ('back', 155, '后侧视角'))):
        pic = render(mesh, textures, size=(440, 650), yaw=yaw, pitch=7, center=(0, 16, 0), scale=17, background=BACKGROUND)
        pic = pic.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        pic.save(HERE / f'{name}_{view}.png')
        sheet.alpha_composite(pic, (i * 440, 90))
        label(i * 440 + 175, 720, caption)
    for i, kind in enumerate(('helmet', 'chestplate', 'leggings', 'boots')):
        sheet.alpha_composite(icon_of(kind).resize((64, 64), Image.Resampling.NEAREST), (40 + i * 84, 770))
    label(400, 775, '材质参照', 17)
    for i, block in enumerate(('casing_inactive', 'casing_active')):
        sheet.alpha_composite(Image.open(BLOCKS / f'{block}.png').convert('RGBA').resize((64, 64), Image.Resampling.NEAREST), (490 + i * 84, 770))
    for i, n in enumerate((1, 2)):
        sheet.alpha_composite(layers[n].resize((256, 128), Image.Resampling.NEAREST), (700 + i * 300, 745))
    label(35, 850, '独立美术概念 / 未接入模组', 17)
    path = HERE / f'design_sheet_{name}.png'
    sheet.save(path)
    return path


# ---------------------------------------------------------------- the three drafts
VANILLA_L1 = vanilla('models/armor/diamond_layer_1')
VANILLA_L2 = vanilla('models/armor/diamond_layer_2')

# Everything lives inside the 64x32 atlas. Face rectangles that matter here:
#   body north  x20-27, y20-31   (chest front; quiet centre x22-25, y22-29)
#   arm  north  x44-47, y20-31   (drawn once -- both arms share it, one of them mirrored)
#   leg  north   x4-7,  y20-31
# A channel crosses onto the bevel rather than stopping short of it, so it reads as passing
# under the plate edge instead of floating in the middle of a panel.
VEINS = [
    # Chest: one continuous run down the sternum, in through the collar and out through the
    # belly plate, so the sight glass reads as a window cut into a channel rather than a gem
    # set into a plate. A broken channel reads as decoration; a continuous one reads as plumbing.
    [(23, 20), (23, 21), (23, 22)],
    [(23, 27), (23, 28), (23, 29)],
    [(45, 21), (45, 22), (45, 23), (45, 24), (45, 25),
     (45, 26), (45, 27), (45, 28), (45, 29), (45, 30)],     # arm, down the outer face
    [(5, 21), (5, 22), (5, 23), (5, 24), (5, 25),
     (5, 26), (5, 27), (5, 28), (5, 29), (5, 30)],          # leg, down the shin
]

def draft_a():
    l1 = panelise(VANILLA_L1, CENTRE, veins=VEINS)
    l2 = panelise(VANILLA_L2, CENTRE, veins=VEINS)
    reservoir(l1, 23, 23, 2, 4)
    return {1: l1, 2: l2}


def draft_b():
    l1 = panelise(VANILLA_L1, CENTRE, pane=QUARTZ)
    l2 = panelise(VANILLA_L2, CENTRE, pane=QUARTZ)
    reservoir(l1, 23, 23, 2, 4)
    return {1: l1, 2: l2}


def draft_c():
    l1 = panelise(VANILLA_L1, CENTRE, pane=QUARTZ, pane_parts=INSTRUMENT, veins=VEINS)
    l2 = panelise(VANILLA_L2, CENTRE, pane=QUARTZ, pane_parts=INSTRUMENT, veins=VEINS)
    # Knee strip: the legs' first piece of identity. One band across all four side faces of the
    # leg net, inset a texel so it sits on the plate rather than under the rim.
    for face in ('east', 'north', 'west', 'south'):
        x0, _y0, x1, _y1 = NETS['leg'][face]
        for x in range(x0 + 1, x1 - 1):
            for y in (26, 27):
                if l2.getpixel((x, y))[3]:
                    paint(l2, (x, y), QUARTZ[(x + y) % 3])
    reservoir(l1, 23, 23, 2, 4)
    return {1: l1, 2: l2}


DRAFTS = [
    ('A_脉络', '远仓机壳护甲 V6-A / 介质脉络', '机壳面板 + 介质通道由胸前液位镜通向四肢 · 轮廓与钻石护甲完全一致', draft_a,
     lambda k: icons(k, veins=VEINS)),
    ('B_嵌板', '远仓机壳护甲 V6-B / 石英嵌板', '每个甲面的中心换成以太石英嵌板 · 机壳退为边框 · 轮廓与钻石护甲完全一致', draft_b,
     lambda k: icons(k, pane_parts=('helmet', 'chestplate', 'leggings', 'boots'))),
    ('C_分层', '远仓机壳护甲 V6-C / 仪器与工作层', '头盔与胸甲承载石英（对应两件独有能力）· 护腿与靴子保持机壳并首次获得膝甲 · 轮廓与钻石护甲完全一致', draft_c,
     lambda k: icons(k, pane_parts=('helmet', 'chestplate'))),
]

if __name__ == '__main__':
    for name, title, subtitle, fn, icon_of in DRAFTS:
        layers = fn()
        for n, im in layers.items():
            assert im.getchannel('A').tobytes() == vanilla(f'models/armor/diamond_layer_{n}').getchannel('A').tobytes()
            im.save(OUT / f'v6_{name}_layer_{n}.png')
        print(build(layers, icon_of, title, subtitle, name))
