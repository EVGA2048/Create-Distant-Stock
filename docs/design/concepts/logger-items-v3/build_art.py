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
    'edge':'#7c7c67', 'shadow':'#aaa68a', 'fold':'#c5c1a0',
    'paper':'#e5dfba', 'light':'#f4edcf', 'ink':'#7a806c',
    'core':'#5c5140', 'core_light':'#948364',
}


def sprite():
    im=Image.new('RGBA',(16,16))
    return im,ImageDraw.Draw(im)


def receipt():
    im,d=sprite()
    # One gentle bend with almost parallel edges; no bulges or dangling points.
    outline=[(5,1),(10,1),(11,2),(11,6),(10,8),(10,13),
             (9,14),(5,14),(4,13),(4,8),(5,6)]
    d.polygon(outline,fill=P['paper'])
    d.line(outline+[outline[0]],fill=P['edge'],width=1)
    d.line([(6,2),(6,6),(5,8),(5,12)],fill=P['light'])
    d.line([(10,3),(10,6),(9,8),(9,12)],fill=P['fold'])
    # A single squared fold and a shallow tear keep the sheet papery.
    d.point((10,2),fill=P['fold'])
    d.line((6,13,8,13),fill=P['paper'])
    d.point((7,14),fill=P['fold'])
    # Coarse print marks; no tiny faux text or fixed severity color.
    d.line((7,4,9,4),fill=P['ink'])
    d.line((7,6,9,6),fill=P['ink'])
    d.line((6,8,8,8),fill=P['ink'])
    d.line((6,10,7,10),fill=P['ink'])
    d.point((8,12),fill=P['ink'])
    return im


def roll():
    im,d=sprite()
    # V1's three-quarter cylinder, with a taut outer sheet and regular rim.
    body=[(4,4),(9,1),(11,1),(13,3),(14,5),(14,7),(12,9),
          (7,12),(4,12),(2,10),(1,8),(2,5)]
    d.polygon(body,fill=P['paper'])
    d.line(body+[body[0]],fill=P['edge'])
    d.polygon([(4,5),(9,2),(11,2),(13,4),(8,7)],fill=P['light'])
    d.polygon([(8,8),(13,5),(13,7),(11,9),(7,11)],fill=P['fold'])
    # A short tensioned flap follows the roll, instead of sagging underneath.
    tail=[(9,9),(12,8),(13,10),(11,12),(8,13),(7,12)]
    d.polygon(tail,fill=P['paper'])
    d.line([(12,8),(13,10),(11,12),(8,13),(7,12)],fill=P['edge'])
    d.line((8,12,12,10),fill=P['light'])
    # Pixel ellipse, thick pale winding, then the cardboard bore.
    face=[(4,4),(6,4),(8,6),(8,9),(6,11),(4,11),(2,9),(2,6)]
    d.polygon(face,fill=P['paper'])
    d.line(face+[face[0]],fill=P['edge'])
    d.line([(4,5),(6,5),(7,6),(7,8)],fill=P['light'])
    d.line([(3,7),(3,9),(4,10),(6,10)],fill=P['fold'])
    d.polygon([(4,6),(5,6),(6,7),(6,8),(5,9),(4,8)],fill=P['core_light'])
    d.rectangle((4,7,5,8),fill=P['core'])
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
    label(sheet,(40,28),'远仓日志台 / 纸张物品 V3',34)
    label(sheet,(42,82),'16×16 原生物品贴图 · 透明背景 · 与面板小票共用暖纸色',19,'#717a6a')
    d=ImageDraw.Draw(sheet)
    d.line((40,119,1160,119),fill='#b8beaf')
    for i,(name,im) in enumerate(items.items()):
        x=72+i*584
        bg=checker((384,384))
        bg.alpha_composite(im.resize((384,384),Image.Resampling.NEAREST))
        sheet.alpha_composite(bg,(x+26,150))
        label(sheet,(x+24,559),'日志条 / 取出的小票' if i==0 else '替换纸卷 / 未打印耗材',27)
        label(sheet,(x+24,605),'一处轻弯、两侧收紧、保留纸张厚薄' if i==0 else '斜向圆柱、紧实卷层、短翘纸尾',20,'#717a6a')
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
