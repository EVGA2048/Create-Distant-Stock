#!/usr/bin/env python3
"""Check physical handoffs, deterministic NBT and the storyboard/caption contract."""
import json
import re
import tempfile
from pathlib import Path
import gen_ponder_structures as structures

ROOT = structures.ROOT
JAVA = ROOT/'src/main/java/dev/distantstock/client/ponder'
LANG = {lang:json.loads((ROOT/f'src/main/resources/assets/distantstock/lang/{lang}.json').read_text())
        for lang in ('en_us','zh_cn')}
authored = structures.PONDER
count = 0
with tempfile.TemporaryDirectory() as tmp:
    structures.PONDER = Path(tmp)
    for path in JAVA.glob('*Scenes.java'):
        methods = re.split(r'public static void ',path.read_text())[1:]
        for method in methods:
            match = re.search(r'begin\(builder,"distant_(\w+)","([^"]+)",(\d+)\)',method)
            assert match, path
            name,title,width=match.groups()
            layout=structures.SCENES[name]()
            structures.verify(layout,name)
            assert layout.width==layout.depth==int(width), (name,'floor framing')
            layout.save(name+'.nbt')
            assert (structures.PONDER/(name+'.nbt')).read_bytes()==(authored/(name+'.nbt')).read_bytes(), (name,'stale NBT')
            occupied={tuple(b['pos']):layout.palette[b['state']] for b in layout.blocks}
            notes=re.findall(r'note\(scene,util,(\d+),(\d+),(\d+),"([^"]+)"\)',method)
            prefix=f'distantstock.ponder.distant_{name}.'
            assert LANG['en_us'][prefix+'header']==title,name
            for i,(x,y,z,text) in enumerate(notes,1):
                assert (int(x),int(y),int(z)) in occupied,(name,'caption points at air',x,y,z)
                assert LANG['en_us'][prefix+f'text_{i}']==text,(name,'English caption drift')
                assert LANG['zh_cn'][prefix+f'text_{i}'].strip(),(name,'missing Chinese caption')
            for lang,data in LANG.items():
                assert len([k for k in data if k.startswith(prefix+'text_')])==len(notes),(name,lang,'caption count')
            for b in layout.blocks:
                state=layout.palette[b['state']];x,y,z=b['pos']
                if state['Name']=='create:andesite_belt_funnel':
                    dx,dz={'north':(0,1),'west':(1,0)}[state['Properties']['facing']]
                    target=occupied.get((x+dx,y,z+dz),{}).get('Name','')
                    assert target in {'distantstock:remote_packager','distantstock:dock'},(name,'funnel missing inventory')
                if state['Name']=='minecraft:hopper':
                    dx,dy,dz={'down':(0,-1,0),'south':(0,0,1),'west':(-1,0,0)}[state['Properties']['facing']]
                    assert occupied.get((x+dx,y+dy,z+dz),{}).get('Name')=='minecraft:chest',(name,'hopper has no receiving inventory')
                if state['Name']=='distantstock:remote_gauge':
                    assert 'top_right' in b['nbt'],(name,'empty gauge')
                    assert state['Properties']['face']=='wall',name
            count+=1
assert count==len(structures.SCENES),(count,len(structures.SCENES))
print(f'Ponder verification PASSED: {count} storyboards, captions, bounds, physical handoffs and reproducible structures')
