# 远仓工程装甲 V1

独立美术概念，未修改模组资源、逻辑或注册。蓝灰分片甲、浅钢包边、黄铜扣件与冰蓝护目镜。

textures/distant_layer_1.png 与 distant_layer_2.png 为 64×32 原版护甲 UV 图集，包含头盔、胸甲、护腿、靴子；四张 distant_<部位>.png 为 16×16 物品贴图。使用原版钻石护甲覆盖范围和物品轮廓，关键面单独绘制。

build_art.py 使用项目 scripts/concepts/render_scene.py 和标准人形比例做离线穿戴预览。无附加几何配件；身体下面的深灰色是展示人台，不属于护甲。护甲 UV 与镜像在游戏内仍需后续验证。本稿不是游戏截图，不包含已有护甲的隐形阶段效果。

执行 python3 docs/design/concepts/distant-armor-v1/build_art.py 可重新生成；依赖 Pillow、NumPy 和项目已有 Minecraft 资源归档。
