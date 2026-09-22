> 已取消：用户最终选择 V2。正式开发请使用 [V2交接包](../../factory-stack-light-approved-v2/README.md)。本目录仅保留历史方案。

# 工厂三色灯 V3：黄铜连接环

在 V2 的几何和像素密度上，仅把红/黄、黄/绿之间的两道连接环换成 Create 原版 `brass_block` 材质。底座、顶盖、最下方灯托仍为安山，支柱仍为工业铁；三色半透明单层灯罩和 alpha=204 不变，没有独立灯芯。

黄铜顶面保留原生边缘像素并删去中间行列来适配 6×6 面；侧面取原生亮边的一像素高条带。未缩放纹理，每个面均为 1 纹素 / 模型单位。54 面 UV 密度验证通过。

运行 `python3 docs/design/concepts/factory-stack-light-v3/render_preview.py`，直接使用项目 `scripts/concepts/render_scene.py`；输出仅写本目录。

- `brass-comparison.png`：V2/V3 同角度、同尺度、同明度对比。
- `factory-stack-light-v3.png`：完整设计页，包含原版物品参照。
- `three-quarter.png`：单独预览。
- `model-*.json`、`textures/`：本地预览模型与材质。

总高24单位（1.5格），底座8×8，灯罩5×5×5。此目录是独立美术方案，未接入正式游戏资源或修改程序；`study:` 为本地预览命名空间。渲染图展示几何与材质，不是游戏截图。原始材质来源和灯罩方案参见相邻 V2 的 README。
