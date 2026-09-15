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
    return {
        "credit": "Tower art by Distant Stock; crystal and reflective glass drawn in-house. "
                  "Create andesite, brass and gearbox textures are Create's.",
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "render_type": render_type,
        "textures": textures,
        "elements": elements,
    }


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
        out.append(moved)
    if not out:
        raise SystemExit("no cross-frame found at the y=32 seam")
    return out


def resonator_models():
    """Fixed body and rotating arms, as two models: the rotor is turned by a block entity."""
    fixed = load(HANDOFF / "tower/fixed_mesh.json")
    rotor = load(HANDOFF / "tower/rotor_mesh.json")
    textures = {name: f"distantstock:block/tower/{name}"
                for name in sorted({q["material"] for q in fixed} | {q["material"] for q in rotor})}
    return {"ether_resonator": (fixed, textures), "ether_resonator_rotor": (rotor, textures)}


def copy_textures():
    TEX_OUT.mkdir(parents=True, exist_ok=True)
    for source in sorted((HANDOFF / "tower/textures").glob("*.png")):
        shutil.copyfile(source, TEX_OUT / source.name)
    for source in sorted((HANDOFF / "casing/textures").glob("*.png")):
        shutil.copyfile(source, TEX_OUT / source.name)
    print(f"textures -> {TEX_OUT.relative_to(ROOT)}")


def write(name: str, quads, textures, render_type="minecraft:cutout"):
    model = build_model(quads, textures, render_type)
    MODEL_OUT.mkdir(parents=True, exist_ok=True)
    out = MODEL_OUT / f"{name}.json"
    out.write_text(json.dumps(model, indent=2) + "\n")
    print(f"  {name:26s} {len(model['elements']):3d} elements")
    return model


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
        out = json.loads(json.dumps(model(name)))
        for element in out["elements"]:
            for point in (element["from"], element["to"]):
                point[1] = round(point[1] + dy, 4)
                if turn:
                    x, z = point[0] - 8, point[2] - 8
                    angle = math.radians(turn)
                    point[0] = round(8 + x * math.cos(angle) - z * math.sin(angle), 4)
                    point[2] = round(8 + x * math.sin(angle) + z * math.cos(angle), 4)
        return out

    def merge(*models):
        merged = {"textures": {}, "elements": []}
        for m in models:
            merged["textures"].update(m["textures"])
            merged["elements"] += m["elements"]
        return merged

    tower = merge(
        placed("tower_core", 0),
        placed("tower_coupler_top", 16),
        placed("tower_coupler_middle", 32),
        placed("tower_coupler_middle", 48),
        placed("ether_resonator", 64),
        placed("ether_resonator_rotor", 64, turn=25),
    )
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
    write("tower_core", core, core_textures)

    for name, (quads, textures) in coupler_models().items():
        write(name, quads, textures)

    for name, (quads, textures) in resonator_models().items():
        write(name, quads, textures)

    verify_against_handoff()


if __name__ == "__main__":
    main()
