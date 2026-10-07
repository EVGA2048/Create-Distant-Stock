已确认：诊断 Ping包

用户最初确认「就这样」，随后实机指出 Ping 标志与 Create 包裹眼睛重叠，并要求「把 ping 包的标志改到另一个方向的面上」。

锁定柠檬黄箱体；眼睛所在的 east/west 两面恢复为原始箱体纹理，不再叠加标志。线缆 Ping 标志改到 north/south 共用的另一组侧面 UV；中段垂直、保留原来的线条明暗，左上暖白十字端点，右下淡蓝十字端点。模型与 UV 不变。

remote_package_ping.png 为 128×128 图集；粒子贴图保持柠檬黄。预览为离线模型渲染。可复现生成脚本保留于 ../../concepts/lemon-ping-package-cable-blue/build_art.py。

本目录为当前确认美术资产，并已同步到游戏 runtime 的 ping_package 纹理。
