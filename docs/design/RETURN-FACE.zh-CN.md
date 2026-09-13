# 机械动力：远仓——远仓港回退面与不兼容物品剔除

> 决策日期：2026-09-10
> 状态：已实现（灯态、回退面、剔除链路、管理员出口），待多人多服实测
> 相关代码：`block/DockBlock.java`、`block/DockBlockEntity.java`、`block/DockStatus.java`、
> `link/PackageStripCodec.java`、`link/PackageStripService.java`、`link/ParcelEscrowPump.java`

## 1. 要解决的问题

跨服包裹会失败，失败以后物品必须回到**发货那台服务器的玩家自己手里**，不能集中到一个全服公共的地方。

最初设想是新增"退件信箱"方块并给包裹记录所有者。实测约束推翻了它：

- 远仓港绑定的是 Create 物流网络频率，本身不携带玩家身份；给港加 owner 会在团队共用工厂上出错；
- Create 的包裹在整条链路上是不透明整体，中转节点拆包并改写内容是新语义，必须有严格边界。

最终方案：**归属不落到玩家，落到"那个发货的港"**。退件从港自身的回退面回到玩家自己的物流里；只有港已经不存在时，才由服务器退件箱兜底（管理员可见）。

## 2. 灯态（唯一的常驻状态显示）

远仓港没有界面，2×2 工厂仪表灯就是全部状态输出。灯态由 `DockStatus` 单一方块状态驱动：

| 灯态 | 显示 | 触发条件 |
|---|---|---|
| `inactive` | 灰色熄灭 | 未接入远仓链路（链路 DOWN） |
| `standby` | 绿色常亮 | 已接入、空闲 |
| `sending` | 青色闪烁 | 正在播放 30 tick 发送动画 |
| `blocked` | 橙色闪烁 | 回退面有东西要交付，但下方没有容器、容器已满，或退件缓冲已满 |
| `fault` | 红色常亮 | 需要人工：托管记录无法解码、剔除无法定位、剔除重发超限 |

优先级：`fault` > `blocked` > `sending` > `inactive` > `standby`。

- 闪烁用两帧动画贴图实现（`gauge_bulb_cyan_blink.png` / `gauge_bulb_orange_blink.png` + `.mcmeta`，frametime 8），不产生方块状态刷新和网络包。
- 进入 `fault` 或 `blocked` 时在港位置播放一次 Create 的 `DENY` 提示音；退出由玩家交互（扳手或空手右键）或一次成功循环清除。
- 护目镜显示灯态、回退面占用、最近一次回退原因和故障原因；`/distantstock status` 继续显示托管与退件箱计数。

## 3. 回退面（底面）

- 底面是**第六个面的独立能力**：`ModCapabilities` 对 `Direction.DOWN` 注册 `bottomFace`，它只允许投入包裹，`extractItem` 永远返回空。所以底下的溜槽**不可能**把到货包裹或待发包裹抽走。
- 港自己把回退内容**单向推**进下方方块的物品能力（`Capabilities.ItemHandler.BLOCK`，`pos.below()`）；任意能接物品的方块都行：Create 溜槽、漏斗、箱子、皮带。
- 回退缓冲：9 格，只出不进，默认堆叠上限（因为要装被剔除的普通物品，而不只是包裹）。
- 推不出去时内容留在缓冲里，港进入 `blocked`，**绝不销毁**。
- 港的 `pullAdjacent` 跳过 `DOWN`，否则刚推出去的包裹会被自己抽回来形成循环。
- 破坏远仓港时 `playerWillDestroy` 会把到货、待发、回退三个缓存全部弹出（此前破坏港会直接吞掉里面的包裹，属于既有漏洞，一并修复）。

## 4. 剔除链路（目标缺模组）

链路两端的能力是不对称的，这是设计的出发点：

1. **目标服知道自己缺什么**：`PayloadManifest.missingRegistryEntries()` 在反序列化前就用自身注册表算出缺失的 `item:` / `component:` 条目。
2. **目标服拿不出实物**：缺模组的物品在目标服无法实例化（`PackageCodec.decode` 直接失败），所以"从港下面清理出来"只能发生在**有那个模组的服务器**，也就是来源服。
3. **Transerver 不传递原因**：`MessageHandler` 只能返回 `APPLIED` / `RETRY` / `REJECTED`，回执里的 `detail` 由 Transerver 自己填写。因此缺失清单走 DistantStock 自己的反向频道 `distantstock:v1.package.strip`（不改 Transerver 公共 API）。

流程：

```text
目标服：清单预检失败 → 发 package.strip（parcelId + 缺失条目）→ 返回 REJECTED
来源服：PackageStripService 把缺失清单挂到托管记录上并落盘
        ParcelEscrowPump 在原港已加载时拆包：
          包裹内容里"自身或嵌套条目命中缺失清单"的整件物品被取出
          取出的物品进港的回退缓冲 → 从底面推出到玩家的物流
          剩余内容写回 create:package_contents，重算清单与 SHA-256，记一次 strips，重新提交
```

约束与护栏：

- **粒度是整件**：只要某件物品（含其嵌套容器、收纳袋、弩的装填物，递归深度同清单上限）引用的条目命中缺失清单，就整件取出。不做"只摘掉缺失的数据组件"的半拆，避免物品语义变形。
- **零剔除不重发**：本次一件都没取出来时，说明清单没有覆盖到真正的拒收原因，直接整包从回退面退出，并置 `fault`。
- **重发上限**：`ParcelEscrowPump.STRIP_LIMIT = 3`，达到上限后整包退出 + 置 `fault`。这两条一起消除了"永久拒收被无限重发"的隐患。
- **重发是来源托管自己重发**：不把包裹塞回港的发送缓存，parcelId 不变、清单与哈希重算，`strips` 计数随托管记录持久化。
- **回执只看当前在途消息**：`resolveCompleted` 现在比对 `messageId`，忽略旧消息的回执，避免"同 parcelId 重发"被上一轮拒收覆盖状态。
- 原港不存在或未加载时，托管记录保留；宽限期（600 tick）后移交 `ParcelReturnInbox`，缺失清单一起保留，管理员可用命令处理。

## 5. 管理员出口

- `ParcelReturnInbox`（无主退件，港已消失）与 `ParcelQuarantine`（无法解码的记录）保留原职责，不再参与玩家正常路径。
- `/distantstock returns restore <id>` 现在把包裹放回**原港的回退面**（会明确提示回退面已满），`give` / `export` 不变。
- `/distantstock status` 增加回退箱与隔离库计数。

## 6. 尚未覆盖

1. **跨表崩溃窗口**：剔除与交付都是"先交付、后销账"（交付物先进港的回退缓冲或玩家容器，再删托管记录），而托管数据与区块数据是两份文件。极端崩溃窗口内可能重复交付一次（不会丢失）。要彻底关闭，需要一个随包裹走的幂等标记组件 + 交付前的存在性检查；被剔除的普通物品不适合带标记（会破坏堆叠），因此只对整包可行。
2. **剔除后包裹尺寸/外观**：`PackageItem.getWidth` 等是否随内容变化，需要在游戏里确认；清单与哈希已重算。
3. **专用思索场景**：现有 `export` / `status` 场景已用文字说明回退面与灯态；真正的"底面接溜槽 + 不接会橙闪"分镜需要新建 schematic，留待游戏内采集。
4. **多人多服实测**：拒收 → 剔除 → 重发 → 送达，以及原港被拆除后的管理员恢复，都还没有在真实服务器上跑过。
