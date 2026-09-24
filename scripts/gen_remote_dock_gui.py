#!/usr/bin/env python3
"""Generate the Distant Dock's own Create-style panel chrome.

The interactive controls are deliberately *not* painted here. DockScreen renders Create's own
Station textbox and Worldshaper scroll-input frames at runtime, so their affordances stay visually
identical to Create.

The panel deliberately returns to Create's normal Package Port palette instead of tinting the whole
screen with Ether colours. Distant Stock identity comes from layout/content; the surrounding chrome
stays familiar to Create players.

Usage: python3 scripts/gen_remote_dock_gui.py
Output: src/main/resources/assets/distantstock/textures/gui/remote_dock.png
"""

from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/remote_dock.png"

W = 220
HEADER_H = 18
BODY_H = 52
FOOTER_H = 48
H = HEADER_H + BODY_H + FOOTER_H

BLACK = (0, 0, 0, 255)
DEEP = (68, 72, 90, 255)
# Create Package Port title blue.
HEADER = (104, 132, 159, 255)
HEADER_HI = (168, 196, 223, 255)
# Create's standard grey work-surface values.
BODY = (198, 198, 198, 255)
BODY_ALT = (163, 163, 163, 255)
FOOTER = (198, 198, 198, 255)
FOOTER_HI = (255, 255, 255, 255)


def bevel_rect(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], fill, hi, lo) -> None:
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=BLACK)
    draw.rectangle((x0 + 2, y0 + 2, x1 - 2, y1 - 2), fill=fill)
    draw.line((x0 + 2, y0 + 2, x1 - 2, y0 + 2), fill=hi)
    draw.line((x0 + 2, y0 + 2, x0 + 2, y1 - 2), fill=hi)
    draw.line((x0 + 2, y1 - 2, x1 - 2, y1 - 2), fill=lo)
    draw.line((x1 - 2, y0 + 2, x1 - 2, y1 - 2), fill=lo)


def main() -> None:
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)

    # Header: no Frogport "eyes" or animal silhouette; just Create's familiar blue machine chrome.
    bevel_rect(draw, (0, 0, W - 1, HEADER_H - 1), HEADER, HEADER_HI, DEEP)

    # Body: clean solid Create-style work surface, closer to the Drone Port family than Frogport's
    # checker treatment. Depth comes from the bevel and control frames.
    y0 = HEADER_H
    draw.rectangle((0, y0, W - 1, y0 + BODY_H - 1), fill=BLACK)
    draw.rectangle((2, y0 + 2, W - 3, y0 + BODY_H - 3), fill=BODY)
    draw.line((2, y0 + 2, W - 3, y0 + 2), fill=(255, 255, 255, 255))
    draw.line((2, y0 + BODY_H - 3, W - 3, y0 + BODY_H - 3), fill=DEEP)

    # Footer/control deck: standard light-grey Create panel, separated by the ordinary bevel.
    fy = HEADER_H + BODY_H
    bevel_rect(draw, (0, fy, W - 1, H - 1), FOOTER, FOOTER_HI, DEEP)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    im.save(OUT)
    print(f"{OUT.relative_to(ROOT)} {im.size}")


if __name__ == "__main__":
    main()
