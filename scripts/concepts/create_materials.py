#!/usr/bin/env python3
"""Extract installed Create materials for isolated concept artwork only."""
import io
import json
import os
import zipfile
from pathlib import Path
from functools import lru_cache
from PIL import Image, ImageDraw, ImageEnhance, ImageOps

# Crops use source pixels; only the tower's painted trims are tone-adjusted.
MATERIALS = {
    'andesite': {'source': 'andesite_block', 'crop': [0, 0, 16, 16]},
    'wood': {'source': 'andesite_casing', 'crop': [3, 3, 13, 13]},
    'casing': {'source': 'andesite_casing', 'crop': [0, 0, 16, 16]},
    'iron': {'source': 'industrial_iron_block', 'crop': [0, 0, 16, 16]},
    'cast_white': {'source': 'palettes/stone_types/polished/andesite_cut_polished',
                   'crop': [0, 0, 16, 16], 'brightness': 1.22},
    'blue_gray': {'source': 'industrial_iron_block', 'crop': [0, 0, 16, 16],
                  'tint': ['#344854', '#B5CCD4']},
}


def material(name):
    spec = MATERIALS[name]
    image = source(spec['source']).crop(spec['crop']).copy()
    if 'brightness' in spec:
        image = ImageEnhance.Brightness(image).enhance(spec['brightness'])
    if 'tint' in spec:
        image = ImageOps.colorize(ImageOps.grayscale(image), *spec['tint']).convert('RGBA')
    return image


def tiled(name, size=(16, 16)):
    tile = material(name)
    image = Image.new('RGBA', size)
    for y in range(0, size[1], tile.height):
        for x in range(0, size[0], tile.width):
            image.paste(tile, (x, y))
    return image

ROOT=Path(__file__).resolve().parents[2]
DEFAULT=Path.home()/'Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar'

@lru_cache(None)
def source(name):
    with zipfile.ZipFile(os.environ.get('CREATE_JAR',str(DEFAULT))) as z:
        return Image.open(io.BytesIO(z.read('assets/create/textures/block/'+name+'.png'))).convert('RGBA')

def main():
    out=ROOT/'build/art/concepts';out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(os.environ.get('CREATE_JAR',str(DEFAULT))) as z:
        names=[n for n in z.namelist() if n.startswith('assets/create/textures/block/') and n.endswith('.png') and any(s in n for s in ('andesite','casing','shaft','industrial_iron','fluid_tank','metal_girder'))]
    print('\n'.join(names))
    choices=['andesite_casing','andesite_block','palettes/stone_types/polished/andesite_cut_polished','industrial_iron_block','brass_casing','copper_casing','fluid_tank','scaffold/andesite_scaffold']
    images=[]
    for n in choices:
        try: images.append((n,source(n)))
        except KeyError: pass
    sheet=Image.new('RGB',(1200,((len(images)+3)//4)*330),'#e9e5da');d=ImageDraw.Draw(sheet)
    for i,(n,im) in enumerate(images):
        x=i%4*300;y=i//4*330
        d.text((x+10,y+10),n,fill='#303c42')
        tile=im.crop((0,0,16,16))
        sheet.paste(tile.resize((256,256),Image.Resampling.NEAREST),(x+10,y+40))
    sheet.save(out/'create-material-atlases.png')
    print(out/'create-material-atlases.png')
if __name__=='__main__':main()
