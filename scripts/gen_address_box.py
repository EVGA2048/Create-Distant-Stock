#!/usr/bin/env python3
"""从 Create 自己的界面贴图里裁出那个地址框，做成我们自己的 3 段式贴图。

为什么是裁而不是画：玩家要的是"和 Create 那个框一模一样的手感"。那个框（左边一卷铜丝 + 一条羊皮纸）
不是独立的 sprite，它是**烧在每张窗口底图里**的 —— `create:textures/gui/requester.png` 里那一条，
`factory_gauge.png` 里也有自己的一条。所以想一模一样，只能把那几像素原样搬过来（Create 是 MIT）。

裁出来的条按 3 段用：左端（铜丝 + 框的左圆角）、中间 1 像素（横向拉长成任意宽度）、右端（羊皮纸
右缘 + 黑描边）。中间那 1 像素竖着取，羊皮纸内部的横纹是水平方向的，横向重复不会花。

**裁的范围是量出来的，不是估的**（`requester.png` 的窗口原点为主，即 (16,16)）：

    y 58..59   铜丝的顶卷 + 木纹
    y 63       框顶的黑描边
    y 64..79   羊皮纸（里面 65 / 77 / 79 行是纸的横纹）
    y 80       框底的黑描边
    y 81..82   框投在木纹上的影子，左边 x 32 起、右边到 183
    x 26..45   铜丝 + 框的左端
    x 46..181  羊皮纸（可拉长的那一段）
    x 182..184 纸的右缘 + 黑描边

第一版裁的是 y 76..100 —— 那是**框底往下**、连着灰按钮条的位置，所以生成出来的纸只有框的下半截
加一条影子，玩家看到的就是"下面的地址栏有阴影且错位"。裁剪窗口差 13 像素，整条纸就是废的。

用法：python3 scripts/gen_address_box.py
输出：src/main/resources/assets/distantstock/textures/gui/address_box.png
"""

from __future__ import annotations

import io
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
MODPACK = (Path.home() / "Documents/minecraft_launcher/.minecraft/versions"
           / "ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods")
CREATE_JAR = MODPACK / "create-1.21.1-6.0.10.jar"
SOURCE = "assets/create/textures/gui/requester.png"

# 上面那些数字都是**窗口内**的坐标，而窗口在那张 sheet 上的原点是 (16,16) —— 裁剪时要各加 16。
SHEET = 16

CROP_TOP, CROP_BOTTOM = 58, 83          # 25 高
LEFT_CAP = (26, 45)                     # 20 宽：铜丝 + 框的左端
MIDDLE_X = 100                          # 羊皮纸内部任意一列：横向拉长的那 1 像素
RIGHT_CAP = (182, 184)                  # 3 宽：纸的右缘 + 黑描边

# 这几张常量是给 Java 那边对表的：AddressStrip 里的 LEFT/MIDDLE/RIGHT/HEIGHT 必须和它们一致。
LEFT_W = LEFT_CAP[1] - LEFT_CAP[0] + 1
MIDDLE_W = 1
RIGHT_W = RIGHT_CAP[1] - RIGHT_CAP[0] + 1
HEIGHT = CROP_BOTTOM - CROP_TOP
# 输入框（Create 的 AddressEditBox）相对这张贴图左上角的位置 —— 直接量 Create 自己的窗口：
# 框的黑顶边在贴图第 5 行，输入框的顶端在贴图第 10 行（68 - 58）。
BOX_TOP = 5
TEXT_X = 29                             # 55 - 26
TEXT_Y = 10                             # 68 - 58
BOX_H = 10

OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/address_box.png"

# 那一条在 Create 的窗口底图上是画在木纹背景上的，背景是这两种棕色之一。我们把它贴到自己的
# 界面上（底下的东西也是木纹，但位置不一样），所以这两种颜色必须抠成透明 —— 不抠就是一块方方正正的
# 棕板子。
#
# **只抠这两种。** (96,61,57) 看着像背景（框下面那道投影就是它），但铜丝自己也用这个颜色描边，
# 一起抠掉的话铜丝上会多出一排排小洞 —— 第一版就是这么抠的。投影本来也不该删：它在 Create 的窗口里
# 就是框压在木纹上的那一下，位置对了一切都对。
BACKGROUND = {(140, 93, 75), (135, 90, 77)}


def main() -> None:
    if not CREATE_JAR.is_file():
        raise SystemExit(f"找不到 Create 的 jar：{CREATE_JAR}")
    with zipfile.ZipFile(CREATE_JAR) as jar:
        sheet = Image.open(io.BytesIO(jar.read(SOURCE))).convert("RGBA")

    left = sheet.crop((SHEET + LEFT_CAP[0], SHEET + CROP_TOP,
                       SHEET + LEFT_CAP[1] + 1, SHEET + CROP_BOTTOM))
    middle = sheet.crop((SHEET + MIDDLE_X, SHEET + CROP_TOP,
                         SHEET + MIDDLE_X + 1, SHEET + CROP_BOTTOM))
    right = sheet.crop((SHEET + RIGHT_CAP[0], SHEET + CROP_TOP,
                        SHEET + RIGHT_CAP[1] + 1, SHEET + CROP_BOTTOM))

    strip = Image.new("RGBA", (LEFT_W + MIDDLE_W + RIGHT_W, HEIGHT), (0, 0, 0, 0))
    strip.paste(left, (0, 0))
    strip.paste(middle, (LEFT_W, 0))
    strip.paste(right, (LEFT_W + MIDDLE_W, 0))
    knock_out_background(strip)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    strip.save(OUT)
    print(f"{OUT.relative_to(ROOT)}  {strip.size}  "
          f"left={LEFT_W} middle={MIDDLE_W} right={RIGHT_W} height={HEIGHT} "
          f"box_top={BOX_TOP} text=({TEXT_X},{TEXT_Y})")


def knock_out_background(image: Image.Image) -> None:
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if (r, g, b) in BACKGROUND:
                pixels[x, y] = (0, 0, 0, 0)


if __name__ == "__main__":
    main()
