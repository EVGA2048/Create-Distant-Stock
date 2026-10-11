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
        if any(tuple(b["pos"]) == tuple(pos) for b in self.blocks):
            raise ValueError(f"two blocks occupy {pos}: {block}")
        if not (0 <= pos[0] < self.width and 0 < pos[1] < self.height and 0 <= pos[2] < self.depth):
            raise ValueError(f"block outside structure bounds: {pos}")
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


# --- Authored layouts: each machine has one physical task. --------------------

FREQUENCY = IntArray([1234, 5678, 9012, 3456])

def chest(scene, pos):
    return scene.place(pos, 'minecraft:chest', {'facing':'north','type':'single','waterlogged':'false'},
                       {'id':'minecraft:chest','Items':[]})

def support(scene, pos):
    return scene.place(pos, 'minecraft:polished_andesite')

def dock(scene, pos, mode='receive'):
    return scene.place(pos, 'distantstock:dock', {'facing':'north','status':'standby'},
                       {'id':'distantstock:dock','Mode':mode})

def packager(scene, pos):
    return scene.place(pos, 'distantstock:remote_packager',
                       {'powered':'false','facing':'north','linked':'true'},
                       {'id':'distantstock:remote_packager','AnimationTicks':0,'QueuedPackages':[]})

def link(scene, pos):
    return scene.place(pos, 'create:stock_link',
                       {'face':'floor','facing':'north','powered':'false','waterlogged':'false'},
                       {'id':'create:packager_link','Freq':FREQUENCY,'Added':1})

def shaft(scene, pos, axis):
    return scene.place(pos,'create:shaft',{'axis':axis},
                       {'id':'create:simple_kinetic','Speed':0.0})

def cogwheel(scene, pos, axis):
    return scene.place(pos,'create:cogwheel',{'axis':axis},
                       {'id':'create:simple_kinetic','Speed':0.0})

def gearbox(scene, pos):
    # Axis Z leaves shaft connections on X and Y, turning the input upward.
    return scene.place(pos,'create:gearbox',{'axis':'z'},
                       {'id':'create:gearbox','Speed':0.0})

def funnel(scene, pos, facing, shape):
    return scene.place(pos,'create:andesite_belt_funnel',
                       {'facing':facing,'shape':shape,'powered':'false','waterlogged':'false'},
                       {'id':'create:funnel'})

def belt(scene, start, end):
    x0,y,z = start
    for x in range(x0,end+1):
        scene.place((x,y,z),'create:belt',
                    {'facing':'east','part':'start' if x==x0 else 'end' if x==end else 'middle',
                     'slope':'horizontal','casing':'false','waterlogged':'false'},
                    {'id':'create:belt','Speed':0.0,'Length':end-x0+1,'Index':x-x0,
                     'IsController':int(x==x0),'Controller':IntArray(start),'Casing':'NONE',
                     'Inventory':{'Items':[],'PositiveOrder':1}})

def hopper(scene,pos,facing='down'):
    scene.place(pos,'minecraft:hopper',{'facing':facing,'enabled':'true'},
                {'id':'minecraft:hopper','Items':[]})

def export_scene():
    s=Scene(7,7,5)
    chest(s,(1,2,4)); support(s,(1,1,4))
    packager(s,(1,2,3)); link(s,(1,3,3))
    cogwheel(s,(1,1,0),'z'); shaft(s,(1,1,1),'z')
    belt(s,(1,1,2),4)
    funnel(s,(1,2,2),'north','pulling')
    funnel(s,(4,2,2),'west','pushing')
    dock(s,(5,2,2),'send')
    hopper(s,(5,1,2),'south'); chest(s,(5,1,3))
    return s

def import_scene():
    s=Scene(7,7,5)
    dock(s,(3,3,3))
    s.place((3,2,3),'create:chute',{'facing':'down','shape':'normal','waterlogged':'false'},
            {'id':'create:chute'})
    chest(s,(3,1,3))
    return s

def tune_scene():
    s=Scene(7,7,4)
    chest(s,(1,1,4)); packager(s,(1,1,3)); link(s,(1,2,3))
    dock(s,(4,1,2))
    s.place((5,1,4),'distantstock:gauge',{'facing':'north','lit':'true'})
    return s

def status_scene():
    s=Scene(7,7,4)
    for y in (1,2): support(s,(2,y,4))
    s.place((2,2,3),'distantstock:monitor',{'facing':'north','status':'green','link':'online'})
    dock(s,(4,1,2))
    return s

def tower_scene():
    s=Scene(7,7,10)
    cogwheel(s,(0,1,3),'x')
    for x in (1,2): shaft(s,(x,1,3),'x')
    gearbox(s,(3,1,3))
    for x in (0,1):
        s.place((x,2,3),'create:fluid_pipe',
                {face:str(face in ('east','west')).lower() for face in ('up','down','north','south','east','west')} | {'waterlogged':'false'},
                {'id':'create:fluid_pipe'})
    s.place((3,2,3),'distantstock:tower_core',None,
            {'id':'distantstock:tower_core','Speed':0.0,'Couplers':5,'Tier':'I'})
    for x in range(2,5):
        for z in range(2,5):
            if (x,z)!=(3,3): s.place((x,2,z),'distantstock:tower_casing',{'port':'none','powered':'false'})
    for y in range(3,8):
        s.place((3,y,3),'distantstock:tower_coupler',{'above':str(y<7).lower(),'below':str(y>3).lower()})
    s.place((3,8,3),'distantstock:ether_resonator',None,{'id':'distantstock:ether_resonator','Beam':0})
    # Lever attaches to the near casing, leaving its fluid port on the west face.
    s.place((3,2,1),'minecraft:lever',{'face':'wall','facing':'north','powered':'false'})
    return s

def replenish_scene():
    s=Scene(7,7,4)
    for y in (1,2): support(s,(2,y,3))
    s.place((2,2,2),'distantstock:remote_gauge',
            {'face':'wall','facing':'north','waterlogged':'false','powered':'false'},
            {'id':'distantstock:remote_gauge','top_right':{
                'Freq':FREQUENCY,'Filter':{'id':'minecraft:iron_ingot','count':1},
                'FilterAmount':64,'LastLevel':16,'RecipeAddress':'ASSEMBLY',
                'Satisfied':0,'PromisedSatisfied':0,'UpTo':1}})
    chest(s,(4,1,4));packager(s,(4,1,3));link(s,(4,2,3))
    dock(s,(5,2,1));hopper(s,(5,1,1),'west');chest(s,(4,1,1))
    return s

def redstone_scene():
    s=Scene(7,7,4)
    s.place((2,1,2),'distantstock:remote_redstone_requester',{'axis':'x','powered':'false'})
    s.place((2,1,3),'minecraft:lever',{'face':'floor','facing':'north','powered':'false'})
    dock(s,(5,2,2));hopper(s,(5,1,2),'south');chest(s,(5,1,3))
    return s

def diagnostics_scene():
    s=Scene(9,9,6)
    for x,dx in [(2,4),(6,-4)]:
        shaft(s,(x,2,5),'y')
        if x==2: gearbox(s,(x,1,5))
        else: shaft(s,(x,1,5),'y')
        s.place((x,3,5),'create:chain_conveyor',None,
                {'id':'create:chain_conveyor','Connections':[IntArray([dx,0,0])],
                 'Speed':0.0,'TravellingPackages':[],'LoopingPackages':[]})
    # External rotation enters at the scene boundary and turns upward through a gearbox.
    cogwheel(s,(0,1,5),'x'); shaft(s,(1,1,5),'x')
    for x,block,address,slots in [(2,'distantstock:diagnostic_frogport','__distantstock_diag__/ponder',18),
                                  (6,'create:package_frogport','ASSEMBLY',18)]:
        chest(s,(x,1,2))
        s.place((x,2,2),block,None,{'id':block,'CreateFrequency':FREQUENCY,
                    'Target':{'flipped':0,'chain_pos':0.0,'connection':IntArray([4 if x==2 else -4,0,0]),
                              'type':'create:chain_conveyor','relative_pos':IntArray([0,1,3])},
                    'AcceptsPackages':1,'AddressFilter':address,'PlacedYaw':0.0,
                    'Inventory':{'Size':slots,'Items':[]}})
    chest(s,(4,1,7));packager(s,(4,1,6));link(s,(4,2,6))
    return s

def cache_scene():
    s=diagnostics_scene()
    chest(s,(4,1,2))
    s.place((4,2,2),'distantstock:cache_frogport',None,
            {'id':'distantstock:cache_frogport','Target':{
                'flipped':0,'chain_pos':2.0,'connection':IntArray([4,0,0]),
                'type':'create:chain_conveyor','relative_pos':IntArray([-2,1,3])},
             'AcceptsPackages':1,'AddressFilter':'__distantstock_cache__/ponder','PlacedYaw':0.0,
             'Inventory':{'Size':54,'Items':[]}})
    return s

def logger_scene():
    s=Scene(7,7,4)
    for x in (2,3,4):
        for y in (1,2):support(s,(x,y,4))
    s.place((3,2,3),'distantstock:logger',{'facing':'north','status':'normal','printed':'false'},
            {'id':'distantstock:logger','DisplayCode':'OK','PaperRemaining':0,'AlarmSoundMode':'SILENT'})
    return s

def alarms_scene():
    s=Scene(7,7,5)
    for x in (2,5):
        for y in (1,2):support(s,(x,y,4))
    s.place((2,2,3),'distantstock:stack_light',
            {'face':'wall','facing':'north','green':'false','yellow':'false','red':'false'})
    s.place((2,1,1),'distantstock:condition_linker',{'facing':'north'},
            {'id':'distantstock:condition_linker','TargetDimension':'minecraft:overworld',
             'TargetX':2,'TargetY':2,'TargetZ':3})
    s.place((2,1,0),'minecraft:lever',{'face':'floor','facing':'north','powered':'false'})
    s.place((5,2,3),'distantstock:red_wall_sounder',{'facing':'north','lit':'false','powered':'false'})
    s.place((5,1,3),'minecraft:lever',{'face':'floor','facing':'north','powered':'false'})
    return s

SCENES = dict(export=export_scene, **{'import':import_scene}, tune=tune_scene,status=status_scene,
              tower=tower_scene,replenish=replenish_scene,redstone=redstone_scene,
              diagnostics=diagnostics_scene,cache=cache_scene,logger=logger_scene,alarms=alarms_scene)

def verify(scene,name):
    # Layout invariants are checked at insertion, before any NBT is written.
    # Check known local states; resource variants are not full state definitions
    # (remote_gauge inherits facing/face even though its static model has no variants).
    for entry in scene.palette:
        if not entry['Name'].startswith('distantstock:'): continue
        p=ROOT/'src/main/resources/assets/distantstock/blockstates'/f"{entry['Name'].split(':')[1]}.json"
        assert p.exists(), (name,entry['Name'])
    assert all(p['Name']!='create:creative_motor' for p in scene.palette), (name,'visible creative motor')
    positions={tuple(b['pos']):scene.palette[b['state']]['Name'] for b in scene.blocks}
    for b in scene.blocks:
        pos=tuple(b['pos']); block=scene.palette[b['state']]['Name']
        if 'frogport' in block:
            assert positions.get((pos[0],pos[1]-1,pos[2]))=='minecraft:chest', (name,'unsupported frogport')
            target=b['nbt']['Target']
            at=tuple(pos[i]+target['relative_pos'][i] for i in range(3))
            assert positions.get(at)=='create:chain_conveyor', (name,'missing chain target')
        if block=='create:andesite_belt_funnel':
            assert positions.get((pos[0],pos[1]-1,pos[2]))=='create:belt', (name,'funnel off belt')
        if block=='create:chain_conveyor':
            for delta in b['nbt']['Connections']:
                target=tuple(pos[i]+delta[i] for i in range(3))
                other=next(v for v in scene.blocks if tuple(v['pos'])==target)
                assert [-n for n in delta] in other['nbt']['Connections'], (name,'one-way chain')

def main():
    for name,build in SCENES.items():
        scene=build();verify(scene,name);scene.save(name+'.nbt')
        print(f'{name}: {len(scene.blocks)} blocks, {scene.width}x{scene.height}x{scene.depth}')

if __name__=='__main__':main()
