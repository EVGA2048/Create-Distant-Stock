"""Distant request terminal and logger console redesign."""
from common import *
from PIL import Image

for name in ('ether_glass','ether_core'):NEW[name]=Image.open(HERE/'textures'/(name+'.png')).convert('RGBA')
GLASS,CORE='concept:ether_glass','concept:ether_core'
ANDESITE='create:block/andesite_casing'; BRASSC='create:block/brass_casing'
IRON='create:block/industrial_iron_block'; BRASSB='create:block/brass_block'
def screen():
    # Up face of a 7x8 box: tile pixels x2..8, y2..9. Stock list rows, ether-blue on dark glass.
    im=Image.new('RGBA',(16,16),rgba('#1c2226'))
    fill(im,2,2,8,9,'#22292e')
    for y,length in ((3,5),(5,4),(7,5)):
        fill(im,3,y,3,y,GLOW['white']); fill(im,4,y,3+length,y,GLOW['edge'])
    fill(im,3,9,7,9,'#3a4a52')
    return im


def pedestal_front():
    im=tex(IRON).copy()
    # Auto UV of x4..12, y2..11 is pixels 4..11 / 5..13: a narrow ether gauge window.
    fill(im,7,6,8,12,'#353438'); fill(im,7,9,8,12,GLOW['mid']); fill(im,7,9,7,9,GLOW['white'])
    return im


def hood_front():
    im=tex(BRASSC).copy()
    for x,color in ((4,'#5bd36a'),(7,GLOW['light']),(10,'#3a3a3a')):fill(im,x,1,x+1,1,color)
    return im


def terminal():
    e=[el('foot',(1,0,1),(15,2,15),ANDESITE),
       el('pedestal',(4,2,5),(12,11,11),IRON,faces={'north':save('pedestal_front',pedestal_front())}),
       el('desk',(0,11,0),(16,13,16),ANDESITE),
       el('screen',(2,13,2),(9,13.5,10),IRON,faces={'up':save('terminal_screen',screen())}),
       el('hood',(0,13,11),(16,16,16),ANDESITE,faces={'north':save('hood_front',hood_front())},
          uv={'north':[0,0,16,3]})]
    e+=[el(f'key_{z}',(11,13,z),(13,13.5,z+2),BRASSB) for z in (2,5,8)]
    gem={'origin':[13,24,13],'axis':'y','angle':45}
    e+=[el('mast',(12.5,16,12.5),(13.5,22,13.5),BRASSB),
        el('gem',(12,22,12),(14,26,14),GLASS,rotation=gem),
        el('gem_core',(12.5,22.5,12.5),(13.5,25.5,13.5),CORE,rotation=gem)]
    return e


def logger_face():
    im=tex(ANDESITE).copy(); iron=tex(IRON)
    for x in range(2,14):
        for y in range(2,14):im.putpixel((x,y),iron.getpixel((x,y)))
    return im


def printer_front():
    im=tex(ANDESITE).copy(); fill(im,3,12,11,12,'#1c1f1e'); fill(im,3,11,11,11,'#828784')
    return im


def receipt():
    im=Image.new('RGBA',(16,16),rgba('#ece6d4'))
    for y,length in ((0,5),(1,3),(2,6)):fill(im,1,y,length,y,'#9a9384')
    return im


def logger(digits='27'):
    e=[el('backplate',(0,0,13),(16,16,16),ANDESITE,faces={'north':save('logger_face',logger_face())}),
       el('link_frame',(4,13,12),(12,15,13),BRASSC),
       el('link_window',(5,13.5,11.5),(11,14.5,12),CORE),
       el('printer',(4,2,10),(14,5,13),ANDESITE,faces={'north':save('printer_front',printer_front())}),
       el('receipt',(5,0,9.95),(12,3,9.95),save('receipt',receipt()),skip=('west','east','up','down'),
          uv={'north':[0,0,7,3],'south':[0,0,7,3]})]
    NEW['nixie_digit']=Image.new('RGBA',(16,16))
    for i,(x0,ch) in enumerate(zip((9.5,4.5),digits)):
        glyph(NEW['nixie_digit'],ch,i*4,0,'#ffb35c')
        e+=[el(f'tube_socket_{i}',(x0,6,9),(x0+4,8,13),BRASSC),
            el(f'tube_{i}',(x0,8,9),(x0+4,13,13),save('nixie_glass',nixie_glass())),
            el(f'digit_{i}',(x0+.5,8,11),(x0+3.5,13,11),'concept:nixie_digit',skip=('west','east','up','down'),
               uv={'north':[i*4,0,i*4+3,5],'south':[i*4,0,i*4+3,5]})]
    save('nixie_digit',NEW['nixie_digit'])
    for y,state in ((11,'red_off'),(7.5,'amber_off'),(4,'green_on')):
        e+=[el('lamp_bezel',(1,y,12),(3,y+2,13),BRASSC),
            el('lamp',(1.25,y+.25,10.5),(2.75,y+1.75,12),f'distantstock:block/logger/{state}')]
    return e


export('request_terminal',terminal(),ANDESITE); export('logger',logger(),ANDESITE)
LIT=('ether_core','terminal_screen','nixie_digit','_on')
old_t,tt=existing('gauge'); old_l,lt=existing('logger_idle')
r={'terminal':draw(terminal(),(460,560),(8,11,8),16,lit=LIT),
   'terminal_old':draw(old_t,(260,320),(8,11,8),9,textures=tt),
   'logger':draw(logger(),(460,460),(8,8,10),20,yaw=-28,lit=LIT),
   'logger_old':draw(old_l,(260,260),(8,8,10),11,yaw=-28,lit=('_on',),textures=lt)}
for k,im in r.items():im.save(HERE/'renders'/f'{k}.png')

sheet=Image.new('RGBA',(1440,900),BG)
label(sheet,(40,24),'远仓请求台 / 远仓日志台 · 重设计 V1',32)
label(sheet,(42,74),'安山机壳 + 工业铁 + 黄铜 · 以太石英只出现在信号位置：请求台天线晶体、日志台链路窗',19,MUTED)
sheet.alpha_composite(r['terminal'],(20,110)); sheet.alpha_composite(r['terminal_old'],(470,330))
label(sheet,(60,675),'远仓请求台：安山底座、工业铁立柱（含以太液位窗）、深色库存屏、黄铜按键、天线晶体',16)
label(sheet,(560,655),'现有',17)
sheet.alpha_composite(r['logger'],(740,140)); sheet.alpha_composite(r['logger_old'],(1170,330))
label(sheet,(760,675),'远仓日志台：工业铁面板、两支辉光管、三颗工况灯、打印口与小票、以太链路窗',16)
label(sheet,(1270,595),'现有',17)
label(sheet,(40,730),'新增贴图',18)
swatches(sheet,['terminal_screen','pedestal_front','hood_front','logger_face','printer_front','receipt','nixie_glass','nixie_digit'],40,760)
label(sheet,(40,860),'Python 离线渲染，非游戏截图 · 概念稿，未修改正式资源 · 指示灯沿用现有日志台贴图',16,MUTED)
sheet.save(HERE/'console_sheet.png'); print(HERE/'console_sheet.png')
