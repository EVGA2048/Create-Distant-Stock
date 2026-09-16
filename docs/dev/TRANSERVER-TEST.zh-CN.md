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
2. A 端：`/distantstock dock send-to <组名>` 或手持终端对港普通右键，把它指向 **B 端的组**。
   跨服时组是**对面服务器上的组**，靠 `networkId` + 组 id 寻址，不是本地组。
3. A 端塞一个包裹进发送港，盯三处：
   - A 端 `/distantstock status` 的出站队列是否清空；
   - Router 的 `build/transerver-router/data/` 是否出现消息文件；
   - B 端的港是否吐出包裹。
4. **断网重试**：把 Router 杀掉，再塞一个包裹 → 消息应留在 A 的 outbox 里；
   把 Router 起回来 → 应该自动补投，且**不重复**（同 `messageId` 幂等）。
5. **拒收路径**：B 端把目标港拆了再发 → 包裹应进入回退/隔离，
   `/distantstock returns list` 与 `/distantstock quarantine list` 应能看到它。

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
