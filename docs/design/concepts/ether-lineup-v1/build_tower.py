"""Tower coupler and ether resonator redesign, stacked on the current tower core."""
from common import *
from PIL import Image


def glass():
    # Faces span u 6..10, so the four-column rhythm repeats on every glass face.
    im=Image.new('RGBA',(16,16))
    ramp=[(GLOW['light'],190),(GLOW['white'],150),(GLOW['mid'],120),(GLOW['edge'],175)]
    for x in range(16):
        color,a=ramp[(x-6)%4]
        for y in range(16):im.putpixel((x,y),rgba(color,a))
    for start in (2,10):  # two rising facet lines, the only marking on the column
        for i in range(4):im.putpixel((6+i,start+3-i),rgba(GLOW['white'],215))
    return im


def core():
    im=Image.new('RGBA',(16,16))
    for x in range(16):
        for y in range(16):im.putpixel((x,y),rgba(GLOW['white'] if x%2 else GLOW['light']))
    return im


def beam():
    im=Image.new('RGBA',(16,16))
    for x in range(16):
        for y in range(16):im.putpixel((x,y),rgba(GLOW['light'] if 7<=x<=8 else GLOW['mid'],105 if 7<=x<=8 else 60))
    return im


def deck():
    im=tex('create:block/andesite_casing').copy()
    for x in range(2,14):
        for y in range(2,14):
            r=((x-7.5)**2+(y-7.5)**2)**.5
            if 4.3<r<=5.6:im.putpixel((x,y),rgba(BRASS['light'] if x+y<15 else BRASS['shade']))
            elif r<=4.3:im.putpixel((x,y),rgba('#353b39' if r>3 else '#2a2d2c'))
    return im


GLASS=save('ether_glass',glass()); CORE=save('ether_core',core()); BEAM=save('ether_beam',beam())
DECK=save('resonator_deck',deck())
ANDESITE='create:block/andesite_casing'; BRASSC='create:block/brass_casing'


def coupler():
    """Andesite corner posts, a brass collar frame and a clamped ether column."""
    e=[el(f'post_{x}_{z}',(x,2,z),(x+3,16,z+3),ANDESITE,skip=('up','down')) for x in (0,13) for z in (0,13)]
    e+=[el('collar_n',(0,0,0),(16,2,3),BRASSC),el('collar_s',(0,0,13),(16,2,16),BRASSC),
        el('collar_w',(0,0,3),(3,2,13),BRASSC),el('collar_e',(13,0,3),(16,2,13),BRASSC),
        el('ether_column',(6,0,6),(10,16,10),GLASS,skip=('up','down')),
        el('ether_core',(7,0,7),(9,16,9),CORE,skip=('up','down')),
        el('clamp',(5,7,5),(11,9,11),BRASSC)]
    return e


def resonator(running):
    turn=22.5 if running else 0
    spin=lambda: {'origin':[8,8,8],'axis':'y','angle':turn} if turn else None
    e=[el('deck',(0,0,0),(16,3,16),ANDESITE,faces={'up':DECK}),
       el('hub',(6,3,6),(10,6,10),BRASSC),
       el('arm',(1,6,7),(15,7,9),ANDESITE,rotation=spin())]
    for x0 in (1,14):
        e.append(el('fork_bridge',(x0,6,6),(x0+1,7,10),ANDESITE,rotation=spin()))
        for z0 in (6,9):
            e.append(el('fork_prong',(x0,7,z0),(x0+1,11,z0+1),BRASSC,rotation=spin()))
            e.append(el('fork_tip',(x0,11,z0),(x0+1,12,z0+1),CORE,rotation=spin()))
    diamond={'origin':[8,8,8],'axis':'y','angle':45}
    e+=[el('emitter',(6,7,6),(10,14,10),GLASS,skip=('down',),rotation=diamond),
        el('emitter_core',(7,7,7),(9,13,9),CORE,skip=('down',),rotation=diamond),
        el('emitter_cap',(7,14,7),(9,15,9),GLASS,rotation=diamond)]
    if running:
        side=[6.5,0,9.5,16]
        e.append(el('beam',(6.5,16,6.5),(9.5,32,9.5),BEAM,skip=('up','down'),
                    uv={s:side for s in ('north','south','west','east')},rotation=diamond))
    return e


def new_tower(running):
    core_e,core_t=existing('tower/tower_core')
    return core_e+shift(coupler(),dy=16)+shift(coupler(),dy=32)+shift(resonator(running),dy=48),core_t


def old_tower():
    textures={}; out=[]
    for path,dy in (('tower/tower_core',0),('tower/tower_coupler_bottom',16),('tower/tower_coupler_top',32),
                    ('tower/ether_resonator',48),('tower/ether_resonator_rotor',48)):
        e,t=existing(path); textures.update(t); out+=shift(e,dy=dy)
    return out,textures


export('tower_coupler',coupler(),ANDESITE)
export('ether_resonator',resonator(False),ANDESITE)
export('ether_resonator_running',resonator(True),ANDESITE)
LIT=('ether_core','ether_beam')
renders={}
for key,(elements,textures) in {'old':old_tower(),'idle':new_tower(False),'running':new_tower(True)}.items():
    renders[key]=draw(elements,(460,960),(8,38,8),11.5,lit=LIT if key!='old' else ('core','crystal'),textures=textures)
    renders[key].save(HERE/'renders'/f'tower_{key}.png')
close={'coupler':draw(coupler(),(400,400),(8,8,8),13.5,lit=LIT),
       'resonator':draw(resonator(False),(400,400),(8,7.5,8),14,lit=LIT)}
for k,im in close.items():im.save(HERE/'renders'/f'{k}.png')

sheet=Image.new('RGBA',(1440,1080),BG)
label(sheet,(40,24),'互通塔耦合器 / 以太谐振器 · 重设计 V1',32)
label(sheet,(42,74),'安山机壳立柱 + 黄铜箍 · 以太石英芯柱贯通整座塔 · 谐振器为音叉转子与菱形发射晶体',19,MUTED)
for i,(key,title) in enumerate((('old','现有'),('idle','新稿 · 待机'),('running','新稿 · 运行（转子转动、光柱）'))):
    sheet.alpha_composite(renders[key].resize((300,626)),(30+i*300,110)); label(sheet,(60+i*300,745),title,19)
sheet.alpha_composite(close['coupler'].resize((300,300)),(960,110)); label(sheet,(1000,415),'耦合器：四角安山柱、底部黄铜框、中段黄铜卡箍',16)
sheet.alpha_composite(close['resonator'].resize((300,300)),(960,450)); label(sheet,(1000,755),'谐振器：黄铜轴承座、双端音叉、以太发射晶体',16)
label(sheet,(40,800),'新增贴图',18); swatches(sheet,['ether_glass','ether_core','ether_beam','resonator_deck'],40,830)
label(sheet,(320,800),'其余面直接使用 Create 安山机壳与黄铜机壳；互通塔底座沿用现有模型。',17,MUTED)
label(sheet,(320,830),'以太芯柱在相邻耦合器之间连续，塔越高光柱越长，对应“塔越高带载越大”。',17,MUTED)
label(sheet,(40,1040),'Python 离线渲染，非游戏截图 · 概念稿，未修改正式资源',16,MUTED)
sheet.save(HERE/'tower_sheet.png'); print(HERE/'tower_sheet.png')
