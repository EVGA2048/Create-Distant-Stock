package dev.distantstock;

import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.ModItems;
import dev.distantstock.link.ParcelEscrow;
import dev.distantstock.link.ParcelEscrowPump;
import dev.distantstock.link.ParcelLedger;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 本地投递：包裹的目的地就是本机时，没有 Transerver 也要送到。
 *
 * <p>These cover the path a save with no Transerver configured actually takes. A dock group only
 * means something if a parcel can be sent to one, and the transport is allowed to be absent, so the
 * escrow pump has to finish the delivery in-process instead of leaving the record HELD forever.
 *
 * <p>Every dock is put in a freshly created group. Groups are random UUIDs, so the docks other
 * cases place in the same batch are never eligible for these parcels, and the other cases' parcels
 * (all of which use the default group) are never eligible for these docks. The game test runner
 * runs the whole batch in parallel, so that isolation is not optional.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class ParcelEscrowGameTests {
    private static final int Y = 2;
    private static final int Z = 2;
    /** 别的组里的港：包裹不该落在这里。 */
    private static final int OTHER_X = 2;
    /** 目标组里的港。 */
    private static final int TARGET_X = 5;

    /**
     * 目的地是本机的包裹必须真的到达目标港：escrow 记录清掉、ledger 打标记，一个都不能少。
     *
     * <p>没挂 Transerver 时（也就是绝大多数单机存档）这条路径以前是断的：escrow 泵调 send()
     * 拿到 null，记录就永远停在 HELD，护目镜上的在途数还会一直涨。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void localParcelReachesTheTargetDock(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        DockGroupDirectory directory = DockGroupDirectory.get(server);
        DockGroup otherGroup = directory.create("发送组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockGroup targetGroup = directory.create("目标组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockBlockEntity other = placeDock(h, OTHER_X, otherGroup.id());
        DockBlockEntity target = placeDock(h, TARGET_X, targetGroup.id());
        // onLoad（也就是 LoadedDocks.add）要等下一个方块实体 tick 才跑，所以投递必须等一拍。
        h.runAfterDelay(2, () -> {
            h.assertTrue(LoadedDocks.allInGroup(targetGroup.id()).contains(target),
                    "目标港没有注册进 LoadedDocks，后面的断言就没有意义了");
            ParcelEscrow escrow = ParcelEscrow.get(server);
            UUID parcelId = escrow.hold(new ItemStack(ModItems.REMOTE_PACKAGE.get()), "",
                    TranserverBridge.localNodeId(), targetGroup.id(),
                    level.dimension().location().toString(), h.absolutePos(new BlockPos(OTHER_X, Y, Z)),
                    level.getGameTime(), level.registryAccess());
            ParcelEscrowPump.tick(server);

            h.assertTrue(target.displayedStack().is(ModItems.REMOTE_PACKAGE.get()),
                    "本地投递没有把包裹送进目标组的港");
            h.assertTrue(other.displayedStack().isEmpty(), "包裹落进了另一个组的港");
            h.assertTrue(escrow.find(parcelId).isEmpty(),
                    "投递成功后 escrow 记录没有清掉，包裹会被再投一次");
            h.assertTrue(ParcelLedger.get(server).contains(parcelId),
                    "ledger 没有标记已投递的包裹，重放会被当成一个新包裹");
            h.succeed();
        });
    }

    /**
     * 组决定落哪个港：包裹的地址**不参与**选择，多古怪的地址都照样落进这个组。
     *
     * <p>这条规则以前是反的 —— 港会拿自己的地址再筛一遍包裹，于是同一个组里的两个港只要写了不同
     * 的地址就各收一半，"一组多个港"退化成了"几个各管一半的港"，而哪个港吃哪一半取决于一个方块上
     * 根本不显示、只有戴护目镜才看得见的东西。现在港只认组；地址是包裹落地**之后**，由本机物流
     * （蛙港、溜槽、传送带）用来继续分拣的。
     *
     * <p>断言故意用一个谁也不会去写的地址：以前它会被同组的两个港一起拒收，包裹无处可去。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aGroupChoosesTheDockAndTheAddressDoesNot(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        DockGroup group = DockGroupDirectory.get(server)
                .create("地址组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockBlockEntity one = placeDock(h, TARGET_X, group.id());
        DockBlockEntity other = placeDock(h, OTHER_X, group.id());

        h.runAfterDelay(2, () -> {
            ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            com.simibubi.create.content.logistics.box.PackageItem.addAddress(parcel, "没人认领的门牌");
            h.assertTrue("没人认领的门牌".equals(com.simibubi.create.content.logistics.box.PackageItem
                            .getAddress(parcel)),
                    "包裹地址没写进去，后面的断言就没有意义了");

            ParcelEscrow escrow = ParcelEscrow.get(server);
            escrow.hold(parcel, "没人认领的门牌", TranserverBridge.localNodeId(), group.id(),
                    level.dimension().location().toString(),
                    h.absolutePos(new BlockPos(OTHER_X, Y, Z)), level.getGameTime(),
                    level.registryAccess());
            ParcelEscrowPump.tick(server);

            boolean landed = one.displayedStack().is(ModItems.REMOTE_PACKAGE.get())
                    || other.displayedStack().is(ModItems.REMOTE_PACKAGE.get());
            h.assertTrue(landed, "包裹没落进这个组的任何一个港 —— 地址又被当成收件条件了");
            h.succeed();
        });
    }

    /**
     * 过海那一刻换地址：包裹从对面带来的门牌，到这边就换成这边的。
     *
     * <p>证据在包裹自己身上，而不是在"它落进了哪个港"上：港现在只认组，包裹进港跟它身上写什么
     * 无关，所以落进港这件事证明不了替换发生过。落地之后它穿的必须是本端地址，标签必须已经清掉
     * （留着就会被换第二次，第二次没有对面那台服务器的地址可换，包裹会凭空变成另一个门牌）。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aCrossingSwapsTheParcelsAddress(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        DockGroup group = DockGroupDirectory.get(server)
                .create("过海组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockBlockEntity dock = placeDock(h, TARGET_X, group.id());

        h.runAfterDelay(2, () -> {
            ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            PackageItem.addAddress(parcel, "乙站发货口");
            dev.distantstock.routing.RemoteRouteData.write(parcel,
                    new dev.distantstock.routing.RemoteRoute(
                            dev.distantstock.routing.RemoteRoute.CURRENT_SCHEMA,
                            java.util.UUID.fromString(TranserverBridge.localNodeId()),
                            group.id(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
                    "本服 · " + group.name(), "甲站收货口");
            h.assertTrue("甲站收货口".equals(dev.distantstock.routing.RemoteRouteData.homeAddress(parcel)),
                    "本端地址没写到包裹上，后面的断言就没有意义了");

            ParcelEscrow escrow = ParcelEscrow.get(server);
            escrow.hold(parcel, "乙站发货口", TranserverBridge.localNodeId(), group.id(),
                    level.dimension().location().toString(),
                    h.absolutePos(new BlockPos(OTHER_X, Y, Z)), level.getGameTime(),
                    level.registryAccess());
            ParcelEscrowPump.tick(server);

            ItemStack landed = dock.displayedStack();
            h.assertTrue(landed.is(ModItems.REMOTE_PACKAGE.get()),
                    "包裹没有落进这个组的港");
            h.assertTrue("甲站收货口".equals(PackageItem.getAddress(landed)),
                    "落地的包裹还穿着对岸的门牌：" + PackageItem.getAddress(landed));
            h.assertTrue(dev.distantstock.routing.RemoteRouteData.homeAddress(landed).isEmpty(),
                    "换过的地址还留在标签里，会被再换一次");
            h.succeed();
        });
    }

    /**
     * 摸一下：两个地址都在，而且**过完海只剩一个**。
     *
     * <p>玩家报的是"设置了远端111本端222，但只能看见一个"。第二个地址以前挂在"这单会跨服"后面，
     * 而那个标记是出港时盖上的、**没有任何地方会去掉** —— 于是落地之后的包裹一辈子挂着
     * 「跨服寄往…」，一条已经走完的路，每摸一次都被再告知一次。
     *
     * <p>现在两件事分开：第二个地址只问"它身上有没有"，跨服那一行只问"路线指向的还是不是别的
     * 服务器"。后者是个现算的答案，落地那一刻它自己就变了 —— 因为包裹脚下的这台服务器，正是路线
     * 指向的那一台。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void bothAddressesAreVisibleUntilTheParcelCrosses(GameTestHelper h) {
        // 一件正在往对面去的包裹：路线指向别的节点，身上还带着回来要穿的地址。
        ItemStack crossing = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(crossing, "111");
        dev.distantstock.routing.RemoteRouteData.write(crossing,
                new dev.distantstock.routing.RemoteRoute(
                        dev.distantstock.routing.RemoteRoute.CURRENT_SCHEMA,
                        java.util.UUID.randomUUID(),
                        DockGroupDirectory.DEFAULT_GROUP_ID,
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
                "远仓B · 仓库", "222");

        java.util.List<Component> lines = dev.distantstock.item.RemotePackageItem.extraLines(crossing);
        h.assertTrue(lines.stream().anyMatch(line -> line.getString().contains("222")),
                "包裹身上写着两个地址，却没有一行说出第二个：" + lines);
        h.assertTrue(lines.stream().anyMatch(line -> line.getString().contains("远仓B · 仓库")),
                "它正要去对面，却没说要去哪儿：" + lines);

        // 过海：地址换成 222，第二个地址的标签清掉。
        ItemStack landed = crossing.copy();
        h.assertTrue(dev.distantstock.routing.RemoteRouteData.applyHomeAddress(landed),
                "过海没有换地址");
        h.assertTrue("222".equals(PackageItem.getAddress(landed)),
                "换完的地址不对：" + PackageItem.getAddress(landed));
        // 落地的包裹：路线指向的那台服务器就是这一台（这才是"到了"的定义），所以两行都不该再有。
        dev.distantstock.routing.RemoteRouteData.write(landed,
                new dev.distantstock.routing.RemoteRoute(
                        dev.distantstock.routing.RemoteRoute.CURRENT_SCHEMA,
                        java.util.UUID.fromString(TranserverBridge.localNodeId()),
                        DockGroupDirectory.DEFAULT_GROUP_ID,
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        java.util.List<Component> after = dev.distantstock.item.RemotePackageItem.extraLines(landed);
        h.assertTrue(after.stream().noneMatch(line -> line.getString().contains("222")),
                "落地之后还在说「对面服务器上用 222」，那是已经用掉的东西：" + after);
        h.assertTrue(after.isEmpty(),
                "落地之后的包裹只剩 Create 自己那一行地址，这里却还多出：" + after);
        h.succeed();
    }

    /**
     * 地址里的一层语法：`@名字`（指名给某个玩家）。
     *
     * <p>Create 自己的通配符（`*`）不再由我们匹配了 —— 港只认组，地址落地之后由本机物流接手，
     * 而蛙港匹配地址用的就是 Create 的 {@code matchAddress}，那套规矩一条没变，只是换了地方执行。
     * 这里所以只钉我们**加**的那一层：`@名字` 的全部意义在"只有那个人能取走"，一条只会让包裹
     * 显示得好看一点的语法不值得存在，而一条没人测的所有权规则不值得信任。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void addressesCarryPlayerNames(GameTestHelper h) {
        // @名字：取出来的是名字，不是原串。
        ItemStack named = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(named, "@张三");
        h.assertTrue("@张三".equals(PackageItem.getAddress(named)), "地址没写进去");
        h.assertTrue("张三".equals(dev.distantstock.routing.ParcelAddressing.addressee(named)),
                "@名字 没有被解析成名字");
        h.assertTrue(dev.distantstock.routing.ParcelAddressing.addressee(
                        new ItemStack(ModItems.REMOTE_PACKAGE.get())).isEmpty(),
                "没写地址的包裹被解析出了收件人");
        ItemStack plain = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(plain, "厂区门口");
        h.assertTrue(dev.distantstock.routing.ParcelAddressing.addressee(plain).isEmpty(),
                "普通地址被当成了收件人");

        // 所有权：没指名的谁都能拿；指名的只有本人。
        var level = h.getLevel();
        net.minecraft.world.entity.player.Player stranger = h.makeMockPlayer(GameType.SURVIVAL);
        h.assertTrue(dev.distantstock.routing.ParcelAddressing.mayTake(level.getServer(), plain, stranger),
                "没指名的包裹被拦下了");
        h.assertFalse(dev.distantstock.routing.ParcelAddressing.mayTake(level.getServer(), named, stranger),
                "指名给别人的包裹被陌生人拿走了");

        // 港里那件包裹：陌生人取不出来，而且港能说出是给谁的。
        DockGroup group = DockGroupDirectory.get(level.getServer())
                .create("指名组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockBlockEntity dock = placeDock(h, TARGET_X, group.id());
        // 港身上不写地址：收不收这包裹只看组，"是不是给谁的"是包裹自己的事。
        h.runAfterDelay(2, () -> {
            h.assertTrue(dock.insert(named), "港没有收下这包裹");
            h.assertFalse(dock.takeReceived(stranger), "陌生人把指名给别人的包裹取走了");
            // 返回的是解析出来的名字，不是带 @ 的原串。
            h.assertTrue("张三".equals(dock.heldForSomeoneElse(stranger)),
                    "港说不出这件包裹是给谁的：" + dock.heldForSomeoneElse(stranger));
            h.succeed();
        });
    }

    /**
     * 只有过一个地址的包裹（老版本发的，或者货根本不过海）不许被凭空改地址。
     *
     * <p>没有本端地址时什么都不做，正是「不猜」这条规矩：发送方没说过的事，代码不能替它编。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aParcelWithoutAHomeAddressKeepsItsOwn(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        DockGroup group = DockGroupDirectory.get(server)
                .create("单地址组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockBlockEntity dock = placeDock(h, TARGET_X, group.id());

        h.runAfterDelay(2, () -> {
            ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            PackageItem.addAddress(parcel, "旧版地址");
            ParcelEscrow escrow = ParcelEscrow.get(server);
            escrow.hold(parcel, "旧版地址", TranserverBridge.localNodeId(), group.id(),
                    level.dimension().location().toString(),
                    h.absolutePos(new BlockPos(OTHER_X, Y, Z)), level.getGameTime(),
                    level.registryAccess());
            ParcelEscrowPump.tick(server);

            ItemStack landed = dock.displayedStack();
            h.assertTrue(landed.is(ModItems.REMOTE_PACKAGE.get()), "包裹没有落进港");
            h.assertTrue("旧版地址".equals(PackageItem.getAddress(landed)),
                    "没有本端地址的包裹被换了地址：" + PackageItem.getAddress(landed));
            h.succeed();
        });
    }

    /**
     * 没匹配的港时记录必须留在 HELD。区块没加载、港满了、地址对不上都只是「现在不行」，
     * 丢记录才是不可接受的那一种。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void parcelWithNoTargetDockStaysHeld(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        // 一个没有任何港在里面的组，等价于「目标港所在区块根本没加载」。
        DockGroup emptyGroup = DockGroupDirectory.get(server)
                .create("空组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        h.runAfterDelay(2, () -> {
            ParcelEscrow escrow = ParcelEscrow.get(server);
            UUID parcelId = escrow.hold(new ItemStack(ModItems.REMOTE_PACKAGE.get()), "",
                    TranserverBridge.localNodeId(), emptyGroup.id(),
                    level.dimension().location().toString(), h.absolutePos(new BlockPos(OTHER_X, Y, Z)),
                    level.getGameTime(), level.registryAccess());
            ParcelEscrowPump.tick(server);

            ParcelEscrow.Record record = escrow.find(parcelId).orElse(null);
            h.assertTrue(record != null, "没有目标港时 escrow 把记录丢掉了");
            h.assertTrue(record.state() == ParcelEscrow.State.HELD,
                    "没有目标港的记录状态不是 HELD，而是 " + record.state());
            h.assertTrue(!ParcelLedger.get(server).contains(parcelId), "没有投递成功却写了 ledger");

            // 再跑一拍还是 HELD：RETRY 不是失败，也不会被当成退件处理掉。
            ParcelEscrowPump.tick(server);
            ParcelEscrow.Record retried = escrow.find(parcelId).orElseThrow();
            h.assertTrue(retried.state() == ParcelEscrow.State.HELD,
                    "重试把记录变成了 " + retried.state());
            h.assertTrue(retried.encodedPackage().equals(record.encodedPackage()),
                    "重试改动了包裹内容");
            escrow.remove(parcelId);
            h.succeed();
        });
    }

    /**
     * 收件港组在这一侧根本不存在：等一会儿，然后退回，而不是永远重投。
     *
     * <p>和上一条用例的区别正是这条的全部意义。上面那个组**存在**，只是里面没有港 —— 那是"区块没
     * 加载、港满了"那一类，等就是了。这一个组**不存在**，而组 id 是随机的：没人能再把它造出来，
     * 所以"等"不是耐心，是把一件货永远留在空中。真机上就是这么卡住的：两件包裹对着一个谁都认不出
     * 的组 id，每几秒被重投一次，投了一整个下午，日志里一个字都没有。
     *
     * <p>宽限期是五分钟的游戏刻，用例等不起，所以这里直接调那个收参数的入口、宽限期给 0。参数化而
     * 不是加一个静态开关：game test 是并行跑在同一个 JVM 里的，那种开关会顺手改掉别人正在跑的那条。
     *
     * <p>退回是**有出路**的，和"丢掉"不是一回事：记录进 REJECTED，退件流程接手，包裹从发件港的
     * 底面掉出来还给玩家；发件港早就没了才进服务器的退件箱等管理员发还。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aGroupThatDoesNotExistHereIsRefusedNotRetried(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        // 一个随机 uuid：这一侧的目录里没有这个组，而且以后也不会有。
        UUID nowhere = UUID.randomUUID();
        h.runAfterDelay(2, () -> {
            h.assertTrue(DockGroupDirectory.get(server).find(nowhere).isEmpty(),
                    "用例的前提不成立：这一侧居然有这个组");

            ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            var dispatch = dev.distantstock.link.PackageDispatchCodec.create(UUID.randomUUID(), nowhere,
                    "", dev.distantstock.link.PayloadManifest.fromPackage(parcel),
                    dev.distantstock.link.PackageCodec.encode(parcel, level.registryAccess()));

            // 宽限期内还是"等一下"。这一条不能少：两边各建一次组是常态，对面可能正在建。
            h.assertTrue(dev.distantstock.link.TranserverPackageService.apply(server, dispatch,
                            TranserverBridge.localNodeId(), 20L * 60 * 5)
                            == dev.transerver.api.DeliveryResult.RETRY,
                    "对面可能正在建这个组，这一侧却当场就把它退了");
            // 宽限期过了：退回，而且是被明确拒绝，不是继续等。
            h.assertTrue(dev.distantstock.link.TranserverPackageService.apply(server, dispatch,
                            TranserverBridge.localNodeId(), 0L)
                            == dev.transerver.api.DeliveryResult.REJECTED,
                    "一个永远不存在的收件港组没有被退回，包裹会一直被重投");
            h.succeed();
        });
    }

    /**
     * isLocal 的边界。空白目的地是「比节点 id 更早的记录」，只能由本机认领；哨兵必须是一个
     * uuid 的规范写法，否则写进记录再读出来就匹配不上；别人的节点 id 不能被当成本机，那会把
     * 包裹投进错误的存档。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void isLocalAcceptsBlanksAndTheLocalNode(GameTestHelper h) {
        h.assertTrue(TranserverBridge.isLocal(null), "null 目的地必须算本机");
        h.assertTrue(TranserverBridge.isLocal(""), "空目的地必须算本机");
        h.assertTrue(TranserverBridge.isLocal("   "), "纯空白目的地必须算本机");
        h.assertTrue(TranserverBridge.isLocal(TranserverBridge.localNodeId()), "本机节点 id 必须算本机");
        try {
            UUID sentinel = UUID.fromString(TranserverBridge.LOCAL_NODE_ID);
            h.assertTrue(TranserverBridge.LOCAL_NODE_ID.equals(sentinel.toString()),
                    "哨兵的字符串形式和 UUID.toString() 不一致，落盘的记录将永远匹配不上本机");
        } catch (IllegalArgumentException invalid) {
            h.fail("本机哨兵不是合法 uuid：" + invalid.getMessage());
        }
        // 挂上 Transerver 时 localNodeId() 是真实节点，没挂上时是哨兵；两种情况都要有一个「别人」。
        String foreign = TranserverBridge.LOCAL_NODE_ID.equals(TranserverBridge.localNodeId())
                ? UUID.randomUUID().toString() : TranserverBridge.LOCAL_NODE_ID;
        h.assertFalse(TranserverBridge.isLocal(foreign), "别的节点被当成了本机：" + foreign);
        h.succeed();
    }

    /** 放一个港并把它划进指定的港组。 */
    private static DockBlockEntity placeDock(GameTestHelper h, int x, UUID groupId) {
        BlockPos pos = h.absolutePos(new BlockPos(x, Y, Z));
        h.getLevel().setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(dock != null, "x=" + x + " 处没有生成远仓港方块实体");
        dock.setGroupId(groupId);
        // 这些用例讲的是投递，不是塔：设备由"某座塔"带着（见 TestTowers）。
        TestTowers.carried(h, pos);
        return dock;
    }

    private ParcelEscrowGameTests() {
    }

    /**
     * A held parcel is stamped on the level's clock, not on a wall clock.
     *
     * <p>This replaces a test that accepted either outcome and so could not fail. The escrow used to
     * stamp parcels with {@code System.currentTimeMillis()} while the deadline compared that stamp
     * against game time — eleven orders of magnitude apart, so every parcel looked brand new and
     * none was ever given back. Asserting the stamp is on the same clock as the deadline is the
     * part of that which can be checked in a second.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aHeldParcelIsStampedOnTheLevelClock(GameTestHelper h) {
        var escrow = dev.distantstock.link.ParcelEscrow.get(h.getLevel().getServer());
        var level = h.getLevel();
        long before = level.getGameTime();
        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        java.util.UUID id = escrow.hold(parcel, "", dev.distantstock.link.TranserverBridge.localNodeId(),
                java.util.UUID.randomUUID(), level.dimension().location().toString(),
                h.absolutePos(new BlockPos(1, 1, 1)), before, level.registryAccess());

        var record = escrow.find(id).orElse(null);
        h.assertTrue(record != null, "a held parcel was not recorded at all");
        long stamp = record.createdAt();
        h.assertTrue(stamp >= before, "a parcel was stamped before it was held: " + stamp);
        h.assertTrue(stamp <= level.getGameTime() + 1,
                "a parcel was stamped in the future, so the deadline can never reach it: " + stamp);
        // A wall clock is around 1.7e12; game time is nowhere near it. This is the shape of the bug.
        h.assertTrue(stamp < 1_000_000_000L,
                "a parcel was stamped with something that is not a game time: " + stamp);
        h.succeed();
    }

    /**
     * The deadline's two sides, without waiting half an hour for one of them.
     *
     * <p>The test above proves the stamp is on the right clock; this proves the comparison on that
     * clock is the one we mean. Between them nothing is left that could be wrong and still pass.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theHoldDeadlineFiresOnlyAfterItsWindow(GameTestHelper h) {
        long window = dev.distantstock.link.ParcelEscrowPump.HOLD_TIMEOUT_TICKS;
        h.assertFalse(dev.distantstock.link.ParcelEscrowPump.pastDeadline(1000L, 1000L),
                "a parcel that was just held was already past the deadline");
        h.assertFalse(dev.distantstock.link.ParcelEscrowPump.pastDeadline(1000L, 1000L - window + 1),
                "a parcel still inside its window was past the deadline");
        h.assertTrue(dev.distantstock.link.ParcelEscrowPump.pastDeadline(1000L, 1000L - window),
                "a parcel exactly at the deadline was not past it");
        h.assertTrue(dev.distantstock.link.ParcelEscrowPump.pastDeadline(1000L, 1000L - window - 1),
                "a parcel past its window was not past the deadline");
        h.succeed();
    }

}
