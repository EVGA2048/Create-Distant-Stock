# 远仓开发踩坑集（茶话会素材）

按「有多反直觉」排序，不是按时间。每条都给了具体位置，方便照着复现。

---

## 一、Create 面板的空格子没有碰撞箱

**现象**：在远仓面板上放第二个仪表时，什么都放不上去；放原版仪表反而会把整块板子顶掉。

**原因**在 `FactoryPanelBlockEntity.getShape()`：

```java
this.lastShape = Shapes.empty();
for (FactoryPanelBehaviour behaviour : this.panels.values()) {
    if (behaviour.isActive()) {          // ← 只有已占用的格子
        this.lastShape = Shapes.or(this.lastShape, Shapes.create(bb));
    }
}
```

空格子**没有形状**，所以准星直接穿过面板打到后面的墙上。事件型 handler 读 `event.getPos()` 拿到的是**墙**，不是面板，`instanceof FactoryPanelBlock` 当场返回。

接着原版路径接手，而 `BlockPlaceContext.getClickedPos()` 返回的是 `relativePos`（= 面板位置），于是走 `canBeReplaced`：

- 手持**远仓仪表/信号灯** → `AllBlocks.FACTORY_GAUGE.isIn(stack)` 为 false → 不可替换 → 放置失败 → **什么都不发生**
- 手持**原版工厂仪表** → 为 true 且格子空闲 → **整块面板被替换成一块新仪表** → 远仓仪表全部消失

**为什么 Create 自己能放满 4 格**：恰恰*因为*空格子没碰撞箱 —— 射线打到墙，原版通过 `relativePos` 找回面板并调 `addPanel`。我们用错了坐标。

**修法**（`SignalLampPanelItem.panelUnder`）：按 `BlockPlaceContext` 的 relative 语义把点击解析回面板。

```java
public static BlockPos panelUnder(Level level, BlockPos hitPos, Direction hitFace) {
    if (level.getBlockState(hitPos).getBlock() instanceof FactoryPanelBlock) return hitPos;
    BlockPos mounted = hitPos.relative(hitFace);
    return level.getBlockState(mounted).getBlock() instanceof FactoryPanelBlock ? mounted : null;
}
```

---

## 二、测试建模不够真实，等于没测

上面那个 bug **我的 gametest 全绿**。因为我是**直接算好坐标调 handler** 的：

```java
GaugePlacementEvents.install(new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit));
```

跳过了**射线检测**这一层。而真实游戏里，玩家点击产生的 `hitResult` 正是问题的来源。

**修法**：让测试模拟真实射线 —— 点击位置报在**面板后面的墙上**，而不是面板上：

```java
var hit = new BlockHitResult(new Vec3(local.x, local.y, wall.getZ()),
        Direction.NORTH, wall, false);   // ← 墙，不是面板
```

**教训**：测试如果绕过了某个真实环节，就必须显式把这个环节补回来。「直接调 handler」省掉的那一步，往往就是 bug 所在。

配套做法：**先退回修复，确认测试真的会红**。上面这条我这么验过 —— 退掉 `panelUnder` 后两个新测试立刻失败。没有这一步，你无法区分「测试通过」和「测试空转」。

---

## 三、图集配置的命名空间（流体紫黑）

**现象**：桶放地上、储罐里、管道里全是紫黑缺失贴图。

两个独立原因叠在一起：

**1) 空模型不收集材质。** 流体方块模型是空的（液面由流体渲染器绘制），而 MC 只从**模型元素的面**上收集材质 —— 除了 `particle`，still/flow 根本没被收集。

**2) 图集配置的查找路径。** `SpriteSourceList`：

```java
ResourceLocation file = ATLAS_INFO_CONVERTER.idToFile(atlasId);
for (Resource resource : resourceManager.getResourceStack(file)) {
    list.addAll(SpriteSources.FILE_CODEC.parse(dynamic).getOrThrow());
}
```

`idToFile` 把 `minecraft:blocks` 映射到 **`minecraft:atlases/blocks.json`** —— 用**图集自己的命名空间**。放在 `assets/distantstock/atlases/blocks.json` 等于建了个**没人用的 `distantstock:blocks` 图集**。

顺带一个好消息：这里是 `getResourceStack` + `addAll`，即**多模组的同名文件合并**而不是覆盖，所以不用担心和 Create 冲突。

**教训**：这类问题的正确检查方式不是「文件存在吗」，而是**查运行时状态**。所以我在客户端 smoke 测试里加了：

```java
var missing = atlas.getSprite(MissingTextureAtlasSprite.getLocation()).contents().name();
if (atlas.getSprite(name).contents().name().equals(missing)) {
    throw new AssertionError("Fluid sprite was not stitched into the block atlas: " + name);
}
```

---

## 四、重写渲染器时不调 super 会静默丢东西

**现象**：远仓仪表上的过滤物品**完全看不见**（原版仪表正常）。

`SmartBlockEntityRenderer.renderSafe` 干了这些：

```java
protected void renderSafe(T blockEntity, ...) {
    FilteringRenderer.renderOnBlockEntity(blockEntity, ...);   // 过滤物品
    LinkRenderer.renderOnBlockEntity(blockEntity, ...);        // 红石链接覆盖层
}
```

我为远仓仪表写自定义面板贴图时，重写了 `renderSafe` 并**去掉了 `super.renderSafe()`**（因为 Create 的父类还会画它自己的灯泡贴图）—— 顺手把过滤物品和红石链接也删了。

**修法**：不调 `super`，但**显式调这两个渲染器**，并保持 Create 的分层顺序（外壳 → 过滤物品/链接 → 灯泡 → 连接线）。

**教训**：`super.renderSafe()` 不是「什么都不干」的空实现。要么保留它，要么逐条读清它做了什么再决定删哪一条。

---

## 五、别靠记忆推断框架行为

我一度推断「客户端 cancel `RightClickBlock` 会阻止交互包发出」，并准备据此改架构。读字节码后发现**完全相反**：

```java
this.startPrediction(this.minecraft.level, sequence -> {
    mutableobject.setValue(this.performUseItemOn(...));
    return new ServerboundUseItemOnPacket(hand, hitResult, sequence);   // ← 无条件构造并发送
});
```

`startPrediction` 里包是**无条件**发出的，`performUseItemOn` 提前 return 不影响它。

同一类误判还有一个：一度怀疑 Create 的动态面板模型会套到我们的方块上造成重复绘制。`ModelSwapper` 的 `getAllBlockStateModelLocations` 用的是**方块自己的注册名**构造 MRL，所以按方块注册，我们的方块拿不到 —— 假设被推翻。

**教训**：涉及混合、渲染、网络这三块时，**读字节码/反编译源码**，不要凭印象。这一轮我用 Vineflower 反编译了 Create、NeoForge、Extra Gauges 和 deployer，几乎是唯一可靠的定位手段。

---

## 六、你测的包，不一定是刚构建的包

**现象**：修了一整轮，用户反馈「所有问题都没解决」。

原因：HMCL 里两个实例的 `mods/` 目录不同 ——

| 实例 | 读哪个目录 |
|---|---|
| `1.21.1-DistantStock-Testfield` | `.minecraft/mods/`（该版本没有自己的 mods 子目录） |
| ES2 整合包 | `versions/ES2_Firmament_.../mods/` |

我一直在往整合包那个目录装，而测试端读的是另一个。

**教训**：装机前**先确认目标目录**，装机后**比对哈希**。`scripts/install_client.py` 已经会打印 SHA256，用 `shasum` 对一下构建产物即可。

另外交接文档记录过一次真实事故：**运行中覆盖 JAR** 会导致 JVM 类索引损坏 —— 已缓存旧 JAR 的类索引、文件却被新 JAR 覆盖，放置方块时 `NoClassDefFoundError` + 画面冻结。覆盖前务必确认游戏已完全退出。

---

## 七、测试环境的隐藏变量

两条都是「被测对象无辜，是环境在捣乱」：

**流体会扩散。** 我把「熔融紫水晶造成伤害」和「以太凝液不造成伤害」写进同一个测试场地，结果熔融液体流到了以太那边的实体脚下，测试红了。**测流体必须一测试一场地。**

**测试世界是白天。** 用僵尸当受伤目标，结果僵尸**日晒燃烧**，掉的血看起来像以太造成的。换成猪（不会日晒燃烧）后稳定。

**教训**：测试里出现的伤害/状态变化，先问「除了我测的东西，还有什么会造成它」。

---

## 八、API 名字记错了会浪费好几轮

1.21.1 里几个真实的坑点：

| 想用的 | 实际存在 |
|---|---|
| `FluidState.getBlockState()` | `FluidState.createLegacyBlock()` |
| `Fluid.entityInside(...)` 做流体伤害 | 1.21.1 没有这个钩子，伤害要挂在 **`LiquidBlock`** 上 |
| `BaseFlowingFluid.Source` 子类化 | 可以，但解决不了上面那条 |
| `new Pig(level)` | `new Pig(EntityType.PIG, level)` |

另外 `FluidState.AMOUNT_FULL`（=8）是现成常量，比硬编码 8 好 —— 流体的 `getAmount()` 是 `AMOUNT_FULL - level`，所以 250mB 对应 `LEVEL = 6`。

**教训**：`javap` 花十秒，猜错花半小时。写新 API 之前先列一遍签名。

---

## 九、静态方法不能被更弱权限隐藏

```java
// Create 的父类
public static void renderBulb(...)

// 我的子类 —— 编译错误：无法覆盖，尝试分配更弱的访问权限
static void renderBulb(...)
```

静态方法同名同签名、但可见性更低，Java 直接拒绝编译。**改名**（`renderRemoteBulb`）比调权限干净。

---

## 十、顺便：校验脚本只扫了一个文件

`verifyClientAssets` 原本只从 `ModBlocks.java` 里正则抓 `BLOCKS.register("...")`。我把流体方块注册在 `ModFluids.java`（流体和方块互相引用，放一起避免静态初始化死锁），结果那两个 blockstate **完全没被校验**。

修成扫描 `src/main/java` 下所有 java 文件后，方块数从 12 变 14 —— 缺口自己暴露出来了。

**教训**：**校验脚本的覆盖面本身也要被怀疑**。一个只覆盖了 80% 的检查，比没有检查更危险，因为它给你虚假的安全感。

---

## 十一、不在 `textures/block/` 下的贴图不会被自动拼进图集

方块的 `assets/minecraft/atlases/blocks.json` 里只有两条目录源：

```json
{"type": "directory", "source": "block", "prefix": "block/"},
{"type": "directory", "source": "item",  "prefix": "item/"}
```

也就是说，**只有 `textures/block/` 和 `textures/item/` 会被自动收集**。流体的贴图在 `textures/fluid/`，两条目录源都覆盖不到。NeoForge 自己在这个类的注释里写着答案：

```java
// IClientFluidTypeExtensions
// Add entries to assets/minecraft/atlases/blocks.json if your texture location
// is not already covered by the default atlas search locations.
```

拼进图集有两条路：**烘焙时被某个模型引用**，或者**被图集定义显式列出**。

注意文件必须放在 `assets/minecraft/atlases/`（**不是** `assets/distantstock/atlases/`）。图集名来自文件名，而方块图集的 id 硬编码在 `ModelManager.VANILLA_ATLASES` 里：

```java
TextureAtlas.LOCATION_BLOCKS, ResourceLocation.withDefaultNamespace("blocks")
```

`SpriteSourceList.load` 读的是 `minecraft:atlases/blocks.json`，然后用 `ResourceManager.getResourceStack()` 读**所有资源包**的同一路径并合并 —— 所以这是**追加**，不会覆盖原版或 Create 的条目。和 Create 给 `create:fluid/chocolate_still` 做的事情完全一样。

**未拼进图集不会报错**，只会画成紫黑格子。所以 `runClientSmoke` 里专门断言了这几个 sprite 真的在图集里 —— 那是唯一会把"贴图没进图集"变成失败而不是截图的检查。

**教训**：紫黑格子 = 图集里没有，不一定是文件不存在。先看路径在不在 `block/` 或 `item/` 下。

---

## 十二、Ponder 结构里的方块属性写错 = 方块变成空气

`StructureTemplate` 的调色板是用 `NbtUtils.readBlockState` 解析的，它对无法解析的状态**不抛异常**：

```java
return parseBlockState(blockGetter, tag)
        .resultOrPartial(LOGGER::error)
        .orElse(Blocks.AIR.defaultBlockState());
```

所以只要写了方块没有的属性（比如港改成 `status` 枚举之后，结构里还留着 `loaded` / `lit`），那一格就静默变成空气。四个思索场景里的远仓港因此**全部消失**，`tune` 更是整场只剩地板。

`scripts/gen_ponder_structures.py` 现在会在写文件前校验：把 `distantstock:` 开头的调色板条目和该方块的 blockstate 变体键（`facing=north,status=inactive` 这种）比对，属性名和取值都必须存在。

**教训**：结构的错误日志在 `latest.log` 里以 `error` 出现，不会崩游戏，也不会显示在界面上。方块状态定义一改，所有手写结构都要跟着改。

---

## 十三、连接纹理：图集的位序是画的人的，不是 Create 的

远仓机壳用 Create 的 CT 管线（`ConnectedTextureBehaviour` + `CTType` + `CTSpriteShiftEntry`），图集是一张 16×16 格的表，索引就是八邻域掩码。**位序照抄 Create 会错**：

| | 左 | 右 | 上 | 下 | 左上 | 右上 | 左下 | 右下 |
|---|---|---|---|---|---|---|---|---|
| 美术包 | 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 |
| Create 的 CROSS 类型 | 4 | 8 | 1 | 2 | 16 | 32 | 64 | 128 |

低四位整个对调：照 Create 写，每一格的**左右和上下会互换**，一堵墙沿一个轴接得对、沿另一个轴接错，看起来像美术画错了而不是映射写错了。

**离线可以核对**：从图集里把四个单位掩码的格子读出来比一比就行 ——

```python
base = 边缘像素(tile(0))
for bit in range(4):
    print(1 << bit, 变化最大的那条边(tile(1 << bit), base))
# 1 → left, 2 → right, 4 → top, 8 → bottom
```

角位同理：`tile(1|4)` 和 `tile(1|4|16)` 的差异只该落在左上角 3×3 区域。角位为真的格子永远不会单独出现（`buildContext` 里 `topLeft = up && left && 对角`），所以图集里那些「只亮了一个角」的格子是死格。

另外两条：

- `getSheetSize()` 是**每边的格数**（我们要的 16），不是像素数也不是总格数。返回 256 会让 `index % 256 == index`、`index / 256 == 0`，全部落到第一行。
- `CTModel` 只在 **quad 的 sprite 正好等于 `shift.getOriginal()`** 时才换 UV。模型里那个面必须引用原图，大图只做 target，否则不报错、就是没有连接效果。

**教训**：CT 全是静默失败。布局错了不报错，只是图案不对；交换没挂上也不报错，方块看起来完全正常，只是永远不连接 —— 所以 `runClientSmoke` 里专门断言了机壳的烘焙模型真的是 `CTModel`。

---

## 十四、交接包的网格是给「没有深度缓冲」的渲染器画的

美术交接包里的 `*_mesh.json` 是给画家算法的离线预览渲染器画的：它按列表顺序刷四边形，**没有深度测试**。在那个世界里，把一片铜质细节直接铺在底板**同一个平面**上是免费的 —— 后画的盖住先画的就行。

进游戏就是两层同朝向的面抢同一个像素（z-fighting）。交接包里这种「贴在同平面的细节」非常多：底座顶面是一整块 16×16 的板，上面直接铺了四条铜条和一个水晶口，全部在 y=16。

转换时加了一步去重：**同朝向、共面、投影相交**的两个面，后画的那个沿法线推出去 0.02 模型单位（约 0.0015 格，肉眼不可见）。反朝向的共面**不用管** —— 那是一块板的两面，背面剔除保证同一时刻只画一个。

跨方块的接缝要单独处理，单块模型的去重看不见邻居：

- 耦合器接缝处的横框和上下两块的机身正好同面 → 把横框的皮肤整体抬高一步，变成一圈微微凸起的箍
- 底座的水晶尖被耦合器的水晶柱整根罩住，六个面全部重合 → 把尖内缩一步藏进去

写盘前跑一次**共面重叠检查**（装配后的整塔 336 个面里不允许有两个同朝向的面共面且投影相交），是唯一能把这类错误变成失败而不是截图的办法。

**教训**：离线预览好看不等于进游戏好看。零厚度面、共面细节、半透明批次不写深度 —— 这三件事在预览里全都不存在。

## 十五、建模用的预览渲染器忽略 alpha，游戏不忽略

同一个交接包，同一个根因。离线预览把贴图直接合成到背景上，**完全不看 alpha**；游戏里的区块模型走 `cutout`，**alpha 低于 128 的像素直接丢弃**。

`crystal.png` 256 个像素全是半透明（92~185）—— 那是画的人用软边做光晕。`cutout` 下 220 个像素变成洞，塔的水晶柱会出来像蕾丝。

第二个更隐蔽：机壳的观察窗贴图中心是半透明的，但机壳模型是 `solid`，**`solid` 层直接无视 alpha**，窗会画成实心色块。**渲染层属于模型，不属于方块状态**，所以两个状态只能拆成两个模型，用方块状态去选。

**教训**：拿到美术资源先跑一遍 alpha 直方图，再决定每个材质该走哪个渲染层。判断规则：

| 层 | alpha 行为 | 适合 |
|---|---|---|
| `solid` | 无视 alpha | 不透明方块 |
| `cutout` | < 128 丢弃，其余不透明 | 有洞的形状（树叶、栏杆） |
| `translucent` | 混合、不写深度 | 玻璃、光柱、水 |

**不该修的别修**：`axis` / `frame` 这类贴图是 0/255 二值的，那些洞**是形状不是光晕**，强行填不透明会把轴和边框糊成方块。判断依据是 alpha 是不是只有 0 和 255。

## 十六、Create 的应力是按方块注册的，不是按方块状态

`BlockStressValues.IMPACTS` 是 `SimpleRegistry<Block, DoubleSupplier>`，键是**方块**。应力会随等级变的机器（比如越高越费应力的塔）没法用它表达，得覆写 `KineticBlockEntity.calculateStressApplied()`。

两件容易搞错的事：

- **没有 `addStress()` 这种东西**。应力是网络汇总时算的：`实际消耗 = calculateStressApplied() × |转速|`，所以「转速翻倍消耗翻倍」是框架行为，不是你要写的。
- **`CStress.setImpact` 对非 create 命名空间直接抛异常**（`Non-Create blocks cannot be added to Create's config.`）。那是 Create 给自己生成配置文件用的。

改完应力记得让网络重算（置 `networkDirty`），否则新等级要等下一次别的什么事件才会生效。

## 十七、区块加载要用 TicketController，不要用 setChunkForced

`ServerLevel.setChunkForced` 和 `ChunkMap.addRegionTicket` 都绕开 NeoForge 的 owner 记账：没有 controller 隔离（多个模组互相踩），也不会进 `validateTickets` 的清理回调。

**清理回调是这个 API 存在的主要理由**。方块拆了、存档残留了、区块卸载了 —— 只要 `validateTickets` 里没把票删干净，那些区块就**永久强加载**，服主的服务器慢慢被拖死，而且看不出是谁干的。

照 `create_power_loader` 的形状写：

- owner 用**方块坐标**（这样回调里能 `level.getBlockEntity(pos)` 反查），不要用 UUID
- 查不到方块实体 → `removeAllTickets(pos)`
- 查得到 → 删掉所有 non-ticking 票，把 ticking 票交给方块实体认领（不认领就会重复 force 或误卸）
- **世界加载后留宽限期**（它是 100 tick）：校验回调发生在世界加载早期，那时方块实体还没加载完，立刻删会让刚恢复的区块马上卸载
- 增删票**延后到服务器 tick 里做**，别在遍历或卸载过程中改
- 半径 r 覆盖 `(2r-1)²` 个区块，**r=1 只有中心那一格** —— 写成 `±r` 就是经典 off-by-one
- 做查找时**不要加载区块**，碰到没加载的跳过

**教训**：Create 本体里没有区块加载器，`create_power_loader` 是独立模组。别在 Create 的 jar 里找，找不到的。
