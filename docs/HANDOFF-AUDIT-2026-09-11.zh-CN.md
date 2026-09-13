# 机械动力：远仓——现状审计与模型交接

> 审计日期：2026-09-11（Asia/Shanghai）  
> 适用工程：`DistantStock` + `Transerver`  
> 本文以当前磁盘上的源码、资源、Git 状态和本次构建结果为准。它用于取代“根据聊天记忆推断进度”的做法；旧的 `CURSOR-HANDOFF.zh-CN.md` 仍可查阅细节，但不能再当作唯一事实来源。

## 0. 先说结论

工程没有坏到需要推倒重来。当前代码能够编译，协议与包裹保管的两个自检也能通过；远仓港、远仓打包机、请求器、仪表、监视器、信号灯等主要注册项都还在。

真正的问题是开发状态失去边界：

1. `DistantStock` 有 **54 个已跟踪文件被修改**，同时有大量未跟踪的 Java、JSON、PNG、测试和文档。最新的大部分功能并不在 Git 历史中。
2. `Transerver` 也有 7 个未提交源码修改；这些修改为 DistantStock 的节点发现功能提供 `knownNodes()`，两边必须成套保留。
3. 旧的 HTTP 点对点链路与新的 Transerver 可靠链路目前会同时启动。它们可以作为迁移期兼容层存在，但现在没有明确的启用条件和退役界线，是最容易制造重复路径与误判的地方。
4. “写出类”“接入 tick”“通过离线自检”“在真实整合包跨服跑通”是四件不同的事。现有文档曾把它们写得过于接近。
5. 最后一次可查到的实际游戏日志中，Transerver 是 `disabled`，旧 `LinkServer` 在监听 `18772`。因此新 Transerver 端到端链路**没有被这份日志证明跑通过**。

接手者不要重构全项目，也不要根据文件名删除“看起来重复”的类。先做可回退的工作区快照，然后以一个最小双节点端到端测试为主线收束工程。

## 1. 工程与版本

| 项目 | 路径 | 当前分支/基线 | 状态 |
|---|---|---|---|
| DistantStock | `/Users/xx2005/Documents/git_repository/DistantStock` | `main` / `48ad49d` | 大量未提交成果 |
| Transerver | `/Users/xx2005/Documents/git_repository/Transerver` | `main` / `799ccc8` | 7 个未提交源码修改 |
| 测试实例 | `/Users/xx2005/Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal` | 本地整合包 | 当前已放入新构建 jar |

运行基线：

- Minecraft 1.21.1
- NeoForge 21.1.219
- Create 6.0.10
- Java 21
- DistantStock 0.3.7
- Transerver 0.1.0-SNAPSHOT

本次新构建产物：

| 文件 | SHA-256 |
|---|---|
| `DistantStock/build/Create-Distant-Stock-0.3.7+mc1.21.1.jar` | `8e0261fa9be78395b2e445c0d9584fd17f93e983636625ba8f5a9c292c76ff15` |
| `Transerver/build/libs/Transerver-0.1.0-SNAPSHOT.jar` | `7b18605ca28311cb56c1a9797859895b7629796187b49d3d9d6506ae5548b504` |

测试实例中同名的两个启用 jar 与上述哈希一致。`mods` 中还留有一份旧的 `Create-Distant-Stock...jar.disabled`，它不会加载，但以后排查版本时应注意不要认错。

## 2. 本次实际验证

2026-09-11 本次审计执行并通过：

```text
DistantStock: ./gradlew clean build verifyWireCodec verifyParcelOwnership --no-daemon
结果：BUILD SUCCESSFUL

Transerver: ./gradlew clean build --no-daemon
结果：BUILD SUCCESSFUL
```

DistantStock 编译有 8 个 NeoForge `EventBusSubscriber.Bus` 弃用警告，以及 `SignalLampPanelItem` 使用弃用 API 的提示；现在不阻塞 1.21.1，不应为了消警告立刻大改事件结构。

验证边界：

- 已证明：主源码、资源处理、测试源码能够构建；线格式往返/损坏检测测试通过；包裹单一保管权测试通过。
- 未证明：客户端进存档、方块实际放置、Flywheel 动画、Create 物流请求、两个真实服务器之间的传输、断线重试、重启恢复、异模组退件。
- 日志事实：可查到的 2026-09-09 游戏日志显示 `Transerver node is disabled`，随后旧 `LinkServer` 监听 `0.0.0.0:18772`。不能把它当成 Transerver 已跑通的证据。

## 3. 不可违反的工作区规则

1. 禁止 `git reset --hard`、`git clean`、`git checkout -- .`，也不要批量删除未跟踪文件。
2. 在形成快照前，不要把未跟踪源码当成临时文件；核心的新 Transerver、路由、托管、信号灯代码都在其中。
3. 不要在 Minecraft 仍运行时覆盖 `mods` 内的 jar。JVM 可能已经缓存旧 jar 索引，热覆盖曾导致类明明在新 jar 中却报 `NoClassDefFoundError`。
4. 修改 DistantStock 的 Transerver API 用法时，同时检查 Transerver 的未提交 diff。尤其不能单独丢掉 `knownNodes()`。
5. 美术概念资源主要位于 `docs/design/`、`scripts/concepts/` 和 `assets/distantstock_concept/`；不要无差别复制进正式注册空间。

建议第一步先建立一个明确的 WIP 快照提交，或至少创建保护分支。快照不是宣称功能完成，而是防止下一轮整理时丢代码。

## 4. 系统目标与已冻结设计

### 4.1 两个独立 jar

- **Transerver**：通用多节点可靠消息前置库。管理稳定节点身份、实际传输地址、队列、回执、重试和持久化，不理解 Minecraft 物品。
- **DistantStock**：Create 附属模组。理解物流网络、请求、包裹、港组、方块、玩家交互和跨模组兼容。

物理 IP、ZeroTier 地址或域名不能写进包裹。包裹只携带稳定逻辑目标：节点 UUID、收货港组 UUID、关联 ID。管理员改变地址后，旧包裹仍可由 Transerver 的节点配置找到新地址。

### 4.2 一网对多港、多网对多网

- 一个 Create 物流网络可以连接多个远仓港。
- 一个收货港组可以包含多个远仓港；按最高优先级层选择，同层轮转。
- 请求器选择的是远程网络与目标收货港组，不是某个坐标，也不是 IP。
- 远仓港同时是物流网络边界与跨服网关，不要求旧工厂把全部打包机换成远仓打包机。
- 远程请求由对面原版/Create 打包流程生产包裹；远仓港在包裹到达网络边界时补上稳定跨服路线。

### 4.3 异模组服务器

目标不是把陌生物品强行反序列化成空气，而是：

1. 来源服托管原始包裹；
2. 信封带实际用到的物品/数据组件清单；
3. 目标服先检查注册表，再解码；
4. 缺少内容时拒收并通知来源；
5. 来源可剔除不兼容整件后重发，或把整包退回；
6. 无法解码的原始数据进入管理员隔离库，不静默销毁。

这个方向正确，但真实异模组双服测试尚未完成。

## 5. 当前运行架构

### 5.1 新主链：Transerver

```text
请求器/远仓仪表
  -> 选择 RemoteNetworkId + receivingDockGroupId
  -> ORDER_REQUEST
  -> 对面 TranserverOrderService
  -> CreateStock 发起 Create 物流请求
  -> OrderRouteDirectory 记录 Create orderId 对应的 RemoteRoute
  -> 普通打包机生产包裹
  -> 包裹进入发送远仓港
  -> 港从包裹自身 / orderId / 港默认路由解析目标
  -> ParcelEscrow 持久托管原包
  -> PACKAGE_DISPATCH
  -> 目标服预检 manifest、解码、按港组选择接收港
  -> 成功插入后 ParcelLedger 去重并返回 APPLIED
  -> 来源服收到最终回执后才删除托管记录
```

关键入口：`GameClock.started()` 注册 5 个 Transerver channel handler，再执行 `TranserverBridge.start()`；tick 中驱动桥接状态、托管泵、远程订单、网络广播和库存查询。

### 5.2 旧兼容链：LinkServer / LinkClient

旧链仍然在 `GameClock.started()` 中无条件 `LinkServer.start()`，旧队列也仍由 `OrderService.drainInbound()`、`PackagePump.drain()` 消费。港在 Transerver 未附着且包裹无稳定路线时会回退到旧 `LinkQueues`。

这不是立即删除的理由，但必须明确策略：

- 短期：标注为 legacy，仅在配置显式启用或 Transerver 未启用时启动。
- 验证新链后：默认关闭 legacy，保留一次迁移窗口。
- 发布前：决定完全删除，还是作为不保证可靠性的兼容模式保留。

目前无条件同时启动两套服务，会造成端口暴露、状态含义混合，也增加“到底走了哪条链”的排障成本。

## 6. 子系统状态表

状态定义：

- **A 构建验证**：本次能构建或自检通过。
- **B 静态接通**：入口、调用和持久化路径可从源码追通，但未做真实游戏验证。
- **C 原型/半成品**：可见功能存在，仍有明显行为或交互缺口。
- **D 设计稿**：只有文档/概念资源，未正式实现。

| 子系统 | 状态 | 审计结论 |
|---|---:|---|
| Transerver 基础消息、回执、持久队列 | A/B | Transerver 自身构建通过；此前已有持久发送结果结构，但本轮未做真实路由器双节点测试 |
| Transerver 节点发现 `knownNodes()` | A/B | 两仓库成套编译通过；当前 7 个 Transerver 未提交修改即为此功能 |
| 稳定网络 ID | B | `nodeId + worldId + dimensionId + createFrequency` 已编码、广播、查询；Multiverse/多世界实际环境未测 |
| 远程库存广播与查询 | B | handler 已注册并由 tick 驱动；未做双服 GUI 实测 |
| 远程订单 | B | 持久 `InboundOrderInbox`、重复 childOrderId 处理、Create 请求路径已接入；失败/重试语义需实测 |
| 包裹路线 | B | 包裹数据优先，其次 Create orderId 映射，最后港默认路由；没有路线时不猜目标 |
| 来源托管与最终回执 | A/B | `ParcelEscrow`、`ParcelEscrowPump`、完成回执匹配已接入；所有权离线检查通过 |
| 目标去重 | B | `ParcelLedger` 在成功插港后记录；崩溃窗口和真实重复投递尚未压测 |
| 异模组 manifest | A/B | V2 信封、注册表预检、标准嵌套容器递归已自检；第三方自定义组件内私藏 ItemStack 不保证覆盖 |
| 退件箱/隔离库 | A/B | 保存、移交、崩溃后 reconcile 的离线检查通过；命令和实际世界保存/恢复未手测 |
| 剔除不兼容物后重发 | B/C | 通知、剔除、最多 3 次、回退面路径已写；属于复杂新逻辑，必须做异模组双服测试后才能称完成 |
| 远仓港 | B/C | 模式、优先级、港组、三组缓存、回退面、30 tick 发送动画、V5 模型已接入；曾出现放置卡死和动画/穿模问题，当前版本需重新回归 |
| 远仓打包机 | B/C | 继承 Create 打包机逻辑，镂空与 Flywheel visual 接入；透明度、舱门/托盘曾多次修复，需游戏内确认 |
| 请求器 UI | C | Create 仓储界面式背景、路线标签和港组输入已出现；当前接收港组仍是文本输入体验，目录选择/校验未完善 |
| 远仓仪表 | C | 与 Create 工厂仪表同体系，用于远程请求；材质方向曾修，跨服请求需回归 |
| 远仓监视器 | C | TPS/网络面板原型存在；视觉仍偏现代、信息来源和最终交互未冻结 |
| 安山/黄铜信号灯系统 | C | 四分格面板、物品放置、渲染与命名数据已注册；放置闪仪表、熄灭透明、亮度等问题曾修，需重新手测全部方向 |
| 独立旧式指示灯方块 | C | 五色 `IndicatorLampBlock` 仍注册；它与四分格信号面板概念重叠，发布前必须决定保留、隐藏还是迁移 |
| 以太石英、磨制以太石英、高阶“以太”晶体 | D | 名称与贴图方向讨论过，尚未形成正式注册和冻结配方 |
| 熔融紫水晶、以太凝液 | D | 流体、桶、瓶、管道/储罐外观有概念；正式流体注册、配方和机制未完成 |
| 普通互通塔、世界互通塔 | D | 多方块、等级、范围、区块加载、耗液仍在设计；不要现在写大套塔逻辑 |

## 7. 目前最需要警惕的技术点

### 7.1 两套传输链并行

`GameClock` 同时启动 Transerver 与旧 HTTP server。下一模型首先要做的是增加清晰的运行模式与日志，而不是继续加第三套抽象。每个订单/包裹日志至少应带：`correlationId`、`childOrderId`、`parcelId`、source、destination、group 和所用 transport。

### 7.2 运行验证落后于实现

最新 jar 已放进测试实例，但现有可查日志停留在 Transerver disabled 的运行。必须在 Minecraft 完全退出后确认 jar 哈希，再启动一个最小测试世界；不要直接在大型存档里验证复杂退件。

### 7.3 `PackageDispatchCodec.Dispatch.sha256` 语义不一致

`create()` 给 record 字段写的是“包裹字符串摘要”，V2 `encode()` 实际发送的是“整个信封摘要”，`decode()` 返回的 record 字段又是整个信封摘要。当前逻辑不依赖该字段，因此自检仍通过，但这个字段名会误导后续代码。应统一成明确的 `wireDigest`，或完全不把它暴露在 record 中；修复时保留 V1 读取兼容。

### 7.4 持久目录缺少清理生命周期

`OrderRouteDirectory` 上限 4096，会淘汰最老项，但成功消费后没有显式删除；这不会立刻坏，却可能让长时间运行后的 orderId 冲突和诊断变难。先补端到端测试，再决定消费后删除时机，避免包裹迟到时路线丢失。

### 7.5 世界身份与 Multiverse

`WorldIdentity` 目前按 `ServerLevel.getDataStorage()` 存 ID，并在启动时对当前服务器所有 level 去重；网络 ID 同时保存 dimension key。这个静态设计考虑了多维度，但“Multiverse 插件的世界复制、卸载、重载、改名”没有实测。不要宣称已兼容，只能写“标识字段已预留”。

### 7.6 退件/隔离逻辑不能只靠单元式 main

`verifyParcelOwnership` 检查内存对象与 NBT 往返，价值很高，但没有模拟 Minecraft 在两个 SavedData 表之间真正落盘和崩溃。交付前要做：发送后杀进程、目标满仓、来源港卸载、来源港被拆、缺模组、重复 receipt、隔离库导出/恢复。

### 7.7 Create/Flywheel 渲染非常脆弱

- 远仓打包机依赖 Create 的 `PackagerVisual` 与 `PackagerRenderer` 协作。
- 模型若自己定义 `elements` 会替换父几何。
- MC 模型元素不接受 90° rotation，需要烘进坐标。
- 机壳镂空 alpha 后必须有内部机芯兜底。
- 信号面板的 behaviour 命中坐标、`isActive()`、`getAmountTip()` 都有踩坑记录。

不要为了“整理代码”重写这些区域；每改一处都应立即进游戏放置并观察。

## 8. 关键代码地图

### 8.1 启动与边界

- `DistantStock.java`：注册方块、方块实体、物品、菜单。
- `server/GameClock.java`：服务器生命周期与所有周期任务。
- `link/TranserverBridge.java`：DistantStock 唯一应使用的 Transerver 边界。
- `META-INF/neoforge.mods.toml`：Transerver 当前为 required，范围 `[0.1.0-SNAPSHOT,0.2.0)`。

### 8.2 远程网络与请求

- `routing/RemoteNetworkId.java`
- `routing/WorldIdentity.java`
- `link/NetworkAnnouncementService.java`
- `link/TranserverStockService.java`
- `link/TranserverOrderService.java`
- `stock/CreateStock.java`
- `routing/OrderRouteDirectory.java`

### 8.3 包裹可靠传输

- `routing/RemoteRoute.java`
- `routing/RemoteRouteData.java`
- `link/PackageDispatchCodec.java`
- `link/PayloadManifest.java`
- `link/ParcelEscrow.java`
- `link/ParcelEscrowPump.java`
- `link/TranserverPackageService.java`
- `link/ParcelLedger.java`
- `link/ParcelReturnInbox.java`
- `link/ParcelQuarantine.java`
- `link/PackageStripService.java`

### 8.4 方块与界面

- `block/DockBlockEntity.java`：最大风险热点，包含港缓存、路线解析、动画状态、故障/回退、护目镜信息。
- `block/LoadedDocks.java`：接收港组选择和已加载港索引。
- `client/DockRenderer.java`：包裹上升/缩小与内部机芯。
- `block/RemotePackagerBlockEntity.java` + `client/ClientSetup.java`：Create 打包机复用与 Flywheel visual。
- `block/SignalPanelBlockEntity.java`、`item/SignalLampPanelItem.java`、`client/SignalPanelRenderer.java`：四分格灯。
- `client/RequesterScreen.java`、`menu/RequesterMenu.java`、`net/PlaceOrderC2S.java`：请求 UI 到订单入口。

## 9. 下一阶段唯一推荐顺序

### P0：冻结并建立可诊断基线

1. 保存两个仓库的 WIP 快照，不能先“清理未跟踪文件”。
2. 记录构建 jar 哈希；确认游戏关闭后再安装。
3. 给旧 HTTP 与 Transerver 增加明确模式：优先建议 `transerver` / `legacy`，默认 Transerver；不要默认并行。
4. 启动最小实例，验证进存档、放置全部注册方块、退出重进均不崩。

完成标准：日志清楚说明启用了哪条链；所有基础方块能放置；没有卡死和紫黑模型。

### P1：最小双节点竖切测试

只测一条链：

1. A、B 两个节点都启用 Transerver；
2. B 暴露一个 Create 物流网络和一个默认收货港组；
3. A 请求 B 的一种原版物品；
4. B 打包，远仓港托管并发送；
5. A 正确港组收到；
6. 重复消息不会重复生成；
7. 中途重启来源/目标分别再测一次。

完成标准：通过日志能用 correlation/order/parcel 三个 ID 串起整条链，来源只在 APPLIED 后销账。

### P2：故障矩阵

按顺序验证：目标港满、目标港不存在、目标离线、来源重启、目标缺少模组物品、来源港卸载/拆除。每个场景都要回答“包裹现在唯一由谁保管”。任何一次静默消失或复制都先修，不继续做内容。

### P3：玩家交互收束

- 请求器把远程网络和收货港组改成可选目录，不要求普通玩家手填 UUID。
- 远仓港默认路由、模式、优先级继续使用 Create 风格 Value Settings 与护目镜，不新增大 GUI。
- 管理员地址、节点别名、权限和诊断放命令/配置；普通玩家看逻辑名称。

### P4：内容与美术

只有 P0–P2 稳定后，才继续以太材料、流体和互通塔。否则玩法变化会继续扩大协议和存档面。

## 10. 暂时不要做

- 不要开始普通塔/世界塔的完整多方块实现。
- 不要把“云盘”塞进第一版港逻辑。
- 不要继续添加新的退件抽象，现有 escrow / return inbox / quarantine 已足够验证。
- 不要先做 WebUI、数据库或外置 MySQL；Transerver 的目标就是降低这类部署负担。
- 不要为了兼容未来所有模组扫描任意 `CUSTOM_DATA`；先用真实不兼容样本补测试。
- 不要大规模重画现有贴图；先确认当前正式资源与概念资源的边界。

## 11. 给下一模型的直接任务

```text
你正在接手 Minecraft 1.21.1 NeoForge 模组“机械动力：远仓”。

先完整阅读：
1. /Users/xx2005/Documents/git_repository/AGENTS.md
2. /Users/xx2005/Documents/git_repository/DistantStock/docs/HANDOFF-AUDIT-2026-09-11.zh-CN.md
3. DistantStock 与 Transerver 当前 git status/diff

硬规则：
- 当前大量未提交和未跟踪文件都是有效 WIP，不得 reset、clean、checkout 或批量删除。
- 不要相信旧文档里的“完成”字样，源码与实际测试优先。
- 不要在 Minecraft 运行时覆盖 mods jar。
- 不做全项目重构；一次只推进一个可运行竖切。

第一项工作：
1. 为两个仓库建立安全 WIP 快照；
2. 重新运行 DistantStock 的 build + verifyWireCodec + verifyParcelOwnership，以及 Transerver build；
3. 收束 GameClock 中旧 HTTP 与 Transerver 无条件并行启动的问题，增加明确运行模式与可追踪日志；
4. 在最小双节点环境完成一次原版物品的远程请求、打包、发送、接收、APPLIED 销账；
5. 把结果、日志证据和未通过项更新回本审计文档。

不要开始互通塔、流体或大规模美术工作，直到这个双节点竖切稳定。
```

## 12. 交接判断

当前最准确的项目描述是：

> **“核心方案已经形成、代码量已经很大、离线构建与两项关键自检通过，但真实 Transerver 双服闭环尚未被证明；工程首先需要收束与验证，而不是继续横向加功能。”**

只要下一模型守住工作区、明确两条链的边界，并按 P0 → P1 → P2 推进，就不需要推倒重来。

---

## 13. 2026-09-11 接手验证记录（Cursor 第二模型）

### 13.1 WIP 快照

为两个仓库建立了安全快照分支 `wip-snapshot-2026-09-11`：

- **DistantStock**: `dc17835` — 360 文件，含全部未提交+未跟踪的 Java/JSON/PNG/文档/测试
- **Transerver**: `cabaad0` — 7 个未提交源码修改

快照建立后工作树已恢复到原始状态（main + 未提交修改 + 未跟踪文件），两仓库均验证文件数一致。

⚠️ 注意：`git checkout main` 会把快照分支 commit 过的原本未跟踪文件从工作树中删除。恢复方式：`git checkout wip-snapshot-2026-09-11 -- . && git reset HEAD .`。后续做保护分支时应改用 `git stash -u` 或先 `git add -A` 后在 main 上暂存而不切分支。

### 13.2 构建验证

```text
DistantStock: ./gradlew clean build verifyWireCodec verifyParcelOwnership --no-daemon
结果：BUILD SUCCESSFUL (8 NeoForge EventBusSubscriber.Bus 弃用警告，1 SignalLampPanelItem 弃用 API 提示)
verifyWireCodec: PASSED
verifyParcelOwnership: PASSED

Transerver: ./gradlew clean build --no-daemon
结果：BUILD SUCCESSFUL
```

前提：DistantStock 通过文件路径 `../Transerver/build/libs/Transerver-0.1.0-SNAPSHOT.jar` 引用 Transerver，因此 **必须先构建 Transerver 再构建 DistantStock**。Transerver 的 `knownNodes()` API 存在于 7 个未提交修改中，丢失会导致编译失败。

审计文档中提到的 `verifyWireCodec` 和 `verifyParcelOwnership` 是 `build.gradle` 中注册的 `JavaExec` 任务，分别执行 `src/test/java/dev/distantstock/link/PackageDispatchCodecCheck.java` 和 `ParcelOwnershipCheck.java`。这两个文件是未跟踪状态，不在 git 历史中。

### 13.3 transport.mode 收束

**问题**：`GameClock.started()` 无条件同时启动 Transerver 5 个 channel handler + bridge 和旧 HTTP `LinkServer`。日志无法区分使用了哪条链路。

**修改**：
- `StockConfig` 新增 `transport.mode` 配置项，值 `transerver`（默认）/ `legacy` / `both`
- `GameClock` 根据 `StockConfig.useTranserver()` 和 `StockConfig.useLegacy()` 决定启动和 tick 哪些组件
- 启动时输出 `[DistantStock] transport.mode = 'xxx' | transerver=true/false legacy=true/false`

默认值 `transerver` 意味着旧 HTTP 链路不再自动启动。需要兼容迁移期时可改为 `both`，纯旧链路用 `legacy`。

修改后 jar SHA-256: `e39072bbfedbb9844dcef72d48bec1c3a23e82dcc5e687b55630c316119c318c`

### 13.4 当前验证边界

与前次审计相同：
- **已证明**：主源码+资源+测试源码能构建；协议编解码往返/损坏检测测试通过；包裹单一保管权测试通过
- **新增证明**：transport.mode 配置生效，两条链路不再无条件并行
- **未证明**：客户端进存档、方块放置、Flywheel 动画、Create 物流请求、双节点 Transerver 端到端传输、断线重试、重启恢复、异模组退件

### 13.5 下一步

按审计文档 P1 继续：最小双节点竖切测试。需要 Minecraft 测试环境启动后验证。

## 14. 2026-09-12 客户端崩溃修复记录

### 14.1 证据边界

用户提供的 `minecraft-exported-crash-info-2026-09-12T14-08-03.zip` 内，实际崩溃报告时间为 `12:49:50`，调用链是 Create `FilteringRenderer` / `ValueSettingsClient.render` 向 `Font.width` 传入空文本。该报告早于后续构建和安装，不能用于判断新包是否仍崩溃。日志还包含大量旧世界区块 DataFixer `Pair cannot be cast to Dynamic` 错误，应与信号灯客户端崩溃分开处理。

### 14.2 独立信号灯根因与修复

`SignalLampPanelItem.placeStandalone` 使用的 `BlockPlaceContext.getClickedPos()` 已经是实际放置位置，旧代码又沿点击面 `relative(...)` 一次，导致目标落在支撑面外一格。独立灯无法存活后，旧代码调用 `super.place(context)`，实际放置了该物品继承绑定的 `SIGNAL_PANEL`，因此出现整块紫黑模型并进入 Create 工厂面板的 Filtering / Value Settings 路径。

现已改为：

- 独立灯直接使用 `context.getClickedPos()` 和原放置上下文；
- 无法放置时明确返回 `FAIL`，绝不回退为信号面板；
- 信号面板灯格在数据同步前及确认是灯格后，始终返回非空 `Component.empty()` 提示并绕过 Value Settings；
- 独立灯和四分格面板灯的模型、贴图与所有 24 个附着/朝向/亮灭 blockstate 组合由 `verifyClientAssets` 自动检查。

### 14.3 监视器重开修复

监视器的“显式打开”和“每秒刷新”已拆成两个不同的网络包：

- `OpenMonitorS2C` 只能由玩家右键触发并打开界面；
- `LinkSnapshotS2C` 只能更新已经打开、且来源方块坐标一致的 `MonitorScreen`；
- 周期刷新包不再具备打开界面的代码路径，关闭界面后不会被刷新包重新拉起。

### 14.4 资源检查与构建

新增 `scripts/verify_client_assets.py` 和 Gradle 任务 `verifyClientAssets`，并接入 `check`。检查首次发现远仓港八个状态模型引用了不存在的 `distantstock:block/lamp_socket`，已改为现有的 Create 工业铁块纹理。安山青色独立灯、面板灯和远仓港均已离线渲染成功。

2026-09-12 18:11 完整通过：

```text
Transerver: ./gradlew clean build --no-daemon
DistantStock: ./gradlew clean build verifyWireCodec verifyParcelOwnership verifyClientAssets --no-daemon
结果：BUILD SUCCESSFUL
```

当前安装包：

```text
DistantStock SHA-256: 767b97a1867dc6d9757beb895427c297057562bbeee80c8ffeb527186239563f
Transerver SHA-256:    7b18605ca28311cb56c1a9797859895b7629796187b49d3d9d6506ae5548b504
```

两个构建产物与 HMCL 测试实例 `mods` 内文件哈希一致。测试服务器已关闭。下一步必须用这个哈希的新包在干净测试世界做一次实际放置回归；双服 Transerver 请求、打包、发送、接收和 `APPLIED` 销账仍未被真实游戏测试证明。

## 15. 2026-09-12 Codex 重新接盘记录

### 15.1 用户补充的人工验收事实

用户确认 Cursor 开发阶段完成了两类实际工作：

- 制作并替换了一轮新的方块贴图；当前工作树中远仓港八种状态模型均有后续调整，客户端资源检查覆盖 11 个方块、74 个模型。
- 实际测试过两个服务端之间的通信，正常双服通信已经成功。

这两项属于用户提供的人工验收结果。当前本机可查的 `latest.log` 只记录到 Transerver 节点被禁用的单机运行，不能独立复现双服成功过程；后续测试应保留两端日志作为回归证据。

### 15.2 当前工作树重新验证

本轮以 Cursor 留下的未提交版本重新执行：

```text
Transerver: ./gradlew clean build --no-daemon
DistantStock: ./gradlew clean build verifyWireCodec verifyParcelOwnership verifyClientAssets --no-daemon
结果：全部 BUILD SUCCESSFUL
客户端资源检查：PASSED（11 blocks, 74 models）
```

Cursor 的主要未提交工作经静态审查可确认包括：

- `DockItem`：远仓港物品可先从已有 Create 物流链接复制稳定网络身份，放置后自动成为发送港；潜行空手可解除港绑定。
- 远仓港空手交互可取走完整到货包裹，先检查玩家背包是否能容纳，避免取出后掉失。
- 信号灯独立放置不再错误回退成整块 `signal_panel`。
- 监视器显式打开包与周期刷新包分离，关闭界面后不会被刷新包强制重开。
- 新的 `verifyClientAssets` 已挂入 Gradle `check`。

### 15.3 本轮新增的跨服可诊断性

为订单和包裹主链补充统一日志，关键状态现在会带稳定标识：

- 订单：`messageId`、`correlationId`、`childOrderId`、目标节点、Create 网络频率、收货港组、物品行数；
- 包裹：`parcelId`、`messageId`、目标/来源节点、收货港组、剔除次数、最终 `APPLIED` / `REJECTED`；
- 目标端：重复包裹、不兼容注册表、无法解码、无已加载港、港满、插入拒绝与最终落港位置；
- 隔离：记录来源保管区、原因和明细。

日志前缀固定为 `[DistantStock/Order]` 与 `[DistantStock/Parcel]`。正常状态用 INFO，暂时性等待用 DEBUG，拒收/不兼容用 WARN，进入隔离库用 ERROR。

### 15.4 新增故障恢复自动测试

`TranserverNodeIntegrationTest` 新增并通过两个场景：

1. 目标第一次返回 `RETRY`：消息继续由来源队列持有；目标恢复后返回 `APPLIED`；继续 pump 不会再次应用。
2. 目标节点离线且来源节点重启：来源重启后消息仍在；目标上线后只应用一次；来源收到最终 `APPLIED` 并清空待发队列。

这证明 Transerver 底层对“港暂时不可用”和“目标离线 + 来源重启”具备可靠恢复行为。它仍不能代替 Minecraft 层的实测：下一步应在游戏中验证远仓港满/不存在时，`TranserverPackageService` 是否按预期持续返回 `RETRY`，腾出空间后是否只生成一个包裹。
