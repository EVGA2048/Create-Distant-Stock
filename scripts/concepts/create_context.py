#!/usr/bin/env python3
"""Read local Create models for concept context. Never edits the mod or client."""
from __future__ import annotations

import argparse
import io
import json
import os
import zipfile
from functools import lru_cache
from pathlib import Path

from PIL import Image
from render_scene import ROOT, BG, box, load_minecraft_model, move, render, rotation, header, footer, text

DEFAULT_CREATE = Path.home() / 'Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar'
OUT=ROOT/'build/art/concepts'


class CreateReferences:
    def __init__(self,path=None):
        self.path=Path(path or os.environ.get('CREATE_JAR',DEFAULT_CREATE))
        self.archive=zipfile.ZipFile(self.path)

    @lru_cache(None)
    def model(self,name):
        namespace,path=(name.split(':',1) if ':' in name else ('minecraft',name))
        if namespace=='minecraft':
            if path in ('block/block','block/base','block/thin_block'):
                return {}
            if path in ('block/cube','block/cube_all','block/orientable','block/orientable_with_bottom','block/cube_bottom_top','block/cube_column','block/cube_column_horizontal'):
                faces={side:{'texture':'#'+side,'uv':[0,0,16,16]} for side in ('north','south','east','west','up','down')}
                textures={}
                if path=='block/cube_all':
                    textures={side:'#all' for side in faces}
                elif path in ('block/cube_column','block/cube_column_horizontal'):
                    textures={side:('#end' if side in ('up','down') else '#side') for side in faces}
                elif path=='block/cube_bottom_top':
                    textures={side:('#top' if side=='up' else '#bottom' if side=='down' else '#side') for side in faces}
                elif path.startswith('block/orientable'):
                    textures={side:('#front' if side=='north' else '#top' if side=='up' else ('#bottom' if path.endswith('bottom') else '#top') if side=='down' else '#side') for side in faces}
                return {'textures':textures,'elements':[{'from':[0,0,0],'to':[16,16,16],'faces':faces}]}
            raise ValueError('Unsupported vanilla parent '+name)
        data=json.loads(self.archive.read(f'assets/{namespace}/models/{path}.json'))
        if data.get('parent'):
            parent=self.model(data['parent'])
            data={**parent,**data,'textures':{**parent.get('textures',{}),**data.get('textures',{})}}
        return data

    @lru_cache(None)
    def texture(self,name):
        namespace,path=(name.split(':',1) if ':' in name else ('minecraft',name))
        return Image.open(io.BytesIO(self.archive.read(f'assets/{namespace}/textures/{path}.png'))).convert('RGBA')

    def block(self,block,wanted=None,offset=(0,0,0)):
        states=json.loads(self.archive.read(f'assets/create/blockstates/{block}.json'))
        wanted=wanted or {}
        variants=states.get('variants',{})
        if not variants:
            raise ValueError('Multipart block needs explicit model: '+block)
        best=None; score=-1
        for key,value in variants.items():
            props=dict(p.split('=') for p in key.split(',') if '=' in p)
            s=sum(props.get(k)==str(v).lower() for k,v in wanted.items())
            if s>score:
                best=value[0] if isinstance(value,list) else value
                score=s
        # Create's block-only model omits its BE-rendered tray and hatch.
        # The bundled static item model includes those parts at the idle pose.
        source='create:block/packager/item' if block=='packager' else best['model']
        mesh,tex=load_minecraft_model(self.model(source),self.texture)
        turns=[]
        if best.get('x'):
            turns.append(('x',best['x'],(8,8,8)))
        if best.get('y'):
            turns.append(('y',-best['y'],(8,8,8)))
        return move(mesh,turns,offset),tex,best


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--create-jar',type=Path)
    args=ap.parse_args()
    refs=CreateReferences(args.create_jar)
    print('Belt models:', [n for n in refs.archive.namelist() if n.startswith('assets/create/models/block/belt/')])
    OUT.mkdir(parents=True,exist_ok=True)
    sheet=Image.new('RGBA',(1200,570),BG)
    header(sheet,'CREATE / MATERIAL AND SCALE REFERENCES','Actual installed Create 6.0.10 model resources / no textures copied into production assets')
    for i,(name,props) in enumerate([
        ('packager',{'facing':'north','powered':False,'linked':False}),
        ('andesite_funnel',{'facing':'north','extracting':False}),
        ('brass_casing',{}),
        ('gearbox',{'axis':'y'}),
    ]):
        mesh,tex,variant=refs.block(name,props)
        print(name,variant,'faces',len(mesh))
        sheet.alpha_composite(render(mesh,tex,(290,335),32,24,(8,8,8),11),(i*295+10,112))
        text(sheet,(i*295+20,467),name.upper().replace('_',' '),18)
    footer(sheet)
    sheet.save(OUT/'create-references.png')
    print(OUT/'create-references.png')


if __name__=='__main__':
    main()
