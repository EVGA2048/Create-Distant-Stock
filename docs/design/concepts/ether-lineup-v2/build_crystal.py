"""V2 premium crystal: two 16x16 candidates drawn only with colours the mod already uses.

Style rules taken from the shipped ether items:
  * ether_quartz / polished_ether_quartz: pale-blue ramp with white highlight texels.
  * ether_mechanism (the current top tier): deep #293f53 outline plus brass #fbcc68/#b57849.
  * the tower core's own motif: a crystal held in a brass socket.
So the new tier gets a bigger faceted gem in a brass setting - no glow, no halo, no new hue.
"""
from common import *
from PIL import Image, ImageDraw

OUT='#293f53'                                     # ether_mechanism's outline colour
GLINT='#fff9c7'                                   # ether_mechanism's brightest brass texel
BRASS_EXTRA={'shade2':'#b57849','dark2':'#76462d','base2':'#9e6947','glint2':GLINT}
ALLOWED=set(ETHER.values())|set(BRASS.values())|set(BRASS_EXTRA.values())|{OUT,'#ffffff'}

CROWN=[('l',0.22),('w',0.45),('p',0.62),('m',0.80),('d',1.01)]     # left to right, above the band
PAVILION=[('p',0.30),('m',0.55),('d',0.80),('k',1.01)]             # below the band
BANDRAMP=[('B',0.30),('b',0.60),('s',0.85),('D',1.01)]             # brass girdle


RAMP={'O':OUT,'k':ETHER['deep'],'d':ETHER['dark'],'m':ETHER['mid'],'l':ETHER['light'],
      'p':ETHER['pale'],'w':ETHER['mist'],'W':ETHER['white'],
      'D':BRASS['dark'],'s':BRASS['shade'],'b':BRASS['base'],'L':BRASS['light'],
      'B':BRASS_EXTRA['shade2']}


def tone(stops,t):
    for ch,edge in stops:
        if t<edge:return RAMP[ch]
    return RAMP[stops[-1][0]]


def row(im,y,x0,x1,stops,outline=OUT):
    """One scanline of a facet: outline at both ends, a flat-tone gradient in between."""
    span=max(1,x1-x0)
    for x in range(x0,x1+1):
        im.putpixel((x,y),rgba(tone(stops,(x-x0)/span if x0!=x1 else .5)))
    im.putpixel((x0,y),rgba(outline)); im.putpixel((x1,y),rgba(outline))


# Three shards as (axis, first row): the middle one is tallest. Feet all end on row 12.
SHARDS=((3,6),(8,1),(13,7))


def candidate_a():
    """Single hexagonal crystal with a two-row brass girdle: the polished gem, one size up."""
    im=Image.new('RGBA',(16,16))
    crown={1:1,2:2,3:3,4:4,5:5,6:5,7:6}
    pavilion={10:6,11:5,12:4,13:3,14:1}
    for y,hw in crown.items():row(im,y,8-hw,7+hw,CROWN)
    row(im,8,1,14,BANDRAMP)                       # brass girdle, full width
    row(im,9,1,14,BANDRAMP)
    for y,hw in pavilion.items():row(im,y,8-hw,7+hw,PAVILION)
    return im


def scan(im,y):
    return [x for x in range(16) if im.getpixel((x,y))[3]]


def shard(im,xc,top,foot=12):
    """One crystal: a lit single-texel point, then three texels wide, four at the base.

    The point row is drawn alone - outlining it would sit the highlight on a dark texel."""
    for y in range(top,foot+1):
        k=(y-top)/(foot-top)
        if k<0.12:
            im.putpixel((xc,y),rgba(ETHER['white']));continue
        if k<0.4:x0,x1=xc-1,xc+1
        else:x0,x1=xc-1,xc+2
        row(im,y,x0,x1,CROWN if k<0.55 else PAVILION)


def candidate_b():
    """Three faceted shards in a brass socket, the tower core's motif at item scale."""
    im=Image.new('RGBA',(16,16))
    for xc,top in SHARDS:shard(im,xc,top)
    for y,stops in ((13,BANDRAMP),(14,BANDRAMP),(15,PAVILION)):
        x0,x1=(2,15) if y!=15 else (3,14)
        row(im,y,x0,x1,stops)
    return im


def palette(im):
    return {(r,g,b) for r,g,b,a in im.getdata() if a}


def opaque(im):
    return sum(1 for *_,a in im.getdata() if a)


def slot(item,background,scale=4):
    """Vanilla-style inventory slot so the icon is judged at its real size too."""
    im=Image.new('RGBA',(18*scale,18*scale),background)
    px=lambda x0,y0,x1,y1,c:ImageDraw.Draw(im).rectangle((x0*scale,y0*scale,x1*scale-1,y1*scale-1),fill=c)
    px(0,0,18,1,'#373737'); px(0,0,1,18,'#373737'); px(0,17,18,18,'#ffffff'); px(17,0,18,18,'#ffffff')
    im.alpha_composite(item.resize((16*scale,16*scale),Image.Resampling.NEAREST),(scale,scale))
    return im


a,b=candidate_a(),candidate_b()
save('resonant_crystal_a',a); save('resonant_crystal_b',b)

# Only the mod's own colours, and a bigger silhouette than the tier below: checked, not eyeballed.
for name,im in (('a',a),('b',b)):
    extra=palette(im)-{ImageColor.getrgb(c) for c in ALLOWED}
    print(f'candidate {name}: opaque={opaque(im)} palette_outside_existing={extra}')
for n in ('ether_quartz','polished_ether_quartz','ether_mechanism'):
    print(f'  {n}: opaque={opaque(tex("distantstock:item/"+n))}')

S=(256,256)
sheet=Image.new('RGBA',(1440,940),BG)
label(sheet,(40,24),'谐振以太晶体 · 高阶产物概念 V2',32)
label(sheet,(42,74),'造型沿用远仓自己的两点：互通塔核心的“黄铜座托晶体”，以及以太石英的浅蓝色阶 · 不发光、无光晕、无新配色',19,MUTED)

for i,(im,name,tag) in enumerate(((a,'A · 单晶体 + 黄铜腰箍','以太石英的放大版：一颗更大的六方晶体'),(b,'B · 三棱晶簇 + 黄铜底座','互通塔核心的动机：晶体从黄铜座里长出来'))):
    x=60+i*700
    sheet.alpha_composite(im.resize(S,Image.Resampling.NEAREST),(x,130))
    label(sheet,(x,398),name,20); label(sheet,(x,424),tag,15,MUTED)
    for j,bg in enumerate(('#8b8b8b','#c6c6c6','#2b2b2d')):
        sheet.alpha_composite(slot(im,bg,4),(x+j*84,452))
    sheet.alpha_composite(slot(im,'#8b8b8b',2),(x+260,468))
    label(sheet,(x,542),'物品栏 ×4 / ×2',14,MUTED)

label(sheet,(60,586),'材料阶梯（同一显示尺寸，注意晶体大小）',20)
tiers=[('distantstock:item/ether_quartz','以太石英'),('distantstock:item/polished_ether_quartz','磨制以太石英'),
       ('distantstock:item/ether_mechanism','以太构件'),('concept:resonant_crystal_a','A 谐振以太晶体'),
       ('concept:resonant_crystal_b','B 谐振以太晶体')]
for i,(path,n) in enumerate(tiers):
    x=60+i*150
    sheet.alpha_composite(tex(path).resize((96,96),Image.Resampling.NEAREST),(x,624))
    label(sheet,(x,726),n,13,INK if i>=3 else MUTED)
    if i:label(sheet,(x-30,650),'›',26,MUTED)
label(sheet,(60,756),'新增贴图只有这一个图标：颜色全部取自现有以太石英与以太构件的色阶，没有引入新色',15,MUTED)
label(sheet,(60,782),'建议定位（未实装）：以太构件 + 黄铜块，在运行中的以太谐振器上序列组装，作为跨维度设备的高阶材料',15,MUTED)
swatches(sheet,['resonant_crystal_a','resonant_crystal_b'],60,812,64,80)
label(sheet,(40,905),'Python 离线渲染，非游戏截图 · 概念稿，未修改正式资源 · 图像未经目视检查，仅做过色板和覆盖率数值校验',15,MUTED)
sheet.save(HERE/'crystal_sheet.png'); print(HERE/'crystal_sheet.png')
