# Sable / 机械动力：航空学 兼容说明

> 状态：服务端功能已接入，客户端渲染仍需实测。适用于 Sable 2.0.5 / Create: Aeronautics 1.3.x。

## 做了什么

### 1. 覆盖判定改用全局坐标

Sable 把子关卡的方块存在 plot 坐标里；plot 坐标和船在世界里的位置相差很远。原来的覆盖判定直接比较
`BlockPos`，所以地面塔永远够不到船上的港，船上的塔也只会和 plot 里的设备互相看见。

现在 `TowerActivation.survey` 给每座塔、每台设备都算一个 `globalCenter`，由
`SableBridge.toGlobal` 投影到世界空间；`TowerSystem` 的合并、覆盖、最近塔选择都用这个点。
`TowerSystem` 仍然保持纯运算，旧的双参数构造器保留，普通（非 Sable）世界的行为不变。

覆盖按 **空间（space）** 分组：普通世界是一个空间，每个 Sable 子关卡按自己的 UUID 是另一个空间。
塔和设备只会在同一个空间里互相带载。

效果：

- 船上的塔覆盖同一艘船上的港；
- 船飞过地面工厂时，**不会**因为它的塔在转动而把地面设备判成"没塔"；
- 地面塔和船上设备之间不跨空间带载。跨空间带载没有启用：如果允许，一艘移动的船会让整片
  地面区域的设备状态跟着它变，代价比收益大。

### 2. 船上的塔用 Sable 票据保持加载

`TowerChunkLoader` 原来的 NeoForge `TicketController` 只按区块坐标工作。指向 plot 坐标会和 Sable
自己的 `PlotChunkHolder` 打架，不能这么做。

现在 `reconcile` 对每座塔先问 `SableBridge.keepLoaded`：

- 塔在普通世界里 → 返回 false，继续走原来的 NeoForge 票据；
- 塔在子关卡里 → 给该子关卡加一个 `SubLevelLoadingTicketType`（key 是塔的 plot 坐标），
  同时释放它可能残留的 NeoForge 票据，然后跳过区块强制加载。

票据按子关卡 UUID 存在 Sable 的 `SubLevelTicketsSavedData` 里，所以船在无人时也能被这张票拉回来。
票据类型的静态初始化放在模组构造阶段（`SableCompat.init()`），保证早于 Sable 读取存档票据。

### 3. 到货提示使用世界坐标

`TranserverPackageService` 给玩家发的到货坐标现在会先 `globalBlockPos`，不会再把 plot 坐标
（几万、几十万那种）直接显示给玩家。

## 已知边界

- **船上的设备要由同一艘船上的塔带载。** 地面塔不跨空间带载，也不负责把已经卸载的船拉回来；
  船离线时保持加载靠的是船上那座塔的 Sable 票据。要扩展成"地面塔给停泊的船加票"，需要另外
  设计跨空间带载和票据归属。
- **自定义 BER 还没有做 `renderPose` 适配。** 港的包裹渲染、塔核心渲染等可能在船上位置不对。
  这是纯客户端问题，不崩溃，但要实测后再补。
- **每塔设置仍按“维度 + 方块坐标”存。** 船被拆解/重新组装后 Sable 会分配新的 plot 坐标，
  塔的半径/加载/带载开关可能回到默认值；港的组 ID 在方块实体 NBT 里，会跟着船走。
- **Create contraption 不适用。** 机械轴承/活塞组装的移动结构会把方块实体封进 contraption，
  方块实体不 tick；航空学的飞船是 Sable sub-level，不受这条限制。

## 构建

`build.gradle` 从 `modpackMods` 里找 `sable-neoforge-1.21.1-2.0.5.jar`，并从中解出
`sable-companion-common` 到 `libs/`。如果构建机的整合包路径不同，把 Sable jar 放进 `libs/`
同名文件即可。Sable 是 `compileOnly`，运行时不强制依赖；没有 Sable 的整合包会走
`SableBridge` 的普通坐标实现。

## 实测清单

1. 在地面上建塔 + 港，确认原有行为不变；
2. 把塔和港建到船上，确认 `TowerActivation` 的护目镜/监视器显示“被塔带载”；
3. 船上收一件跨服包裹，确认落地提示的坐标是世界坐标；
4. 让所有玩家离开，等待船卸载/重新加载，确认票据把船拉回来且港重新注册；
5. 拆船、重新组装，确认塔和港仍在工作（设置可能需要重新调）；
6. 观察客户端渲染是否有位置偏差，记录需要补 `renderPose` 的渲染器。
