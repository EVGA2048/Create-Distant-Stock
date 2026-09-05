#!/usr/bin/env python3
"""Recolor the original 0.3.5 requester silhouette; keep the manual unchanged.

First import (also restores and verifies):
  python3 scripts/gen_item_art.py --import-requester-jar build/Create-Distant-Stock-0.3.5+mc1.21.1.jar
Repeatable asset build (no JAR needed):
  python3 scripts/gen_item_art.py
Optional independent JAR comparison:
  python3 scripts/gen_item_art.py --verify-requester-jar build/Create-Distant-Stock-0.3.5+mc1.21.1.jar

Original model geometry, UVs and poses are preserved exactly. The 0.3.5 texture
is recolored by UV material regions, keeping every original alpha value.
Sources retain their original bytes; existing upstream MIT notices apply.
Only requester resources and build/item-art previews are written by default.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/distantstock"
SOURCES = ROOT / "scripts/art_sources"
PREVIEW = ROOT / "build/item-art"
RESOURCES = {
    "models/item/requester.json": "requester-0.3.5.json",
    "textures/item/requester.png": "requester-0.3.5.png",
}
COLORS = {
    "edge": "#393833", "brass": "#B28D49", "brassLight": "#E2C17C",
    "brassDark": "#795B30", "grip": "#443D33", "gripLight": "#5B5142",
    "gripDark": "#2E2A24", "paper": "#E9DDBB", "paperDark": "#C1AD80",
    "ink": "#675B43", "link": "#63BBD0", "linkDark": "#346875",
}


def rgba(name):
    value = COLORS.get(name, name).lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def manual():
    """Retained original manual recipe; requester restoration never saves it."""
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)
    def rect(coords, color):
        d.rectangle(coords, fill=rgba(color))
    rect((2, 2, 12, 14), "edge")
    rect((3, 1, 12, 12), "gripDark")
    rect((4, 2, 12, 12), "grip")
    rect((5, 3, 11, 11), "gripLight")
    rect((3, 3, 3, 12), "brassDark")
    rect((3, 13, 12, 13), "paperDark")
    rect((4, 12, 12, 12), "paper")
    rect((12, 3, 13, 12), "edge")
    rect((12, 4, 12, 11), "paper")
    rect((12, 6, 12, 6), "paperDark")
    rect((12, 9, 12, 9), "paperDark")
    rect((4, 2, 5, 2), "brassLight")
    rect((10, 2, 11, 2), "brass")
    rect((4, 10, 4, 11), "brass")
    rect((10, 11, 11, 11), "brassDark")
    rect((6, 4, 10, 5), "paper")
    rect((7, 5, 9, 5), "ink")
    rect((6, 7, 7, 8), "brassLight")
    rect((9, 8, 10, 9), "brass")
    rect((8, 8, 8, 8), "link")
    rect((5, 13, 5, 14), "linkDark")
    return im


def restore(import_jar=None, verify_jar=None):
    reference = {}
    if import_jar or verify_jar:
        with zipfile.ZipFile(import_jar or verify_jar) as archive:
            reference = {path: archive.read("assets/distantstock/" + path) for path in RESOURCES}
    if import_jar:
        SOURCES.mkdir(parents=True, exist_ok=True)
        for path, name in RESOURCES.items():
            (SOURCES / name).write_bytes(reference[path])
    source_data = {path: (SOURCES / name).read_bytes() for path, name in RESOURCES.items()}
    for path, data in source_data.items():
        if reference:
            assert data == reference[path], f"Source differs from JAR: {path}"
        print(f"PASS original source SHA256: {path}\n  {hashlib.sha256(data).hexdigest()}")
    original_model = json.loads(source_data["models/item/requester.json"])
    model = json.loads(source_data["models/item/requester.json"])
    model["credit"] = "Layout and original texture from Create: Mobile Packages (MIT, Tim Heidler). Distant Stock warm metal, wood and brass recolor; cyan interconnection accents."
    original = Image.open(io.BytesIO(source_data["textures/item/requester.png"])).convert("RGBA")
    texture = recolor(original)
    assert texture.getchannel("A").tobytes() == original.getchannel("A").tobytes()
    assert model["elements"] == original_model["elements"]
    assert model["display"] == original_model["display"]
    model_text = source_data["models/item/requester.json"].decode("utf-8")
    model_text = model_text.replace(json.dumps(original_model["credit"]), json.dumps(model["credit"]), 1)
    (ASSETS / "models/item/requester.json").write_text(model_text)
    texture.save(ASSETS / "textures/item/requester.png")
    # Check persisted files, including every face's sampled alpha mask.
    saved_model = json.loads((ASSETS / "models/item/requester.json").read_bytes())
    saved_texture = Image.open(ASSETS / "textures/item/requester.png").convert("RGBA")
    assert saved_model["elements"] == original_model["elements"]
    assert saved_model["display"] == original_model["display"]
    count = 0
    for element in saved_model["elements"]:
        for face in element["faces"].values():
            assert len(face["uv"]) == 4 and all(0 <= v <= 16 for v in face["uv"])
            u0,v0,u1,v1 = [round(v*original.size[i%2]/16) for i,v in enumerate(face["uv"])]
            bounds = min(u0,u1),min(v0,v1),max(u0,u1),max(v0,v1)
            assert saved_texture.crop(bounds).getchannel("A").tobytes() == original.crop(bounds).getchannel("A").tobytes()
            count += 1
    assert saved_texture.getchannel("A").tobytes() == original.getchannel("A").tobytes()
    assert saved_texture.tobytes() != original.tobytes()
    print(f"PASS: {len(model['elements'])} original elements, {count} unchanged face UVs, all original display poses; full and per-face alpha masks identical.")
    return model, texture


def recolor(original):
    """0.3.5 has the same 32-unit atlas, stored at 4x; preserve its detail/alpha.

    The available mobile_packager.bbmodel uses an unrelated 16-unit tool atlas,
    so copying that texture would break these UVs. Use the matching 0.3.5 source.
    Quantized palettes preserve hard pixel shading, without adding gradients.
    """
    palettes = {
        "iron": ("#45443F", "#616158", "#828176", "#A09D8D", "#C0BDAB"),
        "wood": ("#352B23", "#504031", "#70533A", "#947048", "#B79262"),
        "brass": ("#554129", "#795B30", "#A68142", "#C6A057", "#E2C17C"),
        "cyan": ("#294E54", "#346875", "#478C9C", "#63BBD0", "#A5D7DE"),
    }
    out = original.copy()
    sx,sy = original.width/32,original.height/32
    for y in range(original.height):
        for x in range(original.width):
            r,g,b,a = original.getpixel((x,y))
            if not a:
                continue
            u,v = x/sx,y/sy
            luminance = (r*3+g*6+b)/10
            level = min(4, max(0, int(luminance/51)))
            material = None
            if 0 <= u < 10 and 0 <= v < 14:
                # Only the old warped-colored frame becomes warm cast metal;
                # the paper field and brass details keep their material split.
                if g > r*1.08 and b > r*1.04:
                    material = "iron"
                elif r > b*1.35 and g < r*.85:
                    material = "brass"
            elif 10 <= u < 20 and 0 <= v < 14:
                material = "wood"
            elif 13 <= u < 27 and 14 <= v < 16:
                material = "iron"
            elif 3 <= u < 13 and 17 <= v < 19:
                material = "iron"
            elif 13 <= u < 18 and 16 <= v < 21:
                material = "brass"
            elif (0 <= u < 3 and 14 <= v < 24) or (3 <= u < 13 and 14 <= v < 17):
                material = "brass"
            # Matching narrow collar strips on the crossed aerial's UV islands.
            if (0 <= u < 3 and 21 <= v < 22) or (10 <= u < 11 and 14 <= v < 17):
                material = "cyan"
                level = 3 if level >= 2 else 1
            # Small tuning tab and tip: deliberately not a cyan body or screen.
            if ((19 <= u < 20 and 16 <= v < 18) or (7 <= u < 8 and 19 <= v < 20)
                    or (4.5 <= u < 5.5 and 11 <= v < 12)):
                material = "cyan"
                level = 3
            if material:
                color = palettes[material][level]
                out.putpixel((x,y),rgba(color)[:3]+(a,))
    return out


def rotate(point, axis, angle, origin=(0, 0, 0), rescale=False):
    p = [point[i] - origin[i] for i in range(3)]
    a, b = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[axis]
    cosine, sine = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    p[a], p[b] = cosine*p[a]-sine*p[b], sine*p[a]+cosine*p[b]
    if rescale:
        p[a], p[b] = p[a]/cosine, p[b]/cosine
    return tuple(p[i]+origin[i] for i in range(3))


def render(model, texture, yaw, pitch, size=(280, 360)):
    """Orthographic preview with UV flips, face/element rotation and alpha cutouts."""
    faces = []
    for e in model["elements"]:
        x0,y0,z0 = e["from"]
        x1,y1,z1 = e["to"]
        corners = {
            "north": [(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],
            "south": [(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],
            "west": [(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],
            "east": [(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],
            "up": [(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],
            "down": [(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)],
        }
        for name, face in e["faces"].items():
            points = corners[name]
            if "rotation" in e:
                points = [rotate(p, **e["rotation"]) for p in points]
            points = [rotate(rotate(p, "y", yaw), "x", -pitch) for p in points]
            u0,v0,u1,v1 = [round(v*texture.size[i%2]/16) for i,v in enumerate(face["uv"])]
            tile = texture.crop((min(u0,u1), min(v0,v1), max(u0,u1), max(v0,v1)))
            if u1 < u0:
                tile = tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            if v1 < v0:
                tile = tile.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
            if face.get("rotation"):
                tile = tile.rotate(-face["rotation"], expand=True)
            faces.append((points, tile))
    all_points = [p for points, _ in faces for p in points]
    lo = [min(p[i] for p in all_points) for i in range(3)]
    hi = [max(p[i] for p in all_points) for i in range(3)]
    center = [(a+b)/2 for a,b in zip(lo,hi)]
    scale = min((size[0]-40)/(hi[0]-lo[0]), (size[1]-50)/(hi[1]-lo[1]))
    def project(p):
        return ((size[0]/2+(p[0]-center[0])*scale, size[1]/2-(p[1]-center[1])*scale), p[2])
    polygons = []
    for points, tile in faces:
        pp = [project(p)[0] for p in points]
        cross = (pp[1][0]-pp[0][0])*(pp[2][1]-pp[0][1])-(pp[1][1]-pp[0][1])*(pp[2][0]-pp[0][0])
        if cross >= 0:
            continue
        def point(u,v):
            return tuple(points[0][i]+u*(points[1][i]-points[0][i])+v*(points[3][i]-points[0][i]) for i in range(3))
        w,h = tile.size
        for y in range(h):
            for x in range(w):
                color = tile.getpixel((x,y))
                if color[3] < 128:
                    continue
                quad = [project(point(u,v)) for u,v in ((x/w,y/h),((x+1)/w,y/h),((x+1)/w,(y+1)/h),(x/w,(y+1)/h))]
                polygons.append((quad,color))
    im = Image.new("RGBA", size, (232,229,220,255))
    pixels = im.load()
    depth_buffer = [math.inf]*(size[0]*size[1])
    for quad,color in polygons:
        for indices in ((0,1,2),(0,2,3)):
            a,b,c = [quad[i] for i in indices]
            (ax,ay),(bx,by),(cx,cy) = a[0],b[0],c[0]
            denominator = (by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
            if abs(denominator) < 1e-9:
                continue
            for py in range(max(0,math.floor(min(ay,by,cy))),min(size[1],math.ceil(max(ay,by,cy)))):
                for px in range(max(0,math.floor(min(ax,bx,cx))),min(size[0],math.ceil(max(ax,bx,cx)))):
                    wa = ((by-cy)*(px+.5-cx)+(cx-bx)*(py+.5-cy))/denominator
                    wb = ((cy-ay)*(px+.5-cx)+(ax-cx)*(py+.5-cy))/denominator
                    wc = 1-wa-wb
                    if min(wa,wb,wc) < -1e-7:
                        continue
                    depth = wa*a[1]+wb*b[1]+wc*c[1]
                    index = py*size[0]+px
                    if depth < depth_buffer[index]:
                        depth_buffer[index] = depth
                        pixels[px,py] = color
    return im


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--import-requester-jar", type=Path)
    mode.add_argument("--verify-requester-jar", type=Path)
    args = parser.parse_args()
    manual_path = ASSETS / "textures/item/manual.png"
    manual_before = manual_path.read_bytes() if manual_path.exists() else None
    model,texture = restore(args.import_requester_jar,args.verify_requester_jar)
    PREVIEW.mkdir(parents=True,exist_ok=True)
    texture.resize((768,768),Image.Resampling.NEAREST).save(PREVIEW / "requester-atlas-12x.png")
    texture.resize((texture.width*6,texture.height*6),Image.Resampling.NEAREST).save(PREVIEW / "requester-recolored-atlas-6x.png")
    sheet = Image.new("RGBA",(840,392),(232,229,220,255))
    for i,(label,yaw,pitch) in enumerate((("DISTANT STOCK / TOP",0,90),("DISTANT STOCK / OBLIQUE",30,55),("DISTANT STOCK / UNDERSIDE",-30,-55))):
        sheet.paste(render(model,texture,yaw,pitch),(i*280,24))
        ImageDraw.Draw(sheet).text((i*280+10,10),label,fill=rgba("edge"))
    sheet.save(PREVIEW / "requester-three-views.png")
    original = Image.open(SOURCES / "requester-0.3.5.png").convert("RGBA")
    comparison = Image.new("RGBA",(560,392),(232,229,220,255))
    for i,(label,im) in enumerate((("0.3.5 ORIGINAL",original),("SAME SHAPE / DISTANT STOCK",texture))):
        comparison.paste(render(model,im,30,55),(i*280,24))
        ImageDraw.Draw(comparison).text((i*280+10,10),label,fill=rgba("edge"))
    comparison.save(PREVIEW / "requester-before-after.png")
    if manual_before is not None:
        assert manual_path.read_bytes() == manual_before
        print(f"PASS: manual unchanged SHA256 {hashlib.sha256(manual_before).hexdigest()}")
    print(f"Recolored requester with original shape and poses; updated previews in {PREVIEW}")


if __name__ == "__main__":
    main()
