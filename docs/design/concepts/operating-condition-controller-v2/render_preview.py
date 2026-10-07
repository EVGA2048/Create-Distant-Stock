"""Condition controller V2: redstone-link base/antenna plus display-link circuit."""
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


def load_mesh(data):
    mesh,mats=[],{}
    for part in data.get('children',{'all':data}).values():
        faces,textures_for_part=load_minecraft_model(part,load_tex,REF.model)
        mesh.extend(faces)
        mats.update(textures_for_part)
    return mesh,mats


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
    display=REF.model('create:block/display_link/block')
    (SOURCE/'create-display-link.json').write_text(json.dumps(display,indent=2)+'\n')
    details=REF.texture('create:block/link_details')
    store('link_details',details)

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
    model['credit']='Create redstone-link base/antenna and display-link top assembly; Distant Stock concept V2'
    model['textures']={k:'condition_study:'+v.split('/')[-1] for k,v in original['textures'].items()}
    body=model['elements'][0]
    for side,color in [('north','red'),('east','yellow'),('south','green'),('west','plain')]:
        body['faces'][side]={'uv':[0,0,12,3],'texture':'condition_study:side_'+color}
    body['faces']['up']={'uv':[0,0,12,12],'texture':'condition_study:controller_top'}
    # Retain body and antenna only; both original item-frequency pads go away.
    model['elements']=model['elements'][:4]
    for a,b in zip(model['elements'],original['elements'][:4]):
        assert a['from']==b['from'] and a['to']==b['to']
    imported=[]
    source_parts=display['children']['base']['elements'][2:] + display['children']['bulb']['elements']
    names=['display_coil','display_tube_socket','display_aux_contact','display_tube_detail','display_tube_glass']
    for name,src in zip(names,source_parts):
        e=copy.deepcopy(src)
        e['name']=name
        for key in ('from','to'):
            e[key][1]-=2
        if 'rotation' in e:
            e['rotation']['origin'][1]-=2
        for f in e['faces'].values():
            f['texture']='condition_study:link_details'
        assert np.allclose(np.array(e['to'])-e['from'],np.array(src['to'])-src['from'])
        for side,face in e['faces'].items():
            assert face['uv']==src['faces'][side]['uv']
        imported.append(e)
    solid={
        'render_type':'minecraft:cutout_mipped',
        'textures':model['textures'],
        'elements':model['elements']+imported[:-1],
    }
    glass={
        'render_type':'minecraft:translucent',
        'elements':[imported[-1]],
    }
    model.pop('elements')
    model['loader']='neoforge:composite'
    model['children']={'base':solid,'tube':glass}
    (HERE/'controller-model.json').write_text(json.dumps(model,indent=2)+'\n')
    for name in ('red','yellow','green'):
        edited=np.asarray(textures['condition_study:side_'+name])[:3,:12]
        delta=np.any(edited!=np.asarray(base_side),axis=2)
        assert int(delta.sum())==4
        assert np.array_equal(np.argwhere(delta),np.array([[1,4],[1,5],[1,6],[1,7]]))
    mesh,mats=load_mesh(model)
    for f in mesh:
        points=np.asarray(f['points'])
        texels=np.asarray(f['uv'])*np.array(mats[f['material']].size)/16
        assert np.allclose(np.linalg.norm(points-np.roll(points,-1,axis=0),axis=1),
                           np.linalg.norm(texels-np.roll(texels,-1,axis=0),axis=1)), f
    return display,model


def draw(data,size,scale,yaw=32,pitch=28,center=(7,4,8)):
    mesh,mats=load_mesh(data)
    return render(mesh,mats,size,yaw,pitch,center,scale,BG)


def main():
    original,model=make()
    sheet=Image.new('RGBA',(1500,1100),BG)
    label(sheet,(36,24),'机械动力：远仓 / 工况控制器 V2',32)
    label(sheet,(38,74),'移除两个物品槽 · 移植显示链接器电路组件 · 保留天线与三色短条',19,'#6b7870')
    ImageDraw.Draw(sheet).line((36,115,1464,115),fill='#b9c4bb')
    sheet.alpha_composite(draw(model,(710,640),37), (20,140))
    label(sheet,(80,795),'主视角 / 红、黄输入侧',24)
    label(sheet,(80,838),'侧边仅四个彩色像素，不铺满整条边',19,'#6b7870')
    label(sheet,(80,872),'原版铜色线圈、接点与玻璃管；几何与纹素未缩放',18,'#6b7870')

    sheet.alpha_composite(draw(model,(620,430),25,212,32,center=(7,5.5,8)),(805,150))
    label(sheet,(901,582),'背面 / 绿色短条与第四侧',22)
    sheet.alpha_composite(draw(model,(350,350),22,0,90,center=(7,3,8)),(780,645))
    label(sheet,(858,1010),'俯视 / 三侧位置',20)
    sheet.alpha_composite(draw(original,(350,350),17,32,28,center=(8,5,8)),(1130,645))
    label(sheet,(1140,1010),'Create 显示链接器',20)
    label(sheet,(38,1064),'第四侧保留原材质；功能映射未在本次美术中定义。实际模型与纹理预览，未接入游戏。',16,'#6b7870')
    sheet.convert('RGB').save(HERE/'controller-design-sheet.png')
    draw(model,(820,680),42).convert('RGB').save(HERE/'controller-front.png')
    draw(model,(820,680),42,212,32,center=(7,5.5,8)).convert('RGB').save(HERE/'controller-back.png')
    # Flatten each side at its exact original density for implementation review.
    sides=Image.new('RGBA',(960,280),BG)
    for i,name in enumerate(('red','yellow','green','plain')):
        im=textures['condition_study:side_'+name].crop((0,0,12,3))
        sides.alpha_composite(im.resize((216,54),Image.Resampling.NEAREST),(i*240+12,80))
        label(sides,(i*240+45,155),{'red':'红色输入','yellow':'黄色输入','green':'绿色输入','plain':'第四侧'}[name],20)
    label(sides,(24,225),'每侧宽12像素，颜色标记占4像素；仅中间一行着色。',18,'#6b7870')
    sides.convert('RGB').save(HERE/'side-strip-details.png')
    print('Verified: item pads removed; native base/antenna preserved; five display-link components translated without scaling; 1 texel/unit; side markers unchanged.')
    print(HERE/'controller-design-sheet.png')


if __name__=='__main__':
    main()
