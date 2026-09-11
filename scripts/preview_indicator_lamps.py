#!/usr/bin/env python3
"""Render the shipped lamp model and textures without launching Minecraft."""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from preview_block_art import render

OUT = ROOT / "build/art/indicator-lamps-0.3.7.png"
COLORS = [
    ("CYAN / 青", "cyan"),
    ("ORANGE / 橙", "orange"),
    ("RED / 红", "red"),
    ("GREEN / 绿", "green"),
    ("WHITE / 白", "white"),
]


def main():
    sheet = Image.new("RGBA", (1100, 620), "#e8e5dc")
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 18), "DISTANT STOCK — SINGLE 5 x 5 PIXEL INDICATOR LAMPS", fill="#343b3b")
    draw.text((24, 42), "Actual shipped model JSON + 16 px PNG textures", fill="#687171")
    for column, (label, color) in enumerate(COLORS):
        x = column * 220
        draw.text((x + 20, 78), label, fill="#3c4444")
        for row, (state, suffix) in enumerate((("NO SIGNAL", ""), ("REDSTONE ON", "_lit"))):
            y = 105 + row * 240
            draw.text((x + 20, y), state, fill="#5e6767")
            image = render(
                f"distantstock:block/{color}_indicator_lamp{suffix}",
                yaw=30,
                pitch=55,
                size=(220, 205),
                scale=17,
            )
            sheet.alpha_composite(image, (x, y + 10))
    draw.text((24, 590), "The same model rotates onto walls, floors and ceilings. Powered light level: 10.", fill="#687171")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
