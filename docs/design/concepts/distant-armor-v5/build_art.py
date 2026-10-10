"""Distant Casing frames painted per armour face, on unchanged vanilla diamond alpha masks."""
from pathlib import Path
import io
import sys
import zipfile
from PIL import Image, ImageColor, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from render_scene import box, render, move
OUT=HERE/'textures'; OUT.mkdir(parents=True,exist_ok=True)
JAR=ROOT/'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar'
BLOCKS=ROOT/'src/main/resources/assets/distantstock/textures/block/tower'

# Colours are taken from casing_inactive / casing_active so the armour reads as the tower block.
FRAME_LIGHT,FRAME_DARK='#8599a2','#758a94'
CORNER={'tl':'#758a94','tr':'#667c87','bl':'#667c87','br':'#5b727e'}
BEVEL_LIGHT=['#c5d4da','#d4e2e7','#c5d4da','#b8c8cf']
BEVEL_DARK=['#a9bac2','#a9bac2','#97a9b2','#a9bac2']
CENTRE=[(184,200,207),(192,207,214),(197,210,217),(205,218,223),(210,222,227),(214,226,230)]
GROOVE='#617883'
ETHER,ETHER_GLINT,ETHER_LOW='#b7e0ed','#e8fbff','#acdcf1'
ICON=[(91,114,126),(117,138,148),(133,153,162),(169,186,194),(197,212,218),(214,226,230)]


def vanilla(name):
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read('assets/minecraft/textures/'+name+'.png'))).convert('RGBA')


def paint(im,xy,color):
    """Recolour an existing texel, keeping its alpha."""
    im.putpixel(xy,ImageColor.getrgb(color)[:3]+(im.getpixel(xy)[3],))


def luminance(c):
    return .2126*c[0]+.7152*c[1]+.0722*c[2]


def ramp(src,palette):
    values=sorted(luminance(c) for c in src.getdata() if c[3])
    low,high=values[0],values[-1]
    def pick(c):
        t=(luminance(c)-low)/max(1,high-low)
        return palette[min(len(palette)-1,int(t*(len(palette)-.01)))]
    return pick


def net(u,v,w,h,d):
    return dict(east=(u,v+d,u+d,v+d+h),north=(u+d,v+d,u+d+w,v+d+h),
                west=(u+d+w,v+d,u+2*d+w,v+d+h),south=(u+2*d+w,v+d,u+2*d+2*w,v+d+h),
                up=(u+d,v,u+d+w,v+d),down=(u+d+w,v,u+d+2*w,v+d))


NETS=dict(head=net(0,0,8,8,8),body=net(16,16,8,12,4),arm=net(40,16,4,12,4),leg=net(0,16,4,12,4))
STEPS={(0,-1):'up',(0,1):'down',(-1,0):'left',(1,0):'right'}


def framed(src):
    """Each UV face becomes a small casing panel: dark rim, light bevel, quiet centre.

    Rings are measured inwards from the face rectangle and from the vanilla cut-outs,
    so the helmet opening and boot cuffs are rimmed like the casing's own edges."""
    im=Image.new('RGBA',src.size)
    centre=ramp(src,CENTRE)
    for x in range(src.width):
        for y in range(src.height):
            c=src.getpixel((x,y))
            if c[3]:im.putpixel((x,y),centre(c)+(c[3],))
    for faces in NETS.values():
        for x0,y0,x1,y1 in faces.values():
            inside=lambda p:x0<=p[0]<x1 and y0<=p[1]<y1 and src.getpixel(p)[3]>0
            cells=[(x,y) for x in range(x0,x1) for y in range(y0,y1) if inside((x,y))]
            if not cells:continue
            bevel=min(x1-x0,y1-y0)>=8
            ring={}
            for p in cells:
                if any(not inside((p[0]+dx,p[1]+dy)) for dx,dy in STEPS):ring[p]=0
            for k in (1,):
                for p in cells:
                    if p not in ring and any(ring.get((p[0]+dx,p[1]+dy))==k-1 for dx,dy in STEPS):ring[p]=k
            for (x,y),k in ring.items():
                if k==1 and not bevel:continue
                towards={name for (dx,dy),name in STEPS.items()
                         if (not inside((x+dx,y+dy)) if k==0 else ring.get((x+dx,y+dy))==k-1)}
                light=bool(towards&{'up','left'});dark=bool(towards&{'down','right'})
                if k==0:
                    vertical='t' if 'up' in towards else 'b' if 'down' in towards else ''
                    horizontal='l' if 'left' in towards else 'r' if 'right' in towards else ''
                    color=CORNER[vertical+horizontal] if vertical and horizontal else FRAME_LIGHT if light else FRAME_DARK
                else:
                    color=(BEVEL_DARK if dark and not light else BEVEL_LIGHT)[(x+y)%4]
                paint(im,(x,y),color)
    assert im.getchannel('A').tobytes()==src.getchannel('A').tobytes()
    return im


def core(im,x,y):
    """A 2x2 lit window in a groove, the casing_active centre at armour scale."""
    for dx in range(-1,3):
        for dy in range(-1,3):
            assert im.getpixel((x+dx,y+dy))[3]
            if dx in (-1,2) or dy in (-1,2):paint(im,(x+dx,y+dy),GROOVE)
    for (dx,dy),color in (((0,0),ETHER_GLINT),((1,0),ETHER),((0,1),ETHER_LOW),((1,1),ETHER)):
        paint(im,(x+dx,y+dy),color)


layers={i:framed(vanilla(f'models/armor/diamond_layer_{i}')) for i in (1,2)}
core(layers[1],23,23)  # chest front face spans x20-27, y20-31
for n,im in layers.items():
    assert im.getchannel('A').tobytes()==vanilla(f'models/armor/diamond_layer_{n}').getchannel('A').tobytes()
    im.save(OUT/f'distant_layer_{n}.png')

for kind in ('helmet','chestplate','leggings','boots'):
    src=vanilla('item/diamond_'+kind)
    pick=ramp(src,ICON);icon=src.copy()
    for x in range(16):
        for y in range(16):
            c=src.getpixel((x,y))
            if c[3]:icon.putpixel((x,y),pick(c)+(c[3],))
    if kind=='chestplate':core(icon,7,9)
    assert icon.getchannel('A').tobytes()==src.getchannel('A').tobytes()
    icon.save(OUT/f'distant_{kind}.png')

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


# Mannequin and inflation match V2-V4 so the drafts compare side by side.
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

BACKGROUND='#e6e3db'
sheet=Image.new('RGBA',(1320,880),BACKGROUND)
font='/System/Library/Fonts/STHeiti Medium.ttc';d=ImageDraw.Draw(sheet)
def label(x,y,text,size=20):d.text((x,y),text,font=ImageFont.truetype(font,size),fill='#415f6b')
label(35,20,'远仓机壳护甲 V5 / 机壳框格',32)
label(36,66,'每块甲面按远仓机壳方块的边框与倒角绘制 · 胸前一枚激活态机壳小窗 · 轮廓与钻石护甲完全一致')
for i,(name,yaw,title) in enumerate((('front',0,'正面'),('three_quarter',-28,'四分之三视角'),('back',155,'后侧视角'))):
    pic=render(mesh,textures,size=(440,650),yaw=yaw,pitch=7,center=(0,16,0),scale=17,background=BACKGROUND)
    pic=pic.transpose(Image.Transpose.FLIP_LEFT_RIGHT);pic.save(HERE/(name+'.png'))
    sheet.alpha_composite(pic,(i*440,90));label(i*440+175,720,title)
for i,kind in enumerate(('helmet','chestplate','leggings','boots')):
    sheet.alpha_composite(Image.open(OUT/f'distant_{kind}.png').resize((64,64),Image.Resampling.NEAREST),(40+i*84,770))
label(400,775,'材质参照',17)
for i,name in enumerate(('casing_inactive','casing_active')):
    sheet.alpha_composite(Image.open(BLOCKS/f'{name}.png').convert('RGBA').resize((64,64),Image.Resampling.NEAREST),(490+i*84,770))
for i,n in enumerate((1,2)):
    sheet.alpha_composite(layers[n].resize((256,128),Image.Resampling.NEAREST),(700+i*300,745))
label(35,850,'独立美术概念 / 未接入模组',17)
sheet.save(HERE/'design_sheet.png')
print(HERE/'design_sheet.png')
