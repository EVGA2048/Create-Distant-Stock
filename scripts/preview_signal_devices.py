#!/usr/bin/env python3
"""Render actual signal-device and corrected request-desk models."""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from preview_block_art import render

OUT = ROOT / "build/art/signal-devices-0.3.7.png"


def main():
    sheet = Image.new("RGBA", (1100, 660), "#e8e5dc")
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 18), "DISTANT STOCK — BRASS SIGNAL SYSTEM", fill="#343b3b")
    draw.text((24, 42), "Actual model JSON + 16 px textures", fill="#687171")

    cells = [
        ("BRASS LAMP / OFF", "distantstock_concept:block/brass_signal_lamp", 30, 55),
        ("BRASS LAMP / GREEN", "distantstock_concept:block/brass_signal_lamp_green", 30, 55),
        ("BRASS LAMP / RED", "distantstock_concept:block/brass_signal_lamp_red", 30, 55),
        ("LAMP SIGNAL LINK", "distantstock_concept:block/lamp_signal_link", 30, 48),
    ]
    for index, (label, model, yaw, pitch) in enumerate(cells):
        x = 20 + index * 270
        draw.text((x, 82), label, fill="#454d4c")
        image = render(model, yaw, pitch, size=(250, 235), scale=16)
        sheet.alpha_composite(image, (x - 8, 100))

    draw.line((24, 350, 1076, 350), fill="#b8b6ae", width=1)
    draw.text((24, 372), "REMOTE REQUEST DESK — RIGHT SIDE UV ROTATED 180 DEGREES", fill="#454d4c")
    gauge = render("distantstock:block/gauge", 90, 25, size=(500, 250), scale=11)
    sheet.alpha_composite(gauge, (15, 392))

    draw.text((560, 390), "FOUR INPUTS", fill="#454d4c")
    draw.text((745, 405), "RED", fill="#713128")
    draw.text((628, 485), "CYAN", fill="#356b73")
    draw.text((865, 485), "ORANGE", fill="#8a5a2c")
    draw.text((738, 575), "GREEN", fill="#3d7045")
    draw.rectangle((728, 455, 808, 535), fill="#4d4535", outline="#8f7a4e", width=4)
    draw.rectangle((746, 473, 790, 517), fill="#303634", outline="#646b66", width=3)
    draw.line((768, 455, 768, 430), fill="#713128", width=8)
    draw.line((808, 495, 842, 495), fill="#8a5a2c", width=8)
    draw.line((768, 535, 768, 560), fill="#3d7045", width=8)
    draw.line((728, 495, 694, 495), fill="#356b73", width=8)
    draw.text((925, 472), "adjacent", fill="#687171")
    draw.text((925, 491), "brass lamp", fill="#687171")
    draw.line((812, 495, 912, 495), fill="#777a74", width=2)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
