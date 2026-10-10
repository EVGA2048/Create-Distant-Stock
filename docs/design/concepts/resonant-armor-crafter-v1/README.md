# 谐振套在动力合成器上的摆法

四件套都是 **7 宽 × 5 高** 的 `create:mechanical_crafting`。这比工作台大、也比 Create 自己
发布的任何机械合成配方都大（它自带的最大是 5×5），所以需要 35 台动力合成器摆成阵列。

**横向摆**：长轴横着，所以整排只有 5 格高，不用搭脚手架。四张都是**左右对称**的，核心在中轴那一列。

`crafter_layout.png` 是从**实装配方 JSON 直接读出来**渲染的，不是照着抄的——配方改了这张图
就会跟着变，不会出现"图上是这样、游戏里不认"的情况。物品图标来自三个地方：我们自己的
`src/main/resources`、Create 的 jar、原版客户端 jar。重跑：

```
python3 docs/design/concepts/resonant-armor-crafter-v1/build_art.py
```

## 共同的骨架

四张图不是四套独立设计，共用一套读法：

- **外框 = 谐振坚固板**：整套里最重的零件（七步序列组装），所以它铺边
- **正中 = 下界合金件**：每件一个，四张图里都只出现一次，永远在 (3,2)
- **四角与上下 = 塔机壳**：需要一座在转的远塔才做得出来，是这套甲唯一和塔绑定的零件
- **棱线 = 以太机构**：夹着中心那一列/行
- **磨制以太石英**点缀在边角

各件的差异全在中层：头盔嵌玻璃与电子管，胸甲塞鞘翅和两个流体储罐，护腿铺铁板，靴子铺铜板。

（`create:mechanical_crafting` 的上限是 9×9——Create 在 `AllRecipeTypes.register` 里调
`ShapedRecipePattern.setCraftingSize(9, 9)` 把原版的 3×3 顶上去了。5×7 实测能加载，
`EtherCasingRecipeGameTests` 守着这一条。）
