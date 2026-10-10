#!/usr/bin/env python3
"""The elytra as shipped, on vanilla's wing geometry and poses.

The wing is a flat plane: vanilla paints only the south face of the box, and the shape is the
texture's alpha and nothing else. So this renders the real file from src/main/resources through the
same box, which is the only way to see what the spar actually looks like once it is folded back
against the shoulder and again once it is spread.

Pass a jar as argv[1] to render that jar's texture instead, for a before-and-after.
"""
from __future__ import annotations

import io
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = next(a for a in HERE.parents if (a / 'scripts/concepts/render_scene.py').exists())
sys.path.insert(0, str(ROOT / 'scripts/concepts'))

from PIL import Image, ImageDraw, ImageFont
from render_scene import box, move, render

FONT = '/System/Library/Fonts/STHeiti Medium.ttc'
INSIDE = 'assets/distantstock/textures/models/armor/ether_casing_wings.png'

# ElytraModel: texOffs(22,0).addBox(-10,0,0, 10,20,2, new CubeDeformation(1.0)), then mirrored for
# the other side. Only the south face is painted, at (36,2) 10x20.
W, L, D = 10.0, 20.0, 2.0
SOUTH = (36, 2, 46, 22)


def wing(texture, glide, yaw, pitch, size=(400, 420)):
    faces = {name: 'clear' for name in ('north', 'west', 'east', 'up', 'down')}
    faces['south'] = 'wing'
    one = box((-W, -L, 0.0), (0.0, 0.0, D), 'clear', faces=faces)
    tex = {'wing': texture.crop(SOUTH),
           'clear': Image.new('RGBA', (8, 8), (0, 0, 0, 0))}

    mesh = []
    # setupAnim: xRot eases 15deg -> 22 in flight; zRot swings -15deg -> -62.
    xrot = 15.0 + 7.0 * glide
    zrot = 15.0 + 47.0 * glide
    for sign in (-1, 1):
        part = [{**f, 'points': [[-p[0], p[1], p[2]] for p in f['points']]} for f in one] if sign > 0 else one
        mesh += move(part, turns=[('z', sign * zrot, (0, 0, 0)), ('x', xrot, (0, 0, 0))],
                     offset=(sign * 5.0, 0, 0))
    return render(mesh, tex, size=size, yaw=yaw, pitch=pitch, center=(0, -10, 0), scale=10,
                  background='#8fb7d4')


def main():
    if len(sys.argv) > 1:
        with zipfile.ZipFile(sys.argv[1]) as jar:
            texture = Image.open(io.BytesIO(jar.read(INSIDE))).convert('RGBA')
        label = Path(sys.argv[1]).name.split('-')[3]
    else:
        texture = Image.open(ROOT / 'src/main/resources' / INSIDE).convert('RGBA')
        label = '0.3.27'

    shots = [('收起 · 背面', 0.0, 168, 12), ('滑翔 · 背面', 1.0, 168, 12), ('滑翔 · 侧后', 1.0, 210, 16)]
    pics = [(wing(texture, g, y, p), cap) for cap, g, y, p in shots]

    w = sum(p.width for p, _ in pics)
    h = pics[0][0].height + 44
    sheet = Image.new('RGBA', (w, h), '#8fb7d4')
    d = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(FONT, 18)
    x = 0
    for pic, cap in pics:
        sheet.alpha_composite(pic, (x, 0))
        d.text((x + 14, h - 32), cap, font=f, fill='#12232e')
        x += pic.width
    d.text((14, 8), f'谐振鞘翅 · 实装贴图 {label} · 原版 ElytraModel 几何与姿态', font=f, fill='#12232e')
    path = HERE / f'shipped_{label}.png'
    sheet.save(path)
    print(path)


if __name__ == '__main__':
    main()
