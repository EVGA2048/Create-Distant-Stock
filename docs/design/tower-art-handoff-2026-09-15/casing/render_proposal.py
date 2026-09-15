# -*- coding: utf-8 -*-
"""Eight-neighbour CT texture study with inactive and redstone crystal states."""
from pathlib import Path
import sys,json
sys.dont_write_bytecode=True
from PIL import Image,ImageDraw
import numpy as np
REPO=Path('/Users/xx2005/Documents/git_repository/DistantStock')
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(REPO/'scripts/concepts'))
from render_scene import render,box
BG='#e8eeed'
prior=OUT.parent/'tower_base_v2/textures'
source=Image.open(prior/'casing_plain.png').convert('RGBA')
port=Image.open(prior/'casing_blue.png').convert('RGBA')
# Extend the subdued centre by native pixels, without enlarging its pixels.
panel=Image.new('RGBA',(16,16))
for y in range(16):
    for x in range(16):panel.putpixel((x,y),source.getpixel((3+x%10,3+y%10)))
crystal=Image.new('RGBA',(16,16),(183,224,237,40))
d=ImageDraw.Draw(crystal)
# Sparse hard-edged reflections. No painted border inside a connected face.
d.line((3,5,7,1),fill=(232,251,255,112),width=1)
d.line((3,6,7,2),fill=(172,220,241,64),width=1)
d.line((9,14,12,11),fill=(223,247,255,91),width=1)
d.point((13,10),fill=(242,253,255,113))
offsets=[(-1,0),(1,0),(0,-1),(0,1),(-1,-1),(1,-1),(-1,1),(1,1)]
def tile(mask,on=False,fluid=False):
    im=(crystal if on else panel).copy()
    # L/R/T/B connected flags. Connected edges disappear; outer frame stays.
    for bit,rect in enumerate([(0,0,3,16),(13,0,16,16),(0,0,16,3),(0,13,16,16)]):
        if not mask&(1<<bit):im.paste(source.crop(rect),rect[:2])
    # Concave corners require diagonal neighbours, not only four edge flags.
    for a,b,diag,rect in [(0,2,4,(0,0,3,3)),(1,2,5,(13,0,16,3)),
                          (0,3,6,(0,13,3,16)),(1,3,7,(13,13,16,16))]:
        if mask&(1<<a) and mask&(1<<b) and not mask&(1<<diag):
            im.paste(source.crop(rect),rect[:2])
    if fluid: im.paste(port.crop((2,2,14,14)),(2,2))
    return im
materials={}
def scene(w,h,on=False,fluid=False):
    occupied={(x,y,0) for x in range(w) for y in range(h)}
    result=[]
    for p in sorted(occupied):
        origin=np.array(p)*16
        for f in box(origin,origin+16,'unused'):
            ps=np.asarray(f['points'])
            center=ps.mean(0)
            normal=np.rint((center-(origin+8))/8).astype(int)
            if tuple(np.array(p)+normal) in occupied:continue
            u=np.rint((ps[1]-ps[0])/16).astype(int)
            v=np.rint((ps[3]-ps[0])/16).astype(int)
            mask=sum(1<<i for i,(du,dv) in enumerate(offsets) if tuple(np.array(p)+u*du+v*dv) in occupied)
            is_port=fluid and p==(w-1,0,0) and normal[2]==-1
            key=f'{mask}_{int(on)}_{int(is_port)}'
            materials[key]=tile(mask,on,is_port)
            f['material']=key
            if on:f['emissive']=True
            result.append(f)
    return result
def picture(w,h,on,size,scale,fluid=False):
    mesh=scene(w,h,on,fluid)
    # Backdrop coloured blocks, separated from the transparent wall by 12 pixels.
    materials['backdrop']=Image.new('RGBA',(16,16),'#596e67')
    materials['stripe']=Image.new('RGBA',(16,16),'#a4b9b1')
    for x in range(w):
        mesh+=box((x*16,-1,28),(x*16+16,h*16+1,30),'backdrop' if x%2 else 'stripe')
    return render(mesh,materials,size=size,center=(w*8,h*8,8),scale=scale,yaw=19,pitch=14,background=BG)
single_off=picture(1,1,False,(330,320),13)
single_on=picture(1,1,True,(330,320),13)
wall_off=picture(5,3,False,(950,540),10,True)
wall_on=picture(5,3,True,(950,540),10,True)
for name,im in [('single_off',single_off),('single_on',single_on),('wall_off',wall_off),('wall_on',wall_on)]:im.save(OUT/(name+'.png'))
from PIL import ImageFont
font='/System/Library/Fonts/STHeiti Medium.ttc'
def txt(im,xy,s,size=20):ImageDraw.Draw(im).text(xy,s,font=ImageFont.truetype(font,size),fill='#344b53')
sheet=Image.new('RGBA',(1510,1340),BG)
txt(sheet,(40,25),'远仓机壳 / 连接纹理与红石水晶态',32)
txt(sheet,(40,78),'A 白蓝流体口 · 断电闭合 · 通电后中间透明，外轮廓保留',21)
txt(sheet,(40,140),'未激活 / 不透明',25)
sheet.alpha_composite(single_off,(45,200))
sheet.alpha_composite(wall_off,(480,165))
txt(sheet,(65,560),'单块：保留完整边框',20)
txt(sheet,(580,713),'5×3×1 拼接：内部边框合并，保留整面墙的外框',20)
txt(sheet,(40,779),'红石激活 / 水晶透明面',25)
sheet.alpha_composite(single_on,(45,830))
sheet.alpha_composite(wall_on,(480,770))
txt(sheet,(65,1190),'透明中心 + 像素反光',20)
txt(sheet,(40,1297),'背后的灰绿条纹用于观察透明度 · 接口保持实体 · 离线美术示意，红石与发光效果待程序接入',17)
sheet.save(OUT/'casing_states_sheet.png')
(OUT/'textures').mkdir(exist_ok=True)
for on in (False,True):
    atlas=Image.new('RGBA',(256,256))
    for mask in range(256):atlas.paste(tile(mask,on),(mask%16*16,mask//16*16))
    atlas.save(OUT/'textures'/('ct_active.png' if on else 'ct_inactive.png'))
tile(0,False).save(OUT/'textures/casing_inactive.png')
tile(0,True).save(OUT/'textures/casing_active.png')
tile(0,False,True).save(OUT/'textures/fluid_port_a.png')
(OUT/'README.md').write_text('''# 远仓机壳：连接纹理与红石态草案

A 白蓝色流体口已经用户选定。本轮新增连接纹理和水晶透明态，尚待确认。

两张256×256图集各含256个16×16格，索引为八邻域掩码：bit0左、1右、2上、3下、4左上、5右上、6左下、7右下。包含重复等效格，属于完整查表草案，并非Create特定CTType可直接加载的图集。按mask%16列、mask//16行取格。

相邻边去掉边框；缺少对角邻居时保留内凹角。中心平铺原生像素面板。红石激活仅将面板换成半透明蓝白玻璃，并保留不透明边框。流体接口保持实体（提案），不随玻璃面透明。

渲染已消除相邻方块内部面。正式实现须根据世界邻接状态和面UV方向选格，不能只把独立材质贴到每个方块；混合红石状态的连接规则待确定。水晶态的离线自发光仅供视觉参考，世界照明、光影材质与红石检测均未实现。

普通机壳左上角反光为硬像素，保持16纹素/格。背后灰绿竖条是透明度参考背景，不属于机壳。本轮未修改工程正式资源。
''')
print(OUT/'casing_states_sheet.png')
