#!/usr/bin/env python3
"""远仓监视器正面：翻牌窗 + 两颗灯的十二种模型。

监视器的正面是**原设计**（铁框、上下黄铜条、四角铆钉），中间那条百叶窗改成一块翻牌窗 ——
Create 的翻牌字形由 `MonitorFlapRenderer` 画在它里面（缩到 0.65 倍，见那个类）。

两颗灯是原设计里就留好的：

    bulb_tps     本端 TPS：绿=正常 / 橙=卡顿 / 红=掉帧
    bulb_status  链路：    灰=未接 / 青=已连接 / 橙=同步中 / 红=故障

色块早就画在 `monitor_bulb.png` 里了 —— 一排五个 2x2（灰 绿 青 橙 红），
但模型的每个面都写着 uv [0,0,2,2]，也就是永远取第一块灰色。模型不能按方块状态换 UV，
能换的只有「用哪个模型」，所以这里按 (TPS 状态 x 链路状态) 生成 12 个模型，
方块状态再按朝向展开成 48 个变体。灯在 y=14..16，和翻牌窗 (y 3..12) 不重叠。

窗的位置不是随手定的：`MonitorFlapRenderer` 把字形压到 0.65 倍、挪到面之前 0.5 像素，
算下来字形块正好落在原来那条百叶窗的位置，所以框、铜条、四角铆钉和两颗灯全都留在原地。
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
TEXTURES = ROOT / "src/main/resources/assets/distantstock/textures/block"
MODELS = ROOT / "src/main/resources/assets/distantstock/models/block"
STATES = ROOT / "src/main/resources/assets/distantstock/blockstates"

# 正面贴图里那条百叶窗的矩形，也是翻牌窗开在哪。

# 灯泡色块：名字 -> 贴图里的 u 偏移。顺序就是那一排。
BULB_UV = {"grey": 0, "green": 2, "cyan": 4, "orange": 6, "red": 8}
TPS_BULB = {"green": "green", "orange": "orange", "red": "red"}
LINK_BULB = {"off": "grey", "online": "cyan", "syncing": "orange", "fault": "red"}


def keep_the_face():
    """The face is the original artwork and stays that way.

    <p>An earlier version of this script painted a recess over the louvres so the flap glyphs would
    have a dark backing. The player's answer was immediate: that is the texture I wanted, put it
    back. The glyphs are drawn in front of it, which is how Create's own board works — its front
    plate is painted too, and the flaps sit behind a window in it.
    """
    print("  monitor_face              left as painted")


def bulb_element(name, lo, hi, swatch):
    """One 2x2 bulb cube, all six faces on the same colour swatch."""
    u = BULB_UV[swatch]
    face = {"texture": "#bulb", "uv": [u, 0, u + 2, 2]}
    return {
        "name": name,
        "comment": ("TPS indicator — green=ok, orange=lag, red=critical" if name == "bulb_tps"
                    else "Link status — grey=off, cyan=connected, orange=syncing, red=fault"),
        "from": lo,
        "to": hi,
        "faces": {f: dict(face) for f in ("north", "south", "west", "east", "up", "down")},
    }


def panel_element():
    return {
        "name": "wall_panel",
        "from": [0, 0, 13],
        "to": [16, 16, 16],
        "faces": {
            "north": {"texture": "#face", "uv": [0, 0, 16, 16]},
            "south": {"texture": "#back", "uv": [0, 0, 16, 16]},
            "west": {"texture": "#side", "uv": [3, 0, 0, 16]},
            "east": {"texture": "#side", "uv": [0, 0, 3, 16]},
            "up": {"texture": "#top", "uv": [0, 0, 16, 3]},
            "down": {"texture": "#top", "uv": [0, 0, 16, 3]},
        },
    }


def write_models():
    textures = {
        "back": "distantstock:block/andesite_block_white_blue",
        "side": "distantstock:block/monitor_side",
        "top": "distantstock:block/monitor_top",
        "face": "distantstock:block/monitor_face",
        "bulb": "distantstock:block/monitor_bulb",
        "particle": "create:block/andesite_casing",
    }
    names = []
    for status, tps in TPS_BULB.items():
        for link, lamp in LINK_BULB.items():
            name = f"monitor_{status}_{link}"
            model = {
                "parent": "minecraft:block/block",
                "render_type": "minecraft:translucent",
                "textures": textures,
                "elements": [
                    panel_element(),
                    bulb_element("bulb_tps", [10, 14, 11], [12, 16, 13], tps),
                    bulb_element("bulb_status", [13, 14, 11], [15, 16, 13], lamp),
                ],
            }
            (MODELS / f"{name}.json").write_text(json.dumps(model, indent=2) + "\n")
            names.append(name)
    # 物品图标与未定状态用「正常 + 已连接」
    (MODELS / "monitor.json").write_text((MODELS / "monitor_green_online.json").read_text())
    return names


def write_blockstate(names):
    variants = {}
    for facing, y in (("north", 0), ("south", 180), ("west", 270), ("east", 90)):
        for status in TPS_BULB:
            for link in LINK_BULB:
                key = f"facing={facing},status={status},link={link}"
                variant = {"model": f"distantstock:block/monitor_{status}_{link}"}
                if y:
                    variant["y"] = y
                variants[key] = variant
    (STATES / "monitor.json").write_text(json.dumps({"variants": variants}, indent=2) + "\n")
    print(f"  monitor.json              {len(variants)} variants over {len(names)} models")


def main():
    keep_the_face()
    names = write_models()
    print(f"  monitor_{{status}}_{{link}}  {len(names)} models")
    write_blockstate(names)


if __name__ == "__main__":
    main()
