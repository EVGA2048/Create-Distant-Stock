#!/usr/bin/env python3
"""Generate the 13 cloak-phase wing textures from the shipped wing texture."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ARMOR = ROOT / 'src/main/resources/assets/distantstock/textures/models/armor'
SOURCE = ARMOR / 'ether_casing_wings.png'

base = Image.open(SOURCE).convert('RGBA')

for phase in range(13):
    out = base.copy()
    pixels = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            if phase == 12:
                pixels[x, y] = (r, g, b, 0)
                continue

            p = phase / 12.0
            cyan_mix = min(0.42, p * 0.55)
            tr, tg, tb = 110, 210, 200
            nr = int(r * (1 - cyan_mix) + tr * cyan_mix)
            ng = int(g * (1 - cyan_mix) + tg * cyan_mix)
            nb = int(b * (1 - cyan_mix) + tb * cyan_mix)

            threshold = ((x * 37 + y * 19 + x * y * 3) % 97) / 97.0
            removed = threshold < max(0.0, (p - 0.28) / 0.72)
            alpha = 0 if removed else int(a * (1.0 - p * 0.58))
            pixels[x, y] = (nr, ng, nb, alpha)

    out.save(ARMOR / f'ether_casing_wings_phase_{phase}.png')

print(f'generated 13 wing phases from {SOURCE}')

