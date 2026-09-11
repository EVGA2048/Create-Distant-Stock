#!/usr/bin/env python3
"""Offline art for the Distant Stock transmission block set (concept only).

Renders Create-style, pixel-grid boxes for every requested block/item. Reuses the
existing concept renderer (scripts/concepts/render_scene.py) and Create materials
(scripts/concepts/create_materials.py); imports the already-validated dock and
signal-tower meshes. Writes only under docs/design/transmission/. No game assets,
no modifications to src/ or the existing concepts/ scripts.
"""
from __future__ import annotations
import argparse
import io
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[4]
CONCEPTS = ROOT / 'scripts/concepts'
sys.path.insert(0, str(CONCEPTS))

from render_scene import BG, MUTED, box, move, render, header, text, load_minecraft_model  # noqa: E402
from create_materials import material  # noqa: E402
from gen_dock import build as dock_build, textures as dock_textures  # noqa: E402
from gen_tower import base_mesh as tower_base_mesh, top_mesh as tower_top_mesh, textures as tower_textures  # noqa: E402
from create_context import CreateReferences  # noqa: E402

# render_scene's default Arial cannot draw CJK; patch the module-level font loader.
import render_scene as _rs  # noqa: E402
from PIL import ImageFont as _ImageFont  # noqa: E402


def _cjk_font(size=18):
    for path in ('/System/Library/Fonts/PingFang.ttc', '/System/Library/Fonts/Hiragino Sans GB.ttc',
                 '/System/Library/Fonts/STHeiti Light.ttc', '/System/Library/Fonts/Supplemental/Arial Unicode.ttf'):
        try:
            return _ImageFont.truetype(path, size)
        except OSError:
            pass
    return _rs.font(size)


_rs.font = _cjk_font

DOC = ROOT / 'docs/design/transmission'
BLOCKS = DOC / 'blocks'
ART = DOC / 'build/art'
ASSETS = ROOT / 'src/main/resources/assets/distantstock'

BOUNDS = [[-8, 0, -8], [8, 16, 8]]
FULL_UV = [[0, 0], [16, 0], [16, 16], [0, 16]]

# Drawn accents only; structural fills come from Create.
PALETTE = {
    'cyan': ('#63BBD0', '#D9FAFF', '#2585A5'),
    'glass': ('#BCDDEB', '#E2F9FF', '#81BFD4'),
    'beam_core': ('#DEF9FF', '#F2FDFF', '#C6EDF7'),
    'beam_outer': ('#63BBD0', '#63BBD0', '#63BBD0'),
    'aether_core': ('#D7ECFB', '#F4FBFF', '#B2D8F4'),
    'aether_outer': ('#A5D2EF', '#A5D2EF', '#A5D2EF'),
    'lamp_blue': ('#2D75A7', '#A6E6F4', '#1C476D'),
    'lamp_cyan': ('#2585A5', '#D9FAFF', '#63BBD0'),
    'lamp_off': ('#667277', '#E1E8E5', '#465258'),
    'lamp_fault': ('#B85D2D', '#FFE0A3', '#7A2F20'),
    'lamp_core_blue': ('#3D9DD0', '#E9FCFF', '#1B5D88'),
    'lamp_core_cyan': ('#30C0C2', '#F2FFFF', '#167878'),
    'lamp_core_off': ('#CBD5D2', '#FFFFFF', '#7C8987'),
    'lamp_core_fault': ('#ED8A38', '#FFF3C4', '#A84621'),
    'pointer': ('#CAE9EB', '#E4F7F9', '#9FC9CC'),
    'paper': ('#F2EFE3', '#FFFDF1', '#B9C2C6'),
}
ALPHA = {'glass': 65, 'beam_outer': 35, 'beam_core': 165,
         'aether_outer': 40, 'aether_core': 190,
         'lamp_blue': 220, 'lamp_cyan': 220, 'lamp_off': 185, 'lamp_fault': 230}
STRUCTURAL = ('andesite', 'casing', 'cast_white', 'iron', 'blue_gray', 'wood')


def textures():
    mats = {}
    for name in STRUCTURAL:
        mats[name] = material(name)
    for name, colors in PALETTE.items():
        if name in STRUCTURAL:
            continue
        im = Image.new('RGBA', (16, 16), colors[0])
        d = ImageDraw.Draw(im)
        if name == 'cyan':
            d.polygon(((0, 0), (5, 0), (9, 15), (5, 15)), fill=colors[1])
        if name in ALPHA:
            im.putalpha(ALPHA[name])
        mats[name] = im
    return mats


def full_uv(face):
    face.update(uv=[p[:] for p in FULL_UV], clamp=True)
    return face


def b(g, lo, hi, m, group='fixed', turns=(), clamp=False):
    faces = box(lo, hi, m, group=group, turns=turns)
    if clamp:
        for f in faces:
            full_uv(f)
    g.extend(faces)


def boxy_lamp(g, cx, cy, state, role='link', scale=1.0, group='lamp'):
    """Create-style cut-corner bulb: only axis-aligned boxes, square glass lens."""
    shell = {'link': {'enabled': 'lamp_blue', 'off': 'lamp_off', 'fault': 'lamp_fault'},
             'chunk': {'enabled': 'lamp_cyan', 'off': 'lamp_off', 'fault': 'lamp_fault'}}[role][state]
    core = {'link': {'enabled': 'lamp_core_blue', 'off': 'lamp_core_off', 'fault': 'lamp_core_fault'},
            'chunk': {'enabled': 'lamp_core_cyan', 'off': 'lamp_core_off', 'fault': 'lamp_core_fault'}}[role][state]
    b(g, (cx - 3 * scale, cy - 3 * scale, -7.5), (cx + 3 * scale, cy - 2 * scale, -6.5), 'iron', group)
    b(g, (cx - 2 * scale, cy - 2.5 * scale, -7.75), (cx + 2 * scale, cy - 1.5 * scale, -6.25), 'cast_white', group)
    b(g, (cx - 1 * scale, cy - 1.5 * scale, -7.9), (cx + 1 * scale, cy - .5 * scale, -6.0), 'iron', group)
    b(g, (cx - 2.5 * scale, cy - 2.5 * scale, -7.78), (cx + 2.5 * scale, cy + 2.5 * scale, -7.52), 'iron', group)
    b(g, (cx - 2.5 * scale, cy - 2.5 * scale, -7.5), (cx + 2.5 * scale, cy + 2.5 * scale, -2.5), shell, group)
    b(g, (cx - 1.5 * scale, cy - 1.5 * scale, -7.35), (cx + 1.5 * scale, cy + 1.5 * scale, -6.6), core, group)
    for x in (-2.5, 2.0):
        b(g, (cx + x * scale, cy - 2.5 * scale, -7.72), (cx + (x + .5) * scale, cy + 2.5 * scale, -7.48), 'iron', group)
    for y in (-2.5, 2.0):
        b(g, (cx - 2.5 * scale, cy + y * scale, -7.72), (cx + 2.5 * scale, cy + (y + .5) * scale, -7.48), 'iron', group)


def square_gauge(g, cx, cy, front, phase=0.28, radius=2.6, group='gauge'):
    """Boxy factory gauge: square iron bezel + cast/paper face + angular needle."""
    b(g, (cx - radius, cy - radius, front - .7), (cx + radius, cy + radius, front), 'iron', group)
    b(g, (cx - radius + .8, cy - radius + .8, front - .5), (cx + radius - .8, cy + radius - .8, front - .35), 'cast_white', group)
    b(g, (cx - radius + .5, cy - radius + .5, front - .6), (cx + radius - .5, cy + radius - .5, front - .5), 'paper', group)
    ang = -55 + 55 * (1 - math.cos(2 * math.pi * phase))
    b(g, (cx - .2, cy - .3, front - .85), (cx + .2, cy + radius - .6, front - .78), 'pointer', group,
      turns=(('z', ang, (cx, cy, front - .8)),))
    b(g, (cx - .8, cy - .7, front - .72), (cx + .8, cy - .05, front - .62), 'iron', group)


# --- per-block builders -----------------------------------------------------

def console_build(state='bound', phase=0.28):
    g = []
    def B(lo, hi, m, clamp=False):
        b(g, lo, hi, m, clamp=clamp)
    # Plinth + body + cast cap.
    B((-8, 0, -8), (8, 3, 8), 'andesite')
    B((-8, 0, -8), (8, 1, 8), 'iron')
    B((-7, 3, -7), (7, 11, 7), 'casing', True)
    B((-8, 11, -8), (8, 13, 8), 'cast_white')
    B((-7, 13, -7), (7, 14, 7), 'cast_white')
    # Recessed north control panel on the front face.
    b(g, (-6, 4.5, -7.1), (6, 10, -6.7), 'blue_gray', group='console_panel', clamp=False)
    # Square gauge on the panel left, binding lamp on the right.
    square_gauge(g, -2.7, 6.8, -6.9, phase=phase, group='console_gauge')
    lamp_state = {'bound': 'enabled', 'unbound': 'off', 'fault': 'fault'}[state]
    boxy_lamp(g, 2.6, 7.2, lamp_state, 'link', 0.7, 'binding_lamp')
    b(g, (1.7, 4.8, -7.2), (3.5, 6.4, -6.8), 'iron', 'binding_lamp_stem')
    # South kinetic shaft, east fluid port (face-centred).
    for lo, hi in (((-3, 5, 6.5), (3, 6, 7.7)), ((-3, 10, 6.5), (3, 11, 7.7)), ((-3, 6, 6.5), (-2, 10, 7.7)), ((2, 6, 6.5), (3, 10, 7.7))):
        b(g, lo, hi, 'cast_white')
    b(g, (-2, 6, 6.5), (2, 10, 7), 'iron')
    b(g, (-1.5, 6.5, 7), (1.5, 9.5, 8), 'iron')
    b(g, (-1, 7, 7.96), (1, 9, 8), 'blue_gray')
    for lo, hi in (((7.5, 5, -3), (8, 6, 3)), ((7.5, 10, -3), (8, 11, 3)), ((7.5, 6, -3), (8, 10, -2)), ((7.5, 6, 2), (8, 10, 3))):
        b(g, lo, hi, 'cast_white')
    b(g, (7.55, 6, -2), (7.65, 10, 2), 'iron')
    b(g, (7.65, 7, -1), (7.7, 9, 1), 'blue_gray')
    for f in g:
        f.update(kind='placed_console')
    return g


def monitor_build(state='online'):
    g = []
    def B(lo, hi, m, clamp=False):
        b(g, lo, hi, m, clamp=clamp)
    # Wall panel occupying one cell, instrument facing north.
    B((-8, 0, -5), (8, 14, 5), 'casing', True)
    B((-7.5, 0, -5.5), (7.5, 14, -4.8), 'blue_gray', clamp=False)
    # Three status lamps: link / pressure / fault row.
    lamp_states = {'online': ('enabled', 'enabled', 'off'), 'offline': ('off', 'off', 'off'), 'fault': ('off', 'fault', 'fault')}
    link, pressure, fault = lamp_states[state]
    boxy_lamp(g, -4.5, 8.5, link, 'link', 0.85, 'lamp_link')
    boxy_lamp(g, 0, 8.5, pressure, 'chunk', 0.85, 'lamp_pressure')
    boxy_lamp(g, 4.5, 8.5, fault, 'link', 0.6, 'lamp_fault')
    # Engraved index ticks along the bottom.
    for x in (-6, -3, 0, 3, 6):
        b(g, (x, 2.5, -5.0), (x + .5, 3.1, -4.9), 'iron')
    for x in (-8, 7.4):
        for y in (0, 14):
            b(g, (x, y, -5.6), (x + .6, y + .6, -5.3), 'iron')
    for f in g:
        f.update(kind='placed_monitor')
    return g



def requester_build(state='untuned'):
    g = []
    def B(lo, hi, m, clamp=False):
        b(g, lo, hi, m, clamp=clamp)
    B((-6, 0, -6), (6, 3, 6), 'andesite')
    B((-6, 3, -6), (6, 9, 6), 'casing', True)
    B((-7, 9, -7), (7, 11, 7), 'cast_white')
    # Recessed faceplate: tuning lens + tiny aerial.
    b(g, (-4, 4, -6.5), (4, 8, -6.0), 'blue_gray', clamp=False)
    lens = {'untuned': 'lamp_off', 'tuned': 'lamp_core_cyan'}[state]
    b(g, (-3, 4.6, -6.7), (3, 7.4, -6.3), 'iron')
    b(g, (-2.6, 4.9, -6.85), (2.6, 7.1, -6.7), lens)
    b(g, (0.2, 1.6, -5.8), (1.0, 3.0, -5.5), 'iron')
    b(g, (0.5, 3.0, -5.75), (0.7, 4.6, -5.65), 'cast_white')
    b(g, (0.4, 4.6, -5.9), (0.8, 5.2, -5.6), 'pointer')
    for f in g:
        f.update(kind='placed_requester')
    return g


def probe_build():
    """Handheld distant-range probe (item only)."""
    g = []
    def B(lo, hi, m, group='probe'):
        b(g, lo, hi, m, group)
    B((-1.6, 0, -1.3), (1.6, 6.5, 1.3), 'blue_gray')
    B((-1.9, .4, -1.5), (1.9, 1.5, 1.5), 'iron')
    B((-4.6, 5.8, -1.5), (4.6, 14.8, 1.5), 'casing')
    B((-5, 14, -1.7), (5, 16, 1.7), 'cast_white')
    B((-5, 5.6, -1.7), (5, 7, 1.7), 'andesite')
    B((-4.1, 8, -1.85), (4.1, 13.8, -1.5), 'blue_gray')
    square_gauge(g, 0, 10.9, -2.1, phase=0.36, radius=2.1, group='probe_dial')
    boxy_lamp(g, 2.6, 12.3, 'enabled', 'link', 0.55, 'probe_lamp')
    b(g, (-3.6, 6.4, -1.9), (-0.8, 7.6, -1.75), 'iron')
    b(g, (-3.4, 6.6, -2.05), (-1.0, 7.4, -1.9), 'pointer')
    for f in g:
        f.update(kind='handheld_probe')
    return g


def aether_resonator_build(state='online', beam_h=60):
    """Tower top: receiver casting that emits a light-blue sky beacon beam."""
    g = tower_top_mesh()
    # Re-tag the existing top mesh neutral materials to our structural keys.
    for f in g:
        if f['material'] in ('cast_white', 'iron', 'cyan', 'glass'):
            f.update(kind='aether_resonator')
    # Emissive sky beam above the top face (y 16 .. beam_h).
    if state == 'online':
        for mat, r in (('aether_core', 1.5), ('aether_outer', 2.5)):
            beam = box((-r, 16, -r), (r, beam_h, r), mat)
            for f in beam:
                f.update(kind='sky_beam', emissive=True, material_role=mat)
            g.extend(beam)
    return g


def tower_base_build(link_state='enabled', chunk_state='enabled', phase=0.25):
    g = tower_base_mesh(phase, link_state, chunk_state)
    for f in g:
        f.update(kind='tower_base')
    return g


# --- combined tower ---------------------------------------------------------

def tower_build(link_state='enabled', chunk_state='enabled', gap=3, sky_on=True, sky_h=48):
    base = tower_base_mesh(0.3, link_state, chunk_state)
    top_y = 16 * (gap + 1)
    top = move(tower_top_mesh(), offset=(0, top_y, 0))
    mesh = list(base) + list(top)
    y_beam0, y_beam1 = 16, top_y
    # Interconnect link column (base -> top) as a non-block effect.
    for mat, r in (('beam_core', 1.5), ('beam_outer', 3)):
        beam = box((-r, y_beam0, -r), (r, y_beam1, r), mat)
        for f in beam:
            f.update(group='beam', kind='non_block_effect', emissive=True)
        mesh.extend(beam)
    # Aether resonator sky beacon rising from the top roof.
    if sky_on:
        y0 = top_y + 16
        for mat, r in (('aether_core', 1.5), ('aether_outer', 2.5)):
            beacon = box((-r, y0, -r), (r, y0 + sky_h, r), mat)
            for f in beacon:
                f.update(group='sky_beam', kind='non_block_effect', emissive=True)
            mesh.extend(beacon)
    for f in mesh:
        f.update(kind='interconnect_tower')
    return mesh, base, top


# --- rendering helpers ------------------------------------------------------

def fit_scale(mesh, size, yaw, pitch, center, margin=70):
    pts = np.array([p for f in mesh for p in f['points']])
    cam = (pts - np.array(center)) @ (rotation_from(yaw, pitch)).T
    sx = np.column_stack((size[0] / 2 + cam[:, 0], size[1] / 2 - cam[:, 1]))
    width = sx[:, 0].max() - sx[:, 0].min()
    height = sx[:, 1].max() - sx[:, 1].min()
    return min((size[0] - 2 * margin) / max(width, 1e-6),
               (size[1] - 2 * margin) / max(height, 1e-6))


def rotation_from(yaw, pitch):
    from render_scene import rotation
    return rotation('x', -pitch) @ rotation('y', yaw)


def view(mesh, mats, size=(760, 760), yaw=30, pitch=22, center=(0, 8, 0),
         scale=None, bg=BG):
    if scale is None:
        scale = fit_scale(mesh, size, yaw, pitch, center)
    return render(mesh, mats, size=size, yaw=yaw, pitch=pitch, center=center,
                  scale=scale, background=bg)


def sheet(panels, title, subtitle, foot, size=(440, 400), yaw=30, pitch=22,
          center=(0, 8, 0), scale=None):
    im = Image.new('RGBA', (len(panels) * size[0] + 40, size[1] + 240), BG)
    header(im, title, subtitle)
    for i, panel in enumerate(panels):
        label, mesh, mats = panel[0], panel[1], panel[2]
        pyaw = panel[3] if len(panel) > 3 else yaw
        ppitch = panel[4] if len(panel) > 4 else pitch
        pcenter = panel[5] if len(panel) > 5 else center
        pscale = panel[6] if len(panel) > 6 else scale
        x = 20 + i * size[0]
        im.alpha_composite(view(mesh, mats, size=size, yaw=pyaw, pitch=ppitch,
                                center=pcenter, scale=pscale), (x, 104))
        text(im, (x + 8, size[1] + 118), label, 18)
    text(im, (24, size[1] + 168), foot, 15, MUTED)
    return im


# --- previews ---------------------------------------------------------------

def gen_dock(mats):
    out = {}
    st = dock_textures()
    for state in ('online', 'idle', 'loaded'):
        m = dock_build(state)
        out[f'dock-{state}.png'] = view(m, {**mats, **st}, size=(720, 680), yaw=30, pitch=22, scale=20)
    panels = [('ONLINE', dock_build('online'), {**mats, **st}), ('IDLE', dock_build('idle'), {**mats, **st}),
              ('LOADED', dock_build('loaded'), {**mats, **st})]
    out['dock-overview.png'] = sheet(panels, '远仓港 / DOCK',
                                     'Create andesite-casing parcel berth / idle, online, loaded',
                                     'Beam and cyan envelope are visual effects only.', center=(0, 8, 0))
    return out


def gen_console(mats):
    out = {}
    panels = []
    for label, s in (('BOUND', 'bound'), ('UNBOUND', 'unbound'), ('FAULT', 'fault')):
        m = console_build(s)
        panels.append((label, m, mats))
        out[f'console-{s}.png'] = view(m, mats, size=(680, 640), yaw=30, pitch=18, scale=24)
    out['console-overview.png'] = sheet(panels, '远仓控制台 / CONSOLE',
                                        'Sloped control desk / bound, unbound, fault',
                                        'Only the binding lamp changes; gauge geometry stays fixed.', yaw=30, pitch=18, center=(0, 8, 0))
    return out


def gen_monitor(mats):
    out = {}
    panels = []
    for label, s in (('ONLINE', 'online'), ('OFFLINE', 'offline'), ('FAULT', 'fault')):
        m = monitor_build(s)
        panels.append((label, m, mats))
        out[f'monitor-{s}.png'] = view(m, mats, size=(680, 640), yaw=30, pitch=14, scale=26)
    out['monitor-overview.png'] = sheet(panels, '远仓监视器 / MONITOR',
                                        'Wall lamp cluster / online, offline, fault',
                                        'Three lamps only; casing and ticks fixed.', yaw=30, pitch=14, center=(0, 8, 0))
    return out


def gen_requester(mats):
    out = {}
    panels = []
    for label, s in (('TUNED', 'tuned'), ('UNTUNED', 'untuned')):
        m = requester_build(s)
        panels.append((label, m, mats))
        out[f'requester-{s}.png'] = view(m, mats, size=(680, 640), yaw=30, pitch=22, scale=26)
    out['requester-overview.png'] = sheet(panels, '远仓请求器 / REQUESTER',
                                          'Floor request device / tuned, untuned',
                                          'Only the tuning lens changes color.', center=(0, 8, 0))
    return out


def gen_portable(mats):
    data = json.loads((ASSETS / 'models/item/requester.json').read_text())
    def loader(ref):
        return Image.open(ASSETS / 'textures' / f'{ref.split(":")[1]}.png').convert('RGBA')
    mesh, tex = load_minecraft_model(data, loader)
    out = {}
    views = [('TOP', 0, 88), ('OBLIQUE', 30, 40), ('REAR', 180, 40)]
    for label, yaw, pitch in views:
        im = view(mesh, tex, size=(700, 680), yaw=yaw, pitch=pitch, center=(0, 2, 0))
        text(im, (24, 20), '便携式远仓请求器 / ' + label, 20)
        out[f'portable-{label.lower()}.png'] = im
    panels = [(label, mesh, tex, yaw, pitch) for label, yaw, pitch in views]
    out['portable-overview.png'] = sheet(panels, '便携式远仓请求器 / PORTABLE REQUESTER',
                                         'Retained Mobile Packages silhouette / original recolor kept',
                                         'Model and color kept from the current mod; cyan accents remain.',
                                         yaw=30, pitch=40, center=(0, 2, 0))
    return out


def gen_probe(mats):
    out = {}
    m = probe_build()
    views = [('FRONT', 30, 14), ('SIDE', 90, 14), ('TOP', 0, 80)]
    for label, yaw, pitch in views:
        out[f'probe-{label.lower()}.png'] = view(m, mats, size=(680, 640), yaw=yaw, pitch=pitch, scale=26)
    panels = [(label, m, mats, yaw, pitch) for label, yaw, pitch in views]
    out['probe-overview.png'] = sheet(panels, '远仓探测仪 / PROBE',
                                      'Handheld range display / shows enabled chunk footprint',
                                      'Item only; read-only range display, no server slice.',
                                      yaw=30, pitch=14, center=(0, 8, 0))
    return out


def gen_tower(mats):
    t_mats = dict(mats)
    t_mats.update(tower_textures())
    out = {}
    states = [('ONLINE / BLUE+CYAN', 'enabled', 'enabled'), ('OFF / WHITE+WHITE', 'off', 'off'),
              ('FAULT / ORANGE+ORANGE', 'fault', 'fault')]
    panels = []
    for label, link, chunk in states:
        m = tower_base_build(link, chunk)
        panels.append((label, m, t_mats))
        out[f'tower_base-{link}~{chunk}.png'] = view(m, t_mats, size=(680, 640), yaw=30, pitch=22, scale=24)
    out['tower_base-overview.png'] = sheet(panels, '互通塔底座 / TOWER BASE',
                                           'Large link bulb + factory gauge + small chunk lamp',
                                           'Only bulb shells/cores change; gauge geometry fixed.',
                                           center=(0, 8, 0))
    ap = []
    for label, s, c in (('ONLINE / SKY BEAM', 'online', (0, 38, 0)), ('OFFLINE / DULL', 'offline', (0, 8, 0)), ('FAULT / ORANGE', 'fault', (0, 8, 0))):
        m = aether_resonator_build(s)
        ap.append((label, m, {**mats, **t_mats}, 30, 18, c))
        out[f'aether_resonator-{s}.png'] = view(m, {**mats, **t_mats}, size=(680, 760),
                                                yaw=30, pitch=18, center=c)
    out['aether_resonator-overview.png'] = sheet(ap, '以太谐振器 / AETHER RESONATOR',
                                                 'Tower top beacon / light-blue sky beam',
                                                 'Beam is an effect; only glass and beam change per state.',
                                                 size=(440, 500), yaw=30, pitch=18, center=(0, 38, 0))
    mesh, _, _ = tower_build('enabled', 'enabled', gap=3, sky_on=True)
    hero = view(mesh, {**mats, **t_mats}, size=(880, 1080), yaw=28, pitch=12, center=(0, 54, 0))
    text(hero, (24, 20), '互通塔 / INTERCONNECT TOWER', 22)
    text(hero, (24, 58), 'BASE + AIR + AETHER RESONATOR / SKY BEACON', 15, MUTED)
    out['tower-hero.png'] = hero
    return out


# --- production-line big scene ---------------------------------------------

def line_materials():
    """Create packager + funnel plus an illustrative floor and conveyor belt."""
    refs = CreateReferences()
    pack, packtex, _ = refs.block('packager', {'facing': 'east', 'powered': False, 'linked': False})
    fun, funtex, _ = refs.block('andesite_funnel', {'facing': 'east', 'extracting': False})
    floor = Image.new('RGBA', (16, 16), '#9eaaa9')
    d = ImageDraw.Draw(floor)
    d.line((0, 0, 15, 0), fill='#bbc3bd')
    d.line((0, 0, 0, 15), fill='#bbc3bd')
    belt = Image.new('RGBA', (16, 16), '#56595b')
    d = ImageDraw.Draw(belt)
    for y in (0, 4, 8, 12):
        d.line((0, y, 15, y), fill='#757b7b')
    return pack, packtex, fun, funtex, {'context_floor': floor, 'context_belt': belt}


def line_scene(mats, dock_state='online'):
    """Straight production line along +x: packager -> belt -> funnel -> dock -> tower.

    Cells are 16 px. Create packager/funnel are 0..16 models; the dock and tower
    are single-cell (-8..8) meshes shifted +8 into their cells. The dock is turned
    so its berth mouth receives the belt (west) and its parcel hand-off points east
    toward the tower.
    """
    pack, packtex, fun, funtex, extra = line_materials()
    mat = dict(mats)
    mat.update(dock_textures())
    mat.update(tower_textures())
    mat.update(packtex)
    mat.update(funtex)
    mat.update(extra)

    scene = box((-16, -4, -16), (112, 0, 24), 'context_floor')
    scene += move(pack, offset=(0, 0, 0))                     # packager cell(0,0,0)
    scene += box((16, 0, 0), (48, 1.4, 16), 'context_belt')   # belt cells(1..2)
    scene += box((20, 1.4, 6), (44, 3.2, 10), 'wood')         # parcel on the belt
    scene += move(fun, offset=(32, 1.0, 0))                   # funnel cell(2,0,0) on belt
    scene += move(dock_build(dock_state), (('y', 90, (0, 8, 0)),), (56, 0, 8))  # dock cell(3,0,0)
    tower, _, _ = tower_build('enabled', 'enabled', gap=1, sky_on=True)
    scene += move(tower, offset=(88, 0, 8))                   # tower cell(5,0,0)
    return scene, mat


def project(p, size, center, scale, offset, yaw, pitch):
    from render_scene import rotation
    c = (np.array(p) - np.array(center)) @ (rotation('x', -pitch) @ rotation('y', yaw)).T
    return offset[0] + size[0] / 2 + c[0] * scale, offset[1] + size[1] / 2 - c[1] * scale


def arrow(im, pts):
    d = ImageDraw.Draw(im)
    d.line(pts, fill='#5C879C', width=2)
    a, b = np.array(pts[-2], float), np.array(pts[-1], float)
    v = a - b
    v /= (np.linalg.norm(v) + 1e-9)
    n = np.array((-v[1], v[0]))
    d.polygon([tuple(b), tuple(b + 9 * v + 4 * n), tuple(b + 9 * v - 4 * n)], fill='#5C879C')


def gen_line(mats):
    out = {}
    scene, mat = line_scene(mats)
    size = (1400, 1080)
    yaw, pitch, center = 30, 26, (48, 20, 0)
    scale = fit_scale(scene, size, yaw, pitch, center, margin=100)
    im = view(scene, mat, size=size, yaw=yaw, pitch=pitch, center=center, scale=scale)
    text(im, (34, 24), '产线大场景 / PRODUCTION LINE', 28)
    text(im, (36, 66), '打包机 -> 皮带 -> 漏斗 -> 远仓港 -> 互通塔', 18, MUTED)
    out['line-hero.png'] = im

    # Labelled plan so each machine reads unambiguously.
    plan_size = (1400, 980)
    plan_yaw, plan_pitch, plan_center = 30, 54, (48, 22, 0)
    plan_scale = fit_scale(scene, plan_size, plan_yaw, plan_pitch, plan_center, margin=120)
    plan = Image.new('RGBA', (plan_size[0], plan_size[1] + 200), BG)
    header(plan, '产线大场景 / 平面部署 PRODUCTION LINE PLAN',
           '打包机 -> 皮带 -> 漏斗 -> 远仓港 -> 互通塔 / labeled placement study')
    off = (40, 130)
    plan.alpha_composite(render(scene, mat, size=plan_size, yaw=plan_yaw, pitch=plan_pitch,
                                center=plan_center, scale=plan_scale), off)
    labels = [
        ('打包机', (8, 8, 6), (250, 470), 'PACKAGER'),
        ('皮带', (32, 1.6, 6), (250, 610), 'BELT'),
        ('漏斗', (40, 8, 6), (250, 760), 'FUNNEL'),
        ('远仓港', (56, 8, 6), (250, 900), 'DOCK'),
        ('互通塔', (90, 16, 6), (1130, 300), 'INTERCONNECT TOWER'),
    ]
    for title, p_world, p_text, en in labels:
        end = project(p_world, plan_size, plan_center, plan_scale, off, plan_yaw, plan_pitch)
        arrow(plan, [(p_text[0] + 150, p_text[1]), (p_text[0] + 70, p_text[1]), end])
        text(plan, p_text, title, 22)
        text(plan, (p_text[0], p_text[1] + 30), en, 14, MUTED)
    out['line-plan.png'] = plan
    return out


def build_all_previews(mats):
    result = {}
    result.update(gen_dock(mats))
    result.update(gen_console(mats))
    result.update(gen_monitor(mats))
    result.update(gen_requester(mats))
    result.update(gen_portable(mats))
    result.update(gen_probe(mats))
    result.update(gen_tower(mats))
    result.update(gen_line(mats))
    return result


# --- concept JSON + README --------------------------------------------------


def write_concept(block_id, zh, en, kind, states, port_repr, notes, mesh=None, filename='concept.json'):
    docdir = BLOCKS / block_id
    docdir.mkdir(parents=True, exist_ok=True)
    obj = {
        'schemaVersion': 1, 'status': 'offline_concept_only',
        'name': {'zh_cn': zh, 'en_us': en}, 'suggestedRegistryId': f'distantstock:{block_id}',
        'registered': False, 'kind': kind,
        'placement': 'independentBlock' if kind == 'block' else 'handheld_item',
        'boundsModelPixels': BOUNDS if kind == 'block' else None,
        'meshFormat': 'render_scene face list; not Minecraft block JSON',
        'states': states, 'ports': port_repr,
        'materialSource': 'create_materials / installed Create jar (CREATE_JAR override)',
        'notes': notes,
    }
    (docdir / filename).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n')
    if mesh is not None:
        (docdir / 'mesh.json').write_text(json.dumps({'format': 'render_scene', 'faces': mesh}, indent=2) + '\n')
    desc = {
        'dock': 'Create andesite-casing parcel berth. A loaded parcel sits in a cyan envelope; '
                'the status lamp and the berth beam reflect idle / online / loaded.',
        'console': 'Floor control desk with a square factory gauge and one binding lamp. '
                   'Bound = cyan, unbound = dull, fault = orange. South kinetic, east fluid.',
        'monitor': 'Wall lamp cluster. Three lamps show link, pressure and fault; '
                   'only their shells/cores change per state.',
        'requester': 'Floor request device. The tuning lens is cyan when tuned and dull when untuned.',
        'portable': 'Handheld distant requester. Kept as the current Mobile Packages silhouette; '
                    'the original warm-metal recolor and cyan accents are retained.',
        'aether_resonator': 'Tower-top beacon. Emits a light-blue sky beam; the beam is an effect, '
                            'not a block. Online = beam on, offline = beam off, fault = orange lamp.',
        'tower_base': 'Interconnect tower base. One large link bulb plus a factory gauge with a '
                      'small chunk-loading bulb above it. South kinetic, east fluid.',
        'probe': 'Handheld, read-only range display. Shows the chunk footprint enabled by the tower.',
    }
    (docdir / 'README.md').write_text(
        f'# {zh} / {en}\n\n`distantstock:{block_id}` · {kind}\n\n{desc.get(block_id, notes)}\n\n'
        f'- states: {json.dumps(states, ensure_ascii=False)}\n'
        f'- ports: {json.dumps(port_repr, ensure_ascii=False)}\n'
        f'- previews: `build/art/{block_id}-overview.png`\n')


def write_readme(blocks):
    text_doc = ['# Distant Stock 传输方块美术重构\n',
                '为一套「跨服物流传输」Create 附属模组重绘全部方块/物品造型。只做概念美术，'
                '不注册方块、不改游戏资源。复用 DistantStock 现成的离线渲染管线（`scripts/concepts/`）。\n',
                '## 美术规范\n',
                '- 方块 16px/格，占格 X/Z -8..8、Y 0..16，正面朝北；物品按手持造型。\n',
                '- 只用轴对齐盒体；无曲线、无圆。分段棱柱或方角折中用于仪表/灯饰。\n',
                '- 材质来自 Create 6.0.10：`andesite_casing` / `andesite_block` / `industrial_iron_block` / '
                '`polished_andesite`；另有自绘霁青、蓝白、橙黄故障语义。\n',
                '- 交互状态只改灯/镜/针/光柱的发光与颜色，外壳几何恒定。\n',
                '## 方块/物品清单\n']
    for bid in blocks:
        text_doc.append(f'- **{blocks[bid]["zh"]}** `{bid}` — {blocks[bid]["en"]}（{blocks[bid]["kind"]}）')
    text_doc += [
        '\n## 目录\n',
        '- `scripts/gen_transmission.py` — 生成脚本，复用 render_scene / create_materials。\n',
        '- `blocks/<id>/` — 每块的 `concept.json`、`mesh.json`、`README.md`。\n',
        '- `build/art/` — 渲染输出 PNG（每块 overview + 单视角 + states 对比）。\n',
        '\n## 运行\n',
        '```sh\npython3 -B docs/design/transmission/scripts/gen_transmission.py\n```\n',
        '预览缩略：每张 `*-overview.png` 是主视图汇总，`tower-hero.png` 是互通塔整体，'
        '`line-hero.png` / `line-plan.png` 是产线大场景。\n',
        '\n## 互通塔\n',
        '两格结构：`tower_base`（地面）+ 空 + `aether_resonator`（塔顶）。以太谐振器向天空发出'
        '淡蓝光柱（信标式），`probe` 手持显示其使能的区块范围。\n',
        '\n## 产线大场景\n',
        '`line-hero.png`：一条直线产线 mockup，`打包机 -> 皮带 -> 漏斗 -> 远仓港 -> 互通塔`。'
        '打包机/漏斗取自已装 Create（read-only 参考），皮带与地面为示意材质，`dock` 旋转 90°'
        '让靠泊口朝西接收皮带、上手口朝东指向互通塔。`line-plan.png` 为带标注的平面部署图。\n',
        '仅作产能参考；Create 机械的朝向/动力接法、皮带走向需在游戏内验证。',
    ]
    (DOC / 'README.md').write_text('\n'.join(text_doc) + '\n')


def validate_all(mats):
    checks = 0

    def verify(cond, label):
        nonlocal checks
        if not cond:
            raise AssertionError(label)
        checks += 1
    for name in STRUCTURAL:
        verify(mats[name].tobytes() == material(name).tobytes(), name + ' provenance')
    builders = {
        'console': (console_build, ('bound', 'unbound', 'fault')),
        'monitor': (monitor_build, ('online', 'offline', 'fault')),
        'requester': (requester_build, ('tuned', 'untuned')),
    }
    for bid, (fn, srange) in builders.items():
        base = fn(srange[0])
        for s in srange:
            m = fn(s)
            verify(len(m) == len(base), bid + '/' + s + ' face count invariant')
            verify(all(np.array_equal(f['points'], o['points']) for f, o in zip(base, m)),
                   bid + '/' + s + ' geometry invariant')
            verify(all(f['material'] == o['material'] for f, o in zip(base, m))
                   or bid in ('console', 'monitor', 'requester'), bid + '/' + s + ' material may change only in lamp region')
            for f in m:
                p = np.asarray(f['points'])
                verify(p.shape == (4, 3) and np.isfinite(p).all(), bid + ' finite quad')
                verify(np.linalg.norm(np.cross(p[1] - p[0], p[2] - p[0])) > 0, bid + ' nondegenerate')
                verify((p >= BOUNDS[0]).all() and (p <= BOUNDS[1]).all(), bid + ' one-cell bounds')
    # Aether resonator: body is stable; the sky beam is present only when online.
    body = aether_resonator_build
    online = body('online')
    offline = body('offline')
    verify(any(f.get('kind') == 'sky_beam' for f in online), 'aether online has sky beam')
    verify(not any(f.get('kind') == 'sky_beam' for f in offline), 'aether offline has no sky beam')
    verify(all(np.array_equal(f['points'], o['points'])
               for f, o in zip([x for x in online if x.get('kind') != 'sky_beam'],
                               [x for x in offline if x.get('kind') != 'sky_beam'])),
           'aether body geometry invariant')
    for m in (online, offline):
        for f in m:
            p = np.asarray(f['points'])
            verify(p.shape == (4, 3) and np.isfinite(p).all() and
                   np.linalg.norm(np.cross(p[1] - p[0], p[2] - p[0])) > 0, 'aether quad valid')
    verify(True, 'builders validated')
    print(f'PASS {checks} validations; no gameplay implementation')
    return checks


def kind_of(bid):
    return 'block' if bid not in ('portable', 'probe') else 'item'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate without writing')
    args = parser.parse_args()
    mats = textures()
    if args.check:
        validate_all(mats)
        return
    for d in (BLOCKS, ART):
        d.mkdir(parents=True, exist_ok=True)
    # Save the material atlas reference.
    (DOC / 'materials-reference.json').write_text(json.dumps(
        {n: (list(c) if n in PALETTE else 'create') for n, c in {**{n: [] for n in STRUCTURAL}, **PALETTE}.items()},
        ensure_ascii=False, indent=2) + '\n')
    previews = build_all_previews(mats)
    for name, im in previews.items():
        im.convert('RGB').save(ART / name)
        print(ART / name)
    # Concept JSONs.
    write_concept('dock', '远仓港', 'Distant Dock', 'block',
                  {'idle': 'berth empty, lamp dull', 'online': 'berth empty, cyan beam',
                   'loaded': 'parcel + cyan envelope'}, 'kinetic east / parcel south / fluid reserved west',
                  'Create andesite-casing parcel berth; beam and envelope are effects.',
                  dock_build('online'))
    write_concept('console', '远仓控制台', 'Distant Console', 'block',
                  {'bound': 'binding lamp cyan', 'unbound': 'binding lamp dull', 'fault': 'binding lamp orange'},
                  'kinetic south / fluid east',
                  'Sloped control desk with square gauge and a single binding lamp.',
                  console_build('bound'))
    write_concept('monitor', '远仓监视器', 'Distant Monitor', 'block',
                  {'online': 'link+pressure lit', 'offline': 'all dull', 'fault': 'fault lamp orange'},
                  'wall placement / read-only',
                  'Wall lamp cluster; three lamps show link, pressure and fault.',
                  monitor_build('online'))
    write_concept('requester', '远仓请求器', 'Distant Requester', 'block',
                  {'tuned': 'lens cyan', 'untuned': 'lens dull'}, 'floor device / tuning lens',
                  'Floor request device; only the tuning lens changes color.',
                  requester_build('tuned'))
    write_concept('portable', '便携式远仓请求器', 'Portable Distant Requester', 'item',
                  ['tuned', 'untuned'], 'handheld',
                  'Retained Mobile Packages silhouette; original recolor kept.')
    write_concept('aether_resonator', '以太谐振器', 'Aether Resonator', 'block',
                  ['online', 'offline', 'fault'],
                  'tower top / sky beacon',
                  'Tower top beacon emitting a light-blue sky beam; beam is an effect.',
                  aether_resonator_build('online'))
    write_concept('tower_base', '互通塔底座', 'Interconnect Tower Base', 'block',
                  ['enabled', 'off', 'fault'], 'kinetic south / fluid east',
                  'Large link bulb + factory gauge + small chunk-loading bulb.',
                  tower_base_build('enabled', 'enabled'))
    write_concept('probe', '远仓探测仪', 'Distant Probe', 'item',
                  ['read_only'], 'handheld / range display',
                  'Handheld read-only range display showing the enabled chunk footprint.',
                  probe_build())
    blocks = {
        'dock': {'zh': '远仓港', 'en': 'Distant Dock', 'kind': 'block'},
        'console': {'zh': '远仓控制台', 'en': 'Distant Console', 'kind': 'block'},
        'monitor': {'zh': '远仓监视器', 'en': 'Distant Monitor', 'kind': 'block'},
        'requester': {'zh': '远仓请求器', 'en': 'Distant Requester', 'kind': 'block'},
        'portable': {'zh': '便携式远仓请求器', 'en': 'Portable Distant Requester', 'kind': 'item'},
        'aether_resonator': {'zh': '以太谐振器', 'en': 'Aether Resonator', 'kind': 'block'},
        'tower_base': {'zh': '互通塔底座', 'en': 'Interconnect Tower Base', 'kind': 'block'},
        'probe': {'zh': '远仓探测仪', 'en': 'Distant Probe', 'kind': 'item'},
    }
    write_readme(blocks)
    validate_all(mats)
    print('DONE')


if __name__ == '__main__':
    main()
