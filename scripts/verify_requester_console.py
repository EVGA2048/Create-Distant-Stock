#!/usr/bin/env python3
"""Re-render the shipped requester console and diff it against the approved art.

This is the check that keeps the console honest. Everything the game sees about a face,
its UV rectangle and the in-plane rotation that rectangle needs, is decided by vanilla's
own tables (FaceInfo for vertex order, BlockFaceUV for which rectangle corner each vertex
reads). Those tables disagree with the offline renderer this project uses for previews,
so a mesh built the preview way proves nothing about the game. Here the mesh is built the
vanilla way and rendered with the handoff's own renderer, which is then compared against
the images the user actually approved.

Run it after changing the generator, the model, or the handoff. It has already earned its
keep once: it caught an antenna built as a 90 degree element rotation, which block models
do not allow, and it is what proves the baked UV rotation is the right one.
"""
import importlib.util
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / 'docs/design/requester-console-approved-v5'
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(HANDOFF / 'renderer'))

import preview_block_art as bake  # noqa: E402
from render_scene import render as scene_render  # noqa: E402

spec = importlib.util.spec_from_file_location('gen', ROOT / 'scripts/gen_requester_console.py')
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

# The triangle winding the handoff renderer culls by, which is opposite to FaceInfo's.
# Points are emitted in this order so faces survive culling; the UV each point receives
# still comes from the vanilla pairing, so the picture matches the game.
WINDING = {
    'north': lambda a, b: [(b[0], b[1], a[2]), (a[0], b[1], a[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2])],
    'south': lambda a, b: [(a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], a[1], b[2]), (a[0], a[1], b[2])],
    'west': lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (a[0], a[1], b[2]), (a[0], a[1], a[2])],
    'east': lambda a, b: [(b[0], b[1], b[2]), (b[0], b[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    'up': lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], b[1], b[2]), (a[0], b[1], b[2])],
    'down': lambda a, b: [(a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
}

# name -> (size, yaw, pitch, scale, pivot), the camera each approved image was rendered with.
VIEWS = {
    'requester_front': ((740, 810), 24, 28, 29, (8, 12.5, 8)),
    'requester_alt': ((620, 640), -28, 38, 23, (8, 12.5, 8)),
}


def texture_file(material):
    if material == 'distantstock:item/requester':
        return HANDOFF / 'textures/portable_requester.png'
    # Everything else is a console texture, renamed into the preview's own namespace.
    return ROOT / ('src/main/resources/assets/distantstock/textures/block/requester_console/'
                   + material.split(':', 1)[1] + '.png')


def build_mesh():
    # Deliberately the unmirrored build. The shipped model is this flipped about x, because
    # the handoff's own previews are mirrored relative to the game: its camera at yaw 0 puts
    # +x on the right, while Minecraft looking along +z with +y up has right = -x. Comparing
    # the shipped file here would therefore fail by construction. The flip itself is checked
    # in gen_requester_console.mirror_x, where yaw 0 / pitch 0 makes it an exact screen flip.
    model = gen.build(False, mirrored=False)
    mesh = []
    for element in model['elements']:
        for name, face in element['faces'].items():
            step = face.get('rotation', 0) // 90
            source = gen.vanilla_vertices(element['from'], element['to'], name)
            uv_at = {point: gen.corner_uv(face['uv'], (k + step) % 4)
                     for k, point in enumerate(source)}
            order = WINDING[name](element['from'], element['to'])
            reference = model['textures'][face['texture'].lstrip('#')]
            material = ('distantstock:item/requester' if reference == 'distantstock:item/requester'
                        else 'requester_study:' + reference.rsplit('/', 1)[1])
            mesh.append({
                'points': [list(bake.rotate(p, element.get('rotation'))) for p in order],
                'uv': [list(uv_at[p]) for p in order],
                'material': material,
                'group': 'fixed',
                'clamp': True,
            })
    return mesh


def main():
    mesh = build_mesh()
    tiles = {m: Image.open(texture_file(m)).convert('RGBA')
             for m in {f['material'] for f in mesh}}
    failed = []
    for name, (size, yaw, pitch, scale, pivot) in VIEWS.items():
        approved = Image.open(HANDOFF / f'{name}.png').convert('RGBA')
        rendered = scene_render(mesh, tiles, size=size, yaw=yaw, pitch=pitch,
                                center=pivot, scale=scale, background='#e9eeeb')
        a = np.asarray(approved).astype(int)
        b = np.asarray(rendered).astype(int)
        differing = int((np.abs(a - b).sum(axis=2) > 12).sum())
        total = a.shape[0] * a.shape[1]
        # Two intended differences, both understood. The dropped lamp core shows through the
        # translucent cover at about 0.2% of the frame. The antenna dish is now an opaque
        # square where the approved render draws a soft blob, because the handoff's renderer
        # ignores alpha and the game does not: the patch the dish used to read has holes in
        # it, and in game it came out as a shredded star. Together they measure 1.0%.
        # Anything structural lands far above that; the antenna built as a 90 degree element
        # rotation, which is what this check was written for, was many times larger.
        verdict = 'MATCH' if differing < total * 0.015 else 'DIFFERS'
        if verdict == 'DIFFERS':
            failed.append(name)
        print(f'{verdict} {name}: {differing} of {total} pixels differ from the approved render'
              f' ({differing / total:.2%}; lamp core ~0.2%, opaque antenna dish ~0.8%)')
    if failed:
        raise SystemExit('console no longer matches the approved art: ' + ', '.join(failed))
    print('requester console matches the approved art')


if __name__ == '__main__':
    main()
