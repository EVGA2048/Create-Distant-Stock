> 已取消：用户最终选择 V2。正式开发请使用 [V2交接包](../../factory-stack-light-approved-v2/README.md)。本目录仅保留历史方案。

# 工厂三色灯 V4：黄铜连接环与蜂鸣器座

按用户追加意见，在 V3 黄铜连接环方案上加厚绿灯下方的底托，形成蜂鸣器壳体。

- 红黄、黄绿之间两道连接环：保留 Create 原版黄铜纹理。
- 最下方底托：6×6×1 改成 7×7×3，四周各多出半个模型单位；正面三个一像素深色点作为出声孔视觉。
- 安山安装脚 8×8×2、铁支柱 2×2×3 不变。为保留支柱高度，灯组整体上移2单位，总高26单位（1.625格）。
- 灯罩仍为原生5×5×5像素的单层透明壳，alpha=204，没有灯芯。
- 54个面继续保持1纹素/模型单位。出声孔用原生工业铁暗色像素绘入本地贴图，不添加细于一纹素的线条。

运行 `python3 docs/design/concepts/factory-stack-light-v4/render_preview.py`，复用项目 `scripts/concepts/render_scene.py`。输出限定在本目录。

`buzzer-comparison.png` 是V3/V4同尺度、同角度对比；`factory-stack-light-v4.png` 为完整设计页；`three-quarter.png` 为单独预览。`model-*.json` 与 `textures/` 是可复现的预览源文件，`study:` 命名空间尚未接入游戏。

仅新增独立美术方案，未实现声音、注册、碰撞等程序功能，未修改正式资源。离线图展示实际几何与UV，不是游戏截图。原生材质来源详见相邻V2、V3说明。
