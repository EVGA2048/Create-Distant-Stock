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
     * The promise: a world without towers behaves exactly as it did before towers existed.
     *
     * <p>No case in this suite builds a tower that is both complete and turning, so this is the
     * real world state too, with nothing pinned: the dock ships a parcel through the ordinary route
     * machinery, exactly as it did before this stage.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void withoutTowersADockStillShips(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(pos);
        dock.setExport(UUID.randomUUID());
        dock.setDefaultDestination(UUID.randomUUID(), DockGroupDirectory.DEFAULT_GROUP_ID);
        h.assertTrue(dock.canSend(), "a dock in a world without towers cannot send | gated="
                + dev.distantstock.routing.TowerActivation.snapshot().gated(level.dimension())
                + " running=" + dev.distantstock.block.LoadedTowers.all().stream()
                        .filter(dev.distantstock.block.TowerCoreBlockEntity::isRunning).count()
                + " tiers=" + dev.distantstock.block.LoadedTowers.all().stream()
                        .map(be -> String.valueOf(be.tier())).toList());

        h.assertTrue(insertParcel(level, pos), "the parcel did not enter the outgoing slot");
        h.runAfterDelay(140, () -> {
            h.assertTrue(dock.displayedStack().isEmpty(),
                    "the parcel never left a dock that no tower has to carry");
            h.succeed();
        });
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
        TowerActivation.Snapshot snapshot = TowerActivation.of(List.of(tower), List.of(inside, outside));
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
        TowerActivation.Snapshot snapshot = TowerActivation.of(List.of(tower), new ArrayList<>(backward));
        h.assertTrue(snapshot.active(h.getLevel().dimension(), near.pos()), "the closest device is off");
        h.assertFalse(snapshot.active(h.getLevel().dimension(), far.pos()),
                "a device past the budget is on");
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
        dock.setImport("");
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
                TowerActivation.unpinDevices();
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
                        TowerActivation.unpinDevices();
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
        dock.setDefaultDestination(UUID.randomUUID(), DockGroupDirectory.DEFAULT_GROUP_ID);
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
