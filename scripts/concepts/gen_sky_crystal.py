#!/usr/bin/env python3
"""A sky-blue crystal item drawn in create's rose-quartz idiom.

Concept only -- nothing in the mod reads this yet.

What create's rose_quartz actually does, read off the 16x16 pixel by pixel:

  * The lit (upper-left) silhouette edge is a thin BODY-tone rim, not a black outline.
    The black outline runs down the right side and around the bottom only.
  * Immediately inside that rim a saturated vein runs nearly the full height. It is the
    one feature that keeps the sprite alive; lose it and the form goes to mush.
  * One big pale plane sits to the right of the vein. The far side is a dark facet broken
    up by mid tones, so it reads as a shaded plane rather than a silhouette blob.
  * The darks barely move in hue (334 -> 341 deg) and buy their depth with saturation.
    The lights sweep 57 deg toward warm (350 -> 2 -> 22 -> 35) and drain 64% -> 12%.
  * About 54% of the opaque pixels are in the dark family. Getting that split wrong is
    what makes a crystal read as a washed-out blob.
  * Hard edges only, fully opaque or fully clear, no anti-aliasing.

The palette is the part that does not transfer literally. Matching create's luminance ramp
on a blue hue forces `S` (luminance 166) down to a desaturated steel blue, because blue is
intrinsically dark -- there is no vivid blue at that luminance. So B and S are allowed to
sit 14-18 points brighter than create's, and pay for it with chroma instead. Everything
else lines up: 49/79/104/138/184/220/241/255 against create's 49/81/103/124/166/220/243/255.

The silhouette comes from shearing create's own role grid rather than from a hand drawing.
Every proportion create chose survives untouched and only the lean changes. Hand drawing
was tried first and kept reading as a leaf.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.dont_write_bytecode = True

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/art/concepts'

# The 14 content rows of create's assets/create/textures/item/rose_quartz.png, rewritten
# as roles. One character per pixel naming what that pixel DOES rather than what colour it
# is, which is what makes the palette swappable. Transcribed verbatim -- a hand-shifted
# copy silently shears the row-to-row alignment and the shape stops being create's.
#
#   D outline   M dark body   m mid   B saturated bright   S light   P pale   C palest   W specular
ROLE_GRID = (
    '.........MMM....',
    '....MMM.MPCCD...',
    '...MCCPMPCCCD...',
    '...MCCCPMCCMD...',
    '...MWCCCPDMMD...',
    '...MSWCCMDMmD...',
    '...MSBWMmMMmD...',
    '...MSBPmMCPmD...',
    '...MSSPMCCCDD...',
    '...DmSPMCCMD....',
    '....DmPMSPMD....',
    '.....DSMBPMD....',
    '......DMBSD.....',
    '.......DDD......',
)

ORDER = 'DMmBSPCW'

# Luminance of create's own ramp, for comparison: 49 81 103 124 166 220 243 255
PALETTE = ('#1D3263', '#2A5488', '#3171B0', '#3D99DA', '#6DC8F2', '#AFE6FA', '#DDF5FE', '#FFFFFF')
CREATE_PALETTE = ('#6B1F3B', '#9D3964', '#CB4872', '#F45974', '#FF8E8A', '#FFD5BD', '#FFF2E0', '#FFFFFF')

BG = (36, 36, 44, 255)
CHECK = ((92, 92, 104), (74, 74, 86))


def grid(rows):
    """Centre the 14 content rows in a 16x16 field, as create's own file has them."""
    rows = list(rows)
    pad = (16 - len(rows)) // 2
    top, bottom = pad, 16 - len(rows) - pad
    return ['.' * 16] * top + [r.ljust(16, '.')[:16] for r in rows] + ['.' * 16] * bottom


def roles_from_create():
    return grid(ROLE_GRID)


def shear(rows, per_row):
    """Shift each row horizontally, pivoting on the vertical centre of mass."""
    filled = [y for y, row in enumerate(rows) if row.strip('.')]
    pivot = sum(filled) / len(filled)
    out = []
    for y, row in enumerate(rows):
        shift = round((y - pivot) * per_row)
        shifted = ['.'] * 16
        for x, ch in enumerate(row):
            if ch != '.' and 0 <= x + shift < 16:
                shifted[x + shift] = ch
        out.append(''.join(shifted))
    return out


def mirror(rows):
    return [row[::-1] for row in rows]


def render(rows, palette=PALETTE):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '.':
                continue
            hexv = palette[ORDER.index(ch)]
            img.putpixel((x, y), tuple(int(hexv[i:i + 2], 16) for i in (1, 3, 5)) + (255,))
    return img


def plate(img, scale):
    """Upscale on a checkerboard so the transparent field reads as field, not as colour."""
    out = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = img.getpixel((x, y))
            out.putpixel((x, y), CHECK[(x // 4 + y // 4) % 2] + (255,) if c[3] == 0 else c)
    return out.resize((16 * scale, 16 * scale), Image.NEAREST)


def contact_sheet(variants, path):
    big, small = 10, 1
    pad, gap = 14, 16
    tw, th = 16 * big, 16 * big + 10 + 16 * small
    sheet = Image.new('RGBA', (gap + len(variants) * (tw + gap), th + pad * 2), BG)
    for i, (_, img) in enumerate(variants):
        x = gap + i * (tw + gap)
        sheet.paste(plate(img, big), (x, pad))
        y = pad + 16 * big + 10
        for k in range(4):
            sheet.paste(plate(img, small), (x + k * (16 + 4), y))
    sheet.save(path)
    return path


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    base = roles_from_create()

    # The role grid goes back through create's own ramp unchanged; if the transcription is
    # faithful this is create's texture, pixel for pixel. Compare against the jar when it
    # happens to be reachable, otherwise just the census.
    control = render(base, CREATE_PALETTE)
    opaque = sum(1 for p in control.getdata() if p[3])
    darks = {tuple(int(CREATE_PALETTE[i][j:j + 2], 16) for j in (1, 3, 5)) + (255,) for i in (0, 1, 2)}
    print(f'control rendered through create\'s ramp: {opaque} opaque px, '
          f'dark family {sum(1 for p in control.getdata() if p in darks) * 100 // opaque}%'
          f'   (the real file: 113 px, 54%)')
    for jar in sorted((Path.home() / 'Documents/minecraft_launcher/.minecraft/versions').glob(
            '*/mods/create-1.21.1-*.jar'))[:1]:
        import zipfile
        with zipfile.ZipFile(jar) as z:
            import io
            real = Image.open(io.BytesIO(z.read('assets/create/textures/item/rose_quartz.png'))).convert('RGBA')
        print(f'  vs {jar.name}: {"identical" if list(real.getdata()) == list(control.getdata()) else "DIFFERS"}')
        break

    variants = [('lean-right', render(shear(base, 0.30))),
                ('lean-left', render(mirror(shear(mirror(base), 0.30)))),
                ('upright', render(base))]
    sheet = contact_sheet([('create', control)] + variants, OUT / 'sky_crystal_preview.png')
    for name, img in variants:
        img.save(OUT / f'sky_crystal_{name}.png')
    print(f'wrote {len(variants)} textures + {sheet.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
