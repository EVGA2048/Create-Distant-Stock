#!/usr/bin/env python3
"""远仓监视器正面的画：一块翻牌显示器的底衬 + 一圈状态色的框。

监视器的正面现在是**真的**翻牌显示器（`MonitorBlockEntity extends FlapDisplayBlockEntity`），
Create 的渲染器会在面之前 2 像素处画字形。所以这张贴图不能再画「一块翻牌」——
它会和真的字形重叠。这里画的是：

    1 像素外框（铁）→ 1 像素状态色描边 → 内凹的深色底衬

状态色由方块状态的 status 决定（绿=正常 / 橙=卡顿 / 红=掉帧），
所以状态灯从「右上角两个小灯」变成了整块板的描边 —— 原来那两个小灯的位置
正好落在字形的平面上，会跟字打架，而且底衬变深之后它们也看不清。
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
TEXTURES = ROOT / "src/main/resources/assets/distantstock/textures/block"
MODELS = ROOT / "src/main/resources/assets/distantstock/models/block"

# 和 Create 的翻牌字形同一个色系：字是浅暖灰（Create 的默认行色 #D3C4BA），
# 所以底衬必须够深，不然字读不出来。
IRON = (139, 160, 164)
IRON_L = (183, 202, 203)
IRON_D = (72, 88, 93)
INK = (18, 22, 26)
LIP = (42, 49, 56)

STATUS = {
    "green": (96, 181, 130),
    "orange": (201, 152, 74),
    "red": (198, 92, 84),
}


def face(status_rgb):
    im = Image.new("RGBA", (16, 16), IRON_D)
    d = ImageDraw.Draw(im)
    # 外框：左上受光、右下压暗，和别的机器方块一致
    d.rectangle((0, 0, 15, 15), outline=IRON)
    d.line((0, 0, 15, 0), fill=IRON_L)
    d.line((0, 0, 0, 15), fill=IRON_L)
    d.line((15, 0, 15, 15), fill=IRON_D)
    d.line((0, 15, 15, 15), fill=IRON_D)
    # 状态描边
    d.rectangle((1, 1, 14, 14), outline=status_rgb)
    # 内凹的底衬，顶上一条亮边假装是凹进去的
    d.rectangle((2, 2, 13, 13), fill=INK)
    d.line((2, 2, 13, 2), fill=LIP)
    d.line((2, 2, 2, 13), fill=LIP)
    return im


def main():
    for name, rgb in STATUS.items():
        face(rgb).save(TEXTURES / f"monitor_face_{name}.png")
    # 物品栏与未定状态用绿色那块，和方块状态默认值一致
    face(STATUS["green"]).save(TEXTURES / "monitor_face.png")

    textures = {
        "back": "distantstock:block/andesite_block_white_blue",
        "side": "distantstock:block/monitor_side",
        "top": "distantstock:block/monitor_top",
        "particle": "create:block/andesite_casing",
    }
    for name in STATUS:
        model = {
            "parent": "minecraft:block/block",
            "render_type": "minecraft:translucent",
            "textures": {**textures, "face": f"distantstock:block/monitor_face_{name}"},
            "elements": [{
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
            }],
        }
        (MODELS / f"monitor_{name}.json").write_text(json.dumps(model, indent=2) + "\n")
    # 方块本体（也是物品图标用的那个）跟绿色状态一致
    (MODELS / "monitor.json").write_text((MODELS / "monitor_green.json").read_text())
    print("Generated the monitor face in three status colours and the three block models.")


if __name__ == "__main__":
    main()
