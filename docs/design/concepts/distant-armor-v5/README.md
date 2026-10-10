# 远仓机壳护甲 V5 · 机壳框格

延续 V2 的思路：不加配件，也不改钻石护甲的轮廓，只改贴图。V5 把护甲当作远仓机壳方块来画。护甲 UV 的每一块甲面都按 `tower/casing_inactive.png` 的结构绘制：外圈一像素深色边框，上、左两侧偏亮，下、右两侧偏暗，四角最深；宽度不少于 8 像素的面再加一圈浅色倒角；中心沿用原版的明暗起伏，压成机壳中心的低对比浅灰蓝。头盔面孔开口和靴口这类原版镂空同样会描边，看起来像机壳的切口。

整套护甲只有胸前一处装饰：2×2 激活态机壳小窗，外围一圈凹槽色，颜色取自 `casing_active.png`，与远仓塔启动时的机壳一致。没有黄铜扣件、护目镜、分片甲或立体件。

- `textures/distant_layer_1.png`、`distant_layer_2.png`：64×32 护甲图集
- `textures/distant_{helmet,chestplate,leggings,boots}.png`：16×16 物品图标，用机壳色阶重映射原版明暗，胸甲带同一枚小窗
- `front.png`、`three_quarter.png`、`back.png`、`design_sheet.png`：用 `scripts/concepts/render_scene.py` 生成的穿戴预览。灰色人台只用于展示

校验结果由脚本断言：六张贴图的 Alpha 与原版钻石贴图逐字节一致；所用颜色全部来自两张远仓机壳贴图的现有调色板，没有新增颜色。运行 `python3 docs/design/concepts/distant-armor-v5/build_art.py` 可复现。

实装只需替换贴图，不涉及模型。相邻甲面的接缝会出现双线边框，这和 Create 机壳方块拼接时的效果相同。这是独立概念稿，未修改正式资源，也未接入模组。
