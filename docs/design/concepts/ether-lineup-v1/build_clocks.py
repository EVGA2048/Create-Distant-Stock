"""Nixie clock and flap clock redesign as one wall-clock family."""
import json
import subprocess
from common import *
from PIL import Image

NEW['ether_core']=Image.open(HERE/'textures/ether_core.png').convert('RGBA'); CORE='concept:ether_core'
ANDESITE='create:block/andesite_casing'; BRASSC='create:block/brass_casing'
IRON='create:block/industrial_iron_block'; BRASSB='create:block/brass_block'
# Model x of each digit slot; the model's north face reads from high x to low x.
SLOTS=(12.5,9,4,0.5)


def framed(border,centre):
    im=tex(border).copy(); inner=tex(centre)
    for x in range(2,14):
        for y in range(2,14):im.putpixel((x,y),inner.getpixel((x,y)))
    return im


def digit_sheet(name,text,color,background=None,seam=None):
    """Four 3x5 glyphs, one per 4-texel column, optionally on cards with a flap seam."""
    im=Image.new('RGBA',(16,16))
    for i,ch in enumerate(text):
        if background:fill(im,i*4,0,i*4+2,6,background)
        glyph(im,ch,i*4,1 if background else 0,color)
        if seam:
            # The flap line only darkens the card, never the glyph strokes.
            for x in range(i*4,i*4+3):
                if im.getpixel((x,3))==rgba(background):im.putpixel((x,3),rgba(seam))
    return save(name,im)


def nixie_clock(text='1245'):
    sheet=digit_sheet('clock_nixie_digits',text,'#ffb35c')
    e=[el('backplate',(0,3,14),(16,13,16),BRASSC,faces={'north':save('clock_plate',framed(BRASSC,IRON))}),
       el('shelf',(0,3,10),(16,5,14),BRASSC),
       el('sync_pip',(7.5,11,13.5),(8.5,12,14),CORE)]
    for i,x0 in enumerate(SLOTS):
        # Front/back use texels 1..3 so the glass highlight stripe stays on the tube sides, off the digit.
        e+=[el(f'tube_{i}',(x0,5,10.5),(x0+3,11,13),save('clock_nixie_glass',nixie_glass()),
               uv={'north':[1,5,4,11],'south':[1,5,4,11]}),
            el(f'cap_{i}',(x0,11,10.5),(x0+3,11.5,13),BRASSB),
            el(f'digit_{i}',(x0,5.5,11.75),(x0+3,10.5,11.75),sheet,skip=('west','east','up','down'),
               uv={'north':[i*4,0,i*4+3,5],'south':[i*4,0,i*4+3,5]})]
    e+=[el(f'colon_{y}',(7.5,y,11.5),(8.5,y+1,12.5),CORE) for y in (6.5,8.5)]
    return e


def flap_clock(text='1245'):
    sheet=digit_sheet('clock_flap_digits',text,'#e6e2d6','#2b2b2d','#18181a')
    e=[el('housing',(0,3,13),(16,13,16),ANDESITE,faces={'north':save('clock_housing',framed(ANDESITE,'create:block/flap_display_inside'))}),
       el('sync_pip',(7.5,11.5,12.5),(8.5,12.5,13),CORE)]
    for i,x0 in enumerate(SLOTS):
        e+=[el(f'card_{i}',(x0,4.5,12),(x0+3,11.5,13),'create:block/flap_display_inside',
               faces={'north':sheet},uv={'north':[i*4,0,i*4+3,7]}),
            el(f'hinge_{i}',(x0-.25,7.75,12.25),(x0+3.25,8.25,13),IRON,skip=('north',))]
    e+=[el(f'colon_{y}',(7.5,y,12.5),(8.5,y+1,13),'create:block/andesite_block') for y in (6,9)]
    return e


def old_flap():
    data=json.loads(subprocess.run(['git','show','HEAD:src/main/resources/assets/distantstock/models/block/flap_clock.json'],
                                   cwd=ROOT,capture_output=True,text=True,check=True).stdout)
    return data['elements'],data['textures']


export('nixie_clock',nixie_clock(),BRASSC); export('flap_clock',flap_clock(),ANDESITE)
LIT=('ether_core','clock_nixie_digits')
old_n,nt=existing('nixie_clock'); old_f,ft=old_flap()
r={'nixie':draw(nixie_clock(),(520,400),(8,8,12),24,yaw=-26,lit=LIT),
   'nixie_front':draw(nixie_clock(),(420,300),(8,8,12),24,yaw=0,pitch=0,lit=LIT),
   'flap':draw(flap_clock(),(520,400),(8,8,13),24,yaw=-26,lit=LIT),
   'flap_front':draw(flap_clock(),(420,300),(8,8,13),24,yaw=0,pitch=0,lit=LIT),
   'nixie_old':draw(old_n,(260,200),(8,8,12),12,yaw=-26,textures=nt),
   'flap_old':draw(old_f,(260,200),(8,8,13),12,yaw=-26,textures=ft)}
for k,im in r.items():im.save(HERE/'renders'/f'clock_{k}.png')

sheet=Image.new('RGBA',(1440,960),BG)
label(sheet,(40,24),'辉光管时钟 / 翻牌时钟 · 重设计 V1',32)
label(sheet,(42,74),'同一套壁挂外形：上下 10 格高背板、四位数字、中间两点 · 顶部以太同步灯表示时间来自远仓网络',19,MUTED)
for i,(key,title,note) in enumerate((('nixie','辉光管时钟','黄铜框 + 工业铁背板 · 黄铜托架上四支玻璃管 · 以太冒号'),
                                     ('flap','翻牌时钟','安山机壳 · 深色翻牌 + 铁合页 · 中缝即翻页线'))):
    x=i*720
    sheet.alpha_composite(r[key],(x+100,110)); label(sheet,(x+60,520),title,24); label(sheet,(x+60,556),note,16,MUTED)
    sheet.alpha_composite(r[key+'_front'].resize((280,200)),(x+40,600)); label(sheet,(x+150,805),'正视',16)
    sheet.alpha_composite(r[key+'_old'],(x+380,600)); label(sheet,(x+480,805),'现有' if key=='nixie' else '已移除的旧版',16)
label(sheet,(40,850),'新增贴图',18)
swatches(sheet,['clock_plate','clock_nixie_glass','clock_nixie_digits','clock_housing','clock_flap_digits'],40,875,48,64)
label(sheet,(420,880),'翻牌时钟已在本轮修复中从代码移除，此处仅为外观概念；若要恢复需重新实现方块与渲染器。',16,MUTED)
label(sheet,(40,935),'Python 离线渲染，非游戏截图 · 数字为静态示意（12:45）· 概念稿，未修改正式资源',15,MUTED)
sheet.save(HERE/'clock_sheet.png'); print(HERE/'clock_sheet.png')
