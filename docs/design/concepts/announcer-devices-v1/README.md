# 广播设备贴图概念 V1

为黄铜广播器、网络广播器和网络扬声器重新设计的 16×16 贴图。三者都保持整格方块，只换贴图，不改模型形状。所有纹素都取自 Create 原版贴图和远仓机壳贴图，边框直接沿用原贴图，没有新增材质语言。

- 黄铜广播器：以 `create:block/brass_casing` 为底。正面（朝向面，对应现有模型的 up 面）是带倒角的八角扬声格栅，中间一枚黄铜音盖，激活时变成现有 `announcer_lamp_powered` 的青色。侧面上半部保留音符盒木纹，下接黄铜横带和一条指示灯槽，激活时点亮。底面为黄铜机壳。
- 网络广播器：以 `create:block/andesite_casing` 为底。顶面是玫瑰石英灯芯加中央发射口；侧面上部为玫瑰石英透镜带，下部是三级信号格。激活时只提亮玫瑰石英纹素，安山边框不变。
- 网络扬声器：使用远仓机壳 `tower/casing_inactive` 边框，与远仓塔、远仓护甲同一家族。侧面是深色穿孔网罩加一条以太蓝指示条；顶面是圆形纸盆，中心为以太蓝音盖。底面沿用现有贴图。

文件：

- `textures/`：10 张新贴图（广播器正面/侧面、网络广播器顶面/侧面各有 off/on 两态，扬声器侧面/顶面各一张）
- `announcer.png`、`network_broadcaster.png`、`network_speaker.png`：新稿三维预览
- `design_sheet.png`：新稿大图，下方对照现有贴图和新稿激活态，并附贴图平铺

运行 `python3 docs/design/concepts/announcer-devices-v1/build_art.py` 可复现。需要本地 Create 6.0.10 jar（路径见 `scripts/concepts/create_context.py`，可用 `CREATE_JAR` 覆盖）以及 `build/moddev` 下的原版资源包。这是未实装的概念稿，没有修改正式资源、模型或方块状态。
