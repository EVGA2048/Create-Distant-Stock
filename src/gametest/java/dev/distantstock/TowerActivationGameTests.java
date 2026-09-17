package dev.distantstock;

import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.DockStatus;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.block.TowerTier;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerBilling;
import dev.distantstock.routing.TowerDirectory;
import dev.distantstock.routing.TowerSystem;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What a tower carries, and what a device does when it is not carried.
 *
 * <p>Half of this file never touches a world. The rules — how far a tower reaches, which towers
 * merge, who wins when there are more devices than places — are arithmetic, and the game test
 * runner shares one level between cases running in parallel: two arenas sit thirteen blocks apart
 * while a tower reaches thirty-two, so a real tower built by one case would switch off every other
 * case's docks. The rules are therefore checked directly, and the wiring that reads them is checked
 * with a single device pinned to the answer under test.
 *
 * <p>For the same reason the two billing settings live in one case rather than two. The switch is
 * one field shared by the whole server, and two cases running at once would each keep setting it
 * back to what the other was not expecting.
 *
 * <p>The one case that does build a real, unpinned world is the first: no towers at all, which is
 * the state every existing save is in and the promise that this stage is not allowed to break.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class TowerActivationGameTests {
    /**
     * **没塔就不能用** —— 这条规则本身。
     *
     * <p>它原来断言的是反过来的事（"没有塔的存档照常收发"），那是模组早期的一条兼容承诺。用户
     * 2026-09-17 拍板推翻：机器必须站在一座**正在运行**的塔的范围内才工作，否则"塔是必需品"这句
     * 话在新玩家那里永远学不会，而且真正的后果是"邻居建了座塔，我全厂停产"。
     *
     * <p>这里没有任何 pin：这个世界的状态就是"一座塔都没有"，而那正是新存档、也是玩家第一次
     * 放下港时的样子。包裹必须被拒收，而且港要说得出为什么（无塔时灯是暗的、护目镜第一行就是红的）。
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void withoutATowerADockRefusesToWork(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(pos);
        dock.setExport(UUID.randomUUID());
        dock.setDefaultDestination(UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId()),
                DockGroupDirectory.DEFAULT_GROUP_ID);

        h.assertFalse(dock.canSend(), "a dock in a world without towers still reports that it can send");
        h.assertFalse(dock.canReceive(), "a dock in a world without towers still reports that it can receive");
        h.assertFalse(insertParcel(level, pos),
                "a dock no tower carries took a parcel in — 塔的硬门槛没有生效");
        h.succeed();
    }

    /** The edge of a tower's reach, which is a sphere around the base and nothing else. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void reachStopsAtTheRadius(GameTestHelper h) {
        BlockPos base = new BlockPos(0, 64, 0);
        TowerSystem.Member tower = tower(base, 8, 8, true);
        List<TowerSystem.System> systems = TowerSystem.merge(List.of(tower));
        h.assertTrue(systems.size() == 1, "a lone tower did not make one system");
        TowerSystem.System system = systems.getFirst();

        h.assertTrue(system.covers(base.offset(8, 0, 0)), "the last block inside the reach is not covered");
        h.assertFalse(system.covers(base.offset(9, 0, 0)), "one block past the reach is still covered");
        // Straight up leaves the reach the same way: the distance is three-dimensional, so a device
        // stacked above the tower is not carried for free.
        h.assertTrue(system.covers(base.offset(0, 8, 0)), "the top of the reach is not covered");
        h.assertFalse(system.covers(base.offset(0, 9, 0)), "a device above the tower is still covered");

        // And the same edge through the snapshot the gates actually read.
        TowerSystem.Device inside = device(base.offset(8, 0, 0));
        TowerSystem.Device outside = device(base.offset(9, 0, 0));
        TowerActivation.Snapshot snapshot = TowerActivation.of(List.of(tower), List.of(inside, outside),
                java.util.Map.of());
        h.assertTrue(snapshot.active(h.getLevel().dimension(), inside.pos()),
                "a device inside the reach was not activated");
        h.assertFalse(snapshot.active(h.getLevel().dimension(), outside.pos()),
                "a device past the reach was activated");
        h.succeed();
    }

    /** Two towers that reach each other are one machine, and one machine adds up its budgets. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void overlappingTowersBecomeOneSystem(GameTestHelper h) {
        TowerSystem.Member first = tower(new BlockPos(0, 64, 0), 20, 8, true);
        TowerSystem.Member second = tower(new BlockPos(30, 64, 0), 20, 16, true);
        List<TowerSystem.Member> both = List.of(first, second);

        List<TowerSystem.System> merged = TowerSystem.merge(both);
        h.assertTrue(merged.size() == 1, "two overlapping towers did not merge");
        h.assertTrue(merged.getFirst().devices() == 24,
                "the merged system carries " + merged.getFirst().devices() + " devices instead of 24");

        // The same two towers the other way round have to produce the same value: the activation
        // snapshot is compared between ticks, and an order-dependent one would look like a change.
        List<TowerSystem.Member> reversed = new ArrayList<>(both);
        Collections.reverse(reversed);
        h.assertTrue(merged.equals(TowerSystem.merge(reversed)),
                "the same two towers made two different systems");

        // One block further apart than their reaches allow, and they are two machines again.
        TowerSystem.Member apart = tower(new BlockPos(41, 64, 0), 20, 8, true);
        h.assertTrue(TowerSystem.merge(List.of(first, apart)).size() == 2,
                "two towers that do not reach each other were merged anyway");

        // Another dimension is never part of the same system, however close it is on paper.
        TowerSystem.Member elsewhere = new TowerSystem.Member(
                new TowerSystem.TowerId("minecraft:the_nether", new BlockPos(0, 64, 0).asLong()),
                new BlockPos(0, 64, 0), 20, 8, true, true);
        h.assertTrue(TowerSystem.merge(List.of(first, elsewhere)).size() == 2,
                "a tower in another dimension joined the system");
        h.succeed();
    }

    /**
     * More devices than places: the closest to their tower stay, and the same ones every time.
     *
     * <p>Run twice with the candidate list in two different orders. A tie broken by whatever order
     * the caller happened to use would switch a different machine off on every rebuild, twenty
     * times a second, which is how a logistics chain starts dropping parcels.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void overBudgetDevicesAreChosenTheSameWayTwice(GameTestHelper h) {
        BlockPos base = new BlockPos(0, 64, 0);
        TowerSystem.Member tower = tower(base, 64, 2, true);
        List<TowerSystem.System> systems = TowerSystem.merge(List.of(tower));

        TowerSystem.Device near = device(base.offset(1, 0, 0));
        TowerSystem.Device middle = device(base.offset(2, 0, 0));
        TowerSystem.Device far = device(base.offset(3, 0, 0));
        List<TowerSystem.Device> forward = List.of(near, middle, far);
        List<TowerSystem.Device> backward = List.of(far, middle, near);

        List<TowerSystem.Carried> first = TowerSystem.carry(systems, forward);
        List<TowerSystem.Carried> second = TowerSystem.carry(systems, backward);
        h.assertTrue(first.size() == 2, "a budget of two carried " + first.size() + " devices");
        h.assertTrue(first.equals(second), "the same three devices were chosen differently on a rerun");
        h.assertTrue(first.get(0).device().pos().equals(near.pos()), "the closest device did not stay");
        h.assertTrue(first.get(1).device().pos().equals(middle.pos()), "the second closest did not stay");

        // The two answers the gates see, from a run through the real snapshot.
        TowerActivation.Snapshot snapshot = TowerActivation.of(List.of(tower), new ArrayList<>(backward),
                java.util.Map.of());
        h.assertTrue(snapshot.active(h.getLevel().dimension(), near.pos()), "the closest device is off");
        h.assertFalse(snapshot.active(h.getLevel().dimension(), far.pos()),
                "a device past the budget is on");
        h.succeed();
    }

    /**
     * What a dock counted lands on the tower that carries it, and only on that one.
     *
     * <p>Both halves matter and both are silent when wrong. The counts are keyed by device, so the
     * merge has to put each window on the tower that won the device — the nearest one, the first
     * one and the system's first member are all plausible ways to get it wrong, and all three would
     * print a number, just not the right one. The third tower carries nothing and has to read zero
     * rather than its neighbour's traffic.
     *
     * <p>Only the rules half is here: the counts go in as a plain list, so no tower has to stand in
     * the world. The live half — the survey handing these counts over at all — is guarded by the
     * signature instead of by a case. It used to be possible to call the builder without them, and
     * {@code survey} did exactly that, collecting every dock's window and then dropping it on the
     * floor: the flow line read 0/0 for a tower whose docks had been moving parcels all along, and
     * nothing failed, because a readout nobody wired up and a line that has never moved are the
     * same zero. There is no such overload any more.
     *
     * <p>The third piece — a dock counting its own parcels — is checked where a parcel is actually
     * moved, in {@code DockGameTests.aParcelCrossesFromOneTowerToAnother}. It cannot be checked
     * here: a tower that is complete and turning claims every dock in this shared level, which is
     * why no case in this file builds one.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aDocksWindowLandsOnTheTowerThatCarriesIt(GameTestHelper h) {
        BlockPos first = new BlockPos(0, 64, 0);
        BlockPos second = new BlockPos(100, 64, 0);
        BlockPos lonely = new BlockPos(200, 64, 0);
        TowerSystem.Member near = tower(first, 20, 8, true);
        TowerSystem.Member far = tower(second, 20, 8, true);
        TowerSystem.Member empty = tower(lonely, 20, 8, true);
        TowerSystem.Device firstDock = device(first.offset(1, 0, 0));
        TowerSystem.Device secondDock = device(first.offset(2, 0, 0));
        TowerSystem.Device otherDock = device(second.offset(1, 0, 0));

        TowerActivation.Snapshot snapshot = TowerActivation.of(List.of(near, far, empty),
                List.of(firstDock, secondDock, otherDock),
                java.util.Map.of(firstDock, new TowerActivation.Traffic(3, 1),
                        secondDock, new TowerActivation.Traffic(1, 1),
                        otherDock, new TowerActivation.Traffic(0, 2)));

        // Two docks under one tower add up: one tower's readout is the whole line's traffic.
        TowerActivation.Traffic nearTraffic = snapshot.traffic(near.id());
        h.assertTrue(nearTraffic.sent() == 4 && nearTraffic.received() == 2,
                "the near tower was given " + nearTraffic + " instead of 4/2");
        TowerActivation.Traffic farTraffic = snapshot.traffic(far.id());
        h.assertTrue(farTraffic.sent() == 0 && farTraffic.received() == 2,
                "the far tower was given " + farTraffic + " instead of 0/2");
        TowerActivation.Traffic emptyTraffic = snapshot.traffic(empty.id());
        h.assertTrue(emptyTraffic.sent() == 0 && emptyTraffic.received() == 0,
                "a tower carrying nothing was given " + emptyTraffic);
        h.succeed();
    }

    /**
     * A dock no tower carries stops, and a player can still empty it by hand.
     *
     * <p>The second half is not an afterthought. If a dark dock refused to hand anything back, the
     * only way at a parcel inside it would be to break the block, and a tower losing its shaft
     * would become a way to lose goods instead of a way to pause a factory.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aDockOutsideItsTowerStopsButCanStillBeEmptied(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(pos);
        dock.setImport();
        // 先把"它被某座塔带着"钉上：这条用例讲的是**失去**塔之后会怎样，所以前半段必须是一台
        // 正常工作的港。塔的硬门槛让这件事不再是不言自明的。
        TestTowers.carried(h, pos);
        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        h.assertTrue(dock.insert(parcel.copy()), "the dock refused a parcel while it was carried");

        // The receive animation has to finish before anything can be taken out, and the pin has to
        // stay in place until the assertions have run — which is after this method has returned.
        h.runAfterDelay(40, () -> {
            TowerActivation.pinDevice(TowerSystem.TowerId.of(level.dimension(), pos), false, null);
            try {
                h.assertFalse(dock.canSend(), "a dock outside its tower still reports that it can send");
                h.assertFalse(dock.canReceive(), "a dock outside its tower still reports that it can receive");

                var player = h.makeMockPlayer(GameType.SURVIVAL);
                h.assertTrue(dock.takeReceived(player),
                        "the parcel could not be taken out of a dormant dock by hand");
                h.assertTrue(dock.displayedStack().isEmpty(), "the parcel is still in the dock");
                h.assertFalse(dock.insert(parcel.copy()),
                        "a dock outside its tower still took a parcel in");
                h.succeed();
            } finally {
                TowerActivation.unpinDevice(TowerSystem.TowerId.of(level.dimension(), pos));
            }
        });
    }

    /**
     * Charging off takes nothing; charging on holds a parcel the tower cannot pay for, and sends it
     * once the tower can.
     *
     * <p>All three answers belong together. A dock that sent anyway would be giving the service
     * away; one that took the ether and then failed to send would be taking payment for nothing;
     * and one that held a parcel forever after being refilled would turn a dry tank into a lost
     * delivery. The parcel has to be in exactly one place at every step.
     */
    @GameTest(template = "empty", timeoutTicks = 700)
    public static void chargingTakesEtherOnlyWhileTheSwitchIsOn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos freeCore = h.absolutePos(new BlockPos(1, 0, 1));
        BlockPos freeDock = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos paidCore = h.absolutePos(new BlockPos(5, 0, 5));
        BlockPos paidDock = h.absolutePos(new BlockPos(6, 2, 6));
        TowerCoreBlockEntity freeTower = placeTowerAndDock(h, level, freeCore, freeDock);
        TowerCoreBlockEntity paidTower = placeTowerAndDock(h, level, paidCore, paidDock);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(paidDock);

        TowerActivation.pinDevice(TowerSystem.TowerId.of(level.dimension(), freeDock), true,
                TowerSystem.TowerId.of(level.dimension(), freeCore));
        TowerBilling.overrideForTesting(false, 250);
        h.assertTrue(insertParcel(level, freeDock), "the parcel did not enter the outgoing slot");
        h.runAfterDelay(140, () -> {
            h.assertTrue(((DockBlockEntity) level.getBlockEntity(freeDock)).displayedStack().isEmpty(),
                    "the parcel did not leave with charging off");
            h.assertTrue(freeTower.ether() == 0, "a tower paid for a parcel with charging off");

            TowerBilling.overrideForTesting(true, 250);
            TowerActivation.pinDevice(TowerSystem.TowerId.of(level.dimension(), paidDock), true,
                    TowerSystem.TowerId.of(level.dimension(), paidCore));
            h.assertTrue(insertParcel(level, paidDock), "the second parcel did not enter the outgoing slot");
            h.runAfterDelay(100, () -> {
                h.assertTrue(!dock.displayedStack().isEmpty(),
                        "the parcel left although the tower had nothing to pay with");
                h.assertTrue(dock.status() == DockStatus.BLOCKED,
                        "an unpaid parcel did not report itself, got " + dock.status());
                h.assertTrue(paidTower.ether() == 0, "ether was taken by a tower that had none");

                paidTower.storeEther(1000);
                // The dock retries on its own after the payment window; it is not waiting for a
                // player to touch its inventory.
                h.runAfterDelay(300, () -> {
                    try {
                        h.assertTrue(dock.displayedStack().isEmpty(),
                                "the parcel stayed after the tower was refilled");
                        h.assertTrue(paidTower.ether() == 750,
                                "the parcel cost " + (1000 - paidTower.ether()) + " mB instead of 250");
                        h.succeed();
                    } finally {
                        TowerBilling.clearOverride();
                        TowerActivation.unpinDevice(TowerSystem.TowerId.of(level.dimension(), freeDock));
                        TowerActivation.unpinDevice(TowerSystem.TowerId.of(level.dimension(), paidDock));
                    }
                });
            });
        });
    }

    /** The settings file stores what it is handed, per tower, and forgets on request. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theTowerDirectoryKeepsSettingsPerTower(GameTestHelper h) {
        TowerDirectory directory = TowerDirectory.get(h.getLevel().getServer());
        TowerSystem.TowerId tower = TowerSystem.TowerId.of(h.getLevel().dimension(),
                h.absolutePos(new BlockPos(1, 1, 1)));
        TowerSystem.TowerId other = TowerSystem.TowerId.of(h.getLevel().dimension(),
                h.absolutePos(new BlockPos(2, 1, 1)));

        h.assertTrue(directory.settings(tower) == TowerDirectory.Settings.DEFAULT,
                "an untouched tower did not start from the defaults");

        directory.setSettings(tower, new TowerDirectory.Settings(2, false, true));
        TowerDirectory.Settings stored = directory.settings(tower);
        h.assertTrue(stored.radius() == 2 && !stored.loading() && stored.carrying(),
                "the settings did not come back as written: " + stored);
        h.assertTrue(directory.settings(other) == TowerDirectory.Settings.DEFAULT,
                "settings leaked onto another tower");

        directory.clear(tower);
        h.assertTrue(directory.settings(tower) == TowerDirectory.Settings.DEFAULT,
                "cleared settings came back");
        h.succeed();
    }

    /**
     * A radius is clamped to the tower's tier, and the stored number is not.
     *
     * <p>The clamp is what stops a mast that was taken down from still holding a square its new tier
     * does not pay for. Leaving the stored number alone is the other half: building the mast back up
     * has to restore what the operator asked for rather than what the tower could pay for at its
     * lowest point.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aRadiusIsClampedToTheTierButStoredUnchanged(GameTestHelper h) {
        TowerDirectory.Settings asked = new TowerDirectory.Settings(3, true, true);
        h.assertTrue(asked.radiusFor(TowerTier.I) == 0,
                "a tier I tower did not clamp a radius of three down to its own");
        h.assertTrue(asked.radiusFor(TowerTier.VII) == 3,
                "a tier VII tower refused a radius it can pay for");
        h.assertTrue(asked.radius() == 3, "the clamp wrote back over what the operator chose");

        TowerDirectory.Settings untouched = TowerDirectory.Settings.DEFAULT;
        h.assertTrue(untouched.radiusFor(TowerTier.IV) == TowerTier.IV.chunkRadius(),
                "an untouched tower did not take its tier's full radius");
        h.assertTrue(untouched.radiusFor(null) == 0, "a tower with no tier asked for chunks");
        h.succeed();
    }

    /**
     * A bare tower base and a dock beside it, wired for sending.
     *
     * <p>The base is deliberately not a tower: no mast, so it carries nothing and claims nothing in
     * the live snapshot. What the case needs from it is a tank and a block entity, and the answers
     * about who carries what are pinned rather than built.
     */
    private static TowerCoreBlockEntity placeTowerAndDock(GameTestHelper h, ServerLevel level,
                                                           BlockPos corePos, BlockPos dockPos) {
        level.setBlock(corePos, ModBlocks.TOWER_CORE.get().defaultBlockState(), 3);
        level.setBlock(dockPos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(dockPos);
        dock.setExport(UUID.randomUUID());
        // The local node, because that is what the gesture writes for a dock pointing at a warehouse
        // on this server — and these cases are about towers and billing, not about crossings. A
        // parcel bound for another node with the default group is refused on purpose (see
        // DockGameTests.aParcelWithNoGroupDoesNotLeaveTheNode); a random node id here was standing
        // in for "somewhere" and made every one of them a crossing.
        dock.setDefaultDestination(UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId()),
                DockGroupDirectory.DEFAULT_GROUP_ID);
        return (TowerCoreBlockEntity) level.getBlockEntity(corePos);
    }

    private static boolean insertParcel(ServerLevel level, BlockPos pos) {
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        return handler != null
                && handler.insertItem(0, new ItemStack(ModItems.REMOTE_PACKAGE.get()), false).isEmpty();
    }

    private static TowerSystem.Member tower(BlockPos base, int radius, int devices, boolean running) {
        return tower(base, radius, devices, running, true);
    }

    /** A member with its carrying switch spelled out, for the cases that turn it off. */
    private static TowerSystem.Member tower(BlockPos base, int radius, int devices, boolean running,
                                            boolean carrying) {
        return new TowerSystem.Member(new TowerSystem.TowerId(TEST_DIMENSION, base.asLong()), base,
                radius, devices, running, carrying);
    }

    private static TowerSystem.Device device(BlockPos pos) {
        return new TowerSystem.Device(pos, TEST_DIMENSION);
    }

    /**
     * The dimension the arithmetic cases are exercised in.
     *
     * <p>Named rather than read from a helper so those cases need no level at all, and every case
     * that compares against the live world reads the level's own key instead.
     */
    private static final String TEST_DIMENSION = "minecraft:overworld";

    private TowerActivationGameTests() {
    }
}
