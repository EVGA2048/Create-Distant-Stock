"""Original low-profile optical fittings for the armour concept renderer."""
from pathlib import Path
import json
from PIL import Image, ImageDraw
from render_scene import box, move


def add_attachments(mesh, textures, out):
    palette={'shell':'#c7dbdf','edge':'#e6efea','recess':'#7f9da9',
             'steel':'#a3bbc3','glass':'#9cd5e1','lens':'#76b3c7','seal':'#617f8c'}
    atlas=Image.new('RGBA',(32,16))
    for i,(name,color) in enumerate(palette.items()):
        tile=Image.new('RGBA',(8,8),color)
        d=ImageDraw.Draw(tile)
        if name=='shell':
            d.line((0,0,7,0),fill='#dce9e9')
            d.line((0,7,7,7),fill='#b8cdd3')
            d.line((2,3,4,3),fill='#ccdfe2')
        elif name=='glass':
            d.line((1,1,4,1),fill='#d8f1f3');d.line((2,2,5,2),fill='#bde7ed')
            d.line((1,6,3,6),fill='#8ec4d3')
        textures['fitting_'+name]=tile
        atlas.paste(tile,(i%4*8,i//4*8))
    atlas.save(out/'textures/fittings.png')
    shapes=[]

    def part(name,lo,hi,material='shell',bone='body',turns=()):
        faces=box(lo,hi,'fitting_'+material)
        if turns:faces=move(faces,turns=turns)
        mesh.extend(faces)
        shapes.append(dict(name=name,bone=bone,lo=lo,hi=hi,material=material,turns=turns))

    # Shallow earpieces leave the face open. The glass inset faces forwards.
    for side,sign in (('left',1),('right',-1)):
        xa,xb=(5.01,6.05) if sign>0 else (-6.05,-5.01)
        part(side+'_optical_housing',(xa,27.4,-3.25),(xb,30.1,-.35),bone='head')
        part(side+'_optical_cap',(xa,30.1,-2.9),(xb,30.45,-.65),'edge','head')
        part(side+'_optical_socket',(xa+.12,27.85,-3.45),(xb-.12,29.65,-3.25),'recess','head')
        part(side+'_optical_glass',(xa+.25,28.1,-3.5),(xb-.25,29.4,-3.45),'glass','head')
    part('brow_lip',(-3.4,29.0,-5.55),(3.4,29.45,-5.0),'edge','head')
    part('brow_inlay',(-1.7,29.04,-5.61),(1.7,29.28,-5.55),'glass','head')

    # Thin, stepped shoulder shells. They articulate with the arm, not the torso.
    for side,sign in (('left',1),('right',-1)):
        turns=[('z',sign*5,(sign*4,24,0))]
        def shoulder(name,a,b,mat):
            lo=(a[0]*sign,a[1],a[2]);hi=(b[0]*sign,b[1],b[2])
            if sign<0:lo=(hi[0],lo[1],lo[2]);hi=(a[0]*sign,hi[1],hi[2])
            part(side+'_'+name,lo,hi,mat,side+'_arm',turns)
        shoulder('shoulder_seal',(4.45,23.05,-2.7),(9.25,24.55,2.7),'recess')
        shoulder('shoulder_plate',(4.5,23.5,-2.95),(9.25,24.65,2.95),'shell')
        shoulder('shoulder_crown',(4.85,24.65,-2.55),(8.95,25.1,2.55),'edge')
        shoulder('shoulder_outer_lip',(8.95,22.95,-2.35),(9.55,24.1,2.35),'shell')
        shoulder('shoulder_side_insert',(9.55,23.15,-.85),(9.65,23.8,.85),'steel')

    # Small central calibration module: a window and two slots, not a full backpack.
    part('back_mount',(-2.7,17.6,3.0),(2.7,23.3,3.55),'recess')
    part('back_shell',(-2.45,17.7,3.55),(2.45,23.15,5.0))
    part('back_top_bevel',(-2.1,23.15,3.55),(2.1,23.6,4.7),'edge')
    part('back_bottom_bevel',(-2.1,17.25,3.55),(2.1,17.7,4.7),'steel')
    part('window_socket',(-1.3,19.25,5.0),(1.3,22.65,5.12),'recess')
    part('optical_window',(-.9,19.55,5.12),(.9,22.35,5.22),'glass')
    part('window_upper_lip',(-1.1,22.35,5.12),(1.1,22.65,5.3),'edge')
    part('window_lower_lip',(-1.1,19.25,5.12),(1.1,19.55,5.3),'steel')
    for y in (18.1,18.6):
        part('back_slot_'+str(y),(-1.35,y,5.0),(1.35,y+.16,5.06),'seal')

    # Small ankle heel guards echo the layered shoulder construction.
    for side,sign in (('left',1),('right',-1)):
        center=sign*3.05
        part(side+'_heel_guard',(center-1.65,1.15,3.0),(center+1.65,2.0,3.55),'steel',side+'_leg')
        part(side+'_heel_edge',(center-1.5,2.0,3.0),(center+1.5,2.35,3.35),'edge',side+'_leg')

    (out/'attachments.json').write_text(json.dumps({'units':'Minecraft model pixels',
        'format':'offline concept geometry, not a game-ready armour model',
        'parts':shapes,'palette':palette},ensure_ascii=False,indent=2)+'\n')
