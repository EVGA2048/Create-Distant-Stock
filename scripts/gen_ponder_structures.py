#!/usr/bin/env python3
"""Write the Ponder scene structures.

These are hand-authored, so they drift out of step with the blocks they show. That is not a
cosmetic problem: a palette entry naming a property the block does not have reads back as AIR, so
the whole machine silently vanishes from the scene instead of failing loudly. The dock used to
carry `loaded` and `lit`, which it has not had since it grew a status enum, so it was missing from
every scene. Every palette below is written against the block's real state definition; change a
block's properties and this file has to change with it.

Kept separate from gen_assets.py so regenerating textures can never quietly rewrite a scene.
"""
from __future__ import annotations

import gzip
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PONDER = ROOT / "src/main/resources/assets/distantstock/ponder"

DATAVERSION = 3955


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

    # --- generic values -------------------------------------------------------------------
    # Belt block entities carry nested source and inventory data, so a block needs more than the
    # flat string and int pairs the texture script got away with.

    def value(self, key, value):
        if isinstance(value, int):
            self.int_tag(key, value)
        elif isinstance(value, float):
            self.tag(5, key, lambda: self.raw(struct.pack(">f", value)))
        elif isinstance(value, str):
            self.string(key, value)
        elif isinstance(value, list):
            self.tag(9, key, lambda: self._list_tag(value))
        elif isinstance(value, dict):
            self.tag(10, key, lambda: (self._compound_body(value), self.end()))
        else:
            raise TypeError(f"no NBT tag for {value!r}")

    def _list_tag(self, values):
        if not values:
            self.u8(10)
            self.i32(0)
            return
        typ = 3 if isinstance(values[0], int) else 10
        self.u8(typ)
        self.i32(len(values))
        for item in values:
            if typ == 3:
                self.i32(item)
            else:
                self._compound_body(item)
                self.end()

    def _compound_body(self, value):
        for k, v in value.items():
            self.value(k, v)


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
            w.tag(10, "nbt", lambda: (w._compound_body(nbt), w.end()))

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
    w.int_tag("DataVersion", DATAVERSION)
    w.end()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(bytes(w.buf)))


class Scene:
    """A checkerboard floor plus whatever is placed on it."""

    def __init__(self, width, depth, height=4):
        self.width = width
        self.depth = depth
        # 场景的高度就是结构的包围盒，Ponder 按它取景。四格够平放的机器用，立起来的塔不够：
        # 塔顶要九格，镜头才不会把谐振器切在画面外。
        self.height = height
        self.palette = [{"Name": "minecraft:white_concrete"}, {"Name": "minecraft:snow_block"}]
        self.blocks = []

    def place(self, pos, block, props=None, nbt=None):
        entry = {"Name": block}
        if props:
            entry["Properties"] = props
        index = self.index_of(entry)
        placed = {"pos": list(pos), "state": index}
        if nbt:
            placed["nbt"] = nbt
        self.blocks.append(placed)
        return self

    def index_of(self, entry):
        for i, existing in enumerate(self.palette):
            if existing == entry:
                return i
        self.palette.append(entry)
        return len(self.palette) - 1

    def save(self, name):
        floor = [{"pos": [x, 0, z], "state": (x + z) % 2}
                 for z in range(self.depth) for x in range(self.width)]
        write_structure(PONDER / name, [self.width, self.height, self.depth],
                        self.palette, floor + self.blocks)


# --- block helpers ------------------------------------------------------------------------

def belt_line(scene, positions, direction, controller, speed=16.0):
    """A run of belt blocks.

    `facing` on a belt is the direction the belt carries things, and `start` is the upstream end,
    so the run is laid out along the travel direction and the parts follow it. The controller and
    the item inventory both live on the start block; the rest only need the shared position so the
    belt can find its length and its driver.
    """
    length = len(positions)
    for i, pos in enumerate(positions):
        part = "start" if i == 0 else "end" if i == length - 1 else "middle"
        nbt = {
            "id": "create:belt",
            "Speed": speed,
            "Index": i,
            "Length": length,
            "IsController": 1 if i == 0 else 0,
            "Casing": "NONE",
            "Covered": 0,
            "Controller": {"X": controller[0], "Y": controller[1], "Z": controller[2]},
            "Source": {"X": controller[0], "Y": controller[1], "Z": controller[2]},
        }
        if i == 0:
            nbt["Inventory"] = {"Items": [], "PositiveOrder": 1}
        scene.place(pos, "create:belt", {
            "casing": "false", "waterlogged": "false", "part": part,
            "facing": direction, "slope": "horizontal",
        }, nbt)


def shaft(pos, scene, axis="z"):
    """The belt's drive. A run with nothing turning it is furniture, not machinery."""
    scene.place(pos, "create:cogwheel", {"waterlogged": "false", "axis": axis},
                {"id": "create:simple_kinetic", "Speed": 16.0,
                 "Source": {"X": pos[0], "Y": pos[1], "Z": pos[2]}})


def dock(pos, scene, facing="south", status="inactive"):
    scene.place(pos, "distantstock:dock", {"facing": facing, "status": status})


def packager(pos, scene, facing="north"):
    """The Distant Packager.

    It is Create's packager with a link to a network on another server, so it takes the same
    blockstate and the same block-entity data; only the name differs.
    """
    scene.place(pos, "distantstock:remote_packager",
                {"powered": "false", "facing": facing, "linked": "false"},
                {"id": "distantstock:remote_packager", "Active": 0, "AnimationTicks": 0,
                 "AnimationInward": 0, "SignAddress": "", "QueuedPackages": []})


def chest(pos, scene, facing="south", kind="single"):
    scene.place(pos, "minecraft:chest",
                {"facing": facing, "type": kind, "waterlogged": "false"},
                {"id": "minecraft:chest", "Items": []})


def funnel(pos, scene, facing="north", shape="pulling"):
    scene.place(pos, "create:andesite_belt_funnel",
                {"powered": "false", "facing": facing, "shape": shape, "waterlogged": "false"},
                {"id": "create:funnel", "TransferCooldown": 0, "UpTo": 1,
                 "Filter": {"id": "minecraft:air", "Count": 0}, "FilterAmount": 64})


def storage(scene, x):
    """A chest, a packager and the funnel that drops the finished parcel onto the belt below.

    This is Create's own arrangement for a packing station: the packager takes from the chest
    behind it and ejects forward into a funnel, and the funnel feeds the belt underneath. Nothing
    is ever pushed in through the roof of anything.
    """
    chest((x, 2, 4), scene, facing="west")
    packager((x, 2, 3), scene, facing="north")
    funnel((x, 2, 2), scene, facing="north", shape="pulling")
    scene.place((x, 1, 3), "minecraft:polished_andesite")
    scene.place((x, 1, 4), "minecraft:polished_andesite")


def export_scene():
    """Storage, then a belt, then the dock.

    The dock sits at the end of a line rather than under a chute. A warehouse already has a way of
    moving goods around, and a scene that teaches the roof as the input is teaching a layout nobody
    builds twice.
    """
    scene = Scene(8, 6)
    storage(scene, 3)
    belt_line(scene, [(1, 1, 2), (2, 1, 2), (3, 1, 2), (4, 1, 2), (5, 1, 2)], "east", (1, 1, 2))
    shaft((1, 1, 3), scene)
    dock((6, 1, 2), scene, facing="south")
    return scene


def import_scene():
    """A dock that hands its arrivals off and a line that carries them into storage."""
    scene = Scene(8, 6)
    # Vanilla hoppers only take from the block straight above, which is how the dock hands cargo
    # out of its underside; it then feeds the belt it points into.
    scene.place((2, 1, 2), "minecraft:hopper", {"facing": "east", "enabled": "true"},
                {"id": "minecraft:hopper", "TransferCooldown": -1, "Enabled": 1})
    belt_line(scene, [(3, 1, 2), (4, 1, 2), (5, 1, 2)], "east", (3, 1, 2))
    shaft((3, 1, 3), scene)
    chest((6, 1, 2), scene, facing="west")
    dock((2, 2, 2), scene, facing="south", status="standby")
    return scene


def tune_scene():
    """The request desk that carries the order, standing well clear of the dock.

    They are two different pieces of furniture with two different jobs, and the first version of
    this scene stacked them against each other until they read as one machine.
    """
    scene = Scene(8, 6)
    dock((1, 1, 3), scene, facing="south", status="standby")
    scene.place((5, 1, 3), "distantstock:gauge", {"facing": "south", "lit": "true"})
    return scene


def status_scene():
    """A wall carrying the monitor, with the dock it reports on beside it.

    A wall panel is mounted on the face it was placed against and looks the other way, so the
    monitor faces north and the wall stands to its south. Cardboard rather than stone, because the
    wall is the console's own casing material everywhere else in the mod.
    """
    scene = Scene(8, 6)
    for x in range(3, 7):
        for y in (1, 2):
            scene.place((x, y, 4), "create:cardboard_block", {"axis": "x"})
    scene.place((4, 1, 3), "distantstock:monitor", {"facing": "north", "status": "green"})
    dock((4, 1, 2), scene, facing="south", status="fault")
    return scene


def tower_scene():
    """互通塔：立轴、3x3 底座、五段耦合器、谐振器，外加裙板角上的一只拉杆。

    桅杆只堆到五段（I 级的第一档）。十七段会把塔顶顶出画面，而「越高越强」本来就讲不完，交给
    文案；场景只负责把结构讲到没有歧义。

    所有摆出来的方块都必须是真的能这么搭的：立轴正对底座下方，耦合器上下相接，谐振器盖在桅杆
    顶上，拉杆压在裙板顶面。少一样、错一格，玩家照着学就会得到一座不成立的塔。
    """
    scene = Scene(8, 6, height=9)

    # 动力从底面进。底座只认底面，侧面接不进轴，所以立轴必须正对它的下方；32 转/分是中等转速，
    # 刚好满足底座要求的最低档，塔本来的开销在应力上。
    scene.place((4, 1, 3), "create:shaft", {"axis": "y"},
                {"id": "create:simple_kinetic", "Speed": 32.0,
                 "Source": {"X": 4, "Y": 1, "Z": 3}})

    # 底座正中央。方块实体里写的是服务器本来会同步过来的那几个数——桅杆有几段、到了哪一级、轴
    # 转多快。塔顶的光柱读的就是它们，不写的话场景里立着的会是一座「还没成塔」的塔。
    scene.place((4, 2, 3), "distantstock:tower_core", None,
                {"id": "distantstock:tower_core", "Speed": 32.0,
                 "Source": {"X": 4, "Y": 1, "Z": 3}, "Couplers": 5, "Tier": "I"})

    # 3x3 的裙板，底座收在正中央（中心那格是底座本身，不是机壳）。
    for x in range(3, 6):
        for z in range(2, 5):
            if (x, z) != (4, 3):
                scene.place((x, 2, z), "distantstock:tower_casing", {"powered": "false"})

    # 桅杆。above/below 描述的是邻居而不是这块方块：接口上下各有各的模型，五段里只有最下一段
    # 的 below 和最上一段的 above 是 false。
    for y in range(3, 8):
        scene.place((4, y, 3), "distantstock:tower_coupler",
                    {"above": "true" if y < 7 else "false",
                     "below": "true" if y > 3 else "false"})

    # 塔顶。1 是「塔在转、没有包裹经过」的那一档光——场景里立着的就是一座建成并且通着动力的塔。
    scene.place((4, 8, 3), "distantstock:ether_resonator", None,
                {"id": "distantstock:ether_resonator", "Beam": 1})

    # 裙板角上的拉杆：floor 朝向的拉杆压在机壳顶面，红石信号由这块机壳进入裙板，正好用来演示
    # 观察窗。位置在 (3,3,3)，即 (3,2,3) 那块机壳的正上方。
    scene.place((3, 3, 3), "minecraft:lever",
                {"face": "floor", "facing": "north", "powered": "false"})
    return scene


def known_states(block_id):
    """The property sets a block accepts, read from its blockstate file.

    A blockstate's variant keys are `property=value` pairs, which is the only machine-readable
    record of a block's state definition in a resource pack. It is a proxy, not the definition
    itself, so a block whose every state is written out longhand still checks correctly and a
    block with no blockstate is skipped rather than guessed at.
    """
    namespace, _, path = block_id.partition(":")
    states = []
    for candidate in (ROOT / "src/main/resources/assets" / namespace / "blockstates").glob(f"{path}.json"):
        variants = json.loads(candidate.read_text()).get("variants", {})
        for key in variants:
            states.append(dict(part.split("=", 1) for part in key.split(",") if "=" in part))
    return states


def verify(scene, name):
    """Every palette property must be one the block actually has."""
    for entry in scene.palette:
        block_id = entry["Name"]
        if not block_id.startswith("distantstock:"):
            # Create's and vanilla's states are not ours to check, and this script has no
            # dependency on their jars.
            continue
        allowed = known_states(block_id)
        if not allowed:
            raise SystemExit(f"{name}: no blockstate for {block_id}, cannot check its palette")
        props = entry.get("Properties", {})
        for key, value in props.items():
            if not any(state.get(key) == value for state in allowed):
                seen = sorted({state.get(key) for state in allowed if key in state})
                raise SystemExit(
                    f"{name}: {block_id} has no {key}={value}; it accepts "
                    f"{key}={seen} (or has no such property at all)")
        if set(props) != set(allowed[0]):
            raise SystemExit(
                f"{name}: {block_id} palette is missing properties; it needs {sorted(allowed[0])}, "
                f"got {sorted(props)}")


def main():
    for name, build in (("export", export_scene), ("import", import_scene),
                        ("tune", tune_scene), ("status", status_scene),
                        ("tower", tower_scene)):
        scene = build()
        verify(scene, name)
        scene.save(f"{name}.nbt")
        path = PONDER / f"{name}.nbt"
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
