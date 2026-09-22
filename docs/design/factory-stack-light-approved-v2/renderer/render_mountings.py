"""V2 mountings: upright wall bracket and world-space red/yellow/green order."""
from pathlib import Path
import copy
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from render_scene import box, load_minecraft_model, move, render
from render_preview import texture, check_model

HERE = Path(__file__).resolve().parents[1]
OUT = HERE / 'mountings'
BG = '#e8ece8'
PIVOT = [8, 8, 8]
ORIENTATIONS = {
    'floor': {'construction': 'original V2', 'bounds': [[4,0,4],[12,24,12]], 'mount_plane': 'y=0'},
    'ceiling': {'construction': 'swap red/green bulb identities, then rotate X=180',
                'bounds': [[4,-8,4],[12,16,12]], 'mount_plane': 'y=16'},
    'wall_north': {'construction': 'upright north-wall model', 'yaw': 0,
                   'bounds': [[4,4,7],[12,29,16]], 'mount_plane': 'z=16'},
    'wall_south': {'construction': 'upright north-wall model', 'yaw': 180,
                   'bounds': [[4,4,0],[12,29,9]], 'mount_plane': 'z=0'},
    'wall_west': {'construction': 'upright north-wall model', 'yaw': 90,
                  'bounds': [[7,4,4],[16,29,12]], 'mount_plane': 'x=16'},
    'wall_east': {'construction': 'upright north-wall model', 'yaw': -90,
                  'bounds': [[0,4,4],[9,29,12]], 'mount_plane': 'x=0'},
}


def load_parts(data):
    parts, materials = [], {}
    for e in data['elements']:
        mesh, mats = load_minecraft_model({**data, 'elements': [e]}, texture)
        for f in mesh:
            f['group'] = e['name']
        parts.append(mesh)
        materials.update(mats)
    return parts, materials


def build_mounting(data, key, state):
    source = copy.deepcopy(data)
    if key == 'ceiling':
        for e in source['elements']:
            if e['name'] in ('green_single_shell', 'red_single_shell'):
                old = e['name'].split('_')[0]
                color = 'red' if old == 'green' else 'green'
                e['name'] = color + '_single_shell'
                status = 'on' if state in (color, 'all') else 'off'
                for f in e['faces'].values():
                    side = f['texture'].rsplit('_', 1)[1]
                    f['texture'] = f'study:{color}_{status}_{side}'
    parts, materials = load_parts(source)
    original = [f for part in parts for f in part]
    if key == 'floor':
        result = original
    elif key == 'ceiling':
        result = move(original, [('x', 180, PIVOT)])
    else:
        # Wall foot and horizontal stem, then a second native stem upright.
        # The complete seven-part lamp stack remains vertical and unchanged.
        wall_foot = move(parts[0], [('x', -90, PIVOT)])
        horizontal_stem = move(parts[1], [('x', -90, PIVOT)])
        for f in horizontal_stem:
            f['group'] = 'wall_horizontal_stem'
        vertical_stem = move(parts[1], offset=(0, 5, 2))
        for f in vertical_stem:
            f['group'] = 'wall_vertical_stem'
        stack = move([f for part in parts[2:] for f in part], offset=(0, 5, 2))
        result = wall_foot + horizontal_stem + vertical_stem + stack
        result = move(result, [('y', ORIENTATIONS[key]['yaw'], PIVOT)])
    for f in result:
        f['points'] = np.round(f['points'], 8).tolist()
    return result, materials


def check_mounting(mesh, key, state):
    points = np.concatenate([f['points'] for f in mesh])
    actual = [points.min(axis=0).tolist(), points.max(axis=0).tolist()]
    assert np.allclose(actual, ORIENTATIONS[key]['bounds']), (key, actual)
    assert len(mesh) == (60 if key.startswith('wall') else 54)
    heights = {}
    for color in ('red', 'yellow', 'green'):
        faces = [f for f in mesh if f['group'] == color + '_single_shell']
        assert len(faces) == 6
        verts = np.concatenate([f['points'] for f in faces])
        assert np.allclose(verts.max(axis=0)-verts.min(axis=0), [5,5,5])
        heights[color] = verts[:,1].mean()
        expected = 'on' if state in (color, 'all') else 'off'
        assert all(f['material'].startswith(f'study:{color}_{expected}_') for f in faces)
    assert heights['red'] > heights['yellow'] > heights['green'], (key, heights)
    for f in mesh:
        p, uv = np.asarray(f['points']), np.asarray(f['uv'])
        assert texture(f['material']).size == (16,16)
        assert np.allclose(np.linalg.norm(p-np.roll(p,-1,axis=0),axis=1),
                           np.linalg.norm(uv-np.roll(uv,-1,axis=0),axis=1))


def text(im, xy, s, size=20, color='#34413d'):
    ImageDraw.Draw(im).text(xy, s, fill=color,
        font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc', size))


def surface(key):
    if key == 'floor':
        return box((0,-2,0),(16,0,16),'context:surface')
    if key == 'ceiling':
        return box((0,16,0),(16,18,16),'context:surface')
    return box((0,0,16),(16,30,18),'context:surface')


def preview(mesh, mats, key, size=(480,620), scale=19):
    center = {'floor': (8,11,8), 'wall_north': (8,15,12), 'ceiling': (8,5,8)}[key]
    yaw, pitch = (48,12) if key == 'wall_north' else (32,16)
    return render(mesh+surface(key), mats, size, yaw, pitch, center, scale, BG)


def main():
    OUT.mkdir(exist_ok=True)
    neutral = Image.new('RGBA',(16,16),'#c5cec9')
    ImageDraw.Draw(neutral).rectangle((0,0,15,15),outline='#b6c1b9',width=1)
    built = {}
    for state in ('off','green','yellow','red','all'):
        data = json.loads((HERE/'models'/f'model-{state}.json').read_text())
        check_model(data)
        for key in ORIENTATIONS:
            mesh, mats = build_mounting(data,key,state)
            check_mounting(mesh,key,state)
            if state == 'off':
                (OUT/f'preview-mesh-{key}.json').write_text(json.dumps(mesh,indent=2)+'\n')
            mats['context:surface'] = neutral
            built[state,key] = mesh,mats
    sheet = Image.new('RGBA',(1500,970),BG)
    text(sheet,(36,22),'工厂三色灯 V2 / 安装姿态修订',32)
    text(sheet,(38,72),'三种姿态始终上红、中黄、下绿；墙装底座贴墙，直角支柱托起竖直灯体',19,'#6b7870')
    ImageDraw.Draw(sheet).line((36,111,1464,111),fill='#b9c4bb')
    for i,(key,title,detail) in enumerate([
        ('floor','立装 / 地面','保持已确认的 V2'),
        ('wall_north','贴墙 / 灯体向上','底座贴墙，支柱转 90° 向上'),
        ('ceiling','倒装 / 天花板','交换红绿位置，保持上红下绿'),
    ]):
        x=i*500+10
        image=preview(*built['off',key],key)
        sheet.alpha_composite(image,(x,155))
        image.convert('RGB').save(OUT/f'{key}.png')
        text(sheet,(x+62,797),title,26)
        text(sheet,(x+52,843),detail,18,'#6b7870')
    text(sheet,(38,908),'沿用 V2 安山材质、薄盖与单层透明灯罩；不增加黄铜、加厚灯座或出声孔。',19)
    text(sheet,(38,940),'项目Python渲染器 / 1纹素每模型单位 / 浅灰安装面仅用于场景示意',15,'#6b7870')
    sheet.convert('RGB').save(OUT/'mounting-views.png')
    states=Image.new('RGBA',(1200,620),BG)
    text(states,(30,20),'同一绿灯点亮 / 三种姿态均在最下方',28)
    for i,key in enumerate(('floor','wall_north','ceiling')):
        states.alpha_composite(preview(*built['green',key],key,(390,510),15),(i*400,70))
    text(states,(30,585),'材质提亮示意，非游戏动态照明。三种姿态均保持从上到下红、黄、绿。',16,'#6b7870')
    states.convert('RGB').save(OUT/'mounting-green-on.png')
    mesh,mats=built['off','wall_north']
    render(mesh+surface('wall_north'),mats,(650,760),90,0,(8,15,12),23,BG).convert('RGB').save(OUT/'wall-side.png')
    (OUT/'transforms.json').write_text(json.dumps({
        'revision':2, 'base_model':'../models/model-off.json', 'pivot':PIVOT, 'units_per_block':16,
        'color_order_world_top_to_bottom':['red','yellow','green'],
        'convention':'Python right-handed coordinates, not Minecraft blockstate rotation values.',
        'ceiling':'Swap red/green logical bulb identities before X=180 rotation; powered textures follow logical color.',
        'wall_north_parts':{
            'foot_and_horizontal_stem':'Original elements 0,1 rotated X=-90 about [8,8,8]',
            'vertical_stem':'Copy original element 1, translate [0,5,2]',
            'upright_lamp_stack':'Original elements 2..8, translate [0,5,2], no rotation',
        },'orientations':ORIENTATIONS,
    },ensure_ascii=False,indent=2)+'\n')
    print('Verified 5 states x 6 directions: red above yellow above green, powered color identity, 1 texel/unit, expected bounds.')
    print(OUT/'mounting-views.png')


if __name__ == '__main__':
    main()
