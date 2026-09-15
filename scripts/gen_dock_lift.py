#!/usr/bin/env python3
"""Draw the dock lift's deck plate.

The deck is the packager's own grate, cut out of `create:block/packager_details` and placed in
the top-left of its own 16x16 sprite. That placement is not arbitrary: `DockParcelMotion.faceUv`
gives every face one turn of the sprite per block measured from its own low corner, so the deck —
twelve units across — reads exactly the twelve units of grate at 0..12, and the one-unit lips read
a one-unit sliver of its frame, which is a flat dark edge. A plate drawn to fill a whole 16x16
sprite would have been sampled three-quarters across and come out cut off.

The rest of the sprite repeats the grate's frame colour so nothing samples an unset pixel.
"""
from __future__ import annotations

import io
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
BLOCK = ROOT / "src/main/resources/assets/distantstock/textures/block"
CREATE = (Path.home() / "Documents/minecraft_launcher/.minecraft/versions"
          / "ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar")

# The grate inside packager_details.png, measured off the texture: a dark frame around a 3x3 grid.
GRATE_BOX = (2, 2, 14, 14)
GRATE_SIZE = GRATE_BOX[2] - GRATE_BOX[0]


def plate() -> Image.Image:
    with zipfile.ZipFile(CREATE) as jar:
        data = jar.read("assets/create/textures/block/packager_details.png")
    details = Image.open(io.BytesIO(data)).convert("RGBA")
    grate = details.crop(GRATE_BOX)

    # The frame colour, taken from the grate's own corner so the fill matches whatever the
    # packager art actually uses rather than a guess at it.
    frame = grate.getpixel((0, 0))
    out = Image.new("RGBA", (16, 16), frame)
    out.paste(grate, (0, 0))
    return out


def main():
    BLOCK.mkdir(parents=True, exist_ok=True)
    out = BLOCK / "dock_cap.png"
    plate().save(out)
    print(f"{out.relative_to(ROOT)}: 16x16, {GRATE_SIZE}x{GRATE_SIZE} packager grate at the origin")


if __name__ == "__main__":
    main()
