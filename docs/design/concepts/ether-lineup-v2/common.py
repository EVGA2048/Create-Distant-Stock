"""Shared loaders and Minecraft-element helpers for the ether lineup V2 concepts."""
from pathlib import Path
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
for sub in ('textures','models','renders'):(HERE/sub).mkdir(exist_ok=True)
BG='#e9e8df'; INK='#35413b'; MUTED='#717a6a'
FONT='/System/Library/Fonts/STHeiti Medium.ttc'
NEW={}
# Ether quartz item ramp and the tower crystal ramp: the mod's existing ether colours.
ETHER=dict(outline='#293f53',deep='#35536e',dark='#507b9b',mid='#699dbc',light='#8dbbd6',
           pale='#b6d8e8',mist='#d9ebee',white='#f3f6e9')
GLOW=dict(edge='#74b8dc',mid='#9dcfe5',light='#c3eaf6',white='#ebfdff')
BRASS=dict(dark='#724731',shade='#9e6947',base='#d7aa5e',light='#f7cb6c',glint='#ffeb8c')


def tex(name):
    ns,path=name.split(':',1) if ':' in name else ('minecraft',name)
    if ns=='concept':return NEW[path]
    if ns=='distantstock':return Image.open(ASSETS/'textures'/(path+'.png')).convert('RGBA')
    if ns=='create':return REF.texture(name)
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read(f'assets/minecraft/textures/{path}.png'))).convert('RGBA')


def rgba(color,alpha=255):
    return ImageColor.getrgb(color)[:3]+(alpha,)


def fill(im,x0,y0,x1,y1,color,alpha=255):
    """Inclusive pixel rectangle."""
    for x in range(x0,x1+1):
        for y in range(y0,y1+1):im.putpixel((x,y),rgba(color,alpha))


def save(name,im):
    assert im.size==(16,16),name
    NEW[name]=im; im.save(HERE/'textures'/(name+'.png'))
    return 'concept:'+name


def auto_uv(lo,hi,side):
    """Vanilla's default face UV, so every face keeps one texel per model unit."""
    (x0,y0,z0),(x1,y1,z1)=lo,hi
    uv=_auto_uv(x0,y0,z0,x1,y1,z1,side)
    # Parts outside the block (masts, beams) wrap back onto the 16x16 texture.
    for i in (0,1):
        while min(uv[i],uv[i+2])<0:uv[i]+=16;uv[i+2]+=16
        while max(uv[i],uv[i+2])>16:uv[i]-=16;uv[i+2]-=16
        # A span straddling a texture edge slides inside it instead, keeping its width.
        if min(uv[i],uv[i+2])<0:d=-min(uv[i],uv[i+2]);uv[i]+=d;uv[i+2]+=d
    return uv


def _auto_uv(x0,y0,z0,x1,y1,z1,side):
    return {'north':[16-x1,16-y1,16-x0,16-y0],'south':[x0,16-y1,x1,16-y0],
            'west':[z0,16-y1,z1,16-y0],'east':[16-z1,16-y1,16-z0,16-y0],
            'up':[x0,z0,x1,z1],'down':[x0,16-z1,x1,16-z0]}[side]


def el(name,lo,hi,texture,faces=None,uv=None,skip=(),rotation=None):
    faces=faces or {}; uv=uv or {}
    out={'name':name,'from':list(lo),'to':list(hi),'faces':{}}
    for side in ('north','south','west','east','up','down'):
        if side in skip:continue
        out['faces'][side]={'texture':faces.get(side,texture),'uv':uv.get(side,auto_uv(lo,hi,side))}
    if rotation:out['rotation']=rotation
    return out


def shift(elements,dx=0,dy=0,dz=0):
    moved=[]
    for e in elements:
        e=json.loads(json.dumps(e))
        e['from']=[a+b for a,b in zip(e['from'],(dx,dy,dz))]; e['to']=[a+b for a,b in zip(e['to'],(dx,dy,dz))]
        if 'rotation' in e:e['rotation']['origin']=[a+b for a,b in zip(e['rotation']['origin'],(dx,dy,dz))]
        moved.append(e)
    return moved


def check(elements):
    """Minecraft limits: coordinates within -16..32, UVs within the 16x16 texture.
    Only the new parts carry a name; the parts copied from shipped models are left as they are."""
    for e in elements:
        if 'name' not in e:continue
        assert all(-16<=v<=32 for v in e['from']+e['to']),e['name']
        for f in e['faces'].values():
            assert all(0<=v<=16 for v in f['uv']),(e['name'],e['from'],e['to'],f['uv'])
        if 'rotation' in e:assert e['rotation']['angle'] in (-45,-22.5,0,22.5,45),e['name']


def export(name,elements,particle):
    check(elements)
    data={'parent':'minecraft:block/block','credit':'Distant Stock concept; concept:* are files in ./textures',
          'textures':{'particle':particle},'elements':elements}
    (HERE/'models'/(name+'.json')).write_text(json.dumps(data,indent=1)+'\n')


def existing(path):
    data=json.loads((ASSETS/'models/block'/(path+'.json')).read_text())
    if 'children' in data:
        textures=dict(data.get('textures',{}))
        for c in data['children'].values():textures.update(c.get('textures',{}))
        return [e for c in data['children'].values() for e in c.get('elements',[])],textures
    return data.get('elements',[]),data.get('textures',{})


def draw(elements,size,center,scale,yaw=30,pitch=22,lit=(),textures=None):
    faces,mats=load_minecraft_model({'textures':textures or {},'elements':elements},tex)
    for f in faces:
        if any(key in f['material'] for key in lit):f['emissive']=True
    return render(faces,mats,size,-yaw,pitch,center,scale,BG).transpose(Image.Transpose.FLIP_LEFT_RIGHT)


def label(im,xy,text,size=20,color=INK):
    ImageDraw.Draw(im).text(xy,text,font=ImageFont.truetype(FONT,size),fill=color)


def swatches(sheet,names,x,y,size=48,gap=56):
    for i,name in enumerate(names):
        sheet.alpha_composite(NEW[name].resize((size,size),Image.Resampling.NEAREST),(x+i*gap,y))


DIGITS={'0':'111101101101111','1':'010110010010111','2':'111001111100111','3':'111001111001111',
        '4':'101101111001001','5':'111100111001111','6':'111100111101111','7':'111001001001001',
        '8':'111101111101111','9':'111101111001111'}


def glyph(im,ch,x,y,color,dim=None):
    for i,bit in enumerate(DIGITS[ch]):
        if bit=='1':im.putpixel((x+i%3,y+i//3),rgba(color))
        elif dim:im.putpixel((x+i%3,y+i//3),rgba(dim))


def nixie_glass():
    im=Image.new('RGBA',(16,16))
    for x in range(16):
        for y in range(16):im.putpixel((x,y),rgba('#ffd4c7' if x%4==0 else '#ffb69c',120 if x%4==0 else 70))
    return im
