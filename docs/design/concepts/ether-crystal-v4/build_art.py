"""Native 16x16 pale-blue optical prism, drawn directly on the texel grid."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parent
TEX=HERE/'assets/distantstock/textures/item';TEX.mkdir(parents=True,exist_ok=True)

def prism():
 im=Image.new('RGBA',(16,16));d=ImageDraw.Draw(im)
 # Congruent triangular ends translated by (5,-3): a true triangular prism.
 a,b,c=(4,4),(1,13),(9,13)
 ar,br,cr=(9,1),(6,10),(14,10)
 # Broad glass faces retain real alpha, rather than painting an opaque gemstone.
 d.polygon([a,ar,cr,c],fill=(151,207,229,157))
 d.polygon([a,b,c],fill=(199,235,246,112))
 # One rear edge shows through the near faces, suggesting transparent volume.
 d.line([br,cr],fill=(108,174,202,94))
 d.line([ar,br],fill=(133,195,219,84))
 # Calm reflection plane, not a glowing centre or a scatter of highlights.
 d.polygon([(6,4),(9,2),(11,6),(8,8)],fill=(207,239,250,133))
 d.line([(3,8),(2,11)],fill=(226,245,250,178))
 # Selective outer contour and one clear long prism ridge.
 d.line([a,ar],fill=(214,243,251,238))
 d.line([ar,cr,c],fill=(90,151,178,228))
 d.line([a,b],fill=(132,191,210,220))
 d.line([b,c],fill=(99,161,183,228))
 d.line([a,c],fill=(175,218,237,213))
 # White is reserved for the upper edge. Lower bevel has a subdued icy glint.
 d.line([(5,3),(7,2)],fill=(240,252,255,250))
 d.point((4,4),fill=(224,246,252,246))
 d.line([(3,12),(5,12)],fill=(207,236,246,170))
 d.point((11,11),fill=(164,208,227,207))
 return im

im=prism();im.save(TEX/'ether_crystal.png')
assert im.size==(16,16)
assert im.getpixel((0,0))[3]==0
assert 80<im.getpixel((4,9))[3]<160
preview=Image.new('RGBA',(760,440),'#e9e6de')
font='/System/Library/Fonts/STHeiti Medium.ttc'
d=ImageDraw.Draw(preview)
d.text((28,18),'以太晶体 · 透明棱镜',font=ImageFont.truetype(font,24),fill='#405361')
for i,(bg,label) in enumerate((('#d6ddd9','浅色背景'),('#304550','深色背景'))):
 tile=Image.new('RGBA',(344,320),bg)
 tile.alpha_composite(im.resize((288,288),Image.Resampling.NEAREST),(28,8))
 # Native inventory scale, enlarged x2 without smoothing.
 tile.alpha_composite(im.resize((32,32),Image.Resampling.NEAREST),(298,275))
 preview.alpha_composite(tile,(24+i*368,68))
 d.text((142+i*368,398),label,font=ImageFont.truetype(font,18),fill='#566972')
preview.save(HERE/'preview.png')
# Checkerboard specifically verifies transparency through the broad glass face.
check=Image.new('RGBA',(320,320),'#cdd5d4');d=ImageDraw.Draw(check)
for y in range(0,320,20):
 for x in range(0,320,20):
  if (x//20+y//20)%2:d.rectangle((x,y,x+19,y+19),fill='#9fadae')
check.alpha_composite(im.resize((320,320),Image.Resampling.NEAREST))
check.save(HERE/'transparency_check.png')
(HERE/'checks.json').write_text(json.dumps({'size':[16,16],'alpha_values':sorted(set(im.getchannel('A').getdata())),'opaque_background':False,'game_integration':False},indent=2)+'\n')
print(HERE/'preview.png')
