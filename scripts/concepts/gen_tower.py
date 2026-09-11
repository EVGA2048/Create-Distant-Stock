#!/usr/bin/env python3
"""Two individually placed signal blocks and a non-block beam preview."""
from __future__ import annotations
import json
import math
import sys
from pathlib import Path
sys.dont_write_bytecode = True
import numpy as np
from PIL import Image, ImageDraw
from render_scene import BG, INK, MUTED, box, ring, move, render, header, text, rotation, face
from create_materials import MATERIALS, material

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / 'docs/design/concepts/tower'
OUT = ROOT / 'build/art/concepts'
PALETTE = {
    'andesite': ('#93979C','#BDC0C4','#686E78'),
    'casing': ('#748087','#AEB6B8','#46535D'),
    'cast_white': ('#DADEE0','#F5F5ED','#A0ACB7'),
    'blue_gray': ('#5F829B','#91B4C6','#3C566F'),
    'iron': ('#4B5766','#7F8A97','#293746'),
    'paper': ('#F2EFE3','#FFFDF1','#B9C2C6'),
    'cyan': ('#63BBD0','#D9FAFF','#2585A5'),
    'lamp_blue': ('#2D75A7','#A6E6F4','#1C476D'),
    'lamp_cyan': ('#2585A5','#D9FAFF','#63BBD0'),
    'lamp_off': ('#667277','#E1E8E5','#465258'),
    'lamp_fault': ('#B85D2D','#FFE0A3','#7A2F20'),
    'lamp_core_blue': ('#3D9DD0','#E9FCFF','#1B5D88'),
    'lamp_core_cyan': ('#30C0C2','#F2FFFF','#167878'),
    'lamp_core_off': ('#CBD5D2','#FFFFFF','#7C8987'),
    'lamp_core_fault': ('#ED8A38','#FFF3C4','#A84621'),
    'glass': ('#BCDDEB','#E2F9FF','#81BFD4'),
    'beam_core': ('#DEF9FF','#F2FDFF','#C6EDF7'),
    'beam_outer': ('#63BBD0','#63BBD0','#63BBD0'),
}
def textures():
    mats = {}
    for name, colors in PALETTE.items():
        if name in MATERIALS:
            mats[name] = material(name)
            continue
        im = Image.new('RGBA',(16,16),colors[0]); d = ImageDraw.Draw(im)
        if name == 'cyan':
            d.polygon(((0,0),(5,0),(9,15),(5,15)),fill=colors[1])
        if name in ALPHA: im.putalpha(ALPHA[name])
        mats[name] = im
    return mats


ALPHA = {'glass': 105, 'beam_outer': 35, 'beam_core': 165,
         'lamp_blue': 225, 'lamp_cyan': 225, 'lamp_off': 185, 'lamp_fault': 230}

LAMP_STATES = {'off', 'enabled', 'fault'}
LAMP_SHELLS = {
    ('link', 'enabled'): 'lamp_blue', ('chunk', 'enabled'): 'lamp_cyan',
    ('link', 'off'): 'lamp_off', ('chunk', 'off'): 'lamp_off',
    ('link', 'fault'): 'lamp_fault', ('chunk', 'fault'): 'lamp_fault',
}
LAMP_CORES = {
    ('link', 'enabled'): 'lamp_core_blue', ('chunk', 'enabled'): 'lamp_core_cyan',
    ('link', 'off'): 'lamp_core_off', ('chunk', 'off'): 'lamp_core_off',
    ('link', 'fault'): 'lamp_core_fault', ('chunk', 'fault'): 'lamp_core_fault',
}


def pixel_prism(center, size, depth, material_name, cut=1.0, group='fixed', emissive=False):
    """Create-style pixel-snap chamfered prism, dimensions are model pixels."""
    cx, cy, cz = center
    width, height = size
    half_w, half_h = width / 2, height / 2
    outline = [(-half_w + cut, -half_h), (half_w - cut, -half_h),
               (half_w, -half_h + cut), (half_w, half_h - cut),
               (half_w - cut, half_h), (-half_w + cut, half_h),
               (-half_w, half_h - cut), (-half_w, -half_h + cut)]
    z0, z1 = cz - depth / 2, cz + depth / 2
    front = [[cx + x, cy + y, z0] for x, y in outline]
    back = [[cx + x, cy + y, z1] for x, y in outline]
    mesh = [face(front, material_name, group=group), face(back[::-1], material_name, group=group)]
    for i in range(len(outline)):
        j = (i + 1) % len(outline)
        f = face([front[i], front[j], back[j], back[i]], material_name, group=group)
        if emissive:
            f['emissive'] = True
        mesh.append(f)
    return mesh


def modeled_bulb(cx, center_y, state, role='link', scale=1.0):
    """Create display_link/tube.json bulb using only MC model-pixel boxes."""
    if state not in LAMP_STATES or role not in {'link', 'chunk'}:
        raise ValueError(f'unknown lamp state or role: {role}/{state}')
    shell = LAMP_SHELLS[(role, state)]
    core = LAMP_CORES[(role, state)]
    g = []
    def b(lo, hi, material_name):
        g.extend(box(lo, hi, material_name))
    # Create's tube.json bulb is [8.5,7,2.5]..[13.5,12,7.5]: 5x5x5 pixels.
    # This preview keeps that exact cuboid silhouette and uses only integer or
    # half-pixel boundaries, so no freeform triangles can appear.
    b((cx - 3 * scale, center_y - 3 * scale, -7.5),
      (cx + 3 * scale, center_y - 2 * scale, -6.5), 'iron')
    b((cx - 2 * scale, center_y - 2.5 * scale, -7.75),
      (cx + 2 * scale, center_y - 1.5 * scale, -6.25), 'cast_white')
    b((cx - 1 * scale, center_y - 1.5 * scale, -7.9),
      (cx + 1 * scale, center_y - .5 * scale, -6.0), 'iron')
    # Rear frame gives the glass a visible modeled edge without changing its size.
    b((cx - 2.5 * scale, center_y - 2.5 * scale, -7.78),
      (cx + 2.5 * scale, center_y + 2.5 * scale, -7.52), 'iron')
    # The shell is the full 5x5x5 Create component; the solid core is recessed.
    b((cx - 2.5 * scale, center_y - 2.5 * scale, -7.5),
      (cx + 2.5 * scale, center_y + 2.5 * scale, -2.5), 'glass')
    b((cx - 1.5 * scale, center_y - 1.5 * scale, -7.35),
      (cx + 1.5 * scale, center_y + 1.5 * scale, -6.6), core)
    # A thin front retaining rim follows the pixel-grid border of the tube.
    for x in (-2.5, 2.0):
        b((cx + x * scale, center_y - 2.5 * scale, -7.72),
          (cx + (x + .5) * scale, center_y + 2.5 * scale, -7.48), 'iron')
    for y in (-2.5, 2.0):
        b((cx - 2.5 * scale, center_y + y * scale, -7.72),
          (cx + 2.5 * scale, center_y + (y + .5) * scale, -7.48), 'iron')
    return g


def base_mesh(phase, link_state='enabled', chunk_state='enabled'):
    if link_state not in LAMP_STATES or chunk_state not in LAMP_STATES:
        raise ValueError('lamp states must be off, enabled, or fault')
    g=[]
    def b(lo,hi,m): g.extend(box(lo,hi,m))
    b((-8,0,-8),(8,4,8),'andesite')
    b((-7.5,4,-6.5),(7.5,12,6.5),'andesite')
    b((-8,12,-8),(8,14,8),'cast_white')
    # Recessed front machine panel. The main Link bulb is deliberately offset
    # left; the smaller second status lamp belongs to the gauge assembly.
    b((-7,3.8,-7.6),(7,12.8,-6.5),'casing')
    b((-7.5,4.2,-7.75),(-6.9,12.4,-7.7),'iron')
    b((6.9,4.2,-7.75),(7.5,12.4,-7.7),'iron')
    g.extend(modeled_bulb(-2.7, 9.0, link_state, 'link', 1.0))
    # Create-style factory gauge with the small chunk-loading bulb mounted above it.
    b((1.0,4.0,-7.9),(6.6,9.4,-6.75),'iron')
    g += ring((3.8,6.5,-7.62),2.25,.46,.55,'cast_white',segments=12)
    g += ring((3.8,6.5,-7.68),1.82,1.82,.06,'paper',segments=12)
    a=-55+55*(1-math.cos(2*math.pi*phase))
    g += box((3.65,6.1,-7.98),(3.95,7.5,-7.91),'blue_gray',turns=(('z',a,(3.8,6.5,-7.96)),))
    b((3.45,6.15,-7.96),(4.15,6.85,-7.88),'iron')
    g.extend(modeled_bulb(3.8, 11.85, chunk_state, 'chunk', .48))
    # Short mounting stem makes the small lamp visibly belong to the gauge.
    b((3.25,9.0,-7.72),(4.35,10.0,-6.35),'iron')
    b((3.5,9.75,-7.88),(4.1,11.0,-6.2),'cast_white')
    # Standard face-center connections: south shaft and east fluid pipe.
    for lo,hi in (((-3,5,6.5),(3,6,7.7)),((-3,10,6.5),(3,11,7.7)),((-3,6,6.5),(-2,10,7.7)),((2,6,6.5),(3,10,7.7))): b(lo,hi,'cast_white')
    b((-2,6,6.5),(2,10,7),'iron')
    b((-1.5,6.5,7),(1.5,9.5,8),'iron')
    b((-1,7,7.96),(1,9,8),'blue_gray')
    for lo,hi in (((7.5,5,-3),(8,6,3)),((7.5,10,-3),(8,11,3)),((7.5,6,-3),(8,10,-2)),((7.5,6,2),(8,10,3))): b(lo,hi,'cast_white')
    b((7.55,6,-2),(7.65,10,2),'iron')
    b((7.65,7,-1),(7.7,9,1),'blue_gray')
    return g


def top_mesh():
    g=[]
    def b(lo,hi,m): g.extend(box(lo,hi,m))
    # Four full-width receiver lips surround a recessed lower optical aperture.
    for lo,hi in (((-8,0,-8),(8,3,-4)),((-8,0,4),(8,3,8)),((-8,0,-4),(-4,3,4)),((4,0,-4),(8,3,4))): b(lo,hi,'cast_white')
    b((-4,2,-4),(4,3,4),'iron'); b((-2.5,1.8,-2.5),(2.5,2,2.5),'cyan')
    b((-7,3,-7),(7,10,7),'cast_white')
    for z in (-8,7):
        for x in (-4,2): b((x,3,z),(x+2,10,z+1),'cast_white')
        b((-.75,5,z),(.75,8,z+.8),'blue_gray')
    for x in (-8,7):
        for z in (-4,2): b((x,3,z),(x+1,10,z+2),'cast_white')
        b((x,5,-.75),(x+.8,8,.75),'blue_gray')
    b((-6,10,-6),(6,12,6),'cast_white'); b((-3.5,12,-3.5),(3.5,13,3.5),'iron')
    b((-1,13,-1),(1,15.2,1),'cyan')
    b((-2.5,13,-2.5),(2.5,15.5,2.5),'glass')
    b((-1.8,15.5,-1.8),(1.8,16,1.8),'glass')
    return g


def build(phase=0, gap=3, powered=True, link_state='enabled', chunk_state='enabled'):
    """Return mesh/components for two placed blocks and independently staged bulbs."""
    if not math.isfinite(phase) or not 0<=phase<=1: raise ValueError('phase must be 0..1')
    if isinstance(gap,bool) or not isinstance(gap,int) or gap<1: raise ValueError('gap must be a positive integer')
    if not isinstance(powered,bool): raise ValueError('powered must be boolean')
    if link_state not in LAMP_STATES or chunk_state not in LAMP_STATES:
        raise ValueError('lamp states must be off, enabled, or fault')
    mesh=[]; components=[]
    for name,local,y in (('signal_base',base_mesh(phase, link_state, chunk_state),0),('signal_top',top_mesh(),16*(gap+1))):
        pts=np.array([p for f in local for p in f['points']])
        assert (pts.min(0)>=np.array([-8,0,-8])-1e-7).all() and (pts.max(0)<=np.array([8,16,8])+1e-7).all()
        g=move(local,offset=(0,y,0))
        for f in g: f.update(group=name,kind='placed_component',owner_cell=[0,y//16,0])
        mesh+=g
        components.append({'id':name,'cell':[0,y//16,0],'facing':'north','required':True,'local_bounds':[pts.min(0).tolist(),pts.max(0).tolist()],'world_bounds':[[-8,y,-8],[8,y+16,8]],'mesh_faces':len(g)})
    if powered:
        for material,r in (('beam_core',1.5),('beam_outer',3)):
            g=box((-r,16,-r),(r,16*(gap+1),r),material)
            for f in g: f.update(group='beam',kind='non_block_effect',emissive=True,material_role=material)
            mesh+=g
    return mesh,components


def arrow(im,pts):
    d=ImageDraw.Draw(im); d.line(pts,fill='#5C879C',width=2)
    a,b=np.array(pts[-2],float),np.array(pts[-1],float); v=a-b; v/=np.linalg.norm(v); n=np.array((-v[1],v[0]))
    d.polygon([tuple(b),tuple(b+9*v+4*n),tuple(b+9*v-4*n)],fill='#5C879C')


def project(p,size,center,scale,offset,yaw=28,pitch=12):
    c=(np.array(p)-center) @ (rotation('x',-pitch) @ rotation('y',yaw)).T
    return offset[0]+size[0]/2+c[0]*scale,offset[1]+size[1]/2-c[1]*scale


def footer(im):
    text(im,(30,im.height-30),'DISTANT STOCK / OFFLINE CONCEPT ONLY / STRUCTURE RECOGNITION NOT IMPLEMENTED',13,MUTED)


def hero(mats):
    im=Image.new('RGBA',(1420,1070),BG)
    header(im,'SIGNAL LINK / TWO PLACED BLOCKS','ONE BASE + ONE TOP / BLUE-WHITE OPTICAL BEAM / EXAMPLE AIR GAP: 3 BLOCKS')
    mesh,_=build(); size=(670,850); offset=(20,125); center=np.array((0,40,0)); scale=8
    im.alpha_composite(render(mesh,mats,size=size,yaw=28,pitch=12,center=center,scale=scale),offset)
    labels=[('01 / PLACE THE RECEIVER TOP',['A complete one-block casting, not a thin cap.','Lower receiver aperture; glass link-status lamp.'],(0,73,0),185),
            ('02 / AIR BETWEEN THE BLOCKS',['Cyan outer sheath + blue-white central beam.','Effect mesh only: no beam blocks are placed.'],(0,40,0),390),
            ('03 / PLACE THE BASE FIRST',['One large modeled Create-style bulb.','Factory gauge + small status bulb above it.'],(-2.7,9,-7),610)]
    for title,lines,p,y in labels:
        text(im,(750,y),title,22)
        for j,line in enumerate(lines): text(im,(750,y+36+27*j),line,17,MUTED)
        end=project(p,size,center,scale,offset); arrow(im,[(730,y+16),(690,y+16),end])
    text(im,(735,810),'BUILD ORDER',20)
    for j,line in enumerate(('1. Place the base and connect input ports.','2. Build temporary scaffolding beside the column.','3. Place the receiver top directly above the base.','4. Remove scaffolding; the gap stays air.')):
        text(im,(735,847+29*j),line,16,MUTED)
    text(im,(45,995),'2 real blocks / 3 air cells / total height 5 blocks',17,MUTED)
    footer(im); return im


def levels(mats):
    im=Image.new('RGBA',(1550,1180),BG)
    header(im,'SIGNAL LINK / SPACING EXAMPLES, SAME SCALE','GAPS 2 / 4 / 6 ARE CONCEPT EXAMPLES, NOT FINAL TIERS. TOTAL HEIGHT = GAP + 2.')
    for i,gap in enumerate((2,4,6)):
        x=35+500*i; mesh,_=build(gap=gap)
        size=(460,850); center=(0,64,0); scale=5.8
        im.alpha_composite(render(mesh,mats,size=size,center=center,scale=scale,yaw=0,pitch=0),(x,145))
        text(im,(x+60,114),f'{gap} AIR CELLS / {gap+2} TOTAL',20)
        d=ImageDraw.Draw(im)
        y0=145+425+64*scale; yt=y0-(gap+2)*16*scale
        d.line((x+100,yt,x+100,y0),fill='#8CA2AE',width=1)
        for y in range(gap+3):
            sy=y0-y*16*scale; d.line((x+93,sy,x+107,sy),fill='#8CA2AE',width=2)
        text(im,(x+95,990),'Same two individually placed blocks',15,MUTED)
    text(im,(42,1050),'CONCEPT RULE: effectiveTier = min(heightTier, rpmTier)',22)
    text(im,(42,1084),'Both mappings and the height cap are configurable design placeholders. No thresholds or gameplay are implemented.',16,MUTED)
    footer(im); return im


def details(mats):
    im=Image.new('RGBA',(1560,1160),BG)
    header(im,'SIGNAL TOWER BASE / CREATE-STYLE BULB + GAUGE','LEFT: one large modeled Link bulb. RIGHT: factory gauge with one small status bulb mounted above it.')
    variants=[
        ('ONLINE / BLUE MAIN + CYAN GAUGE',base_mesh(.25,'enabled','enabled'),30,0),
        ('OFF / WHITE MAIN + WHITE GAUGE',base_mesh(.25,'off','off'),30,0),
        ('FAULT / ORANGE MAIN + ORANGE GAUGE',base_mesh(.25,'fault','fault'),30,0),
        ('MIXED / MAIN OFF + GAUGE FAULT',base_mesh(.25,'off','fault'),30,0),
    ]
    for i,(name,mesh,pitch,yaw) in enumerate(variants):
        x=35+(i%2)*780; y=125+(i//2)*470
        im.alpha_composite(render(mesh,mats,size=(720,390),center=(0,8,0),scale=22,yaw=yaw,pitch=pitch),(x,y))
        text(im,(x+15,y+18),name,18)
        text(im,(x+15,y+48),'Create-style cut-corner bulb / iron socket / short neck / solid core',14,MUTED)
    text(im,(35,1060),'The gauge is retained as secondary service hardware below the bulb pair. South kinetic shaft and east fluid flange remain face-centred.',16,MUTED)
    footer(im); return im


def main():
    DOC.mkdir(parents=True,exist_ok=True); OUT.mkdir(parents=True,exist_ok=True)
    mats=textures(); mesh,components=build(); checks=[]
    for gap in (1,2,3,4,6,10):
        for phase in np.linspace(0,1,9):
            for powered in (False,True):
                m,c=build(float(phase),gap,powered); pts=np.array([p for f in m for p in f['points']])
                assert np.isfinite(pts).all() and len(c)==2
                assert np.allclose(pts.min(0),[-8,0,-8]) and np.allclose(pts.max(0),[8,16*(gap+2),8])
                assert any(f['group']=='beam' for f in m)==powered
                assert {f['material'] for f in m}<=mats.keys()
    end,_=build(1); assert all(np.allclose(a['points'],b['points']) for a,b in zip(mesh,end))
    for n,t in mats.items(): t.save(DOC/f'{n}.png')
    metadata={
        'id':'two_block_signal_link','revision':5,'status':'offline layout/model concept; no production implementation',
        'build_interface':'build(phase=0, gap=3, powered=True) -> (mesh, components)',
        'dimensions':{'footprint_blocks':[1,1],'gap_default':3,'total_height_formula':'gap+2','pixels_per_block':16,'base_bounds':[[-8,0,-8],[8,16,8]],'top_bounds_formula':'x/z -8..8, y 16*(gap+1)..16*(gap+2)'},
        'components':components,'placement':{'real_block_count':2,'base_cell':[0,0,0],'top_cell_formula':[0,'gap+1',0],'between':'air','order':['place base','temporary scaffold beside beam column','place top in aligned column','remove scaffold'],'structure_recognition_implemented':False},
        'beam':{'mesh_group':'beam','kind':'non_block_effect','creates_real_blocks':False,'enabled_when':'powered=True in preview only','core_half_width':1.5,'outer_half_width':3,'particles':0,'implemented':False},
        'ports':[{'owner':'signal_base','kind':'kinetic_input','face':'south','center':[0,8,8]},{'owner':'signal_base','kind':'fluid_input','face':'east','center':[8,8,0]}],
        'tiers':{'formula':'effectiveTier = min(heightTier, rpmTier)','heightTier':'configurable capped mapping of vertical separation','rpmTier':'configurable mapping of input rotational speed','height_cap':None,'rpm_thresholds':None,'examples_gap':[2,4,6],'examples_are_final_tiers':False,'implemented':False},
        'interactions':[{'owner':'signal_base','component':'physical gauge','purpose':'display stored aether','preview_needle_degrees':'-55+55*(1-cos(2*pi*phase))','implemented':False},{'owner':'signal_top','component':'glass lamp','action':'right_click','purpose':'inspect link state and faults','implemented':False}],
        'materials':{n:{'texture':f'{n}.png','palette':list(c),'alpha':ALPHA.get(n,255),'sampling':'nearest'} for n,c in PALETTE.items()},
        'mesh':'mesh.json','mesh_face_count':len(mesh),'previews':[f'build/art/concepts/tower-{n}.png' for n in ('hero','levels','details')],
        'limitations':['No structure recognition or runtime operation.','No final height or RPM tier thresholds.','Two explicit player-placed components; optical beam is an effect, not a block.'],
        'generate':'python3 -B scripts/concepts/gen_tower.py'}
    (DOC/'concept.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    (DOC/'mesh.json').write_text(json.dumps({'format':'offline-quad-mesh-v1','phase':0,'gap':3,'powered':True,'faces':mesh},indent=2)+'\n')
    for name,im in {'hero':hero(mats),'levels':levels(mats),'details':details(mats)}.items():
        p=OUT/f'tower-{name}.png'; im.convert('RGB').save(p)
        with Image.open(p) as test: test.verify()
        checks.append({'path':str(p.relative_to(ROOT)),'size':list(im.size),'png_verified':True}); print(p)
    (DOC/'validation.json').write_text(json.dumps({'two_placed_blocks_pass':True,'local_cell_bounds_pass':True,'gap_phase_power_cases':108,'beam_off_pass':True,'phase_loop_pass':True,'images':checks},indent=2)+'\n')
    print('PASS: 108 gap/phase/power cases; two one-cell solids; beam is effect-only; PNGs verified.')


if __name__=='__main__': main()
