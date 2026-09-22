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
    # A broad, tilted prism: a pointed cap, three long faces, and a bevelled foot.
    shape=[(8,1),(11,2),(13,5),(12,10),(9,13),(5,14),(2,11),(2,7),(4,3)]
    d.polygon(shape,fill=(164,211,235,94))
    # Upper cut planes catch light differently, forming a proper pointed cap.
    d.polygon([(8,1),(11,2),(9,5),(5,6),(4,3)],fill=(216,239,249,126))
    d.polygon([(11,2),(13,5),(10,6),(9,5)],fill=(127,184,220,146))
    d.polygon([(4,3),(5,6),(3,10),(2,11),(2,7)],fill=(191,226,243,102))
    # Main panes remain translucent, with enough contrast to show thickness.
    d.polygon([(5,6),(9,5),(8,10),(5,12),(3,10)],fill=(205,233,247,77))
    d.polygon([(9,5),(10,6),(13,5),(12,10),(9,13),(8,10)],fill=(122,181,218,132))
    d.polygon([(3,10),(5,12),(5,14),(2,11)],fill=(149,203,230,132))
    d.polygon([(5,12),(8,10),(9,13),(5,14)],fill=(184,220,240,112))
    # Rear edges refract through the long front pane; they do not become cracks.
    d.line([(7,4),(6,7),(6,9)],fill=(132,189,221,110))
    d.line([(6,9),(9,10),(11,8)],fill=(164,212,238,156))
    # Selective rim lighting: bright upper-left, blue lower-right, no black line.
    d.line([(8,1),(11,2),(13,5),(12,10)],fill=(92,158,198,218))
    d.line([(12,10),(9,13),(5,14),(2,11)],fill=(96,166,207,218))
    d.line([(2,11),(2,7),(4,3),(8,1)],fill=(166,213,238,211))
    d.line([(4,3),(5,6),(9,5),(11,2)],fill=(225,243,252,205))
    d.line([(9,5),(8,10),(5,12)],fill=(200,232,248,192))
    d.line([(8,10),(9,13)],fill=(142,198,227,187))
    # Sparse hard reflections and internal color shifts; all at native texel size.
    d.line([(5,3),(7,2),(8,2)],fill=(248,253,255,255))
    d.line((3,7,3,9),fill=(235,248,254,240))
    d.point((4,6),fill=(211,237,250,230))
    d.line((6,6,7,6),fill=(232,246,253,180))
    d.point((8,7),fill=(182,220,242,110))
    d.point((10,7),fill=(164,211,239,151))
    d.point((10,8),fill=(185,222,243,173))
    d.line((6,12,7,12),fill=(227,243,252,219))
    d.point((10,11),fill=(198,232,249,213))
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
    label(sheet,(38,26),'淡蓝透明晶体 V2 / 切面与折射',34)
    label(sheet,(40,80),'16×16 · 尖端切面 · 立体棱柱 · 背面棱线与局部反光',21,'#717a6a')
    sheet.alpha_composite(preview,(80,145))
    for i,s in enumerate(('浅色背景','深色背景','透光检查 / 棋盘格')):
        label(sheet,(110+i*320,490),s,22)
    for j,scale in enumerate((1,2,3)):
        x=140+j*110;y=572
        tile=panel('#52656b',(16*scale+12,16*scale+12))
        tile.alpha_composite(im.resize((16*scale,16*scale),Image.Resampling.NEAREST),(6,6))
        sheet.alpha_composite(tile,(x,y))
    label(sheet,(570,590),'主切面约 30–57% 不透明，保留内部透光。',21,'#717a6a')
    label(sheet,(40,686),'此图为透明度合成检查；交付原图为透明 PNG，预览背景未写入贴图。',18,'#717a6a')
    sheet.save(HERE/'ether_crystal_design_sheet.png')
    print('Generated 16x16 translucent RGBA crystal; alpha values:',sorted(values))


if __name__=='__main__': main()
