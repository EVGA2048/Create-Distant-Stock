#!/usr/bin/env python3
"""Turn the tower art handoff's offline quad meshes into loadable Minecraft block models.

The handoff ships `*_mesh.json`: flat lists of quads with `points` (16 units to the block),
`uv` (raw texels of a 16x16 texture), `material` and `group`. They are a preview renderer's
food, not block models, and the handoff says so. This converts them.

Three things make the conversion less obvious than it looks:

  * Every quad is a single flat face. A block model element with zero extent on one axis
    declares two coincident faces and they tear each other apart — that is exactly how the
    requester console's antenna came out shredded. So each quad becomes its own element with
    only the one face it actually is.

  * The quad's four points are in the mesh's order, and vanilla hands vertex k of a face the
    rectangle corner (k + rotation / 90) % 4. The rectangle and the in-plane rotation are
    solved below rather than assumed, the same way `rebaked_face` does it for the console.

  * The coupler is not one model. A column between two neighbours draws only its vertical
    faces; an end block draws its ring. Picking the wrong pieces leaves coincident horizontal
    faces at every seam, which is the z-fighting the handoff warns about.

Verification is the handoff's own renderer: `render_handoff.py` reproduces `tower/tower.png`
pixel for pixel from the meshes, so the converted tower is rendered again here and compared
against the same image. Geometry that renders identically is geometry that converted cleanly.
"""
from __future__ import annotations

import collections
import json
import math
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / "docs/design/tower-art-handoff-2026-09-15"
ASSETS = ROOT / "src/main/resources/assets/distantstock"
TEX_OUT = ASSETS / "textures/block/tower"
MODEL_OUT = ASSETS / "models/block/tower"
ITEM_OUT = ASSETS / "models/item"

# Vanilla's FaceInfo vertex order, as unit-box corners. A face is named after the direction it
# points, and the four corners come in the order the baker emits them.
FACE_CORNERS = {
    "down": [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    "north": [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    "south": [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    "west": [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    "east": [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}
FACE_FROM_NORMAL = {
    (0, -1, 0): "down", (0, 1, 0): "up",
    (0, 0, -1): "north", (0, 0, 1): "south",
    (-1, 0, 0): "west", (1, 0, 0): "east",
}
# BlockFaceUV: the rectangle corner each vertex index reads, before the in-plane rotation.
UV_CORNERS = [(0, 0), (0, 1), (1, 1), (1, 0)]


def load(path: Path) -> list:
    return json.loads(path.read_text())


def quad_normal(quad) -> tuple:
    """The outward face direction.

    The handoff renderer culls the opposite way round from vanilla — the same mismatch the
    requester console handoff had — so the winding's own normal points into the solid and has
    to be flipped. Getting this backwards mirrors every face into the block and the model
    turns inside out.
    """
    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = quad["points"][:3]
    a = (x1 - x0, y1 - y0, z1 - z0)
    b = (x2 - x0, y2 - y0, z2 - z0)
    n = (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    scale = max(abs(v) for v in n)
    if scale < 1e-9:
        raise SystemExit(f"degenerate quad: {quad['points']}")
    return tuple(-round(v / scale) for v in n)


def close(a, b) -> bool:
    return abs(a[0] - b[0]) < 1e-3 and abs(a[1] - b[1]) < 1e-3


def unit_corners(points, low, high, face):
    """The quad's points as unit-box corners, which is the space FACE_CORNERS lives in.

    Only the in-plane axes are scaled: the flat one has no span to scale by, and vanilla wants
    it at whichever end that face sits on — 0 for north, west and down, 1 for their opposites.
    """
    flat = [i for i in range(3) if high[i] - low[i] < 1e-9]
    if len(flat) != 1:
        raise SystemExit(f"quad is not flat on exactly one axis: {points}")
    edge = FACE_CORNERS[face][0][flat[0]]
    out = []
    for point in points:
        corner = []
        for i in range(3):
            if i == flat[0]:
                corner.append(edge)
            else:
                corner.append(round((point[i] - low[i]) / (high[i] - low[i])))
        out.append(tuple(corner))
    return out


def solve_uv(quad, face: str, low, high) -> dict:
    """The uv rectangle and in-plane rotation that reproduce this quad's own corner pairing.

    Matched by position, not by index: the mesh's winding runs the other way, so its point
    order is the reverse of vanilla's. Every point still carries its own uv, and vanilla only
    ever asks which rectangle corner a given vertex reads, so position is the thing to key on.
    """
    points = unit_corners(quad["points"], low, high, face)
    uvs = quad["uv"]
    wanted = {}
    for point, uv in zip(points, uvs):
        wanted[point] = (round(uv[0], 4), round(uv[1], 4))

    for rotation in (0, 90, 180, 270):
        step = rotation // 90
        assigned = {}
        for k, corner in enumerate(FACE_CORNERS[face]):
            uv = wanted.get(corner)
            if uv is None:
                break
            assigned[(k + step) % 4] = uv
        if len(assigned) != 4:
            continue
        # BlockFaceUV numbers its rectangle corners (u0,v0), (u0,v1), (u1,v1), (u1,v0), so the
        # assignment is only a rectangle if the u values pair up that way and the v values do too.
        u0, v0 = assigned[0]
        u1, v1 = assigned[2]
        if not (close(assigned[1], (u0, v1)) and close(assigned[3], (u1, v0))):
            continue
        rect = [u0, v0, u1, v1]
        face_data = {"uv": rect}
        if rotation:
            face_data["rotation"] = rotation
        return face_data
    raise SystemExit(f"no uv solution for {face} quad at {points}")


def key(point) -> tuple:
    return tuple(round(v, 3) for v in point)


def quad_to_element(quad, material_refs: dict) -> dict:
    """One flat quad becomes one element carrying only the face it is."""
    normal = quad_normal(quad)
    face = FACE_FROM_NORMAL[normal]
    points = quad["points"]
    axis = [i for i in range(3) if normal[i] != 0][0]
    low = [min(p[i] for p in points) for i in range(3)]
    high = [max(p[i] for p in points) for i in range(3)]
    if high[axis] - low[axis] > 1e-6:
        raise SystemExit(f"quad is not flat on its normal axis: {points}")
    # A zero-extent axis is legal, but from/to must still be ordered.
    low[axis] = high[axis] = round(low[axis], 4)

    face_data = solve_uv(quad, face, low, high)
    face_data["texture"] = "#" + quad["material"]
    return {
        "from": [round(low[i], 4) for i in range(3)],
        "to": [round(high[i], 4) for i in range(3)],
        "faces": {face: face_data},
    }


def build_model(quads: list, textures: dict, render_type: str = "minecraft:cutout") -> dict:
    elements = []
    refs = defaultdict_textures(textures)
    for quad in quads:
        if quad["material"] not in refs:
            raise SystemExit(f"mesh names a material with no texture: {quad['material']}")
        elements.append(quad_to_element(quad, refs))
    model = {
        "credit": "Tower art by Distant Stock; crystal and reflective glass drawn in-house. "
                  "Create andesite, brass and gearbox textures are Create's.",
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "render_type": render_type,
        "textures": textures,
        "elements": elements,
    }
    deconflict(model)
    return model


def defaultdict_textures(textures: dict) -> dict:
    return textures


def horizontal_quads(quads, at_y):
    """The quads lying flat at one height. These are the ones a neighbour makes coincident."""
    out = []
    for quad in quads:
        heights = {round(p[1], 3) for p in quad["points"]}
        if len(heights) == 1 and abs(heights.pop() - at_y) < 1e-3:
            out.append(quad)
    return out


def coupler_models():
    """The coupler is four models, one per pair of neighbours.

    A coupler between two others draws only `connected_middle_mesh`: full-height corner posts and
    a full-height crystal, with no horizontal face anywhere, so nothing of it is coincident with
    the block above or below. An end coupler keeps `coupler_mesh`, which has real rings, but drops
    the flat cap that faces a neighbour — two caps on the same plane is the z-fighting the handoff
    warns about — and adds the seam's cross-frame, which is what covers the joint.
    """
    full = load(HANDOFF / "coupler/coupler_mesh.json")
    middle = load(HANDOFF / "coupler/connected_middle_mesh.json")
    seam = seam_crossframe()

    textures = {name: f"distantstock:block/tower/{name}"
                for name in sorted({q["material"] for q in full} | {q["material"] for q in seam})}

    bottom_cap = horizontal_quads(full, 0.0)
    top_cap = horizontal_quads(full, 16.0)
    if not bottom_cap or not top_cap:
        raise SystemExit("coupler_mesh has no end caps; the composition below assumes it does")

    def without(quads, drop):
        dropped = {id(q) for q in drop}
        return [q for q in quads if id(q) not in dropped]

    return {
        "tower_coupler": (full, textures),
        "tower_coupler_top": (without(full, top_cap), textures),
        "tower_coupler_bottom": (without(full, bottom_cap) + seam, textures),
        "tower_coupler_middle": (middle + seam, textures),
    }


def coupler_textures():
    """The coupler's textures, with the crystal swapped for the copy that keeps its alpha."""
    textures = coupler_models()["tower_coupler"][1]
    out = dict(textures)
    if "crystal" in out:
        out["crystal"] = f"distantstock:block/tower/{SOFT_CRYSTAL}"
    return out


def seam_crossframe():
    """The cross-frame of one seam, moved into the upper block's own coordinates.

    The handoff's example sits at y=32, spanning 31..33: one pixel into each of the two blocks it
    joins. Drawn by the upper block it runs from -1 to +1, which is where pass 2 puts it.
    """
    quads = load(HANDOFF / "tower/coupler_crossframes_mesh.json")
    out = []
    for quad in quads:
        heights = [p[1] for p in quad["points"]]
        if max(heights) > 33.001:
            continue                      # the other example seam, at y=48
        moved = json.loads(json.dumps(quad))
        for point in moved["points"]:
            point[1] = round(point[1] - 32.0, 4)
            # The handoff draws this frame flush with the tower's skin, which is free in a preview
            # renderer and is a fight in the game: the block below and the block above each draw
            # their own face on exactly this plane. Lifting the skin a step proud settles it, and
            # a frame that stands a hair off the surface it wraps is what it wants to look like.
            for axis in (0, 2):
                if abs(point[axis]) < 1e-3:
                    point[axis] = -COINCIDENT_STEP
                elif abs(point[axis] - 16.0) < 1e-3:
                    point[axis] = 16.0 + COINCIDENT_STEP
        out.append(moved)
    if not out:
        raise SystemExit("no cross-frame found at the y=32 seam")
    return out


def resonator_models():
    """The resonator, as three models.

    The body and the arms are split because the arms turn and a block model cannot. The light
    column comes off the body for a different reason: it is the one piece of the tower that has
    to change colour while the game is running, and a baked block model has one appearance for
    every instance of it. Drawn by the renderer instead, the same four quads can be dim, bright
    or deep depending on what the tower is doing.
    """
    fixed = load(HANDOFF / "tower/fixed_mesh.json")
    rotor = load(HANDOFF / "tower/rotor_mesh.json")
    beam = [q for q in fixed if q["material"] == "crystal"]
    body = [q for q in fixed if q["material"] != "crystal"]
    if not beam:
        raise SystemExit("fixed_mesh has no crystal column; the beam model would be empty")
    textures = {name: f"distantstock:block/tower/{name}"
                for name in sorted({q["material"] for q in fixed} | {q["material"] for q in rotor})}
    return {
        "ether_resonator": (body, textures),
        "ether_resonator_beam": (beam, textures),
        "ether_resonator_rotor": (rotor, textures),
    }


# Textures whose alpha has to go, and why each one is on the list.
#
# The handoff's preview renderer composites over a background and ignores alpha entirely, so a
# texture drawn half-transparent looks soft there and does something else here. A block model
# renders cutout, which discards anything below alpha 128 outright: crystal.png is 92..185 on
# every one of its 256 pixels, so 220 of them would be holes and the tower's crystal column would
# come out as lace. The console's dish had exactly this, and was fixed the same way.
#
# Not on the list, deliberately: axis/axis_top/frame have binary alpha and are shapes rather than
# shading, so their holes are meant; cap is a flat 140, above the cutout threshold, and only loses
# a softness nothing was going to render anyway.
FORCE_OPAQUE = {"crystal"}

# The same crystal with its own alpha left alone, for the one model that renders translucent.
#
# The coupler's models go on the translucent layer, because that is the only way a crystal reads as
# a crystal rather than as a painted stick: the handoff drew it at alpha 92..185, and the cutout
# layer throws away everything under 128. The core and the resonator keep the opaque copy — their
# crystal is a short tip inside the column, and putting their metalwork on the translucent layer to
# get it would cost more than it buys.
SOFT_CRYSTAL = "crystal_shell"


# 观察窗那一格的透明度（越低越透）。
#
# 这个数字改过三次，每次都是因为"太透"或"看不见"其中之一，所以把结论写在这儿：
#   1. 交接包画的是 alpha 40、中心 112 —— 在预览的纯色背景上很好看，进游戏就是个大洞
#      （玩家原话："中间还是能看见纹理"）；
#   2. 于是压到 88~128，窗口成了一块磨砂板。可它的用途不是挡住视线，而是让人**看见里面还剩
#      多少以太** —— 玩家原话："一圈机壳里面都看不见有流体"。磨砂和洞一样不对；
#   3. 现在 30~60：保住窗格的形状、颜色和那圈高光，但隔着它必须读得出液面。
WINDOW_ALPHA = {40: 30, 64: 34, 91: 40, 112: 60, 113: 60}


def opaque_window(image):
    out = image.copy()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = out.getpixel((x, y))
            if a in WINDOW_ALPHA:
                out.putpixel((x, y), (r, g, b, WINDOW_ALPHA[a]))
    return out


def copy_textures():
    TEX_OUT.mkdir(parents=True, exist_ok=True)
    sources = sorted((HANDOFF / "tower/textures").glob("*.png")) \
        + sorted((HANDOFF / "casing/textures").glob("*.png"))
    for source in sources:
        out = TEX_OUT / source.name
        shutil.copyfile(source, out)
        if source.stem == "ct_active":
            from PIL import Image
            opaque_window(Image.open(out).convert("RGBA")).save(out)
        if source.stem == "crystal":
            from PIL import Image
            # Two files from one drawing: the opaque copy every cutout model uses, and the soft one
            # the translucent coupler does.
            image = Image.open(out).convert("RGBA")
            soft = image.copy()
            soft.save(TEX_OUT / f"{SOFT_CRYSTAL}.png")
            image.putalpha(255)
            image.save(out)
        elif source.stem in FORCE_OPAQUE:
            from PIL import Image
            image = Image.open(out).convert("RGBA")
            image.putalpha(255)
            image.save(out)
    print(f"textures -> {TEX_OUT.relative_to(ROOT)}")


def write(name: str, quads, textures, render_type="minecraft:cutout"):
    model = build_model(quads, textures, render_type)
    MODEL_OUT.mkdir(parents=True, exist_ok=True)
    out = MODEL_OUT / f"{name}.json"
    out.write_text(json.dumps(model, indent=2) + "\n")
    print(f"  {name:26s} {len(model['elements']):3d} elements")
    return model


def turned(model: dict, dy: float = 0.0, turn: float = 0.0) -> dict:
    """A copy of a model lifted and spun about its own centre, for stacking or for icons."""
    out = json.loads(json.dumps(model))
    for element in out["elements"]:
        for point in (element["from"], element["to"]):
            point[1] = round(point[1] + dy, 4)
            if turn:
                x, z = point[0] - 8, point[2] - 8
                angle = math.radians(turn)
                point[0] = round(8 + x * math.cos(angle) - z * math.sin(angle), 4)
                point[2] = round(8 + x * math.sin(angle) + z * math.cos(angle), 4)
    return out


def merged(*models: dict) -> dict:
    out = {"textures": {}, "elements": []}
    for model in models:
        out["textures"].update(model["textures"])
        out["elements"] += model["elements"]
    return out


def write_casing():
    """The casing is the one piece that is not from a mesh: a plain cube, twice.

    Twice because the two states need different render layers, and a render layer belongs to the
    model. The window is a sheet of blue-white glass whose middle is mostly transparent; on the
    solid layer alpha is ignored and the glass would come out as flat paint. So the powered state
    gets its own model on the translucent layer, and the block state picks between them.

    Both name the same base texture on purpose. Create only rewrites a quad whose sprite is the
    shift's own original, and both casing shifts are cut from `casing_inactive` — the model never
    shows it, the connected-texture pass replaces it before anything is drawn.
    """
    texture = "distantstock:block/tower/casing_inactive"
    MODEL_OUT.mkdir(parents=True, exist_ok=True)
    for name, render_type in (("tower_casing", None), ("tower_casing_active", "minecraft:translucent")):
        model = {
            "parent": "minecraft:block/block",
            "textures": {"all": texture, "particle": texture},
            "elements": [{
                "from": [0, 0, 0], "to": [16, 16, 16],
                # cullface on every face, and not for the usual reason: it is what makes NeoForge
                # ask this block's neighbours whether they want their facing side drawn at all.
                # `hidesNeighborFace` — the hook that stops two casings drawing the wall between
                # them — is only consulted for quads that declare a cullface, so a casing model
                # without one silently keeps showing the back of the block next to it through the
                # window. See TowerCasingBlock.
                "faces": {face: {"texture": "#all", "cullface": face} for face in
                          ("down", "up", "north", "south", "west", "east")},
            }],
        }
        if render_type:
            model["render_type"] = render_type
        (MODEL_OUT / f"{name}.json").write_text(json.dumps(model, indent=2) + "\n")
    print(f"  {'tower_casing':26s}   1 elements (+ active variant)")


def write_item_models():
    """Inventory icons.

    A plain parent of the block model is enough for most of them — the mesh models inherit
    `minecraft:block/block` and so already carry the GUI transforms. The resonator is the
    exception: its arms are a second model that only a block entity renderer would draw in the
    world, so the icon merges them in by hand or the item is a bare mast.
    """
    ITEM_OUT.mkdir(parents=True, exist_ok=True)
    icon = merged(
        json.loads((MODEL_OUT / "ether_resonator.json").read_text()),
        # The beam is a renderer model in the world, so the icon has to carry it itself or the
        # item is a ring with a hole where the light should be.
        json.loads((MODEL_OUT / "ether_resonator_beam.json").read_text()),
        turned(json.loads((MODEL_OUT / "ether_resonator_rotor.json").read_text()), turn=25),
    )
    icon["parent"] = "minecraft:block/block"
    icon["render_type"] = "minecraft:cutout"
    (ITEM_OUT / "ether_resonator.json").write_text(json.dumps(icon, indent=2) + "\n")

    for name in ("tower_casing", "tower_core", "tower_coupler"):
        parent = f"distantstock:block/tower/{name}"
        (ITEM_OUT / f"{name}.json").write_text(
            json.dumps({"parent": parent}, indent=2) + "\n")
    print(f"  item icons -> {ITEM_OUT.relative_to(ROOT)}")


FACE_AXIS = {"west": 0, "east": 0, "down": 1, "up": 1, "north": 2, "south": 2}
FACE_SIGN = {"west": -1, "east": 1, "down": -1, "up": 1, "north": -1, "south": 1}
# How far a face is pushed off one it was drawn on top of, in model units. Sixteen units to the
# block, so this is under two thousandths of a block: enough to settle a depth test, far too
# little to see, and it does not move the face on screen.
COINCIDENT_STEP = 0.02


def flat_faces(model: dict):
    """Every face as (face, axis, sign, position along that axis, in-plane extent, element)."""
    for element in model["elements"]:
        for face in element["faces"]:
            axis = FACE_AXIS[face]
            span = [sorted((element["from"][i], element["to"][i]))
                    for i in range(3) if i != axis]
            yield face, axis, FACE_SIGN[face], element["from"][axis], span, element


def overlapping(span_a, span_b) -> bool:
    return all(min(a[1], b[1]) - max(a[0], b[0]) > 1e-3 for a, b in zip(span_a, span_b))


def deconflict(model: dict) -> int:
    """Push each face off any earlier face it was drawn on top of.

    These meshes were authored for the handoff's own preview renderer, which paints in list order
    and has no depth buffer: laying a brass detail plate directly on the base's top plate is free
    there, and the plate wins because it comes later. In the game both faces land on the same
    plane facing the same way and fight for every pixel of the overlap.

    Only same-facing pairs are touched. Two coplanar faces looking opposite ways are the two sides
    of one plate, and backface culling means only ever one of them is drawn.
    """
    moved = 0
    seen = []
    for face, axis, sign, at, span, element in flat_faces(model):
        collisions = sum(1 for other in seen
                         if other[0] == axis and other[1] == sign
                         and abs(other[2] - at) < 1e-3
                         and overlapping(span, other[3]))
        if collisions:
            shift = COINCIDENT_STEP * collisions * sign
            element["from"][axis] = round(element["from"][axis] + shift, 4)
            element["to"][axis] = round(element["to"][axis] + shift, 4)
            at = round(at + shift, 4)
            moved += 1
        seen.append((axis, sign, at, span))
    return moved


def coplanar_overlaps(model: dict) -> list[str]:
    """Faces that share a plane and cover the same ground on it.

    Two of those drawn together is z-fighting at best and the shredded-antenna look at worst,
    and it is the specific failure the coupler is built to avoid: a stack would otherwise put a
    cap from each block on the same seam. Checking it here rather than trusting the composition
    is the whole point of dropping those caps.
    """
    plates = [(face, axis, sign, round(at, 4), span)
              for face, axis, sign, at, span, _ in flat_faces(model)]
    clashes = []
    for i, (face_a, axis_a, sign_a, at_a, span_a) in enumerate(plates):
        for face_b, axis_b, sign_b, at_b, span_b in plates[i + 1:]:
            if axis_a != axis_b or sign_a != sign_b or abs(at_a - at_b) > 1e-3:
                continue
            if overlapping(span_a, span_b):
                clashes.append(f"{face_a}@{at_a} vs {face_b}@{at_b} on axis {axis_a}")
    return clashes


def inset_crystal_stub(model: dict) -> None:
    """Pull the core's crystal tip inside the coupler's crystal column.

    A bare core shows a two-unit crystal above its top plate, and a coupler draws that same
    crystal as a full-height column straight through where the tip stands. Stacked, the tip is
    entirely inside the column — every one of its six faces coincides with the column's, and the
    two fight along the bottom two units of the tower's crystal. Neither block can see the
    other's model, so the tip is inset instead: inside a tower it is hidden completely, and on a
    lone core it is a fiftieth of a block smaller, which is not a thing anyone can see.
    """
    for element in model["elements"]:
        if next(iter(element["faces"].values())).get("texture") != "#crystal":
            continue
        if element["from"][1] < 16.0:
            continue                      # the crystal through the base, not the tip
        for axis in range(3):
            element["from"][axis] = round(element["from"][axis] + COINCIDENT_STEP, 4)
            element["to"][axis] = round(element["to"][axis] - COINCIDENT_STEP, 4)


# The sight glass in each of the base's four sides, and the one thing the base could not show
# before: how much ether is in it. Ten units square and centred on the shell, cut into the casing
# texture's face as a separate quad a hair outside the shell — the same trick the coincident faces
# use, from the other side, so the glass and the shell never z-fight.
#
# It is drawn on the *outside* of the shell on purpose, and it only becomes visible when a
# neighbour stops hiding it: the base is a 3x3, so each of these windows sits behind one of the
# eight casings around the core. An inactive casing is a solid cube and hides it; an active one is
# a window, and the ether shows through. That is the whole reason the handoff drew `fluid.png` for
# a model that never used it.
WINDOW_LOW, WINDOW_HIGH = 3.0, 13.0
WINDOW_STEP = COINCIDENT_STEP


def fluid_window(model: dict) -> None:
    """Put a sight glass on each of the base's four sides."""
    model["textures"]["fluid"] = "distantstock:block/tower/fluid"
    added = []
    for face, axis, plane, sign in (("north", 2, 0.0, -1), ("south", 2, 16.0, 1),
                                    ("west", 0, 0.0, -1), ("east", 0, 16.0, 1)):
        low = [WINDOW_LOW, WINDOW_LOW, WINDOW_LOW]
        high = [WINDOW_HIGH, WINDOW_HIGH, WINDOW_HIGH]
        low[axis] = high[axis] = plane + sign * WINDOW_STEP
        added.append({
            "name": f"fluid_window_{face}",
            "from": low,
            "to": high,
            "faces": {face: {"texture": "#fluid", "uv": [0, 0, 16, 16]}},
        })
    model["elements"].extend(added)


# The axle the base takes from below: four units across, dead centre. The same four units Create's
# shaft draws in the block underneath, which is the whole point — the two have to meet.
AXLE_LOW, AXLE_HIGH = 6.0, 10.0
# How far above the underside plane the axle's end sits. The same nudge the coincident faces get:
# enough that the driving shaft's own end cap, which lands on the plane itself, is never coplanar
# with ours, and far too little to see — at a quarter of a unit the rim of the hole showed it.
AXLE_LIP = COINCIDENT_STEP

# The point order that makes `quad_normal` come out facing this way. The handoff renderer draws a
# quad's back, so every winding here is the reverse of the one that reads as counter-clockwise from
# outside; `wound` checks each one rather than trusting the table.
FACE_POINTS = {
    "down": lambda x0, x1, y0, y1, z0, z1: [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    "up": lambda x0, x1, y0, y1, z0, z1: [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
    "north": lambda x0, x1, y0, y1, z0, z1: [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
    "south": lambda x0, x1, y0, y1, z0, z1: [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
    "west": lambda x0, x1, y0, y1, z0, z1: [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
    "east": lambda x0, x1, y0, y1, z0, z1: [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
}
FACE_NORMAL = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1),
               "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}


def wound(face: str, points, uv, material: str) -> dict:
    """One quad, with its winding checked against the face it claims to be."""
    quad = {"material": material, "uv": uv, "points": points, "group": "fixed"}
    if quad_normal(quad) != FACE_NORMAL[face]:
        raise SystemExit(f"{face} quad wound the wrong way: {points}")
    return quad


def underside_axle(model: dict) -> None:
    """Open the base's underside hole and stand an axle in it.

    The bottom plate is one quad with a shaft hole painted on it — a black square, because the
    handoff drew it for a renderer that has no depth buffer and nothing was ever meant to be
    behind it. In the world that reads as a void, and the one thing a player checks before
    building a tower is the one thing the model did not show: whether the power down there
    actually reaches the base.

    So the plate becomes a ring with a real four-unit hole in it, and the axle stands inside from
    the hole to the top plate — invisible from anywhere but the hole, and the thing you see when
    you look up into it. With a shaft underneath, that shaft's own axle shows through instead and
    the two read as one piece; without one, the base still shows the axle it is built around
    rather than a painted black square.

    The axle stops AXLE_LIP short of the underside. The driving shaft's end cap lands on the plane
    itself, and two caps on one plane is the z-fighting the couplers are built to avoid.
    """
    plate = [q for q in model["elements"]
             if q["faces"].get("down", {}).get("texture") == "#gearbox"]
    if len(plate) != 1:
        raise SystemExit(f"expected one gearbox underside quad, found {len(plate)}")
    ring, axle = [], []

    # The plate as four strips around the hole, each keeping the texture it had. The mapping is the
    # one the plate was drawn with: u across x, v up the model's -z.
    def strip(x0, x1, z0, z1):
        points = [(x0, 0.0, z1), (x1, 0.0, z1), (x1, 0.0, z0), (x0, 0.0, z0)]
        uv = [(x0, 16 - z1), (x1, 16 - z1), (x1, 16 - z0), (x0, 16 - z0)]
        return wound("down", points, uv, "gearbox")

    ring.append(strip(0.0, 16.0, 0.0, AXLE_LOW))
    ring.append(strip(0.0, 16.0, AXLE_HIGH, 16.0))
    ring.append(strip(0.0, AXLE_LOW, AXLE_LOW, AXLE_HIGH))
    ring.append(strip(AXLE_HIGH, 16.0, AXLE_LOW, AXLE_HIGH))

    def side(face, points, horizontal):
        # `axis` is a stripe: four opaque texels at 6..10 and nothing else. Every side sample is
        # taken from inside the stripe, one texel per model unit.
        uv = [(6.0 + (h - AXLE_LOW), 16.0 - y) for h, y in horizontal]
        return wound(face, points, uv, "axis")

    x0, x1, z0, z1 = AXLE_LOW, AXLE_HIGH, AXLE_LOW, AXLE_HIGH
    y0, y1 = AXLE_LIP, 16.0
    # The end cap is the one face not taken from the stripe: `axis_top` carries the sawn-off end,
    # in the same 6..10 square the plate's own mapping lands on.
    axle.append(wound("down", FACE_POINTS["down"](x0, x1, y0, y1, z0, z1),
                      [(x0, 16 - z1), (x1, 16 - z1), (x1, 16 - z0), (x0, 16 - z0)], "axis_top"))
    axle.append(side("north", FACE_POINTS["north"](x0, x1, y0, y1, z0, z1),
                     [(x0, y1), (x1, y1), (x1, y0), (x0, y0)]))
    axle.append(side("south", FACE_POINTS["south"](x0, x1, y0, y1, z0, z1),
                     [(x0, y1), (x1, y1), (x1, y0), (x0, y0)]))
    axle.append(side("west", FACE_POINTS["west"](x0, x1, y0, y1, z0, z1),
                     [(z1, y1), (z0, y1), (z0, y0), (z1, y0)]))
    axle.append(side("east", FACE_POINTS["east"](x0, x1, y0, y1, z0, z1),
                     [(z0, y1), (z1, y1), (z1, y0), (z0, y0)]))

    added = [quad_to_element(q, model["textures"]) for q in ring + axle]
    model["elements"] = [q for q in model["elements"] if q is not plate[0]] + added
    model["textures"]["axis"] = "distantstock:block/tower/axis"
    model["textures"]["axis_top"] = "distantstock:block/tower/axis_top"


def verify_against_handoff():
    """Rebuild the handoff's own tower out of the converted models and compare renders.

    `render_handoff.py` reproduces `tower/tower.png` pixel for pixel from the meshes, so it is a
    true oracle: if the tower assembled from the converted, in-game-shaped models renders the same,
    then the conversion kept the geometry, the uv rectangles and the face directions. This is the
    only hard evidence that the conversion is right; the model files themselves are just JSON.
    """
    import sys
    sys.path.insert(0, str(ROOT / "scripts"))
    import preview_block_art as bake

    def model(name):
        return json.loads((MODEL_OUT / f"{name}.json").read_text())

    def placed(name, dy, turn=0.0):
        return turned(model(name), dy, turn)

    tower = merged(
        placed("tower_core", 0),
        placed("tower_coupler_top", 16),
        placed("tower_coupler_middle", 32),
        placed("tower_coupler_middle", 48),
        placed("ether_resonator", 64),
        placed("ether_resonator_beam", 64),
        placed("ether_resonator_rotor", 64, turn=25),
    )
    clashes = coplanar_overlaps(tower)
    if clashes:
        for clash in clashes:
            print(f"  COPLANAR OVERLAP: {clash}")
        raise SystemExit(f"{len(clashes)} coincident faces in the assembled tower")
    print(f"  no coincident faces across {len(tower['elements'])} elements")
    # preview_block_art's renderer takes a pivot, which matters here: the tower stands 86 units
    # tall and pivoting about the block centre pushes the resonator off the top of the frame.
    bake.model = lambda name, _m=tower: _m
    bake.texture.cache_clear()
    image = bake.render("tower", yaw=30, pitch=23, size=(640, 970), scale=9, center=(8, 40, 8))
    out = ROOT / "build/art/tower-rebuilt.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out)
    print(f"  rebuilt tower -> {out.relative_to(ROOT)}")
    print(f"  compare against {HANDOFF.relative_to(ROOT)}/tower/tower.png")


def main():
    copy_textures()
    print("models:")

    core = load(HANDOFF / "tower/core_mesh.json")
    core_textures = {name: f"distantstock:block/tower/{name}"
                     for name in sorted({q["material"] for q in core})}
    core_model = write("tower_core", core, core_textures)
    inset_crystal_stub(core_model)
    underside_axle(core_model)
    fluid_window(core_model)
    (MODEL_OUT / "tower_core.json").write_text(json.dumps(core_model, indent=2) + "\n")
    write_casing()

    # Translucent: see the crystal note above. The frame in the same model goes along with it, which
    # is what a translucent layer costs — the alternative is a second block entity and a renderer
    # drawing the crystal on its own, for one shade of one texture.
    coupler_textures_ = coupler_textures()
    for name, (quads, _ignored) in coupler_models().items():
        write(name, quads, coupler_textures_, "minecraft:translucent")

    for name, (quads, textures) in resonator_models().items():
        write(name, quads, textures)

    write_item_models()
    verify_against_handoff()


if __name__ == "__main__":
    main()
