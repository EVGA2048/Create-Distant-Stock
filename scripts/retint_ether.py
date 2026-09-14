#!/usr/bin/env python3
"""Push the ether palette to a white base with a faint clear-sky blue.

Reported from play: the fluid read as too dark. Two things made it so. The palette sat
around #507B9B.. #699DBC, and the textures carried alpha 180, where vanilla's fluid
textures are fully opaque and take their translucency from the render type instead. Low
alpha let the ground show through and drag the whole pool darker.

Every shade is remapped by exact colour, so the bucket's iron, the bottle's glass and the
cap are untouched. The order of the ramp is preserved, so the ripples and the flowing
streaks still read.
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
FLUID = ROOT / 'src/main/resources/assets/distantstock/textures/fluid'
ITEM = ROOT / 'src/main/resources/assets/distantstock/textures/item'

# Old shade -> new shade. The first six are the fluid ramp; everything drawn with it, the
# bucket and the bottle included, follows. The last two only ever appear in the bottle.
RAMP = {
    (0x50, 0x7B, 0x9B): (0xC6, 0xE3, 0xF1),
    (0x69, 0x9D, 0xBC): (0xDA, 0xED, 0xF6),
    (0x8D, 0xBB, 0xD6): (0xEA, 0xF5, 0xFA),
    (0xB6, 0xD8, 0xE8): (0xF4, 0xFA, 0xFD),
    (0xD9, 0xEB, 0xEE): (0xF9, 0xFC, 0xFE),
    (0xF3, 0xF6, 0xE9): (0xFF, 0xFF, 0xFF),
    (0x5D, 0x8F, 0xC2): (0xCF, 0xE7, 0xF5),
    (0xB3, 0xCF, 0xEC): (0xF0, 0xF7, 0xFC),
}

# Below 255 on purpose: ether is meant to read as a thin condensate. Note this only shows once the
# fluid renders translucent at all. Vanilla picks a fluid's render layer from a table listing water
# and nothing else, and everything past that falls through to solid, which ignores alpha outright.
# The client setup registers ether as translucent; without it this number does nothing.
FLUID_ALPHA = 190

FLUID_FILES = ('ether_still.png', 'ether_flow.png')
ITEM_FILES = ('ether_bucket.png', 'ether_bottle.png')


def retint(path, alpha=None):
    image = Image.open(path).convert('RGBA')
    out = Image.new('RGBA', image.size)
    changed = 0
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = image.getpixel((x, y))
            if (r, g, b) in RAMP:
                r, g, b = RAMP[(r, g, b)]
                changed += 1
            out.putpixel((x, y), (r, g, b, a if alpha is None else alpha))
    out.save(path)
    return changed, image.width * image.height


def main():
    for name in FLUID_FILES:
        changed, total = retint(FLUID / name, alpha=FLUID_ALPHA)
        print(f'{name}: {changed}/{total} pixels retinted, alpha -> {FLUID_ALPHA}')
    for name in ITEM_FILES:
        changed, total = retint(ITEM / name)
        print(f'{name}: {changed}/{total} pixels retinted')


if __name__ == '__main__':
    main()
