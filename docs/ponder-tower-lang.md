# 互通塔思索维护入口

本文件原先的待合并文案已过时，不再作为实现依据。最新场景参见：

- `src/main/java/dev/distantstock/client/ponder/TowerScenes.java`
- `scripts/gen_ponder_structures.py` 中的 `tower_scene`
- `src/main/resources/assets/distantstock/lang/{zh_cn,en_us}.json` 的 `distantstock.ponder.distant_tower.*`
- `docs/ponder-design.md`

已核对的关键规则：八块机壳组成的完整 3×3 塔基参与成塔判定；至少五段耦合器连续连接底座与谐振器；机壳流体接口需要扳手开启，包括底面在内的接口均可用；仅底座底面专用于旋转动力输入。勿沿用旧文档中的“裙板不参与判定”或“机壳底面不能输入流体”。
