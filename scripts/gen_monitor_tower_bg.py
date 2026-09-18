#!/usr/bin/env python3
"""远仓监视器「塔」那一页的底色。

玩家 2026-09-17 报的原话是「远仓监视器的背景是为链路页设计的，塔页面会完全不适配」。确实如此：
监视器就一张 272x190 的绘制图，右边那半张全是链路页的家具 —— 两个大读数框、五个计数器格子。
塔页把一行行塔画上去，那些空框就露在下面，像是没画完。

做法是照着同一张图的**框和页眉**另拼一张更高的：
  0..47    页眉 + 页签那一条（原样复制，两页共用）
  48       平的一行（x7..260 全是 (239,246,243)），往下重复，想多高就多高
  177..189 底边（原样复制）
这样塔页有自己的地方放四座塔 + 一行"还有几座" + 一行区块选区，而链路页一个像素没动。

用法：python3 scripts/gen_monitor_tower_bg.py
输出：src/main/resources/assets/distantstock/textures/gui/monitor_tower.png
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src/main/resources/assets/distantstock/textures/gui/monitor.png"
OUT = ROOT / "src/main/resources/assets/distantstock/textures/gui/monitor_tower.png"

# 复制哪几段：页眉到页签下面那一行、以及底边。中间那行是纯底色的样板。
HEADER = (0, 48)          # [0, 48)
BODY_SAMPLE = 48
FOOTER = (177, 190)       # [177, 190)
# 塔页的高度：和 MonitorScreen.TOWER_H 是一份。四行塔（每行 36）+ 摘要 + 溢出说明 + 区块选区。
HEIGHT = 256


def main() -> None:
    if not SOURCE.is_file():
        raise SystemExit(f"找不到监视器底图：{SOURCE}")
    sheet = Image.open(SOURCE).convert("RGBA")
    width = sheet.width

    out = Image.new("RGBA", (width, HEIGHT), (0, 0, 0, 0))
    out.paste(sheet.crop((0, HEADER[0], width, HEADER[1])), (0, 0))

    body = sheet.crop((0, BODY_SAMPLE, width, BODY_SAMPLE + 1))
    body_rows = HEIGHT - (HEADER[1] - HEADER[0]) - (FOOTER[1] - FOOTER[0])
    for row in range(body_rows):
        out.paste(body, (0, HEADER[1] + row))

    out.paste(sheet.crop((0, FOOTER[0], width, FOOTER[1])), (0, HEIGHT - (FOOTER[1] - FOOTER[0])))
    out.save(OUT)
    print(f"{OUT.relative_to(ROOT)}  {out.size}  页眉 {HEADER[1]} 行 + 底色 {body_rows} 行 + 底边 "
          f"{FOOTER[1] - FOOTER[0]} 行")


if __name__ == "__main__":
    main()
