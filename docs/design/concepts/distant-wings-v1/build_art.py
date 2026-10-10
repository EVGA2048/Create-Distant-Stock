"""Mechanical elytra preview, WITH the vanilla model transforms applied."""
import sys, math
sys.path.insert(0, '/Users/xx2005/Documents/git_repository/DistantStock/scripts/concepts')
from PIL import Image, ImageDraw, ImageFont
from render_scene import box, render, move

FRAME, BLADE, JOINT, EDGE = 'frame', 'blade', 'joint', 'edge'
W, L, D = 10.0, 20.0, 2.0

def wing_shape():
    """Segmented plates tiling vanilla's 10x20 footprint."""
    m = []
    n = 4
    for i in range(n):
        y1, y0 = -L * i / n, -L * (i + 1) / n
        taper = 1.0 - 0.16 * i
        m.extend(box((-W * taper, y0, 0.0), (0.0, y1, D), BLADE if i < 3 else EDGE))
    m.extend(box((-W, -L, -0.5), (-W + 1.5, 0.0, D + 0.5), FRAME))      # leading-edge spar
    m.extend(box((-2.6, -2.4, -0.8), (1.2, 2.4, D + 0.8), FRAME))       # hinge housing
    m.extend(box((-1.8, -1.6, -1.4), (0.6, 1.6, D + 1.4), JOINT))
    return m

def placed(glide):
    """Vanilla ElytraModel: offsetAndRotation(+-5,0,0, pi/12, 0, -+pi/12), then setupAnim."""
    l = wing_shape()
    r = [dict(f) for f in l]
    r = [{**f, 'points': [[-p[0], p[1], p[2]] for p in f['points']]} for f in r]
    # setupAnim: xRot eases 15deg -> ~22 in flight; zRot swings -15deg -> -62
    xrot = 15.0 + 7.0 * glide
    zrot = 15.0 + 47.0 * glide
    out = []
    for side, sign in (('left', -1), ('right', 1)):
        mesh = l if sign < 0 else r
        mesh = move(mesh, turns=[('z', sign * zrot, (0, 0, 0)), ('x', xrot, (0, 0, 0))],
                    offset=(sign * 5.0, 0, 0))
        out.extend(mesh)
    return out

TEX = {FRAME: Image.new('RGBA',(8,8),'#35536e'),
       JOINT: Image.new('RGBA',(8,8),'#8dbbd6'),
       EDGE:  Image.new('RGBA',(8,8),'#d9ebee'),
       BLADE: Image.new('RGBA',(8,8),'#b6d8e8')}
BG='#e6e3db'; pics=[]
for label, glide, yaw, pitch in (('收起',0.0,-40,18),('滑翔',1.0,-40,18),
                                 ('滑翔·后',1.0,150,18),('收起·后',0.0,150,18)):
    pics.append((render(placed(glide), TEX, size=(430,540), yaw=yaw, pitch=pitch,
                        center=(0,-9,0), scale=13, background=BG), label))
Wt=sum(p.width for p,_ in pics); H=pics[0][0].height+40
sh=Image.new('RGBA',(Wt,H),BG); d=ImageDraw.Draw(sh)
f=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',20); x=0
for p,l in pics: sh.alpha_composite(p,(x,0)); d.text((x+155,H-32),l,font=f,fill='#415f6b'); x+=p.width
sh.save('/tmp/wings/preview.png'); print('ok')
