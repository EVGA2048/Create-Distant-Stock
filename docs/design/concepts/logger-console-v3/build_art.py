"""Logger console art study. Native 1 texel/unit assets; no production edits."""
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
from render_scene import load_minecraft_model, render
from create_context import CreateReferences

TEX = HERE / 'textures'
TEX.mkdir(exist_ok=True)
BG = '#e9e8df'
INK = '#303d39'
MUTED = '#6a756b'
textures = {}
REF = CreateReferences()


def save(name, im):
    textures['logger_study:' + name] = im.convert('RGBA')
    im.save(TEX / (name + '.png'))
    return 'logger_study:' + name


def patch(name, size, color, strokes=()):
    im = Image.new('RGBA', (16, 16))
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, size[0]-1, size[1]-1), fill=color)
    for rect, fill in strokes:
        d.rectangle(rect, fill=fill)
    return save(name, im)


def assets():
    # Reference materials are copied at their original resolution, never resized.
    for name, source in [('side', 'monitor_side'), ('top', 'monitor_top'), ('back', 'andesite_block_white_blue')]:
        save(name, Image.open(ROOT / f'src/main/resources/assets/distantstock/textures/block/{source}.png'))
    patch('face', (16,16), '#686e66', [
        ((0,0,15,0),'#a9b3a5'), ((0,1,0,14),'#879387'),
        ((15,1,15,15),'#3d4844'), ((1,15,14,15),'#38443f'),
        ((1,1,14,14),'#73796c'), ((1,0,11,8),'#554a3e'),
        ((1,9,11,9),'#b49455'), ((2,10,10,14),'#59635d'),
        ((0,0,0,0),'#515e57'), ((15,0,15,0),'#515e57'),
        ((0,15,0,15),'#273732'), ((15,15,15,15),'#273732'),
    ])
    patch('brass', (16,16), '#997846', [((0,0,15,0),'#dfbd75'), ((0,1,15,1),'#b89557')])
    patch('iron', (16,16), '#626f68', [((0,0,15,0),'#abb9a8'),((0,1,15,1),'#899781')])
    patch('dark', (16,16), '#293b35')
    # Preserve the original Create glass RGBA and native pixel density.
    # Remove selected interior rows/columns; never shrink the original image.
    native=REF.texture('create:block/nixie_tube')
    refdir=HERE/'reference'
    refdir.mkdir(exist_ok=True)
    native.save(refdir/'create_nixie_tube.png')
    (refdir/'create_nixie_tube_model.json').write_text(json.dumps(REF.model('create:block/nixie_tube/block'),indent=2)+'\n')
    def select(name,xs,ys):
        tile=Image.new('RGBA',(16,16))
        for y,sy in enumerate(ys):
            for x,sx in enumerate(xs): tile.putpixel((x,y),native.getpixel((sx,sy)))
        return save(name,tile)
    select('glass_front',[0,1,2,4,5],[1,2,3,5,7,8,9])
    select('glass_side',[0,2,3,5],[1,2,3,5,7,8,9])
    select('glass_top',[6,7,8,10,11],[0,1,4,5])
    select('tube_socket_front',[0,1,2,4,5],[11,12])
    select('tube_socket_side',[0,2,3,5],[11,12])
    select('tube_socket_top',[6,7,8,10,11],[6,7,10,11])
    # Transparent glyph planes float one model pixel behind the front glass.
    glyphs = ['111101101101111','010110010010111','111001111100111',
              '111001111001111','101101111001001','111100111001111',
              '111100111101111','111001001001001','111101111101111','111101111001111']
    for digit, glyph in enumerate(glyphs):
        for mode in ('lit','off'):
            im = Image.new('RGBA',(16,16))
            d = ImageDraw.Draw(im)
            for i, on in enumerate(glyph):
                if on=='1':
                    d.point((i%3,i//3),fill=('#ffd080' if i<9 else '#ffab55') if mode=='lit' else '#7a6550')
            save(f'digit_{digit}_{mode}',im)
    # Native factory-gauge lamps: retain the exact two-texel glass patches and
    # their 201/206 alpha values, plus the original crossed internal support.
    factory=REF.texture('create:block/factory_panel')
    factory.save(refdir/'create_factory_panel.png')
    save('factory_panel',factory)
    for key in ('panel_with_bulb','bulb_light','bulb_red'):
        (refdir/f'create_factory_gauge_{key}.json').write_text(json.dumps(REF.model('create:block/factory_gauge/'+key),indent=2)+'\n')
    for color in ('red','amber','green'):
        for state in ('on','off'):
            im=Image.new('RGBA',(16,16))
            for y in range(2):
                for x in range(2):
                    pixel=factory.getpixel((x+(11 if color=='red' else 9),y+8))
                    if color=='amber':
                        _,s,v=colorsys.rgb_to_hsv(*(c/255 for c in pixel[:3]))
                        pixel=tuple(round(c*255) for c in colorsys.hsv_to_rgb(.12,s,v))+(pixel[3],)
                    im.putpixel((x,y),pixel)
            save(color+'_'+state,im)
    patch('printer', (10,3), '#718177',[
        ((0,0,9,0),'#b4bca5'),((0,1,0,2),'#8b9a8c'),
        ((9,1,9,2),'#4a5b53'),((1,1,8,1),'#24372f'),((1,2,8,2),'#b79b5d')])
    patch('receipt', (6,4), '#e5dfba',[
        ((0,0,5,0),'#b9ba9e'),((1,1,4,1),'#828b74'),
        ((1,2,2,2),'#92957e'),((4,2,4,2),'#92957e'),
        ((0,3,0,3),'#cec9a5'),((5,3,5,3),'#cec9a5')])
    patch('buzzer', (3,4), '#8c9480',[
        ((0,1,2,1),'#34443b'),((0,3,2,3),'#34443b')])


def element(name, rect, z0, z1, material, front=None):
    # Screen-space pixel rectangles -> north-facing Minecraft coordinates.
    l,t,r,b = rect
    lo,hi = [16-r,16-b,z0],[16-l,16-t,z1]
    w,h,depth = r-l,b-t,z1-z0
    faces={}
    for side, dims in [('north',(w,h)),('south',(w,h)),('west',(depth,h)),('east',(depth,h)),('up',(w,depth)),('down',(w,depth))]:
        if min(dims)<=0: continue
        faces[side]={'texture':'logger_study:'+material,'uv':[0,0,*dims]}
    if front: faces['north']['texture']='logger_study:'+front
    return {'name':name,'from':lo,'to':hi,'faces':faces}


def model(state='alarm', lamps=3):
    digit={'alarm':'03','fault':'02'}.get(state,'00')
    e=[element('wall_panel',(0,0,16,16),13,16,'side','face')]
    glass=[]
    e[0]['faces']['south']['texture']='logger_study:back'
    # The three-column side atlas is pale casing / pale casing / dark rim.
    # Orient the dark rim toward the front (z=13) on BOTH sides.
    e[0]['faces']['west']['uv']=[3,0,0,16]
    e[0]['faces']['east']['uv']=[0,0,3,16]
    # Top uses its own 16x3 strip: dark front row, then the pale casing.
    e[0]['faces']['up']={'texture':'logger_study:top','uv':[0,0,16,3]}
    e[0]['faces']['down']={'texture':'logger_study:top','uv':[0,3,16,0]}
    for i,x in enumerate((1,7)):
        socket=element(f'tube_{i}_socket',(x,7,x+5,9),9,13,'tube_socket_front')
        shell=element(f'tube_{i}_glass',(x,0,x+5,7),9,13,'glass_front')
        for side in ('east','west'):
            socket['faces'][side]['texture']='logger_study:tube_socket_side'
            shell['faces'][side]['texture']='logger_study:glass_side'
        for side in ('up','down'):
            socket['faces'][side]['texture']='logger_study:tube_socket_top'
            shell['faces'][side]['texture']='logger_study:glass_top'
        shell['faces'].pop('down')
        e.append(socket)
        num=element(f'tube_{i}_internal_digit',(x+1,1,x+4,6),10,10,f'digit_{digit[i]}_'+('off' if state=='offline' else 'lit'))
        num['shade']=False
        e.append(num)
        glass.append(shell)
    for i,(color,y) in enumerate([('red',1),('amber',5),('green',9)][:lamps]):
        active=(state=='alarm' and color=='red') or (state=='fault' and color=='amber') or (state!='offline' and color=='green')
        if lamps==2: active=(state=='alarm' and i==0) or (state!='offline' and i==1); color=('red','green')[i]
        e.append(element(color+'_socket',(13,y+2,15,y+3),10,13,'brass'))
        bulb=element(color+'_bulb',(13,y,15,y+2),10,12,color+('_on' if active else '_off'))
        # Preserve the native bulb's per-face UV rotations.
        native_bulb=REF.model('create:block/factory_gauge/bulb_light')['elements'][0]
        for side,face in native_bulb['faces'].items():
            if 'rotation' in face: bulb['faces'][side]['rotation']=face['rotation']
        glass.append(bulb)
        # Original 1x1 cap and two crossed support planes; translation only.
        for j,src in enumerate(REF.model('create:block/factory_gauge/panel_with_bulb')['elements'][3:]):
            internal=copy.deepcopy(src)
            internal['name']=color+'_bulb_internal_'+str(j)
            offset=[1,13-y,4]
            for key in ('from','to'):
                internal[key]=[v+d for v,d in zip(internal[key],offset)]
            internal['rotation']['origin']=[v+d for v,d in zip(internal['rotation']['origin'],offset)]
            for face in internal['faces'].values(): face['texture']='logger_study:factory_panel'
            e.append(internal)
    e.append(element('printer_housing',(1,10,11,13),10,13,'iron','printer'))
    if state=='printed':
        e.append(element('receipt_feed_fold',(3,12,9,12),9,10,'receipt'))
        e.append(element('printed_receipt',(3,12,9,16),9,9,'receipt','receipt'))
    e.append(element('buzzer_grille',(12,12,15,16),12,13,'dark','buzzer'))
    return {'parent':'minecraft:block/block','loader':'neoforge:composite',
            'credit':'Distant Stock / logger console V3 / corrected panel UVs and native Create factory-gauge translucent bulbs',
            'textures':{'particle':'logger_study:back'},'children':{
                'body':{'render_type':'minecraft:cutout','elements':e},
                'glass':{'render_type':'minecraft:translucent','elements':glass}}}


def mesh(data):
    faces,mats=[],{}
    for part in data.get('children',{'all':data}).values():
        fs,ts=load_minecraft_model(part,lambda name:textures[name] if name.startswith('logger_study:') else REF.texture(name))
        for f in fs:
            if ('digit_' in f['material'] and f['material'].endswith('_lit')) or f['material'].endswith('_on'): f['emissive']=True
        faces.extend(fs); mats.update(ts)
    return faces,mats


def draw(data,size,scale,yaw=0,pitch=0,center=(8,8,13)):
    faces,mats=mesh(data)
    # Match Minecraft north UV orientation in this legacy renderer's camera.
    return render(faces,mats,size,-yaw,pitch,center,scale,BG).transpose(Image.Transpose.FLIP_LEFT_RIGHT)


def label(im,xy,words,size=22,color=INK):
    ImageDraw.Draw(im).text(xy,words,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=color)


def main():
    assets()
    models={s:model(s) for s in ('offline','idle','alarm','fault','printed')}
    for state,data in models.items():
        (HERE/f'logger_{state}.json').write_text(json.dumps(data,indent=2)+'\n')
        faces,mats=mesh(data)
        for f in faces:
            p=np.asarray(f['points']); uv=np.asarray(f['uv'])*np.asarray(mats[f['material']].size)/16
            assert np.allclose(np.linalg.norm(p-np.roll(p,-1,axis=0),axis=1),np.linalg.norm(uv-np.roll(uv,-1,axis=0),axis=1)),f
        for el in [e for part in data['children'].values() for e in part['elements']]:
            assert all(0<=v<=16 for k in ('from','to') for v in el[k])
    draw(models['alarm'],(720,720),33,28,17).save(HERE/'logger_three_quarter.png')
    draw(models['alarm'],(720,720),29,-35,35).save(HERE/'logger_left_top.png')
    draw(models['alarm'],(720,720),29,35,35).save(HERE/'logger_right_top.png')
    draw(models['alarm'],(576,576),32).save(HERE/'logger_front.png')
    # Actual small projections; no smoothing or artificially detailed downsample.
    small=Image.new('RGBA',(660,240),BG)
    for i,px in enumerate((32,48,64)):
        im=draw(models['alarm'],(px,px),px/16)
        small.alpha_composite(im.resize((px*3,px*3),Image.Resampling.NEAREST),(i*220+10,8))
        label(small,(i*220+10,210),f'{px} px / 放大 3×',16)
    small.save(HERE/'readability.png')
    sheet=Image.new('RGBA',(1600,1200),BG)
    d=ImageDraw.Draw(sheet)
    label(sheet,(46,30),'远仓日志台',42)
    label(sheet,(48,85),'DISTANT STOCK   /   PANEL UV + FACTORY GAUGE LAMPS   /   03',17,MUTED)
    d.line((46,121,1554,121),fill='#b1b6a7')
    sheet.alpha_composite(draw(models['alarm'],(770,620),32,28,17),(20,140))
    label(sheet,(60,748),'边框 UV 校正 · 工厂仪表半透明灯罩与内部支架',21)
    label(sheet,(60,784),'辉光管保持 V2 / 红绿灯使用原版 RGBA / 黄灯同源变色。',18,MUTED)
    sheet.alpha_composite(draw(models['alarm'],(420,420),25),(785,165))
    label(sheet,(817,139),'正视 / 16×16 模型像素',22)
    for y,title,desc in [
        (190,'01  双位辉光管','玻璃 5×7 / 底座高 2'),
        (280,'02  三颗信号灯','2×2×2 / 透明罩与灯芯'),
        (370,'03  内嵌打印机','外罩 10×3 / 出纸口 8×1'),
        (460,'04  蜂鸣器','3×4 栅格 / 两道声孔'),
    ]:
        label(sheet,(1230,y),title,23)
        label(sheet,(1230,y+37),desc,18,MUTED)
    label(sheet,(813,615),'像素尺度检查',22)
    for i,px in enumerate((32,48,64)):
        im=draw(models['alarm'],(px,px),px/16)
        sheet.alpha_composite(im.resize((px*2,px*2),Image.Resampling.NEAREST),(825+i*220,661))
        label(sheet,(825+i*220,798),f'{px}px 投影 ×2',17,MUTED)
    d.line((46,846,1554,846),fill='#b1b6a7')
    for i,(s,title) in enumerate([('offline','未绑定'),('idle','正常待机'),('alarm','告警待处理'),('printed','右键打印后')]):
        x=55+i*385
        sheet.alpha_composite(draw(models[s],(220,220),12),(x,872))
        label(sheet,(x+218,930),title,22)
        label(sheet,(x+218,967),{'offline':'熄灯 / 暗管','idle':'00 / 绿灯','alarm':'03 / 红灯','printed':'00 / 小票'}[s],17,MUTED)
    label(sheet,(48,1156),'美术试稿 · 离线模型渲染，非游戏截图 · 所有贴图 16×16，1 纹素 / 模型像素 · 状态仅作外观演示',17,MUTED)
    sheet.save(HERE/'logger_design_sheet.png')
    # A deliberately minimal comparison proves what a third lamp costs.
    comp=Image.new('RGBA',(1000,640),BG)
    for i,n in enumerate((2,3)):
        label(comp,(55+i*500,25),'两灯 / 更疏' if n==2 else '三灯 / 推荐',27)
        comp.alpha_composite(draw(model('alarm',n),(460,460),25),(20+i*500,85))
        label(comp,(55+i*500,567),'告警 + 联网' if n==2 else '告警 + 故障 + 联网',21)
    comp.save(HERE/'lamp_comparison.png')
    # Installed Create single tube beside the miniature, at the same scale.
    source=REF.model('create:block/nixie_tube/block')
    native={'children':{k:{**part,'elements':part['elements'][:1]} for k,part in source['children'].items()}}
    mini={'children':{k:{**part,'elements':[e for e in part['elements'] if e['name'].startswith('tube_0_')]} for k,part in models['alarm']['children'].items()}}
    comparison=Image.new('RGBA',(1400,780),BG)
    label(comparison,(40,25),'辉光管材质校正 / 使用本机 Create 6.0.10 原始资源',29)
    comparison.alpha_composite(draw(native,(440,500),27,25,18,(8,6,12)),(0,95))
    comparison.alpha_composite(draw(mini,(440,500),27,25,18,(12.5,11.5,11)),(430,95))
    label(comparison,(52,609),'Create 原版 / 6×9 玻璃 + 3 高底座',20)
    label(comparison,(470,609),'日志台微型 / 5×7 玻璃 + 2 高底座',20)
    label(comparison,(925,148),'保留的材质特征',26)
    for y,line in enumerate(['原版橙色像素与 alpha 96','顶部、正面、侧面均为玻璃','数字独立放在玻璃内部','深棕实体底座，不加金属顶帽','删减纹素行列，维持 1:1 密度']):
        label(comparison,(925,205+y*55),line,21,MUTED)
    label(comparison,(42,707),'同模型比例离线对照。左：原版静态模型，不含方块实体数字。右：自制微型几何与数字，使用原版玻璃 RGBA。',19,MUTED)
    comparison.save(HERE/'create_nixie_comparison.png')
    details=Image.new('RGBA',(1500,940),BG)
    label(details,(38,24),'V3 修正检查 / 连续边框 + 工厂仪表灯泡',30)
    for i,(yaw,title) in enumerate([(-35,'左侧 + 顶部'),(35,'右侧 + 顶部')]):
        details.alpha_composite(draw(models['alarm'],(510,560),25,yaw,35),(10+510*i,80))
        label(details,(55+510*i,635),title,23)
    # Full assembled lamp, with native support geometry visible through glass.
    isolated={'children':{k:{**part,'elements':[el for el in part['elements'] if el['name'].startswith('green_')]} for k,part in models['alarm']['children'].items()}}
    details.alpha_composite(draw(isolated,(450,500),87,28,22,(2,5.5,11.5)),(1040,90))
    label(details,(1080,590),'2×2×2 半透明灯罩',23)
    label(details,(1080,633),'内含原版交叉支架与亮点',20,MUTED)
    for y,line in enumerate(['左右侧 UV 分别定向：深色包边均在前，浅色外壳在后。',
                              '顶部使用 monitor_top 的 16×3 区域；底部反向映射，包边连续。',
                              '灯罩保留工厂仪表 Alpha 201 / 206；红绿原色，黄灯只改色相。',
                              '离线模型检查图；保持原版像素密度，没有缩小贴图或新增高分辨率细节。']):
        label(details,(48,724+y*45),line,21,MUTED)
    details.save(HERE/'uv_and_lamp_details.png')
    print(f'Generated {len(models)} models, {len(textures)} native 16x16 textures; bounds and UV density passed.')


if __name__=='__main__':
    main()
