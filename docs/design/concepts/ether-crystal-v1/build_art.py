"""A native 16x16 pale-blue crystal, with real translucent facet pixels."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ASSETS=HERE/'assets/distantstock'
TEX=ASSETS/'textures/item'
MODELS=ASSETS/'models/item'
for p in (TEX,MODELS): p.mkdir(parents=True,exist_ok=True)


def crystal():
    im=Image.new('RGBA',(16,16))
    d=ImageDraw.Draw(im)
    silhouette=[(8,1),(12,5),(11,11),(6,14),(3,10),(4,5)]
    d.polygon(silhouette,fill=(182,216,232,56))
    d.polygon([(8,1),(12,5),(8,6),(4,5)],fill=(217,235,238,80))
    d.polygon([(4,5),(8,6),(6,14),(3,10)],fill=(198,227,241,44))
    d.polygon([(8,6),(12,5),(11,11),(6,14)],fill=(141,187,214,100))
    # Hard single-pixel ridges, with darker edges restricted to the far side.
    d.line([(8,1),(12,5),(11,11),(6,14)],fill=(105,157,188,208))
    d.line([(6,14),(3,10),(4,5),(8,1)],fill=(182,216,232,192))
    d.line([(4,5),(8,6),(12,5)],fill=(198,227,241,156))
    d.line([(8,6),(6,14)],fill=(141,187,214,148))
    d.line([(8,1),(8,3)],fill=(217,235,238,120))
    # Short reflections only; the centre remains visibly transparent.
    d.line((6,3,7,2),fill=(244,250,253,245))
    d.line((4,6,4,8),fill=(234,245,250,226))
    d.point((8,6),fill=(244,250,253,218))
    d.point((7,12),fill=(217,235,238,170))
    return im


def label(im,xy,text,size=22,color='#344139'):
    ImageDraw.Draw(im).text(xy,text,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=color)


def panel(bg,size=(320,320),checker=False):
    im=Image.new('RGBA',size,bg)
    if checker:
        d=ImageDraw.Draw(im)
        for y in range(0,size[1],20):
            for x in range(0,size[0],20):
                if (x//20+y//20)%2: d.rectangle((x,y,x+19,y+19),fill='#c0c9c5')
    return im


def main():
    im=crystal()
    im.save(TEX/'ether_crystal.png')
    (MODELS/'ether_crystal.json').write_text(json.dumps({
        'parent':'minecraft:item/generated','render_type':'minecraft:translucent',
        'textures':{'layer0':'distantstock:item/ether_crystal'}
    },indent=2)+'\n')
    assert im.size==(16,16)
    values=set(im.getchannel('A').getdata())
    assert 0 in values and any(0<a<128 for a in values)
    preview=Image.new('RGBA',(960,320))
    for i,bg in enumerate(('#dce2dd','#38494f','#d6ddda')):
        tile=panel(bg,checker=i==2)
        tile.alpha_composite(im.resize((256,256),Image.Resampling.NEAREST),(32,32))
        preview.alpha_composite(tile,(i*320,0))
    preview.save(HERE/'ether_crystal_preview.png')
    sheet=Image.new('RGBA',(1120,730),'#e9e8df')
    label(sheet,(38,26),'淡蓝透明晶体 / 像素试稿',34)
    label(sheet,(40,80),'16×16 · 单像素棱线 · 淡蓝透光切面 · 无金属外壳',21,'#717a6a')
    sheet.alpha_composite(preview,(80,145))
    for i,s in enumerate(('浅色背景','深色背景','透光检查 / 棋盘格')):
        label(sheet,(110+i*320,490),s,22)
    for j,scale in enumerate((1,2,3)):
        x=140+j*110;y=572
        tile=panel('#52656b',(16*scale+12,16*scale+12))
        tile.alpha_composite(im.resize((16*scale,16*scale),Image.Resampling.NEAREST),(6,6))
        sheet.alpha_composite(tile,(x,y))
    label(sheet,(570,590),'主切面不透明度约 17–39%，棱线更清楚。',21,'#717a6a')
    label(sheet,(40,686),'此图为透明度合成检查；交付原图为透明 PNG，预览背景未写入贴图。',18,'#717a6a')
    sheet.save(HERE/'ether_crystal_design_sheet.png')
    print('Generated 16x16 translucent RGBA crystal; alpha values:',sorted(values))


if __name__=='__main__': main()
