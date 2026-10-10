"""Independent armour texture study rendered with the project's render_scene.py."""
from pathlib import Path
import io
import json
import sys
import zipfile
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts/concepts'))
from render_scene import box, render, move

OUT = HERE / 'textures'
OUT.mkdir(parents=True, exist_ok=True)
JAR = ROOT / 'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar'
P = dict(dark='#344955', joint='#415764', shadow='#496b80', blue='#608ca3',
         light='#86afbf', rim='#b9ced0', white='#e2e9df', gold='#b68b49',
         gold_hi='#e6c57d', glass='#82c9db', glass_hi='#d9f4ef')


def vanilla(name):
    with zipfile.ZipFile(JAR) as jar:
        return Image.open(io.BytesIO(jar.read('assets/minecraft/textures/' + name + '.png'))).convert('RGBA')


def pattern(rows):
    colors = {'.': (0, 0, 0, 0), 'D': P['dark'], 'J': P['joint'], 'S': P['shadow'],
              'B': P['blue'], 'L': P['light'], 'R': P['rim'], 'W': P['white'],
              'G': P['gold'], 'H': P['gold_hi'], 'C': P['glass'], 'I': P['glass_hi']}
    im = Image.new('RGBA', (len(rows[0]), len(rows)))
    d = ImageDraw.Draw(im)
    for y, row in enumerate(rows):
        assert len(row) == im.width
        for x, value in enumerate(row):
            d.point((x, y), fill=colors[value])
    return im


def net(u, v, w, h, depth):
    return {'east': (u,v+depth,u+depth,v+depth+h),
            'north': (u+depth,v+depth,u+depth+w,v+depth+h),
            'west': (u+depth+w,v+depth,u+depth+w+depth,v+depth+h),
            'south': (u+2*depth+w,v+depth,u+2*depth+2*w,v+depth+h),
            'up': (u+depth,v,u+depth+w,v+depth),
            'down': (u+depth+w,v,u+depth+2*w,v+depth)}


NETS = {'head': net(0,0,8,8,8), 'body': net(16,16,8,12,4),
        'arm': net(40,16,4,12,4), 'leg': net(0,16,4,12,4)}


def armour(layer):
    mask = vanilla(f'models/armor/diamond_layer_{layer}')
    im = Image.new('RGBA', mask.size)
    for part, faces in NETS.items():
        for face, rect in faces.items():
            x0,y0,x1,y1 = rect
            d = ImageDraw.Draw(im)
            d.rectangle((x0,y0,x1-1,y1-1), fill=P['blue'] if face in ('north','up') else P['shadow'])
            d.line((x0,y0,x1-1,y0),fill=P['rim'])
            d.line((x0,y0,x0,y1-1),fill=P['light'])
            d.line((x0,y1-1,x1-1,y1-1),fill=P['dark'])
            if y1-y0>=8:
                d.line((x0+1,y1-4,x1-2,y1-4),fill=P['joint'])
    # Clip the general plate treatment to vanilla's actual armour coverage.
    im.putalpha(mask.getchannel('A'))
    if layer == 1:
        im.paste(pattern(['SSBLLBSS','RBBLLBBR','RWWRRWWR','DGGDDGGD',
                          'DCIIDCCD','SB....BS','GB....BG','JD....DJ']), (8,8))
        im.paste(pattern(['RRDDDDRR','RLBSSBLR','RLLSSLLR','SLLSSLLS',
                          'SBBSSBBS','SBBGHBBS','JSSGGSSJ','DBBDDBBD',
                          'DBBDDBBD','DJJDDJJD','RGGDDGGR','DDDDDDDD']), (20,20))
        im.paste(pattern(['RRRRRRRR','SLBBBBLS','SLBSSBLS','SBBSSBBS',
                          'SBBRRBBS','SBBCCBBS','DBBDDBBD','DBBDDBBD',
                          'DJJDDJJD','DBBDDBBD','RGGDDGGR','DDDDDDDD']), (32,20))
        im.paste(pattern(['RWWR','RLLR','SLLS','SGGS','DBBD','DJJD',
                          'DBBD','SLLS','SBBG','DBBD','DSSD','DDDD']), (44,20))
        # Both mirrored shoulders share the same original-resolution artwork.
        for x in (40,48,52):
            im.paste(pattern(['RRRR','SLLS','SLLS','SGGS','DBBD','DJJD',
                              'DBBD','SLLS','SBBS','DBBD','DSSD','DDDD']), (x,20))
        # Preserve vanilla boot coverage; new shin/toe motifs live inside it.
        for face, rect in NETS['leg'].items():
            x0,y0,x1,y1=rect
            for y in range(y0,y1):
                for x in range(x0,x1):
                    if mask.getpixel((x,y))[3]:
                        relative = y-y0
                        color = P['rim'] if relative==7 else P['blue'] if relative<10 else P['dark']
                        im.putpixel((x,y), tuple(int(color[n:n+2],16) for n in (1,3,5))+(255,))
        d=ImageDraw.Draw(im)
        d.line((5,30,6,30), fill=P['rim'])
        d.point((4,28),fill=P['gold'])
    else:
        for x in (0,4,8,12):
            im.paste(pattern(['DJJD','SBBG','SLLS','SBBS','DJJD','RLLR',
                              'SLLS','DSSD','DBBD','DBBD','DSSD','DDDD']), (x,20))
        # Waist UV is kept inside the vanilla leggings mask.
        d=ImageDraw.Draw(im)
        for y in range(20,32):
            for x in range(16,40):
                if mask.getpixel((x,y))[3]:
                    d.point((x,y),fill=P['dark'] if y%3==0 else P['shadow'])
        im.putalpha(mask.getchannel('A'))
    return im


layers = {1: armour(1), 2: armour(2)}
for n, atlas in layers.items():
    atlas.save(OUT/f'distant_layer_{n}.png')

# Four coherent 16px item icons, with vanilla silhouettes.
for kind in ('helmet','chestplate','leggings','boots'):
    original=vanilla('item/diamond_'+kind)
    icon=Image.new('RGBA',(16,16))
    shades=[P[k] for k in ('dark','shadow','blue','light','rim','white')]
    for y in range(16):
        for x in range(16):
            r,g,b,a=original.getpixel((x,y))
            if a:
                c=shades[min(5,int((r+g+b)/3/256*6))]
                icon.putpixel((x,y),tuple(int(c[n:n+2],16) for n in (1,3,5))+(a,))
    d=ImageDraw.Draw(icon)
    accents={'helmet':[(4,6),(11,6)],'chestplate':[(7,8),(8,8)],
             'leggings':[(5,3),(10,3)],'boots':[(4,9),(10,9)]}
    for p in accents[kind]:
        if original.getpixel(p)[3]:d.point(p,fill=P['gold_hi'])
    if kind=='helmet':
        for x in range(5,11):
            if original.getpixel((x,6))[3]:d.point((x,6),fill=P['glass'])
    icon.save(OUT/f'distant_{kind}.png')

textures={};mesh=[]


def cuboid(name, lo, hi, atlas=None, part=None, inflate=0, mirror=False):
    faces=box(tuple(v-inflate for v in lo),tuple(v+inflate for v in hi),'')
    for face,geom in zip(('north','south','west','east','up','down'),faces):
        key=name+'_'+face
        if atlas is not None:
            tile=atlas.crop(NETS[part][face])
            if mirror:tile=tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        else:
            tile=Image.new('RGBA',(8,8),'#3b454b')
        textures[key]=tile
        geom['material']=key
        geom['uv']=[[0,0],[tile.width,0],[tile.width,tile.height],[0,tile.height]]
    mesh.extend(faces)


# Minecraft humanoid proportions. No bespoke 3D attachments disguise the texture.
cuboid('under_head',(-4,24,-4),(4,32,4))
cuboid('under_body',(-4,12,-2),(4,24,2))
for side, sign in (('right',-1),('left',1)):
    arm_start=len(mesh)
    lo,hi=((-8,12,-2),(-4,24,2)) if sign<0 else ((4,12,-2),(8,24,2))
    cuboid('under_'+side+'arm',lo,hi)
    cuboid(side+'arm',lo,hi,layers[1],'arm',.7,sign>0)
    mesh[arm_start:]=move(mesh[arm_start:],turns=[('z',sign*5,(sign*4,24,0))])
    # Slightly separated neutral stance prevents the inflated boot cubes overlapping.
    lo,hi=((-4.9,0,-2),(-.9,12,2)) if sign<0 else ((.9,0,-2),(4.9,12,2))
    cuboid('under_'+side+'leg',lo,hi)
    cuboid(side+'leg',lo,hi,layers[2],'leg',.35,sign>0)
    cuboid(side+'boot',lo,hi,layers[1],'leg',.75,sign>0)
cuboid('chest',(-4,12,-2),(4,24,2),layers[1],'body',.8)
cuboid('waist',(-4,12,-2),(4,24,2),layers[2],'body',.4)
cuboid('helmet',(-4,24,-4),(4,32,4),layers[1],'head',.8)

for name,yaw in (('front',0),('three_quarter',-28),('back',180)):
    pic=render(mesh,textures,size=(440,650),yaw=yaw,pitch=7,center=(0,16,0),scale=17,background='#e6e3db')
    pic=pic.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    pic.save(HERE/(name+'.png'))

sheet=Image.new('RGBA',(1320,840),'#e6e3db')
d=ImageDraw.Draw(sheet); font='/System/Library/Fonts/STHeiti Medium.ttc'
def text(x,y,t,size=20,color='#4a626c'):
    d.text((x,y),t,font=ImageFont.truetype(font,size),fill=color)
text(35,20,'远仓工程装甲 / 第一版',32,'#2c4552')
text(36,66,'蓝灰甲片 · 浅钢包边 · 黄铜扣件 · 冰蓝护目镜',20)
for i,(name,title) in enumerate((('front','正面'),('three_quarter','四分之三视角'),('back','背面'))):
    sheet.alpha_composite(Image.open(HERE/(name+'.png')),(i*440,90))
    text(i*440+175,720,title)
for i,kind in enumerate(('helmet','chestplate','leggings','boots')):
    sheet.alpha_composite(Image.open(OUT/f'distant_{kind}.png').resize((64,64),Image.Resampling.NEAREST),(470+i*100,762))
text(35,780,'独立概念稿 / 原生像素 / 项目 Python 渲染器',17)
sheet.save(HERE/'design_sheet.png')
(HERE/'README.md').write_text('''# 远仓工程装甲 V1

独立美术概念，未修改模组资源、逻辑或注册。蓝灰分片甲、浅钢包边、黄铜扣件与冰蓝护目镜。

textures/distant_layer_1.png 与 distant_layer_2.png 为 64×32 原版护甲 UV 图集，包含头盔、胸甲、护腿、靴子；四张 distant_<部位>.png 为 16×16 物品贴图。使用原版钻石护甲覆盖范围和物品轮廓，关键面单独绘制。

build_art.py 使用项目 scripts/concepts/render_scene.py 和标准人形比例做离线穿戴预览。无附加几何配件；身体下面的深灰色是展示人台，不属于护甲。护甲 UV 与镜像在游戏内仍需后续验证。本稿不是游戏截图，不包含已有护甲的隐形阶段效果。

执行 python3 docs/design/concepts/distant-armor-v1/build_art.py 可重新生成；依赖 Pillow、NumPy 和项目已有 Minecraft 资源归档。
''')
assert all(im.size==(64,32) for im in layers.values())
print(HERE/'design_sheet.png')
