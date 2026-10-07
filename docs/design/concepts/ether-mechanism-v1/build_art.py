"""Ether assembly sprites: Create chassis plus custom quartz mechanism, 16x16."""
from pathlib import Path
import json
import sys
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences

REF=CreateReferences()
ASSETS=HERE/'assets/distantstock'
TEX=ASSETS/'textures/item'
MODELS=ASSETS/'models/item'
SOURCES=HERE/'reference'
for p in (TEX,MODELS,SOURCES): p.mkdir(parents=True,exist_ok=True)
C={
    'well':'#35536e','steel':'#507b9b','steel_light':'#699dbc',
    'ice':'#8dbbd6','ice_light':'#b6d8e8','white':'#f3f6e9','spark':'#d9ebee',
    'copper_dark':'#76462d','copper':'#b57849','brass':'#d3a155',
    'gold':'#fbcc68','gold_light':'#fff9c7','iron':'#a9a9a9',
}


def base():
    im=REF.texture('create:item/incomplete_precision_mechanism').copy()
    # Rebuild only the inset plate; retain original outer brass and iron lug.
    old={(153,90,61,255):C['steel'],(118,70,45,255):C['well'],(181,120,73,255):C['steel_light']}
    d=ImageDraw.Draw(im)
    for y in range(5,11):
        for x in range(4,12):
            if im.getpixel((x,y)) in old: d.point((x,y),fill=old[im.getpixel((x,y))])
    return im


def incomplete():
    im=base(); d=ImageDraw.Draw(im)
    # Empty dark socket and two spaced copper terminals show unfinished work.
    d.rectangle((7,6,10,9),fill=C['well'])
    d.line((7,6,9,6),fill=C['steel_light'])
    d.line((7,6,7,8),fill=C['steel_light'])
    d.line((8,9,9,9),fill=C['steel'])
    d.line((5,7,5,9),fill=C['copper_dark'])
    d.point((5,7),fill=C['copper'])
    d.point((6,5),fill=C['gold'])
    d.point((10,10),fill=C['brass'])
    return im


def complete():
    im=base(); d=ImageDraw.Draw(im)
    # Compact copper winding on the left; a dark gap separates it from quartz.
    d.line((4,6,4,9),fill=C['copper_dark'])
    d.line((5,6,5,9),fill=C['copper'])
    d.line((4,6,6,6),fill=C['gold_light'])
    d.line((4,8,6,8),fill=C['gold'])
    d.point((5,9),fill=C['brass'])
    # Large five-by-six faceted crystal, in the repository's exact ether palette.
    crystal=[(8,4),(9,4),(11,6),(11,8),(9,10),(7,8),(7,6)]
    d.polygon(crystal,fill=C['ice'])
    d.line([(8,4),(7,6),(7,7)],fill=C['white'])
    d.polygon([(8,5),(9,5),(10,6),(8,8)],fill=C['ice_light'])
    d.line((8,5,8,7),fill=C['white'])
    d.point((9,5),fill=C['spark'])
    d.line([(11,6),(11,8),(9,10)],fill=C['steel'])
    d.line([(10,7),(10,8),(9,9)],fill=C['steel_light'])
    # Opposing brass jaws hold the quartz instead of surrounding it with glow.
    d.line((6,5,7,5),fill=C['gold'])
    d.point((6,6),fill=C['brass'])
    d.line((8,10,10,10),fill=C['copper_dark'])
    d.line((8,9,9,9),fill=C['gold'])
    d.point((10,9),fill=C['brass'])
    return im


def label(im,xy,text,size=22,color='#344139'):
    ImageDraw.Draw(im).text(xy,text,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=color)


def checker(size,step=24):
    im=Image.new('RGBA',size,'#e9e8df'); d=ImageDraw.Draw(im)
    for y in range(0,size[1],step):
        for x in range(0,size[0],step):
            if (x//step+y//step)%2: d.rectangle((x,y,x+step-1,y+step-1),fill='#dcded3')
    return im


def main():
    original={name:REF.texture('create:item/'+name) for name in ('incomplete_precision_mechanism','precision_mechanism')}
    for name,im in original.items(): im.save(SOURCES/(name+'.png'))
    quartz=Image.open(ROOT/'src/main/resources/assets/distantstock/textures/item/polished_ether_quartz.png').convert('RGBA')
    quartz.save(SOURCES/'polished_ether_quartz.png')
    items={'incomplete_ether_mechanism':incomplete(),'ether_mechanism':complete()}
    for name,im in items.items():
        im.save(TEX/(name+'.png'))
        (MODELS/(name+'.json')).write_text(json.dumps({'parent':'minecraft:item/generated','textures':{'layer0':'distantstock:item/'+name}},indent=2)+'\n')
        assert im.size==(16,16)
        assert set(im.getchannel('A').getdata())=={0,255}
        assert im.getchannel('A').tobytes()==original['incomplete_precision_mechanism'].getchannel('A').tobytes()
    sheet=Image.new('RGBA',(1400,1030),'#e9e8df')
    label(sheet,(42,27),'以太构件 / 半成品与成品',36)
    label(sheet,(44,82),'Create 黄铜装配底座 × 远仓以太石英 · 16×16 原生像素',20,'#717a6a')
    d=ImageDraw.Draw(sheet);d.line((42,122,1358,122),fill='#b8beaf')
    for i,(name,im) in enumerate(items.items()):
        x=80+i*690
        tile=checker((384,384));tile.alpha_composite(im.resize((384,384),Image.Resampling.NEAREST))
        sheet.alpha_composite(tile,(x+20,150))
        label(sheet,(x+20,554),'半成品 / 待装晶核' if i==0 else '以太构件 / 装配完成',28)
        label(sheet,(x+20,600),'空置夹座、外露端子、装配底板' if i==0 else '冰蓝晶核、铜线圈、黄铜固定爪',20,'#717a6a')
        for row,bg in enumerate(('#c6c6c6','#3b423a')):
            for j,scale in enumerate((1,2,3)):
                px=16*scale;xx=x+24+j*100; yy=660+row*65
                d.rectangle((xx-4,yy-4,xx+px+3,yy+px+3),fill=bg)
                sheet.alpha_composite(im.resize((px,px),Image.Resampling.NEAREST),(xx,yy))
        label(sheet,(x+347,677),'1× / 2× / 3×',17,'#717a6a')
    d.line((42,804,1358,804),fill='#b8beaf')
    label(sheet,(44,828),'本地原始资源参照',22)
    for i,(name,im) in enumerate([('Create 半成品',original['incomplete_precision_mechanism']),('Create 精密构件',original['precision_mechanism']),('远仓磨制以太石英',quartz)]):
        x=50+i*445
        sheet.alpha_composite(im.resize((96,96),Image.Resampling.NEAREST),(x,883))
        label(sheet,(x+120,916),name,20,'#717a6a')
    sheet.save(HERE/'ether_mechanism_design_sheet.png')
    preview=checker((640,320),20)
    for i,im in enumerate(items.values()):
        preview.alpha_composite(im.resize((256,256),Image.Resampling.NEAREST),(32+320*i,32))
    preview.save(HERE/'ether_mechanism_preview.png')
    print('Generated complete/incomplete 16x16 RGBA sprites and item models; shared silhouette and binary alpha verified.')


if __name__=='__main__': main()
