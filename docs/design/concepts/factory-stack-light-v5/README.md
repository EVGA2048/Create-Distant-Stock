> 已取消：用户最终选择 V2。正式开发请使用 [V2交接包](../../factory-stack-light-approved-v2/README.md)。本目录仅保留历史方案。

# 工厂三色灯 V5：仅加高下灯座，黄铜顶盖

按用户纠正，绿灯下方灯座恢复为原来6×6的宽度，仅从1单位加高至3单位。取消V4额外外扩和出声孔；顶盖与两道灯间连接环统一使用Create原版黄铜纹理。

- 下灯座：[5,5,5] 到 [11,8,11]，6×6×3，原生安山材质。
- 顶盖与两道连接环：6×6×1，原生黄铜材质。
- 灯罩：5×5×5单层半透明壳，原生像素布局与alpha=204，无灯芯。
- 安装脚8×8×2、铁支柱2×2×3不变，总高26单位。
- 54个面均保持1纹素/模型单位；未缩放纹理。

运行 `python3 docs/design/concepts/factory-stack-light-v5/render_preview.py`，使用项目 `scripts/concepts/render_scene.py`。

`preview-v5.png` 展示三分之四及正视；`factory-stack-light-v5.png` 包含原版物品参照；`three-quarter.png` 是单独预览。模型JSON和贴图保存在本目录，仅为独立美术方案，未接入游戏。原始材质取自本机Create 6.0.10，来源详见相邻V2说明。
