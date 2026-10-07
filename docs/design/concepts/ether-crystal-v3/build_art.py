"""Pixel-grid delivery of the generated aether crystal concept. No game changes."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
TEX = HERE / 'assets/distantstock/textures/item'
TEX.mkdir(parents=True, exist_ok=True)
source = Image.open(HERE / 'design_source.png').convert('RGBA')
detail = source.resize((32, 32), Image.Resampling.NEAREST)
native = source.resize((16, 16), Image.Resampling.NEAREST)

# Keep the crystal's internal diamond and sharp silhouette at native item size.
adjustments = {
    (12, 1): (196, 240, 253, 245),
    (12, 2): (137, 211, 248, 250),
    (12, 4): (35, 91, 163, 250),
    (11, 7): (49, 109, 183, 250),
    (8, 7): (151, 227, 249, 235),
    (8, 8): (244, 254, 255, 255),
    (9, 8): (160, 230, 249, 240),
    (8, 9): (77, 152, 206, 240),
    (5, 13): (0, 0, 0, 0),
    (6, 13): (130, 171, 235, 250),
}
for xy, rgba in adjustments.items():
    native.putpixel(xy, rgba)
native.save(TEX / 'ether_crystal.png')
detail.save(TEX / 'ether_crystal_32.png')
assert native.size == (16, 16) and detail.size == (32, 32)
assert native.getpixel((0, 0))[3] == detail.getpixel((0, 0))[3] == 0

font_path = '/System/Library/Fonts/STHeiti Medium.ttc'
def label(im, xy, value, size=20, fill='#c7dce5'):
    ImageDraw.Draw(im).text(xy, value, font=ImageFont.truetype(font_path, size), fill=fill)

sheet = Image.new('RGBA', (960, 560), '#202e3b')
label(sheet, (38, 24), '以太晶体 · 冰蓝折光', 30, '#ecf8ff')
label(sheet, (40, 70), '锐棱 / 透光切面 / 内嵌晶核', 18, '#92b3c4')
for x, sprite, title in ((48, native, '16 × 16 · 原生物品'),
                         (528, detail, '32 × 32 · 精细备选')):
    sheet.alpha_composite(sprite.resize((352,352), Image.Resampling.NEAREST), (x,125))
    label(sheet, (x+55, 493), title, 21)
sheet.save(HERE / 'preview.png')
for name, sprite in (('native', native), ('detail', detail)):
    pic = Image.new('RGBA',(480,480),'#202e3b')
    pic.alpha_composite(sprite.resize((416,416),Image.Resampling.NEAREST),(32,32))
    pic.save(HERE / (name+'_preview.png'))
report = {name: {'size': list(sprite.size), 'visible_pixels': sum(a>0 for a in sprite.getchannel('A').getdata())}
          for name,sprite in (('native',native),('detail',detail))}
(HERE / 'checks.json').write_text(json.dumps(report,indent=2)+'\n')
print(HERE / 'preview.png')
