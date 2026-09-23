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
        if isinstance(value, IntArray):
            self.tag(11, key, lambda: self._int_array(value))
        elif isinstance(value, int):
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
        if isinstance(values[0], IntArray):
            typ = 11
        elif isinstance(values[0], int):
            typ = 3
        else:
            typ = 10
        self.u8(typ)
        self.i32(len(values))
        for item in values:
            if typ == 3:
                self.i32(item)
            elif typ == 11:
                self._int_array(item)
            else:
                self._compound_body(item)
                self.end()

    def _int_array(self, values):
        self.i32(len(values))
        for value in values:
            self.i32(value)

    def _compound_body(self, value):
        for k, v in value.items():
            self.value(k, v)


class IntArray(list):
    """Explicit TAG_Int_Array, needed by Create's chain and Frogport codecs."""


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
    # Deterministic assets: gzip otherwise embeds the current timestamp and makes every generator
    # run dirty all Ponder NBT files even when the structure bytes are identical.
    path.write_bytes(gzip.compress(bytes(w.buf), mtime=0))


class Scene:
    """A checkerboard floor plus whatever is placed on it."""

    def __init__(self, width, depth, height=4):
        self.width = width
        self.depth = depth
        # The scene's height is the structure's bounding box, and Ponder frames from it. Four
        # layers is enough for a machine that lies flat; a tower is not. Nine keeps the resonator
        # inside the shot instead of cutting it off at the top.
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


def chain_conveyor(scene, pos, connections, speed=32.0):
    """A real connected Chain Conveyor node, not three decorative sprockets pretending to be one."""
    scene.place(pos, "create:chain_conveyor", None, {
        "id": "create:chain_conveyor",
        "Connections": [IntArray(connection) for connection in connections],
        "Speed": speed,
        "TravellingPackages": [],
        "LoopingPackages": [],
    })


def frogport(scene, pos, block, target_pos, connection, address, slots):
    """Attach a normal or Distant Stock Frogport to a Chain Conveyor segment."""
    relative = [target_pos[i] - pos[i] for i in range(3)]
    scene.place(pos, block, None, {
        "id": block,
        "Target": {
            "flipped": 0,
            "chain_pos": 1.0,
            "connection": IntArray(connection),
            "type": "create:chain_conveyor",
            "relative_pos": IntArray(relative),
        },
        "AcceptsPackages": 1,
        "AddressFilter": address,
        "PlacedYaw": 0.0,
        "Inventory": {"Size": slots, "Items": []},
    })


def export_scene():
    """One readable flow: inventory -> Distant Packager -> belt -> dock."""
    scene = Scene(9, 7)
    chest((2, 2, 5), scene, facing="west")
    packager((2, 2, 4), scene, facing="north")
    funnel((2, 2, 3), scene, facing="north", shape="pulling")
    scene.place((2, 1, 4), "minecraft:polished_andesite")
    scene.place((2, 1, 5), "minecraft:polished_andesite")
    belt_line(scene, [(2, 1, 3), (3, 1, 3), (4, 1, 3), (5, 1, 3), (6, 1, 3)],
              "east", (2, 1, 3), speed=24.0)
    shaft((2, 1, 4), scene)
    dock((7, 1, 3), scene, facing="south")
    return scene


def import_scene():
    """The receiving side makes the dock's underside and local handoff obvious."""
    scene = Scene(9, 7)
    scene.place((2, 1, 3), "minecraft:hopper", {"facing": "east", "enabled": "true"},
                {"id": "minecraft:hopper", "TransferCooldown": -1, "Enabled": 1})
    belt_line(scene, [(3, 1, 3), (4, 1, 3), (5, 1, 3), (6, 1, 3)],
              "east", (3, 1, 3), speed=24.0)
    shaft((3, 1, 4), scene)
    chest((7, 1, 3), scene, facing="west")
    dock((2, 2, 3), scene, facing="south", status="inactive")
    return scene


def tune_scene():
    """A dock and a request desk are deliberately separate pieces of equipment."""
    scene = Scene(8, 6)
    dock((2, 1, 3), scene, facing="south", status="inactive")
    scene.place((5, 1, 3), "distantstock:gauge", {"facing": "south", "lit": "true"})
    return scene


def status_scene():
    """A compact status station: monitor on the wall, dock in front."""
    scene = Scene(8, 6)
    for x in range(3, 7):
        for y in (1, 2):
            scene.place((x, y, 4), "create:cardboard_block", {"axis": "x"})
    scene.place((4, 1, 3), "distantstock:monitor",
                {"facing": "north", "status": "green", "link": "online"})
    dock((4, 1, 2), scene, facing="south", status="fault")
    return scene


def tower_scene():
    """Five-coupler tier-I tower, centred so the complete 3x3 skirt stays readable."""
    scene = Scene(9, 9, height=10)
    scene.place((4, 1, 4), "create:shaft", {"axis": "y"},
                {"id": "create:simple_kinetic", "Speed": 32.0,
                 "Source": {"X": 4, "Y": 1, "Z": 4}})
    scene.place((4, 2, 4), "distantstock:tower_core", None,
                {"id": "distantstock:tower_core", "Speed": 32.0,
                 "Source": {"X": 4, "Y": 1, "Z": 4}, "Couplers": 5, "Tier": "I"})
    for x in range(3, 6):
        for z in range(3, 6):
            if (x, z) != (4, 4):
                scene.place((x, 2, z), "distantstock:tower_casing",
                            {"port": "none", "powered": "false"})
    for y in range(3, 8):
        scene.place((4, y, 4), "distantstock:tower_coupler",
                    {"above": "true" if y < 7 else "false",
                     "below": "true" if y > 3 else "false"})
    scene.place((4, 8, 4), "distantstock:ether_resonator", None,
                {"id": "distantstock:ether_resonator", "Beam": 1})
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
        blockstate = json.loads(candidate.read_text())
        variants = blockstate.get("variants", {})
        for key in variants:
            states.append(dict(part.split("=", 1) for part in key.split(",") if "=" in part))
        # Logger and sounders use multipart blockstates. Their `when` maps are just as useful for
        # validating authored properties as a variants key, and ignoring them was how old scenes
        # could silently ship impossible states.
        for part in blockstate.get("multipart", []):
            when = part.get("when", {})
            if when and all(isinstance(value, str) for value in when.values()):
                states.append(dict(when))
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


def replenish_scene():
    """Automatic ordering devices on a wall, with their receiving dock visible in the foreground."""
    scene = Scene(9, 7)
    for x in range(2, 8):
        for y in (1, 2):
            scene.place((x, y, 5), "create:cardboard_block", {"axis": "x"})
    scene.place((3, 1, 4), "distantstock:remote_gauge")
    scene.place((6, 1, 4), "distantstock:remote_redstone_requester",
                {"axis": "x", "powered": "false"})
    scene.place((2, 1, 2), "minecraft:hopper", {"facing": "east", "enabled": "true"},
                {"id": "minecraft:hopper", "TransferCooldown": -1, "Enabled": 1})
    belt_line(scene, [(3, 1, 2), (4, 1, 2), (5, 1, 2), (6, 1, 2)],
              "east", (3, 1, 2), speed=16.0)
    shaft((3, 1, 3), scene)
    chest((7, 1, 2), scene, facing="west")
    dock((2, 2, 2), scene, facing="south", status="standby")
    return scene


def diagnostics_scene():
    """Three real connected chain nodes, each with a Frogport attached to the route."""
    scene = Scene(9, 7)
    a, b, c = (2, 1, 4), (4, 1, 4), (6, 1, 4)
    chain_conveyor(scene, a, [(2, 0, 0)])
    chain_conveyor(scene, b, [(-2, 0, 0), (2, 0, 0)])
    chain_conveyor(scene, c, [(-2, 0, 0)])
    frogport(scene, (2, 2, 2), "distantstock:diagnostic_frogport", a, (2, 0, 0),
             "__distantstock_diag__/ponder", 18)
    frogport(scene, (4, 2, 2), "distantstock:cache_frogport", b, (2, 0, 0),
             "__distantstock_cache__/ponder", 54)
    frogport(scene, (6, 2, 2), "create:package_frogport", c, (-2, 0, 0),
             "ASSEMBLY", 18)
    return scene


def logger_scene():
    scene = Scene(8, 6)
    for x in range(2, 7):
        for y in (1, 2):
            scene.place((x, y, 4), "create:cardboard_block", {"axis": "x"})
    scene.place((3, 1, 3), "distantstock:logger", {"facing": "north", "status": "error"},
                {"id": "distantstock:logger"})
    scene.place((5, 1, 3), "distantstock:stack_light",
                {"face": "wall", "facing": "north", "green": "false",
                 "yellow": "false", "red": "true"})
    scene.place((6, 1, 3), "distantstock:red_wall_sounder",
                {"facing": "north", "lit": "true"})
    return scene


def main():
    for name, build in (("export", export_scene), ("import", import_scene),
                        ("tune", tune_scene), ("status", status_scene),
                        ("tower", tower_scene), ("replenish", replenish_scene),
                        ("diagnostics", diagnostics_scene), ("logger", logger_scene)):
        scene = build()
        verify(scene, name)
        scene.save(f"{name}.nbt")
        path = PONDER / f"{name}.nbt"
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
