"""Resonant Equipment Dock V1 — offline concept rendered with the project renderer."""
from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts/concepts'))
from render_scene import box, face, crystal, render

ASSETS = ROOT / 'src/main/resources/assets/distantstock'
BG = '#e9e8df'
INK = '#35413b'
MUTED = '#72796f'
CYAN = '#6ed2d5'
PALE = '#f5fbff'
FONT = '/System/Library/Fonts/STHeiti Medium.ttc'

OUT = HERE / 'renders'
OUT.mkdir(parents=True, exist_ok=True)


def tex(path):
    return Image.open(ASSETS / 'textures' / (path + '.png')).convert('RGBA')


def solid(color, alpha=255):
    rgb = tuple(int(color[i:i+2], 16) for i in (1, 3, 5))
    return Image.new('RGBA', (16, 16), rgb + (alpha,))


def label(im, xy, value, size=20, color=INK):
    ImageDraw.Draw(im).text(xy, value, font=ImageFont.truetype(FONT, size), fill=color)


def status_texture(charging=True):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((1, 4, 14, 12), fill='#263940', outline='#9ab3b8')
    d.rectangle((3, 6, 12, 8), fill='#15262b')
    fill_w = 8 if charging else 3
    d.rectangle((3, 6, 3 + fill_w, 8), fill='#76d8db')
    d.rectangle((3, 10, 4, 11), fill='#f4fbff')
    d.rectangle((6, 10, 7, 11), fill=('#76d8db' if charging else '#546a70'))
    d.rectangle((10, 10, 12, 11), fill='#b6d8e8')
    return im


T = {
    'casing': tex('block/tower/casing_inactive'),
    'active': tex('block/tower/casing_active'),
    'shell': tex('block/tower/shell'),
    'iron': tex('block/tower/iron'),
    'brass': tex('block/tower/brass'),
    'crystal': tex('block/tower/crystal'),
    'armor': tex('item/ether_casing_chestplate'),
    'hose': solid('#6f9fb5'),
    'dark': solid('#2d3d42'),
    'pad': solid('#b9d4d8'),
}


def add_box(mesh, lo, hi, mat):
    mesh.extend(box(lo, hi, mat))


def armor_icon_plane(z=5.58):
    p = [(12.5, 19.5, z), (3.5, 19.5, z), (3.5, 7.5, z), (12.5, 7.5, z)]
    return face(p, 'armor', [[0, 0], [16, 0], [16, 16], [0, 16]])


def dock(charging=True):
    mesh = []
    mats = dict(T)
    mats['status'] = status_texture(charging)

    # One-block base and recessed service plinth.
    add_box(mesh, (0, 0, 0), (16, 3, 16), 'casing')
    add_box(mesh, (1.5, 3, 1.5), (14.5, 5.5, 14.5), 'shell')
    add_box(mesh, (3, 3.3, -0.28), (13, 5.2, 0.25), 'status')

    # Rear maintenance spine and side pads holding the chestplate.
    add_box(mesh, (5, 5.5, 11.5), (11, 21.5, 14.5), 'shell')
    add_box(mesh, (6, 7, 10.6), (10, 19.8, 11.7), 'iron')
    add_box(mesh, (2.5, 8, 8.5), (5.2, 18.5, 11.2), 'pad')
    add_box(mesh, (10.8, 8, 8.5), (13.5, 18.5, 11.2), 'pad')
    add_box(mesh, (3.2, 17.8, 7.5), (12.8, 20.3, 10.8), 'brass')
    add_box(mesh, (4, 6.3, 8.7), (12, 8.2, 11.0), 'brass')

    # Existing chestplate icon, deliberately shown as the item being serviced.
    add_box(mesh, (3.2, 7.2, 5.85), (12.8, 19.8, 6.35), 'dark')
    mesh.append(armor_icon_plane())

    # Side shaft = fast mechanical filling path.
    add_box(mesh, (-3.2, 7, 5.2), (0.4, 10.8, 10.8), 'brass')
    add_box(mesh, (-6.0, 8.15, 6.35), (-2.8, 9.65, 9.65), 'iron')
    add_box(mesh, (-6.5, 8.5, 6.7), (-5.7, 9.3, 9.3), 'brass')

    # Twin feed lines keep the silhouette industrial without becoming pipe clutter.
    add_box(mesh, (1.1, 5.2, 6.7), (2.2, 13.2, 7.8), 'hose')
    add_box(mesh, (1.1, 12.1, 6.7), (4.2, 13.2, 7.8), 'hose')
    add_box(mesh, (13.8, 5.2, 6.7), (14.9, 13.2, 7.8), 'hose')
    add_box(mesh, (11.8, 12.1, 6.7), (14.9, 13.2, 7.8), 'hose')

    # Tower-network trickle receiver, reusing the tower's brass + crystal vocabulary.
    add_box(mesh, (5.4, 21.2, 11.4), (10.6, 22.7, 14.6), 'brass')
    mesh.extend(crystal((8, 25.4, 13), width=4.5, height=6.0, material='crystal'))
    add_box(mesh, (5.8, 27.7, 10.8), (10.2, 28.6, 15.2), 'brass')

    if charging:
        add_box(mesh, (6.4, 19.7, 14.35), (9.6, 22.0, 14.85), 'active')

    return mesh, mats


def add_particles(im, charging=True):
    if not charging:
        return
    d = ImageDraw.Draw(im, 'RGBA')
    pts = [
        (355, 245, 3, CYAN), (390, 223, 2, PALE), (431, 248, 3, CYAN),
        (334, 309, 2, PALE), (452, 315, 2, CYAN), (376, 348, 2, PALE),
        (425, 375, 3, CYAN), (349, 401, 2, CYAN), (399, 420, 2, PALE),
    ]
    for x, y, r, c in pts:
        d.ellipse((x-r, y-r, x+r, y+r), fill=c + 'd8')
    d.line((389, 250, 377, 281), fill='#dffaff88', width=2)
    d.line((420, 271, 433, 301), fill='#77d8dc66', width=2)


def hero(charging=True, yaw=31):
    mesh, mats = dock(charging)
    im = render(mesh, mats, size=(760, 680), yaw=-yaw, pitch=20,
                center=(7.0, 13.8, 7.4), scale=18.5, background=BG)
    im = im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    add_particles(im, charging)
    return im


idle = hero(False)
active = hero(True)
idle.save(OUT / 'equipment_dock_idle.png')
active.save(OUT / 'equipment_dock_charging.png')

sheet = Image.new('RGBA', (1540, 900), BG)
label(sheet, (42, 25), '谐振装备维护座 / Resonant Equipment Dock', 34)
label(sheet, (44, 72), '机械轴快充 + 远仓塔覆盖内涓流补能 · 1×1 落地设备概念', 18, MUTED)
sheet.alpha_composite(idle, (20, 105))
sheet.alpha_composite(active, (770, 105))
label(sheet, (67, 765), '待机 / 无机械输入', 22)
label(sheet, (817, 765), '充气中 / 轴输入或塔网涓流', 22)
label(sheet, (67, 802), '侧面：Create 旋转轴输入', 17, MUTED)
label(sheet, (67, 830), '中央：胸甲维护夹持架', 17, MUTED)
label(sheet, (817, 802), '顶部：以太谐振接收节点', 17, MUTED)
label(sheet, (817, 830), '青白粒子：仅补能时少量出现', 17, MUTED)
label(sheet, (42, 872), 'DISTANT STOCK / PYTHON OFFLINE RENDER / CONCEPT ONLY', 13, MUTED)
sheet.save(HERE / 'design_sheet.png')
print(HERE / 'design_sheet.png')

