#!/usr/bin/env python3
"""Render the current shipped Distant Stock models for README previews."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from preview_block_art import render
BG = '#e8e5dc'
OUT = ROOT / 'docs/preview'

def sheet(items, filename, columns=3, cell=(360,330)):
    width = columns * cell[0]
    rows = (len(items) + columns - 1) // columns
    image = Image.new('RGBA', (width, rows * cell[1]), BG)
    draw = ImageDraw.Draw(image)
    for i, (label, model, yaw, pitch, scale) in enumerate(items):
        x = (i % columns) * cell[0]
        y = (i // columns) * cell[1]
        draw.text((x + 18, y + 16), label, fill='#39413f')
        model_image = render('distantstock:block/' + model, yaw, pitch, size=(cell[0], cell[1] - 55), scale=scale)
        image.alpha_composite(model_image, (x, y + 42))
    image.save(OUT / filename)

OUT.mkdir(parents=True, exist_ok=True)
sheet([
    ('REMOTE DOCK / idle', 'dock', 30, 25, 12),
    ('REMOTE DOCK / linked', 'dock_lit', 30, 25, 12),
    ('REMOTE DOCK / parcel', 'dock_loaded_lit', 30, 25, 12),
    ('REQUEST DESK', 'gauge', 30, 30, 12),
    ('REMOTE MONITOR', 'monitor', 30, 25, 12),
    ('REMOTE PACKAGER', 'remote_packager', 30, 25, 12),
    ('SIGNAL PANEL', 'signal_panel', 30, 25, 12),
], 'machines-0.3.7.png')

colors = ['cyan', 'orange', 'red', 'green', 'white']
sheet([(f'{c.upper()} / off', f'{c}_indicator_lamp', 30, 45, 17) for c in colors] +
      [(f'{c.upper()} / lit', f'{c}_indicator_lamp_lit', 30, 45, 17) for c in colors],
      'indicator-lamps-0.3.7.png', columns=5, cell=(260,260))
print(OUT / 'machines-0.3.7.png')
print(OUT / 'indicator-lamps-0.3.7.png')
