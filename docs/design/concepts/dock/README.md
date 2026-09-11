# 远仓港：Create 停泊港概念

远仓港重做为 **Create 安山机壳风格的包裹停泊港**，与信号塔、控制器共用同一套 Create 材质（`andesite_casing` / `andesite_block` / `andesite_cut_polished` / `industrial_iron_block`），不再使用自画的百叶柜、圆形指针仪表或平面控制面板。

## 结构

一格完整方块，模型像素边界 `x/z -8..8，y 0..16`，正面朝北。

- **底座**：粗安山岩 + 底部工业铁裙边，承重。
- **主体**：内缩一格的安山机壳，四面是 Create 机壳纹理。
- **顶部**：两级白灰铸件盖，保留机械倒角。
- **正面停泊舱口**：凹入的空腔，铁唇包围，深蓝灰内壁；底部中央是凹入的光束发生器底座，上方一颗小状态灯。
- **东面动力接口**：方形轴端 + 方形轴承，接入 Create 动力。
- **南面物流接口**：内凹方形舱口，对皮带 / 漏斗交接包裹。
- **西面**：预留流体接口位置，流体名称、类型、消耗量均未定，不注册虚构流体。

## 状态

| 状态 | 停泊舱 | 状态灯 | 光束 |
|------|--------|--------|------|
| `idle` | 空 | 蓝灰（灭） | 关 |
| `online` | 空 | 霁青（就绪） | 开 |
| `loaded` | 包裹 + 霁青能量包络 | 霁青 | 开 |

光束与能量包络都是**视觉效果**，不注册隐藏方块、不模拟光束物理。包裹停泊、封装、远程发射均为后续游戏逻辑，本轮不实现。

## 接口与输出

- `build(state='idle') -> list[face]`：返回 render_scene 面列表，`idle / online / loaded`。
- `textures() -> dict[str, PIL.Image]`：返回材质，结构件来自 `create_materials`，霁青 / 玻璃 / 光束为本地发光层。
- `concept.json`：设计说明、端口、材质来源与状态语义。
- `mesh.json`：`online` 状态的离线网格，不是 Minecraft block JSON。

生成命令：

```sh
python3 -B scripts/concepts/gen_dock.py
```

输出到 `build/art/concepts/dock-*.png`：`overview`（三视图）、`states`（三态对比）、`front`、`rear`、`side`。

## 尚未实现

包裹远程传输、流体接入、光束物理、结构识别、游戏状态切换均未实现。本轮为离线效果图与材质一致性审图，不修改正式 `src/`、不替换现有 `dock` block model。
