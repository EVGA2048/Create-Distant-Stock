#!/usr/bin/env python3
"""Validate shipped JSON/UVs and render actual baked-model geometry offline.

This is a nearest-sampled orthographic material preview, not a Minecraft screenshot.
No OpenGL context or running world is required.
"""
import io
import json
import math
import zipfile
from functools import lru_cache
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets'
OUT = ROOT / 'build/art'
CREATE = Path.home() / 'Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar'


@lru_cache(None)
def model(name):
    namespace, path = name.split(':')
    if namespace == 'minecraft':
        return {}
    if namespace == 'create':
        with zipfile.ZipFile(CREATE) as jar:
            data = json.loads(jar.read(f'assets/create/models/{path}.json'))
    else:
        data = json.loads((ASSETS / namespace / 'models' / (path + '.json')).read_text())
    parent = model(data['parent']) if 'parent' in data else {}
    return {**parent, **data, 'textures': {**parent.get('textures', {}), **data.get('textures', {})}}


@lru_cache(None)
def texture(name):
    namespace, path = name.split(':')
    if namespace == 'create':
        with zipfile.ZipFile(CREATE) as jar:
            im = Image.open(io.BytesIO(jar.read(f'assets/create/textures/{path}.png'))).convert('RGBA')
    else:
        im = Image.open(ASSETS / namespace / 'textures' / (path + '.png')).convert('RGBA')
    return im


def resolve(ref, textures):
    seen = set()
    while ref.startswith('#'):
        assert ref not in seen, 'Texture cycle'
        seen.add(ref)
        ref = textures[ref[1:]]
    return texture(ref)


def rotate(point, rotation):
    if not rotation:
        return point
    origin = rotation['origin']
    a = math.radians(rotation['angle'])
    p = [point[i]-origin[i] for i in range(3)]
    axis = 'xyz'.index(rotation['axis'])
    u,v = ((1,2),(2,0),(0,1))[axis]
    p[u],p[v] = math.cos(a)*p[u]-math.sin(a)*p[v], math.sin(a)*p[u]+math.cos(a)*p[v]
    return tuple(p[i]+origin[i] for i in range(3))


def render(name, yaw=30, pitch=25, size=(240,250), scale=9):
    obj = model(name)
    sy,cy = math.sin(math.radians(yaw)),math.cos(math.radians(yaw))
    sp,cp = math.sin(math.radians(pitch)),math.cos(math.radians(pitch))
    def project(p):
        x,y,z = p[0]-8,p[1]-8,p[2]-8
        xr,zr = cy*x+sy*z,-sy*x+cy*z
        yr,depth = cp*y+sp*zr,-sp*y+cp*zr
        return (size[0]/2+xr*scale,size[1]/2-yr*scale),depth
    im = Image.new('RGBA',size,'#e8e5dc')
    pix = im.load()
    depths = [math.inf]*(size[0]*size[1])
    for e in obj.get('elements',[]):
        x0,y0,z0 = e['from']; x1,y1,z1 = e['to']
        points = {
            'north':[(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],
            'south':[(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],
            'west':[(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],
            'east':[(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],
            'up':[(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],
            'down':[(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)]}
        for face,f in e['faces'].items():
            ps = [rotate(p,e.get('rotation')) for p in points[face]]
            projected = [project(p) for p in ps]
            a,b,c = [q[0] for q in projected[:3]]
            if (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]) >= 0:
                continue
            tex = resolve(f['texture'],obj['textures'])
            u0,v0,u1,v1 = f['uv']
            shade = {'north':1,'south':.85,'east':.82,'west':.85,'up':1,'down':.65}[face]
            # Rasterise whole face with perspective-free interpolation and a true depth buffer.
            for ids,coords in (((0,1,2),((0,0),(1,0),(1,1))),((0,2,3),((0,0),(1,1),(0,1)))):
                a,b,c = [projected[i] for i in ids]
                ax,ay = a[0]; bx,by = b[0]; cx,cy_ = c[0]
                den = (by-cy_)*(ax-cx)+(cx-bx)*(ay-cy_)
                if abs(den)<1e-8:
                    continue
                for y in range(max(0,math.floor(min(ay,by,cy_))),min(size[1],math.ceil(max(ay,by,cy_)))):
                    for x in range(max(0,math.floor(min(ax,bx,cx))),min(size[0],math.ceil(max(ax,bx,cx)))):
                        wa = ((by-cy_)*(x+.5-cx)+(cx-bx)*(y+.5-cy_))/den
                        wb = ((cy_-ay)*(x+.5-cx)+(ax-cx)*(y+.5-cy_))/den
                        wc = 1-wa-wb
                        if min(wa,wb,wc)<-1e-7:
                            continue
                        dep = wa*a[1]+wb*b[1]+wc*c[1]
                        if dep >= depths[y*size[0]+x]:
                            continue
                        u = wa*coords[0][0]+wb*coords[1][0]+wc*coords[2][0]
                        v = wa*coords[0][1]+wb*coords[1][1]+wc*coords[2][1]
                        for _ in range(f.get('rotation',0)//90):
                            u,v = v,1-u
                        tx = min(tex.width-1,max(0,int((u0+u*(u1-u0))*tex.width/16)))
                        ty = min(tex.height-1,max(0,int((v0+v*(v1-v0))*tex.height/16)))
                        color = tex.getpixel((tx,ty))
                        if color[3] == 0:
                            continue
                        pix[x,y] = tuple(round(v*shade) for v in color[:3])+(255,)
                        depths[y*size[0]+x] = dep
    return im


def validate():
    for name in ('dock','dock_lit','dock_loaded','dock_loaded_lit','gauge','gauge_lit','monitor'):
        m = model('distantstock:block/'+name)
        for e in m['elements']:
            assert set(e['faces']) == {'north','south','east','west','up','down'}
            for face in e['faces'].values():
                assert all(0 <= v <= 16 for v in face['uv']), (name,face)
                assert resolve(face['texture'],m['textures']).getchannel('A').getextrema() == (255,255)
            for x in (e['from'][0],e['to'][0]):
                for y in (e['from'][1],e['to'][1]):
                    for z in (e['from'][2],e['to'][2]):
                        assert all(-.001 <= p <= 16.001 for p in rotate((x,y,z),e.get('rotation'))), (name,e['name'])
        if name.startswith('dock'):
            assert len(m['elements']) == 1 and m['elements'][0]['from'] == [0,0,0] and m['elements'][0]['to'] == [16,16,16]
        print(f'PASS {name}: {len(m["elements"])} cuboids, {sum(len(e["faces"]) for e in m["elements"])} faces, opaque UVs, within one block')
    for path in (ASSETS/'distantstock/blockstates').glob('*.json'):
        for entry in json.loads(path.read_text()).get('variants',{}).values():
            for v in entry if isinstance(entry,list) else [entry]:
                model(v['model'])
    print('PASS all blockstate model references')


def main():
    validate()
    OUT.mkdir(parents=True,exist_ok=True)
    cells = [('DOCK / IDLE','dock',30,25),('DOCK / LINK','dock_lit',30,25),
             ('DOCK / PARCEL','dock_loaded_lit',30,25),('DOCK / BACK','dock',210,25),
             ('DOCK / FRONT','dock_lit',0,0),('REQUEST DESK','gauge_lit',30,35),
             ('DESK / SIDE','gauge',90,20),('WALL INSTRUMENT','monitor',30,20)]
    sheet = Image.new('RGBA',(960,580),'#e8e5dc')
    d = ImageDraw.Draw(sheet)
    for i,(label,name,yaw,pitch) in enumerate(cells):
        x,y = (i%4)*240,(i//4)*280
        sheet.paste(render('distantstock:block/'+name,yaw,pitch),(x,y+30))
        d.text((x+12,y+12),label,fill='#3c423c')
    d.text((12,565),'0.3.7 ACTUAL JSON + TEXTURES / OFFLINE PREVIEW, NOT A GAME SCREENSHOT',fill='#62685f')
    sheet.save(OUT/'machines-0.3.7.png')
    print(OUT/'machines-0.3.7.png')


if __name__ == '__main__':
    main()
