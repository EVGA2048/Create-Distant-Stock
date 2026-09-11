#!/usr/bin/env python3
"""Distant Stock visual system v2.

Creates implementation-oriented concept renders for the four remote-stock tools,
the three-part interconnect tower, and aetheric condensate.  Outputs are versioned
under docs/design/transmission/build/art-v2 and do not touch game assets.
"""
from __future__ import annotations

import io
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts/concepts"))

import render_scene as rs
from create_materials import material, source
from render_scene import BG, MUTED, box, header, load_minecraft_model, move, render, text

DOC = ROOT / "docs/design/transmission"
OUT = DOC / "build/art-v2"
ASSETS = ROOT / "src/main/resources/assets/distantstock"


def cjk_font(size=18):
    for path in (
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/System/Library/Fonts/PingFang.ttc",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return rs.font(size)


rs.font = cjk_font

INK = "#303B3F"
PAPER_BG = "#EAE6DA"
FULL_UV = [[0, 0], [16, 0], [16, 16], [0, 16]]


def flat(base, highlight=None, alpha=255, pattern=None):
    im = Image.new("RGBA", (16, 16), base)
    d = ImageDraw.Draw(im)
    if pattern == "diagonal" and highlight:
        for x in range(-16, 32, 8):
            d.line((x, 15, x + 15, 0), fill=highlight, width=2)
    elif pattern == "grid" and highlight:
        for p in (0, 8, 15):
            d.line((p, 0, p, 15), fill=highlight)
            d.line((0, p, 15, p), fill=highlight)
    elif highlight:
        d.line((0, 0, 15, 0), fill=highlight)
        d.line((0, 0, 0, 15), fill=highlight)
    im.putalpha(alpha)
    return im


def materials():
    mats = {
        "andesite": material("andesite"),
        "casing": material("casing"),
        "iron": material("iron"),
        "cast_white": material("cast_white"),
        "wood": material("wood"),
        "brass": source("brass_casing").copy(),
        "copper": source("copper_casing").copy(),
        "tank": source("fluid_tank").copy(),
        "screen": flat("#172226", "#263A3D"),
        "screen_grid": flat("#183036", "#245762", pattern="grid"),
        "screen_glass": flat("#7CC8D2", "#D8FCFF", 95),
        "cyan": flat("#3EAEC3", "#D8FCFF", pattern="diagonal"),
        "cyan_core": flat("#D8FCFF", "#FFFFFF"),
        "cyan_glass": flat("#60BDD0", "#D8FCFF", 85),
        "purple": flat("#7453A8", "#CFA7FF", pattern="diagonal"),
        "purple_core": flat("#C69AF4", "#F3E7FF"),
        "purple_glass": flat("#8D65C2", "#D9B8FF", 100),
        "green": flat("#4FA95A", "#D8FFD2"),
        "orange": flat("#DB8B36", "#FFE0A1"),
        "red": flat("#BD4B43", "#FFC6B8"),
        "lamp_off": flat("#5D686A", "#9DA8A8"),
        "paper": flat("#E9E1C8", "#FFF9E7"),
    }
    return mats


def add(g, lo, hi, mat, group="fixed", turns=(), clamp=False, emissive=False):
    faces = box(lo, hi, mat, group=group, turns=turns)
    for face in faces:
        if clamp:
            face.update(uv=[p[:] for p in FULL_UV], clamp=True)
        if emissive:
            face["emissive"] = True
    g.extend(faces)


def pixel_lamp(g, cx, cy, z, color, size=2.4, group="status"):
    """Square Create-like pilot light with a deep metal socket."""
    add(g, (cx-size/2-.6, cy-size/2-.6, z-.5),
        (cx+size/2+.6, cy+size/2+.6, z+.5), "iron", group)
    add(g, (cx-size/2, cy-size/2, z-.7),
        (cx+size/2, cy+size/2, z-.45), color, group, emissive=color != "lamp_off")
    add(g, (cx-size/2+.6, cy-size/2+.6, z-.82),
        (cx+size/2-.6, cy+size/2-.6, z-.69),
        "cyan_core" if color == "cyan" else color, group, emissive=color != "lamp_off")


def requester(state="tuned"):
    """Placed storage requester: a recognizable desk terminal, not a machine port."""
    g = []
    add(g, (-8, 0, -8), (8, 2, 8), "andesite")
    add(g, (-7, 2, -7), (7, 5, 7), "casing", clamp=True)
    add(g, (-7.5, 4.7, -7.5), (7.5, 6, 1.8), "brass")
    # Upright inventory screen.
    add(g, (-7, 5, 1), (7, 15, 4), "brass", "display")
    add(g, (-6, 6, 0.65), (6, 14, 1.05), "screen", "display")
    # Inventory-list rows and item slots; intentionally abstract at concept scale.
    for y in (7.2, 9.4, 11.6):
        add(g, (-5.2, y, 0.35), (-3.7, y+1.25, 0.62), "screen_grid", "screen_marks")
        add(g, (-3.0, y+.25, 0.36), (3.5, y+.55, 0.61), "cyan" if state == "tuned" else "lamp_off", "screen_marks")
        add(g, (4.1, y+.15, 0.36), (5.0, y+.95, 0.61), "paper", "screen_marks")
    # Keyboard/order strip.
    add(g, (-5.8, 5.8, -6.5), (5.8, 6.35, -1.0), "iron", "keyboard")
    for x in (-4.8, -2.8, -.8, 1.2, 3.2):
        add(g, (x, 6.35, -5.6), (x+1.15, 6.58, -4.3), "paper", "keys")
    add(g, (-4.8, 6.35, -3.6), (2.5, 6.58, -2.4), "paper", "keys")
    add(g, (3.1, 6.35, -3.6), (5.0, 6.58, -2.4), "cyan" if state == "tuned" else "lamp_off", "order_key")
    pixel_lamp(g, 5.1, 13.1, .25, "cyan" if state == "tuned" else "lamp_off", 1.5, "network_lamp")
    return g


def dock(state="online"):
    """Four-sided parcel berth.  Every horizontal face keeps an 8 px opening."""
    g = []
    add(g, (-8, 0, -8), (8, 2, 8), "iron")
    add(g, (-7.5, 2, -7.5), (7.5, 4.2, 7.5), "casing", clamp=True)
    add(g, (-5, 4.2, -5), (5, 5.1, 5), "screen", "berth")
    # Low cardinal lips advertise compatibility without blocking funnels/chutes.
    for lo, hi in (
        ((-4, 4.2, -8), (4, 5.5, -6.7)), ((-4, 4.2, 6.7), (4, 5.5, 8)),
        ((-8, 4.2, -4), (-6.7, 5.5, 4)), ((6.7, 4.2, -4), (8, 5.5, 4)),
    ):
        add(g, lo, hi, "iron", "logistics_lip")
    # Four brass/casing corner pylons form a protective open cradle.
    for x in (-7.2, 5.2):
        for z in (-7.2, 5.2):
            add(g, (x, 4.2, z), (x+2, 10.5, z+2), "casing", "pylon", clamp=True)
            add(g, (x-.3, 9.2, z-.3), (x+2.3, 11.2, z+2.3), "brass", "pylon_cap")
    glow = "cyan" if state in ("online", "loaded") else "lamp_off"
    # One inset emitter on every side: active network field, rotationally symmetric.
    for lo, hi in (
        ((-2, 4.65, -7.9), (2, 5.15, -7.75)), ((-2, 4.65, 7.75), (2, 5.15, 7.9)),
        ((-7.9, 4.65, -2), (-7.75, 5.15, 2)), ((7.75, 4.65, -2), (7.9, 5.15, 2)),
    ):
        add(g, lo, hi, glow, "network_emitters", emissive=state != "offline")
    if state == "loaded":
        add(g, (-3.5, 5.1, -3.5), (3.5, 10.6, 3.5), "wood", "parcel")
        add(g, (-4.1, 4.8, -4.1), (4.1, 11.1, 4.1), "cyan_glass", "transfer_field", emissive=True)
    elif state == "online":
        add(g, (-3.8, 5.1, -3.8), (3.8, 5.45, 3.8), "cyan_glass", "transfer_field", emissive=True)
    return g


def monitor(tps="green"):
    """Thin wall display with exactly one TPS pilot light at the upper right."""
    if tps not in ("green", "orange", "red"):
        raise ValueError(tps)
    g = []
    add(g, (-8, 0, -1), (8, 16, 3.5), "iron", "wall_body")
    add(g, (-7.5, .5, -1.6), (7.5, 15.5, -.7), "brass", "bezel")
    add(g, (-6.6, 1.4, -2.0), (6.6, 14.6, -1.55), "screen", "screen")
    # Left link strip, central TPS graph and numeric-like segments.
    add(g, (-5.8, 2.1, -2.25), (-5.1, 13.8, -2.02), "cyan", "link_strip", emissive=True)
    for i, h in enumerate((2.0, 3.8, 3.0, 5.5, 4.4, 6.2)):
        x = -3.8 + i * 1.25
        add(g, (x, 3.0, -2.22), (x+.65, 3.0+h, -2.03), tps, "tps_graph", emissive=True)
    add(g, (-3.8, 10.7, -2.23), (2.2, 11.15, -2.02), "paper", "tps_digits")
    add(g, (-3.8, 12.0, -2.23), (.2, 12.45, -2.02), "paper", "tps_digits")
    pixel_lamp(g, 4.5, 12.3, -2.05, tps, 2.4, "tps_lamp")
    return g


def tower_base(state="online"):
    g = []
    add(g, (-8, 0, -8), (8, 3, 8), "iron")
    add(g, (-7, 3, -7), (7, 13, 7), "casing", clamp=True)
    add(g, (-8, 13, -8), (8, 16, 8), "brass")
    # Front service panel: purple fluid sight + network light.
    add(g, (-5.8, 4.2, -7.35), (5.8, 11.8, -6.75), "iron", "service_panel")
    add(g, (-4.7, 5.0, -7.7), (-2.3, 11.0, -7.3), "purple_glass", "fluid_sight")
    add(g, (-4.1, 5.4, -7.85), (-2.9, 10.4, -7.68), "purple_core", "fluid_sight", emissive=True)
    for y in (5.3, 7.1, 8.9, 10.7):
        add(g, (-1.0, y, -7.65), (2.0, y+.35, -7.35), "paper", "gauge_ticks")
    lamp = "cyan" if state == "online" else ("red" if state == "fault" else "lamp_off")
    pixel_lamp(g, 4.0, 9.0, -7.5, lamp, 2.2, "tower_status")
    # South kinetic shaft.
    add(g, (-3, 6, 6.5), (3, 10, 8), "brass", "kinetic_port")
    add(g, (-1.7, 7.1, 7.7), (1.7, 8.9, 9), "iron", "kinetic_port")
    # East fluid flange: purple center makes the medium unambiguous.
    add(g, (6.5, 5, -3), (8.2, 11, 3), "copper", "fluid_port")
    add(g, (7.8, 6.4, -1.8), (9.0, 9.6, 1.8), "iron", "fluid_port")
    add(g, (8.85, 7.1, -1.1), (9.1, 8.9, 1.1), "purple", "fluid_port")
    return g


def tower_frame(state="online"):
    """Repeatable one-block tower mast with visible internal aether conduit."""
    g = []
    # Brass feet/caps and four iron corner rails leave a readable open truss.
    for y0, y1 in ((0, 2), (14, 16)):
        add(g, (-7, y0, -7), (7, y1, -5), "brass", "frame_ring")
        add(g, (-7, y0, 5), (7, y1, 7), "brass", "frame_ring")
        add(g, (-7, y0, -5), (-5, y1, 5), "brass", "frame_ring")
        add(g, (5, y0, -5), (7, y1, 5), "brass", "frame_ring")
    for x in (-7, 5):
        for z in (-7, 5):
            add(g, (x, 1.5, z), (x+2, 14.5, z+2), "iron", "tower_rail")
    # Cross braces are narrow boxes, rotated in the vertical plane.
    for z in (-6.3, 6.0):
        add(g, (-5.2, 7.2, z), (5.2, 8.2, z+.55), "casing", "brace", turns=(("z", 42, (0, 8, z)),))
        add(g, (-5.2, 7.2, z), (5.2, 8.2, z+.55), "casing", "brace", turns=(("z", -42, (0, 8, z)),))
    # Central purple conduit carries fluid upward; cyan collar means active field.
    add(g, (-2.4, 1.5, -2.4), (2.4, 14.5, 2.4), "purple_glass", "aether_conduit")
    add(g, (-1.3, 1.8, -1.3), (1.3, 14.2, 1.3), "purple_core", "aether_conduit", emissive=True)
    collar = "cyan" if state == "online" else ("red" if state == "fault" else "lamp_off")
    add(g, (-3.2, 7, -3.2), (3.2, 9, 3.2), collar, "field_collar", emissive=state != "offline")
    return g


def resonator(state="online", sky_beam=True):
    """Tower crown: four brass resonator arms around a glass field core."""
    g = []
    add(g, (-8, 0, -8), (8, 3, 8), "brass", "resonator_base")
    add(g, (-6, 3, -6), (6, 7, 6), "casing", "resonator_body", clamp=True)
    add(g, (-3.5, 4, -3.5), (3.5, 12.5, 3.5), "purple_glass", "resonator_core")
    add(g, (-1.8, 4.5, -1.8), (1.8, 13, 1.8), "purple_core", "resonator_core", emissive=True)
    # Four horizontal tuning arms with cyan end lenses.
    field = "cyan" if state == "online" else ("red" if state == "fault" else "lamp_off")
    for lo, hi, lens_lo, lens_hi in (
        ((-7.5, 7, -2), (-3, 10, 2), (-8, 7.4, -2.4), (-6.8, 9.6, 2.4)),
        ((3, 7, -2), (7.5, 10, 2), (6.8, 7.4, -2.4), (8, 9.6, 2.4)),
        ((-2, 7, -7.5), (2, 10, -3), (-2.4, 7.4, -8), (2.4, 9.6, -6.8)),
        ((-2, 7, 3), (2, 10, 7.5), (-2.4, 7.4, 6.8), (2.4, 9.6, 8)),
    ):
        add(g, lo, hi, "brass", "resonator_arm")
        add(g, lens_lo, lens_hi, field, "resonator_lens", emissive=state != "offline")
    add(g, (-4.5, 12, -4.5), (4.5, 14, 4.5), "iron", "crown")
    add(g, (-2.3, 14, -2.3), (2.3, 16, 2.3), field, "crown_lens", emissive=state != "offline")
    if state == "online" and sky_beam:
        add(g, (-1.6, 16, -1.6), (1.6, 56, 1.6), "cyan_core", "sky_beam", emissive=True)
        add(g, (-3, 16, -3), (3, 56, 3), "cyan_glass", "sky_beam", emissive=True)
    return g


def tower(state="online", frames=3):
    mesh = list(tower_base(state))
    for i in range(frames):
        mesh += move(tower_frame(state), offset=(0, 16*(i+1), 0))
    mesh += move(resonator(state, sky_beam=True), offset=(0, 16*(frames+1), 0))
    return mesh


def portable_model():
    data = json.loads((ASSETS / "models/item/requester.json").read_text())
    def loader(ref):
        return Image.open(ASSETS / "textures" / f"{ref.split(':')[1]}.png").convert("RGBA")
    return load_minecraft_model(data, loader)


def fit(mesh, size, yaw=30, pitch=22, center=(0, 8, 0), margin=70):
    pts = np.array([p for f in mesh for p in f["points"]])
    rot = rs.rotation("x", -pitch) @ rs.rotation("y", yaw)
    cam = (pts - np.array(center)) @ rot.T
    w = cam[:, 0].max() - cam[:, 0].min()
    h = cam[:, 1].max() - cam[:, 1].min()
    return min((size[0]-2*margin)/max(w, 1), (size[1]-2*margin)/max(h, 1))


def view(mesh, mats, size=(640, 560), yaw=30, pitch=22, center=(0, 8, 0), scale=None):
    return render(mesh, mats, size=size, yaw=yaw, pitch=pitch, center=center,
                  scale=scale or fit(mesh, size, yaw, pitch, center), background=BG)


def card(title, subtitle, panels, foot, panel_size=(430, 430), center=(0, 8, 0), pitch=22):
    w = 40 + len(panels)*panel_size[0]
    im = Image.new("RGBA", (w, panel_size[1]+225), BG)
    header(im, title, subtitle)
    for i, (label, mesh, mats, *camera) in enumerate(panels):
        yaw = camera[0] if camera else 30
        ppitch = camera[1] if len(camera) > 1 else pitch
        c = camera[2] if len(camera) > 2 else center
        x = 20+i*panel_size[0]
        im.alpha_composite(view(mesh, mats, panel_size, yaw, ppitch, c), (x, 100))
        text(im, (x+12, panel_size[1]+110), label, 18)
    text(im, (28, panel_size[1]+165), foot, 15, MUTED)
    return im


def arrow(draw, a, b, color="#5A8392", width=4):
    draw.line((a, b), fill=color, width=width)
    v = np.array(a, float)-np.array(b, float)
    v /= np.linalg.norm(v)+1e-9
    n = np.array((-v[1], v[0]))
    p = np.array(b, float)
    draw.polygon([tuple(p), tuple(p+13*v+6*n), tuple(p+13*v-6*n)], fill=color)


def system_overview(mats):
    im = Image.new("RGBA", (1600, 1040), BG)
    header(im, "机械动力：远仓 / 视觉系统 V2", "CREATE INDUSTRIAL HARDWARE + PURPLE AETHER FLUID + CYAN NETWORK FIELD")
    items = [
        ("远仓请求器", requester("tuned"), (0, 8, 0), 30, 22),
        ("远仓港", dock("loaded"), (0, 8, 0), 30, 24),
        ("远仓监视器", monitor("green"), (0, 8, 0), 25, 12),
        ("互通塔", tower("online", 2), (0, 43, 0), 28, 14),
    ]
    for i, (label, mesh, center, yaw, pitch) in enumerate(items):
        x = 20+i*390
        im.alpha_composite(view(mesh, mats, (380, 710), yaw, pitch, center), (x, 105))
        text(im, (x+22, 825), label, 22)
    text(im, (38, 895), "紫色 = 以太凝液 / CYAN = NETWORK ACTIVE / 绿橙红 = TPS HEALTH", 20, INK)
    text(im, (38, 940), "一套部件只表达一种信息；所有结构材质来自 Create 6 的安山岩、工业铁、黄铜与铜。", 17, MUTED)
    return im


def dock_logistics(mats):
    mesh = dock("loaded")
    im = Image.new("RGBA", (1200, 900), BG)
    header(im, "远仓港 / 四面物流交互", "FOUR OPEN SIDES / TOP OPEN / NO KINETIC OR FLUID PORT ON THE DOCK")
    scene = view(mesh, mats, (760, 680), 45, 52, (0, 7, 0), scale=27)
    im.alpha_composite(scene, (220, 110))
    d = ImageDraw.Draw(im)
    for label, start, end in (
        ("漏斗", (95, 340), (390, 440)), ("蛙港", (1100, 340), (820, 440)),
        ("溜槽", (600, 820), (600, 610)), ("皮带 / 其他物品交互", (600, 160), (600, 330)),
    ):
        text(im, (start[0]-50, start[1]-35), label, 19, INK)
        arrow(d, start, end)
    text(im, (36, 850), "中心是开放式包裹泊位；四边中段无实体高墙，角柱只负责保护与显示网络状态。", 16, MUTED)
    return im


def tower_components(mats):
    panels = [
        ("01 底座 / 动力 + 流体", tower_base("online"), mats),
        ("02 塔架 / 可重复堆叠", tower_frame("online"), mats),
        ("03 以太谐振器 / 塔顶", resonator("online", False), mats),
    ]
    return card("互通塔 / 三种组件", "BASE + REPEATABLE MAST + AETHER RESONATOR",
                panels, "底座存储以太凝液并接收应力；塔架输送介质；谐振器建立网络场并加载区块。")


def fluid_sheet(mats):
    im = Image.new("RGBA", (1450, 900), BG)
    header(im, "以太凝液 / AETHERIC CONDENSATE", "HEATED AMETHYST PROCESS FLUID / CONSUMED BY INTERCONNECT TOWERS")
    # Tank mockup.
    tank = []
    add(tank, (-7, 0, -7), (7, 3, 7), "copper")
    # Fluid tank frame: open windows so the purple medium is actually readable.
    for x in (-6, 4):
        for z in (-6, 4):
            add(tank, (x, 3, z), (x+2, 14, z+2), "tank")
    for y0, y1 in ((3, 5), (12, 14)):
        add(tank, (-6, y0, -6), (6, y1, -4), "tank")
        add(tank, (-6, y0, 4), (6, y1, 6), "tank")
        add(tank, (-6, y0, -4), (-4, y1, 4), "tank")
        add(tank, (4, y0, -4), (6, y1, 4), "tank")
    add(tank, (-5.2, 4, -5.2), (5.2, 12, 5.2), "purple_glass")
    add(tank, (-4.7, 4, -4.7), (4.7, 11, 4.7), "purple_core", emissive=True)
    add(tank, (-7, 14, -7), (7, 16, 7), "brass")
    im.alpha_composite(view(tank, mats, (580, 610), 30, 22, (0, 8, 0), scale=26), (30, 130))
    d = ImageDraw.Draw(im)
    # Recipe flow uses simple symbolic cards to avoid claiming final item counts.
    cards = [("水", "1000 mB", "#6CB6D9"), ("紫水晶材料", "+", "#9765C5"), ("加热搅拌", "HEATED", "#D87542"), ("以太凝液", "1000 mB", "#7453A8")]
    for i, (a, btxt, color) in enumerate(cards):
        x = 640+i*190
        d.rounded_rectangle((x, 250, x+155, 420), radius=14, fill="#F4F0E5", outline="#8A9696", width=2)
        d.rectangle((x+26, 278, x+129, 350), fill=color)
        text(im, (x+18, 366), a, 17, INK)
        text(im, (x+18, 395), btxt, 14, MUTED)
        if i < len(cards)-1:
            arrow(d, (x+158, 335), (x+185, 335), width=3)
    d.rounded_rectangle((640, 510, 1320, 665), radius=14, fill="#F4F0E5", outline="#8A9696", width=2)
    text(im, (675, 542), "每成功发送 1 个包裹", 23, INK)
    text(im, (675, 590), "互通塔消耗 250 mB 以太凝液", 30, "#7453A8")
    text(im, (52, 800), "建议正式配方：水 + 紫水晶材料 + 少量导能材料，在动力搅拌盆中加热；具体数量在平衡阶段确定。", 17, MUTED)
    return im


def network_sheet(mats):
    im = Image.new("RGBA", (1600, 1000), BG)
    header(im, "互通网络 / 覆盖与消耗", "ONE ACTIVE TOWER POWERS NEARBY DISTANT-STOCK TOOLS")
    im.alpha_composite(view(tower("online", 2), mats, (560, 760), 28, 14, (0, 43, 0)), (30, 125))
    devices = [("请求器", requester("tuned")), ("远仓港", dock("online")), ("监视器", monitor("green"))]
    d = ImageDraw.Draw(im)
    for i, (label, mesh) in enumerate(devices):
        x = 720+i*285
        im.alpha_composite(view(mesh, mats, (260, 300), 30, 20, (0, 8, 0)), (x, 180+i*190))
        text(im, (x+70, 470+i*190), label, 18, INK)
        arrow(d, (580, 400), (x+40, 330+i*190), color="#3EAEC3", width=4)
    text(im, (710, 810), "塔内网络范围", 25, INK)
    text(im, (710, 850), "激活范围内的远仓工具 + 区块加载", 20, MUTED)
    text(im, (710, 900), "发送包裹：-250 mB", 27, "#7453A8")
    return im


def write_spec():
    spec = {
        "revision": "visual-system-v2",
        "status": "concept_approved_pending_user_review",
        "visualLanguage": {
            "structure": ["create:andesite_casing", "create:industrial_iron", "create:brass_casing", "create:copper_casing"],
            "aetherFluid": {"color": "purple", "meaning": "stored/flowing consumable medium"},
            "networkField": {"color": "cyan", "meaning": "tower coverage, tuning, transfer field"},
            "tps": {"green": "healthy", "orange": "degraded", "red": "critical"},
        },
        "content": {
            "requester": {"type": "block", "role": "opens Create stock-order UI", "states": ["tuned", "untuned"]},
            "portable_requester": {"type": "item", "role": "handheld order UI", "silhouette": "Mobile Packages-inspired current model"},
            "dock": {"type": "block", "role": "parcel transfer berth", "itemInteraction": "four horizontal sides plus open top", "states": ["offline", "online", "loaded"]},
            "monitor": {"type": "wall_block", "role": "remote TPS/link screen", "tpsLamp": ["green", "orange", "red"]},
            "tower_base": {"type": "multiblock_component", "inputs": ["kinetic stress", "aetheric condensate"]},
            "tower_frame": {"type": "repeatable_multiblock_component", "role": "mast and visible aether conduit"},
            "aether_resonator": {"type": "multiblock_component", "role": "network field transmitter and tower crown"},
            "aetheric_condensate": {"type": "fluid", "recipeConcept": "heated mixing of water, amethyst materials, and a conductive ingredient", "costPerParcelMb": 250},
        },
        "deferred": ["fluid distant dock", "energy distant dock", "exact coverage radius", "exact fluid recipe counts"],
    }
    (DOC / "visual-system-v2.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2)+"\n")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    mats = materials()
    pmesh, ptex = portable_model()
    renders = {
        "00-system-overview.png": system_overview(mats),
        "01-requester.png": card("远仓请求器 / REQUESTER", "STORAGE ORDER TERMINAL / TUNED VS UNTUNED",
            [("已调谐", requester("tuned"), mats), ("未调谐", requester("untuned"), mats)],
            "大屏负责库存与购物车；黄铜框架沿用 Create 仓储终端语言；霁青灯只表示已加入网络。"),
        "02-portable-requester.png": card("便携式远仓请求器", "HANDHELD REQUESTER / MOBILE PACKAGES-INSPIRED SILHOUETTE",
            [("俯视", pmesh, ptex, 0, 88, (0, 2, 0)), ("手持斜视", pmesh, ptex, 35, 38, (0, 2, 0)), ("背面", pmesh, ptex, 180, 35, (0, 2, 0))],
            "保留现有无人机物流请求器轮廓；正式落地只需统一黄铜、深铁与霁青像素。", center=(0, 2, 0), pitch=38),
        "03-dock-states.png": card("远仓港 / DOCK", "FOUR-SIDED PARCEL BERTH / OFFLINE, ONLINE, LOADED",
            [("离线", dock("offline"), mats), ("在线", dock("online"), mats), ("包裹传输中", dock("loaded"), mats)],
            "无动力口、无流体口；四边中部均可连接蛙港、漏斗、溜槽或其他物品交互。"),
        "04-dock-logistics.png": dock_logistics(mats),
        "05-monitor-tps.png": card("远仓监视器 / TPS", "WALL SCREEN + ONE FACTORY PILOT LIGHT",
            [("健康 / 绿", monitor("green"), mats), ("警告 / 橙", monitor("orange"), mats), ("严重 / 红", monitor("red"), mats)],
            "屏幕显示链路与 TPS；右上角只有一个工厂式指示灯，颜色严格跟随 TPS 阈值。", pitch=12),
        "06-tower-components.png": tower_components(mats),
        "07-tower-hero.png": card("互通塔 / INTERCONNECT TOWER", "BASE + 3 MAST SECTIONS + AETHER RESONATOR",
            [("在线结构", tower("online", 3), mats, 28, 12, (0, 52, 0)), ("离线结构", tower("offline", 3), mats, 28, 12, (0, 52, 0))],
            "1×1 占地、纵向多方块；紫色介质贯穿塔架，顶部霁青光场表示网络与区块加载已激活。",
            panel_size=(650, 770), center=(0, 52, 0), pitch=12),
        "08-aetheric-condensate.png": fluid_sheet(mats),
        "09-network-coverage.png": network_sheet(mats),
    }
    for name, im in renders.items():
        p = OUT / name
        im.convert("RGB").save(p)
        with Image.open(p) as check:
            check.verify()
        print(p)
    write_spec()
    print(DOC / "visual-system-v2.json")
    print(f"PASS: {len(renders)} renders generated")


if __name__ == "__main__":
    main()
