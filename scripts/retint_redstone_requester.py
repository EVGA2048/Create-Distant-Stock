#!/usr/bin/env python3
"""Recolour Create's redstone requester into the distant-stock light-blue family.

The machine is a variant, not a new shape: it keeps Create's model, its two-part body, its
panel and its power light, and only changes the palette. Everything is remapped by exact
colour, so the panel, the body and the indicator stay separate and nothing is guessed from
luminance alone.

Three families go in, three come out:

* the dark blue-grey casing becomes the pale blue the distant casing and packager already
  use, dark enough at the bottom of the ramp that the shading still reads on a light block;
* the olive industrial bits and the warm beige panel become the packager's pale grey, which
  is the same relationship the pale packager variant already has to its teal one;
* the power light keeps its meaning and changes its colour: a red lamp on a blue machine
  reads as a fault, so lit is the ether cyan and unlit is the deep teal the docks use when
  they are idle. Lit and unlit stay as far apart as they were.

Run from anywhere: python3 scripts/retint_redstone_requester.py
"""
from pathlib import Path
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/distantstock/textures/block'
CREATE_JAR = Path.home() / 'Documents/minecraft_launcher/.minecraft/mods/create-1.21.1-6.0.10.jar'

# Body: Create's nine dark blue-greys, darkest first, onto the pale blue ramp.
BODY = {
    (43, 43, 49): (108, 140, 158),
    (49, 48, 55): (124, 157, 175),
    (53, 52, 61): (131, 168, 189),
    (57, 56, 66): (143, 179, 199),
    (65, 65, 80): (167, 196, 211),
    (68, 72, 90): (182, 209, 222),
    (70, 78, 97): (204, 223, 232),
    (73, 86, 105): (218, 234, 241),
    (75, 94, 113): (234, 242, 244),
    (86, 107, 129): (245, 252, 253),
}

# Olive industrial steel and the beige panel. Both stay in the blue family rather than going
# grey: the first pass turned the whole front panel grey, which made a light-blue machine with a
# grey face. The steel sits below the body in value so the recess reads, and the panel sits above
# it, which is the same three-step relationship the original had.
PALE = {
    (48, 58, 56): (92, 120, 136),
    (65, 72, 68): (108, 138, 155),
    (83, 87, 81): (122, 150, 166),
    (105, 105, 98): (137, 164, 178),
    (132, 130, 119): (168, 196, 210),
    (159, 155, 147): (190, 212, 224),
    (188, 182, 174): (212, 229, 238),
}

# The power light: red out, ether cyan in; the dark reds become the dock's idle teal.
LIGHT_ON = {
    (102, 0, 0): (54, 110, 121),
    (151, 0, 0): (82, 161, 177),
    (174, 0, 0): (120, 182, 194),
    (205, 0, 0): (163, 215, 226),
}
LIGHT_OFF = {
    (59, 0, 0): (28, 57, 63),
    (70, 0, 0): (32, 65, 72),
    (80, 1, 1): (37, 76, 83),
    (88, 1, 1): (43, 88, 96),
}

RAMP = {**BODY, **PALE, **LIGHT_ON, **LIGHT_OFF}

# Create's name -> ours. The powered pair keeps its own file so the block can swap textures.
FILES = {
    'redstone_requester': 'remote_redstone_requester',
    'redstone_requester_powered': 'remote_redstone_requester_powered',
    'redstone_requester_unpowered': 'remote_redstone_requester_unpowered',
}


def read_from_create(name):
    with zipfile.ZipFile(CREATE_JAR) as jar:
        entry = 'assets/create/textures/block/%s.png' % name
        with jar.open(entry) as handle:
            image = Image.open(handle)
            image.load()
            return image.convert('RGBA')


def retint(image):
    out = Image.new('RGBA', image.size)
    unknown = {}
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = image.getpixel((x, y))
            if a == 0:
                out.putpixel((x, y), (0, 0, 0, 0))
                continue
            if (r, g, b) not in RAMP:
                unknown[(r, g, b)] = unknown.get((r, g, b), 0) + 1
                out.putpixel((x, y), (r, g, b, a))
                continue
            nr, ng, nb = RAMP[(r, g, b)]
            out.putpixel((x, y), (nr, ng, nb, a))
    return out, unknown


def main():
    if not CREATE_JAR.exists():
        raise SystemExit('Create jar not found: %s' % CREATE_JAR)
    OUT.mkdir(parents=True, exist_ok=True)
    for source, target in FILES.items():
        image = read_from_create(source)
        out, unknown = retint(image)
        path = OUT / (target + '.png')
        out.save(path)
        note = '' if not unknown else '  UNMAPPED %s' % sorted(unknown)
        print('%-42s %sx%s -> %s%s' % (target + '.png', image.width, image.height, path.name, note))


if __name__ == '__main__':
    main()
