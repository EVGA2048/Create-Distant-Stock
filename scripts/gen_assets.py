#!/usr/bin/env python3
"""Generate Distant Stock textures and ponder structure NBTs."""
from __future__ import annotations

import gzip
import io
import struct
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BLOCK = ROOT / "src/main/resources/assets/distantstock/textures/block"
ITEM = ROOT / "src/main/resources/assets/distantstock/textures/item"
GUI = ROOT / "src/main/resources/assets/distantstock/textures/gui"
PONDER = ROOT / "src/main/resources/assets/distantstock/ponder"
CREATE = Path.home() / "Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar"

OUTLINE = (42, 42, 42, 255)
AND = (132, 132, 127, 255)
AND_L = (164, 164, 156, 255)
AND_D = (96, 96, 91, 255)
BRASS = (177, 139, 66, 255)
BRASS_L = (225, 194, 109, 255)
BRASS_D = (104, 77, 37, 255)
AETHER = (99, 187, 208, 255)
AETHER_L = (186, 230, 236, 255)
AETHER_D = (38, 98, 112, 255)
DARK = (28, 24, 20, 255)
PAPER = (243, 230, 200, 255)
CARD = (187, 137, 78, 255)
CARD_D = (132, 91, 48, 255)
CLOTH = (58, 52, 46, 255)
CLOTH_L = (86, 76, 66, 255)


def image(color=(0, 0, 0, 0), size=16):
    return Image.new("RGBA", (size, size), color)


def px(im):
    return im.load()


def rect(im, x0, y0, x1, y1, color):
    p = px(im)
    for y in range(y0, y1):
        for x in range(x0, x1):
            if 0 <= x < im.size[0] and 0 <= y < im.size[1]:
                p[x, y] = color


def frame(im, x0, y0, x1, y1, edge, inside):
    rect(im, x0, y0, x1, y1, edge)
    rect(im, x0 + 1, y0 + 1, x1 - 1, y1 - 1, inside)


def rivets(im, points, dark=BRASS_D, light=BRASS_L):
    p = px(im)
    for x, y in points:
        if 0 <= x < im.size[0] and 0 <= y < im.size[1]:
            p[x, y] = dark
        if 0 <= x + 1 < im.size[0] and 0 <= y < im.size[1]:
            p[x + 1, y] = light


def andesite():
    im = image(AND)
    p = px(im)
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                p[x, y] = AND_D
            elif (x + y * 3) % 11 == 0:
                p[x, y] = AND_L
            elif (x * 5 + y) % 17 == 0:
                p[x, y] = AND_D
    return im


def brass_plate():
    im = image(BRASS)
    p = px(im)
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                p[x, y] = BRASS_D
            elif x in (1, 8) or y in (1, 8):
                p[x, y] = BRASS_D
            elif (x + y) % 9 == 0:
                p[x, y] = BRASS_L
    rivets(im, ((2, 2), (12, 2), (2, 12), (12, 12)))
    return im


def dock_side():
    im = andesite()
    rect(im, 0, 0, 16, 1, AND_D)
    rect(im, 0, 15, 16, 16, AND_D)
    rect(im, 2, 6, 14, 10, BRASS_D)
    rect(im, 3, 7, 13, 9, BRASS)
    rivets(im, ((1, 2), (13, 2), (1, 13), (13, 13)))
    return im


def dock_top():
    im = andesite()
    frame(im, 2, 2, 14, 14, BRASS_D, DARK)
    rect(im, 3, 3, 13, 4, BRASS)
    rect(im, 3, 12, 13, 13, BRASS_D)
    p = px(im)
    for y in range(5, 12):
        p[4, y] = AETHER_D
        p[11, y] = AETHER_D
    rivets(im, ((1, 1), (13, 1), (1, 13), (13, 13)))
    return im


def dock_front(on, loaded):
    im = andesite()
    frame(im, 0, 0, 16, 16, OUTLINE, AND)
    rect(im, 1, 1, 15, 3, BRASS_D)
    rect(im, 2, 1, 14, 2, BRASS_L)
    frame(im, 3, 3, 13, 14, BRASS_D, DARK)
    rect(im, 4, 12, 12, 13, BRASS)
    glow = AETHER_L if on else AETHER_D
    mid = AETHER if on else AETHER_D
    p = px(im)
    for y in range(5, 12):
        p[3, y] = glow
        p[12, y] = mid
    if loaded:
        rect(im, 5, 5, 11, 12, CARD)
        rect(im, 7, 5, 9, 12, CARD_D)
        rect(im, 5, 8, 11, 9, CARD_D)
    rivets(im, ((1, 4), (13, 4), (1, 13), (13, 13)))
    return im


def dock_inside():
    im = image(DARK)
    p = px(im)
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                p[x, y] = (18, 16, 14, 255)
            elif (x + y) % 7 == 0:
                p[x, y] = (36, 30, 26, 255)
    return im


def dock_rail(on):
    im = image(DARK)
    color = AETHER_L if on else AETHER_D
    rect(im, 5, 0, 11, 16, color)
    rect(im, 6, 0, 10, 16, AETHER if on else AETHER_D)
    p = px(im)
    for y in range(1, 16, 3):
        p[7, y] = AETHER_L if on else AND_D
    return im


def gauge_side():
    im = andesite()
    rect(im, 0, 5, 16, 6, BRASS_D)
    rivets(im, ((2, 2), (12, 2), (2, 12), (12, 12)))
    return im


def gauge_top():
    im = brass_plate()
    frame(im, 4, 4, 12, 12, BRASS_D, DARK)
    rect(im, 6, 6, 10, 10, AETHER_D)
    return im


def gauge_front(on):
    im = andesite()
    frame(im, 1, 1, 15, 12, BRASS_D, PAPER)
    p = px(im)
    for x in range(3, 13):
        p[x, 4] = (214, 196, 160, 255)
        p[x, 7] = (214, 196, 160, 255)
        p[x, 10] = (214, 196, 160, 255)
    rect(im, 2, 12, 14, 15, BRASS)
    rect(im, 3, 12, 13, 13, BRASS_L)
    lamp = AETHER_L if on else AETHER_D
    for x in (4, 8, 12):
        p[x, 13] = lamp if on or x == 8 else DARK
    rect(im, 11, 3, 13, 6, AETHER if on else AETHER_D)
    rivets(im, ((1, 1), (13, 1)))
    return im


def monitor_front():
    im = andesite()
    frame(im, 1, 1, 15, 15, BRASS_D, DARK)
    rect(im, 2, 2, 14, 3, BRASS_L)
    rect(im, 3, 4, 13, 13, PAPER)
    p = px(im)
    for x in range(4, 13, 2):
        p[x, 12] = BRASS_D
    for y, x1 in ((6, 8), (8, 11), (10, 7)):
        for x in range(5, x1):
            p[x, y] = AETHER_D
    rect(im, 11, 5, 13, 7, AETHER)
    return im


def manual():
    im = image()
    p = px(im)
    for y in range(2, 15):
        for x in range(3, 13):
            p[x, y] = OUTLINE
    for y in range(3, 14):
        for x in range(4, 12):
            p[x, y] = CLOTH if (x + y) % 5 else CLOTH_L
    for y in range(4, 14):
        p[12, y] = PAPER
    p[12, 3] = PAPER
    for x, y in ((4, 3), (11, 3), (4, 13), (11, 13)):
        p[x, y] = BRASS
        if x + 1 < 16:
            p[x + 1, y] = BRASS_L
    p[8, 7] = AETHER
    p[7, 8] = AETHER_D
    p[9, 8] = AETHER_L
    p[8, 9] = AETHER
    return im


def requester():
    im = image(size=32)
    p = px(im)
    for y in range(0, 14):
        for x in range(0, 10):
            p[x, y] = AND_D if x in (0, 9) or y in (0, 13) else AND
            if (x + y * 2) % 9 == 0 and 0 < x < 9 and 0 < y < 13:
                p[x, y] = AND_L
    rect(im, 3, 3, 7, 11, PAPER)
    rect(im, 4, 4, 6, 10, (232, 218, 184, 255))
    p[5, 6] = AETHER
    p[5, 8] = AETHER_D
    for y in range(0, 14):
        for x in range(10, 20):
            p[x, y] = BRASS_D if x in (10, 19) or y in (0, 13) else BRASS
            if x in (12, 16):
                p[x, y] = BRASS_L
    for y in range(14, 24):
        for x in range(0, 3):
            p[x, y] = BRASS if y % 3 else BRASS_D
    p[1, 14] = AETHER_D
    p[1, 18] = AETHER
    p[1, 22] = AETHER_L
    for y in range(16, 21):
        for x in range(13, 18):
            p[x, y] = BRASS_L if (x - 15) ** 2 + (y - 18) ** 2 <= 4 else (0, 0, 0, 0)
    p[15, 18] = AETHER
    return im


def panel(brass):
    im = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    p = im.load()
    wood, darkwood, lightwood = (92, 64, 36, 255), (62, 42, 24, 255), (122, 86, 48, 255)
    paper, shadow, line = (243, 230, 200, 255), (228, 212, 176, 255), (196, 176, 136, 255)
    for y in range(8, 248):
        for x in range(8, 248):
            p[x, y] = shadow if (x + y * 3) % 17 == 0 else (line if y % 12 == 0 and 16 < x < 240 else paper)
    for y in range(256):
        for x in range(256):
            if x < 8 or x >= 248 or y < 8 or y >= 248:
                band = (y // 4) % 3 if x < 8 or x >= 248 else (x // 4) % 3
                p[x, y] = lightwood if band == 0 else (wood if band == 1 else darkwood)
    for y in range(7, 249):
        for x in range(7, 249):
            if x in (7, 248) or y in (7, 248):
                p[x, y] = brass.getpixel((x % 16, y % 16))
    for y in range(8, 28):
        for x in range(8, 248):
            p[x, y] = brass.getpixel((x % 16, y % 16)) if y == 27 else (wood if (x // 6 + y) % 4 else lightwood)
    return im


def monitor_panel():
    width, height = 272, 190
    im = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    wood_d, wood, wood_l = (66, 43, 27, 255), (102, 66, 39, 255), (137, 91, 51, 255)
    brass_d, brass, brass_l = (102, 75, 35, 255), (177, 139, 66, 255), (225, 194, 109, 255)
    paper, paper_2, rule = (241, 229, 199, 255), (235, 219, 184, 255), (190, 163, 120, 255)
    draw.rectangle((0, 0, width - 5, height - 5), fill=wood_d)
    draw.rectangle((2, 2, width - 7, height - 7), fill=wood)
    p = im.load()
    for y in range(3, height - 7):
        for x in range(3, width - 7):
            if (x * 3 + y * 7) % 37 == 0:
                p[x, y] = wood_l
    draw.rectangle((5, 5, width - 10, height - 10), fill=brass_d)
    draw.rectangle((7, 7, width - 12, height - 12), fill=paper)
    draw.rectangle((7, 7, width - 12, 27), fill=wood_d)
    draw.rectangle((7, 27, width - 12, 29), fill=brass)
    draw.line((8, 27, width - 13, 27), fill=brass_l)
    draw.rectangle((12, 33, width - 17, 45), fill=paper_2)
    draw.line((12, 32, width - 17, 32), fill=rule)
    draw.line((12, 45, width - 17, 45), fill=rule)

    def card(box):
        x0, y0, x1, y1 = box
        draw.rectangle(box, fill=paper_2)
        draw.rectangle(box, outline=brass_d)
        draw.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=brass_l)
        draw.rectangle((x0 + 1, y0 + 2, x1 - 1, y0 + 16), fill=(226, 207, 168, 255))
        draw.line((x0 + 1, y0 + 17, x1 - 1, y0 + 17), fill=rule)

    card((12, 51, 130, 120))
    card((142, 51, 260, 120))
    card((12, 129, 260, 177))
    for x in (61, 110, 159, 208):
        draw.line((x, 147, x, 175), fill=rule)
    return im


class NbtWriter:
    def __init__(self):
        self.buf = bytearray()

    def raw(self, data: bytes):
        self.buf += data

    def u8(self, v):
        self.buf.append(v & 0xFF)

    def u16(self, v):
        self.buf += struct.pack(">H", v)

    def i32(self, v):
        self.buf += struct.pack(">i", v)

    def name(self, s: str):
        raw = s.encode("utf-8")
        self.u16(len(raw))
        self.raw(raw)

    def tag(self, typ, key, write):
        self.u8(typ)
        self.name(key)
        write()

    def end(self):
        self.u8(0)

    def string(self, key, value):
        self.tag(8, key, lambda: self._string(value))

    def _string(self, value):
        raw = value.encode("utf-8")
        self.u16(len(raw))
        self.raw(raw)

    def int_tag(self, key, value):
        self.tag(3, key, lambda: self.i32(value))

    def int_list(self, key, values):
        def body():
            self.u8(3)
            self.i32(len(values))
            for v in values:
                self.i32(v)
        self.tag(9, key, body)

    def compound_list(self, key, items, write_item):
        def body():
            self.u8(10)
            self.i32(len(items))
            for item in items:
                write_item(item)
                self.end()
        self.tag(9, key, body)


def write_structure(path: Path, size, palette, blocks):
    w = NbtWriter()
    w.u8(10)
    w.name("")
    w.int_list("size", size)
    w.compound_list("entities", [], lambda _: None)

    def write_block(block):
        w.int_list("pos", block["pos"])
        w.int_tag("state", block["state"])
        nbt = block.get("nbt")
        if nbt:
            def write_nbt():
                for k, v in nbt.items():
                    if isinstance(v, str):
                        w.string(k, v)
                    elif isinstance(v, int):
                        w.int_tag(k, v)
            w.tag(10, "nbt", write_nbt)
            w.end()

    w.compound_list("blocks", blocks, write_block)

    def write_palette(entry):
        w.string("Name", entry["Name"])
        props = entry.get("Properties")
        if props:
            def write_props():
                for k, v in props.items():
                    w.string(k, v)
            w.tag(10, "Properties", write_props)
            w.end()

    w.compound_list("palette", palette, write_palette)
    w.int_tag("DataVersion", 3955)
    w.end()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(bytes(w.buf)))


def floor(width, depth, extra, palette_extra, nbt_extra=None):
    palette = [
        {"Name": "minecraft:white_concrete"},
        {"Name": "minecraft:snow_block"},
    ] + palette_extra
    blocks = []
    for z in range(depth):
        for x in range(width):
            blocks.append({"pos": [x, 0, z], "state": (x + z) % 2})
    for block in extra:
        blocks.append(block)
    write_structure(PONDER / nbt_extra, [width, 4, depth], palette, blocks)


def ponder_scenes():
    PONDER.mkdir(parents=True, exist_ok=True)
    floor(7, 6, [
        {"pos": [1, 1, 2], "state": 2, "nbt": {"id": "minecraft:chest"}},
        {"pos": [2, 1, 2], "state": 3},
        {"pos": [3, 1, 2], "state": 4},
        {"pos": [4, 1, 2], "state": 5},
    ], [
        {"Name": "minecraft:chest", "Properties": {"facing": "east", "type": "single", "waterlogged": "false"}},
        {"Name": "create:packager", "Properties": {"facing": "east", "powered": "false", "linked": "false"}},
        {"Name": "minecraft:hopper", "Properties": {"facing": "east", "enabled": "true"}},
        {"Name": "distantstock:dock", "Properties": {"facing": "south", "loaded": "false", "lit": "true"}},
    ], "export.nbt")

    floor(7, 6, [
        {"pos": [2, 1, 2], "state": 2},
        {"pos": [3, 1, 2], "state": 3},
        {"pos": [4, 1, 2], "state": 4, "nbt": {"id": "minecraft:chest"}},
    ], [
        {"Name": "distantstock:dock", "Properties": {"facing": "south", "loaded": "true", "lit": "true"}},
        {"Name": "minecraft:hopper", "Properties": {"facing": "east", "enabled": "true"}},
        {"Name": "minecraft:chest", "Properties": {"facing": "west", "type": "single", "waterlogged": "false"}},
    ], "import.nbt")

    floor(6, 6, [
        {"pos": [1, 1, 3], "state": 2},
        {"pos": [2, 1, 2], "state": 3},
    ], [
        {"Name": "distantstock:dock", "Properties": {"facing": "south", "loaded": "false", "lit": "true"}},
        {"Name": "distantstock:gauge", "Properties": {"facing": "south", "lit": "true"}},
    ], "tune.nbt")

    floor(6, 6, [
        {"pos": [1, 1, 3], "state": 2},
        {"pos": [3, 1, 3], "state": 3},
        {"pos": [4, 1, 2], "state": 4},
    ], [
        {"Name": "minecraft:stone", "Properties": {}},
        {"Name": "distantstock:monitor", "Properties": {"facing": "south"}},
        {"Name": "distantstock:dock", "Properties": {"facing": "south", "loaded": "false", "lit": "false"}},
    ], "status.nbt")


def textures():
    BLOCK.mkdir(parents=True, exist_ok=True)
    ITEM.mkdir(parents=True, exist_ok=True)
    GUI.mkdir(parents=True, exist_ok=True)
    brass = brass_plate()
    if CREATE.exists():
        with zipfile.ZipFile(CREATE) as archive:
            brass = Image.open(io.BytesIO(archive.read("assets/create/textures/block/brass_casing.png"))).convert("RGBA")
    andesite().save(BLOCK / "andesite_trim.png")
    dock_side().save(BLOCK / "dock_side.png")
    dock_top().save(BLOCK / "dock_top.png")
    dock_inside().save(BLOCK / "dock_inside.png")
    dock_rail(False).save(BLOCK / "dock_rail.png")
    dock_rail(True).save(BLOCK / "dock_rail_lit.png")
    dock_front(False, False).save(BLOCK / "dock_front.png")
    dock_front(True, False).save(BLOCK / "dock_front_lit.png")
    dock_front(False, True).save(BLOCK / "dock_front_loaded.png")
    dock_front(True, True).save(BLOCK / "dock_front_loaded_lit.png")
    gauge_side().save(BLOCK / "gauge_side.png")
    gauge_top().save(BLOCK / "gauge_top.png")
    gauge_front(False).save(BLOCK / "gauge_front.png")
    gauge_front(True).save(BLOCK / "gauge_front_lit.png")
    monitor_front().save(BLOCK / "monitor_front.png")
    manual().save(ITEM / "manual.png")
    requester().save(ITEM / "requester.png")
    panel(brass).save(GUI / "panel.png")
    monitor_panel().save(GUI / "monitor.png")
    if (BLOCK / "ether_metal.png").exists():
        (BLOCK / "ether_metal.png").unlink()


def main():
    from gen_block_art import main as blocks
    from gen_item_art import main as items
    blocks()
    items()
    ponder_scenes()
    print("generated current block/item art and ponder nbt")


if __name__ == "__main__":
    main()
