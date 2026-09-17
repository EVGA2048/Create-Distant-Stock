#!/usr/bin/env python3
"""给远仓仪表的窗口做一截"往下接"的木质延长段。

玩家的原话是「远仓仪表的UI还是没改，需要改合理了，也可以拉长界面（原来在空白的地方有一层蓝色的
透明层）」—— 上一版把那两行字段画在**窗口外面**，垫了一块深色底板、还给整个窗口糊了一层蓝色。
那不是窗口，那是个补丁。这次把窗口本身接长一段，字段住在窗口里。

工厂仪表的窗口是两块贴图拼的：上段（`FACTORY_GAUGE_RECIPE` 192x96 或 `FACTORY_GAUGE_RESTOCK`
192x40）+ 下段（`FACTORY_GAUGE_BOTTOM` 200x64）。下段的最后两行是窗口的底边（一行深灰 + 一行黑），
所以延长段的第一件事是**把那两行盖掉**，然后再用窗口自己的木纹往下铺，最后补一条黑底边。

铺的木纹是把下段第 28..30 行原样重复 —— 这三行是干净的木头（左边 2 像素黑边 + 高光 + 暗边，
右边一样），复制它们等于让窗口自己的边框继续往下走。**不能拿一整块纯色填**：窗口的木纹是两种棕色
隔两三行换一次的，纯色会和上面的木头断开。

用法：python3 scripts/gen_gauge_band.py
输出：src/main/resources/assets/distantstock/textures/gui/remote_gauge_band.png
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
SOURCE = "assets/create/textures/gui/factory_gauge.png"

# AllGuiTextures.FACTORY_GAUGE_BOTTOM = 这张图里 (32,176) 起 200x64 的那一块。
BOTTOM = (32, 176, 200, 64)
# 复制哪几行当木纹样板：28..30 是地址框下方那段干净的木头。
WOOD_ROWS = (28, 29, 30)
# 窗口底边的行：只有它整个是黑的。
EDGE_ROW = 63

# 延长段的高度。相对它自己的顶端：
#   0..1    盖掉窗口原来的底边（深灰 + 黑）
#   2..3    木头
#   4..28   第一行地址条（25 高）
#   29..30  间隔
#   31..55  第二行地址条
#   56..57  间隔
#   57..64  只读的绑定说明那一行（8 像素高的字）
#   65..66  木头
#   67      新的底边
COVER = 2
BAND_H = 68
ROW_TOP = 4
ROW_STEP = 27
HINT_TOP = 57

OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/remote_gauge_band.png"


def main() -> None:
    if not CREATE_JAR.is_file():
        raise SystemExit(f"找不到 Create 的 jar：{CREATE_JAR}")
    with zipfile.ZipFile(CREATE_JAR) as jar:
        sheet = Image.open(io.BytesIO(jar.read(SOURCE))).convert("RGBA")

    x, y, width, height = BOTTOM
    bottom = sheet.crop((x, y, x + width, y + height))

    band = Image.new("RGBA", (width, BAND_H), (0, 0, 0, 0))
    wood = [bottom.crop((0, r, width, r + 1)) for r in WOOD_ROWS]
    for row in range(BAND_H - 1):
        band.paste(wood[row % len(wood)], (0, row))
    band.paste(bottom.crop((0, EDGE_ROW, width, EDGE_ROW + 1)), (0, BAND_H - 1))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    band.save(OUT)
    print(f"{OUT.relative_to(ROOT)}  {band.size}  "
          f"cover={COVER} row_top={ROW_TOP} row_step={ROW_STEP} hint_top={HINT_TOP}")


if __name__ == "__main__":
    main()
