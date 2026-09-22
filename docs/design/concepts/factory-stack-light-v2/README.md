# 工厂三色灯 V2：原生像素模型预览

用户最终选择本V2方案。整理后的自包含交接包位于 [factory-stack-light-approved-v2](../../factory-stack-light-approved-v2/README.md)，请开发agent以该包为准。本目录保留探索阶段生成源，未注册方块、未修改正式资源或程序。

## 设计

- 上红、中黄、下绿；8×8×2 安山底座、2×2×3 铁支柱。
- 三层灯罩各 5×5×5；四片分隔盖各 6×6×1；总高 24 模型单位（1.5 格）。
- 每层只有一个半透明方块壳，没有独立灯芯、内层发光立方体或额外玻璃框线。
- 所有新模型表面严格使用 1 纹素 / 模型单位，16 单位为一格。半单位坐标用于把 5 单位宽灯罩居中，不改变纹素密度。
- 安山框取原生纹理的边缘像素，移除中间行列来适配尺寸，不把整张 16×16 纹理缩放到小零件上。

## 原版与项目参考

从本机 Create 6.0.10 jar 读取：

- `assets/create/models/block/stock_link/block_vertical.json` 的 `children.bulb`：5 单位立方体，32×32 的 `link_details` 图集中每面对应 5×5 纹素，alpha=204。
- `link_details.png` 的侧面裁切 `[27,5,32,10]`、顶面 `[27,0,32,5]`、底面 `[27,10,32,15]`。绿灯关闭贴图保持原色、原像素分布与透明度；红黄只变色相与饱和度，不改变布局和透明度。
- `factory_gauge/bulb_light.json` 与 `panel_with_bulb.json`：原版工厂仪表对照。
- `andesite_block.png`、`industrial_iron_block.png`：基座、隔片、支柱素材。
- 远仓现有 `signal_panel/andesite_base.json` 和 `indicator_lamp_green_off.png`：同尺度对照。

注意：原版链接器主体另有平面细节；本设计只借用其单层灯罩结构，不移植那些细节。现有远仓面板灯的灯体比例、UV 是原有实现，对照时保持不变；新三色灯单独进行严格 UV 密度校验。

## 预览与复现

运行 `python3 docs/design/concepts/factory-stack-light-v2/render_preview.py`。

直接调用项目 `scripts/concepts/render_scene.py` 的模型加载和渲染函数，不使用图像生成模型，也未修改共享渲染器。所有输出限定在本目录。

- `factory-stack-light-v2.png`：大图、正视、绿灯提亮、原版与现有物品同尺度对照。
- `three-quarter.png`：单独三分之四视图。
- `model-{off,green,yellow,red,all}.json`：实际预览几何与 UV，可用于后续正式建模；`study:` 仅是本地预览资源命名空间，尚不能直接注册进游戏。
- `textures/`：16×16 本地预览图集，各区域不重采样。

脚本验证 54 个面全部为 1 纹素 / 模型单位、三灯壳、9 个元素、无独立内芯。

离线预览有方向光和透明混合。绿灯点亮仅模拟材质明度提升，仍保留 alpha=204；没有模拟游戏光照等级、泛光或完整渲染管线。它是几何/UV/贴图预览，不是游戏截图。
