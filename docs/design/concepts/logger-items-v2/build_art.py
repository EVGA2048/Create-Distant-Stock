"""Native 16x16 logger item sprites. Pixel recipes, no resampling or antialiasing."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ASSETS=HERE/'assets/distantstock'
TEX=ASSETS/'textures/item'
MODELS=ASSETS/'models/item'
TEX.mkdir(parents=True,exist_ok=True)
MODELS.mkdir(parents=True,exist_ok=True)
P={
    'edge':'#92917e', 'shadow':'#b9b69a',
    'paper':'#e5dfba', 'light':'#f4edcf', 'ink':'#757b6b',
    'core':'#68604d',
}


def sprite():
    im=Image.new('RGBA',(16,16))
    return im,ImageDraw.Draw(im)


def receipt():
    im,d=sprite()
    # Straight paper strip. One tiny tear instead of a dripping scalloped hem.
    d.rectangle((4,1,11,14),fill=P['paper'])
    d.line((4,1,11,1),fill=P['light'])
    d.line((4,2,4,13),fill=P['light'])
    d.line((11,2,11,14),fill=P['edge'])
    d.line((5,14,10,14),fill=P['shadow'])
    d.point((4,14),fill=P['shadow'])
    d.point((7,14),fill=(0,0,0,0))
    d.point((7,13),fill=P['shadow'])
    # Regular, clearly separated one-pixel print lines.
    d.line((6,4,9,4),fill=P['ink'])
    d.line((6,6,9,6),fill=P['ink'])
    d.line((6,8,8,8),fill=P['ink'])
    d.line((6,10,7,10),fill=P['ink'])
    d.point((9,10),fill=P['ink'])
    return im


def roll():
    im,d=sprite()
    # Horizontal cylinder: parallel straight sides and a regular end face.
    d.rectangle((5,3,12,11),fill=P['paper'])
    d.rectangle((13,4,13,10),fill=P['paper'])
    d.rectangle((14,5,14,9),fill=P['edge'])
    d.line((6,3,12,3),fill=P['light'])
    d.line((7,4,12,4),fill=P['light'])
    d.line((8,10,13,10),fill=P['shadow'])
    d.line((6,11,12,11),fill=P['edge'])
    # Only a two-pixel straight feed tab; no loose curling ribbon.
    d.rectangle((9,11,12,13),fill=P['paper'])
    d.line((9,11,12,11),fill=P['shadow'])
    d.line((9,13,12,13),fill=P['edge'])
    # Symmetric stepped ellipse: 3/5/7/7/7/7/7/5/3 texel rows.
    for y,l,r in [(3,4,6),(4,3,7),(5,2,8),(6,2,8),(7,2,8),
                  (8,2,8),(9,2,8),(10,3,7),(11,4,6)]:
        d.line((l,y,r,y),fill=P['paper'])
        d.point((l,y),fill=P['light'])
        d.point((r,y),fill=P['edge'])
    d.line((4,3,6,3),fill=P['light'])
    d.line((4,11,6,11),fill=P['shadow'])
    # A compact rectangular bore reads cleanly at native inventory size.
    d.rectangle((4,5,6,9),fill=P['shadow'])
    d.rectangle((4,6,5,8),fill=P['core'])
    d.line((3,6,3,8),fill=P['light'])
    return im


def label(im,xy,text,size=22,fill='#344139'):
    ImageDraw.Draw(im).text(xy,text,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=fill)


def checker(size,step=24):
    im=Image.new('RGBA',size,'#e9e8df')
    d=ImageDraw.Draw(im)
    for y in range(0,size[1],step):
        for x in range(0,size[0],step):
            if (x//step+y//step)%2: d.rectangle((x,y,x+step-1,y+step-1),fill='#dcded3')
    return im


def main():
    items={'event_receipt':receipt(),'logger_paper_roll':roll()}
    for name,im in items.items():
        im.save(TEX/(name+'.png'))
        (MODELS/(name+'.json')).write_text(json.dumps({
            'parent':'minecraft:item/generated',
            'textures':{'layer0':'distantstock:item/'+name}
        },indent=2)+'\n')
        assert im.size==(16,16)
        assert set(im.getchannel('A').getdata())=={0,255}
    sheet=Image.new('RGBA',(1200,860),'#e9e8df')
    label(sheet,(40,28),'远仓日志台 / 纸张物品 V2',34)
    label(sheet,(42,82),'16×16 原生物品贴图 · 透明背景 · 与面板小票共用暖纸色',19,'#717a6a')
    d=ImageDraw.Draw(sheet)
    d.line((40,119,1160,119),fill='#b8beaf')
    for i,(name,im) in enumerate(items.items()):
        x=72+i*584
        bg=checker((384,384))
        bg.alpha_composite(im.resize((384,384),Image.Resampling.NEAREST))
        sheet.alpha_composite(bg,(x+26,150))
        label(sheet,(x+24,559),'日志条 / 取出的小票' if i==0 else '替换纸卷 / 未打印耗材',27)
        label(sheet,(x+24,605),'直边纸带、单处小撕口、规整打印行' if i==0 else '平直筒身、规则卷面、短直纸尾',20,'#717a6a')
        # Inventory-size inspection on both light and dark slots.
        for j,color in enumerate(('#c6c6c6','#3b423a')):
            y=661+j*62
            for k,scale in enumerate((1,2,3)):
                side=16*scale; left=x+24+k*108
                d.rectangle((left-4,y-4,left+side+3,y+side+3),fill=color)
                sheet.alpha_composite(im.resize((side,side),Image.Resampling.NEAREST),(left,y))
        label(sheet,(x+357,670),'1× / 2× / 3×',17,'#717a6a')
    label(sheet,(40,817),'像素稿与物品模型已备齐 · 此页为放大预览，游戏使用透明的 16×16 PNG',18,'#717a6a')
    sheet.save(HERE/'logger_items_design_sheet.png')
    preview=checker((640,320),20)
    for i,im in enumerate(items.values()):
        preview.alpha_composite(im.resize((256,256),Image.Resampling.NEAREST),(32+320*i,32))
    preview.save(HERE/'logger_items_preview.png')
    print('Wrote 2 native RGBA sprites, 2 generated-item models, and size-check previews.')


if __name__=='__main__':
    main()
