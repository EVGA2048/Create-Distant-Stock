"""V2: small edits to the current models, using only textures that already ship with the mod or Create."""
import json
from common import *
from PIL import Image

T='distantstock:block/tower/'
BRASS_T,CRYSTAL,SHELL_GLASS,CASING=T+'brass',T+'crystal',T+'crystal_shell',T+'casing_inactive'
SHELL,IRON=T+'shell',T+'iron'


def node(x,y,z,size=2):
    """The tower core's crystal-in-brass socket, scaled down: brass foot, ether crystal, brass lid."""
    h=size/2
    return [el('node_foot',(x-h-.5,y,z-h-.5),(x+h+.5,y+1,z+h+.5),BRASS_T),
            el('node_crystal',(x-h,y+1,z-h),(x+h,y+1+size,z+h),CRYSTAL),
            el('node_lid',(x-h-.25,y+1+size,z-h-.25),(x+h+.25,y+1.5+size,z+h+.25),BRASS_T)]


def collar(y,lo=5.5,hi=10.5,height=2):
    """Brass band hugging the 6..10 ether column, like the brass ring on the tower core."""
    return [el('collar_n',(lo,y,lo),(hi,y+height,6),BRASS_T),el('collar_s',(lo,y,10),(hi,y+height,hi),BRASS_T),
            el('collar_w',(lo,y,6),(6,y+height,10),BRASS_T),el('collar_e',(10,y,6),(hi,y+height,10),BRASS_T)]


def model(path):
    d=json.loads((ASSETS/'models/block'/(path+'.json')).read_text())
    return d['elements'],d['textures']


# Request terminal: the 128px antenna becomes a brass mast with an ether node; the girder gets a sight glass.
def terminal():
    old,t=model('gauge')
    keep=[e for e in old if '#antenna' not in json.dumps(e)]
    return keep+[el('mast',(4,16,13),(5,21,14),BRASS_T)]+node(4.5,21,13.5,2)+[
        el('sight_frame',(5,4,5.5),(11,10,6),BRASS_T),el('sight_glass',(6,5,5.25),(10,9,5.5),CRYSTAL)],t


# Tower coupler: one brass collar with clamp bands on the four posts, nothing else changes.
def coupler(path):
    e,t=model('tower/'+path)
    clamps=[el(f'clamp_{x}_{z}',(x-.25,7,z-.25),(x+2.25,9,z+2.25),BRASS_T) for x in (0,14) for z in (0,14)]
    return e+collar(7)+clamps,t


# Ether resonator: idle column is glass like the coupler's, crowned by the tower core's brass socket.
def resonator(running=False):
    e,t=model('tower/ether_resonator'); rotor,_=model('tower/ether_resonator_rotor')
    column=[el('column',(6,5,6),(10,20,10),SHELL_GLASS,skip=('up','down'))]
    crown=[el('crown_ring',(5,20,5),(11,22,11),BRASS_T),el('crown_crystal',(6.02,22,6.02),(9.98,24,9.98),CRYSTAL)]
    beam=model('tower/ether_resonator_beam')[0] if running else []
    return e+rotor+column+crown+beam,t


# Logger: an ether link window in the free strip under the printer and a brass lip over it.
def logger():
    e,t=model('logger_idle')
    return e+[el('link_frame',(6,0.75,12.5),(14,2.75,13),BRASS_T),
              el('link_window',(6.5,1.25,12.25),(13.5,2.25,12.5),CRYSTAL),
                                      el('printer_lip',(5,6,10),(15,6.5,13.25),BRASS_T)],t


# Nixie clock: pale tower shell frame with brass lips; a sync node above the colon tube.
def nixie():
    d=json.loads((ASSETS/'models/block/nixie_clock.json').read_text())
    e=[x for c in d['children'].values() for x in c['elements']]
    t={'frame':SHELL,'tube':'create:block/nixie_tube'}
    lips=[el('lip_top',(0,11.5,12.5),(16,12.5,16.01),BRASS_T),el('lip_bottom',(0,3.5,12.5),(16,4.5,16.01),BRASS_T)]
    return e+lips+node(9.35,12.5,14.5,1.5),t


# Flap clock (removed from code): Create's own flap display face on an andesite wall housing, one row of flaps.
def flap():
    front='create:block/flap_display_front'
    housing=[el('housing',(0,4,13),(16,12,16),'create:block/andesite_casing',
                faces={'north':front},uv={'north':[0,0,16,8]}),
             el('lip_top',(0,11.5,12.5),(16,12.5,16.01),BRASS_T),el('lip_bottom',(0,3.5,12.5),(16,4.5,16.01),BRASS_T)]
    return housing+node(8,12.5,14.5,1.5),{}


def old_flap():
    import subprocess
    data=json.loads(subprocess.run(['git','show','HEAD:src/main/resources/assets/distantstock/models/block/flap_clock.json'],
                                   cwd=ROOT,capture_output=True,text=True,check=True).stdout)
    return data['elements'],data.get('textures',{})


def tower(new):
    def part(path,dy,fn=None):
        e,t=fn() if fn else model('tower/'+path)
        return shift(e,dy=dy),t
    parts=[part('tower_core',0)]
    if new:parts+=[part('',16,lambda:coupler('tower_coupler_bottom')),part('',32,lambda:coupler('tower_coupler_top')),
                   part('',48,resonator)]
    else:
        r,t=model('tower/ether_resonator'); rr,_=model('tower/ether_resonator_rotor')
        parts+=[part('tower_coupler_bottom',16),part('tower_coupler_top',32),(shift(r+rr,dy=48),t)]
    e=[]; t={}
    for pe,pt in parts:e+=pe; t.update(pt)
    return e,t


def pair(sheet,old,new,x,y,title,notes,old_label='现有'):
    """Equal-size side by side so the change is judged against the shipped model."""
    sheet.alpha_composite(old,(x,y)); sheet.alpha_composite(new,(x+old.width+10,y))
    label(sheet,(x+8,y+6),old_label,15,MUTED); label(sheet,(x+old.width+18,y+6),'V2',15,INK)
    label(sheet,(x,y+old.height+8),title,22)
    for i,n in enumerate(notes):label(sheet,(x,y+old.height+42+i*22),'· '+n,15,MUTED)


def view(elements_textures,size,center,scale,yaw):
    e,t=elements_textures
    return draw(e,size,center,scale,yaw=yaw,textures=t)


models={'request_terminal':terminal(),'tower_coupler_bottom':coupler('tower_coupler_bottom'),
        'tower_coupler_top':coupler('tower_coupler_top'),'ether_resonator':resonator(),
        'logger_idle':logger(),'nixie_clock':nixie(),'flap_clock':flap()}
for name,(e,t) in models.items():
    check(e)
    data={'parent':'minecraft:block/block','credit':'Distant Stock V2 concept: existing textures only',
          'textures':{**t,'particle':t.get('particle',BRASS_T)},'elements':e}
    (HERE/'models'/(name+'.json')).write_text(json.dumps(data,indent=1)+'\n')

R={}
R['tower_old']=view(tower(False),(400,900),(8,36,8),11,30); R['tower_new']=view(tower(True),(400,900),(8,36,8),11,30)
R['coupler_old']=view(model('tower/tower_coupler_top'),(320,340),(8,9,8),10.5,30)
R['coupler_new']=view(coupler('tower_coupler_top'),(320,340),(8,9,8),10.5,30)
R['resonator_old']=view((model('tower/ether_resonator')[0]+model('tower/ether_resonator_rotor')[0],model('tower/ether_resonator')[1]),(320,360),(8,11,8),9,30)
R['resonator_new']=view(resonator(),(320,360),(8,11,8),9,30)
R['terminal_old']=view(model('gauge'),(330,380),(8,12,8),12.5,30); R['terminal_new']=view(terminal(),(330,380),(8,12,8),12.5,30)
R['logger_old']=view(model('logger_idle'),(330,380),(8,8,13),16,-26); R['logger_new']=view(logger(),(330,380),(8,8,13),16,-26)
R['nixie_old']=view(existing('nixie_clock'),(330,300),(8,8,14),13,-26)
R['nixie_new']=view(nixie(),(330,300),(8,8,14),13,-26)
R['flap_old']=view(old_flap(),(330,300),(8,8,14),13,-26); R['flap_new']=view(flap(),(330,300),(8,8,14),13,-26)
for k,im in R.items():im.save(HERE/'renders'/f'{k}.png')

HEAD='只在现有模型上小改 · 所有贴图都是远仓或 Create 已有的文件，没有新画方块贴图 · 母题统一为“黄铜底座托一块以太晶体”（取自互通塔核心）'
FOOT='Python 离线渲染，非游戏截图 · 概念稿，未修改正式资源'

s=Image.new('RGBA',(1560,1140),BG)
label(s,(40,24),'互通塔 · 耦合器 / 以太谐振器 V2',32); label(s,(42,72),HEAD,17,MUTED)
pair(s,R['tower_old'],R['tower_new'],30,110,'整塔',['从下到上：核心 › 耦合器 ×2 › 谐振器','外形与高度不变'])
pair(s,R['coupler_old'],R['coupler_new'],880,110,'互通塔耦合器',['以太柱中段加一圈黄铜箍','四根角柱同高处加黄铜卡箍'])
pair(s,R['resonator_old'],R['resonator_new'],880,560,'以太谐振器',['待机时也显示耦合器同款以太玻璃柱','顶部换成核心同款黄铜座 + 以太晶块'])
label(s,(40,1105),FOOT,15,MUTED); s.save(HERE/'tower_sheet.png')

s=Image.new('RGBA',(1440,760),BG)
label(s,(40,24),'远仓请求台 / 远仓日志台 V2',32); label(s,(42,72),HEAD,17,MUTED)
pair(s,R['terminal_old'],R['terminal_new'],30,110,'远仓请求台',['128px 天线换成黄铜桅杆 + 以太晶体节点','钢梁立柱正面开一扇黄铜框以太观察窗'])
pair(s,R['logger_old'],R['logger_new'],730,110,'远仓日志台',['打印机下方加黄铜框以太链路窗','打印机顶加黄铜压条，其余不动'])
label(s,(40,725),FOOT,15,MUTED); s.save(HERE/'console_sheet.png')

s=Image.new('RGBA',(1440,640),BG)
label(s,(40,24),'辉光管时钟 / 翻牌时钟 V2',32); label(s,(42,72),HEAD,17,MUTED)
pair(s,R['nixie_old'],R['nixie_new'],30,110,'辉光管时钟',['工业铁框换成远仓塔的浅色外壳','上下黄铜压边，冒号管上方加以太同步节点'])
pair(s,R['flap_old'],R['flap_new'],730,110,'翻牌时钟（已从代码移除）',['直接用 Create 翻牌显示器的正面贴图','与辉光管时钟同框：黄铜压边 + 以太同步节点'],'已移除的旧版')
label(s,(40,605),FOOT+' · 翻牌时钟若要恢复需重新实现方块与渲染器',15,MUTED); s.save(HERE/'clock_sheet.png')
print('ok')
