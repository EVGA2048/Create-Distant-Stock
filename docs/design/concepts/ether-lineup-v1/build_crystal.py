"""Resonant ether crystal: a tier above polished ether quartz and the ether mechanism."""
import math
import numpy as np
from common import *
from PIL import Image
from render_scene import box, face

# Pixel key: ether ramp for the gem, brass ramp for the girdle band.
KEY={'O':ETHER['outline'],'k':ETHER['deep'],'d':ETHER['dark'],'m':ETHER['mid'],'l':ETHER['light'],
     'p':ETHER['pale'],'w':ETHER['mist'],'W':ETHER['white'],
     'D':BRASS['dark'],'s':BRASS['shade'],'b':BRASS['base'],'L':BRASS['light'],'R':BRASS['glint'],'G':BRASS['glint']}
# Inner pixels of each row; the row is centred on columns 7/8 and closed with an outline pixel.
ROWS=['','Wl','pwlm','ppwlmd','pppwlmmd','ppplwlmmdd',
      'LLLGLLLLLLbb','bRbbRbbRbbRs','ssssssssssss',
      'plmmmddddk','lpmmddkk','lmpmddkk','lmpdkk','mmdk','dk','']
BAND=(6,7,8)


def icon():
    im=Image.new('RGBA',(16,16))
    for y,inner in enumerate(ROWS):
        n=len(inner)//2+1; edge='D' if y in BAND else 'O'
        for x,ch in enumerate(edge+inner+edge):im.putpixel((8-n+x,y),rgba(KEY[ch]))
    return im


def ramp(name,top,bottom,steps=4):
    """Vertical facet tile banded into a few flat tones, like hand-shaded pixel art."""
    a,b=np.array(ImageColor.getrgb(top)),np.array(ImageColor.getrgb(bottom))
    im=Image.new('RGBA',(16,16))
    for y in range(16):
        t=min(steps-1,y*steps//16)/(steps-1); c=tuple(int(v) for v in a+(b-a)*t)+(255,)
        for x in range(16):im.putpixel((x,y),c)
    NEW[name]=im; im.save(HERE/'textures'/(name+'.png')); return name


CROWN=[ramp('crystal_crown_a',ETHER['white'],ETHER['light']),ramp('crystal_crown_b',ETHER['mist'],ETHER['mid']),
       ramp('crystal_crown_c',ETHER['pale'],ETHER['dark'])]
PAVILION=[ramp('crystal_pavilion_a',ETHER['light'],ETHER['dark']),ramp('crystal_pavilion_b',ETHER['pale'],ETHER['mid']),
          ramp('crystal_pavilion_c',ETHER['mid'],ETHER['deep'])]


def poly(points,material,uv,centre):
    """Triangle or quad wound so the renderer's normal points away from centre."""
    p=np.array(points,float)
    if np.dot(-np.cross(p[1]-p[0],p[2]-p[0]),p.mean(axis=0)-np.array(centre))<0:
        points,uv=points[::-1],uv[::-1]
    if len(points)==3:points,uv=points+[points[2]],uv+[uv[2]]
    return face(points,material,uv)


def gem(cx=8,cy=14,cz=8,radius=4,crown=5.5,pavilion=7.5):
    hexagon=[(cx+radius*math.cos(math.radians(30+60*i)),cy,cz+radius*math.sin(math.radians(30+60*i))) for i in range(6)]
    top,bottom=(cx,cy+crown,cz),(cx,cy-pavilion,cz)
    mesh=[]
    for i in range(6):
        a,b=list(hexagon[i]),list(hexagon[(i+1)%6])
        mesh.append(poly([a,list(top),b],CROWN[i%3],[[0,16],[8,0],[16,16]],(cx,cy,cz)))
        mesh.append(poly([a,list(bottom),b],PAVILION[i%3],[[0,0],[8,16],[16,0]],(cx,cy,cz)))
    return mesh


def girdle(cx=8,cy=14,cz=8,radius=4.35,height=1.4,crown_r=3.5,pavilion_r=3.6):
    """Hexagonal brass band; its rims close the gap to the gem faces above and below."""
    y0,y1=cy-height/2,cy+height/2
    at=lambda r,i,y:[cx+r*math.cos(math.radians(30+60*i)),y,cz+r*math.sin(math.radians(30+60*i))]
    mesh=[]
    for i in range(6):
        j=i+1
        mesh.append(poly([at(radius,i,y1),at(radius,j,y1),at(radius,j,y0),at(radius,i,y0)],'brass_block',
                         [[0,0],[5,0],[5,2],[0,2]],(cx,cy,cz)))
        mesh.append(poly([at(crown_r,i,y1),at(crown_r,j,y1),at(radius,j,y1),at(radius,i,y1)],'brass_casing',
                         [[0,0],[5,0],[5,1],[0,1]],(cx,cy-10,cz)))
        mesh.append(poly([at(pavilion_r,i,y0),at(pavilion_r,j,y0),at(radius,j,y0),at(radius,i,y0)],'brass_casing',
                         [[0,0],[5,0],[5,1],[0,1]],(cx,cy+10,cz)))
    return mesh


def stand():
    mesh=box((3,0,3),(13,2,13),'andesite')+box((5,2,5),(11,4,11),'brass_casing')
    mesh+=box((6.75,4,6.75),(9.25,6.75,9.25),'brass_block')
    # Three claws rise from the casing to the girdle, like a Create mechanism frame.
    for angle in (90,210,330):
        x,z=8+4.6*math.cos(math.radians(angle)),8+4.6*math.sin(math.radians(angle))
        mesh+=box((x-.45,2,z-.45),(x+.45,15.2,z+.45),'brass_block')
    return mesh


def render_crystal(mesh,size,center,scale,yaw):
    mats={'andesite':tex('create:block/andesite_casing'),'brass_casing':tex('create:block/brass_casing'),
          'brass_block':tex('create:block/brass_block')}
    mats.update({k:NEW[k] for k in CROWN+PAVILION})
    return render(mesh,mats,size,-yaw,22,center,scale,BG).transpose(Image.Transpose.FLIP_LEFT_RIGHT)


def slot(item,background,scale=4):
    """Vanilla-style inventory slot so the icon is judged at its real size too."""
    im=Image.new('RGBA',(18*scale,18*scale),background)
    fill_px=lambda x0,y0,x1,y1,c:ImageDraw.Draw(im).rectangle((x0*scale,y0*scale,x1*scale-1,y1*scale-1),fill=c)
    fill_px(0,0,18,1,'#373737');fill_px(0,0,1,18,'#373737');fill_px(0,17,18,18,'#ffffff');fill_px(17,0,18,18,'#ffffff')
    im.alpha_composite(item.resize((16*scale,16*scale),Image.Resampling.NEAREST),(scale,scale))
    return im


item=icon(); save('resonant_ether_crystal',item)
model=gem()+girdle()+stand()
r={'stand':render_crystal(model,(560,640),(8,10,8),26,30),
   'stand_side':render_crystal(model,(300,340),(8,10,8),13,-60),
   'gem':render_crystal(gem()+girdle(),(300,340),(8,14,8),18,0)}
for k,im in r.items():im.save(HERE/'renders'/f'crystal_{k}.png')

sheet=Image.new('RGBA',(1440,960),BG)
label(sheet,(40,24),'谐振以太晶体 · 高阶产物概念 V1',32)
label(sheet,(42,74),'以太石英的终点：六方双锥晶体被黄铜腰箍锁住 · 不发光、无光晕，靠切面明暗表现透亮 · 色阶全部取自现有以太石英',19,MUTED)
sheet.alpha_composite(item.resize((256,256),Image.Resampling.NEAREST),(60,140))
label(sheet,(60,410),'物品图标 16×16（×16 放大）',17)
for i,bg in enumerate(('#8b8b8b','#c6c6c6','#2b2b2d')):
    sheet.alpha_composite(slot(item,bg),(60+i*92,450))
for i,bg in enumerate(('#8b8b8b','#c6c6c6')):
    sheet.alpha_composite(slot(item,bg,2),(340+i*48,468))
label(sheet,(60,532),'物品栏 ×4 / ×2',15,MUTED)
sheet.alpha_composite(r['stand'],(420,110))
label(sheet,(470,740),'展示座：安山机壳 + 黄铜套环，三爪托住腰箍',17)
sheet.alpha_composite(r['gem'],(1000,110)); label(sheet,(1040,452),'晶体正视（去掉底座）',16)
sheet.alpha_composite(r['stand_side'],(1000,470)); label(sheet,(1040,812),'背侧 −60°',16)

label(sheet,(60,580),'材料阶梯',20)
tiers=[('distantstock:item/ether_quartz','以太石英'),('distantstock:item/polished_ether_quartz','磨制以太石英'),
       ('distantstock:item/ether_mechanism','以太构件'),('concept:resonant_ether_crystal','谐振以太晶体')]
for i,(path,name) in enumerate(tiers):
    x=60+i*92
    sheet.alpha_composite(tex(path).resize((64,64),Image.Resampling.NEAREST),(x,620))
    label(sheet,(x-4,692),name,13,INK if i==3 else MUTED)
    if i:label(sheet,(x-22,640),'›',24,MUTED)
label(sheet,(60,730),'建议定位（未实装）：以太构件 + 黄铜在运行中的以太谐振器上',15,MUTED)
label(sheet,(60,752),'序列组装合成，作为远距离/跨维度设备的高阶材料。',15,MUTED)
label(sheet,(60,800),'新增贴图',18)
swatches(sheet,['resonant_ether_crystal']+CROWN+PAVILION,60,830,48,58)
label(sheet,(40,925),'Python 离线渲染，非游戏截图 · 概念稿，未修改正式资源 · 3D 展示座使用自定义三角面，游戏内需改为方块元素或物品渲染器',15,MUTED)
sheet.save(HERE/'crystal_sheet.png'); print(HERE/'crystal_sheet.png')
