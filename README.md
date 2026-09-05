# Create: Distant Stock

机械动力：远仓 — 跨服仓管，不合并物流网

[中文](README.md) · [English](README.en.md)

[![Release](https://img.shields.io/github/v/release/EVGA2048/DistantStock)](https://github.com/EVGA2048/DistantStock/releases)
[![License](https://img.shields.io/github/license/EVGA2048/DistantStock)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-blue)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-yellow)](https://modrinth.com/mod/create)

---

**Create: Distant Stock**（机械动力：远仓）是面向 NeoForge 1.21.1、依赖 Create 6 的跨服仓管附属。两边各装一份，管理员在配置里指定 `host` / `peer` 与地址；玩家只调谐频率、填写包裹地址，**方块里不填 IP**。版本号以 [`gradle.properties`](gradle.properties) 与 [GitHub Releases](https://github.com/EVGA2048/DistantStock/releases) 为准。

模组 id：`distantstock`。HTTP 默认 **18772**，头 `X-DistantStock-Token`。和 EtherLink（18771）、PeerLink（18770）、AllMusic（18765）错开。代码只认配置里的 `self.id`，**不要写死服名**。

## 目录

- [介绍](#介绍)
- [功能一览](#功能一览)
- [数据如何走](#数据如何走)
- [运行环境](#运行环境)
- [安装](#安装)
- [使用方法](#使用方法)
- [配置键](#配置键)
- [HTTP 口](#http-口)
- [常见误解与配置注意](#常见误解与配置注意)
- [构建](#构建)
- [适用范围与参与](#适用范围与参与)
- [许可证](#许可证)

---

## 介绍

Create 的物流网（Stock Link / 打包机 / 青蛙港）只在**本 JVM** 里算。两台独立服不能共用一张网，也不该把散件拆开再寄。远仓只做一件事：把未拆封的 `PackageItem` 连同地址 hop 到对面，让对面按地址还原包裹。

请先明确它**不会**做的事情，以免按实时管道、中心服或无人机物流来理解：

- **没有第三台中心服。** `host` 只是配置里多听一个口的那一端；`peer` 连出去。两边对等拉状态。
- **客户端零 HTTP。** 请求器、仪表、监视器只跟本服说话（菜单 / 方块实体 / S2C）。本服 io 线程去拉对面，结果进缓存。
- **不合并两张 Stock Link。** 各服各算物流；跨服只寄未拆的包裹 NBT。
- **不拆散件再寄。** 地址写在包裹上，拆开会丢。
- **不是 Create: Mobile Packages（无人机物流）。** 不抄蜂、不把请求器放到地上。便携物品只是仓管竖窗；跨服 hop 是链接器。
- **主线程禁止 HTTP，也禁止为等回包而 `callSyncMethod`。** 只写快照、入队，下一 tick 再生成物品。

界面跟 Create 仓管同一套 `stock_keeper` 贴图（HEADER / BODY / FOOTER）。标题写远仓，不另画现代仪表盘。

## 功能一览

| 中文 | 英文 | id | 作用 |
|------|------|-----|------|
| 便携式远仓请求器 | Portable Distant Requester | `requester` | 手持 / Curios `body`；对着 Stock Link 抄频率；**H** 打开 |
| 远仓链接器 | Distant Linker | `linker` | 出货：调谐 freq，槽里的包裹入队寄出；收货：只写地址，按地址还原 |
| 远仓仪表 | Distant Gauge | `gauge` | 墙上固定请求器，和便携同一套 GUI |
| 远仓监视器 | Distant Monitor | `monitor` | 贴墙；右键 Dashboard（本端 / 对端 TPS + 链路压力） |

三块方块均实现 Create 的工程师护目镜（`IHaveGoggleInformation`）。蹲下多两行细节。**永远不显示** `peer.host`、端口或 token。

同 JVM 已有这张网：本地 `broadcastPackageRequest`。否则 `POST /order`，由对面打包后再 `POST /package` 寄回。

## 数据如何走

```
玩家 → 本服菜单 / BE
         ↓ 主线程：入队、扫仓、打包裹
本服缓存 ← io 线程 HTTP → 对端 LinkServer（18772）
```

| 组件 | 谁写 | 谁读 |
|------|------|------|
| `StockCache` | 主线程扫 `LogisticsManager`；io 线程拉 `/stock` | GUI、`GET /stock` |
| `LinkSnapshot` | 主线程写本端 TPS / 队列；io 线程写对端 RTT | 监视器 S2C、护目镜、`GET /status` |
| 订单 / 包裹队列 | HTTP 入队或玩家下单 | 下一主线程 tick 打包或还原 |

玩家在方块里**不填 IP**。管理员只在两边的 `distantstock-common.toml` 里配对。

## 运行环境

每一台加入互通的服务器均需要：

- **Java 21**
- **NeoForge 1.21.1**（Arclight / Youer 等混合端亦按此模组加载）
- **Create 6.0.6–6.0.10**（以 6.0.10 验证）

可选：

- **Curios**：便携式远仓请求器可进 `body` 槽；不装也能拿手持。

两边必须能互相访问 **18772**（或你改过的 `self.bind` / `peer.port`）。公网请走隧道或内网，不要把 token 留空。

## 安装

1. 将 `Create-Distant-Stock-<版本>+mc1.21.1.jar` 放入两边的 `mods/`，与 Create 一起启动一次。
2. 编辑 `config/distantstock-common.toml`：
   - 一边 `self.id = "host"`，`self.bind = "0.0.0.0:18772"`。
   - 另一边 `self.id = "peer"`，填写 `peer.host` / `peer.port` 指向 host。
   - host 若也填了 `peer.host`，同样会拉对面状态（互为备份看仪表）。
   - **`token` 两边必须相同。** 正式服用随机长串；空 token 仅开发用。
3. 重启或等配置重载后再测 `/status`。新版本会补全缺失键，不会改写你已经填写的值。

## 使用方法

### 便携式远仓请求器

1. 创造栏或合成取得（去皮诡异木外观）。
2. 去**有仓库的那台服**，对着 **Stock Link** 右键抄频率。
3. 右键空气打开，或按 **H**（饰品栏里也能开）。
4. 搜索、点进购物车、填包裹地址、下单。

`debug.demoStock=true` 时，缓存为空会显示演示物品。正式服请保持默认 `false`。

### 远仓链接器

- 用已调谐的便携请求器**右键**：抄频率，进入**出货**。漏斗 / 皮带把打包好的包裹送进槽，链接器入队寄出。
- **潜行** + 便携请求器右键：只抄地址、清频率，进入**收货**。地址空则按 `*` 全收。满了会对端重试。
- 扳手或空手右键：看当前模式。不另开 GUI。

出货端应放在打包机 / 皮带能喂到的位置，且区块保持加载。缺货或网未加载时护目镜写「缺货 / 未加载」，不会预扣。

### 远仓仪表

贴墙。用已调谐的便携请求器右键抄频率；空手或其它物品右键打开与便携同一套仓管竖窗。

### 远仓监视器

贴墙。右键打开一张 Create 风 Dashboard：本端 / 对端 TPS、MSPT；链路压力（RTT、失败次数、订单与包裹积压、在途）。对端离线写 **LINK DOWN**。监视器不下单、不调频。

## 配置键

`config/distantstock-common.toml`：

| 键 | 含义 |
|---|---|
| `self.id` | 本端角色，通常 `host` 或 `peer`；监视器 / 护目镜显示此字，不是服名 |
| `self.bind` | 监听地址，默认 `0.0.0.0:18772` |
| `peer.host` | 对端 IP 或主机名；空 = 不主动连出去 |
| `peer.port` | 对端端口，默认 `18772` |
| `token` | 共享密钥，两边相同；空 = 不校验（仅开发） |
| `debug.demoStock` | 缓存为空时显示假目录；正式服保持 `false` |

玩家在方块或 GUI 里看不到上述主机与 token。

## HTTP 口

均需头 `X-DistantStock-Token`（token 非空时）。仅本服 io 线程访问，客户端不连。

| 方法 | 路径 | 作用 |
|------|------|------|
| `GET` | `/stock?freq=` | 只读库存缓存 |
| `GET` | `/status` | 本端 TPS、MSPT、队列深度、`self.id` |
| `POST` | `/order` | 入队 `{freq, address, items[]}`；主线程打包 |
| `POST` | `/package` | 入队包裹 NBT；主线程按地址还原 |

队列满返回 `503`，对端将重试。

## 常见误解与配置注意

1. **`token` 两边必须相同，正式服不要留空。** 留空则任意能打到端口的人都可以下单、寄包裹。
2. **`peer.host` 必须是对端服务器能够访问的地址。** 对端若在另一台机器，请勿写 `127.0.0.1`。写错后监视器显示 LINK DOWN，下单会失败或只在本服排队。
3. **两台服不要共用一张 Stock Link。** 频率可以抄过去，但物流表各算各的。跨服只认包裹。
4. **出货链接器必须加载、且能吃到打包机吐出的包裹。** 只下单、没有链接器收箱，对面收不到货。
5. **收货链接器地址与下单地址要对得上。** 不符会计入护目镜「拒收」；空地址为 `*`。
6. **主线程不会等 HTTP。** 下单成功只表示已入队。缺货、区块未加载、对端队列满，要看护目镜或监视器。
7. **`debug.demoStock` 不是真库存。** 对着演示列表下单不会从仓库扣东西。
8. **令牌与对端地址不要写入公开仓库或截图。** 配置在服务器本地。

## 构建

JDK 21。编译期 `compileOnly` 本地 Create 6.0.10 jar（见 `build.gradle`）；运行时由游戏加载 Create。

```bash
./gradlew jar
# → build/Create-Distant-Stock-<版本>+mc1.21.1.jar
```

请只部署当前版本这一份，不要和旧的 `Create-Distant-Stock-*.jar` 叠放。

## 适用范围与参与

本模组建议用于你充分信任、能够共同维护端口与 token 的两台服务器。互通一旦建立，库存目录与未拆包裹便会在两端之间流动；不要把 18772 暴露给不可信的网络。

目前仍在测试，功能与配置仍可能调整。缺陷请通过 [Issue](https://github.com/EVGA2048/DistantStock/issues) 反馈；改进亦欢迎 [Pull Request](https://github.com/EVGA2048/DistantStock/pulls)。

## 许可证

本项目采用 [MIT License](LICENSE)。

便携式远仓请求器的物品模型布局改编自 [Create: Mobile Packages](https://github.com/tom5454/Create-Mobile-Packages)（**MIT**，Tim Heidler），木材重映射为去皮诡异木；天线 UV 保持原作。Create 仓管 GUI 贴图在运行时读取 `create` 命名空间，不打进本 jar。
