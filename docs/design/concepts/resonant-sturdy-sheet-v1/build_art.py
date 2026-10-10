"""Recolour Create's sturdy sheet with the project's ether-quartz palette."""
from pathlib import Path
import sys
import json
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts/concepts'))
from create_context import CreateReferences

OUT=HERE/'assets/distantstock/textures/item'
OUT.mkdir(parents=True,exist_ok=True)
source=CreateReferences().texture('create:item/sturdy_sheet')
quartz=Image.open(ROOT/'src/main/resources/assets/distantstock/textures/item/ether_quartz.png').convert('RGBA')
source.save(HERE/'sturdy_sheet_reference.png')
quartz.save(HERE/'ether_quartz_reference.png')
# Preserve every contour, groove and highlight in the original sheet.
# Seven tones are copied from ether quartz; two bridge its existing blue steps.
palette={
 (3,5,12):(53,83,110),
 (11,13,21):(66,102,130),
 (25,27,36):(80,123,155),
 (42,42,55):(105,157,188),
 (56,56,70):(123,172,201),
 (67,66,81):(141,187,214),
 (82,81,97):(182,216,232),
 (112,110,130):(217,235,238),
 (142,141,161):(243,246,233),
}
item=Image.new('RGBA',source.size)
item.putdata([(*palette[(r,g,b)],a) if a else (r,g,b,a) for r,g,b,a in source.getdata()])
assert item.size==(16,16)
assert source.getchannel('A').tobytes()==item.getchannel('A').tobytes()
item.save(OUT/'resonant_sturdy_sheet.png')
preview=Image.new('RGBA',(384,384),'#e4e6e0')
preview.alpha_composite(item.resize((352,352),Image.Resampling.NEAREST),(16,16))
preview.save(HERE/'preview.png')
dark=Image.new('RGBA',(384,384),'#304550')
dark.alpha_composite(item.resize((352,352),Image.Resampling.NEAREST),(16,16))
dark.save(HERE/'preview_dark.png')
(HERE/'checks.json').write_text(json.dumps({'size':list(item.size),'alpha_matches_source':True,
 'visible_pixels':sum(a>0 for a in item.getchannel('A').getdata()),
 'source':'Create 6.0.10 assets/create/textures/item/sturdy_sheet.png',
 'production_resources_modified':False},indent=2)+'\n')
print(OUT/'resonant_sturdy_sheet.png')
