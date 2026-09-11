# Distant Stock 传输方块美术重构

为一套「跨服物流传输」Create 附属模组重绘全部方块/物品造型。只做概念美术，不注册方块、不改游戏资源。复用 DistantStock 现成的离线渲染管线（`scripts/concepts/`）。

## 美术规范

- 方块 16px/格，占格 X/Z -8..8、Y 0..16，正面朝北；物品按手持造型。

- 只用轴对齐盒体；无曲线、无圆。分段棱柱或方角折中用于仪表/灯饰。

- 材质来自 Create 6.0.10：`andesite_casing` / `andesite_block` / `industrial_iron_block` / `polished_andesite`；另有自绘霁青、蓝白、橙黄故障语义。

- 交互状态只改灯/镜/针/光柱的发光与颜色，外壳几何恒定。

## 方块/物品清单

- **远仓港** `dock` — Distant Dock（block）
- **远仓控制台** `console` — Distant Console（block）
- **远仓监视器** `monitor` — Distant Monitor（block）
- **远仓请求器** `requester` — Distant Requester（block）
- **便携式远仓请求器** `portable` — Portable Distant Requester（item）
- **以太谐振器** `aether_resonator` — Aether Resonator（block）
- **互通塔底座** `tower_base` — Interconnect Tower Base（block）
- **远仓探测仪** `probe` — Distant Probe（item）

## 目录

- `scripts/gen_transmission.py` — 生成脚本，复用 render_scene / create_materials。

- `blocks/<id>/` — 每块的 `concept.json`、`mesh.json`、`README.md`。

- `build/art/` — 渲染输出 PNG（每块 overview + 单视角 + states 对比）。


## 运行

```sh
python3 -B docs/design/transmission/scripts/gen_transmission.py
```

预览缩略：每张 `*-overview.png` 是主视图汇总，`tower-hero.png` 是互通塔整体，`line-hero.png` / `line-plan.png` 是产线大场景。


## 互通塔

两格结构：`tower_base`（地面）+ 空 + `aether_resonator`（塔顶）。以太谐振器向天空发出淡蓝光柱（信标式），`probe` 手持显示其使能的区块范围。


## 产线大场景

`line-hero.png`：一条直线产线 mockup，`打包机 -> 皮带 -> 漏斗 -> 远仓港 -> 互通塔`。打包机/漏斗取自已装 Create（read-only 参考），皮带与地面为示意材质，`dock` 旋转 90°让靠泊口朝西接收皮带、上手口朝东指向互通塔。`line-plan.png` 为带标注的平面部署图。

仅作产能参考；Create 机械的朝向/动力接法、皮带走向需在游戏内验证。
