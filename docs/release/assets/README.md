# 发布美术资源

本目录中用于 README 与发布页的插图全部由 `scripts/render_release_art.py` 调用项目 Python 模型渲染器生成，输入来自当前仓库实际发布的 Minecraft JSON 模型与 PNG 材质，不使用生成式图片。

- `distantstock-icon-512.png`：平台上传首选；另提供 1024、256、128、64 像素 PNG。
- `machines.png`：README 顶部设备总览。
- `logistics.png`：请求、自动化与跨服运输设备。
- `tower.png`：互通塔组件。
- `control.png`：监控、告警与诊断设备。
- `src/main/resources/distantstock-icon.png`：同一图标的 256 像素版本，供 NeoForge 模组列表使用。

插图本身不写设备名、版本号、宣传语或“离线渲染”等说明文字；所有说明都由 README 的 Markdown 承担。图片只负责展示模型，因此以后模型和材质修改后可以直接重新渲染，而不需要手工编辑图中文字。

图标中央为正式物品模型 `distantstock:item/remote_package`，双向箭头为 Pillow 绘制的几何图形。设备插图是静态离线模型展示，不包含游戏中的动画、UI、动态包裹和方块实体附加渲染，不应当作游戏截图。

复现命令：

```bash
python3 scripts/render_release_art.py
```

需要 Pillow 10.1+、NumPy，以及 `scripts/render_block.py` 中 `CREATE` 指定的 Create 6.0.10 JAR。本次使用 Pillow 11.3.0、NumPy 2.0.2。项目既有 `docs/preview/` 图片未纳入这套发布资源。
