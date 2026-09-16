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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
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
     * 按地址投递：同一个组里的两个港，包裹只该落在地址对上的那一个。
     *
     * <p>组决定「从哪些港出来」，地址决定「具体是哪一个」—— 一个组里有好几个港时，
     * 后者才是玩家真正在用的那件事。这条链的最后一跳是 {@code PackageItem.matchAddress}，
     * 港没写地址时当作 {@code *}（谁都收）。
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aParcelLandsInTheDockItsAddressNames(GameTestHelper h) {
        var level = h.getLevel();
        MinecraftServer server = level.getServer();
        DockGroup group = DockGroupDirectory.get(server)
                .create("地址组 " + java.util.UUID.randomUUID().toString().substring(0, 8));
        DockBlockEntity named = placeDock(h, TARGET_X, group.id());
        DockBlockEntity open = placeDock(h, OTHER_X, group.id());
        // 两个都写死地址，而且不同：只写一个的话另一个就成了 `*`（谁都收），
        // 两个港都会匹配上，选中哪个都是对的，这条用例就什么都证明不了。
        named.setImport("甲站");
        open.setImport("乙站");

        h.runAfterDelay(2, () -> {
            ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            com.simibubi.create.content.logistics.box.PackageItem.addAddress(parcel, "甲站");
            h.assertTrue("甲站".equals(com.simibubi.create.content.logistics.box.PackageItem
                            .getAddress(parcel)),
                    "包裹地址没写进去，后面的断言就没有意义了");

            ParcelEscrow escrow = ParcelEscrow.get(server);
            escrow.hold(parcel, "甲站", TranserverBridge.localNodeId(), group.id(),
                    level.dimension().location().toString(),
                    h.absolutePos(new BlockPos(OTHER_X, Y, Z)), level.getGameTime(),
                    level.registryAccess());
            ParcelEscrowPump.tick(server);

            h.assertTrue(named.displayedStack().is(ModItems.REMOTE_PACKAGE.get()),
                    "包裹没有落进地址对上的那个港");
            h.assertTrue(open.displayedStack().isEmpty(),
                    "包裹落进了同组里地址不对的那个港（乙站）");
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
