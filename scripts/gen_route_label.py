#!/usr/bin/env python3
"""Redraw the requester screen's route plate as a plate, not a crop of one.

The sprite this replaces was lifted out of Create's stock-keeper sheet by hand, and it showed:
the left end still carried a few columns of the widget that had been next to it over there — a
half-cut hook and two grey blobs — which read exactly as what it was, a piece of a screenshot
pasted onto our screen. The user reported it as "边框都没去除干净".

The colours below are sampled from the undisturbed part of that sprite, so the plate still
matches the sheet it sits on; only the shape is redrawn, as one rectangle with a frame that runs
all the way round it. The label goes at x=16 and the destination field starts at x=60, both
inside the field band, which is why the frame is drawn to the edges and nothing else is.

**WIDTH is 182, not 194**, and that is the second half of the same complaint. The window's content
column — the item slots and the search bar — is CreateSheets.BG, 182 wide, drawn at x+22. A 194-wide
plate drawn at x+8 lined up with nothing: it hung fourteen pixels off the left of every other row
and twelve pixels over the right, which is what "the textures are all misaligned" was about. The
band's own structure is unchanged — it is the same strip of the sheet, cut to the right length.

Run from anywhere: python3 scripts/gen_route_label.py
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/distantstock/textures/gui/route_label.png'

WIDTH, HEIGHT = 182, 26

EDGE = (179, 187, 191, 255)          # the outer grey
TOP_EDGE = (186, 194, 198, 255)      # its lit first row
LINE = (0, 0, 0, 255)                # the black outline
BROWN = (62, 40, 18, 255)            # the wooden band
FIELD = (237, 245, 249, 255)         # the recessed field
BEVEL = (217, 225, 229, 255)         # one row of shading in it


def main() -> None:
    image = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
    px = image.load()

    for x in range(WIDTH):
        px[x, 0] = TOP_EDGE
        px[x, 1] = LINE
        px[x, 2] = BROWN
        px[x, 3] = EDGE
        px[x, 4] = EDGE
        px[x, 5] = LINE
        px[x, 22] = LINE
        px[x, 23] = BROWN
        px[x, 24] = BROWN
        px[x, 25] = EDGE
    for y in range(6, 22):
        px[0, y] = EDGE
        px[1, y] = LINE
        px[WIDTH - 2, y] = LINE
        px[WIDTH - 1, y] = EDGE
        for x in range(2, WIDTH - 2):
            px[x, y] = BEVEL if y in (7, 20) else FIELD

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f'wrote {OUT.relative_to(ROOT)} ({WIDTH}x{HEIGHT})')


if __name__ == '__main__':
    main()
