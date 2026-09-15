# 远仓设备配方提案（待商讨）

一份让讨论能落到数字上的清单。左边是现在游戏里跑的东西，右边是缺口和几个可选档位。
**没有改动任何现有配方**——现有的是你早先定的，改不改由你决定。

## 一、现状：两套价位并存

| 档位 | 材料 | 设备 |
|---|---|---|
| 早期（安山岩/铜/诡异菌柄） | `minecraft:andesite`、`minecraft:copper_ingot`、`minecraft:stripped_warped_stem`、`minecraft:redstone`、`minecraft:glass_pane` | 远仓港、请求台、便携请求器、监视器 |
| 早期（半成品） | `create:packager` + 淡蓝染料 + 紫水晶碎片 | 远仓打包机 |
| 毕业期（黄铜/精密构件/以太石英） | `create:brass_sheet`、`create:precision_mechanism`、`create:gearbox`、`distantstock:ether_quartz`、`polished_ether_quartz`、`minecraft:amethyst_block` | 互通塔底座/耦合器/机壳、以太谐振器 |

**问题**：远仓的定位是「Create 工业毕业期产物，必须贵于无人机物流和纸飞机，与铁路相当或略贵」
（你自己定的），但码头侧那一排设备（港、请求台、请求器、监视器）是安山岩+铜+诡异菌柄的
开局成本。现在的结果是：**跨服能力用开局材料就能搭起来，只有塔贵**。

两条路，选一条：

- **A. 保持现状**：码头设备是入门件，塔是毕业件，跨服的门槛由塔（以及以太材料）承担。
- **B. 上调码头侧**：把跨服能力本身做成毕业期内容。下表给的是 B 的具体数字。

## 二、新增设备（本轮刚做完，目前只有创造栏）

### 远仓仪表 `distantstock:remote_gauge`

它是「会自己下单的工厂仪表」，核心部件是跨服寻址，所以按 B 档：

```
提案：  A P A          A = create:andesite_alloy   （或 create:brass_sheet）
       P G P          P = distantstock:polished_ether_quartz
        A P A          G = create:factory_gauge
```
产量 1。理由：一张工厂仪表 + 四块磨制以太石英把「跨服」写进配方里，
安山合金做骨架，价格落在耦合器与底座之间。

### 远仓红石请求器 `distantstock:remote_redstone_requester`

它是 Create 红石请求器的跨服版，按同一档：

```
提案：  B P B          B = create:brass_sheet
       P R P          P = distantstock:polished_ether_quartz
        B P B          R = create:redstone_requester
```
产量 1。理由与仪表对称：一台 Create 请求器 + 八块磨制以太石英，
是「已有的机器 + 跨服改造件」的形状，和远仓打包机（Create 打包机 + 紫水晶）一脉相承。

### 便携请求器 `distantstock:requester`（现有，建议调整）

现在只要 3 个诡异菌柄 + 2 铜 + 1 红石。它是**绑定的唯一手段**——没有它，仪表、红石请求器、
港组都配不了。当前成本让「跨服」在几分钟内就能开局。

```
可选：  W Q W          W = minecraft:stripped_warped_stem
       W C W          Q = distantstock:ether_quartz
        R             C = minecraft:copper_ingot / create:brass_sheet
                       R = minecraft:redstone
```
只把中间那格换成以太石英。改动很小，但它让「第一台请求器」必须经过以太材料链
（末影珍珠 → 末影粉 → 熔融紫水晶 → 以太石英），跨服因此自然排在粉碎轮之后。

## 三、需要你定的事

1. **A 还是 B**：码头设备是入门件，还是毕业件？
2. 若选 B：**便携请求器**要不要一起上调？（它是唯一入口，最影响节奏的那个）
3. 仪表/红石请求器用 `andesite_alloy` 还是 `brass_sheet` 做骨架？
4. 要不要给某些设备加**序列装配**（Create 的机械臂产线）版本？塔底座这种量产的部件
   很适合，但会把「毕业期」的门槛推到机械臂上。
5. Ponder 现在教的是「组装一座塔」；配方定案后要不要再加一章「接一台请求器到远端仓库」。

## 四、还没进配方表的其它东西

- 以太凝液/熔融紫水晶的**桶装**：现在有瓶（250mB），没有桶（1000mB）。
- 监控器/信号灯目前是早期材料，若要 B 档应同步上调。
- 高阶机壳（`tower_casing` 之外）与「世界互通塔」（未开工）的配方都还没有。

## 附：现有配方原文

| 设备 | 配方 |
|---|---|
| 远仓港 | `AWA / WCW / AWA`，A=安山岩 W=去皮诡异菌柄 C=铜锭 |
| 请求台 | `AWA / WRW / AWA`，R=红石 |
| 便携请求器 | ` W / WCW / R ` |
| 监视器 | `AWA / WGW / AWA`，G=玻璃板 |
| 远仓打包机 | ` D / APA / C `，P=Create 打包机 D=淡蓝染料 A=紫水晶碎片 C=铜锭 |
| 塔底座 | `PBP / BGB / PBP`，P=磨制以太石英 B=黄铜板 G=齿轮箱 |
| 耦合器 ×2 | ` A / AQA / A `，A=安山合金 Q=以太石英 |
| 机壳 ×4 | ` I / IQI / I `，I=铁板 Q=磨制以太石英 |
| 以太谐振器 | `BPB / PMP / A `，M=精密构件 A=紫水晶块 |
