# 机械动力：远仓（Create: Distant Stock）

机械动力：远仓是适用于 Minecraft 1.21.1 NeoForge 的机械动力附属模组，提供跨服务器的仓储访问、订单请求与包裹运输功能。

玩家可以通过便携式远仓终端或远仓请求台浏览成员仓库并下单。来源服务器的 Create 仓储网络处理订单，由远仓打包机生成包裹，再经远仓港送到目标服务器，交给接收端的本地物流继续运输。

## 主要内容

- **跨服仓储与运输**：远仓终端、请求台、打包机和远仓港组成跨服物流线路；同一接收港地址可包含多台港。
- **自动化请求**：远仓仪表根据本地库存向来源仓库补货；远仓红石请求器通过红石信号触发订单。
- **互通塔**：由底座、耦合器和以太谐振器组成的多方块结构。塔使用旋转动力，等级影响设备数量、覆盖范围和可选择的区块加载范围；默认发送包裹消耗以太凝液。
- **工厂监控**：远仓监视器显示链路和队列信息；远仓日志台记录事件并打印小票；信号面板、工况灯与声光报警器用于显示工厂状态。
- **网络成员管理**：使用加入码建立远仓网络，通过成员仓库和接收港地址组织物流，同时保留 Create 本地仓库的权限规则。

模组包含中文、英文界面和 Ponder 教程。跨服运输保留来源仓库的真实打包过程，各 Create 仓储网络分别运行。

## 运行环境

Minecraft 1.21.1，NeoForge，Java 21。必需前置为 Create 6.0.x 与 Transerver 0.1.x，当前开发基准为 NeoForge 21.1.231、Create 6.0.10。

按当前模组依赖声明，客户端及每台参与互通的服务器均需安装远仓、Create、Transerver 及相关依赖。跨服使用前，管理员需要配置 Transerver Router 和各服务器节点。

Curios、Create: Deployer、Extra Gauges 与 Create: FluidLogistics 为可选集成。

## 入门流程

配置服务器连接与本地 Create 物流，搭建并驱动互通塔，给远仓港绑定本地 Create 网络。随后使用终端创建或加入远仓网络，通过港口将仓库登记为成员，配置接收港地址与发送方向，即可提交跨服订单。

[完整中文教程](https://github.com/EVGA2048/Create-Distant-Stock/blob/HEAD/docs/wiki/快速开始.md) · [设备说明](https://github.com/EVGA2048/Create-Distant-Stock/blob/HEAD/docs/wiki/设备.md) · [源码与下载](https://github.com/EVGA2048/Create-Distant-Stock)

## 致谢

感谢 **MUL、KNaMg_Rana、w4yw、BSGM** 在项目前期开发中提供的反馈与建议。

部分便携终端模型布局改编自 Tim Heidler 的 Create: Mobile Packages，遵循其 MIT 许可证。本项目使用 MIT 许可证。
