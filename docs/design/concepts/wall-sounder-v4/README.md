# 壁挂声光报警器 V4 · 灯罩折中比例

![预览](sounder_preview.png)

用户认为 V3 太宽太扁，本版将灯罩从 **7×5×2 改为 6×5×3**：宽度减少 1，高度不变，厚度增加 1。保留无托座、无铁脚设计、安山机身和半透明红/橙灯罩。

灯罩范围 x=5..11、y=3..8、z=11..14，背面贴住背板，前端与上方栅条齐平。原版 3×4 U 形灯芯放在 z=12.5，处于灯罩厚度中央。

玻璃贴图沿用 Create 库存链接器纹素与 Alpha 204；正面仅增加一列中间纹素，深度保留前、中、后三列或行，不缩放纹素。灯罩各面保持 1 纹素/模型单位。

`sounder_*.json` 为四种状态模型；`textures/` 为纹理；`sounder_on_wall.png` 为壁挂示意；`sounder_side_profile.png` 为侧视；`sounder_pulse.gif` 延续亮 100ms / 灭 1400ms 的无声节奏。

运行 `python3 docs/design/concepts/wall-sounder-v4/build_art.py` 可复现。已通过模型边界与各面 UV 密度检查，并查看生成的主预览。仅美术概念资产，未接入正式游戏或音效；来源及素材许可处理延续 V2/V3。
