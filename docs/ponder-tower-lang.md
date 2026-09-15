# 互通塔 Ponder 文案（待并入 lang 文件）

场景 `distant_tower`（结构 `assets/distantstock/ponder/tower.nbt`，由 `scripts/gen_ponder_structures.py`
生成）。这里的键直接照 `lang/en_us.json` 与 `lang/zh_cn.json` 的写法排，**合并时请把英文列抄进
`en_us.json`、中文列抄进 `zh_cn.json` 的对应位置**，键名两边一致，`text_N` 的序号就是场景里第 N 段
文字的顺序。

| 键 | en_us | zh_cn |
|---|---|---|
| `distantstock.ponder.distant_tower.header` | Raising an interlink tower | 建造互通塔 |
| `distantstock.ponder.distant_tower.text_1` | A tower is driven from below: one vertical shaft, set directly under the base. The underside is the only face that takes rotation, so a shaft run in from the side meets nothing. Speed is not the price — a medium network will do; what a tower spends is stress. | 一座塔的动力从脚下送上来：在底座正下方接一根竖直的传动轴。底面是底座唯一接受旋转的接面，从侧面接过来的轴它认不到。转速不必快，中速（30 转/分）即可——真正吃紧的是应力。 |
| `distantstock.ponder.distant_tower.text_2` | Above the shaft goes the Interlink Tower Base — the one part of a tower that turns, and the one that keeps the count. Close the base off with a 3x3 ring of Distant Casing: the casing has no say in whether a tower stands, but it is the base's face. | 在轴的正上方放一块互通塔底座——整座塔只有它会转，等级与带载也都记在它身上。四周再用远仓机壳围出 3×3 的裙板收口：成塔判定只认底座、耦合器与谐振器，裙板不参与其中，但它是塔基的门面。 |
| `distantstock.ponder.distant_tower.text_3` | Stack Interlink Tower Couplers upward, and keep the mast unbroken from the base: one gap, or one segment of another block, and there is no tower at all. Five segments is the first tier. | 往上堆互通塔耦合器，桅杆要从底座一路连续长上去：中间断一格，或者夹进别的方块，塔就不成立。五段是 I 级的门槛。 |
| `distantstock.ponder.distant_tower.text_4` | Cap the mast with an Ether Resonator and the tower is up. The column over the cap is its state lamp: grey while the tower is unfinished or standing still, cyan while it works, and a deeper, self-lit blue while a parcel crosses it. | 顶上盖一台以太谐振器，塔就立起来了。塔顶的光柱就是它的状态灯：还没成塔、或塔没在转时是暗灰，工作时是青色，有包裹经过时会变成更深的蓝色并自己发亮——从塔底抬头就能看清。 |
| `distantstock.ponder.distant_tower.text_5` | Height is rank: five couplers is tier I, two more takes the next step, and seventeen couplers is the last of them, tier VII. | 塔越高，等级越高：耦合器堆到 5 段是 I 级，此后每多两段升一级，17 段封顶 VII 级。 |
| `distantstock.ponder.distant_tower.text_6` | A tier decides three things: how many distant devices the tower carries (8 at tier I, 128 at VII), how far from the base they may stand (32 blocks out to 144), and how large a square of chunks it keeps loaded (1x1 up to 7x7). | 等级决定三件事：能带载多少台远仓设备（I 级 8 台，VII 级 128 台）、能照顾到多远的设备（32 格到 144 格）、能常驻加载多大的区块范围（1×1 到 7×7）。 |
| `distantstock.ponder.distant_tower.text_7` | The shaft below feeds the tower rotation, and taller towers draw more of it: 256 at tier I, roughly doubling a step to 16384 at VII. Fall below a medium speed, or overstress the network, and the light goes out. | 塔从下方那根轴吃旋转动力，等级越高吃得越多：I 级 256，此后逐级约翻倍，VII 级 16384。转速掉到中速以下，或者网络应力过载，塔顶的光柱都会暗下去。 |
| `distantstock.ponder.distant_tower.text_8` | Pull the lever and the casing it lights turns its centre into a see-through window, spreading along the casings it touches. It is decoration only — the tower works exactly the same. | 把拉杆按下去：被红石点亮的机壳，中间会变成透明的观察窗，并顺着相连的机壳一路传开。这只是外观，塔照常工作。 |

## 文案里出现的数字都是哪来的

写的时候逐条核过代码，合并前如果要改数字，请一起改：

- 3×3、五段起步、17 段封顶、7 个等级：`block/TowerTier.java` 的等级表（5/7/9/11/13/15/17），
  `block/TowerStructure.java` 的 `MIN_COUPLERS = 5`。
- 8/128 台设备、32/144 格半径、1×1 到 7×7 区块：`TowerTier` 的 `devices()`、`radius()`、
  `chunkSide()`（I 级与 VII 级两端）。
- 应力 256 到 16384：`TowerTier.stress()`。它是 Create 的 stress impact，实际开销还会乘以转速，
  文案里没写这层，只说了「等级越高吃得越多」。
- 中速 30 转/分：`TowerCoreBlock.getMinimumRequiredSpeedLevel()` 要求 MEDIUM，而
  `IRotate.SpeedLevel.MEDIUM.getSpeedValue()` 读的是 Create 配置 `kinetics.mediumSpeed`，默认 30。
  场景里立轴给的 32 转/分正好过线。
- 光柱三态（暗灰 / 青色 / 深蓝自发光）：`client/ResonatorRenderer.java` 的三档 tint 与
  `block/ResonatorBlockEntity.java` 的 `Beam`。
- 观察窗：`block/TowerCasingBlock.java` 的 `POWERED`；它由红石信号沿相连机壳传播而来，传播上限
  是 `StockConfig.casingRedstoneRange()`（默认 32）。
