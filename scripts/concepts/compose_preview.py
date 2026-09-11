#!/usr/bin/env python3
"""Compose the reworked Create berth with local Create references; no gameplay simulation.

The dock was reworked into a single Create-andesite-casing parcel berth. This module
renders that berth (from gen_dock.build) next to the shipped 0.3.7 dock and to the
installed Create packager, and places the berth at the end of a simplified production
line (casing -> packager -> belt -> funnel -> berth). No gameplay is simulated.

All coordinates are Minecraft model pixels (16 per block). The berth mesh from gen_dock
is centered x/z -8..8; it is shifted +8 so it shares the 0..16 space of Create models.
"""
from __future__ import annotations
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
from PIL import Image, ImageDraw
from render_scene import ROOT, BG, box, move, render, load_minecraft_model, header, footer, text
from create_context import CreateReferences
from gen_dock import build as berth_build, textures as berth_textures

OUT = ROOT / 'build/art/concepts'

CENTER = (8, 8, 8)                         # center of a 0..16 block cell
BERTH_TURN = (('y', -90, (8, 8, 8)),)     # south hand-off -> west, mouth -> east


def old_dock():
    assets = ROOT / 'src/main/resources/assets/distantstock'
    data = json.loads((assets / 'models/block/dock.json').read_text())
    return load_minecraft_model(data, lambda ref: Image.open(assets / 'textures' / f'{ref.split(":")[1]}.png'))


def berth(state='online', world=(0, 0, 0), turns=()):
    """New berth placed at an integer block cell; mouth faces north by default."""
    return move(berth_build(state), turns, (world[0] + 8, world[1], world[2] + 8))


def comparison(refs):
    old, oldtex = old_dock()
    newtex = berth_textures()
    pack, packtex, _ = refs.block('packager', {'facing': 'east', 'powered': False, 'linked': False})
    tex = dict(newtex)
    tex.update(oldtex)
    tex.update(packtex)

    sheet = Image.new('RGBA', (1400, 700), BG)
    header(sheet, 'DISTANT STOCK / BERTH CASTING STUDY',
           'Same camera, lighting and scale. 0.3.7 sealed body on the left; the berth keeps the berth mouth.')
    panels = [
        ('0.3.7 / CURRENT', old, oldtex),
        ('NEW / BERTH MOUTH', berth('online'), newtex),
        ('NEW / HAND-OFF + SHAFT', berth('loaded', turns=BERTH_TURN), newtex),
        ('CREATE / PACKAGER', pack, packtex),
    ]
    for i, (label, mesh, local) in enumerate(panels):
        im = render(mesh, {**tex, **local}, (340, 420), 30, 22, CENTER, 13)
        sheet.alpha_composite(im, (i * 350, 115))
        text(sheet, (i * 350 + 25, 565), label, 19)
    text(sheet, (30, 620), 'Berth geometry from gen_dock; reference packager read from the installed Create JAR.', 16)
    footer(sheet)
    sheet.save(OUT / 'dock-comparison.png')


def factory(refs):
    # Production line along +z: casing/packager at far +z, belt, funnel, berth mouth
    # facing the camera (north). Items travel from the packager toward the berth.
    casing, ctex, _ = refs.block('andesite_casing')
    pack, packtex, _ = refs.block('packager', {'facing': 'north', 'powered': False, 'linked': False})
    funnel, ftex, _ = refs.block('andesite_funnel', {'facing': 'north', 'extracting': False})

    floor = Image.new('RGBA', (16, 16), '#9eaaa9')
    d = ImageDraw.Draw(floor)
    d.line((0, 0, 15, 0), fill='#bbc3bd')
    d.line((0, 0, 0, 15), fill='#bbc3bd')
    belt = Image.new('RGBA', (16, 16), '#56595b')
    d = ImageDraw.Draw(belt)
    for y in (0, 4, 8, 12):
        d.line((0, y, 15, y), fill='#757b7b')

    mat = dict(berth_textures())
    mat.update(ctex)
    mat.update(packtex)
    mat.update(ftex)
    mat['context_floor'] = floor
    mat['context_belt'] = belt

    # World cells (x,z) in units of 16px blocks. Casing and packager beside each other,
    # short belt back to the funnel (seated on the belt, spout pointing into the berth
    # south port), berth at the front (north).
    scene = box((-20, -4, -8), (36, 0, 60), 'context_floor')
    scene += move(casing, offset=(-16, 0, 40))
    scene += move(pack, offset=(0, 0, 40))
    scene += box((0, 0, 16), (16, 2, 40), 'context_belt')           # belt slab on the floor
    scene += move(funnel, offset=(0, 2, 16))                        # funnel on the belt, spout into berth
    scene += berth('online', world=(0, 0, 0))                       # mouth faces north (camera)

    im = Image.new('RGBA', (1400, 740), BG)
    header(im, 'DISTANT STOCK / FACTORY-SCALE MOCKUP',
           'Create casing + packager + simplified belt + funnel + reworked berth')
    im.alpha_composite(render(scene, mat, (1360, 500), 26, 32, (8, 8, 10), 10.0), (20, 115))
    text(im, (35, 630), 'PLACEMENT STUDY ONLY / belt is illustrative / port directions require in-game verification', 15)
    footer(im)
    im.save(OUT / 'factory-context.png')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    refs = CreateReferences()
    comparison(refs)
    factory(refs)
    print(OUT / 'dock-comparison.png')
    print(OUT / 'factory-context.png')


if __name__ == '__main__':
    main()
