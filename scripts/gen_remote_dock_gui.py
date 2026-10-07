#!/usr/bin/env python3
"""Generate the Distant Dock machine body in Create's Package Port visual language.

The title bar is Create's real FROGPORT_HEADER and controls are Create widgets at runtime. This
texture is only the 220x82 body: one continuous checker work area plus the lower control deck.
There are deliberately no separate boxed cards for each field.

Usage: python3 scripts/gen_remote_dock_gui.py
Output: src/main/resources/assets/distantstock/textures/gui/remote_dock.png
"""

from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/remote_dock.png"
HEADER_OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/remote_dock_header.png"

W = 220
H = 82
DECK_Y = 52
HEADER_H = 17

FRAME = (238, 238, 238, 255)
BLACK = (0, 0, 0, 255)
CHECK_A = (198, 198, 198, 255)
CHECK_B = (210, 210, 210, 255)
DECK = (198, 198, 198, 255)
DECK_HI = (255, 255, 255, 255)
DECK_LO = (85, 85, 85, 255)
HEADER_BLUE = (104, 132, 159, 255)
HEADER_HI = (168, 196, 223, 255)
HEADER_LO = (68, 72, 90, 255)

def main() -> None:
    # Flat Create-style title bar for the Distant Dock. Unlike Frogport's header it has no animal
    # eye caps; the bar runs flush from left to right.
    header = Image.new("RGBA", (W, HEADER_H), FRAME)
    hd = ImageDraw.Draw(header)
    hd.rectangle((1, 1, W - 2, HEADER_H - 2), fill=BLACK)
    hd.rectangle((2, 2, W - 3, HEADER_H - 3), fill=HEADER_BLUE)
    hd.line((3, 3, W - 4, 3), fill=HEADER_HI)
    hd.line((3, HEADER_H - 4, W - 4, HEADER_H - 4), fill=HEADER_LO)

    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)

    # One outer shell. No nested panel rectangles.
    draw.rectangle((0, 0, W - 1, H - 1), fill=FRAME)
    draw.rectangle((2, 0, W - 3, H - 2), fill=CHECK_A)

    # Fine checker work surface like Frogport/Bee Port, rather than a flat form background.
    for yy in range(2, DECK_Y):
        for xx in range(2, W - 2):
            if ((xx // 2) + (yy // 2)) & 1:
                draw.point((xx, yy), fill=CHECK_B)

    # Single lower deck: one black separator, then the parcel bay and confirm button are drawn by
    # DockScreen. This replaces the old unused full-width boxed footer.
    draw.line((1, DECK_Y, W - 2, DECK_Y), fill=BLACK)
    draw.line((2, DECK_Y + 1, W - 3, DECK_Y + 1), fill=DECK_HI)
    draw.rectangle((2, DECK_Y + 2, W - 3, H - 3), fill=DECK)
    draw.line((2, H - 3, W - 3, H - 3), fill=DECK_LO)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    im.save(OUT)
    header.save(HEADER_OUT)
    print(f"{OUT.relative_to(ROOT)} {im.size}")
    print(f"{HEADER_OUT.relative_to(ROOT)} {header.size}")


if __name__ == "__main__":
    main()
