"""Condition controller: native Create redstone-link geometry, local texture edits."""
from pathlib import Path
import copy
import colorsys
import json
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model, render

REF = CreateReferences()
BG = '#e8ece8'
TEX = HERE / 'textures'
TEX.mkdir(exist_ok=True)
SOURCE = HERE / 'reference'
SOURCE.mkdir(exist_ok=True)
textures = {}


def store(name, im):
    textures['condition_study:' + name] = im
    im.save(TEX / (name + '.png'))
    return 'condition_study:' + name


def patch(name, im):
    atlas = Image.new('RGBA', (16,16))
    atlas.paste(im, (0,0))
    return store(name, atlas)


def label(im, xy, text, size=20, color='#34413d'):
    ImageDraw.Draw(im).text(xy,text,fill=color,
        font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size))


def load_tex(name):
    return textures[name] if name.startswith('condition_study:') else REF.texture(name)


def make():
    original = REF.model('create:block/redstone_link/transmitter')
    (SOURCE / 'create-redstone-link-transmitter.json').write_text(json.dumps(original,indent=2)+'\n')
    bridge = REF.texture('create:block/redstone_bridge')
    antenna = REF.texture('create:block/redstone_antenna')
    packager = REF.texture('create:block/packager_horizontal_powered')
    bridge.save(SOURCE/'redstone_bridge.png')
    antenna.save(SOURCE/'redstone_antenna.png')
    packager.save(SOURCE/'packager_horizontal_powered.png')
    store('redstone_bridge',bridge)
    store('redstone_antenna',antenna)

    # The native full-width packager light strip is 6x1 pixels. Take its
    # central four original pixels, without resizing, for this smaller base.
    strip = packager.crop((5,12,9,13))
    strip.save(SOURCE/'packager-strip-crop.png')
    # Use native shape and contrast; assign three distinct signal hues.
    palette = {}
    for name,hue in [('red',0.0),('yellow',0.12),('green',0.34)]:
        colors=[]
        for r,g,b,a in strip.getdata():
            _,s,v=colorsys.rgb_to_hsv(r/255,g/255,b/255)
            r,g,b=colorsys.hsv_to_rgb(hue,max(.70,s*.86),max(.58,v))
            colors.append((round(r*255),round(g*255),round(b*255),255))
        palette[name]=colors

    # Preserve the exact 12x3 side texel field and the original relief.
    base_side = bridge.crop((0,26,12,29))
    for name in ('red','yellow','green','plain'):
        side=base_side.copy()
        if name!='plain':
            for i,c in enumerate(palette[name]):
                side.putpixel((4+i,1),c)
        patch('side_'+name,side)

    top=bridge.crop((0,14,12,26)).copy()
    # Bake the model's existing UV rotation into the local patch, then keep
    # a direct axis-aligned UV so the three rim marks correspond to sides.
    top=top.transpose(Image.Transpose.ROTATE_90)
    # Neutralize the original broad red decoration inside the wooden panel;
    # the compact side markers are the controller's new color cues.
    source_top=top.copy()
    for y in range(12):
        for x in range(12):
            r,g,b,a=source_top.getpixel((x,y))
            if r>g*1.8 and r>b*1.8:
                top.putpixel((x,y),(90,68,38,a))
    # On an up face, U is world X and V is world Z.
    for i in range(4):
        top.putpixel((4+i,0),palette['red'][i])
        top.putpixel((11,4+i),palette['yellow'][i])
        top.putpixel((4+i,11),palette['green'][i])
    patch('controller_top',top)

    model=copy.deepcopy(original)
    model['credit']='Create redstone-link transmitter geometry; Distant Stock condition-controller texture study'
    model['textures']={k:'condition_study:'+v.split('/')[-1] for k,v in original['textures'].items()}
    body=model['elements'][0]
    for side,color in [('north','red'),('east','yellow'),('south','green'),('west','plain')]:
        body['faces'][side]={'uv':[0,0,12,3],'texture':'condition_study:side_'+color}
    body['faces']['up']={'uv':[0,0,12,12],'texture':'condition_study:controller_top'}
    model['render_type']='minecraft:cutout'
    (HERE/'controller-model.json').write_text(json.dumps(model,indent=2)+'\n')

    # Geometry and antenna are an exact copy of the native transmitter.
    assert len(model['elements'])==len(original['elements'])
    for a,b in zip(model['elements'],original['elements']):
        assert a['from']==b['from'] and a['to']==b['to']
    for name in ('red','yellow','green'):
        edited=np.asarray(textures['condition_study:side_'+name])[:3,:12]
        delta=np.any(edited!=np.asarray(base_side),axis=2)
        assert int(delta.sum())==4
        assert np.array_equal(np.argwhere(delta),np.array([[1,4],[1,5],[1,6],[1,7]]))
    mesh,mats=load_minecraft_model(model,load_tex,REF.model)
    for f in mesh:
        points=np.asarray(f['points'])
        texels=np.asarray(f['uv'])*np.array(mats[f['material']].size)/16
        assert np.allclose(np.linalg.norm(points-np.roll(points,-1,axis=0),axis=1),
                           np.linalg.norm(texels-np.roll(texels,-1,axis=0),axis=1)), f
    return original,model


def draw(data,size,scale,yaw=32,pitch=28,center=(7,4,8)):
    mesh,mats=load_minecraft_model(data,load_tex,REF.model)
    return render(mesh,mats,size,yaw,pitch,center,scale,BG)


def main():
    original,model=make()
    sheet=Image.new('RGBA',(1500,1100),BG)
    label(sheet,(36,24),'机械动力：远仓 / 工况控制器',32)
    label(sheet,(38,74),'原版无线红石体量与天线 · 三侧 4 × 1 像素输入短条 · Python 模型预览',19,'#6b7870')
    ImageDraw.Draw(sheet).line((36,115,1464,115),fill='#b9c4bb')
    sheet.alpha_composite(draw(model,(710,640),37), (20,140))
    label(sheet,(80,795),'主视角 / 红、黄输入侧',24)
    label(sheet,(80,838),'侧边仅四个彩色像素，不铺满整条边',19,'#6b7870')
    label(sheet,(80,872),'12 × 12 × 3 底盘；保留原版天线和顶面小件',18,'#6b7870')

    sheet.alpha_composite(draw(model,(620,430),25,212,32),(805,150))
    label(sheet,(901,582),'背面 / 绿色短条与第四侧',22)
    sheet.alpha_composite(draw(model,(350,350),22,0,90,center=(7,3,8)),(780,645))
    label(sheet,(858,1010),'俯视 / 三侧位置',20)
    sheet.alpha_composite(draw(original,(350,350),20,32,28),(1130,645))
    label(sheet,(1152,1010),'Create 原版对照',20)
    label(sheet,(38,1064),'第四侧保留原材质；功能映射未在本次美术中定义。实际模型与纹理预览，未接入游戏。',16,'#6b7870')
    sheet.convert('RGB').save(HERE/'controller-design-sheet.png')
    draw(model,(820,680),42).convert('RGB').save(HERE/'controller-front.png')
    draw(model,(820,680),42,212,32).convert('RGB').save(HERE/'controller-back.png')
    # Flatten each side at its exact original density for implementation review.
    sides=Image.new('RGBA',(960,280),BG)
    for i,name in enumerate(('red','yellow','green','plain')):
        im=textures['condition_study:side_'+name].crop((0,0,12,3))
        sides.alpha_composite(im.resize((216,54),Image.Resampling.NEAREST),(i*240+12,80))
        label(sides,(i*240+45,155),{'red':'红色输入','yellow':'黄色输入','green':'绿色输入','plain':'第四侧'}[name],20)
    label(sides,(24,225),'每侧宽12像素，颜色标记占4像素；仅中间一行着色。',18,'#6b7870')
    sides.convert('RGB').save(HERE/'side-strip-details.png')
    print('Verified: native geometry unchanged; 1 texel/model unit on every face; each marked side changes 4 of 36 texels.')
    print(HERE/'controller-design-sheet.png')


if __name__=='__main__':
    main()
