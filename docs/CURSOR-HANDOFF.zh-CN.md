# 机械动力：远仓——Cursor 开发交接文档

> 交接时间：2026-09-10（Asia/Shanghai）  
> 最近更新：2026-09-10，回退面/灯态/剔除/Value Settings 落地；机壳保持镂空，灯态与网 id 修复  
> Minecraft：1.21.1  
> NeoForge：21.1.219  
> Create：6.0.10  
> DistantStock：0.3.7（开发中）  
> Transerver：0.1.0-SNAPSHOT（独立前置 JAR）

本文档用于让新的开发者或 Cursor 在不依赖聊天记录的情况下继续开发。请先完整阅读“仓库安全状态”“不可破坏的系统约束”和“下一阶段优先级”，再修改代码。

## 1. 仓库与运行环境

### 1.1 工程路径

- 远仓主工程：`/Users/xx2005/Documents/git_repository/DistantStock`
- Transerver 前置：`/Users/xx2005/Documents/git_repository/Transerver`
- 测试整合包：`/Users/xx2005/Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal`
- 测试实例 mods：上述目录下的 `mods`
- DistantStock 构建产物：`DistantStock/build/Create-Distant-Stock-0.3.7+mc1.21.1.jar`
- Transerver 构建产物：`Transerver/build/libs/Transerver-0.1.0-SNAPSHOT.jar`

### 1.2 分支与工作区状态

两个仓库都在 `main` 分支。DistantStock 当前基准提交为：

```text
48ad49d feat: checkpoint Distant Stock 0.3.7 development
```

Transerver 当前基准提交为：

```text
799ccc8 feat: expose node identity and admin status
```

**非常重要：两个工作区都有大量尚未提交的有效开发成果。** DistantStock 同时包含许多已修改文件和未跟踪的新 Java、JSON、PNG、文档及测试文件；Transerver 也有未提交修改。不要执行：

```text
git reset --hard
git checkout -- .
git clean -fd
```

不要为了“整理工作区”删除未跟踪文件。它们不是垃圾，而是本轮开发的主体。提交前应先按功能拆分、审查并确认资源文件，不要把旧的概念图目录误当成正式资源。

### 1.3 构建与验证

在 DistantStock 根目录运行：

```bash
./gradlew clean build verifyWireCodec verifyParcelOwnership
```

最近一次结果为 `BUILD SUCCESSFUL`。`verifyWireCodec` 会验证包裹、订单、网络公告和库存协议的往返、尾随数据与损坏数据拒绝行为。`verifyParcelOwnership` 会验证退件与隔离场景下的物品归属：接管必须先落盘、落盘失败必须回滚、崩溃窗口必须收敛到唯一保管方。

安装前必须确认 Minecraft 已完全退出：

```bash
lsof /完整路径/mods/Create-Distant-Stock-0.3.7+mc1.21.1.jar
```

只有命令无输出时才可以覆盖 JAR。**严禁在 Minecraft 正在运行时覆盖模组 JAR。** 曾经因此出现过：JVM 已缓存旧 JAR 的类索引，文件却被新 JAR 覆盖；放置远仓港首次加载 `DockMode` 时发生 `NoClassDefFoundError`，画面冻结且 ESC 无效。该问题不是远仓港逻辑死循环，而是热覆盖造成的类加载损坏。

当前已安装构建（含回退面、灯态与剔除链路）的 SHA-256 为：

```text
8e0261fa9be78395b2e445c0d9584fd17f93e983636625ba8f5a9c292c76ff15
```

继续修改后哈希当然会变化。每次复制后应同时校验构建产物与 mods 内 JAR 的哈希一致。

## 2. 产品定位与不可破坏的约束

“机械动力：远仓”是 Create 的多服务器仓储与包裹运输附属模组。Transerver 是独立前置，只负责通用可靠消息；DistantStock 负责 Minecraft、Create、物流网络、订单、包裹和玩家交互。

后续实现必须遵守：

1. 支持多台服务器，不是两个服务器一对一传输。
2. 包裹、请求器和存档不得保存 IP、域名或 Router URL；只保存稳定 ID。
3. 远仓港不是固定隧道口。目的节点与接收港组由包裹携带，任意可用发送港均可转交。
4. 发送与接收使用同一个远仓港方块。
5. 一张 Create 仓储网络可以有多个远仓港；一个港组也可以有多个接收港。
6. 不猜测玩家的传送带和本地物流布局。本地包裹地址、目标节点和接收港组必须明确配置。
7. 物品安全高于表面成功。断线、重启、重复消息、目标缺模组、港满、区块卸载都不得复制或吞物品。
8. Create 风格以少 UI 为原则：港口高级状态由工程师护目镜显示；简单模式和数值用 Value Settings；请求器因需要浏览库存而保留仓储 GUI。
9. 普通互通塔、世界互通塔、以太流体和资源消耗属于后续玩法层，不能反向污染当前传输协议。
10. 为未来的流体港、能量港和第三方接口保留可扩展标识，不得写死实现类或两服拓扑。

更完整的产品说明见：

- `docs/PROJECT-OVERVIEW.zh-CN.md`
- `docs/IMPLEMENTATION-PLAN.zh-CN.md`

## 3. 当前架构

### 3.1 三层边界

```text
Create 本地仓储/打包/皮带
          ↕
DistantStock：网络目录、远程订单、稳定路由、港组、包裹托管、Minecraft 交互
          ↕ 仅调用 dev.transerver.api
Transerver：节点身份、消息持久化、重试、回执、去重、Router 传输
```

DistantStock 不应直接访问 Transerver 内部 `core` 包，只通过 `dev.transerver.api`。适配入口为 `TranserverBridge`。

### 3.2 主要频道

定义位置：`routing/RoutingChannels.java`

```text
distantstock:v1.network.announce
distantstock:v1.stock.query
distantstock:v1.stock.result
distantstock:v1.order.request
distantstock:v1.order.result
distantstock:v1.package.dispatch
```

频道名仍为 `v1`，但 `package.dispatch` 的内部二进制信封已升级为 V2；V1 仍可解码。不要仅因内部字段升级就随意更换频道名，否则会割裂持久消息和滚动升级。

### 3.3 服务器生命周期

入口为 `server/GameClock.java`：

- 服务器启动：保证世界身份唯一、注册四类 Transerver 服务、启动 Bridge 和旧 HTTP 迁移服务；
- 每 tick：更新本地状态、推动 Transerver、排空旧队列；
- 每 10 tick：推动包裹托管和远程订单；
- 每 20 tick：扫描 Create 网络并发布网络公告；
- 每 40 tick：刷新远端库存查询与打开中的请求器。

`WorldIdentity.ensureUnique` 已在启动时执行，能够检测同时加载的世界副本并为重复 UUID 重新生成身份。后续仍需实际验证 Multiverse 的创建、卸载、重命名和复制流程。

## 4. 已完成的核心程序

### 4.1 稳定路由

- `RemoteNetworkId`：节点 UUID、世界 UUID、维度键和 Create 网络频率。
- `RemoteRoute`：目标节点、接收港组、关联订单和子订单。
- `RemoteRouteData`：把稳定路由写进包裹数据。
- `OrderRouteDirectory`：持久保存 Create `orderId` 到远仓路由的关系。
- 远仓打包机生成包裹时写入路由；原版打包机包裹进入港口时，也会按 Create `orderId` 尝试补齐。
- 目的地解析是严格三级的 `routing/RouteResolution`：包裹自身路由 > Create 订单路由 > 发送港的默认目的；三者皆空时包裹留在港内并报错，不猜目标。
- 旧 `ReturnRoute` 地址映射已删除（类与唯一写入点），地址不再参与路由。

### 4.2 Transerver 适配

- DistantStock 已声明 Transerver `0.1.x` 为必需前置，开发构建接受 `0.1.0-SNAPSHOT`。
- `TranserverBridge` 是唯一适配层。
- 节点身份、别名、在线状态和队列状态已接入。
- 网络公告、库存查询、订单请求、包裹发送均有版本化且限长的二进制编码。
- 新请求器优先按 `networkId.nodeId` 发送；旧 HTTP 流程暂时保留用于迁移。

### 4.3 订单安全

- `InboundOrderInbox` 在目标服持久保存远程订单。
- 调用 Create 生产前先写入并强制保存 `PROCESSING` 状态。
- 崩溃后不自动重放状态不确定的生产请求，避免重复生产。
- `RemoteRoute` 与 Create 的 `PackagingRequest.orderId` 关联。

### 4.4 包裹托管、回执和去重

- `ParcelEscrow`：来源服在真正交出包裹前持久托管完整编码、路线、来源维度与方块坐标。
- `ParcelEscrowPump`：提交持久消息、读取 `APPLIED` / `REJECTED` 回执、恢复退件。每 10 tick 执行一次。
- `ParcelLedger`：目标服持久保存 `parcelId -> APPLIED`，重复消息不会重复生成物品。
- `TranserverPackageService`：在 Minecraft 主线程完成校验和插入，只有成功插入接收港后才返回 `APPLIED`。
- 目标港不存在或已满时返回 `RETRY`，不会自动投到错误港组。

退件与隔离（本轮之前新增）：

- `ParcelReturnInbox`（`distantstock_parcel_returns`）：托管记录在原发送港不存在或未加载时的**兜底**保管方（宽限 600 tick 后接管，之后每 100 tick 重试交付）。原港恢复后，内容交给**回退面**而不是发送缓存。
- `ParcelQuarantine`（`distantstock_parcel_quarantine`）：来源服自己无法解码的记录进入隔离库，保存原始字符串、SHA-256、路线、原因、明细和时间，不自动投递，只能由管理员处理。
- 单所有者迁移规则：先把新保管方记录写入并调用 `InboundOrderInbox` 同款 `flush(server)` 强制落盘，成功后才从 `ParcelEscrow` 删除。落盘抛异常时回滚新记录，托管记录保留，绝不先删后写。
- 崩溃窗口处理：两个数据表都在覆世界的 `DimensionDataStorage` 里，但两次落盘之间存在极小的崩溃窗口，可能同时留下两份记录。`ParcelReturnInbox.reconcile` 每个 tick 批次先运行，凡是退件箱或隔离库已经持有的 `parcelId`，一律丢弃 `ParcelEscrow` 中的旧副本，因此“新保管方优先”，包裹不会被退回两次。
- 容量上限：退件箱 32768 条、隔离库 8192 条。超限时拒绝写入并让 `ParcelEscrow` 继续持有，不静默丢弃。

退回与剔除的完整链路（本轮新增，细节见 `docs/design/RETURN-FACE.zh-CN.md`）：

- 目标拒收时先算出自身缺失的注册表条目，通过 `distantstock:v1.package.strip` 把 `parcelId + 缺失清单` 发回来源；
- 来源把缺失清单挂到托管记录（`ParcelEscrow.attachStrip` 并落盘），由 `ParcelEscrowPump` 在原港已加载时整件剔除命中条目；
- 剔除物进港的回退缓冲并从底面推出，剩余内容写回 `create:package_contents`、重算清单与哈希、记一次 `strips` 后重新提交；
- 未命中任何条目或 `strips` 达到 3 次时不允许重发，整包从底面退出并置故障灯；
- 交付一律"先前交付、后销账"，崩溃窗口内可能重复交付一次，但不会丢失；未覆盖项见设计文档第 6 节。

管理员命令（`/distantstock`，权限等级 2）：

```text
/distantstock status                     概览：托管/在途/待退回、退件箱、隔离库、Transerver 队列、远程订单
/distantstock returns [list] [页码]      退件箱逐条列表（序号、短 ID、原因、目标、来源、等待时长、重试次数）
/distantstock returns restore <id>       立即退回原发送港（原港未加载/已满/非发送模式会明确报错）
/distantstock returns give <id>          把包裹交给管理员背包（原港已消失时的出路）
/distantstock returns export <id>        导出诊断文件（含 SHA-256、路线、原因与 base64 原始数据）
/distantstock quarantine [list] [页码]   隔离库逐条列表（含数据完整/损坏标记）
/distantstock quarantine give <id>       解码成功时交给管理员背包
/distantstock quarantine export <id>     导出原始数据与哈希，供人工修复
/distantstock quarantine discard <id>    显式删除隔离记录（唯一会销毁包裹的命令）
```

`<id>` 既可以用列表序号，也可以用 UUID 前缀，命令自带补全。

### 4.5 PayloadManifest 与跨服模组差异

最近完成：

- `PayloadManifest` 收集本包裹实际引用的物品 ID 和数据组件 ID；模组集合可由命名空间推导。
- 扫描包裹外壳和九格内容，并递归扫描标准容器组件、收纳袋、弩的装填物；递归深度上限 8。
- 目标服在反序列化 NBT 前检查自身 `ITEM` 与 `DATA_COMPONENT_TYPE` 注册表。
- 缺少条目时返回 `REJECTED`，来源托管记录随后负责退件。
- `PackageDispatchCodec` V2 的 SHA-256 覆盖整个信封，而不只是包裹 NBT；修改地址、清单或负载都会失败。
- V1 信封仍按旧的“包裹 NBT 摘要”规则读取。

尚未覆盖所有第三方模组把嵌套物品私藏于自定义数据组件或原始 `CUSTOM_DATA` 的特殊格式。权威解码仍是最后防线；不要因为清单通过就跳过目标服解码与 `PackageItem.isPackage` 校验。

## 5. 当前方块与客户端状态

### 5.1 远仓港

关键类：

- `block/DockBlock.java`
- `block/DockBlockEntity.java`
- `block/LoadedDocks.java`
- `client/DockRenderer.java`

现状：

- 同一方块支持发送、接收、双向三种内部模式。
- 接收缓存与发送缓存各 9 格，能力处理器：前 9 格仅供提取到货包裹，后 9 格仅接收待发包裹；**底面单独一套能力**，只允许投入，`extractItem` 永远返回空，因此底下的溜槽抽不到任何包裹。
- 底面是**回退面**：港把无法处置的包裹与被剔除的整件物品推进下方容器（溜槽、漏斗、箱子、皮带都行），推不出去就留在 9 格回退缓冲里并进入橙色闪烁。
- 橙色（堵塞）只代表"真的有东西出不去"：回退缓冲非空且推不动、回退面交付被拒、发送缓存里有无法解析目的地的包裹。空机永远是绿灯。
- 机壳贴图必须保留 Create 打包机的**镂空**（`remote_packager_*` 的 alpha 与 Create 原版逐像素一致，可用 alpha 通道从 create jar 直接还原，别去补实）。镂空后面要靠**发光机芯**兜住：远仓港由 `DockRenderer.renderInnerCore` 在内部画一个全亮核心，打包机靠 Create 自己的 `PackagerRenderer`。删掉机芯会立刻看穿到虚空。
- 信号灯的物品名必须由 `SignalLampPanelItem.getDescriptionId()` 指定：它继承 `BlockItem`，不覆盖就会显示方块名（信号面板）。
- 灯格不要覆盖 `isActive()`（Create 用它判断槽位占用，覆盖会让灯格渲染成空仪表）；要屏蔽的是 Create 客户端提示：`getAmountTip()` 必须返回非空的 `Component.empty()`，绝不能返回 `null`，否则 `FilteringRenderer` 会把 null 放进提示列表并在 `Font.width` 崩溃；同时配 `acceptsValueSettings=false` + `bypassesInput=true`。
- `SignalLampPanelItem.placeStandalone()` 的 `BlockPlaceContext.getClickedPos()` 已经是实际放置坐标，绝不能再沿点击面 `relative()`；独立灯放置失败时必须返回 `FAIL`，不能 `super.place()`，因为该物品继承绑定的是 `SIGNAL_PANEL`，回退会悄悄生成工厂面板并重新进入 Filtering/Value Settings 路径。
- 舱口（Create 打包机的螺旋舱门）是**模型元素**，不是靠渲染器：`remote_dock_*` 里叫 `bottom_hatch`、`remote_packager*` 用作者原始坐标（Create 的"关闭"状态就是原位）。
- 打包机的舱口与托盘**不要写进模型**，也不要指望 `PackagerRenderer`：它在 `VisualizationManager.supportsVisualization()` 为真时直接跳过这两样，交给按方块实体类型注册的 Flywheel visual（Create 只给自己的类型注册了 `PackagerVisual`）。我们的类型必须在客户端 `FMLClientSetupEvent` 里自行注册：`SimpleBlockEntityVisualizer.builder(REMOTE_PACKAGER).factory((ctx,be,t) -> new PackagerVisual<>(ctx,be,t)).neverSkipVanillaRender().apply()`。`neverSkipVanillaRender` 不能省——Create 的 BER 还要在 Flywheel 检查之外画包裹。
- 往模型里再塞一个静态舱口会变成"两个舱口、其中一个在中间"，`SuperByteBuffer.translate` 的单位是**方块**（0.5 = 半格），不是模型单位（曾把舱口丢到 6 格之外）。
- 给模型加自己的元素时注意：**子模型一旦定义了 `elements`，父模型的几何会被整体替换**，会只剩自己那个元素（打包机曾因此只剩舱口、框架消失）。
- 模型元素**不能用 90° 旋转**：MC 只接受 ±22.5/±45，写了 90 会让整个模型烘焙失败、方块与物品都变紫黑。需要 90° 时把旋转"烘"进坐标与面朝向里（远仓港底舱口就是这么做的：把 y/z 互换、north↔up、south↔down）。改完务必用 `scripts/preview_block_art.py` 离线渲染确认。
- 数值面板的命中测试必须在 behaviour 里**减去方块坐标**（Create 传到 behaviour 的是世界坐标，数值框用方块内坐标）；写成 `slot.testHit(level, pos, state, hit)` 就永远命中失败、面板永远不弹。这条曾经丢失两次，改动 `DockModeBehaviour`/`DockPriorityBehaviour` 时重点检查。
- 方块的 ticker 必须**双端**注册、并在 `serverTick` 里先 `be.tick()` 再判 `isClientSide` 返回：Create 只在 `SmartBlockEntity.tick()` 里初始化 behaviour，客户端没有 behaviour 就不会弹数值面板。
- 灯格不要覆盖 `isActive()`：Create 用它判断槽位是否占用，覆盖成 false 会让灯格渲染成空的工厂仪表。
- 我们自己的方块都实现了 `IWrenchable`，但覆盖了 `onWrenched` 返回 SUCCESS：扳手单击只报状态，不旋转（朝向由放置决定）。
- 灯的四个格使用 `FactoryPanelBehaviour` 的子类，但灯格必须 `acceptsValueSettings=false` 且 `bypassesInput=true`：否则按住右键会弹出 Create 工厂仪表的数量设置，灯的改名/拆除也会被吞掉。
- 灯态是单一方块状态 `status`：熄灭＝未接入、绿＝待机、青闪＝发送、橙闪＝回退堵塞、红＝故障；闪烁用两帧动画贴图，不产生状态刷新。
- 进入故障或堵塞时在港位置播放一次 Create `DENY` 提示音；玩家用扳手或空手右键可以清除故障。
- 接收港组按稳定排序后轮转选择有空间的港。
- 带稳定路由的包裹优先使用自身目标节点和港组。
- 发送动画持续 30 tick：包裹从港内中央上升，接近以太面时缩小，最高包围范围低于传送面，动画完成后才真正从缓存移入托管。
- 新 V5 外观已正式接入：白灰淡蓝打包机式机架、立体顶框、凹入式动态以太面、2×2 Create 风格灯。
- 破坏港时会把到货、待发、回退三个缓存全部弹出，不再吞掉落包裹。

交互：远仓港的少量数值走 Create 的 Value Settings（拿扳手对着港按住右键）。现在有两个数值面板：**模式**（出货/收货/双向，三行选项）与**优先级**（P0–P5，最高优先层里有空位的港先收，同层轮流）。面板位置在港的舱面左上与右上。

**结构注意**：为了拿到 Value Settings，`DockBlockEntity` 已经从 `BlockEntity` 改成 `SmartBlockEntity`，因此有几处约定必须遵守，改这个类之前先看：

- 存档不再用 `saveAdditional`/`loadAdditional`（在 `SmartBlockEntity` 里是 final），改用 `write(tag, registries, clientPacket)` / `read(...)`，两者都要先调 `super`（行为数据的读写在那里）；
- `setRemoved()` 也是 final，清理放进 `remove()`（`SmartBlockEntity.setRemoved` 会在服务端调它），另外 `destroy()` 与 `onChunkUnloaded()` 也一起清 `LoadedDocks`；
- 方块的 ticker 现在**双端**都注册：Create 只在 `SmartBlockEntity.tick()` 里初始化 behaviour，客户端需要 behaviour 才能渲染数值框；原服务端逻辑在 `level.isClientSide` 提前返回之后。

未完成：港组调谐工具、真正的地址牌交互、请求器的收货港组选择、需要新 schematic 的回退面思索分镜。默认目的已可用调谐请求器设置。护目镜现在会显示灯态、回退面占用与最近回退原因，但退件箱/隔离库只能通过 `/distantstock` 命令查看。

### 5.2 远仓打包机与远仓包裹

- `RemotePackagerBlockEntity` 继承/适配 Create 打包行为并补写远仓路由。
- 方块模型已改为正确透明渲染，中间不会再被实体层填满。
- 远仓包裹使用低饱和灰白淡蓝贴图，并注册 Create 的包裹 PartialModel，传送带/包裹实体应显示对应外观。

### 5.3 信号灯系统

- 五种安山信号灯：青、橙、红、绿、白。
- 黄铜信号灯根据红石强度切换颜色。
- 可以作为独立墙面灯，也可以与 Create 工厂仪表一样在一个方块中占四分之一格。
- `SignalPanelBlockEntity` 保留原版工厂仪表连接；灯能够读取邻接红石和连接的仪表输出。
- 命名牌可修改单灯名称；工程师护目镜显示名称、材料、颜色和强度。
- 修复了放置时先闪现工厂仪表的问题：客户端数据未同步前不渲染默认仪表占位。
- 熄灭使用 `translucent` 保留玻璃感；点亮使用 `cutout` 且满亮度，避免被透明度二次压暗。

### 5.4 请求器、监视器和仪表

- 请求器 UI 已向 Create 仓储界面靠拢，使用淡蓝灰背景，并增加来源网络、本地地址、接收港组等路由字段。
- 请求器数据已支持稳定 `networkId`，同时保留旧频率迁移。
- 远仓监视器用于 TPS、链路和队列概览，不等同于远仓仪表。显式打开只走 `OpenMonitorS2C`；每秒刷新只走 `LinkSnapshotS2C`，后者只能更新来源坐标一致且已经打开的 `MonitorScreen`，不能打开界面。禁止再把打开标志塞回周期刷新包。
- 远仓仪表对应 Create 工厂仪表，用于跨服请求生产；相关右侧模型方向曾修正 180°。
- 这些部分仍需要真实多人、多服流程验证，不能仅凭界面可打开认定完成。

## 6. 关键类索引

| 领域 | 主要文件 |
|---|---|
| 生命周期 | `server/GameClock.java` |
| Transerver 边界 | `link/TranserverBridge.java` |
| 网络发现 | `link/NetworkAnnouncementService.java`, `stock/NetworkDirectory.java` |
| 库存查询 | `link/TranserverStockService.java`, `link/StockWireCodec.java` |
| 远程订单 | `link/TranserverOrderService.java`, `link/InboundOrderInbox.java`, `link/OrderRequestCodec.java` |
| 路由 | `routing/RemoteNetworkId.java`, `RemoteRoute.java`, `RemoteRouteData.java`, `OrderRouteDirectory.java` |
| 目的地解析与港选择 | `routing/RouteResolution.java`, `routing/DockSelection.java` |
| 包裹协议 | `link/PackageDispatchCodec.java`, `PayloadManifest.java` |
| 来源托管 | `link/ParcelEscrow.java`, `ParcelEscrowPump.java` |
| 退件与隔离 | `link/ParcelReturnInbox.java`, `ParcelQuarantine.java` |
| 剔除与回退面 | `link/PackageStripCodec.java`, `PackageStripService.java`, `block/DockStatus.java` |
| 目标去重 | `link/ParcelLedger.java`, `TranserverPackageService.java` |
| 港组 | `routing/DockGroup.java`, `DockGroupDirectory.java`, `block/LoadedDocks.java` |
| Create 库存/生产 | `stock/CreateStock.java`, `StockScanner.java`, `StockCache.java` |
| 请求器网络包 | `net/PlaceOrderC2S.java`, `StockSyncS2C.java`, `menu/RequesterMenu.java` |
| 远仓港 | `block/DockBlock.java`, `DockBlockEntity.java`, `client/DockRenderer.java` |
| 远仓打包机 | `block/RemotePackagerBlock.java`, `RemotePackagerBlockEntity.java` |
| 信号面板 | `block/SignalPanelBlock.java`, `SignalPanelBlockEntity.java`, `client/SignalPanelRenderer.java` |

## 7. 下一阶段推荐顺序

### P0：先做一次干净启动回归

不要继续堆功能之前先验证当前 JAR：

1. 完全退出游戏后安装；
2. 新建或备份测试世界；
3. 分别放置远仓港、远仓打包机、五色独立灯和四分格灯；
4. 确认远仓港不再出现 `DockMode` 类加载错误；
5. 确认信号灯熄灭半透明、点亮明显、放置时不闪工厂仪表；
6. 确认港口动画不穿模；
7. 检查 `logs/latest.log` 中所有 `distantstock` ERROR/WARN。

如果仍出现放置即冻结，先收集异常首行和 `Caused by`，不要凭表现猜测渲染死循环。

### P1：远仓港回退面与剔除（已实现，待实测）

退件不再集中到全服公共位置，也不再回到发送缓存：

- 底面是回退面（只出不进的能力，`extractItem` 永远为空），港把无法处置的包裹和被剔除的整件物品推到下方容器；
- 灯态改为单一方块状态：熄灭＝未接入、绿＝待机、青闪＝发送、橙闪＝回退堵塞、红＝故障，闪烁用两帧动画贴图；
- 目标缺模组时通过 `package.strip` 反向频道带回缺失清单，来源拆包整件剔除、重算清单与哈希、限次重发；
- 破坏港会把到货/待发/回退三个缓存全部弹出；
- 设计、护栏和未覆盖项见 `docs/design/RETURN-FACE.zh-CN.md`。

仍需实测：拒收→剔除→重发→送达全链路、无回退容器时的橙闪与提示音、原港被拆除后的管理员恢复。

### P1：服务器退件箱与隔离库（已完成，待实测）

两个 `SavedData` 已实现，细节见 4.4：

- `ParcelReturnInbox` 接管来源港不存在、区块未加载或缓存已满的 `REJECTED` 托管记录，宽限期 600 tick，之后每 100 tick 重试退回原港；
- `ParcelQuarantine` 保存无法解码记录的原始字符串、SHA-256、路线、原因、明细和时间；
- 迁移遵循单所有者转换：先写新保管方并 `flush` 落盘，再从 `ParcelEscrow` 删除，落盘失败回滚；
- `ParcelReturnInbox.reconcile` 在崩溃窗口内让新保管方优先，避免退件两次；
- 管理员命令、逐条恢复和诊断导出见 4.4 的命令表；
- `./gradlew verifyParcelOwnership` 覆盖上述规则（接管落盘、失败回滚、崩溃窗口、隔离哈希与篡改检测、持久化往返）。

仍需实测：真实多服场景下的退件箱投递、原港被拆除后 `give`/`export` 流程、退件箱接近容量上限时的告警表现。

### P1：REJECTED 包裹会被再次寄出（已解决）

原先退件回到发送缓存会被港口再次寄往同一目标，永久拒收（例如缺少模组）会形成无限重发。现在退件一律**从港的回退面退出**，不再进入发送缓存，因此不存在自动重发。

新的重发只发生在一种情况：目标通过 `package.strip` 明确报告了缺失清单，来源拆包剔除命中条目后**限次重发**（`STRIP_LIMIT = 3`），并且"本次一件都没剔掉"时不允许重发。整套设计见 `docs/design/RETURN-FACE.zh-CN.md`。

### P1：移除地址级 ReturnRoute（已完成）

- 调用点统计结果：唯一写入点是 `OrderService.drainInbound`，唯一读取点是远仓港出货逻辑；
- 读取点已改为 `RouteResolution` 三级解析，写入点与 `ReturnRoute` 类一并删除，地址不再参与路由；
- 手动普通包裹使用发送港显式设置的默认目的：调谐请求器（带远端网络）右键设为出货并记录默认目的，扳手潜行右键清除；
- 未设置默认目的时包裹留在发送缓存、护目镜显示 `send.no_route`，不会使用空目标或随机节点；
- `verifyWireCodec` 新增 `RouteResolution` 优先级与 `DockSelection` 优先层级/轮转/满组场景的检查。

### P1：完成远仓港玩家交互

目标不是增加一个管理 GUI。推荐拆分：

- Value Settings：发送/接收/双向、优先级；
- 调谐物品：复制接收港组、应用港组、清除绑定；
- 地址牌：设置落地后的本地 Create 地址；
- 默认发送路线：目标节点 + 接收港组，供没有 `RemoteRouteData` 的普通包裹使用；
- 护目镜：模式、节点别名、港组名称、地址、两侧缓存、托管状态、最后错误。

任何普通包裹如果没有自身路由且港口也没有默认目标，应留在发送缓存并显示明确错误，不能使用空目标、随机节点或自动猜测。

### P2：仓储网关与请求器闭环

- 远仓港本地绑定 Create 仓储网络并登记 `RemoteNetworkId`；
- 网络目录增加玩家/频道权限过滤；
- 请求器明确选择来源网络、接收港组和到货地址；
- V1 一张订单只选一张来源网络，不自动跨网络拆分；
- 将订单状态与包裹、回执、退件原因关联；
- 更新请求器 UI、说明书和 Ponder。

### P2：多世界与权限

- 实测 Multiverse 世界加载、卸载、重命名、复制；
- 禁止仅用维度显示名作为身份；
- 频道角色：所有者、管理员、成员、访客；
- 正版服用玩家 UUID；离线/代理环境使用一次性认领码；
- 所有目录、库存和下单接口在服务端重新校验权限，不能相信客户端选项。

### P3：互通塔和资源系统

基础传输可靠后再做。当前共识：

- 普通互通塔：底座 3×3 或 5×5，顶部以太谐振器，中间一格宽耦合柱，外部可自由装饰；底座和顶部距离决定等级，有上限；
- 负责区块加载、范围内设备激活、带载、速度和资源效率；
- 世界互通塔：全服共同建设，可激活全世界远仓设备；
- 小塔制作阶段使用以太流体；世界塔可持续消耗；
- 传输资源在发送前预留，成功后结算，失败/退件不扣除；
- 未来流体港、能量港暂不实现，但协议需留类型扩展位。

## 8. 测试矩阵

公开测试前至少覆盖：

1. 三节点 A→B、B→C、C→A 同时传输；
2. 一网多港、一港对多目的地、同港组轮转；
3. 两位玩家使用相同本地地址同时下单；
4. Router 断线、来源服重启、目标服重启；
5. 目标服缺少顶层物品模组；
6. 潜影盒/收纳袋中嵌套目标服没有的物品；
7. 目标服缺少数据组件注册；
8. 目标港满、目标区块卸载、世界卸载；
9. 重复消息、重复回执、回执乱序；
10. 修改 ZeroTier 地址、域名或 Router URL，既有包裹仍可路由；
11. Multiverse 世界复制后 UUID 去重；
12. 一百次故障注入后核对物品总量守恒；
13. 目标拒收且来源港区块卸载：宽限期后进入退件箱，原港加载后从回退面交付；
14. 目标缺模组：来源整件剔除命中条目、限次重发、剩余内容送达；剔除物落到回退面下方容器；
15. 底面没有容器或容器已满：港橙灯闪烁并保留内容，补上容器后自动排出；
16. 零剔除或达到重发上限：整包从回退面退出并亮红灯；
17. 目标拒收且原港已被拆除：退件箱保留，`/distantstock returns give` 与 `export` 可用；
18. 退件箱/隔离库记录在服务器重启后仍存在，且只被交付一次；
19. 破坏装着包裹的远仓港：三个缓存全部弹出，无物品消失。

每个故障测试都应回答：“此刻谁持有物品的唯一所有权？”合法答案只能是本地库存、来源托管、Transerver 持久消息、目标港或退件/隔离库中的一个。

自动化部分：`./gradlew verifyWireCodec`（协议编解码）与 `./gradlew verifyParcelOwnership`（退件与隔离的物品归属规则）必须一起通过。

## 9. 资源与美术注意事项

- 正式资源位于 `src/main/resources/assets/distantstock`。
- `assets/distantstock_concept`、`docs/design/concepts` 和外部 `outputs` 中有大量概念版本，不应自动批量发布。
- 远仓港当前正式样式来自确认过的 Remote Dock Portal V5。
- Create / Minecraft 原版贴图以 16×16 为主要风格基准；不要用 LLM 生图替代可落地像素资源。
- 机壳必须考虑 CT 拼接连续性；边框合并、中间纹理变化不能只看单方块效果图。
- 远仓配色不是高饱和青色，而是低饱和灰白带少量淡蓝。
- 信号灯应接近工厂仪表和显示链接器的小灯：低对比、像素化、点亮清楚但不是霓虹灯。

## 10. 上一轮任务与下一轮建议

上一轮（2026-09-10）交接指令已完成：`ParcelReturnInbox`、`ParcelQuarantine`、单所有者迁移、`reconcile` 对账、`/distantstock` 退件与隔离命令、`verifyParcelOwnership` 检查全部落地，构建产物已安装到测试整合包并校验哈希。细节见 1.3、4.4 与 7。

下一轮建议按此顺序：

```text
请先阅读 docs/CURSOR-HANDOFF.zh-CN.md、docs/IMPLEMENTATION-PLAN.zh-CN.md 和 docs/PROJECT-OVERVIEW.zh-CN.md。当前工作区包含大量未提交的有效修改，禁止 reset、checkout 或 clean，也不要删除未跟踪资源。

先运行 ./gradlew clean build verifyWireCodec verifyParcelOwnership，确认基线。

1. 实测退件箱：制造目标拒收（缺模组或损坏数据），确认包裹按规则进入退件箱而不是被吞掉，检查 /distantstock status 与 returns list，必要时用 give 与 export 走通人工恢复。
2. 处理 7 中“REJECTED 包裹会被再次寄出”的无限重发风险，先给出方案再改代码。
3. 接着做 7 的“完成远仓港玩家交互”剩余部分：港组调谐工具（复制/应用/清除绑定）、地址牌交互、请求器选择收货港组。Value Settings（模式 + 优先级）已完成，见 5.1 的结构注意。

不要开始互通塔、流体或新贴图，不要删除旧 HTTP 兼容路径，不要改变 Transerver 公共 API，除非完成当前安全闭环确实需要且同时更新两个独立仓库。完成后再次运行构建与两个检查任务，报告修改文件、状态迁移规则、测试结果与仍未覆盖的故障场景。不要在 Minecraft 运行时覆盖 mods 中的 jar。
```

## 11. 当前交接结论

代码已从“HTTP 两服原型”推进到具备稳定节点/世界/网络/港组标识、Transerver 可靠频道、持久订单收件箱、来源包裹托管、目标幂等账本、V2 兼容清单、服务器退件箱与隔离库，以及真实远仓港动画的阶段。物品在任何故障时刻都只属于本地库存、来源托管、Transerver 持久消息、目标港、退件箱、隔离库之一。

剩余的关键路径是：实测退件箱闭环与多服故障、解决永久拒收导致的重发、淘汰地址级临时路由、补齐港口玩家配置。互通塔和材料系统继续排在可靠传输之后。
