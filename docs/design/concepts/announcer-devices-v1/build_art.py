"""Brass announcer, network broadcaster and network speaker: texture study, renders only."""
from pathlib import Path
import colorsys
import io
import json
import sys
import zipfile
from PIL import Image, ImageColor, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model, render
REF=CreateReferences()
ASSETS=ROOT/'src/main/resources/assets/distantstock'
JAR=ROOT/'build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar'
TEX=HERE/'textures'; TEX.mkdir(parents=True,exist_ok=True)
BG='#e9e8df'
new={}


def texture(name):
    ns,path=name.split(':',1) if ':' in name else ('minecraft',name)
    if ns=='concept':return new[path]
    if ns=='distantstock':return Image.open(ASSETS/'textures'/(path+'.png')).convert('RGBA')
    if ns=='create':return REF.texture(name)
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read(f'assets/minecraft/textures/{path}.png'))).convert('RGBA')


BRASS=texture('create:block/brass_casing'); ANDESITE=texture('create:block/andesite_casing')
ROSE=texture('create:block/rose_quartz_lamp'); NOTE=texture('minecraft:block/note_block')
CASING=texture('distantstock:block/tower/casing_inactive')


def put(im,x,y,color):
    im.putpixel((x,y),ImageColor.getrgb(color)[:3]+(255,))


def save(name,im):
    assert im.size==(16,16) and im.getchannel('A').getextrema()==(255,255),name
    new[name]=im; im.save(TEX/(name+'.png'))


def glow(im):
    """Powered rose quartz: brighten only rose texels, never the casing's brown or grey."""
    out=im.copy()
    for x in range(16):
        for y in range(16):
            r,g,b,a=im.getpixel((x,y)); h,s,v=colorsys.rgb_to_hsv(r/255,g/255,b/255)
            if s>.35 and (h>.85 or h<.02):
                out.putpixel((x,y),tuple(round(c*255) for c in colorsys.hsv_to_rgb(h,s*.85,min(1,v*1.6+.1)))+(a,))
    return out


def announcer_front(on):
    # Brass casing keeps its frame and groove; a chamfered speaker grille fills the recess.
    im=BRASS.copy()
    for x in range(2,14):
        for y in range(2,14):
            dx,dy=abs(x-7.5),abs(y-7.5); m=max(dx,dy,(dx+dy)*.72)
            if 3.6<m<=4.6:c='#f7cb6c' if x+y<15 else '#9e6947'
            elif m<=1.0:c=('#9af4e5' if on else '#ffeb8c') if (x,y)==(7,7) else ('#4ec5b8' if on else '#cea05a')
            elif m<=3.6:c='#131615' if (x+y)%2==0 and m>1.5 else '#2e312f'
            else:continue
            put(im,x,y,c)
    return im


def announcer_side(on):
    # Note-block wood above a brass band keeps the block's note-block lineage.
    im=BRASS.copy()
    for x in range(3,13):
        for y in range(3,7):im.putpixel((x,y),NOTE.getpixel((x,y)))
        put(im,x,7,'#f7cb6c'); put(im,x,8,'#9e6947')
    for x in range(5,11):
        for y in (10,11):put(im,x,y,'#1c1f1e')
    for x in range(6,10):
        put(im,x,10,'#9af4e5' if on else '#374743'); put(im,x,11,'#4ec5b8' if on else '#282b29')
    return im


def broadcaster_top(on):
    im=ANDESITE.copy()
    for x in range(2,14):
        for y in range(2,14):im.putpixel((x,y),ROSE.getpixel((x,y)))
    for x in range(6,10):
        for y in range(6,10):
            if x in (6,9) or y in (6,9):put(im,x,y,'#3a4445')
    for (x,y),c in (((7,7),'#adc6a9'),((8,7),'#9aa490'),((7,8),'#9aa490'),((8,8),'#888b79')):put(im,x,y,c)
    return glow(im) if on else im


def broadcaster_side(on):
    im=ANDESITE.copy()
    for x in range(3,13):
        for y in range(3,7):im.putpixel((x,y),ROSE.getpixel((x,y)))
        put(im,x,7,'#3a4445')
    for x in range(4,12):
        for y in range(8,13):put(im,x,y,'#353b39')
    # Three rising signal bars, the broadcaster's only pictogram.
    for x,h in ((5,2),(7,3),(9,4)):
        for y in range(13-h,13):put(im,x,y,'#821e47' if y==13-h else '#641336')
    return glow(im) if on else im


def speaker_side():
    im=CASING.copy()
    for x in range(3,13):
        for y in range(3,13):put(im,x,y,'#2b3031' if y>9 else '#313637')
    for x in (4,6,9,11):
        for y in (4,6,8):put(im,x,y,'#181b1b')
    for x in range(5,11):put(im,x,11,'#e8fbff' if x==5 else '#b7e0ed')
    return im


def speaker_top():
    im=CASING.copy()
    for x in range(3,13):
        for y in range(3,13):
            r=((x-7.5)**2+(y-7.5)**2)**.5
            if r>5.1:continue
            c=(('#c5d4da' if x+y<15 else '#97a9b2') if r>4.2 else '#222627' if r>3.3 else '#313637' if r>2.2
               else '#2b3031' if r>1.2 else '#e8fbff' if (x,y)==(7,7) else '#b7e0ed')
            put(im,x,y,c)
    return im


for state in ('off','on'):
    on=state=='on'
    save(f'announcer_front_{state}',announcer_front(on)); save(f'announcer_side_{state}',announcer_side(on))
    save(f'broadcaster_top_{state}',broadcaster_top(on)); save(f'broadcaster_side_{state}',broadcaster_side(on))
save('speaker_side',speaker_side()); save('speaker_top',speaker_top())


def cube(side,top,bottom):
    """Plain full cube: the redesign changes textures only, never block shape."""
    faces={d:{'texture':'#'+('top' if d=='up' else 'bottom' if d=='down' else 'side'),'uv':[0,0,16,16]}
           for d in ('north','south','east','west','up','down')}
    return {'textures':{'side':side,'top':top,'bottom':bottom},'elements':[{'from':[0,0,0],'to':[16,16,16],'faces':faces}]}


def current(name):
    data=json.loads((ASSETS/'models/block'/(name+'.json')).read_text())
    parent=data.get('parent','')
    base=current(parent.split('/')[-1]) if parent.startswith('distantstock:') else \
        REF.model(parent) if parent=='minecraft:block/cube' else {}
    return {**base,**data,'textures':{**base.get('textures',{}),**data.get('textures',{})}}


DEVICES=[  # name, title, note, current off, current on, concept off, concept on
    ('announcer','黄铜广播器','黄铜机壳 · 八角扬声格栅 · 侧面保留音符盒木纹',
     'announcer','announcer_powered',
     cube('concept:announcer_side_off','concept:announcer_front_off','create:block/brass_casing'),
     cube('concept:announcer_side_on','concept:announcer_front_on','create:block/brass_casing')),
    ('network_broadcaster','网络广播器','安山机壳 · 玫瑰石英透镜 · 侧面三级信号格',
     'network_broadcaster','network_broadcaster_powered',
     cube('concept:broadcaster_side_off','concept:broadcaster_top_off','create:block/andesite_casing'),
     cube('concept:broadcaster_side_on','concept:broadcaster_top_on','create:block/andesite_casing')),
    ('network_speaker','网络扬声器','远仓机壳边框 · 圆形纸盆 · 以太蓝指示条',
     'network_speaker','network_speaker',
     cube('concept:speaker_side','concept:speaker_top','distantstock:block/andesite_block_white_blue'),
     cube('concept:speaker_side','concept:speaker_top','distantstock:block/andesite_block_white_blue')),
]


def draw(data,size,lit=False,yaw=30,pitch=24):
    faces,mats=load_minecraft_model(data,texture)
    for f in faces:
        if lit and (f['material'].endswith('_on') or 'powered' in f['material']):f['emissive']=True
    scale=size[0]/30
    return render(faces,mats,size,-yaw,pitch,(8,8,8),scale,BG).transpose(Image.Transpose.FLIP_LEFT_RIGHT)


def label(im,xy,txt,size=20,color='#35413b'):
    ImageDraw.Draw(im).text(xy,txt,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=color)


sheet=Image.new('RGBA',(1440,960),BG)
label(sheet,(40,24),'广播设备贴图概念 V1 / 黄铜广播器 · 网络广播器 · 网络扬声器',32)
label(sheet,(42,74),'只换贴图、不改方块形状 · 全部取材于 Create 黄铜/安山机壳、玫瑰石英灯与远仓机壳调色板',19,'#717a6a')
for i,(name,title,note,old_off,old_on,off,on) in enumerate(DEVICES):
    x=i*480
    hero=draw(off,(420,420)); hero.save(HERE/f'{name}.png')
    sheet.alpha_composite(hero,(x+30,110))
    label(sheet,(x+60,530),title,26); label(sheet,(x+60,568),note,17,'#717a6a')
    for j,(data,lit,caption) in enumerate(((current(old_off),False,'现有贴图'),(on,True,'新稿 · 激活'))):
        sheet.alpha_composite(draw(data,(200,200),lit),(x+40+j*210,600)); label(sheet,(x+105+j*210,800),caption,17)
    used=sorted({f['material'] for f in load_minecraft_model(off,texture)[0]+load_minecraft_model(on,texture)[0]
                 if f['material'].startswith('concept:')})
    for k,ref in enumerate(used):
        sheet.alpha_composite(new[ref.split(':')[1]].resize((56,56),Image.Resampling.NEAREST),(x+60+k*70,840))
ImageDraw.Draw(sheet).line((40,914,1400,914),fill='#b8beaf')
label(sheet,(42,925),'Python 离线渲染（scripts/concepts/render_scene.py），非游戏截图 · 仅概念稿，未修改正式资源与模型',16,'#717a6a')
sheet.save(HERE/'design_sheet.png')
print(HERE/'design_sheet.png')
