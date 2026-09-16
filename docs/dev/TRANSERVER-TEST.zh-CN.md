# 跨服真机测试跑本（本机两台服务器 + 一个 Router）

远仓唯一在单机测不了的东西就是跨服：它需要**两个真的服务端进程**和它们中间的 Router。
本机已经跑通过的部分（2026-09-16）：两台开发服务器起来、各自拿到稳定节点身份、
Distant Stock 的 bridge 挂上、Router 带两个节点监听。

## 已经起好的东西

| 东西 | 位置 | 说明 |
|---|---|---|
| Router | `build/transerver-router/router.properties` | 独立进程，`127.0.0.1:8765` |
| 节点 A | `run/`（`./gradlew runServer`） | `远仓A`，端口 25565 |
| 节点 B | `build/server-peer/`（`./gradlew -PwithPeerServer runServerPeer`） | `远仓B`，端口 25566 |
| 节点身份 | `<游戏目录>/transerver/node-identity.properties` | **稳定 UUID，要跟存档一起备份** |
| 节点配置 | `<游戏目录>/config/transerver-server.toml` | `enabled` / `nodeAlias` / `routerUrl` / `networkSecret` |

**改节点身份等于换一台服务器**：Router 配置里的 `nodes` 列的是这两个 UUID，
身份文件删掉重建就会换 UUID，Router 那边要同步改。

## 起（按顺序）

```bash
# 1. Router 先起，节点后起最省事（节点会自己重试，但先起 Router 日志更干净）
java -jar ~/Documents/git_repository/Transerver/build/libs/Transerver-0.1.0-SNAPSHOT.jar \
     build/transerver-router/router.properties

# 2. 两台服务器（两个终端）
./gradlew runServer
./gradlew -PwithPeerServer runServerPeer

# 3. 各自确认：服务器控制台
/transerver identity      # 别名 + 短码 + 完整 UUID
/transerver status        # Router 连通状态、队列深度、最近错误
/distantstock status      # 远仓自己看到的传输模式与队列
```

两条日志行说明挂上了：

```
Transerver node started: 远仓A (BWWRD1M5-JH08S0D3) via http://127.0.0.1:8765/
[DistantStock] transport.mode = 'transerver' | transerver=true legacy=false
```

## 测什么

单机 gametest 能证明到「订单被拒不会算在途」「未绑定不下单」为止——
**订单真的离开本机、落在对面**这一段只有这里能验。

1. **两端各搭一座塔 + 一个远仓港**，各建一个港组（潜行右键终端 → 建组）。
2. **互换配对码**——这一步就是跨服寻址的全部：
   - B 端组主：终端界面里把组名填进「目的地」框 → 展开下拉 → 点最后一行
     「＋ 生成配对码」（也可以 `/distantstock pair create <组名>`），聊天里得到 6 位码。
   - A 端玩家：把这个码**打进同一个目的地框并回车**（或 `/distantstock pair redeem <码>`）。
     几秒后聊天说「配对成功：目的地『X』已可用（来自 <节点短码>）」，
     下拉框里多出一行紫色的 `节点短码·组名`。
   - 反方向再来一次（A 发码、B 兑换），两台就互相认识了。
   - **应失败的几种**：输错一位 → 「没有服务器认领」；同一个码再兑一次 → 「对面拒绝了」；
     把本服的码在本服兑 → 「这个码是本服的，直接选组名就行」。
3. A 端：`/distantstock dock send-to <远端组名>`，或在下拉框里点那行紫色目的地再手持终端
   普通右键点港，把这个港指向 **B 端的那个组**。
4. A 端塞一个包裹进发送港，盯三处：
   - A 端 `/distantstock status` 的出站队列是否清空；
   - Router 的 `build/transerver-router/data/` 是否出现消息文件；
   - B 端的港是否吐出包裹。
5. **断网重试**：把 Router 杀掉，再塞一个包裹 → 消息应留在 A 的 outbox 里；
   把 Router 起回来 → 应该自动补投，且**不重复**（同 `messageId` 幂等）。
6. **拒收路径**：B 端把目标港拆了再发 → 包裹应进入回退/隔离，
   `/distantstock returns list` 与 `/distantstock quarantine list` 应能看到它。
7. **远端目的地当订单目的地**（新）：A 端在请求台上把目的地选成那行紫色远端组、
   向 **B 端的仓库网络**下单 → 路由目的地是**本机还是对面**由
   `TranserverOrderService.destinationNode` 决定：组是 B 自己的（且不是默认组）→ 货留在 B，
   落在 B 那个组的港里；其它情况 → 货寄回 A，落在 A 选的组里。
   这一条是本轮唯一没有单机测试覆盖的判定，重点看它。

## 已知的坑

- `server.properties` 里 `online-mode=false`（开发服），两台都是，别改。
- 两台服务器**同时**跑要留够内存；各开一个终端，日志别混在一起看。
- 跨服的组名在两边都要存在；只在一端建组，另一端下单会找不到目标。
- Transerver 的 `networkSecret` 两端与 Router 必须一致，**至少 32 字节**。

## 本机跑通到哪一步了（2026-09-16）

**通了**：Router 起来带三个节点、两台服务器各自生成稳定身份、`transerver` 节点启动、
Distant Stock 的 bridge 挂上（`transport.mode = 'transerver'`）、RCON 能发命令。

**没通**：节点报 `Transerver 未连接`，最近错误 `Transerver pump failed`。
A 的发件箱里卡着一条 `distantstock:v1.network.announce`（235 字节，`messages/outbox/`）。
Router 侧一切正常：`/v1/nodes` 无认证返回 401、带节点列表启动，说明 HTTP 面是活的。

**下次从这里接着查**：

1. `TranserverNode.runStage` 把 `IOException` 包成 `TranserverException("Transerver pump failed", cause)`
   **只留了顶层消息，没打堆栈**——所以 `status` 里看不到真正的原因。
   要看原因：把发件箱那条卡住的消息挪走（`mv run/transerver/messages/outbox/*.bin /tmp/`）
   再 `/transerver status`；如果这就好了，问题是那条 announce 本身。
2. 还不行就上探针：`build/transerver-router/probe-c.properties` 已经写好
   （节点 id `11111111-…`，Router 的 `nodes` 里也加了它），
   用 `TranserverProbeMain`（`build/libs/Transerver-*.jar`）跑一次，
   探针能通就说明传输层和 Router 没问题，是游戏节点这一侧的事。
3. `FileRouterTransport.knownNodes` / `RouteResolver` 是「未知目标直接拒」的，
   报错会是 `No route for node: ...` 而不是 `pump failed`——见到那句就去查 Router 的 `nodes` 列表。

**踩过的坑**：用 `ps | grep Transerver-0.1.0-SNAPSHOT.jar` 找 Router 进程会**连游戏服务器一起杀掉**
——那个 jar 也在游戏服务器的 classpath 里。按 `java -jar` 前缀找：
```bash
pgrep -f "java -jar.*Transerver"     # 只匹配 Router
```
