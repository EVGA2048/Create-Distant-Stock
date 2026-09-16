# 与 Extra Gauges / Create: Deployer 的面板兼容（调研 + 方案）

「大改」那条。先把事实钉死，再谈做什么——上一轮我给过一个**错的结论**，这份文档从纠正它开始。

## 〇、先纠正上一轮的错误

我说过「你的整合包没装 Extra Gauges」。**那是错的**——我只看了 `.minecraft/mods/`（HMCL 的全局目录），
你实际在玩的是**版本实例自己的 `mods/`**：

```
~/Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/
    deployer-0.1.2.jar            ← Create: Deployer API（面板类型注册表）
    extra_gauges-2.1.2.jar        ← 8 种仪表类型
    simulated_gauges-1.1.0.jar
    create-1.21.1-6.0.10.jar
    Create-Distant-Stock-0.3.7+mc1.21.1.jar
```

两个附带事实，都是这次顺出来的：

1. **整合包跑的是 NeoForge `21.1.231`**，而我们的开发环境一直是 `21.1.219`。
   也就是说**这半个月的 65 项 gametest 全部跑在一个你没在用的运行时上**。已把 `neo_version` 调成 21.1.231。
2. `deployer` 自己要求 `neoforge >= 21.1.227`，`create >= 6.0.10`，并内置 `compat_manager` + `mixinextras`
   （jar-in-jar，不用另外装）。这些条件整合包都满足。

## 一、Deployer 是什么（反汇编确认）

它不是「给仪表加功能」的模组，是**把 Create 的工厂仪表板变成一个能挂外来面板类型的板子**：

```java
// 注册表（NeoForge 标准 Registry）
DeployerRegistries.PANEL_KEY / DeployerRegistries.PANEL : Registry<PanelType<?>>

// 类型
class PanelType<T extends AbstractPanelBehaviour> {
    PanelType(PanelType.Constructor<T>, Class<T>);
    AbstractPanelBehaviour create(FactoryPanelBlockEntity, FactoryPanelBlock.PanelSlot);
}

// 面板行为基类（继承 Create 的 FactoryPanelBehaviour）
abstract class AbstractPanelBehaviour extends FactoryPanelBehaviour {
    abstract void  addConnections(PanelConnectionBuilder);
    abstract Item  getItem();
    abstract PartialModel getModel(PanelState, FactoryPanelBlock.PanelType);
    // 还有：连接系统、交互系统、bulb 状态、屏幕/菜单、写读 NBT
}

// 放置：一个 BlockItem，带着一个 Supplier<PanelType<?>>
class PanelBlockItem extends BlockItem {
    boolean applyToSlot(FactoryPanelBlockEntity be, PanelSlot slot, UUID network);
}
```

`applyToSlot` 的反汇编是关键一条——它**只要求目标是 `FactoryPanelBlockEntity`**：

```java
panels.get(slot).isActive()  // 空槽检查
→ getNewBehaviourInstance(be, slot)   // 造新行为
→ active = true; setNetwork(uuid)
→ attachBehaviourLate(newBehaviour)
→ panels.put(slot, newBehaviour)
```

**没有方块类型检查。** Extra Gauges 的物品（`extra_gauges:logic_gauge`）就是拿这个 `PanelBlockItem`
放到 **Create 自己的 `create:factory_panel` 方块**上的。

## 二、所以我们现在的处境

| | `create:factory_panel` | `distantstock:remote_gauge` / `signal_panel` |
|---|---|---|
| 方块/方块实体 | Create 的 | **也是 `FactoryPanelBlock` / `FactoryPanelBlockEntity` 的子类** |
| 面板创建 | Create 的 `addBehaviours` → deployer mixin 接管 | `RemoteGaugeBlockEntity` 不覆写 → 同样被 mixin 接管；`SignalPanelBlockEntity` **覆写**，自己造 4 个 `SignalLampAwarePanelBehaviour` |
| 能放 EG 仪表吗 | 能 | **待验证**（见第三节的探针） |
| 能放远仓仪表吗 | **不能**——远仓仪表不是 `PanelType`，它是另一个方块 | 它自己就是那块板 |

我们和 Extra Gauges **没有代码冲突**（两边没碰同一个类），冲突是：
**我们的仪表/信号灯没有进 `DeployerRegistries.PANEL`，所以它们只能待在自己的方块上，
Create 的板子和 EG 的板子都挂不上；反过来 EG 的类型能不能挂到我们的板子上，
取决于我们的板子有没有把那套 mixin 通道让出来。**

## 三、实测：探针跑出来的三件事

把 `deployer` 和 `extra_gauges` 加进开发环境（`build.gradle`，取自整合包同一目录），
`neo_version` 提到 21.1.231，写了一个一次性探针在真实 jar 上问三个问题。结果：

| 问题 | 结果 |
|---|---|
| 对面 mod 的仪表能不能挂到**我们的方块实体**上？ | **能。** `PanelBlockItem.applyToSlot(我们的 be, slot, uuid)` 返回 true，格子里的行为从 `FactoryPanelBehaviour` 变成 `LogicPanelBehaviour`，`activePanels()` 从 0 变 1 |
| 玩家拿着 EG 仪表右键我们的板子会怎样？ | **板子被拆掉。** 方块被换成 `create:factory_gauge`，`RemoteGaugeBlockEntity` 连同上面的绑定一起消失 |
| 同样一下就 Create 自己的板子呢？ | 没事。`useOn=FAIL`，面板进格子 |

原因是 Create 的 `FactoryPanelBlock`：

```java
// canBeReplaced：手持物是工厂仪表、目标格子空 → 允许「原地放同一块方块」
// getStateForPlacement：if (level.getBlockState(clickedPos).is(AllBlocks.FACTORY_GAUGE))
//                           → 不放置，改成 be.addPanel(slot, network)
```

第二处是**按方块身份写死的**。我们的板子不是 `create:factory_gauge`，这个分支永远不为它运行，
于是退回普通放置：把一块工厂仪表放在板子的位置上——板子没了。

**这不是理论风险，是现在装着的版本就有的数据丢失**（前提是装了会往面板上放东西的 mod）。

## 三·补：已经修好了（不需要大改）

修在 `GaugePlacementEvents`：原来它只认**我们的仪表**和**Create 的工厂仪表**两种手持物，
现在扩成「**任何方块是 `FactoryPanelBlock` 的物品**」。撞上我们的板子时，不再自己做，
而是把这一下**转交给板子自己的 `useItemOn`**——deployer 的面板放置就挂在那里
（`FactoryPanelBlockMixin.useItemOn` 里那个 `be.addPanel(slot, uuid)` 的包装），
所以 EG 的仪表会以正确的类型落进格子。

两条回归测试盯着它（`PanelBoardGameTests`，一条远仓仪表、一条信号灯板），
**关掉修复后两条都会红**：

```
the click was left to ordinary placement, which is what flattens the board
```

也就是说：**「EG 仪表能放到我们的板子上」这件事，现在已经能用了**，
不需要把远仓仪表注册成 `PanelType`。第三节那张表里「能放 EG 仪表吗 = 待验证」的那一行，
答案是「能，而且是刚从 bug 里救出来的」。

## 三·补二、deployer 是谁的（决定之前的尽调）

| 问题 | 答案 |
|---|---|
| 是 Create 官方的吗？ | **不是。** 作者 LIUKRAST，和 **Extra Gauges 是同一个人**（名下 17 个项目、220 万下载）。 |
| 有官方背书吗？ | **没有。** Create 6.0.10 **不给面板类型的扩展点**——`FactoryPanelBehaviour.getTypeForSlot(PanelSlot)` 是个 static 方法，对 4 个槽位的 ordinal 做 `tableswitch` 返回 `BehaviourType`，**用途是存档还原槽位**，不是给外部注册类型用的。我早先把它当成官方口子，**是错的**。 |
| 那它怎么做到的？ | 靠**大约 10 个 mixin 把 Create 的面板内部整体改一遍**（`FactoryPanelBehaviourMixin` / `FactoryPanelBlockEntityMixin` / `FactoryPanelBlockMixin` / `FactoryPanelModelMixin` / `FactoryPanelRendererMixin` / `FactoryPanelScreenMixin` / `FactoryPanelConnectionHandlerMixin` / `FactoryPanelConfigurationPacketMixin` …）才造出 `DeployerRegistries.PANEL` 这个注册表。 |
| 认可度 | 是这个生态（Extra Gauges / Simulated Gauges）里的**事实路线**，16.45 万下载、11 关注、两周前刚发 0.1.3（整合包是 0.1.2）。下载量里很大一部分来自 EG 的依赖自动安装，**不代表独立的社区共识**。 |
| 能用吗 | **能。** MIT 许可，用 / 改 / 随模组分发都行（保留版权声明）。 |
| 要注意 | 0.1.x、单人维护、深度 mixin 贴着 Create 内部走——Create 一升级内部结构它就可能跟不上。而且它 mixin 的 `FactoryPanelBehaviour` 和 `FactoryPanelConnectionHandler` **正是我们自己 mixin 的那两个类**（信号灯连线）。 |

**结论（已定）：做成可选依赖。** 装了 deployer 就注册成 `PanelType`；
没装就完全走今天这套，行为不变；两个方块保留，存档不动。

## 四、方案

### A. 把远仓仪表和信号灯注册成 `PanelType`（真正的那条路）

```java
DeferredRegister<PanelType<?>> PANELS =
        DeferredRegister.create(DeployerRegistries.PANEL_KEY, "distantstock");

public static final DeferredHolder<PanelType<?>, PanelType<RemoteGaugePanelBehaviour>> REMOTE_GAUGE =
        PANELS.register("remote_gauge",
                () -> new PanelType<>(RemoteGaugePanelBehaviour::new, RemoteGaugePanelBehaviour.class));
```

`RemoteGaugePanelBehaviour extends AbstractPanelBehaviour`，实现四个抽象成员，**下单逻辑一行不改**——
它本来就在 `RemoteOrderBook` 里，行为类只是把它接上。

**收益**：远仓仪表变成「能在任何一块工厂仪表板上占一格的东西」。一块板上可以同时是
Create 仪表 + EG 逻辑仪表 + 远仓仪表，这正是你要的「混在任意一格」。

**代价（要你拍板）**：

1. **`signal_panel` 方块和 `remote_gauge` 方块会变成多余。** 玩家不再需要专门的方块，
   直接拿仪表往 `create:factory_panel` 上放。留着就是两套东西同时存在，删掉就是存档里的方块变空气。
2. **`deployer` 变成编译期依赖。** 运行期可以做成软依赖（`ModList.get().isLoaded("deployer")`
   守卫 + 单独一个类文件承载所有 deployer 类型引用），**没装 deployer 的玩家走今天这条路**。
   软依赖 = 两套实现并存 = 每个面板逻辑两边都要接一次；硬依赖 = 干净一个实现，但你的模组
   从此要求玩家装 Create: Deployer API。
3. **客户端要重做**：`RemoteGaugeRenderer` / `SignalPanelRenderer` 现在是自己画的，
   deployer 有 `ClientRegisterHelpers.PanelFactory` / `PanelRenderer` 的注册口，得接上去。

### B. 只做「让 EG 的仪表能放到我们的板子上」

改 `SignalPanelBlockEntity.addBehaviours`：不再无条件造 4 个 `SignalLampAwarePanelBehaviour`，
改成「deployer 在线时交给它」。远仓仪表那块板本来就没覆写，大概率已经是好的。
**这是十几行的改动，但它是单向兼容**——我们的仪表还是上不了别人的板子。

### C. 什么都不做

你的整合包里两套面板各用各的：Create 板子给工厂自动化，远仓仪表板给跨服补货。
**它们今天就不冲突**，只是不能混在同一块板上。

## 五、现在还剩什么

**B 已经做完了**（第三节补）：别的 mod 的面板能落到我们的板子上，而且不会再把板子拆掉。

**A 还没做，它是另一个方向的兼容**：把**我们的仪表**做成 `PanelType`，
这样它才能放到 **Create 的板子**（或任何装了 deployer 的板子）上。今天的远仓仪表必须占一整块
`distantstock:remote_gauge` 方块，四格中的一格不能是 EG 的仪表——**反方向已经通了，正方向没有**。

A 的三个代价里两个不是技术问题：

- 做完之后 `remote_gauge` / `signal_panel` 两个方块就多余了，删掉会影响你存档里已经搭好的东西；
- 「你的模组要不要**硬依赖** Create: Deployer API」是发布决策，不是我能替你做的
  （软依赖 = 两套实现并存，工作量翻倍但玩家无感）。

**要你回答两个问题**：

1. 你想不想把**远仓仪表放到 Create 自己的工厂仪表板上**（和工厂仪表、EG 仪表混在一格里）？
   想 = 做 A。
2. 你能不能接受 DistantStock **硬依赖** `deployer`？不能 = 软依赖双实现。

**两个问题都已回答：要，并且做成可选依赖。** 下面是实施计划。

## 六、实施计划（可选依赖版）

### 形状

```
dev.distantstock.panel                      ← 全部 deployer 引用只出现在这个包
    DeployerPanels          注册表 + 两个 PanelType + register(bus)
    RemoteGaugePanelBehaviour   extends OrderingPanelBehaviour
    SignalLampPanelBehaviour    extends AbstractPanelBehaviour
    DeployerPanelClient     客户端渲染注册（独立入口，同样守卫）
```

**载入隔离**：`DeployerPanels` 只在 `ModList.get().isLoaded("deployer")` 为真时被
主类调用（`DistantStock` 构造里那一行），调用方自身不引用任何 deployer 类型，
所以没装 deployer 时这个类永远不会被 JVM 载入。

### 复用而不是重写

deployer 已经把「带过滤器 / 带数量 / 会下单的仪表」做好了：`OrderingPanelBehaviour`
（`AbstractPanelBehaviour` → `ScrollPanelBinding` → `OrderingPanelBehaviour`）。
远仓仪表要换掉的只有**下单那一步**：

```java
class RemoteGaugePanelBehaviour extends OrderingPanelBehaviour {
    boolean isFilterEmpty()                   // getFilter().isEmpty()
    void    addPromises(RequestPromiseQueue)  // 远仓不向本地打包机承诺 → 空实现
    void    tick()                            // 走 RemoteOrderBook 的那一拍
    Item    getItem()                         // ModItems.REMOTE_GAUGE
    PartialModel getModel(state, type)
}
```

**关键重构（先做，且与 deployer 无关）**：`RemoteOrderBook` 今天是**按板子**记账
（`EnumMap<PanelSlot, …>`），因为 `RemoteGaugeBlockEntity` 一次管四个格子。
.deployer 路径下**一个行为实例只管自己那一格**，所以要把「一个格子的下单」抽出来：

```
RemoteOrderSlot     绑定 + 在途计数 + 结算 + 那一拍（纯逻辑，可单测）
    ├── RemoteOrderBook 用它管四个格子   （今天这条路）
    └── RemoteGaugePanelBehaviour 自己持一个（deployer 那条路）
```

抽完之后两条路**共用同一份下单逻辑**，`RemoteGaugeOrders` 的算术一行不改。

### 阶段

| # | 内容 | 验证 |
|---|---|---|
| 1 | 抽出 `RemoteOrderSlot`；`RemoteOrderBook` 改为持有四个 | 现有 67 项 gametest 全绿（它们已经覆盖在途/超时/封顶） |
| 2 | `panel` 包 + 注册表 + 守卫；`RemoteGaugePanelBehaviour` 能**落到 `create:factory_gauge` 上** | 新 gametest：`applyToSlot` 到 Create 板上，方块不变、类型是我们的 |
| 3 | 那条路上的下单跑通（行为自己 tick） | 新 gametest：Create 板上的远仓面板会下单 |
| 4 | 客户端：`DeployerPanelClient` 注册渲染器 | `runClientSmoke` |
| 5 | 信号灯变过去（它的监控列表 / 菜单 / 命名牌要逐个对照 `AbstractPanelBehaviour`） | 现有信号灯测试 + 冒烟 |
| 6 | `GaugePlacementEvents` 在 deployer 在线时改走注册表（不再 `convertFactoryPanel`） | 现有 `PanelBoardGameTests` 两条继续绿 |
| 7 | 两个方块的去留、Ponder、说明书、`DEV-PITFALLS` | 全套 |

阶段 1 与 deployer 无关，先落地；2 之后每一步都要能单独装机。

### 没有解决的风险（要盯着）

- **我们和 deployer 都 mixin 了 `FactoryPanelBehaviour` 和 `FactoryPanelConnectionHandler`。**
  今天 67 项测试在装了 deployer 的运行时上是绿的，说明不炸；但信号灯连线的**行为**是否被它改过，
  只有真机能看出来。
- **信号灯不是仪表。** 它没有过滤器、没有数量、有监控列表和监视器菜单。
  `AbstractPanelBehaviour` 的抽象成员能实现，但阶段 5 之前不承诺它一定塞得进去——
  塞不进去我会在这里写明，而不是硬做。

## 七、阶段 5–7 已落地（信号灯也变过去了）

- `SignalLampPanelBehaviour extends AbstractPanelBehaviour`：**接线模式**读它指向的仪表，
  **绑定模式**读 Create 网络；两级阶梯抽成 `LampReadings`，我们自己的灯板也改调它，
  一个颜色在哪块板子上都是同一个意思。
- 模式值面板（正常/反相）、标签、命名牌改名、配网手势（库存链接或调谐终端，潜行解绑）
  在别人的板子上都能用，走 `DockInteractionEvents.offerLampClick`。
- **踩到的坑（会静默失效的那种）**：`AbstractPanelBehaviour.getFilter()` **故意返回 EMPTY**，
  是给「根本没有过滤器」的面板类型用的。子类必须自己回答，而 `super.getFilter()` 拿到的正是
  那个 EMPTY —— 于是面板看着一切正常、读数也照走，**只是永远看不到过滤物品**：
  仪表永远不下单，灯根本不是灯。正确读法是 Create 的字段 `filter.item()`。
- **没带过去的**：黄铜灯的监控列表与监视器菜单。菜单是 `(方块坐标, 槽位)` 去
  `getBlockEntity` 取容器的，是板子级的东西；搬到别人板上要先抽一层「谁是这块板这个槽位的
  监控来源」。灯本身照常按它绑定的网络亮/灭，只是不能告诉它盯哪几个物品。
