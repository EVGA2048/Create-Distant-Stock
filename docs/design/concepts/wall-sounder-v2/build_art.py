"""Andesite wall sounder: real box geometry and Create translucent lamp assets."""
from pathlib import Path
import colorsys
import json
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model,render
REF=CreateReferences()
TEX=HERE/'textures'; SOURCE=HERE/'reference'
for p in (TEX,SOURCE):p.mkdir(parents=True,exist_ok=True)
textures={}; BG='#e9e8df'


def save(name,im):
    textures['sounder_study:'+name]=im.convert('RGBA')
    im.save(TEX/(name+'.png'))


def patch(name,im):
    tile=Image.new('RGBA',(16,16));tile.paste(im,(0,0));save(name,tile)


def frame(im,w,h):
    xs=list(range((w+1)//2))+list(range(16-w//2,16))
    ys=list(range((h+1)//2))+list(range(16-h//2,16))
    return Image.fromarray(np.asarray(im)[np.ix_(ys,xs)])


def assets():
    stone=REF.texture('create:block/andesite_block')
    iron=REF.texture('create:block/industrial_iron_block')
    detail=REF.texture('create:block/link_details')
    for name,im in [('andesite',stone),('iron',iron)]:save(name,im)
    for name in ('stock_link/block_vertical','display_link/block','factory_gauge/bulb_red','factory_gauge/panel_with_bulb'):
        (SOURCE/(name.replace('/','_')+'.json')).write_text(json.dumps(REF.model('create:block/'+name),indent=2)+'\n')
    detail.save(SOURCE/'link_details.png')
    REF.texture('create:block/factory_panel').save(SOURCE/'factory_panel.png')
    patch('mount_front',frame(stone,8,12))
    patch('housing_top',frame(stone,8,4))
    patch('housing_side',frame(stone,4,12))
    head=frame(stone,8,5)
    ImageDraw.Draw(head).rectangle((1,0,6,4),fill='#303934')
    patch('speaker_well',head)
    patch('rail_front',stone.crop((3,1,9,2)))
    patch('rail_top',stone.crop((3,2,9,3)))
    patch('socket_front',frame(stone,6,2))
    patch('socket_top',frame(stone,6,6))
    # Exact stock-link 5x5 texture islands: retain texel count and alpha.
    islands={'side':(27,5,32,10),'top':(27,0,32,5),'bottom':(27,10,32,15)}
    for color,hue in [('red',.008),('orange',.075)]:
        for state in ('off','on'):
            for face,rect in islands.items():
                im=detail.crop(rect).copy()
                for y in range(5):
                    for x in range(5):
                        r,g,b,a=im.getpixel((x,y))
                        if a:
                            _,s,v=colorsys.rgb_to_hsv(r/255,g/255,b/255)
                            if state=='on':v=min(1,v*1.75+.06)
                            rgb=tuple(round(c*255) for c in colorsys.hsv_to_rgb(hue,s,v))
                            im.putpixel((x,y),rgb+(a,))
                patch(f'{color}_{state}_{face}',im)
    core=detail.crop((23,12,26,16))
    patch('filament_off',core)
    lit=core.copy()
    for y in range(4):
        for x in range(3):
            if lit.getpixel((x,y))[3]:lit.putpixel((x,y),(255,235-y*9,188-y*12,255))
    patch('filament_on',lit)


def box(name,lo,hi,material,front=None):
    w,h,d=np.asarray(hi)-lo
    faces={}
    for side,dims in [('north',(w,h)),('south',(w,h)),('west',(d,h)),('east',(d,h)),('up',(w,d)),('down',(w,d))]:
        if min(dims)<=0:continue
        faces[side]={'texture':'sounder_study:'+material,'uv':[0,0,*[float(v) for v in dims]]}
    if front:faces['north']['texture']='sounder_study:'+front
    return {'name':name,'from':lo,'to':hi,'faces':faces}


def model(color='red',state='off',wall=False):
    e=[box('wall_mount',[4,2,14],[12,14,16],'andesite','mount_front'),
       box('speaker_housing',[4,9,12],[12,14,14],'andesite','speaker_well')]
    # Adjacent boxes share a single continuous 8x4 top / 4x12 side unwrap.
    # Do not restart the border texture at the join between front and rear.
    for part,west,east,top in [(e[0],[2,0,4,12],[4,0,2,12],[0,2,8,4]),
                               (e[1],[0,0,2,5],[2,0,0,5],[0,0,8,2])]:
        for side,uv in [('west',west),('east',east)]:
            part['faces'][side]={'texture':'sounder_study:housing_side','uv':uv}
        part['faces']['up']={'texture':'sounder_study:housing_top','uv':top}
    # Three forward bars and two true recessed openings, one model unit deep.
    for y in (9,11,13):
        rail=box('grille_rail_'+str(y),[5,y,11],[11,y+1,12],'andesite','rail_front')
        rail['faces']['up']['texture']='sounder_study:rail_top'
        e.append(rail)
    e.append(box('lamp_socket_foot',[6,2,9],[10,3,13],'iron'))
    collar=box('lamp_socket_collar',[5,3,8],[11,4,14],'andesite','socket_front')
    collar['faces']['north']['uv']=[0,1,6,2]
    collar['faces']['up']['texture']='sounder_study:socket_top'
    e.append(collar)
    # Original stock-link lamp translated [-3,-2,+6], no geometric scaling.
    stock=REF.model('create:block/stock_link/block_vertical')
    src=stock['children']['bulb']['elements'][0]
    lamp=box('glass_bulb',[5.5,4,8.5],[10.5,9,13.5],f'{color}_{state}_side')
    lamp['faces']['up']['texture']=f'sounder_study:{color}_{state}_top'
    lamp['faces']['down']['texture']=f'sounder_study:{color}_{state}_bottom'
    for side in ('south','west'):lamp['faces'][side]['uv']=[5,0,0,5]
    for side in ('up','down'):lamp['faces'][side]['rotation']=180
    assert np.allclose(np.asarray(lamp['to'])-lamp['from'],np.asarray(src['to'])-src['from'])
    filament=box('filament',[6.5,4,11],[9.5,8,11],'filament_'+state)
    # Identical 3x4 native U-shaped filament, placed 2.5 units behind the glass.
    filament['faces']['south']['uv']=[3,0,0,4]
    e.append(filament)
    if wall:e.insert(0,box('context_wall',[0,0,16],[16,16,18],'iron'))
    return {'parent':'minecraft:block/block','loader':'neoforge:composite',
            'textures':{'particle':'sounder_study:andesite'},
            'credit':'Distant Stock concept; Create andesite and stock-link lamp texels, recolored glass',
            'children':{'body':{'render_type':'minecraft:cutout','elements':e},
                        'glass':{'render_type':'minecraft:translucent','elements':[lamp]}}}


def mesh(data):
    faces,mats=[],{}
    for part in data['children'].values():
        f,t=load_minecraft_model(part,lambda n:textures[n])
        for face in f:
            if '_on' in face['material']:face['emissive']=True
        faces.extend(f);mats.update(t)
    return faces,mats


def draw(data,size=(650,650),scale=40,yaw=30,pitch=18,center=(8,8,12)):
    f,t=mesh(data)
    return render(f,t,size,-yaw,pitch,center,scale,BG).transpose(Image.Transpose.FLIP_LEFT_RIGHT)


def label(im,xy,txt,size=22,color='#35413b'):
    ImageDraw.Draw(im).text(xy,txt,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=color)


def main():
    assets()
    models={}
    for color in ('red','orange'):
        for state in ('off','on'):
            m=model(color,state);models[color,state]=m
            (HERE/f'sounder_{color}_{state}.json').write_text(json.dumps(m,indent=2)+'\n')
            f,t=mesh(m)
            for face in f:
                p=np.asarray(face['points']); uv=np.asarray(face['uv'])*np.asarray(t[face['material']].size)/16
                assert np.allclose(np.linalg.norm(p-np.roll(p,-1,axis=0),axis=1),np.linalg.norm(uv-np.roll(uv,-1,axis=0),axis=1))
            for part in m['children'].values():
                for e in part['elements']:assert all(0<=v<=16 for k in ('from','to') for v in e[k])
    draw(models['red','off']).save(HERE/'sounder_three_quarter.png')
    draw(models['red','off'],yaw=-34,pitch=22).save(HERE/'sounder_opposite_side.png')
    draw(models['red','off'],yaw=0,pitch=0).save(HERE/'sounder_front.png')
    draw(model('red','off',True),scale=31,center=(8,8,14)).save(HERE/'sounder_on_wall.png')
    sheet=Image.new('RGBA',(1460,1130),BG)
    label(sheet,(40,26),'壁挂声光报警器 V2 / 安山主题',35)
    label(sheet,(42,81),'实体栅格 · 凸出式玻璃灯泡 · Create 库存链接器原尺寸灯罩与灯芯',20,'#717a6a')
    sheet.alpha_composite(draw(models['red','off'],(700,650),43),(5,125))
    label(sheet,(70,778),'8×12 壁挂机身 / 安山石 + 深灰铁座',24)
    label(sheet,(70,820),'下半部是独立玻璃壳，灯芯在壳内。',20,'#717a6a')
    for i,color in enumerate(('red','orange')):
        for j,state in enumerate(('off','on')):
            x=755+335*j;y=132+i*350
            sheet.alpha_composite(draw(models[color,state],(315,295),19),(x,y))
            label(sheet,(x+72,y+293),('红色' if color=='red' else '橙色')+(' / 熄灯' if state=='off' else ' / 短闪'),21)
    ImageDraw.Draw(sheet).line((40,878,1420,878),fill='#b8beaf')
    label(sheet,(45,908),'灯罩 5×5×5，前凸 5.5；顶部格栅前凸 1。',23)
    label(sheet,(45,950),'灯罩沿用原版 Alpha 204（约 80% 不透明），能透出 U 形灯芯；没有不透明色块内胆。',20,'#717a6a')
    label(sheet,(45,992),'闪光仍为亮 0.10 秒 / 停 1.40 秒。此轮交付模型、贴图与无声示意；音效尚未制作。',20,'#717a6a')
    label(sheet,(45,1070),'Python 离线渲染，非游戏截图 · 所有模型面均验证 1 纹素 / 模型单位 · 未接入游戏逻辑',17,'#717a6a')
    sheet.save(HERE/'sounder_design_sheet.png')
    preview=Image.new('RGBA',(1100,620),BG)
    for i,color in enumerate(('red','orange')):
        preview.alpha_composite(draw(models[color,'off'],(550,590),37),(i*550,0))
        label(preview,(160+i*550,578),'红色玻璃灯泡' if color=='red' else '橙色玻璃灯泡',23)
    preview.save(HERE/'sounder_preview.png')
    frames=[]
    for state in ('off','on'):
        im=Image.new('RGBA',(900,540),BG)
        for i,color in enumerate(('red','orange')):im.alpha_composite(draw(models[color,state],(450,540),31),(i*450,0))
        frames.append(im.convert('RGB'))
    frames[0].save(HERE/'sounder_pulse.gif',save_all=True,append_images=frames[1:],duration=[1400,100],loop=0,disposal=2)
    print('Generated 4 composite models, native-density textures and 3D previews; UV and bounds checks passed.')


if __name__=='__main__':main()
