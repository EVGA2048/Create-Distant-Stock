#!/usr/bin/env python3
"""Create-style resonant canister concept using the project's offline renderer."""

from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts' / 'concepts'))

from render_scene import box, render
from create_context import CreateReferences

REF = CreateReferences()
ASSETS = ROOT / 'src/main/resources/assets/distantstock'
OUT = HERE / 'renders'
OUT.mkdir(parents=True, exist_ok=True)

BG = '#eceae3'
INK = '#37403d'
MUTED = '#747b76'


def font(size):
    for p in ('/System/Library/Fonts/STHeiti Medium.ttc',
              '/System/Library/Fonts/PingFang.ttc',
              '/System/Library/Fonts/Supplemental/Arial.ttf'):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            pass
    return ImageFont.load_default()


def tex(name):
    if name.startswith('create:'):
        return REF.texture(name)
    if name.startswith('distantstock:'):
        _, path = name.split(':', 1)
        return Image.open(ASSETS / 'textures' / (path + '.png')).convert('RGBA')
    raise KeyError(name)


MATS = {
    'andesite': tex('create:block/andesite_casing'),
    'brass': tex('create:block/brass_casing'),
    'brass_block': tex('create:block/brass_block'),
    'window': tex('create:block/fluid_tank_window_single'),
    'ether': tex('distantstock:fluid/ether_still'),
    'amethyst': tex('distantstock:fluid/molten_amethyst_still'),
}


def add(mesh, lo, hi, material):
    mesh += box(lo, hi, material)


def canister(fluid=None):
    """Compact mechanical cartridge; front is negative Z."""
    m = []

    # Main andesite pressure shell.
    add(m, (4.5, 3.0, 5.0), (11.5, 13.0, 11.0), 'andesite')

    # Brass collars / crash guards.
    add(m, (4.0, 2.0, 4.5), (12.0, 4.0, 11.5), 'brass')
    add(m, (4.0, 12.0, 4.5), (12.0, 14.0, 11.5), 'brass')
    add(m, (5.0, 1.25, 5.25), (11.0, 2.0, 10.75), 'brass_block')

    # Top fill port / valve boss.
    add(m, (6.0, 14.0, 6.0), (10.0, 15.0, 10.0), 'andesite')
    add(m, (6.75, 15.0, 6.75), (9.25, 16.0, 9.25), 'brass_block')

    # Recessed vertical sight glass on the front.
    zf0, zf1 = 4.42, 4.78
    add(m, (6.0, 5.0, zf0), (10.0, 5.65, zf1), 'brass_block')
    add(m, (6.0, 10.35, zf0), (10.0, 11.0, zf1), 'brass_block')
    add(m, (6.0, 5.65, zf0), (6.65, 10.35, zf1), 'brass_block')
    add(m, (9.35, 5.65, zf0), (10.0, 10.35, zf1), 'brass_block')
    add(m, (6.65, 5.65, 4.54), (9.35, 10.35, 4.72), 'window')
    if fluid:
        # Leave a visible headspace so this reads as liquid, not a glowing lamp.
        add(m, (6.82, 5.82, 4.49), (9.18, 9.85, 4.58), fluid)

    # Side latch + relief valve.
    add(m, (11.5, 7.0, 6.25), (12.25, 9.5, 9.75), 'brass_block')
    add(m, (12.25, 7.75, 7.0), (13.0, 8.75, 9.0), 'andesite')
    return m


def render_state(name, fluid=None):
    im = render(canister(fluid), MATS, size=(420, 460), yaw=28, pitch=18,
                center=(8, 8.5, 8), scale=20.0, background=BG)
    im.save(OUT / f'{name}.png')
    return im


def item_icon(name, fluid=None):
    im = render(canister(fluid), MATS, size=(32, 32), yaw=28, pitch=16,
                center=(8, 8.5, 8), scale=1.42, background='#00000000')
    im.save(OUT / f'{name}_32.png')
    return im


def main():
    states = [
        ('empty', None, '空罐', '无介质 · 观察窗保持暗色'),
        ('ether', 'ether', '以太凝液', '淡青白液位 · 标准介质'),
        ('amethyst', 'amethyst', '熔融紫水晶', '紫色液位 · 高温介质'),
    ]
    rendered = []
    for key, fluid, title, note in states:
        rendered.append((key, title, note, render_state(key, fluid)))
        item_icon(key, fluid)

    sheet = Image.new('RGBA', (1480, 720), BG)
    d = ImageDraw.Draw(sheet)
    d.text((42, 28), '谐振介质罐 / RESONANT CANISTER', font=font(34), fill=INK)
    d.text((44, 76), 'Create 材质参考 · 安山机壳 + 黄铜机械件 + 窄液位窗 · Python 离线渲染',
           font=font(17), fill=MUTED)
    d.line((42, 108, 1438, 108), fill='#b8bbb4', width=1)

    x0 = 38
    for i, (key, title, note, im) in enumerate(rendered):
        x = x0 + i * 480
        sheet.alpha_composite(im, (x, 124))
        d.text((x + 22, 590), title, font=font(25), fill=INK)
        d.text((x + 22, 628), note, font=font(16), fill=MUTED)
        icon = Image.open(OUT / f'{key}_32.png').resize((96, 96), Image.Resampling.NEAREST)
        sheet.alpha_composite(icon, (x + 330, 575))

    d.text((44, 690), '概念稿：模型尺寸与部件均可直接转成 Minecraft item/block JSON；未修改正式物品贴图。',
           font=font(14), fill=MUTED)
    sheet.save(HERE / 'design_sheet.png')
    print(HERE / 'design_sheet.png')


if __name__ == '__main__':
    main()
