"""Reproduce the tower from the self-contained handoff assets."""
import sys
sys.dont_write_bytecode=True
from pathlib import Path
import json
from PIL import Image
from render_scene import box,move,render
root=Path(__file__).resolve().parents[1]
def mesh(path):return json.loads((root/path).read_text())
mats={p.stem:Image.open(p).convert('RGBA') for p in (root/'tower/textures').glob('*.png')}
core=mesh('tower/core_mesh.json')
fixed=mesh('tower/fixed_mesh.json')
rotor=mesh('tower/rotor_mesh.json')
resonator=fixed+move(rotor,turns=(('y',25,(8,0,8)),))
old=mesh('coupler/tower_mesh.json')
column=[f for f in old if min(p[1] for p in f['points'])>=16 and max(p[1] for p in f['points'])<=64]
column+=mesh('tower/coupler_crossframes_mesh.json')
shaft=box((6,-12,6),(10,0,10),'axis',faces={'down':'axis_top','up':'axis_top'})
for f in shaft:
    if f['material']=='axis':f['uv']=[[6,0],[10,0],[10,12],[6,12]]
base=core+shaft
for x in (-16,0,16):
    for z in (-16,0,16):
        if (x,z)!=(0,0):base+=box((x,0,z),(x+16,16,z+16),'casing',faces={'north':'fluid'} if (x,z)==(0,-16) else None)
full=base+column+move(resonator,offset=(0,64,0))
im=render(full,mats,size=(640,970),center=(8,38,8),scale=9,yaw=30,pitch=23,background='#e8eeed')
expected=Image.open(root/'tower/tower.png').convert('RGBA')
assert im.tobytes()==expected.tobytes(), 'Rendered tower differs from approved preview'
out=root/'verification'
out.mkdir(exist_ok=True)
im.save(out/'tower.png')
print('Tower matches handoff preview pixel-for-pixel; all mesh textures resolved.')
