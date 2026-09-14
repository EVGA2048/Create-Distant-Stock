# Create: Distant Stock

跨服仓储与包裹运输附属，让 Create 的仓储网络能够把订单和未拆封包裹送到另一台 Minecraft 服务器。

[English](README.en.md) · [问题反馈](https://github.com/EVGA2048/Create-Distant-Stock/issues) · [版本发布](https://github.com/EVGA2048/Create-Distant-Stock/releases)

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1-blue)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.x-orange)](https://modrinth.com/mod/create)
[![License](https://img.shields.io/github/license/EVGA2048/Create-Distant-Stock)](LICENSE)

<p align="center">
  <img src="docs/preview/machines-0.3.7.png" alt="远仓机器与方块预览" width="960">
</p>

<p align="center">
  <img src="docs/preview/indicator-lamps-0.3.7.png" alt="远仓独立信号灯预览" width="1100">
</p>

> 图片使用当前资源文件中的模型 JSON 与 PNG 离线渲染，米色背景仅用于展示，不是游戏截图。

## 远仓是什么？

**机械动力：远仓（Create: Distant Stock）** 是一个 NeoForge 附属模组。它不把多个 Create 仓储网络强行合并，而是通过远仓港和远仓包裹，在服务器之间传递订单结果与物品。

每台服务器都拥有自己的 Create 仓储网络。远仓只负责跨服边界：

```text
Create 仓储网络 A → 远仓打包机 → 远仓港 )) 可靠链路 (( 远仓港 → 本地物流 → Create 仓储网络 B
```

模组的目标是让“仓库服 + 多台生存服”成为可维护的物流系统，同时在断线、重启、目标拒收或模组不一致时优先保住物品。

## 当前包含的设备

### 远仓港 `distantstock:dock`

跨服物流的核心方块。一个港可以设置为出货、收货或双向模式，并支持优先级。它有独立的待发、到货和回退缓存：

- 待发包裹从上方或侧面的 Create 物流进入；
- 到货包裹从港的输出侧取出；
- 无法投递的包裹进入底部回退面，而不是被悄悄销毁；
- 绿、青、橙、红等灯态用于表示待机、发送、回退堵塞和故障；
- 用工程师护目镜查看节点、港组、缓存和最近错误；
- 破坏方块时会弹出各类缓存中的物品。

港组使用稳定标识和排序选择，允许一个港组拥有多个接收港，也允许一张本地仓储网络连接多个远仓港。

### 远仓打包机 `distantstock:remote_packager`

Create 打包机的远仓变体，保留打包机式结构和包裹动画，负责生成远仓包裹。它应连接在本地 Create 物流之后，再连接到远仓港。

### 远仓包裹 `distantstock:remote_package`

包含 Create 包裹内容和远程路线的物品。路线使用节点、世界、维度和接收港组等稳定标识，不把 IP、域名或 Router 地址写进物品。

### 远仓请求台 `distantstock:gauge`

落地式请求设备，用于打开仓储请求界面并配置来源网络、目标地址和接收港组。界面风格沿用 Create 的仓储界面。

### 远仓请求器 `distantstock:requester`

便携式请求器，可在不站在请求台旁边时浏览已加入的远程网络并提交订单。Curios 支持为可选依赖；不安装 Curios 时仍可作为普通手持物品使用。

### 远仓监视器 `distantstock:monitor`

贴墙安装的状态显示器，用于查看本端和远端的 TPS、链路状态、队列以及包裹概览。打开界面和周期刷新使用不同的数据包，关闭后不会因为刷新而重新打开。

### 信号灯与信号面板

模组提供五种安山独立信号灯：青、橙、红、绿、白，以及黄铜信号灯。它们可以作为独立墙面灯放置，也可以装入 Create 风格的四分格信号面板。灯格能够读取红石或连接的工厂仪表信号，并显示熄灭与点亮状态。

### 说明书与 Ponder

创造栏中提供远仓说明书。模组还包含出货、收货、状态和调谐相关的 Ponder 场景，用于说明基本结构和物流方向。

## 物品安全设计

远仓不是简单的 HTTP “发一个 NBT 就算成功”。当前实现包含以下保护：

- Transerver 负责节点身份、持久消息、重试、回执和去重；
- 来源服在交出包裹前使用持久托管记录；
- 目标服以 `parcelId` 做幂等登记，重复消息不会重复生成物品；
- 目标港满、区块未加载或暂时不可用时，包裹进入重试流程；
- 目标缺少物品或数据组件时拒收，并把包裹交给退件流程；
- 目标明确报告缺失内容时，来源可以剔除缺失条目后限次重发；
- 原港消失或无法加载时，包裹进入服务器退件箱；
- 无法解码的内容进入隔离库，保留原始数据与 SHA-256，等待管理员处理；
- 交接遵循“先写入新的唯一保管方，再删除旧记录”，避免先删后丢。

管理员命令：

```text
/distantstock status
/distantstock returns list
/distantstock returns restore <id>
/distantstock returns give <id>
/distantstock returns export <id>
/distantstock quarantine list
/distantstock quarantine give <id>
/distantstock quarantine export <id>
/distantstock quarantine discard <id>
```

## 安装要求

当前开发目标：

- Minecraft 1.21.1
- NeoForge 21.1.219 或兼容的 21.1 版本
- Create 6.0.x（当前以 6.0.10 验证）
- Transerver 0.1.x（必需前置）
- Java 21
- Curios 9.x（可选）

每一台参与互通的服务器都需要安装 Distant Stock、Transerver、Create 以及对应依赖。客户端也要安装 Distant Stock 和 Create，才能显示方块、界面和 Ponder。

把以下 JAR 放入服务器或客户端的 `mods/`：

```text
Create-Distant-Stock-<version>+mc1.21.1.jar
Transerver-<version>.jar
create-1.21.1-<version>.jar
```

不要同时放入旧版本的 Distant Stock。升级前先完全关闭 Minecraft，再替换 JAR。

## 基本配置

服务器首次启动后会生成 `config/distantstock-common.toml`。当前配置核心是：

- `self.id`：本端稳定节点 ID；每台服务器必须不同；
- `self.bind`：Transerver 监听地址；
- `peers`：可达节点列表；
- `token`：节点之间共享的认证令牌；正式服建议使用随机长字符串；
- `giveManual`：进入世界时是否赠送说明书；
- `debug.demoStock`：是否显示演示库存，正式服应关闭。

节点和包裹只保存稳定 ID。主机、端口和认证令牌属于服务器配置，不应写入公开仓库、截图或分享给无关人员。

## 使用流程

1. 在目标服务器建立并启用 Create 仓储网络。
2. 将远仓打包机接到本地仓储网络的打包物流上。
3. 将远仓打包机与远仓港连接，设置港的模式、优先级和默认路线。
4. 在另一台服务器放置收货港，并配置相同的接收港组。
5. 使用请求器或请求台选择来源网络、物品、数量、本地地址和接收港组。
6. 订单被目标仓库处理后，远仓包裹经过可靠链路送到收货港。
7. 收货港通过漏斗、溜槽、皮带或其他 Create 物流把包裹送入本地网络。

普通包裹如果没有明确路线，不会被随机发送，而是保留在发送港并显示缺少目的地。收货港组没有空间时，包裹也不会投到错误的港。

## 当前状态

这是一个仍在开发中的测试版本。核心代码和安全护栏已经覆盖：

- 稳定节点、世界、网络和港组路由；
- Transerver 可靠频道与版本化协议；
- 远程库存查询和订单请求；
- 包裹托管、回执、幂等与重复消息处理；
- 目标缺模组时的清单检查、退件、隔离和限次剔除重发；
- 远仓港、打包机、请求器、监视器和信号灯的客户端资源。

仍未完成或尚未充分验证的部分：

- 两台真实独立服务器上的完整下单闭环；
- Router 断线、重启、区块卸载、目标港满等故障矩阵；
- Multiverse 多世界生命周期与权限系统；
- 港组调谐工具、地址牌和请求器收货港组交互的进一步完善；
- 互通塔、以太流体和资源消耗玩法。

请把当前版本视为开发测试版，不要直接用于唯一存档或无人值守的生产服务器。测试时优先使用干净世界，并保留服务器备份。

## 开发与验证

需要 JDK 21。仓库提供资源检查和协议检查：

```bash
./gradlew clean build
./gradlew verifyWireCodec verifyParcelOwnership verifyClientAssets
```

`verifyClientAssets` 会检查注册方块、blockstate、模型父项、贴图引用、灯状态和模型几何，避免紫黑方块问题只在游戏里才被发现。

生成 README 展示图：

```bash
python3 scripts/render_readme_showcase.py
```

展示图来自 `src/main/resources/assets/distantstock` 的正式资源，不代表所有未来状态都已经实现。

## 许可与致谢

本项目使用 [MIT License](LICENSE)。

部分便携请求器物品模型布局改编自 [Create: Mobile Packages](https://github.com/tom5454/Create-Mobile-Packages)，遵循其 MIT 许可证。Create 的仓储界面资源在运行时使用 Create 命名空间，不打包复制到本模组。
