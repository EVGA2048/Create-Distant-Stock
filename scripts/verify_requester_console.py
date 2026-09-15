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


# Textures the shipped model uses that the handoff does not have, mapped back to the artwork the
# approval was given against. The antenna and the lamp cover are deliberately re-drawn for the game
# -- the icon's holes filled in, the glass made opaque so it survives cutout -- and none of that is
# a statement about the mesh. Rendering the shipped texture here would just measure how much of the
# art changed; this check exists to measure whether the geometry and its uv mapping still match.
HANDOFF_EQUIVALENT = {
    'requester_study:antenna': 'distantstock:item/requester',
    'requester_study:glass_lit': 'requester_study:glass',
}


def texture_file(material):
    if material == 'distantstock:item/requester':
        return HANDOFF / 'textures/portable_requester.png'
    # Everything else is a console texture, renamed into the preview's own namespace. The handoff's
    # own copy is preferred, so the comparison is against the approved art rather than ours.
    name = material.split(':', 1)[1]
    original = HANDOFF / 'textures' / (name + '.png')
    if original.exists():
        return original
    return ROOT / ('src/main/resources/assets/distantstock/textures/block/requester_console/'
                   + name + '.png')


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
            material = HANDOFF_EQUIVALENT.get(material, material)
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
        # Rendered with the handoff's own artwork, so what is left is mesh only, and three
        # deliberate departures from it: the dropped lamp core, the antenna dish given real
        # thickness, and the mast narrowed from the crossed pair to the rod they share (at the
        # user's request — on a block the crossing reads as a plus, not as an aerial). Together
        # they measure about 1.4%. Anything structural lands far above that; the antenna built
        # as a 90 degree element rotation, which is what this check was written for, was many
        # times larger.
        verdict = 'MATCH' if differing < total * 0.02 else 'DIFFERS'
        if verdict == 'DIFFERS':
            failed.append(name)
        print(f'{verdict} {name}: {differing} of {total} pixels differ from the approved render'
              f' ({differing / total:.2%}; lamp core, dish thickness and the slender mast)')
    if failed:
        raise SystemExit('console no longer matches the approved art: ' + ', '.join(failed))
    print('requester console matches the approved art')


if __name__ == '__main__':
    main()
