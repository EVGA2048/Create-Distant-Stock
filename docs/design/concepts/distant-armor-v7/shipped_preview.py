#!/usr/bin/env python3
"""Preview the armour textures that are actually in src/main/resources, not a concept copy.

Two questions this answers that the design sheets could not:

  1. Does the player's skin read through? The concept sheets draw the mannequin in flat grey, and a
     flat grey mannequin flatters a translucent shell badly -- every earlier pass that looked right
     on the sheet turned out too thin or too blue against real skin.
  2. What survives at range? At distance a texture is mipmapped, which is an area-weighted mean over
     the whole face, so the flats decide what you see. Up close it is the texels themselves, so the
     lines decide. Rendering the same mesh at four scales, the last one with the texture pre-filtered
     the way the mip chain would, is the only way to check both readings at once.
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = next(a for a in HERE.parents if (a / 'scripts/concepts/render_scene.py').exists())
sys.path.insert(0, str(ROOT / 'scripts/concepts'))

from PIL import Image, ImageDraw, ImageFont
from render_scene import box, move, render

ARMOUR = ROOT / 'src/main/resources/assets/distantstock/textures/models/armor'

BACKGROUND = '#8fb7d4'      # open sky: the lightest thing this suit will ever be seen against
FONT = '/System/Library/Fonts/STHeiti Medium.ttc'
VANILLA = ROOT / 'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar'

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


def underkind(name, direction, kind):
    """The body the shell goes over. Skin, a shirt, trousers -- not flat grey."""
    if kind == 'shirt' and direction == 'north':
        t = Image.new('RGBA', (8, 8), '#2f6f4e')       # a saturated shirt, so a tint shows up
        d = ImageDraw.Draw(t)
        d.line((0, 3, 7, 3), fill='#255840')
        d.line((3, 0, 3, 2), fill='#255840')
        return t
    if kind == 'shirt':
        return Image.new('RGBA', (8, 8), '#2f6f4e')
    if kind == 'skin' and direction == 'north':
        t = Image.new('RGBA', (8, 8), '#b98a62')
        d = ImageDraw.Draw(t)
        d.point((2, 3), fill='#3d2f26'); d.point((5, 3), fill='#3d2f26')   # eyes
        d.line((3, 6, 4, 6), fill='#8d6244')                               # mouth
        d.line((0, 1, 7, 1), fill='#6b4a33')                               # hairline
        return t
    return Image.new('RGBA', (8, 8), '#b98a62' if kind == 'skin' else '#3f4a63')


def build(layers):
    """Player geometry, armour inflated over it exactly as HumanoidArmorLayer does."""
    mesh, textures = [], {}

    def cuboid(key, lo, hi, atlas=None, part=None, inflate=0, mirror=False, under=None):
        f = box(tuple(v - inflate for v in lo), tuple(v + inflate for v in hi), '')
        for direction, fc in zip(('north', 'south', 'west', 'east', 'up', 'down'), f):
            k = key + '_' + direction
            if atlas is None:
                tile = underkind(k, direction, under)
            else:
                tile = atlas.crop(NETS[part][direction])
                if mirror:
                    tile = tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            textures[k] = tile
            fc['material'] = k
            fc['uv'] = [[0, 0], [tile.width, 0], [tile.width, tile.height], [0, tile.height]]
        mesh.extend(f)

    cuboid('under_head', (-4, 24, -4), (4, 32, 4), under='skin')
    cuboid('under_body', (-4, 12, -2), (4, 24, 2), under='shirt')
    for side, sign in (('right', -1), ('left', 1)):
        start = len(mesh)
        lo, hi = ((-8, 12, -2), (-4, 24, 2)) if sign < 0 else ((4, 12, -2), (8, 24, 2))
        cuboid('under_' + side + 'arm', lo, hi, under='skin')
        cuboid(side + 'arm', lo, hi, layers[1], 'arm', .96, sign > 0)
        mesh[start:] = move(mesh[start:], turns=[('z', sign * 5, (sign * 4, 24, 0))])
        lo, hi = ((-5.05, 0, -2), (-1.05, 12, 2)) if sign < 0 else ((1.05, 0, -2), (5.05, 12, 2))
        cuboid('under_' + side + 'leg', lo, hi, under='pants')
        cuboid(side + 'leg', lo, hi, layers[2], 'leg', .5, sign > 0)
        cuboid(side + 'boot', lo, hi, layers[1], 'leg', 1, sign > 0)
    cuboid('chest', (-4, 12, -2), (4, 24, 2), layers[1], 'body', 1)
    cuboid('waist', (-4, 12, -2), (4, 24, 2), layers[2], 'body', .48)
    cuboid('helmet', (-4, 24, -4), (4, 32, 4), layers[1], 'head', 1)
    return mesh, textures


def mipmapped(im, levels):
    """What the GPU's mip chain hands the sampler when this texture is small on screen."""
    out = im
    for _ in range(levels):
        out = out.resize((max(1, out.width // 2), max(1, out.height // 2)), Image.Resampling.BOX)
    return out


def main():
    layers = {n: Image.open(ARMOUR / f'ether_casing_layer_{n}.png').convert('RGBA') for n in (1, 2)}
    mesh, textures = build(layers)

    # scale, mip levels: 17 = a hand's reach away, 2.2 = a player at the far end of the field.
    shots = [(17, 0, '近看 · 1 格'), (9, 0, '4 格'), (5, 1, '10 格'), (2.4, 2, '25 格')]
    pics = []
    for scale, mips, label in shots:
        tex = dict(textures)
        if mips:
            tex = {k: mipmapped(v, mips) for k, v in textures.items()}
        pic = render(mesh, tex, size=(360, 660), yaw=-24, pitch=6, center=(0, 16, 0),
                     scale=scale, background=BACKGROUND)
        pic = pic.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        pics.append((pic, label, scale))

    w = sum(p.width for p, _, _ in pics)
    h = pics[0][0].height + 46
    sheet = Image.new('RGBA', (w, h), BACKGROUND)
    d = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(FONT, 19)
    fs = ImageFont.truetype(FONT, 15)
    x = 0
    for pic, label, scale in pics:
        sheet.alpha_composite(pic, (x, 0))
        d.text((x + 14, h - 38), label, font=f, fill='#12232e')
        d.text((x + 14, h - 17), f'渲染缩放 {scale}', font=fs, fill='#33505f')
        x += pic.width
    d.text((14, 8), '谐振石英护甲 · 实装贴图 · 皮肤人偶', font=f, fill='#12232e')
    path = HERE / 'shipped_preview.png'
    sheet.save(path)
    print(path)


if __name__ == '__main__':
    main()
