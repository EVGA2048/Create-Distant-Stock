#!/usr/bin/env python3
"""把 Create 的红石请求器窗口加长一段棕色，做成我们自己的窗口底图。

玩家要的是"上面棕色的部分变长一点来容纳这些新的输入栏"。那张窗口底图是整块烧在
`create:textures/gui/requester.png` 里的 232x120，所以做法是：把它的棕色段和灰色按钮条切开，
在缝里插进若干行同色的棕色，再拼回去。

**切开的那条缝是有讲究的**（窗口内的行号）：0..15 是蓝色标题条、16..87 是棕色段（9 个幽灵槽在
24..55、Create 自己的地址框在 58..82）、88 是那道黑线、89..117 是灰按钮条、118..119 是底边。
所以缝在 88：棕色段多出来一截，黑线和灰按钮条一起往下走。按钮是**界面上**的东西（Create 在
init() 里按窗口高度 120 摆好），底图加长不会带着它们走 —— 所以 {@code RemoteRedstoneRequesterScreen}
要把灰条里那三个图标的 y 也加一个 BAND，不然它们会留在原来那一行、也就是棕色段中间。

插的是**窗口自己那一行**的副本，不是一块纯色填充 —— 左右两边的深色描边、中间的木纹都在那一行里，
整行复制才能让加长的那段和上面接得上。

用法：python3 scripts/gen_requester_gui.py
输出：src/main/resources/assets/distantstock/textures/gui/remote_requester.png
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

# AllGuiTextures.REDSTONE_REQUESTER = 这张图里 (16,16) 起 232x120 的那一块。
WINDOW = (16, 16, 232, 120)
# 棕色段到灰色按钮条的分界（相对窗口顶端，量出来的）：0..87 是棕的，88 是那条黑分隔线，89 起是灰条。
SEAM = 88
# 插进去的那段有多高。两行地址条 25+2+25 = 52，上下各留 3 像素。
BAND = 58
# 复制哪一行当那段棕色的样板：84..87 都是干净的棕色，取中间那一行。
SAMPLE_ROW = 86

OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/remote_requester.png"


def main() -> None:
    if not CREATE_JAR.is_file():
        raise SystemExit(f"找不到 Create 的 jar：{CREATE_JAR}")
    with zipfile.ZipFile(CREATE_JAR) as jar:
        sheet = Image.open(io.BytesIO(jar.read(SOURCE))).convert("RGBA")

    x, y, width, height = WINDOW
    window = sheet.crop((x, y, x + width, y + height))
    top = window.crop((0, 0, width, SEAM))
    bottom = window.crop((0, SEAM, width, height))

    sample = window.crop((0, SAMPLE_ROW, width, SAMPLE_ROW + 1))
    band = Image.new("RGBA", (width, BAND), (0, 0, 0, 0))
    for row in range(BAND):
        band.paste(sample, (0, row))

    grown = Image.new("RGBA", (width, height + BAND), (0, 0, 0, 0))
    grown.paste(top, (0, 0))
    grown.paste(band, (0, SEAM))
    grown.paste(bottom, (0, SEAM + BAND))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    grown.save(OUT)
    print(f"{OUT.relative_to(ROOT)}  {grown.size}  (窗口 {width}x{height} + 棕色段 {BAND})")


if __name__ == "__main__":
    main()
