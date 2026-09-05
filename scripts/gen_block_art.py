#!/usr/bin/env python3
"""Build 16-pixel Create-style machine art. No runtime renderers or animations.

Bee-port material layout: Create: Mobile Packages, MIT, Tim Heidler.
The unmodified source atlas and its license live beside this script.
"""
import argparse
import base64
import copy
import io
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/distantstock'
SOURCE = ROOT / 'scripts/art_sources/bee_port.png'
TEX = ASSETS / 'textures/block'
MODELS = ASSETS / 'models/block'

IRON_D = '#454b46'
IRON = '#7d837b'
IRON_L = '#b4b7a7'
BRASS_D = '#635037'
BRASS = '#b28c50'
BRASS_L = '#e0c27c'
DARK = '#292d2b'
INK = '#4c4539'
PAPER = '#e6d8b3'
BLUE = '#63bbd0'
BLUE_D = '#3c6470'
BLUE_L = '#bee7e9'


def save(name, im):
    TEX.mkdir(parents=True, exist_ok=True)
    im.save(TEX / (name + '.png'))


def tile(atlas, col, row):
    return atlas.crop((col * 16, row * 16, col * 16 + 16, row * 16 + 16))


def inset(draw, box):
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=DARK)
    draw.line((x0, y1, x1, y1), fill=IRON_L)
    draw.line((x1, y0 + 1, x1, y1), fill=IRON)
    draw.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill='#1f2422')


def lamp(draw, x, y, on):
    draw.rectangle((x, y, x + 1, y + 1), fill=BLUE_D if not on else BLUE)
    draw.point((x, y), fill='#729398' if not on else BLUE_L)


def block_textures(atlas):
    # Use the source material layout, not a machine texture stretched over every part.
    side = tile(atlas, 0, 1)
    back = tile(atlas, 1, 0)
    bottom = tile(atlas, 2, 0)
    top = tile(atlas, 0, 2)
    for on in (False, True):
        suffix = '_lit' if on else ''
        s = side.copy()
        d = ImageDraw.Draw(s)
        # A small coupling plate above the transport interface remains visible beside a funnel.
        d.rectangle((6, 2, 9, 4), fill=IRON_D)
        d.line((6, 2, 9, 2), fill=IRON_L)
        lamp(d, 7, 3, on)
        save('dock_side' + suffix, s)
        t = top.copy()
        d = ImageDraw.Draw(t)
        inset(d, (3, 3, 12, 12))
        d.rectangle((5, 5, 10, 10), fill=BRASS_D)
        d.line((5, 5, 10, 5), fill=BRASS_L)
        d.line((5, 6, 5, 10), fill=BRASS)
        d.rectangle((6, 6, 9, 9), fill=DARK)
        lamp(d, 7, 7, on)
        for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
            d.point((x, y), fill=IRON_L)
        save('dock_top' + suffix, t)
        for loaded in (False, True):
            f = tile(atlas, 0, 0)
            d = ImageDraw.Draw(f)
            # Compact hatch with layered frame, clear wood outside and iron sill.
            inset(d, (3, 3, 12, 12))
            d.line((4, 3, 11, 3), fill=BRASS_L)
            d.line((3, 4, 3, 10), fill=BRASS)
            d.line((12, 4, 12, 10), fill=BRASS_D)
            d.line((4, 11, 11, 11), fill='#4c514b')
            for y in (5, 7, 9):
                d.line((5, y, 10, y), fill='#373d36')
            # Only two short rails carry the aether colour, not the casing.
            for x in (4, 11):
                d.line((x, 5, x, 9), fill=BLUE if on else BLUE_D)
                d.point((x, 5), fill=BLUE_L if on else '#738e91')
            if loaded:
                d.rectangle((5, 5, 10, 10), fill='#9b7045')
                d.line((5, 5, 9, 5), fill='#dbb980')
                d.line((5, 6, 5, 9), fill='#c99c64')
                d.line((10, 5, 10, 10), fill='#765135')
                d.line((7, 5, 7, 10), fill='#e3cd9f')
                d.line((5, 8, 10, 8), fill='#644c36')
            else:
                # Rolled shutter at the top and a dark empty bay underneath.
                d.line((5, 4, 10, 4), fill=IRON)
                d.line((5, 5, 10, 5), fill=IRON_D)
            d.line((4, 13, 11, 13), fill=IRON_D)
            d.point((2, 6), fill=BRASS_L)
            d.point((13, 9), fill=BRASS_L)
            save('dock_front' + ('_loaded' if loaded else '') + suffix, f)
    save('dock_back', back)
    save('dock_bottom', bottom)
    save('machine_wood', tile(atlas, 1, 0))

    # Component sheets have their own UVs; no compressed complete casing on small knobs.
    metal = Image.new('RGBA', (16, 16), IRON)
    d = ImageDraw.Draw(metal)
    d.line((0, 0, 15, 0), fill=IRON_L)
    d.line((0, 0, 0, 15), fill='#939b90')
    d.line((0, 15, 15, 15), fill=IRON_D)
    d.line((15, 0, 15, 15), fill='#666d64')
    save('machine_metal', metal)
    brass = Image.new('RGBA', (16, 16), BRASS)
    d = ImageDraw.Draw(brass)
    d.line((0, 0, 15, 0), fill=BRASS_L)
    d.line((0, 0, 0, 15), fill='#c6a05e')
    d.line((0, 15, 15, 15), fill=BRASS_D)
    d.line((15, 0, 15, 15), fill='#88683e')
    save('machine_brass', brass)
    for on in (False, True):
        face = tile(atlas, 0, 0)
        d = ImageDraw.Draw(face)
        d.rectangle((1, 1, 14, 14), fill=IRON_D)
        d.line((2, 1, 13, 1), fill=IRON_L)
        # Ledger under glass, tuning dial and a small link telltale.
        d.rectangle((2, 3, 9, 11), fill=BRASS_D)
        d.rectangle((3, 4, 8, 10), fill=PAPER)
        for y, end in ((5, 7), (7, 6), (9, 7)):
            d.line((4, y, end, y), fill='#ac9774')
        d.rectangle((11, 4, 13, 6), fill=BRASS_D)
        lamp(d, 11, 4, on)
        d.rectangle((10, 9, 13, 12), fill=BRASS)
        d.line((11, 9, 12, 9), fill=BRASS_L)
        d.line((11, 10, 11, 11), fill=INK)
        d.line((3, 13, 8, 13), fill='#161d1b')
        save('gauge_face' + ('_lit' if on else ''), face)
    mon = Image.new('RGBA', (16, 16), IRON_D)
    d = ImageDraw.Draw(mon)
    d.line((0, 0, 15, 0), fill=IRON_L)
    d.line((0, 0, 0, 15), fill=IRON)
    d.line((15, 0, 15, 15), fill='#323832')
    d.rectangle((2, 2, 13, 13), fill=BRASS_D)
    d.line((2, 2, 12, 2), fill=BRASS_L)
    d.rectangle((3, 3, 12, 12), fill=PAPER)
    # Two analog scales, not a cyan television.
    for ox in (4, 9):
        d.line((ox, 5, ox + 2, 5), fill=INK)
        d.point((ox, 6), fill=INK)
        d.point((ox + 2, 6), fill=INK)
        d.line((ox + 1, 7, ox + 2, 6), fill='#886243')
    d.line((4, 9, 11, 9), fill='#b6a182')
    d.line((4, 11, 6, 11), fill=BLUE_D)
    d.line((9, 11, 11, 11), fill=BLUE_D)
    for x,y in ((1,1),(14,1),(1,14),(14,14)):
        d.point((x,y), fill=IRON_L)
    save('monitor_face', mon)


def box(name, lo, hi, texture, overrides=None, rotation=None):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    # Vanilla's default box UV projection, written explicitly so small parts keep pixel scale.
    uvs = {
        'north': [16-x1, 16-y1, 16-x0, 16-y0],
        'south': [x0, 16-y1, x1, 16-y0],
        'west': [z0, 16-y1, z1, 16-y0],
        'east': [16-z1, 16-y1, 16-z0, 16-y0],
        'up': [x0, z0, x1, z1],
        'down': [x0, 16-z1, x1, 16-z0],
    }
    obj = {'name': name, 'from': lo, 'to': hi,
           'faces': {f: {'texture': '#' + texture, 'uv': uv} for f,uv in uvs.items()}}
    if overrides:
        for face, (tex, uv) in overrides.items():
            obj['faces'][face] = {'texture': '#' + tex, 'uv': uv}
    if rotation:
        obj['rotation'] = rotation
    return obj


def write_model(name, model):
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / (name + '.json')).write_text(json.dumps(model, indent=2) + '\n')


def block_models():
    # Exactly one cube, six quads. No custom renderer, transparent overlays, or frame tick cost.
    textures = {key: 'distantstock:block/dock_' + key for key in ('front','side','top','back','bottom')}
    textures['particle'] = 'create:block/andesite_casing'
    cube = box('sealed_machine_casing', [0,0,0], [16,16,16], 'side')
    for face, tex in [('north','front'),('south','back'),('up','top'),('down','bottom')]:
        cube['faces'][face]['texture'] = '#' + tex
    for face in cube['faces']:
        cube['faces'][face]['cullface'] = face
    write_model('dock', {'parent': 'minecraft:block/block', 'textures': textures, 'elements': [cube]})
    for on, loaded in ((True,False),(False,True),(True,True)):
        suffix = ('_loaded' if loaded else '') + ('_lit' if on else '')
        tex = {'front': 'distantstock:block/dock_front' + suffix}
        if on:
            tex.update({k: 'distantstock:block/dock_' + k + '_lit' for k in ('side','top')})
        write_model('dock' + suffix, {'parent':'distantstock:block/dock', 'textures':tex})

    textures = {'wood': 'distantstock:block/machine_wood',
                'metal': 'distantstock:block/machine_metal',
                'brass': 'distantstock:block/machine_brass',
                'face': 'distantstock:block/gauge_face',
                'particle': 'create:block/andesite_casing'}
    parts = [box('plinth', [0,0,0], [16,3,16], 'wood'),
             box('pedestal', [2,3,3], [14,8,14], 'wood'),
             box('rear_housing', [2,8,10], [14,11,14], 'wood'),
             box('sloped_control_panel', [1,8,2], [15,10,14], 'metal',
                 {'up': ('face',[1,2,15,14])},
                 {'origin':[8,9,8], 'axis':'x', 'angle':-22.5})]
    write_model('gauge', {'parent':'minecraft:block/block', 'textures':textures, 'elements':parts})
    write_model('gauge_lit', {'parent':'distantstock:block/gauge',
                            'textures':{'face':'distantstock:block/gauge_face_lit'}})
    write_model('monitor', {'parent':'minecraft:block/block',
        'textures':{**textures, 'face':'distantstock:block/monitor_face'},
        'elements':[
            box('wall_backplate',[0,0,14],[16,16,16],'wood'),
            box('instrument_housing',[1,1,12],[15,15,14],'metal',
                {'north':('face',[0,0,16,16])})]})
    # Inventory previews show the device, never imply a live connection.
    for name in ('dock','gauge','monitor'):
        (ASSETS / f'models/item/{name}.json').write_text(json.dumps({'parent':f'distantstock:block/{name}'}, indent=2)+'\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seed-reference', type=Path)
    args = parser.parse_args()
    if args.seed_reference:
        model = json.loads(args.seed_reference.read_text())
        source = model['textures'][0]['source']
        im = Image.open(io.BytesIO(base64.b64decode(source.split(',')[1]))).convert('RGBA')
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        im.save(SOURCE)
    atlas = Image.open(SOURCE).convert('RGBA')
    assert atlas.size == (64, 64), atlas.size
    block_textures(atlas)
    block_models()
    print('Generated cube dock, sloped request desk and wall instruments.')


if __name__ == '__main__':
    main()
