#!/usr/bin/env python3
"""Distant Stock dock reworked as a Create-styled parcel berth (offline concept).

Single placed block, x/z -8..8, y 0..16, front north. Uses Create andesite
casing / andesite / industrial iron textures via create_materials; cyan only for
the berth lamp and the transfer beam. No round gauge, no flat control panel.
"""
from __future__ import annotations
import argparse
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
from PIL import Image, ImageDraw
from render_scene import BG, INK, MUTED, box, move, render, header, text, rotation
from create_materials import MATERIALS, material

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / 'docs/design/concepts/dock'
OUT = ROOT / 'build/art/concepts'

# Structural fills come from Create; cyan/glass/beam are the only painted/glow
# accents, kept identical to the signal tower palette so the set reads together.
STRUCTURAL = ('andesite', 'casing', 'cast_white', 'iron', 'blue_gray', 'wood')
PALETTE = {
    'cyan': ('#63BBD0', '#D9FAFF', '#2585A5'),
    'glass': ('#BCDDEB', '#E2F9FF', '#81BFD4'),
    'beam_core': ('#DEF9FF', '#F2FDFF', '#C6EDF7'),
    'beam_outer': ('#63BBD0', '#63BBD0', '#63BBD0'),
}
ALPHA = {'glass': 65, 'beam_outer': 35, 'beam_core': 165}
STATES = ('idle', 'online', 'loaded')
BOUNDS = [[-8, 0, -8], [8, 16, 8]]
FULL_UV = [[0, 0], [16, 0], [16, 16], [0, 16]]


def casing_mesh():
    """The inset machine casing as an isolated cube with full-border UVs."""
    faces = box((-7, 3, -7), (7, 13, 7), 'casing', group='casing')
    for face in faces:
        face.update(uv=[p[:] for p in FULL_UV], clamp=True)
    return faces


def textures():
    mats = {}
    for name in STRUCTURAL:
        mats[name] = material(name)
    for name, colors in PALETTE.items():
        im = Image.new('RGBA', (16, 16), colors[0])
        d = ImageDraw.Draw(im)
        if name == 'cyan':
            d.polygon(((0, 0), (5, 0), (9, 15), (5, 15)), fill=colors[1])
        if name in ALPHA:
            im.putalpha(ALPHA[name])
        mats[name] = im
    return mats


def dock_mesh(state='idle'):
    """Standalone north-facing berth; state in idle / online / loaded."""
    if state not in STATES:
        raise ValueError(f'Unknown dock state: {state!r}')
    g = []

    def b(lo, hi, m):
        faces = box(lo, hi, m)
        if m == 'casing':
            # Preserve all four borders instead of cropping to model dimensions.
            for face in faces:
                face.update(uv=[p[:] for p in FULL_UV], clamp=True)
        g.extend(faces)

    # Plinth: rough andesite with an iron base skirt.
    b((-8, 0, -8), (8, 3, 8), 'andesite')
    b((-8, 0, -8), (8, 1, 8), 'iron')

    # Rear machine body, set back so the berth mouth reads as a real cavity.
    b((-7, 3, -3), (7, 13, 7), 'casing')
    # Front frame: two pillars + lintel + sill enclose a genuine empty mouth.
    b((-7, 3, -7), (-4, 13, -3), 'casing')       # left pillar
    b((4, 3, -7), (7, 13, -3), 'casing')         # right pillar
    b((-4, 11, -7), (4, 13, -3), 'casing')       # lintel
    b((-4, 3, -7), (4, 4.2, -3), 'casing')       # sill

    # Cast stone cap, two steps.
    b((-8, 13, -8), (8, 15, 8), 'cast_white')
    b((-7, 15, -7), (7, 16, 7), 'cast_white')

    # Beam emitter base, recessed at the bottom center of the empty mouth.
    b((-2, 4.2, -6.8), (2, 5.2, -6.4), 'iron')
    b((-1, 5.2, -6.9), (1, 5.6, -6.55), 'cyan')

    # Small berth status lamp above the mouth.
    b((-2, 14.6, -7.95), (2, 15.4, -7.6), 'iron')
    b((-1.4, 14.8, -8.0), (1.4, 15.2, -7.7), 'cyan' if state != 'idle' else 'blue_gray')

    # East kinetic input: square shaft end at the face center.
    for lo, hi in (((7, 5, -3), (8, 6, 3)), ((7, 10, -3), (8, 11, 3)),
                   ((7, 6, -3), (8, 10, -2)), ((7, 6, 2), (8, 10, 3))):
        b(lo, hi, 'cast_white')
    b((7, 6, -2), (7.1, 10, 2), 'iron')
    b((7.1, 7, -1), (7.15, 9, 1), 'blue_gray')

    # South logistics port: parcel hand-off to belt / funnel.
    b((-3, 5, 7), (3, 11, 7.5), 'iron')
    b((-2, 6, 7.5), (2, 10, 7.9), 'blue_gray')

    # A parcel occupying the cavity, wrapped in a translucent cyan envelope.
    if state == 'loaded':
        b((-3, 5, -6.6), (3, 10, -3.5), 'wood')
        b((-3.5, 4.6, -6.9), (3.5, 10.4, -3.3), 'glass')

    # Vertical transfer beam inside the cavity, effect-only.
    if state in ('online', 'loaded'):
        for mat, r in (('beam_core', 0.7), ('beam_outer', 1.5)):
            beam = box((-r, 5.6, -6.6), (r, 11, -6.5), mat)
            for f in beam:
                f.update(group='beam', kind='non_block_effect', emissive=True, material_role=mat)
            g.extend(beam)

    for f in g:
        f.update(kind='placed_dock_concept', owner_cell=[0, 0, 0])
    return g


def build(state='idle'):
    return dock_mesh(state)


def concept():
    return {
        'schemaVersion': 1, 'status': 'offline_concept_only',
        'name': {'zh_cn': '远仓港', 'en_us': 'Distant Stock Dock'},
        'suggestedRegistryId': 'distantstock:dock', 'registered': True,
        'revision': 'dock-berth-v1',
        'placement': {'independentBlock': True, 'cellCount': 1,
                      'boundsModelPixels': BOUNDS, 'front': 'north'},
        'meshFormat': 'render_scene face list; not Minecraft block JSON',
        'api': {'build': "build(state='idle') -> list[face]", 'textures': 'textures() -> dict[str, PIL.Image]',
                'states': list(STATES)},
        'states': {
            'idle': {'berth': 'empty', 'lamp': 'blue-gray (off)', 'beam': 'off'},
            'online': {'berth': 'empty', 'lamp': 'cyan (ready)', 'beam': 'on'},
            'loaded': {'berth': 'parcel + cyan envelope', 'lamp': 'cyan', 'beam': 'on'},
        },
        'stateSemantics': {'beamAndEnvelopeAreEffects': True,
                           'noRoundGauge': True, 'noFlatControlPanel': True},
        'ports': [{'owner': 'dock', 'kind': 'kinetic_input', 'face': 'east', 'center': [8, 8, 0]},
                  {'owner': 'dock', 'kind': 'parcel_handoff', 'face': 'south', 'center': [0, 8, 8]},
                  {'owner': 'dock', 'kind': 'fluid_input_reserved', 'face': 'west', 'center': [-8, 8, 0], 'fluid': None}],
        'materials': {name: MATERIALS[name] for name in STRUCTURAL},
        'materialSource': 'create_materials.material/source; installed Create jar or CREATE_JAR override',
        'noImplementationClaims': ['parcel teleport', 'fluid transfer', 'beam physics', 'game state logic'],
        'fluid': {'name': None, 'registered': False},
    }


def previews():
    result = {}
    for label, state, yaw in (('front', 'online', 30), ('rear', 'idle', 210), ('side', 'idle', 90)):
        im = render(build(state), textures(), size=(700, 640), yaw=yaw, pitch=22, center=(0, 8, 0), scale=22)
        text(im, (24, 20), 'DOCK / ' + label.upper(), 20)
        text(im, (24, 607), 'OFFLINE CONCEPT / ONE CELL / CREATE MATERIALS', 14, MUTED)
        result[label + '.png'] = im

    sheet = Image.new('RGBA', (1500, 700), BG)
    header(sheet, 'DISTANT STOCK / DOCK',
           'Create andesite casing parcel berth / no round gauge / 16 x 16 x 16 model pixels')
    for i, (label, state, yaw) in enumerate((('FRONT / BERTH MOUTH', 'online', 30),
                                             ('REAR / PARCEL HAND-OFF', 'idle', 210),
                                             ('SIDE / KINETIC SHAFT', 'idle', 90))):
        sheet.paste(render(build(state), textures(), size=(500, 490), yaw=yaw, pitch=22, center=(0, 8, 0), scale=16), (i * 500, 112))
        text(sheet, (i * 500 + 25, 610), label, 17)
    text(sheet, (30, 661), 'OFFLINE ART ONLY / NO PARCEL TELEPORT, FLUID TRANSFER OR BEAM PHYSICS IMPLEMENTED', 16, MUTED)
    result['overview.png'] = sheet

    sheet = Image.new('RGBA', (1200, 650), BG)
    header(sheet, 'DOCK / SIMULATED BERTH STATES',
           'IDLE empty / ONLINE ready beam / LOADED parcel inside cyan envelope')
    for i, state in enumerate(STATES):
        im = render(build(state), textures(), size=(400, 420), yaw=30, pitch=22, center=(0, 8, 0), scale=13)
        sheet.paste(im, (i * 400, 112))
        text(sheet, (i * 400 + 24, 535), state.upper(), 21)
    text(sheet, (30, 592), 'Beam and cyan envelope are visual effects only; no game state logic.', 16, MUTED)
    result['states.png'] = sheet

    sheet = Image.new('RGBA', (1200, 420), BG)
    header(sheet, 'DOCK / CASING UV CHECK',
           'Actual dock casing faces / full Create tile on every panel / no geometry change')
    for i, (label, yaw) in enumerate((('NORTH', 0), ('EAST', 90), ('SOUTH', 180), ('WEST', 270))):
        casing = [f for f in build('idle') if f['material'] == 'casing']
        sheet.paste(render(casing, textures(), size=(290, 250), yaw=yaw,
                           pitch=0, center=(0, 8, 0), scale=16), (i * 300, 112))
        text(sheet, (i * 300 + 28, 370), label + ' / FULL 16x16 UV', 16)
    result['casing-check.png'] = sheet
    return result


def json_outputs():
    return {'concept.json': concept(),
            'mesh.json': {'format': 'render_scene', 'state': 'online', 'faces': build('online')}}


def validate():
    checks = 0

    def verify(condition, label):
        nonlocal checks
        if not condition:
            raise AssertionError(label)
        checks += 1

    mats = textures()
    for face in casing_mesh():
        verify(face['uv'] == FULL_UV and face.get('clamp') is True,
               'complete casing border UV; normalized clamped sampling')
    for name in STRUCTURAL:
        verify(mats[name].tobytes() == material(name).tobytes(), name + ' Create material provenance')
        # The casing-interior wood crop is intentionally flat; still must vary.
        unique = len(set(mats[name].getdata()))
        verify(unique > (2 if name == 'wood' else 8), name + ' nonuniform source pixels')
    for state in STATES:
        mesh = build(state)
        pts = np.array([p for f in mesh for p in f['points']])
        verify(np.isfinite(pts).all(), state + ' finite mesh')
        verify((pts >= BOUNDS[0]).all() and (pts <= BOUNDS[1]).all(), state + ' one-cell bounds')
        verify({f['material'] for f in mesh} <= set(mats), state + ' known materials')
        casing = [f for f in mesh if f['material'] == 'casing']
        verify(len(casing) >= 6, state + ' casing faces present')
        verify(all(f['uv'] == FULL_UV and f.get('clamp') is True for f in casing),
               state + ' all casing borders retained')
        for f in mesh:
            p, uv = np.asarray(f['points']), np.asarray(f['uv'])
            verify(p.shape == (4, 3) and uv.shape == (4, 2), 'quad/uv shape')
            verify(np.linalg.norm(np.cross(p[1] - p[0], p[2] - p[0])) > 0, 'nondegenerate quad')
    idle = build('idle')
    # idle geometry must be a stable prefix of the other states.
    for state in ('online', 'loaded'):
        m = build(state)
        verify(len(m) >= len(idle), state + ' superset of idle geometry')
        verify(all(np.array_equal(f['points'], o['points']) for f, o in zip(idle, m)), state + ' idle geometry preserved')
    verify(concept()['stateSemantics']['noRoundGauge'] is True, 'no gauge')
    verify(concept()['ports'][2]['fluid'] is None, 'fluid not invented')
    for name, expected in json_outputs().items():
        verify(json.loads((DOC / name).read_text()) == expected, name + ' reproducible JSON')
    for name, expected in previews().items():
        with Image.open(OUT / ('dock-' + name)) as saved:
            verify(saved.size == expected.size and saved.convert('RGBA').tobytes() == expected.tobytes(),
                   name + ' reproducible preview')
    print(f'PASS {checks} validations; {len(build("online"))} faces; {len(mats)} textures; {len(STATES)} states; no gameplay')
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate saved outputs without writing')
    args = parser.parse_args()
    if args.check:
        validate()
        return
    DOC.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    for name, obj in json_outputs().items():
        (DOC / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n')
    for name, im in previews().items():
        im.save(OUT / ('dock-' + name))
    validate()
    print(OUT / 'dock-overview.png')
    print(OUT / 'dock-states.png')


if __name__ == '__main__':
    main()
