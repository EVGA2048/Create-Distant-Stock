"""Logger console art study. Native 1 texel/unit assets; no production edits."""
from pathlib import Path
import json
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts/concepts'))
from render_scene import load_minecraft_model, render

TEX = HERE / 'textures'
TEX.mkdir(exist_ok=True)
BG = '#e9e8df'
INK = '#303d39'
MUTED = '#6a756b'
textures = {}


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
    for name, source in [('side', 'monitor_side'), ('back', 'andesite_block_white_blue')]:
        save(name, Image.open(ROOT / f'src/main/resources/assets/distantstock/textures/block/{source}.png'))
    patch('face', (16,16), '#686e66', [
        ((0,0,15,0),'#a9b3a5'), ((0,1,0,14),'#879387'),
        ((15,1,15,15),'#3d4844'), ((1,15,14,15),'#38443f'),
        ((1,1,14,14),'#73796c'), ((2,1,11,8),'#505b55'),
        ((1,9,11,9),'#b49455'), ((2,10,10,14),'#59635d'),
        ((0,0,0,0),'#515e57'), ((15,0,15,0),'#515e57'),
        ((0,15,0,15),'#273732'), ((15,15,15,15),'#273732'),
    ])
    patch('brass', (16,16), '#997846', [((0,0,15,0),'#dfbd75'), ((0,1,15,1),'#b89557')])
    patch('iron', (16,16), '#626f68', [((0,0,15,0),'#abb9a8'),((0,1,15,1),'#899781')])
    patch('dark', (16,16), '#293b35')
    patch('tube_side', (16,16), '#40504b', [((0,0,15,0),'#90a69a'),((0,1,0,15),'#697f74')])
    # A custom tiny tube: five texels wide, seven high, plus a brass foot.
    glyphs = ['111101101101111','010110010010111','111001111100111',
              '111001111001111','101101111001001','111100111001111',
              '111100111101111','111001001001001','111101111101111','111101111001111']
    for digit, glyph in enumerate(glyphs):
        for mode in ('lit','off'):
            im = Image.new('RGBA',(16,16))
            d = ImageDraw.Draw(im)
            d.rectangle((0,0,4,6),fill='#31453e')
            d.rectangle((1,0,3,0),fill='#809487')
            d.rectangle((0,1,0,5),fill='#66867a')
            d.rectangle((4,1,4,5),fill='#475e51')
            d.rectangle((1,6,3,6),fill='#647968')
            for i, on in enumerate(glyph):
                if on=='1':
                    d.point((1+i%3,1+i//3),fill=('#ffb04f' if i<9 else '#dd7836') if mode=='lit' else '#765c3e')
            save(f'digit_{digit}_{mode}',im)
    for name, colors in {
        'red_on':['#ffb075','#e75f3e','#bd4131','#8d342c'],
        'red_off':['#936751','#794b3d','#623c32','#52372c'],
        'amber_on':['#fff0a0','#e4b54b','#bf8f36','#8a6b30'],
        'amber_off':['#8d8151','#73633c','#5b512f','#4a442c'],
        'green_on':['#ccdfab','#92b780','#6d9569','#4d6c53'],
        'green_off':['#7c8871','#66745e','#51624e','#465346'],
    }.items():
        im=Image.new('RGBA',(16,16))
        for i,c in enumerate(colors): im.putpixel((i%2,i//2),tuple(bytes.fromhex(c[1:]))+(255,))
        save(name,im)
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
    e[0]['faces']['south']['texture']='logger_study:back'
    for i,x in enumerate((1,7)):
        e.append(element(f'tube_{i}_socket',(x,8,x+5,9),10,13,'brass'))
        e.append(element(f'tube_{i}_glass',(x,1,x+5,8),10,13,'tube_side',f'digit_{digit[i]}_'+('off' if state=='offline' else 'lit')))
        # Tube shoulders are geometry, rather than extra high resolution outlines.
        e.append(element(f'tube_{i}_cap',(x+1,0,x+4,1),11,13,'iron'))
    for i,(color,y) in enumerate([('red',1),('amber',5),('green',9)][:lamps]):
        active=(state=='alarm' and color=='red') or (state=='fault' and color=='amber') or (state!='offline' and color=='green')
        if lamps==2: active=(state=='alarm' and i==0) or (state!='offline' and i==1); color=('red','green')[i]
        e.append(element(color+'_socket',(13,y+2,15,y+3),11,13,'brass'))
        e.append(element(color+'_bulb',(13,y,15,y+2),10,13,color+('_on' if active else '_off')))
    e.append(element('printer_housing',(1,10,11,13),10,13,'iron','printer'))
    if state=='printed':
        e.append(element('receipt_feed_fold',(3,12,9,12),9,10,'receipt'))
        e.append(element('printed_receipt',(3,12,9,16),9,9,'receipt','receipt'))
    e.append(element('buzzer_grille',(12,12,15,16),12,13,'dark','buzzer'))
    return {'parent':'minecraft:block/block','render_type':'minecraft:cutout',
            'credit':'Distant Stock / logger console V1 / art study; custom native-density miniature tubes',
            'textures':{'particle':'logger_study:back'},'elements':e}


def mesh(data):
    return load_minecraft_model(data,lambda name:textures[name])


def draw(data,size,scale,yaw=0,pitch=0):
    faces,mats=mesh(data)
    # Match Minecraft north UV orientation in this legacy renderer's camera.
    return render(faces,mats,size,-yaw,pitch,(8,8,13),scale,BG).transpose(Image.Transpose.FLIP_LEFT_RIGHT)


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
        for el in data['elements']:
            assert all(0<=v<=16 for k in ('from','to') for v in el[k])
    draw(models['alarm'],(720,720),33,28,17).save(HERE/'logger_three_quarter.png')
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
    label(sheet,(48,85),'DISTANT STOCK   /   FIRE CONTROL PANEL STUDY   /   01',17,MUTED)
    d.line((46,121,1554,121),fill='#b1b6a7')
    sheet.alpha_composite(draw(models['alarm'],(770,620),32,28,17),(20,140))
    label(sheet,(60,748),'铜底座 · 深色玻璃 · 三色小灯 · 下置打印口',21)
    label(sheet,(60,784),'沿用监视器 16×16 面板，板厚 3，元件前凸 3。',18,MUTED)
    sheet.alpha_composite(draw(models['alarm'],(420,420),25),(785,165))
    label(sheet,(817,139),'正视 / 16×16 模型像素',22)
    for y,title,desc in [
        (190,'01  双位辉光管','含管帽 5×9 / 数字 3×5'),
        (280,'02  三颗信号灯','每颗 2×2 / 红、黄、绿'),
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
    print(f'Generated {len(models)} models, {len(textures)} native 16x16 textures; bounds and UV density passed.')


if __name__=='__main__':
    main()
