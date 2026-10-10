"""Light Distant Casing material on the unchanged vanilla diamond armour UVs."""
from pathlib import Path
import io
import sys
import zipfile
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from render_scene import box, render, move
OUT=HERE/'textures'; OUT.mkdir(parents=True,exist_ok=True)
JAR=ROOT/'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar'
CASING=Image.open(ROOT/'src/main/resources/assets/distantstock/textures/block/tower/casing_inactive.png').convert('RGBA')


def vanilla(name):
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read('assets/minecraft/textures/'+name+'.png'))).convert('RGBA')


def material(src):
    # Keep the original bevels and complete alpha mask: no added plates or coverage.
    luminance=lambda c: .2126*c[0]+.7152*c[1]+.0722*c[2]
    values=sorted(set(round(luminance(c),3) for c in src.getdata() if c[3]))
    low,high=values[0],values[-1]
    palette=[(139,168,177),(160,187,194),(183,207,214),(203,223,226),(225,237,236),(244,249,244)]
    im=Image.new('RGBA',src.size)
    for y in range(src.height):
        for x in range(src.width):
            c=src.getpixel((x,y))
            if not c[3]:continue
            t=(luminance(c)-low)/max(1,high-low)
            color=palette[min(5,int(t*5.99))]
            # Subdued broad texels from the casing's centre, never its block frame.
            sample=CASING.getpixel((4+(x//2)%8,4+(y//2)%8))
            adjustment=round((luminance(sample)-160)*.06)
            im.putpixel((x,y),tuple(max(0,min(255,v+adjustment)) for v in color)+(c[3],))
    assert im.getchannel('A').tobytes()==src.getchannel('A').tobytes()
    return im


layers={i:material(vanilla(f'models/armor/diamond_layer_{i}')) for i in (1,2)}


def paint_covered(im, points, color):
    """Texture accents never enlarge the existing armour silhouette."""
    for xy in points:
        if im.getpixel(xy)[3]:
            ImageDraw.Draw(im).point(xy, fill=color)


plate=layers[1]
# Short optical inlay above the open face, recessed within the original brow.
paint_covered(plate,[(10,9),(11,9),(12,9),(13,9)],'#87becd')
paint_covered(plate,[(11,9),(12,9)],'#b0dce4')
# Four-by-four clipped diamond; steel perimeter and two quiet blue glass facets.
paint_covered(plate,[(23,24),(24,24),(22,25),(25,25),(22,26),(25,26),(23,27),(24,27)],'#c4d7db')
paint_covered(plate,[(23,25),(23,26)],'#abd4de')
paint_covered(plate,[(24,25),(24,26)],'#80b5c8')
paint_covered(plate,[(23,24),(22,25)],'#e1efed')
paint_covered(plate,[(25,26),(24,27)],'#97b3be')
# Limited machined edge glints, not additional panels or piping.
paint_covered(plate,[(44,20),(45,20),(48,20)],'#e4eeeb')
paint_covered(plate,[(4,27),(5,27),(8,27)],'#e0ecea')
for n,im in layers.items():
    assert im.getchannel('A').tobytes()==vanilla(f'models/armor/diamond_layer_{n}').getchannel('A').tobytes()
for i,im in layers.items():im.save(OUT/f'distant_layer_{i}.png')
for kind in ('helmet','chestplate','leggings','boots'):
    icon=material(vanilla('item/diamond_'+kind))
    if kind=='helmet':
        paint_covered(icon,[(6,5),(7,5),(8,5),(9,5)],'#a1d2df')
    elif kind=='chestplate':
        paint_covered(icon,[(7,7),(8,7),(7,9),(8,9)],'#d4e5e7')
        paint_covered(icon,[(7,8)],'#b2d8e1')
        paint_covered(icon,[(8,8)],'#84b5c7')
    elif kind=='boots':
        paint_covered(icon,[(4,8),(5,8),(10,8),(11,8)],'#e0ecea')
    icon.save(OUT/f'distant_{kind}.png')


def net(u,v,w,h,d):
    return dict(east=(u,v+d,u+d,v+d+h),north=(u+d,v+d,u+d+w,v+d+h),
                west=(u+d+w,v+d,u+2*d+w,v+d+h),south=(u+2*d+w,v+d,u+2*d+2*w,v+d+h),
                up=(u+d,v,u+d+w,v+d),down=(u+d+w,v,u+d+2*w,v+d))


NETS=dict(head=net(0,0,8,8,8),body=net(16,16,8,12,4),arm=net(40,16,4,12,4),leg=net(0,16,4,12,4))
mesh=[];textures={}


def cuboid(name,lo,hi,atlas=None,part=None,inflate=0,mirror=False):
    faces=box(tuple(v-inflate for v in lo),tuple(v+inflate for v in hi),'')
    for direction,f in zip(('north','south','west','east','up','down'),faces):
        key=name+'_'+direction
        if atlas is not None:
            tile=atlas.crop(NETS[part][direction])
            if mirror:tile=tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        else:
            tile=Image.new('RGBA',(8,8),'#909a98')
            if name=='under_head':
                tile=Image.new('RGBA',(8,8),'#bbac94')
                if direction=='north':
                    d=ImageDraw.Draw(tile)
                    d.point((2,3),fill='#54605e');d.point((5,3),fill='#54605e')
                    d.line((3,6,4,6),fill='#96856f')
        textures[key]=tile;f['material']=key
        f['uv']=[[0,0],[tile.width,0],[tile.width,tile.height],[0,tile.height]]
    mesh.extend(faces)


cuboid('under_head',(-4,24,-4),(4,32,4))
cuboid('under_body',(-4,12,-2),(4,24,2))
for side,sign in (('right',-1),('left',1)):
    start=len(mesh)
    lo,hi=((-8,12,-2),(-4,24,2)) if sign<0 else ((4,12,-2),(8,24,2))
    cuboid('under_'+side+'arm',lo,hi)
    cuboid(side+'arm',lo,hi,layers[1],'arm',.96,sign>0)
    mesh[start:]=move(mesh[start:],turns=[('z',sign*5,(sign*4,24,0))])
    lo,hi=((-5.05,0,-2),(-1.05,12,2)) if sign<0 else ((1.05,0,-2),(5.05,12,2))
    cuboid('under_'+side+'leg',lo,hi)
    cuboid(side+'leg',lo,hi,layers[2],'leg',.5,sign>0)
    cuboid(side+'boot',lo,hi,layers[1],'leg',1,sign>0)
cuboid('chest',(-4,12,-2),(4,24,2),layers[1],'body',1)
cuboid('waist',(-4,12,-2),(4,24,2),layers[2],'body',.48)
cuboid('helmet',(-4,24,-4),(4,32,4),layers[1],'head',1)

from attachments import add_attachments
add_attachments(mesh,textures,HERE)

sheet=Image.new('RGBA',(1320,840),'#e6e3db')
font='/System/Library/Fonts/STHeiti Medium.ttc';d=ImageDraw.Draw(sheet)
def label(x,y,text,size=20):d.text((x,y),text,font=ImageFont.truetype(font,size),fill='#415f6b')
label(35,20,'远仓机壳护甲 / 立体光学组件',32)
label(36,66,'薄肩壳 · 侧置光学件 · 背部校准模块 · 小型护踵')
for i,(name,yaw,title) in enumerate((('front',0,'正面'),('three_quarter',-28,'四分之三视角'),('back',155,'后侧视角'))):
    pic=render(mesh,textures,size=(440,650),yaw=yaw,pitch=7,center=(0,16,0),scale=17,background='#e6e3db')
    pic=pic.transpose(Image.Transpose.FLIP_LEFT_RIGHT);pic.save(HERE/(name+'.png'))
    sheet.alpha_composite(pic,(i*440,90));label(i*440+175,720,title)
for i,kind in enumerate(('helmet','chestplate','leggings','boots')):
    sheet.alpha_composite(Image.open(OUT/f'distant_{kind}.png').resize((64,64),Image.Resampling.NEAREST),(470+i*100,762))
label(35,780,'独立美术概念 / 未接入模组',17)
sheet.save(HERE/'design_sheet.png')
print(HERE/'design_sheet.png')
