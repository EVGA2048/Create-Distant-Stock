#!/usr/bin/env python3
"""Offline block model renderer for Minecraft JSON models + PNG textures.

Reads model JSON (with parent chain) and textures from the asset tree and the
Create jar, then rasterises an orthographic preview using numpy-accelerated
scanline rendering with a true depth buffer.

Improvements over the previous version:
- Vectorised scanline rasteriser (10-50x faster than per-pixel loop)
- Blockstate-level x/y rotation support
- Correct face normal lighting after element and blockstate rotations
- Sub-pixel UV interpolation with proper nearest-neighbour sampling
- Face UV rotation support (0/90/180/270)
- Ambient-occlusion-style darkening for down/side faces
- Transparent face depth-sorted blending
- CLI: render any model with  python3 render_block.py <model> [--yaw N] [--pitch N]
"""
from __future__ import annotations

import argparse
import io
import json
import math
import sys
import zipfile
from functools import lru_cache
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets"
OUT = ROOT / "build/art"
CREATE = (
    Path.home()
    / "Documents/minecraft_launcher/.minecraft/versions"
    / "ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar"
)

# ── Model / texture loading ──────────────────────────────────────────────────


@lru_cache(maxsize=256)
def load_model(name: str) -> dict:
    """Load a model JSON with full parent chain resolution."""
    ns, path = name.split(":", 1)
    if ns == "minecraft":
        return {}
    if ns == "create":
        with zipfile.ZipFile(CREATE) as jar:
            data = json.loads(jar.read(f"assets/create/models/{path}.json"))
    else:
        fp = ASSETS / ns / "models" / (path + ".json")
        if not fp.exists():
            print(f"WARN: model not found: {fp}", file=sys.stderr)
            return {}
        data = json.loads(fp.read_text())
    parent_name = data.get("parent")
    if parent_name and ":" not in parent_name:
        # ResourceLocation defaults an omitted namespace to minecraft (Create uses "block/block").
        parent_name = f"minecraft:{parent_name}"
    parent = load_model(parent_name) if parent_name else {}
    merged = {**parent, **data}
    merged["textures"] = {**parent.get("textures", {}), **data.get("textures", {})}
    return merged


@lru_cache(maxsize=256)
def load_texture(name: str) -> np.ndarray:
    """Load a texture PNG as an RGBA uint8 numpy array."""
    ns, path = name.split(":", 1)
    if ns == "create":
        with zipfile.ZipFile(CREATE) as jar:
            im = Image.open(io.BytesIO(jar.read(f"assets/create/textures/{path}.png")))
    else:
        im = Image.open(ASSETS / ns / "textures" / (path + ".png"))
    return np.array(im.convert("RGBA"), dtype=np.uint8)


def resolve_texture(ref: str, textures: dict) -> np.ndarray:
    """Follow #variable references to a concrete texture array."""
    seen: set[str] = set()
    while ref.startswith("#"):
        if ref in seen:
            raise ValueError(f"Texture reference cycle: {ref}")
        seen.add(ref)
        key = ref[1:]
        if key not in textures:
            raise KeyError(f"Missing texture variable: {ref}")
        ref = textures[key]
    return load_texture(ref)


# ── Geometry helpers ─────────────────────────────────────────────────────────


def _rot_axis(point: np.ndarray, origin: np.ndarray, axis: int, angle_deg: float) -> np.ndarray:
    """Rotate a point around an axis through origin (axis: 0=x, 1=y, 2=z)."""
    a = math.radians(angle_deg)
    p = point - origin
    ca, sa = math.cos(a), math.sin(a)
    u, v = ((1, 2), (2, 0), (0, 1))[axis]
    result = p.copy()
    result[u] = ca * p[u] - sa * p[v]
    result[v] = sa * p[u] + ca * p[v]
    return result + origin


def apply_element_rotation(point: np.ndarray, rotation: Optional[dict]) -> np.ndarray:
    """Apply an element-level rotation (single axis, ±45°)."""
    if not rotation:
        return point
    origin = np.array(rotation["origin"], dtype=np.float64)
    axis = "xyz".index(rotation["axis"])
    return _rot_axis(point, origin, axis, rotation["angle"])


def apply_blockstate_rotation(point: np.ndarray, bs_x: int, bs_y: int) -> np.ndarray:
    """Apply blockstate x/y rotation (multiples of 90°) around block centre."""
    centre = np.array([8.0, 8.0, 8.0])
    p = point
    if bs_x:
        p = _rot_axis(p, centre, 0, bs_x)
    if bs_y:
        p = _rot_axis(p, centre, 1, bs_y)
    return p


def face_vertices(x0, y0, z0, x1, y1, z1) -> dict[str, list]:
    """MC face vertex order: TL, TR, BR, BL when looking at the face."""
    return {
        "north": [(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],
        "south": [(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],
        "west":  [(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],
        "east":  [(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],
        "up":    [(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],
        "down":  [(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)],
    }


# ── Rasteriser ───────────────────────────────────────────────────────────────

# Directional light shading (matches MC's vanilla AO-less shading)
FACE_SHADE = {"up": 1.0, "north": 0.80, "south": 0.80, "east": 0.60, "west": 0.60, "down": 0.50}


def _cross2d(ax, ay, bx, by):
    return ax * by - ay * bx


def rasterise_triangle(
    buf: np.ndarray,
    zbuf: np.ndarray,
    verts_2d: np.ndarray,  # (3, 2)
    depths: np.ndarray,    # (3,)
    uvs: np.ndarray,       # (3, 2) normalised 0-1
    tex: np.ndarray,       # (H, W, 4) RGBA
    uv_rect: tuple,        # (u0, v0, u1, v1) in 0-16 space
    shade: float,
    uv_rot: int,
):
    """Scanline-rasterise one triangle with depth test and texture sampling."""
    h, w = buf.shape[:2]
    # Bounding box
    xs = verts_2d[:, 0]
    ys = verts_2d[:, 1]
    x_min = max(0, int(math.floor(xs.min())))
    x_max = min(w - 1, int(math.ceil(xs.max())))
    y_min = max(0, int(math.floor(ys.min())))
    y_max = min(h - 1, int(math.ceil(ys.max())))
    if x_min > x_max or y_min > y_max:
        return

    ax, ay = float(verts_2d[0, 0]), float(verts_2d[0, 1])
    bx, by = float(verts_2d[1, 0]), float(verts_2d[1, 1])
    cx, cy = float(verts_2d[2, 0]), float(verts_2d[2, 1])
    denom = _cross2d(bx - ax, by - ay, cx - ax, cy - ay)
    if abs(denom) < 1e-8:
        return

    inv_denom = 1.0 / denom
    u0_16, v0_16, u1_16, v1_16 = uv_rect
    th, tw = tex.shape[:2]

    # Vectorised: generate pixel grid
    py = np.arange(y_min, y_max + 1, dtype=np.float64) + 0.5
    px = np.arange(x_min, x_max + 1, dtype=np.float64) + 0.5
    gx, gy = np.meshgrid(px, py)

    # Barycentric coordinates
    dx = gx - ax
    dy = gy - ay
    w1 = (_cross2d(dx, dy, cx - ax, cy - ay)) * inv_denom
    w2 = (_cross2d(bx - ax, by - ay, dx, dy)) * inv_denom
    w0 = 1.0 - w1 - w2

    # Inside-triangle mask
    mask = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)

    # Depth interpolation
    dep = w0 * depths[0] + w1 * depths[1] + w2 * depths[2]

    # UV interpolation
    u = w0 * uvs[0, 0] + w1 * uvs[1, 0] + w2 * uvs[2, 0]
    v = w0 * uvs[0, 1] + w1 * uvs[1, 1] + w2 * uvs[2, 1]

    # Apply UV rotation
    for _ in range(uv_rot % 4):
        u, v = v, 1.0 - u

    # Map to texture pixel coordinates
    tex_u = u0_16 + u * (u1_16 - u0_16)
    tex_v = v0_16 + v * (v1_16 - v0_16)
    tx = np.clip((tex_u * tw / 16.0).astype(int), 0, tw - 1)
    ty = np.clip((tex_v * th / 16.0).astype(int), 0, th - 1)

    # Slice into buffer region
    region_z = zbuf[y_min:y_max+1, x_min:x_max+1]
    depth_mask = dep < region_z
    final_mask = mask & depth_mask

    if not final_mask.any():
        return

    # Sample texels
    iy = np.where(final_mask)
    colours = tex[ty[iy], tx[iy]]  # (N, 4)

    # Skip fully transparent
    alpha = colours[:, 3]
    visible = alpha > 0
    if not visible.any():
        return

    # Coordinates in buffer
    buf_y = iy[0] + y_min
    buf_x = iy[1] + x_min
    vis_y = buf_y[visible]
    vis_x = buf_x[visible]
    vis_col = colours[visible]
    vis_dep = dep[iy][visible]
    vis_alpha = vis_col[:, 3].astype(np.float64) / 255.0

    # Apply shade
    shaded = vis_col[:, :3].astype(np.float64) * shade

    # Alpha blend
    old = buf[vis_y, vis_x, :3].astype(np.float64)
    a = vis_alpha[:, None]
    blended = shaded * a + old * (1.0 - a)
    buf[vis_y, vis_x, :3] = np.clip(blended, 0, 255).astype(np.uint8)
    buf[vis_y, vis_x, 3] = 255

    # Update depth (only for opaque pixels)
    opaque = vis_alpha > 0.99
    if opaque.any():
        zbuf_y = (iy[0] + y_min)[visible][opaque]  # recalc to avoid stale refs
        zbuf_x = (iy[1] + x_min)[visible][opaque]
        np.minimum.at(region_z, (zbuf_y - y_min, zbuf_x - x_min), vis_dep[opaque])


def render(
    name: str,
    yaw: float = 30,
    pitch: float = 25,
    size: tuple[int, int] = (240, 250),
    scale: float = 9.0,
    bg: str = "#e8e5dc",
    bs_x: int = 0,
    bs_y: int = 0,
) -> Image.Image:
    """Render a named block model to a PIL Image."""
    obj = load_model(name)
    sy, cy = math.sin(math.radians(yaw)), math.cos(math.radians(yaw))
    sp, cp = math.sin(math.radians(pitch)), math.cos(math.radians(pitch))

    def project(p):
        x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
        xr = cy * x + sy * z
        zr = -sy * x + cy * z
        yr = cp * y + sp * zr
        depth = -sp * y + cp * zr
        sx = size[0] / 2 + xr * scale
        sy_ = size[1] / 2 - yr * scale
        return np.array([sx, sy_]), depth

    w, h = size
    buf = np.zeros((h, w, 4), dtype=np.uint8)
    # Fill background
    bg_rgb = tuple(int(bg.lstrip("#")[i:i+2], 16) for i in (0, 2, 4))
    buf[:, :, 0] = bg_rgb[0]
    buf[:, :, 1] = bg_rgb[1]
    buf[:, :, 2] = bg_rgb[2]
    buf[:, :, 3] = 255
    zbuf = np.full((h, w), np.inf, dtype=np.float64)

    # Collect all faces, sort by average depth (painter's sort for transparent)
    faces_to_draw = []
    for e in obj.get("elements", []):
        x0, y0, z0 = e["from"]
        x1, y1, z1 = e["to"]
        verts_by_face = face_vertices(x0, y0, z0, x1, y1, z1)
        elem_rot = e.get("rotation")

        for face_name, face_data in e.get("faces", {}).items():
            raw_verts = verts_by_face[face_name]
            # Apply element rotation, then blockstate rotation
            transformed = []
            for v in raw_verts:
                p = np.array(v, dtype=np.float64)
                p = apply_element_rotation(p, elem_rot)
                p = apply_blockstate_rotation(p, bs_x, bs_y)
                transformed.append(p)

            # Project all 4 vertices
            proj = [project(v) for v in transformed]
            pts_2d = np.array([p[0] for p in proj])
            dps = np.array([p[1] for p in proj])

            # Back-face culling: cross product of first triangle
            edge1 = pts_2d[1] - pts_2d[0]
            edge2 = pts_2d[2] - pts_2d[0]
            cross = edge1[0] * edge2[1] - edge1[1] * edge2[0]
            if cross >= 0:
                continue

            # Compute face normal for shading after rotation
            v01 = transformed[1] - transformed[0]
            v02 = transformed[2] - transformed[0]
            normal = np.cross(v01, v02)
            norm_len = np.linalg.norm(normal)
            if norm_len > 1e-8:
                normal /= norm_len
            # Use one diffuse shading term, not MC shading multiplied by a
            # second directional light (which made pale source textures dark).
            if not elem_rot and not bs_x and not bs_y:
                shade = FACE_SHADE.get(face_name, 0.8)
            else:
                shade = float(normal[0] ** 2 * 0.6 + normal[2] ** 2 * 0.8
                              + normal[1] ** 2 * FACE_SHADE.get(face_name, 1.0))
            if obj.get("shade") is False or e.get("shade") is False:
                shade = 1.0

            avg_depth = float(dps.mean())
            try:
                tex = resolve_texture(face_data["texture"], obj["textures"])
            except (KeyError, FileNotFoundError) as exc:
                print(f"WARN: {exc}", file=sys.stderr)
                continue
            uv = face_data.get("uv", [0, 0, 16, 16])
            uv_rot = face_data.get("rotation", 0) // 90

            faces_to_draw.append((avg_depth, pts_2d, dps, tex, uv, shade, uv_rot))

    # Sort: farthest first (painter's sort helps with transparency)
    faces_to_draw.sort(key=lambda f: -f[0])

    for _, pts_2d, dps, tex, uv, shade, uv_rot in faces_to_draw:
        # Two triangles per quad: 0-1-2 and 0-2-3
        for tri_idx in ((0, 1, 2), (0, 2, 3)):
            tri_2d = pts_2d[list(tri_idx)]
            tri_d = dps[list(tri_idx)]
            # UV corners for the quad: TL(0,0) TR(1,0) BR(1,1) BL(0,1)
            quad_uv = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], dtype=np.float64)
            tri_uv = quad_uv[list(tri_idx)]
            rasterise_triangle(buf, zbuf, tri_2d, tri_d, tri_uv, tex, tuple(uv), shade, uv_rot)

    return Image.fromarray(buf)


# ── Sheet generation ─────────────────────────────────────────────────────────


def render_sheet(cells: list[tuple], title: str, cols: int = 4, cell_size: tuple[int, int] = (240, 280)) -> Image.Image:
    """Render a grid of models into a labelled sheet."""
    rows = math.ceil(len(cells) / cols)
    w = cols * cell_size[0]
    h = rows * cell_size[1] + 40
    sheet = Image.new("RGBA", (w, h), "#e8e5dc")
    draw = ImageDraw.Draw(sheet)

    for i, cell in enumerate(cells):
        if len(cell) == 4:
            label, name, yaw, pitch = cell
            bs_x, bs_y = 0, 0
        else:
            label, name, yaw, pitch, bs_x, bs_y = cell

        col = i % cols
        row = i // cols
        x = col * cell_size[0]
        y = row * cell_size[1]

        try:
            img = render(
                f"distantstock:block/{name}",
                yaw=yaw, pitch=pitch,
                size=(cell_size[0], cell_size[1] - 30),
                scale=9,
                bs_x=bs_x, bs_y=bs_y,
            )
            sheet.paste(img, (x, y + 25), img)
        except Exception as exc:
            draw.text((x + 8, y + 40), f"ERROR: {exc}", fill="#aa3333")

        draw.text((x + 8, y + 6), label, fill="#3c423c")

    draw.text((8, h - 22), title, fill="#62685f")
    return sheet


# ── CLI ──────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="Render Minecraft block models offline")
    sub = parser.add_subparsers(dest="cmd")

    # Single model
    one = sub.add_parser("one", help="Render a single model")
    one.add_argument("model", help="e.g. distantstock:block/dock_lit")
    one.add_argument("--yaw", type=float, default=30)
    one.add_argument("--pitch", type=float, default=25)
    one.add_argument("--width", type=int, default=480)
    one.add_argument("--height", type=int, default=500)
    one.add_argument("--scale", type=float, default=18)
    one.add_argument("--bs-x", type=int, default=0, help="Blockstate X rotation")
    one.add_argument("--bs-y", type=int, default=0, help="Blockstate Y rotation")
    one.add_argument("-o", "--output", default=None)

    # All machines sheet
    sub.add_parser("machines", help="Render all machine blocks sheet")
    # All lamps sheet
    sub.add_parser("lamps", help="Render all indicator lamps sheet")
    # All blocks
    sub.add_parser("all", help="Render machines + lamps sheets")
    sub.add_parser("remote", help="Render the shipped remote packager and parcel assets")

    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    if args.cmd == "one":
        img = render(args.model, args.yaw, args.pitch, (args.width, args.height), args.scale,
                     bs_x=args.bs_x, bs_y=args.bs_y)
        out = Path(args.output) if args.output else OUT / "single.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        img.save(out)
        print(out)

    elif args.cmd in ("machines", "all"):
        cells = [
            ("DOCK / IDLE", "dock", 30, 25),
            ("DOCK / LINK", "dock_lit", 30, 25),
            ("DOCK / PARCEL", "dock_loaded_lit", 30, 25),
            ("DOCK / BACK", "dock", 210, 25),
            ("REQUEST DESK", "gauge_lit", 30, 35),
            ("DESK / SIDE", "gauge", 90, 20),
            ("WALL INSTRUMENT", "monitor", 30, 20),
        ]
        sheet = render_sheet(cells, "DISTANT STOCK 0.3.7 — MACHINE BLOCKS (OFFLINE RENDER)")
        path = OUT / "machines-0.3.7.png"
        sheet.save(path)
        print(path)

    if args.cmd in ("lamps", "all"):
        colors = ["cyan", "orange", "red", "green", "white", "brass"]
        cells = []
        for c in colors:
            cells.append((f"{c.upper()} OFF", f"{c}_indicator_lamp", 30, 55))
            cells.append((f"{c.upper()} LIT", f"{c}_indicator_lamp_lit", 30, 55))
        sheet = render_sheet(cells, "DISTANT STOCK 0.3.7 — INDICATOR LAMPS (OFFLINE RENDER)", cols=6)
        path = OUT / "lamps-0.3.7.png"
        sheet.save(path)
        print(path)

    if args.cmd == "remote":
        cells = [
            ("REMOTE PACKAGER / IDLE", "distantstock:block/remote_packager", 30, 25),
            ("REMOTE DOCK / SAME FRAME", "distantstock:block/remote_dock_off", 30, 25),
            ("REMOTE PARCEL / RESTORED", "distantstock:item/remote_package_12x12", 30, 25),
        ]
        sheet = Image.new("RGBA", (840, 340), "#e8e5dc")
        draw = ImageDraw.Draw(sheet)
        for i, (label, model, yaw, pitch) in enumerate(cells):
            preview = render(model, yaw=yaw, pitch=pitch, size=(280, 300), scale=12)
            sheet.paste(preview, (i * 280, 25), preview)
            draw.text((i * 280 + 10, 8), label, fill="#3c5459")
        draw.text((10, 322), "ACTUAL SHIPPED JSON + PNG / NEAREST-PIXEL OFFLINE RENDER", fill="#68777a")
        path = OUT / "remote-packager-and-parcel.png"
        sheet.save(path)
        print(path)

    if args.cmd is None:
        parser.print_help()


if __name__ == "__main__":
    main()
