from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
HERE=Path(__file__).resolve().parent
TEX=HERE/'assets/distantstock/textures/item';TEX.mkdir(parents=True,exist_ok=True)

def crystal():
 im=Image.new('RGBA',(16,16));d=ImageDraw.Draw(im)
 # Short optical crystal with three broad parallel side facets and a cut cap.
 d.polygon([(4,3),(7,6),(5,13),(2,10)],fill=(145,215,235,235))
 d.polygon([(7,6),(10,6),(8,13),(5,13)],fill=(177,225,245,162))
 d.polygon([(10,6),(13,4),(11,11),(8,13)],fill=(76,142,178,234))
 d.polygon([(7,1),(10,1),(13,4),(10,6),(7,6),(4,3)],fill=(204,238,248,226))
 d.polygon([(10,1),(13,4),(10,6),(8,4)],fill=(133,197,224,239))
 d.line([(7,1),(10,1),(13,4),(11,11),(8,13),(5,13),(2,10)],fill=(61,116,148,250))
 d.line([(2,10),(4,3),(7,1)],fill=(170,226,240,244))
 d.line([(4,3),(7,6),(10,6),(13,4)],fill=(235,249,251,250))
 d.line([(7,6),(5,13)],fill=(190,235,247,245))
 d.line([(10,6),(8,13)],fill=(110,178,209,242))
 d.line([(7,1),(9,1)],fill=(166,216,234,244))
 d.line([(5,3),(7,2),(8,2)],fill=(248,253,250,255))
 d.line([(3,8),(3,9)],fill=(230,247,250,248))
 # Visible rear edge, kept faint within the single transmission window.
 d.line([(8,7),(7,10)],fill=(139,199,224,162))
 d.line([(6,12),(7,12)],fill=(199,230,246,224))
 d.point((11,7),fill=(165,220,242,244))
 d.point((10,10),fill=(132,196,224,240))
 return im
im=crystal();im.save(TEX/'ether_crystal.png')
font='/System/Library/Fonts/STHeiti Medium.ttc'
sheet=Image.new('RGBA',(760,440),'#e9e6de');d=ImageDraw.Draw(sheet)
d.text((28,18),'以太晶体 · 切面晶柱',font=ImageFont.truetype(font,24),fill='#405361')
for i,(bg,label) in enumerate((('#d6ddd9','浅色背景'),('#304550','深色背景'))):
 tile=Image.new('RGBA',(344,320),bg);tile.alpha_composite(im.resize((288,288),Image.Resampling.NEAREST),(28,8))
 tile.alpha_composite(im.resize((32,32),Image.Resampling.NEAREST),(298,275));sheet.alpha_composite(tile,(24+i*368,68))
 d.text((142+i*368,398),label,font=ImageFont.truetype(font,18),fill='#566972')
sheet.save(HERE/'preview.png')
assert im.size==(16,16)
print(HERE/'preview.png')
