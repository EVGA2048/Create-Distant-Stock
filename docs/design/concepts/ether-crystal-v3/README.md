# 以太晶体 V3 · 冰蓝折光

斜向尖棱轮廓，冰蓝亮面、钴蓝背光窄面、少量蓝紫底部折射，内部有小型白青晶核。替代前两版圆润卵石式轮廓的全新候选设计，旧版保留。

- `assets/distantstock/textures/item/ether_crystal.png`：16×16 原生密度版，最近邻取样后单独修整尖端、内部晶核与背光边。
- `assets/distantstock/textures/item/ether_crystal_32.png`：32×32 精细备选，保留更多内部折射。
- `preview.png`：两种尺寸的并排放大展示；背景仅用于展示，不写入贴图。
- `design_source.png`：内置 imagegen 生成的原始设计图。
- `prompt.txt`：完整生成提示词。
- `build_art.py`：将设计稿整理成像素网格贴图并生成预览的 Python 脚本。

两张 PNG 均有透明背景，保留取样到的原始 Alpha；晶体主要靠色块模拟透光与反射，主体接近不透明，并非游戏中的折射材质。只交付贴图概念，不覆盖正式资源，不更改注册或渲染逻辑。来源为内置 imagegen（非 CLI）原创生成，经 Python 最近邻缩小和 16×16 局部像素修整。
