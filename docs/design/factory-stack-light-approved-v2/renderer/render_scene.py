#!/usr/bin/env python3
"""Small orthographic, nearest-textured concept renderer (not a game renderer).

Coordinates are Minecraft model pixels, 16 per block. Meshes may have animated
rigid transforms; these are design sources, not directly loadable block JSON.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
BG = '#e9e5da'
INK = '#353e3c'
MUTED = '#64706b'
BRASS = '#b78b45'
CYAN = '#63bbd0'


def font(size=18):
    for path in ('/System/Library/Fonts/Supplemental/Arial.ttf',
                 '/System/Library/Fonts/Helvetica.ttc'):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return ImageFont.load_default()


def text(im, xy, value, size=18, fill=INK):
    ImageDraw.Draw(im).text(xy, value, font=font(size), fill=fill)


def header(im, title, subtitle):
    text(im, (30, 22), title, 27)
    text(im, (31, 59), subtitle, 15, MUTED)
    ImageDraw.Draw(im).line((30, 88, im.width-30, 88), fill='#b7b8ad', width=1)


def footer(im, extra=''):
    text(im, (30, im.height-27), 'DISTANT STOCK / CONCEPT 01 / OFFLINE MODEL PREVIEW - NOT A GAME SCREENSHOT' + extra, 12, MUTED)


def rotation(axis, angle):
    t = math.radians(angle)
    c, s = math.cos(t), math.sin(t)
    if axis == 'x':
        return np.array(((1,0,0),(0,c,-s),(0,s,c)), dtype=float)
    if axis == 'y':
        return np.array(((c,0,s),(0,1,0),(-s,0,c)), dtype=float)
    return np.array(((c,-s,0),(s,c,0),(0,0,1)), dtype=float)


def transform(points, turns=(), offset=(0,0,0)):
    p = np.array(points, dtype=float)
    for axis, angle, origin in turns:
        o = np.array(origin)
        p = (p-o) @ rotation(axis, angle).T + o
    return p + np.array(offset)


def face(points, material, uv=None, group='fixed'):
    p = np.array(points, dtype=float)
    if uv is None:
        width = max(1, np.linalg.norm(p[1]-p[0]))
        height = max(1, np.linalg.norm(p[3]-p[0]))
        uv = [[0,0],[width,0],[width,height],[0,height]]
    return {'points': p.tolist(), 'uv': uv, 'material': material, 'group': group}


def box(lo, hi, material, group='fixed', turns=(), offset=(0,0,0), faces=None, strict=True):
    x0,y0,z0 = lo
    x1,y1,z1 = hi
    if strict:
        assert x1>x0 and y1>y0 and z1>z0, (lo,hi)
    pts = {
        'north': [(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],
        'south': [(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],
        'west': [(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],
        'east': [(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],
        'up': [(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],
        'down': [(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)],
    }
    result = []
    for name, ps in pts.items():
        result.append(face(transform(ps,turns,offset), (faces or {}).get(name,material), group=group))
    return result


def move(mesh, turns=(), offset=(0,0,0)):
    return [{**f, 'points':transform(f['points'],turns,offset).tolist()} for f in mesh]


def ring(center, radius, width, depth, material, segments=12, group='fixed', angle=0):
    """Faceted annular prism in XY, never a smooth torus."""
    cx,cy,cz = center
    mesh = []
    for i in range(segments):
        a,b = [math.radians(angle + n*360/segments) for n in (i,i+1)]
        def p(r,t,z):
            return [cx+r*math.cos(t),cy+r*math.sin(t),cz+z]
        ro,ri = radius,radius-width
        z0,z1 = -depth/2,depth/2
        quads = [
            [p(ro,b,z0),p(ro,a,z0),p(ri,a,z0),p(ri,b,z0)],
            [p(ro,a,z1),p(ro,b,z1),p(ri,b,z1),p(ri,a,z1)],
            [p(ro,a,z0),p(ro,b,z0),p(ro,b,z1),p(ro,a,z1)],
            [p(ri,b,z0),p(ri,a,z0),p(ri,a,z1),p(ri,b,z1)],
        ]
        for ps in quads:
            mesh.append(face(ps,material,group=group))
    return mesh


def crystal(center, width=7, height=17, material='crystal'):
    cx,cy,cz = center
    equator = [(cx+width/2,cy,cz),(cx,cy,cz+width/2),
               (cx-width/2,cy,cz),(cx,cy,cz-width/2)]
    result=[]
    for i in range(4):
        a,b=equator[i],equator[(i+1)%4]
        # Degenerate fourth vertex represents a triangle.
        result.append(face([a,[cx,cy+height/2,cz],b,b],material,[[0,8],[4,0],[8,8],[8,8]],'core'))
        result.append(face([b,[cx,cy-height/2,cz],a,a],material,[[0,8],[4,16],[8,8],[8,8]],'core'))
    return result


def render(mesh, textures, size=(800,900), yaw=32, pitch=18,
           center=(0,50,0), scale=6, background=BG):
    """Opaque/cutout surfaces, directional shading and a depth buffer."""
    w,h=size
    rgb=np.empty((h,w,4),dtype=np.uint8)
    rgb[:]=Image.new('RGBA',(1,1),background).getpixel((0,0))
    depth=np.full((h,w), np.inf)
    camera=rotation('x',-pitch) @ rotation('y',yaw)
    mats={k:np.array(v.convert('RGBA')) for k,v in textures.items()}
    light=np.array((-.4,.8,-.7)); light/=np.linalg.norm(light)
    translucent={key for key,tile in mats.items() if np.any((tile[:,:,3]>0)&(tile[:,:,3]<255))}
    opaque=[f for f in mesh if f['material'] not in translucent]
    transparent=[f for f in mesh if f['material'] in translucent]
    # Convex glass shells and beam layers are composited back to front after
    # opaque depth, rather than being mistaken for solid cyan surfaces.
    transparent.sort(key=lambda f: float((np.mean(f['points'],axis=0)-center) @ camera[2]), reverse=True)
    for f in opaque+transparent:
        blend=f['material'] in translucent
        world=np.asarray(f['points'],dtype=float)
        normal=np.cross(world[1]-world[0],world[2]-world[0])
        length=np.linalg.norm(normal)
        if length<1e-7:
            continue
        # Minecraft JSON face winding points inward in world coordinates.
        normal/=-length
        cam=(world-np.array(center)) @ camera.T
        projected=np.column_stack((w/2+cam[:,0]*scale,h/2-cam[:,1]*scale,cam[:,2]))
        cross=np.cross(projected[1,:2]-projected[0,:2],projected[2,:2]-projected[0,:2])
        if cross>=0:
            continue
        # Winding normal is outward for Minecraft box faces.
        shade=.70+.30*max(0,float(np.dot(normal,light)))
        if f.get('emissive'):
            shade=1
        tile=mats[f['material']]
        uv=np.asarray(f['uv'],float)
        for indices in ((0,1,2),(0,2,3)):
            a,b,c=projected[list(indices)]
            x0=max(0,math.floor(min(a[0],b[0],c[0])))
            x1=min(w,math.ceil(max(a[0],b[0],c[0])))
            y0=max(0,math.floor(min(a[1],b[1],c[1])))
            y1=min(h,math.ceil(max(a[1],b[1],c[1])))
            if x0>=x1 or y0>=y1:
                continue
            den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den)<1e-7:
                continue
            yy,xx=np.mgrid[y0:y1,x0:x1]
            xx=xx+.5; yy=yy+.5
            wa=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
            wb=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den
            wc=1-wa-wb
            z=wa*a[2]+wb*b[2]+wc*c[2]
            visible=(wa>=-1e-7)&(wb>=-1e-7)&(wc>=-1e-7)&(z<depth[y0:y1,x0:x1])
            if blend and indices==(0,2,3):
                visible &= wc>1e-7
            if not visible.any():
                continue
            ua,ub,uc=uv[list(indices)]
            u=wa*ua[0]+wb*ub[0]+wc*uc[0]
            v=wa*ua[1]+wb*ub[1]+wc*uc[1]
            if f.get('clamp'):
                tx=np.clip(np.floor(u*tile.shape[1]/16).astype(int),0,tile.shape[1]-1)
                ty=np.clip(np.floor(v*tile.shape[0]/16).astype(int),0,tile.shape[0]-1)
            else:
                tx=np.floor(u).astype(int)%tile.shape[1]
                ty=np.floor(v).astype(int)%tile.shape[0]
            colors=tile[ty,tx].copy()
            visible &= colors[:,:,3]>0
            colors[:,:,:3]=(colors[:,:,:3].astype(float)*shade).astype(np.uint8)
            target=rgb[y0:y1,x0:x1]
            if blend:
                alpha=colors[:,:,3:4].astype(float)/255
                combined=colors.copy()
                combined[:,:,:3]=(colors[:,:,:3]*alpha+target[:,:,:3]*(1-alpha)).astype(np.uint8)
                combined[:,:,3]=255
                target[visible]=combined[visible]
            else:
                target[visible]=colors[visible]
                depth[y0:y1,x0:x1][visible]=z[visible]
    return Image.fromarray(rgb,'RGBA')


def ground(extent=48, y=-1):
    return box((-extent,y-2,-extent),(extent,y,extent),'floor')


def load_minecraft_model(data, texture_loader, parent_loader=None, offset=(0,0,0)):
    """Load ordinary box models for honest side-by-side production references."""
    if 'parent' in data and parent_loader:
        parent=parent_loader(data['parent'])
        data={**parent,**data,'textures':{**parent.get('textures',{}),**data.get('textures',{})}}
    out=[]; textures={}
    for e in data.get('elements',[]):
        parts=box(e['from'],e['to'],'unused',strict=False)
        for name,f in zip(('north','south','west','east','up','down'),parts):
            if name not in e.get('faces',{}):
                continue
            definition=e['faces'][name]
            ref=definition['texture']; visited=set()
            while ref.startswith('#'):
                if ref in visited:
                    raise ValueError('Texture cycle')
                visited.add(ref); ref=data['textures'][ref[1:]]
            textures[ref]=texture_loader(ref)
            u0,v0,u1,v1=definition.get('uv',[0,0,16,16])
            f['uv']=[[u0,v0],[u1,v0],[u1,v1],[u0,v1]]
            turns=(definition.get('rotation',0)//90)%4
            if turns:
                f['uv']=f['uv'][turns:]+f['uv'][:turns]
            f.update(material=ref,clamp=True)
            if e.get('rotation'):
                r=e['rotation']
                p=np.asarray(f['points'],float)-r['origin']
                if r.get('rescale'):
                    for axis in range(3):
                        if axis!='xyz'.index(r['axis']):
                            p[:,axis]/=math.cos(math.radians(r['angle']))
                f['points']=(p @ rotation(r['axis'],r['angle']).T+r['origin']).tolist()
            f['points']=transform(f['points'],offset=offset).tolist()
            out.append(f)
    return out,textures
