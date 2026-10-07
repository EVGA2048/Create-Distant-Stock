"""Isolated art study: native Create texels, project Python renderer, no game edits."""
from pathlib import Path
import colorsys
import json
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts/concepts'))
from create_context import CreateReferences
from render_scene import load_minecraft_model, render

REF = CreateReferences()
BG = '#e8ece8'
TEX = HERE / 'textures'
TEX.mkdir(exist_ok=True)
textures = {}


def save_tex(name, im):
    textures['study:' + name] = im
    im.save(TEX / (name + '.png'))
    return 'study:' + name


def atlas_patch(name, patch):
    im = Image.new('RGBA', (16, 16))
    im.paste(patch, (0, 0))
    return save_tex(name, im)


def frame_patch(source, size):
    # Keep native edge pixels; remove middle rows/columns, never resample texels.
    idx = list(range(size // 2)) + list(range(16 - (size - size // 2), 16))
    return Image.fromarray(np.asarray(source)[np.ix_(idx, idx)])


andesite = REF.texture('create:block/andesite_block')
iron = REF.texture('create:block/industrial_iron_block')
save_tex('andesite', andesite)
save_tex('iron', iron)
atlas_patch('foot_top', frame_patch(andesite, 8))
atlas_patch('ring_top', frame_patch(andesite, 6))
atlas_patch('foot_side', frame_patch(andesite, 8).crop((0, 6, 8, 8)))
atlas_patch('ring_side', frame_patch(andesite, 6).crop((0, 5, 6, 6)))

# Native stock-link bulb: a 5x5 cube with a 5x5 texel patch per face.
stock = REF.model('create:block/stock_link/block_vertical')
native_bulb = stock['children']['bulb']
details = REF.texture('create:block/link_details')
native = {'side': details.crop((27, 5, 32, 10)),
          'top': details.crop((27, 0, 32, 5)),
          'bottom': details.crop((27, 10, 32, 15))}


def tint(im, color, lit=False):
    result = im.copy()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = im.getpixel((x, y))
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if color != 'green':
                h = {'red': 0.012, 'yellow': 0.112}[color]
                s = max(s, 0.70)
            if lit:
                v = min(1, v * 1.45 + .10)
            rr, gg, bb = colorsys.hsv_to_rgb(h, s, v)
            result.putpixel((x, y), (round(rr*255), round(gg*255), round(bb*255), a))
    return result


for color in ('red', 'yellow', 'green'):
    for state in ('off', 'on'):
        for side, patch in native.items():
            atlas_patch(f'{color}_{state}_{side}', tint(patch, color, state == 'on'))


def element(name, lo, hi, side, top=None, bottom=None):
    dx, dy, dz = (hi[i] - lo[i] for i in range(3))
    faces = {}
    for direction, w, h in [('north', dx, dy), ('south', dx, dy),
                            ('east', dz, dy), ('west', dz, dy),
                            ('up', dx, dz), ('down', dx, dz)]:
        material = (top or side) if direction == 'up' else (bottom or side) if direction == 'down' else side
        faces[direction] = {'texture': material, 'uv': [0, 0, w, h]}
    return {'name': name, 'from': lo, 'to': hi, 'faces': faces}


def model(active=None):
    parts = [element('andesite_foot', [4, 0, 4], [12, 2, 12],
                     'study:foot_side', 'study:foot_top', 'study:foot_top'),
             element('iron_stem', [7, 2, 7], [9, 5, 9], 'study:iron')]
    for y in (5, 11, 17, 23):
        parts.append(element('one_pixel_collar', [5, y, 5], [11, y+1, 11],
                             'study:ring_side', 'study:ring_top', 'study:ring_top'))
    for color, y in [('green', 6), ('yellow', 12), ('red', 18)]:
        state = 'on' if active in (color, 'all') else 'off'
        key = f'study:{color}_{state}'
        parts.append(element(color + '_single_shell', [5.5, y, 5.5], [10.5, y+5, 10.5],
                             key + '_side', key + '_top', key + '_bottom'))
    return {'credit': 'Distant Stock art study; Create stock-link bulb layout',
            'ambientocclusion': False, 'render_type': 'minecraft:translucent',
            'elements': parts}


def texture(name):
    return textures[name] if name.startswith('study:') else REF.texture(name)


def mesh(data, offset=(0, 0, 0)):
    return load_minecraft_model(data, texture, REF.model, offset)


def draw_model(data, size, scale, yaw=32, pitch=16, center=(8, 12, 8)):
    faces, mats = mesh(data)
    return render(faces, mats, size, yaw, pitch, center, scale, BG)


def txt(im, x, y, s, size=20, color='#34413d'):
    ImageDraw.Draw(im).text((x, y), s, fill=color,
                           font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc', size))


def verify(data):
    count = 0
    for e in data['elements']:
        dx, dy, dz = (e['to'][i] - e['from'][i] for i in range(3))
        for face, f in e['faces'].items():
            want = (dx, dz) if face in ('up', 'down') else (dz, dy) if face in ('east', 'west') else (dx, dy)
            u0, v0, u1, v1 = f['uv']
            assert (u1-u0, v1-v0) == want, (e['name'], face)
            assert texture(f['texture']).size == (16, 16)
            count += 1
    bulbs = [e for e in data['elements'] if e['name'].endswith('_single_shell')]
    assert len(bulbs) == 3
    assert len(data['elements']) == 9  # foot, stem, four collars, three shells; no core
    return count


def main():
    for state in ('off', 'green', 'yellow', 'red', 'all'):
        data = model(None if state == 'off' else state)
        verify(data)
        (HERE / f'model-{state}.json').write_text(json.dumps(data, indent=2) + '\n')
    sheet = Image.new('RGBA', (1500, 1100), BG)
    txt(sheet, 36, 22, '机械动力：远仓  /  工厂三色灯', 32)
    txt(sheet, 38, 70, 'V2 · 原生像素密度 · 单层半透明灯罩 · Python 模型预览', 18, '#6b7870')
    ImageDraw.Draw(sheet).line((36, 108, 1464, 108), fill='#bbc6bd')
    sheet.alpha_composite(draw_model(model(), (580, 770), 28), (18, 130))
    txt(sheet, 64, 932, '原生材质明度 / 三色玻璃', 23)
    txt(sheet, 64, 970, '8 × 8 底座 · 24 高 · 灯罩 5 × 5 × 5', 19, '#6b7870')
    txt(sheet, 64, 1003, '每纹素 = 1 模型单位；16 单位 = 1 格', 19, '#6b7870')

    sheet.alpha_composite(draw_model(model(), (330, 430), 16, 0, 0), (625, 135))
    sheet.alpha_composite(draw_model(model('green'), (330, 430), 16), (1040, 135))
    txt(sheet, 718, 573, '正视 / 固定像素', 20)
    txt(sheet, 1117, 573, '绿灯提亮示意', 20)
    txt(sheet, 664, 629, '同尺度对照：原版仓储链接器 · 工厂仪表 · 远仓信号灯 · 新三色灯', 17)

    combined, materials = [], {}
    def add(data, offset):
        m, t = mesh(data, offset); combined.extend(m); materials.update(t)
    for child in stock['children'].values():
        add(child, (-20, 0, 0))
    add(REF.model('create:block/factory_gauge/panel_with_bulb'), (3, 0, 3))
    add(REF.model('create:block/factory_gauge/bulb_light'), (3, 0, 3))
    lamp = json.loads((ROOT / 'src/main/resources/assets/distantstock/models/block/signal_panel/andesite_base.json').read_text())
    lamp['textures']['lamp'] = 'distantstock:block/indicator_lamp_green_off'
    def local_texture(name):
        if name.startswith('distantstock:'):
            return Image.open(ROOT / 'src/main/resources/assets/distantstock/textures' / (name.split(':')[1]+'.png')).convert('RGBA')
        return texture(name)
    m, t = load_minecraft_model(lamp, local_texture, REF.model, (16, 0, 3))
    combined.extend(m); materials.update(t)
    add(model(), (28, 0, 0))
    sheet.alpha_composite(render(combined, materials, (820, 300), 0, 18, (12, 11, 8), 11, BG), (640, 676))
    txt(sheet, 683, 982, '原版 Create', 17)
    txt(sheet, 919, 982, '原版', 17)
    txt(sheet, 1058, 982, '远仓现有', 17)
    txt(sheet, 1238, 982, '本次设计', 17)
    txt(sheet, 38, 1060, '项目 render_scene.py 渲染；未接入游戏。玻璃沿用原版 alpha=204；点亮图仅示意材质提亮，无泛光。', 16, '#6b7870')
    sheet.convert('RGB').save(HERE / 'factory-stack-light-v2.png')
    draw_model(model(), (640, 900), 30).convert('RGB').save(HERE / 'three-quarter.png')
    print(f'UV density verified: {verify(model())} faces, 1 texel / model unit; no inner core.')
    print(HERE / 'factory-stack-light-v2.png')


if __name__ == '__main__':
    main()
