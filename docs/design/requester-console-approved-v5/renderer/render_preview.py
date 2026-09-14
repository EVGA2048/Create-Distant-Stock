"""Reproduce the approved preview using only this handoff directory."""
import sys
sys.dont_write_bytecode = True
import json
from pathlib import Path
from PIL import Image
from render_scene import render

root = Path(__file__).resolve().parents[1]
mesh = json.loads((root / 'preview_mesh.json').read_text())
textures = {}
for ref in {face['material'] for face in mesh}:
    name = 'portable_requester' if ref == 'distantstock:item/requester' else ref.split(':', 1)[1]
    textures[ref] = Image.open(root / 'textures' / (name + '.png')).convert('RGBA')
out = root / 'verification'
out.mkdir(exist_ok=True)
for name, yaw, pitch, size, scale in (
    ('front', 24, 28, (740, 810), 29),
    ('alt', -28, 38, (620, 640), 23),
):
    result = render(mesh, textures, size=size, yaw=yaw, pitch=pitch,
                    center=(8, 12.5, 8), scale=scale, background='#e9eeeb')
    result.save(out / (name + '.png'))
    expected = Image.open(root / ('requester_front.png' if name == 'front' else 'requester_alt.png')).convert('RGBA')
    assert result.size == expected.size and result.tobytes() == expected.tobytes(), name + ' differs from approved preview'
    print(name + ': matches approved preview pixel-for-pixel')
