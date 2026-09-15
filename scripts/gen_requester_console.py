#!/usr/bin/env python3
"""Generate the requester console block model from the approved art handoff.

Source of truth: docs/design/requester-console-approved-v5/. Re-run this after Astra
revises the art; nothing here is hand-maintained.

Two coordinate systems meet in this file and they are not the same:

  * console_body.json is already authored in block space (0..16, y up, model facing
    north). Its elements are copied through unchanged apart from the texture
    namespace.
  * The antenna comes from the portable requester, which is an ITEM model: there the
    device lies flat and the mast runs along -Z. The handoff documents the rigid
    conversion (x, y, z) -> (x + 7, 18 - z, y + 12.6).

That conversion is a +90 degree rotation about X plus a translation. Block models only
allow element angles of +-22.5 and +-45 degrees, so it cannot be an element rotation and
is baked into the coordinates, face keys included. The UVs travel with their face
untouched, which is what "keep the original UVs" asks for; the result is checked against
the handoff's own approved renders below.
"""
import collections
import json
import math
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / 'docs/design/requester-console-approved-v5'
ASSETS = ROOT / 'src/main/resources/assets/distantstock'
TEX_OUT = ASSETS / 'textures/block/requester_console'
MODEL_OUT = ASSETS / 'models/block'

# The antenna's rigid conversion, (x, y, z) -> (x + 7, 18 - z, y + 12.6).
#
# It cannot be an element rotation: block models only accept element angles of +-22.5 and
# +-45 degrees, so a 90 degree turn has to be baked into the coordinates. Baking means the
# face keys have to be remapped too, because a face is named after the direction it points
# and this one turns up into south, south into down, and so on.
ANTENNA_X_SHIFT = 7.0
ANTENNA_Z_LIFT = 12.6
FACE_UNDER_QUARTER_TURN = {
    'up': 'south', 'south': 'down', 'down': 'north', 'north': 'up',
    'east': 'east', 'west': 'west',
}

# Vanilla's own tables, read out of FaceInfo and BlockFaceUV rather than assumed, because
# both disagree with the offline renderer this project uses and only vanilla matters here.
#
# FaceInfo: the four corners of a face, in the order the baker emits them.
VANILLA_FACE_VERTICES = {
    'down': [('x0', 'y0', 'z1'), ('x0', 'y0', 'z0'), ('x1', 'y0', 'z0'), ('x1', 'y0', 'z1')],
    'up': [('x0', 'y1', 'z0'), ('x0', 'y1', 'z1'), ('x1', 'y1', 'z1'), ('x1', 'y1', 'z0')],
    'north': [('x1', 'y1', 'z0'), ('x1', 'y0', 'z0'), ('x0', 'y0', 'z0'), ('x0', 'y1', 'z0')],
    'south': [('x0', 'y1', 'z1'), ('x0', 'y0', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y1', 'z1')],
    'west': [('x0', 'y1', 'z0'), ('x0', 'y0', 'z0'), ('x0', 'y0', 'z1'), ('x0', 'y1', 'z1')],
    'east': [('x1', 'y1', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y0', 'z0'), ('x1', 'y1', 'z0')],
}

# The handoff names exactly these four parts; the portable model also carries a Body
# and an unnamed 45-degree plate, and neither belongs on the console. 8 body elements
# plus these four come to 63 faces, which is what the approved preview mesh contains.
ANTENNA_PARTS = ('AntennaX', 'AntennaZ', 'Dish', 'AntennaTop')

# The smallest a cuboid is allowed to be.
#
# The portable requester's dish is a zero-thickness plate, which costs nothing on an item held in
# a hand: an item is drawn culled, so only the face pointing at you survives. The console renders
# translucent, and vanilla's translucent pass has back-face culling off so you can read the far
# side of glass, so both faces of that plate are drawn at exactly the same depth and eat each
# other. On screen the dish came out shredded and fringed with transparent pixels, which is what
# "the antenna exploded" looked like. Giving it a real thickness removes the coplanar pair.
MIN_THICKNESS = 0.5


def open_up(from_, to_):
    """Give every flat axis of a cuboid a real extent, centred where the plate already was."""
    from_, to_ = list(from_), list(to_)
    for axis in range(3):
        if to_[axis] - from_[axis] >= MIN_THICKNESS:
            continue
        middle = (from_[axis] + to_[axis]) / 2
        from_[axis] = middle - MIN_THICKNESS / 2
        to_[axis] = middle + MIN_THICKNESS / 2
    return from_, to_

# Dropped from the shipped model at the user's request. The handoff draws a solid cube
# inside the translucent cover, which reads on screen as a solid blue block floating in
# the lamp. The cover itself carries the light instead, the way Create's factory gauge
# bulb brightens as a whole.
DROPPED_PARTS = ('lamp_core',)

# The antenna reads an opaque copy of the portable requester's icon; see write_antenna_texture.
ANTENNA_TEXTURE = 'distantstock:block/requester_console/antenna'
# What the handoff calls that same artwork, in its own preview mesh and renderer.
HANDOFF_ANTENNA_MATERIAL = 'distantstock:item/requester'
PARTICLE = 'distantstock:block/requester_console/base_andesite'

# Cutout, not translucent, and the lamp cover is made opaque to suit.
#
# This is the fix for the antenna coming apart in game while every offline render looked
# correct. RenderType.translucent() sets COLOR_WRITE: it blends but never writes depth, so
# overlapping parts are painted in draw order rather than by distance. The console's body is
# clean, but the antenna is not -- the mast is two boxes crossed through each other, and it
# passes through the dish -- so the far side of the mast painted over the near side and the
# whole aerial shredded. Both offline renderers sort by depth and so never showed it.
#
# Cutout writes depth and culls back faces, which is what a block of solid parts wants. It
# discards alpha below 128, so the cover's glass (alpha 100..120) would vanish; it is drawn
# from bulb_core's own shade instead, which is no loss now that the lamp core inside it was
# removed and the cover carries the light itself. Create renders its own factory gauge with
# cutout for the same reasons.
RENDER_TYPE = 'minecraft:cutout'

# The mod's existing lit-indicator cyan. The lit cover keeps a little of the glass'
# own shading so it does not flatten into a single colour.
LIT_LAMP = (0x53, 0xCB, 0xD4)


# The approved preview frames the console from the opposite side to the game's north
# facing, so the shipped model is its mirror image: in game the clipboard would sit on
# the right and the lamp on the left, which is backwards from the design.
MIRROR_X = True

# Third-party sources, all MIT, carried the way this project already carries them: an
# inline credit field on the model. The diesel generators beam textures are pixel crops
# of andesite_girder_pole.png (MIT, George VI), and the antenna is the portable
# requester's mast, whose own credit is already on models/item/requester.json.
CREDIT = ('Antenna from Create: Mobile Packages (MIT, Tim Heidler). Girder beam textures '
          'derived from Create: Diesel Generators (MIT, George VI). Console body and '
          'andesite palette by Distant Stock.')

def body_elements():
    raw = json.loads((HANDOFF / 'console_body.json').read_text())
    # Already authored in block space with '#' texture references, so it copies through.
    elements = [e for e in json.loads(json.dumps(raw['elements']))
                if e.get('name') not in DROPPED_PARTS]
    return raw, elements


def mirror_x(elements):
    """Flip the whole model about x = 8, which is what "the console is mirrored" means.

    A mirror is not a rotation, so nothing in the block format can express it and every
    part has to be rewritten: the box, the face keys (east and west trade places), and the
    u of every uv rectangle. v is untouched because the flip axis is x.

    The u fix is a SWAP of the rectangle's two u values, not a complement. The face keeps
    reading the same patch of texture; it just walks it the other way, which is what makes
    the picture come out mirrored. Complementing u instead would slide the sample to the
    opposite side of the texture, which on the clipboard lands on a patch of plain brown.

    An x-axis element rotation survives this untouched: mirroring x commutes with a
    rotation about x, and only the rotation's y/z origin matters, which the mirror leaves
    alone too.
    """
    out = []
    for element in elements:
        element = json.loads(json.dumps(element))
        x0, _, _ = element['from']
        x1, _, _ = element['to']
        element['from'][0] = 16 - x1
        element['to'][0] = 16 - x0
        faces = {}
        for name, face in element['faces'].items():
            face = dict(face)
            u0, v0, u1, v1 = face['uv']
            face['uv'] = [u1, v0, u0, v1]
            # The in-plane rotation has to follow. Swapping the rectangle's u ends maps rectangle
            # corner j onto 3-j, and a rotation r assigns corner (k + r) to vertex k, so the new
            # rotation is -r. Leaving it alone wrecks every face that carried one: the antenna's
            # east and west faces turn by 90 and would sample the texture sideways.
            if face.get('rotation'):
                face['rotation'] = (-face['rotation']) % 360
            faces[{'east': 'west', 'west': 'east'}.get(name, name)] = face
        element['faces'] = faces
        out.append(element)
    return out


def vanilla_vertices(box_from, box_to, face):
    x0, y0, z0 = box_from
    x1, y1, z1 = box_to
    return [tuple({'x0': x0, 'y0': y0, 'z0': z0,
                   'x1': x1, 'y1': y1, 'z1': z1}[part] for part in corner)
            for corner in VANILLA_FACE_VERTICES[face]]


def corner_uv(rect, index):
    """Vanilla BlockFaceUV: vertex k reads rect corner (k + rotation / 90) % 4.

    Corner order comes straight from getU/getV: u takes uvs[0] for indices 0 and 1 and
    uvs[2] for 2 and 3, while v takes uvs[1] for 0 and 3 and uvs[3] for 1 and 2. Note the
    rectangle is NOT sorted, because a face may legitimately be written with u0 > u1 to
    mirror it, and sorting would silently flip the picture.
    """
    u_a, v_a, u_b, v_b = rect
    return [(u_a, v_a), (u_a, v_b), (u_b, v_b), (u_b, v_a)][index]


def rebaked_face(source, source_name, box_from, box_to, target_name, move):
    """Move a face onto a rotated cuboid, keeping the pixels where they were.

    The element's box turns, so the face is no longer the same side of the box: an up face
    becomes the south one. Its uv rectangle has to follow, which in block models means
    working out which of the four rectangle corners each new vertex should read. That is
    solved here against vanilla's tables instead of being eyeballed.
    """
    rect = list(source['uv'])
    src_rot = source.get('rotation', 0) // 90
    source_vertices = vanilla_vertices(source['from'], source['to'], source_name)
    moved = {move(point): corner_uv(rect, (k + src_rot) % 4)
             for k, point in enumerate(source_vertices)}

    wanted = [moved[point] for point in vanilla_vertices(box_from, box_to, target_name)]
    for rotation in (0, 90, 180, 270):
        step = rotation // 90
        if all(wanted[k] == corner_uv(rect, (k + step) % 4) for k in range(4)):
            face = {'texture': source.get('texture', '#antenna'), 'uv': rect}
            if rotation:
                face['rotation'] = rotation
            return face
    raise SystemExit(f'{source_name} -> {target_name}: no uv rotation reproduces the source')


def antenna_elements():
    raw = json.loads((HANDOFF / 'portable_requester_reference.json').read_text())
    out = []
    for element in raw['elements']:
        if element.get('name') not in ANTENNA_PARTS:
            continue
        (x0, y0, z0), (x1, y1, z1) = open_up(element['from'], element['to'])
        # A +90 degree turn about X takes (y, z) to (-z, y), so the z ordering becomes the
        # y ordering and from/to stay the right way round without a swap.
        box_from = [x0 + ANTENNA_X_SHIFT, 18 - z1, y0 + ANTENNA_Z_LIFT]
        box_to = [x1 + ANTENNA_X_SHIFT, 18 - z0, y1 + ANTENNA_Z_LIFT]

        def move(point, shift=ANTENNA_X_SHIFT, lift=ANTENNA_Z_LIFT):
            return (point[0] + shift, 18 - point[2], point[1] + lift)

        converted = {'from': box_from, 'to': box_to, 'faces': {}}
        for name, face in element['faces'].items():
            target = FACE_UNDER_QUARTER_TURN[name]
            # The source faces name their artwork with the item model's own variable, '#1', which
            # means nothing here. Every part of the antenna reads the one opaque copy.
            # The source box handed to rebaked_face has to be the opened one, because that is what
            # the target box was built from and its corners are the keys the lookup uses.
            converted['faces'][target] = rebaked_face(
                {**face, 'texture': '#antenna', 'from': [x0, y0, z0], 'to': [x1, y1, z1]},
                name, box_from, box_to, target, move)
        out.append((element.get('name'), converted))
    return slender_mast(out)


# The mast, as two boxes crossed through each other: the portable requester wears it lying along
# one axis, and the console stands it up. On the item it reads as one rod, because an item is
# drawn culled and the crossing is hidden inside; in the world the cross-section shows as a plus
# with four concave corners, which is not what a rod looks like.
MAST_PARTS = ('AntennaX', 'AntennaZ')


def slender_mast(parts):
    """Replace the crossed mast with the single rod they have in common.

    That shared core is the whole of the mast: one unit square, full height, dead on the axis both
    boxes are centred on. Its side faces are taken from the box that already owned those planes —
    north and south from AntennaX, east and west from AntennaZ — so the rod keeps the artwork's
    shading and its banding without re-authoring any uv.
    """
    by_name = dict(parts)
    if not all(name in by_name for name in MAST_PARTS):
        raise SystemExit(f'the handoff no longer ships {MAST_PARTS}; the mast merge below is stale')
    x, z = by_name['AntennaX'], by_name['AntennaZ']

    rod_from = [max(x['from'][i], z['from'][i]) for i in range(3)]
    rod_to = [min(x['to'][i], z['to'][i]) for i in range(3)]
    if any(rod_to[i] - rod_from[i] <= 0 for i in range(3)):
        raise SystemExit('the two mast boxes no longer overlap; there is no shared core')

    faces = {name: dict(x['faces'][name]) for name in ('north', 'south', 'down')}
    faces['east'] = dict(z['faces']['east'])
    faces['west'] = dict(z['faces']['west'])
    # The tip. Neither mast box has artwork on its top face — both are fully transparent there —
    # so taking the rod's cap from either would leave the aerial with a blank top. AntennaTop is
    # the portable requester's own cap, a one-unit cube with a single cyan face, and it sits
    # inside the rod once the rod replaces the crossing. Its face is what the cyan tip always
    # was, so the rod wears it and the cube itself is dropped as buried.
    cap = by_name.get('AntennaTop')
    if cap is None:
        raise SystemExit('the handoff no longer ships AntennaTop; the rod has no cap to wear')
    faces['up'] = dict(cap['faces']['up'])
    rod = {'from': rod_from, 'to': rod_to, 'faces': faces, 'name': 'antenna_mast'}
    return [rod] + [element for name, element in parts
                    if name not in MAST_PARTS + ('AntennaTop',)]


def build(lit, mirrored=True):
    body_raw, body = body_elements()
    textures = {name: f'distantstock:block/requester_console/{name}'
                for name in body_raw['textures']}
    textures['antenna'] = ANTENNA_TEXTURE
    textures['particle'] = PARTICLE
    # The cover is the lamp. Its unlit texture is the handoff's glass untouched, so the
    # approved look is preserved exactly until the link comes up.
    if lit:
        textures['glass'] = 'distantstock:block/requester_console/glass_lit'
    elements = body + antenna_elements()
    if mirrored and MIRROR_X:
        elements = mirror_x(elements)
    return {
        'credit': CREDIT,
        'parent': 'minecraft:block/block',
        'ambientocclusion': True,
        'render_type': RENDER_TYPE,
        'textures': textures,
        'elements': elements,
        # The model stands 25/16 blocks tall and pivots on the block centre, so its visual
        # centre sits about 2 units high at this scale. The translation drops it back down;
        # +y pushes the icon up the slot, which is what made it sit too high.
        'display': {
            'gui': {'rotation': [30, 225, 0], 'translation': [0, -2.0, 0], 'scale': [0.5, 0.5, 0.5]},
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.5, 0.5, 0.5]},
            'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
        },
    }


def copy_textures():
    TEX_OUT.mkdir(parents=True, exist_ok=True)
    for source in sorted((HANDOFF / 'textures').glob('*.png')):
        # portable_requester.png is a byte-identical snapshot of the item texture we
        # already ship, and the antenna references that instead of a second copy.
        if source.name == 'portable_requester.png':
            continue
        shutil.copyfile(source, TEX_OUT / source.name)
    print(f'textures -> {TEX_OUT.relative_to(ROOT)}')


def write_antenna_texture():
    """The antenna's own copy of the portable requester's icon, with every pixel made opaque.

    The antenna is a set of loose uv rectangles laid over an item icon, and most of them include
    fully transparent pixels: on the item those areas simply are not there, and the model was drawn
    for a renderer that either ignored alpha or drew it culled. The console draws them for real, and
    every one of the antenna's faces samples at least one hole, so the holes cannot be worked around
    by moving a rectangle.

    They are filled with the nearest opaque pixel's colour instead, which keeps the artwork the user
    approved and gives the mast and the dish a solid sky to read from. The sprite keeps the icon's
    size, so every uv rectangle still addresses exactly what it did before.
    """
    icon = Image.open(ASSETS / 'textures/item/requester.png').convert('RGBA')
    width, height = icon.size
    pixels = list(icon.getdata())
    filled = [None] * len(pixels)
    queue = collections.deque()
    for i, (r, g, b, a) in enumerate(pixels):
        if a > 0:
            filled[i] = (r, g, b, 255)
            queue.append(i)
    if not queue:
        raise SystemExit('the requester icon is entirely transparent')
    # Breadth-first outward from the solid pixels, so each hole takes the colour of whatever it is
    # actually next to rather than one flat substitute colour.
    while queue:
        i = queue.popleft()
        x, y = i % width, i // width
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < width and 0 <= ny < height:
                j = ny * width + nx
                if filled[j] is None:
                    filled[j] = filled[i]
                    queue.append(j)
    out = Image.new('RGBA', icon.size)
    out.putdata(filled)
    out.save(TEX_OUT / 'antenna.png')


def write_lamp_textures():
    """Both states of the lamp cover, from the handoff's glass but no longer see-through.

    The handoff only draws the unlit lamp, and leaves the colour mapping open. Its cover is drawn
    as glass, alpha 100..120, which cutout would discard outright, so the cover is rebuilt opaque
    at the glass's own colour: see-through stopped meaning anything once the lamp core inside it
    was removed and the cover began carrying the light by itself.

    The lit state is the same cover pulled toward the mod's indicator cyan, which keeps the
    module's own shading instead of flattening it to one colour and matches every other lit
    indicator in the mod.
    """
    glass = Image.open(TEX_OUT / 'glass.png').convert('RGBA')
    unlit = Image.new('RGBA', glass.size)
    lit = Image.new('RGBA', glass.size)
    for y in range(glass.height):
        for x in range(glass.width):
            r, g, b, a = glass.getpixel((x, y))
            # The glass is nearly clear, so its RGB alone would read as a white cube. Lifting the
            # colour toward the casing's own pale blue keeps the cover reading as glass.
            tint = 0.55
            shaded = tuple(round(c + ((0x9E, 0xB8, 0xC4)[i] - c) * tint) for i, c in enumerate((r, g, b)))
            unlit.putpixel((x, y), shaded + (255,))
            blend = 0.65
            lit.putpixel((x, y), (
                round(shaded[0] + (LIT_LAMP[0] - shaded[0]) * blend),
                round(shaded[1] + (LIT_LAMP[1] - shaded[1]) * blend),
                round(shaded[2] + (LIT_LAMP[2] - shaded[2]) * blend),
                255,
            ))
    unlit.save(TEX_OUT / 'glass.png')
    lit.save(TEX_OUT / 'glass_lit.png')


def bake_corners(element):
    """Corners of every face the element actually defines, after its rotation.

    Only defined faces count: AntennaTop carries a single face, so three of its eight
    cuboid corners never reach the screen and must not be expected in the output.
    """
    x0, y0, z0 = element['from']
    x1, y1, z1 = element['to']
    corners = {
        'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        'west': [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        'east': [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        'up': [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        'down': [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }
    rotation = element.get('rotation')
    origin = rotation['origin'] if rotation else None
    angle = math.radians(rotation['angle']) if rotation else 0
    uv = {'x': (1, 2), 'y': (2, 0), 'z': (0, 1)}[rotation['axis'] if rotation else 'x']
    cos, sin = math.cos(angle), math.sin(angle)
    out = []
    for face in element['faces']:
        for point in corners[face]:
            point = list(point)
            if origin:
                a = point[uv[0]] - origin[uv[0]]
                b = point[uv[1]] - origin[uv[1]]
                point[uv[0]] = origin[uv[0]] + cos * a - sin * b
                point[uv[1]] = origin[uv[1]] + sin * a + cos * b
            out.append(tuple(round(v, 2) for v in point))
    return out


def face_pairs(model):
    """Every (position, texture coordinate) a model would actually draw, in vanilla's terms.

    This is the pairing that matters: two models can share geometry and still sample the texture
    from completely different pixels. Comparing these sets is what proves a mirror put every face's
    picture on the right corner.
    """
    pairs = set()
    for element in model['elements']:
        for name, face in element['faces'].items():
            step = face.get('rotation', 0) // 90
            vertices = vanilla_vertices(element['from'], element['to'], name)
            rotation = element.get('rotation')
            origin = rotation['origin'] if rotation else None
            angle = math.radians(rotation['angle']) if rotation else 0
            axis = {'x': (1, 2), 'y': (2, 0), 'z': (0, 1)}[rotation['axis'] if rotation else 'x']
            cos, sin = math.cos(angle), math.sin(angle)
            for k, point in enumerate(vertices):
                point = list(point)
                if origin:
                    a = point[axis[0]] - origin[axis[0]]
                    b = point[axis[1]] - origin[axis[1]]
                    point[axis[0]] = origin[axis[0]] + cos * a - sin * b
                    point[axis[1]] = origin[axis[1]] + sin * a + cos * b
                pos = tuple(round(v, 2) for v in point)
                pairs.add((pos, corner_uv(face['uv'], (k + step) % 4)))
    return pairs


def verify_mirror_is_a_mirror():
    """The shipped model must be the approved one flipped, texture placement included."""
    if not MIRROR_X:
        return
    plain = face_pairs(build(False, mirrored=False))
    flipped = face_pairs(build(False, mirrored=True))
    want = {((round(16 - x, 2), y, z), uv) for (x, y, z), uv in plain}
    missing, extra = want - flipped, flipped - want
    if missing or extra:
        raise SystemExit(f'mirror mismatch: {len(missing)} faces lost their picture, '
                         f'{len(extra)} sample somewhere new\n'
                         f'  e.g. missing {sorted(missing)[:2]}\n'
                         f'  e.g. extra   {sorted(extra)[:2]}')
    print(f'mirror check passed: {len(want)} face corners keep their pixels')


def is_mast_face(face, rod_from, rod_to) -> bool:
    """Whether an approved face belongs to the crossed mast rather than the dish or the cap.

    All three share the antenna's material, so it is told apart by shape: the mast's sides are the
    only antenna faces ten units tall, and its caps are the only horizontal ones at the mast's own
    top and bottom. The dish is flat and sits in the middle; the cap is a one-unit cube a little
    below the top.
    """
    if face.get('material') != HANDOFF_ANTENNA_MATERIAL:
        return False
    points = face['points']
    heights = [p[1] for p in points]
    if max(heights) - min(heights) > 1.0:
        return True
    spans = [max(p[i] for p in points) - min(p[i] for p in points) for i in range(3)]
    if max(spans) <= 1.0 and rod_from[1] < min(heights) and max(heights) < rod_to[1]:
        # AntennaTop: the one-unit cube that used to sit inside the mast, whose single cyan face
        # the rod now wears as its cap.
        return True
    low = min(heights)
    return abs(low - rod_from[1]) < 0.01 or abs(low - rod_to[1]) < 0.01


def approved_mast_rod():
    """The rod, derived from the handoff's two mast boxes rather than from the converter."""
    raw = json.loads((HANDOFF / 'portable_requester_reference.json').read_text())
    boxes = {}
    for element in raw['elements']:
        if element.get('name') in MAST_PARTS:
            (x0, y0, z0), (x1, y1, z1) = open_up(element['from'], element['to'])
            boxes[element['name']] = ([x0 + ANTENNA_X_SHIFT, 18 - z1, y0 + ANTENNA_Z_LIFT],
                                      [x1 + ANTENNA_X_SHIFT, 18 - z0, y1 + ANTENNA_Z_LIFT])
    if len(boxes) != 2:
        raise SystemExit('the handoff no longer ships both mast boxes')
    (ax, az), (bx, bz) = boxes.values()
    return ([max(ax[i], bx[i]) for i in range(3)], [min(az[i], bz[i]) for i in range(3)])


def verify_against_approved_mesh():
    """The handoff ships the exact geometry the user signed off on, baked as flat quads.

    Comparing the corner sets proves the antenna conversion above reproduces that
    geometry, rather than trusting the algebra in the docstring. Three deliberate departures
    are accounted for so the comparison stays exact: the mirror, the dropped lamp core, and
    the thickening of the antenna's flat plate, which has no thickness to preserve.
    """
    approved = json.loads((HANDOFF / 'preview_mesh.json').read_text())
    want = set()
    for face in approved:
        points = [list(p) for p in face['points']]
        if is_mast_face(face, *approved_mast_rod()):
            # The mast, which the shipped model replaces with the rod the two crossed boxes share.
            # Its own corners are asserted below instead; leaving them here would compare the
            # approved three-unit plus against a one-unit rod and fail by design.
            continue
        heights = {p[1] for p in points}
        width = max(p[0] for p in points) - min(p[0] for p in points)
        depth = max(p[2] for p in points) - min(p[2] for p in points)
        if (face.get('material') == HANDOFF_ANTENNA_MATERIAL
                and len(heights) == 1 and width > 3 and depth > 3):
            # The dish, and only the dish. Every face is flat, so flatness alone selects all
            # fifteen antenna quads; requiring the antenna's own material narrows it to the faces
            # lying in a horizontal plane, which still includes the masts' end caps. Those are 3x1
            # and 1x3, and the dish is the only one that spans the full 5x5 outline. Opening it
            # moves the plate's single plane to two, and both of those are approved corners.
            height = heights.pop()
            for side in (height - MIN_THICKNESS / 2, height + MIN_THICKNESS / 2):
                want.update(tuple(round(v, 2) for v in (p[0], side, p[2])) for p in points)
            continue
        want.update(tuple(round(v, 2) for v in p) for p in points)
    if MIRROR_X:
        want = {(round(16 - x, 2), y, z) for x, y, z in want}

    raw = json.loads((HANDOFF / 'console_body.json').read_text())
    dropped = [e for e in json.loads(json.dumps(raw['elements']))
               if e.get('name') in DROPPED_PARTS]
    if MIRROR_X:
        dropped = mirror_x(dropped)
    excused = {tuple(round(v, 2) for v in p) for e in dropped for p in bake_corners(e)}

    got = set()
    faces = 0
    for element in build(False)['elements']:
        got.update(bake_corners(element))
        faces += len(element['faces'])

    # The rod stands in for the mast, so the approved mesh contributes its corners here, built
    # from the handoff's own two boxes rather than from the converter's output.
    rod_from, rod_to = approved_mast_rod()
    if MIRROR_X:
        # Both ends before either is written: assigning from[0] first would feed the mirrored
        # value straight back into to[0], and the rod would come out stretched across the axis.
        mirrored_from = [16 - rod_to[0], rod_from[1], rod_from[2]]
        rod_to = [16 - rod_from[0], rod_to[1], rod_to[2]]
        rod_from = mirrored_from
    corners = [(x, y, z) for x in (rod_from[0], rod_to[0])
               for y in (rod_from[1], rod_to[1]) for z in (rod_from[2], rod_to[2])]
    want.update(tuple(round(v, 2) for v in p) for p in corners)

    missing = want - got - excused
    extra = got - want
    if missing or extra:
        raise SystemExit(f'geometry mismatch: {len(missing)} approved corners missing, '
                         f'{len(extra)} unexpected corners\n'
                         f'  e.g. missing {sorted(missing)[:3]}\n'
                         f'  e.g. extra   {sorted(extra)[:3]}')
    print(f'geometry matches the approved mesh ({"mirrored" if MIRROR_X else "as drawn"}, '
          f'{len(excused)} corners dropped with the lamp core): {faces} faces, '
          f'{len(got)} corners, y up to {max(p[1] for p in got):.2f}')


def main():
    copy_textures()
    write_antenna_texture()
    write_lamp_textures()
    verify_against_approved_mesh()
    verify_mirror_is_a_mirror()
    for lit, name in ((False, 'gauge'), (True, 'gauge_lit')):
        model = build(lit)
        path = MODEL_OUT / f'{name}.json'
        path.write_text(json.dumps(model, indent=2) + '\n')
        faces = sum(len(e['faces']) for e in model['elements'])
        print(f'{path.relative_to(ROOT)}: {len(model["elements"])} cuboids, {faces} faces')


if __name__ == '__main__':
    main()
