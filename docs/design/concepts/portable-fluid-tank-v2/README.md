# 便携储液罐 V2

独立美术方案，未接入模组。原生 16×16 透明 PNG。

罐体采用 Create 流体储罐的红铜色阶，阶梯式圆肩、上下铜卷边、深铁色接口与内嵌玻璃观察窗。没有预绘液体或固定液位。

- `portable_fluid_tank.png`：罐壳与中性半透明玻璃高光。
- `portable_fluid_tank_fluid_mask.png`：观察窗内区域的白色遮罩，供未来程序限定流体绘制范围。先绘制流体，再覆盖罐体贴图。此处不包含渲染代码。
- `preview.png` / `preview_dark.png`：浅色与深色底放大预览。

运行 `python3 build_art.py` 重建。
