#!/usr/bin/env python3
"""Preview-only beam/lamp/needle states; never ticks a Minecraft world."""
import math
import sys
sys.dont_write_bytecode=True
from PIL import Image
from render_scene import ROOT, BG, render, header, footer, text
from gen_tower import build, textures

OUT=ROOT/'build/art/concepts'


def frame(phase):
    mats=textures()
    # Animate indicator demonstration, not real RPM or tank simulation.
    online=.15<=phase<.85
    mesh,_=build(phase=phase,gap=3,powered=online)
    if not online:
        mats['cyan']=Image.new('RGBA',(16,16),'#506e86')
    im=Image.new('RGBA',(920,680),BG)
    header(im,'SIGNAL LINK / STATE STUDY','Illustrative beam, glass lamp and needle motion - no gameplay simulation')
    im.alpha_composite(render(mesh,mats,(410,510),30,12,(0,40,0),5.3),(0,110))
    base=[f for f in mesh if f.get('group')=='signal_base']
    im.alpha_composite(render(base,mats,(480,370),30,22,(0,8,0),17),(430,140))
    text(im,(490,505),'LINK ACTIVE' if online else 'LINK INACTIVE',21,'#347e96' if online else '#606f7e')
    text(im,(490,545),'Needle: illustrative aether storage sweep',14)
    text(im,(490,571),'Tower tier / RPM thresholds not implemented',14)
    footer(im)
    return im.convert('RGB')


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    frames=[frame(i/32) for i in range(32)]
    frames[0].save(OUT/'tower-state-study.gif',save_all=True,append_images=frames[1:],duration=100,loop=0,optimize=False,disposal=2)
    frames[12].save(OUT/'tower-state-study.png')
    with Image.open(OUT/'tower-state-study.gif') as test:
        assert test.n_frames==32
        for i in range(test.n_frames):
            test.seek(i); test.load()
    print('PASS 32 frames: glass shell, beam visibility and illustrative needle states')
    print(OUT/'tower-state-study.gif')


if __name__=='__main__':main()
