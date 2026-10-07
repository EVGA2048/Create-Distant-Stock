# Distant Stock 联机测试修复记录 — 2026-09-19

这份文件记录 ES2 / Parallel A-B 联调期间发现的问题与源码修复。

本轮约定：问题先只修源码并记录；不自动替换测试客户端 Jar，不自动发包。

## 远仓机壳观察窗红石传播默认范围不一致

- **现象**：玩家侧文本与 GameTest 设计都按默认 **32 格**理解，但 `StockConfig` 实际把
  `casing.redstoneRange` 默认值和配置未加载时的 fallback 写成了 **64**。
- **影响**：远仓机壳受到红石后，透明观察窗沿相连机壳传播的距离比文档/测试预期长一倍。
- **修复**：把 `casing.redstoneRange` 的代码默认值和 fallback 统一为 **32**，并同步配置注释。
- **兼容性**：已有服务器如果 `distantstock-common.toml` 中已经显式保存了
  `casing.redstoneRange = 64`，仍会继续使用 64；本修复不会擅自覆盖管理员已有配置。
- **验证**：`CasingGameTests` 本身已经以“default range of 32”为设计前提，继续用它验证传播边界。

## Arclight A/B：network.announce 后 Transerver pump 失败

- **环境**：A / B 两个 Arclight 1.21.1 实例共用本地 Transerver Router；A 为既有世界，B 为全新世界。
- **已确认**：独立 Transerver probe 经同一 Router 可正常送达，HTTP、HMAC、节点路由本身可用。
- **现象**：Distant Stock announce 开始后，B 的 dead-letter 出现 A 发来的
  `distantstock:v1.network.announce`，随后节点出现 pump failure。
- **协议核对**：announcement payload v1→v4 的每次布局变化都提升了版本号，当前 decoder
  兼容 v1–v4；未发现 encode/decode 字段顺序不对称。
- **重要判断**：Transerver 只有 handler 明确返回 `REJECTED` 才会把消息写入 dead-letter；
  handler Future 异常本身不会产生 dead-letter。因此 B 的 announce 至少有一条在进入主线程应用前
  就触发了 payload/来源校验拒绝，不能把 Arclight 调度直接当成已证实根因。
- **Distant Stock 修复**：
  - announce 的每一种拒收路径记录 message id、source 与具体原因；
  - 合法公告先更新线程安全的远端网络/指标快照，再把 SavedData 更新排到 Minecraft 主线程；
  - 不再让 Transerver handler Future 等待主线程完成，减少 Arclight 调度语义对 transport 的耦合；
  - 所有 Distant Stock Transerver handler 增加统一异常边界：同步/异步异常记录完整日志并返回
    `RETRY`，避免业务异常冒出 transport 边界。
- **Transerver 配套修复**：本轮测试包必须同时使用新版 Transerver；旧 Jar 的运行时与回执幂等逻辑
  不足以代表当前源码，详见 Transerver 仓库对应测试。
  - Runtime 使用 `pumpSafely()` 周期执行；一次异常不再终止整个定时 pump；
  - pump 状态包含具体失败 stage 与 cause chain，不再只剩 `Transerver pump failed`；
  - 应用确认过的最终回执保留 `sent-receipts` 幂等记录，迟到/重复 receipt 不再因
    `send-results` 已被消费而误判为未知消息；
  - 从旧版本遗留的孤儿 receipt、以及与已确认结果冲突的 receipt，会保存到
    `receipt-quarantine` 后从 Router ACK 掉，保留证据但不形成永久毒消息。
- **验证**：
  - Transerver：29 tests passed；
  - Distant Stock：139/139 required GameTests passed；
  - Client smoke passed；
  - `test`、`verifyWireCodec`、`verifyParcelOwnership`、`verifyClientAssets` 全部通过。

## 工况灯：黄/绿上升沿蜂鸣提示

- **需求**：蜂鸣器使能打开时，黄灯或绿灯从灭变亮也要响一次；红灯保持原来的周期报警。
- **实现**：
  - 黄灯 `0 -> 1`：播放一次蜂鸣；
  - 绿灯 `0 -> 1`：播放一次蜂鸣；
  - 黄、绿同时上升：只播放一次；
  - 黄/绿保持高电平、下降沿、以及单独打开蜂鸣器使能：不播放；
  - 若黄/绿上升沿与红色故障同时发生，这次单次蜂鸣直接作为红灯当前报警节拍，下一次周期蜂鸣延后 22 tick，避免两声重叠。
- **闪烁建议**：工况灯本身继续只忠实显示输入，不内置闪烁状态机。Create 已提供
  `Pulse Timer`（周期脉冲）；需要更明显的亮灯占空比时可接 `Pulse Extender` 后再送入工况链接器。

## 远仓日志台：正式美术、网络预绑定与事件告警

- **正式美术**：日志台主体接入 `logger-console-v3`；打印物品使用 `logger-items-v1`
  的日志条，小票卷物品使用 `logger-items-v2`。打印后的纸张作为独立 overlay，不覆盖当前告警灯状态。
- **放置流程**：日志台改为专用 `LoggerItem`。放置前必须先拿日志台右键一个已经加入 Create
  物流网络的物流链接；绑定成功后物品发光并保存 Create 网络、远仓节点/网络作用域。未绑定日志台拒绝放置。
- **辉光管**：两个管子的字符不再烘焙固定 `00`，而是直接复用 Create
  `NixieTubeRenderer.drawTube()` 动态绘制字母和数字。当前状态码：`OK`、`OF`、`W1..W9`、`WA`、
  `E1..E9`、`EA`。
- **灯泡规则**：
  - 正常：绿灯常亮；
  - 未确认 WARN：绿灯 + 黄灯闪烁；打印后黄灯常亮；
  - 未确认 ERROR：绿灯 + 红灯闪烁；打印后红灯常亮；
  - 绑定网络离线：三灯熄灭，辉光管显示 `OF`。
- **蜂鸣器**：沿用工况灯蜂鸣器。未确认 WARN 每约 3 秒提示一次；未确认 ERROR 按约 1.1 秒告警节拍响；
  INFO 和已经打印确认的事件不响。服务端的旧 `ACKNOWLEDGE` 协议动作也强制走打印流程，不存在静默消音旁路。
- **日志源**：继续使用持久化 `EventRegistry` 的 `INFO / WARN / ERROR` 等级体系。除原有的塔、Transerver、
  路由错误、包裹隔离和远仓港回退堵塞外，新增：Create 网络离线、物流链接缺失、网络锁定、长时间
  package promise 未完成，以及远仓港发出舱长期卡包。自动化 promise 60 秒为 WARN，180 秒升级 ERROR；
  发出舱卡包 5 秒为 WARN，60 秒升级 ERROR，恢复后自动清除活动事件。
- **小票 Lore**：保存并显示事件时间戳、打印时间、严重级别、事件码、来源类型/来源、Create 网络完整 UUID、
  远仓网络完整 UUID、详细信息、累计次数和事件号。小票本身没有 `use` / `useOn`，右键不会打开 UI。
- **兼容性**：老存档只保存 `CreateFrequency` 的日志台仍能读旧事件，但不会启用新式网络健康采样；重新预绑定
  后即可得到完整作用域与网络健康检测，避免升级后旧设备被误判全部离线。

## 四分格混合仪表：远仓仪表错误显示原版材质

- **现象**：一个四分格板内装多个不同仪表时，远仓仪表槽有时使用 Create 原版工厂仪表外壳。
- **根因**：`SignalPanelBlockEntity` 已经保存了每个槽是否为远仓仪表，但渲染时仍通过
  `behaviour instanceof RemotePanelBehaviour` 猜类型；混合板上的远仓仪表使用宿主板的通用 behaviour，
  所以判断失败并回退到 Create 原版模型。
- **修复**：`SignalPanelRenderer` 将该槽保存的 `remote` 标记显式传给 `RemoteGaugeRenderer.housingFor(...)`，
  槽类型成为权威来源，不再用 behaviour 子类推断。

## 日志台：替换纸卷接入打印流程

- 日志台新增 16 张/卷的纸量状态，默认 0。
- 手持 `logger_paper_roll` 右键空纸日志台装卷；当前卷未用完时拒绝继续塞卷。
- 每张事件小票消耗 1 次纸量；纸量持久化，并同步到日志 GUI 与护目镜。
- 缺纸时事件、灯光与蜂鸣继续工作，但 PRINT / legacy ACK 都被服务端拒绝，保证“打印 = 确认 = 消音”不会被绕过。

## 路由：正式远仓网络成为强制命名空间

- 隐藏 Legacy 网络只保留旧存档读取，不再用于新地址、远程订单、设备绑定或网络公告。
- 未创建/加入正式远仓网络的 Create 仓库不会向其它节点公告，且不能建立远仓路由。
- 接收港地址按 `DistantNetworkId` 严格隔离；不同远仓网络允许同名地址，互不冲突。
- 移除正式网络向 Legacy 地址回退的兼容后门。
- 单人存档无需 Transerver 也可以使用本地稳定节点 ID 创建正式远仓网络。

## 远仓港：发送前实时接收确认

- 网络公告中的港数量只继续用于发现/显示，不再作为最终发货许可。
- 发件港在扣以太、进入 escrow、移走实体包裹之前，对目标节点发送实时 receiver probe。
- 探测同时携带远仓网络 ID 与接收港组 ID；对端只有在“同网络、同地址、当前至少一个可接收港”时返回可用。
- 无可用接收港时包裹留在源港，橙灯闪烁，护目镜显示目标地址原因，并记录 `DOCK_NO_RECEIVER` WARN 供日志台打印。
- 一次肯定答复只许可一个包裹，发送后立即消费；无回复超过一次探测窗口后也按不可用告警，而不是永远静默等待。


## 2026-09-20：单人网络页 / 请求台设置 / 便携终端地址回归

现象：

- 单人档打开远仓网络页面仍显示未选择本地 Create 仓储网络；
- 远仓请求台关闭再打开后，地址、回程地址、接收港地址等界面设置看起来全部丢失；
- 便携式远仓终端携带的旧接收港地址（例如 `333`）删除或修改后会再次出现。

根因与修复：

1. `StockScanner` 曾把“传输 runtime 是否在线”误当成“节点是否有身份”。最终修复不再使用任何固定哨兵：Transerver 会独立加载持久化 `node-identity.properties`，Distant Stock 始终使用该正式节点 UUID；transport 离线只暂停跨服消息。
2. Requester GUI 的开屏额外数据过去只发送频率和库存目录，地址、回程地址、接收港组、远仓网络上下文依赖客户端 ItemStack/BlockEntity 镜像。现在菜单开屏 payload 直接携带服务端权威配置快照。
3. 便携终端在无槽位的自定义菜单内修改 ItemStack 组件时，客户端副本可能继续保持旧值。RequesterScreen 在存在开屏快照时不再回退读取客户端旧 ItemStack；服务端修改终端配置后也显式标记背包已改变。
4. 清空接收港地址仍允许在未加入正式远仓网络时执行，避免旧 Legacy 地址无法清理；创建/选择新的地址仍要求正式远仓网络。

回归覆盖：

- 单人 `StockScanner` 发布的本地 Create 网络必须带稳定 `RemoteNetworkId`；
- 请求台开屏 payload 必须保留服务端的网络、远仓网络、两个地址和接收港组；
- 便携终端清除 `333` 后，服务端 ItemStack 与下一次开屏 payload 都不得恢复旧地址。

## 2026-09-20：单人正式网络创建被旧本地身份 / Legacy 卡住

- 该阶段曾引入固定本地节点哨兵，后续已撤销。正式实现由 Transerver 的持久化 NodeIdentity 统一提供节点 UUID；隐藏 Legacy 网络仅保留旧存档迁移语义。
- 网络管理动作现在先主动执行一次本地 Create 网络扫描，并按当前频率选择服务端 live local `RemoteNetworkId`；便携终端/请求台里保存的旧 node/world 身份不能再覆盖 live local 目录。
- `MenuSync.resolve()` 对本地网络改为 live local row 权威；旧版本终端即使保存了旧 nodeId，也会迁移到当前持久节点身份。
- 正式远仓网络 membership 会随本地 `RemoteNetworkId` 身份迁移，避免升级后已加入的仓库突然变成“未加入”。
- 显式保存为 Legacy membership 的旧数据现在允许直接迁入正式网络；`create()` / `attach()` 只有在成员已经属于另一个正式网络时才拒绝。
- RequesterScreen 的内部 carried receiving-group id 也改为优先使用服务端开屏快照，防止文本框已清空但旧客户端 ItemStack 仍偷偷保留 `333`。

## 2026-09-20：创建/加入网络仍被旧 Legacy 前置判断拦截

- `DistantNetworkJoinService.request()` 过去在 `attach()` 之前自行判断 `current != null && current != target`，因此显式 Legacy membership 会被提前拒绝，根本到不了已经支持 Legacy→正式网络迁移的 `attach()`。现已把 Legacy 从冲突 membership 中排除。
- CREATE 的异常捕获范围收紧到 `DistantNetworkDirectory.create()` 本身，避免“网络已创建，但后续 UI/终端刷新异常”被误报成创建失败。
- 若旧构建已留下“本人、本机 authority、同名”的孤立正式网络，CREATE 会恢复该网络并重新附加当前本地 Create warehouse，而不是永久卡在 duplicate-name。
- 创建真正失败时，界面会显示具体异常原因，并写入日志。
- JOIN 本地找不到加入码且没有 Transerver 时，改为明确提示“本存档没有这个加入码，无法查询其它节点”，不再笼统报发送失败。

## 2026-09-20：远仓网络页允许空名称 / 非完整加入码直接发送

- 实机确认 CREATE 收到空字符串并触发 `Distant Stock network name must not be blank`。此前客户端创建按钮在输入为空时仍可点击，服务端只能事后报错。
- `DistantNetworkScreen` 新增独立 `nameDraft` / `codeDraft`，输入不再只临时存在于 EditBox；`init()` / resize / 状态刷新后会恢复草稿。
- 创建按钮只有网络名称非空时启用；加入按钮只有加入码可以被规范化为完整 `XXXX-XXXX` 时启用。
- 按钮发送草稿状态，加入码发送前统一通过 `normalizeCode()` 标准化，不再把占位提示或残缺输入送到服务端。

## 2026-09-20：正式化节点身份、Create 权限与远仓 membership

- 删除 Distant Stock 中固定的本地节点哨兵；正式代码不再生成或依赖测试/占位 UUID。
- Transerver 将 NodeIdentity 生命周期与 transport runtime 拆开：启动服务器即加载/创建 `transerver/node-identity.properties`；Router/secret/transport 禁用或失败时 identity 仍可用。
- `TranserverBridge.localNodeUuid()` 只读取 Transerver 的正式持久身份；identity 尚未加载时，扫描/下单/管理动作明确暂缓或拒绝，不伪造本机节点。
- 同一张本地 Create 仓储网络的远仓 membership 只认 `DistantNetworkDirectory`；设备中保存的远仓 UUID降为缓存/显示信息，不能覆盖 live membership。
- 新放置的请求台没有 Create 或远仓网络上下文；发现本地仓库列表不等于自动绑定。
- 同一张 Create 仓储网络只能属于一张正式远仓网络；玩家不是 membership key，一个玩家可管理多张远仓网络。
- 本地 Create 网络首次绑定、加入/退出远仓网络使用 Create `mayAdministrate()`；操作本地绑定终端/请求台及下单使用 Create `mayInteract()`。远端成员访问继续由远仓 network membership 授权。
- 新增 `CreateNetworkAccess` 作为 Create 权限边界，避免各设备重复实现所有权/锁定语义。


## 2026-09-20：新远仓港误报“未配置 Transerver 节点”

- 实机提示：`无法绑定：本机尚未配置Transerver节点。请先使用/distantstock 完成服务器连接设置。`
- 根因：`DockItem.useOn()` 仍使用 `TranserverBridge.nodeId()`（transport runtime 身份）判断本机身份；在 transport 未挂接但持久 NodeIdentity 已存在时错误返回 null。
- 修复：远仓港预绑定改用 `TranserverBridge.localNodeUuid()`；节点身份与 transport 状态彻底分离。
- 新增真实交互回归：新远仓港物品右键有效 Create stock link，必须写入稳定节点 UUID 与 Create 频率。
- 设备绑定只要求 Create `mayInteract()`；只有把整张 Create 仓储网络加入/退出远仓网络才要求 `mayAdministrate()`。

## 2026-09-20：旧节点身份导致 Create 仓库已调谐但远仓不识别

- ES2 正式 NodeIdentity：`04657200-8644-4bff-a18f-5e2dd2c79c5c`，Transerver transport 可离线但 identity 正常可用。
- 旧存档可能仍保存同一 Create frequency 的旧 `RemoteNetworkId` membership，以及远仓网络旧 `ownerNode`。这会让港/终端看似绑定了 Create 频率，但网络页把它视为陌生仓库。
- `StockScanner` 现在对本地 Create 网络执行 canonicalization：同 frequency 且只有一个历史 scope 时，自动把 stale membership 迁移到当前稳定 `RemoteNetworkId`；若该成员承载本地网络 authority，同时迁移 `ownerNode`。
- 若同一 frequency 出现多个冲突 scope，不做猜测式迁移，避免静默串网。
- `JoinNetworkC2S` 与请求台绑定改为 Create `mayInteract()`；真正创建/加入/退出远仓网络仍使用 `mayAdministrate()`。
- `OrderService`、`RequesterData.formalDistantNetwork()` 对 stale local binding 统一按 live local frequency 规范化，旧港/旧请求台/旧终端无需逐台重绑。
- 新增 authority+membership 身份升级回归；既有旧 world-id 本地订单回归继续通过。
