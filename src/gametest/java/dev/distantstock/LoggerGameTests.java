package dev.distantstock;

import dev.distantstock.block.LoggerBlock;
import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.event.EventRegistry;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.WorldIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class LoggerGameTests {

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void escalatedAcknowledgedWarningSoundsAgain(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        var logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID frequency = UUID.randomUUID();
        logger.setCreateFrequency(frequency);
        var registry = EventRegistry.get(level.getServer());
        String source = "escalation-test-" + UUID.randomUUID();
        var warning = registry.raise(EventRegistry.Severity.WARN, "NETWORK", "test", source,
                "warning", frequency, null, 1);
        registry.markPrinted(warning.id(), 2);
        h.assertTrue(logger.nextUnacknowledgedAlarm() == null, "printed WARN was not silenced");
        var error = registry.raise(EventRegistry.Severity.ERROR, "NETWORK", "test", source,
                "error", frequency, null, 3);
        h.assertTrue(!error.acknowledged() && !error.printed(),
                "ERROR escalation inherited the old WARN silence/print latch");
        h.assertTrue(logger.nextUnacknowledgedAlarm() != null,
                "ERROR escalation did not enter the audible alarm queue");
        registry.clear("NETWORK", "test", source, 4);
        h.succeed();
    }


    @GameTest(template = "empty", timeoutTicks = 40)
    public static void severityThresholdAlsoControlsAlarmQueues(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID network = UUID.randomUUID();
        logger.setCreateFrequency(network);
        logger.setMinimumSeverity(EventRegistry.Severity.ERROR);

        EventRegistry registry = EventRegistry.get(level.getServer());
        String source = "threshold-" + UUID.randomUUID();
        EventRegistry.Record warn = registry.raise(EventRegistry.Severity.WARN,
                "THRESHOLD_WARN", "test", source, "warning below threshold", network, null,
                System.currentTimeMillis());

        h.assertFalse(logger.rows().stream().anyMatch(row -> row.id().equals(warn.id())),
                "ERROR-only logger still displayed a WARN");
        h.assertTrue(logger.nextUnacknowledgedAlarm() == null,
                "hidden WARN still entered the buzzer queue");
        h.assertTrue(logger.nextPrintableAlarm() == null,
                "hidden WARN still entered the print queue");
        h.assertTrue(logger.status() == LoggerBlock.Status.NORMAL,
                "hidden WARN still changed the front status lamp");

        registry.clear("THRESHOLD_WARN", "test", source, System.currentTimeMillis());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void unloadedCreateNetworkIsUnknownNotErrorAlarm(GameTestHelper h) {
        var level = h.getLevel();
        UUID localNode = TranserverBridge.localNodeUuid();
        if (localNode == null) {
            h.succeed();
            return;
        }

        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID missingFrequency = UUID.randomUUID();
        RemoteNetworkId network = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, localNode,
                WorldIdentity.get(level), level.dimension().location().toString(), missingFrequency);
        logger.setBinding(network, null);

        h.runAfterDelay(130, () -> {
            h.assertTrue(logger.status() == LoggerBlock.Status.OFFLINE,
                    "unloaded network did not settle to the neutral OFFLINE/unknown display");
            h.assertTrue(EventRegistry.get(level.getServer()).active(
                            EventRegistry.Codes.NETWORK_OFFLINE, "network", "network:" + missingFrequency).isEmpty(),
                    "unloaded/unknown Create network still raised a false ERROR alarm");
            h.assertTrue(logger.nextUnacknowledgedAlarm() == null,
                    "unknown network entered the audible alarm queue");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void loggerIsARealCreateDisplayLinkSource(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        boolean found = com.simibubi.create.api.behaviour.display.DisplaySource
                .getAll(h.getLevel(), pos).stream()
                .anyMatch(source -> source instanceof dev.distantstock.display.LoggerDisplaySource);
        h.assertTrue(found,
                "Create Display Link 没有把远仓日志台识别成数据源");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void loggerScopesEventsAndDrivesItsStatusLamp(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(logger != null, "logger block entity was not created");

        UUID networkA = UUID.randomUUID();
        UUID networkB = UUID.randomUUID();
        logger.setCreateFrequency(networkA);
        h.assertTrue(logger.installPaperRoll(), "logger test fixture could not load paper");
        EventRegistry registry = EventRegistry.get(level.getServer());
        String prefix = "logger-test-" + UUID.randomUUID();

        // Another network must not affect this panel.
        registry.raise(EventRegistry.Severity.ERROR, "OTHER", "test", prefix + "-b", "",
                networkB, null, 1);
        h.runAfterDelay(25, () -> {
            h.assertTrue(level.getBlockState(pos).getValue(LoggerBlock.STATUS) == LoggerBlock.Status.NORMAL,
                    "an event from another Create network changed this logger's status");

            registry.raise(EventRegistry.Severity.WARN, "WARN_A", "test", prefix + "-warn", "",
                    networkA, null, 2);
            h.runAfterDelay(25, () -> {
                h.assertTrue(level.getBlockState(pos).getValue(LoggerBlock.STATUS) == LoggerBlock.Status.WARN,
                        "a scoped WARN did not turn the logger orange");

                registry.raise(EventRegistry.Severity.ERROR, "ERR_A", "test", prefix + "-error", "",
                        networkA, null, 3);
                h.runAfterDelay(25, () -> {
                    h.assertTrue(level.getBlockState(pos).getValue(LoggerBlock.STATUS) == LoggerBlock.Status.ERROR,
                            "a scoped ERROR did not turn the logger red");
                    registry.clear("ERR_A", "test", prefix + "-error", 4);
                    registry.clear("WARN_A", "test", prefix + "-warn", 5);
                    h.runAfterDelay(25, () -> {
                        h.assertTrue(level.getBlockState(pos).getValue(LoggerBlock.STATUS) == LoggerBlock.Status.NORMAL,
                                "cleared scoped events left the logger alarmed");
                        registry.clear("OTHER", "test", prefix + "-b", 6);
                        h.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void boundLoggerStillShowsTowerInfrastructureFaults(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(logger != null, "logger block entity was not created");

        UUID network = UUID.randomUUID();
        logger.setCreateFrequency(network);
        h.assertTrue(logger.installPaperRoll(), "logger test fixture could not load paper");

        BlockPos dockPos = h.absolutePos(new BlockPos(3, 1, 3));
        level.setBlock(dockPos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (dev.distantstock.block.DockBlockEntity) level.getBlockEntity(dockPos);
        h.assertTrue(dock != null, "tower-scope test dock was not created");
        dock.setExport(network);

        BlockPos towerPos = h.absolutePos(new BlockPos(5, 1, 5));
        level.setBlock(towerPos, ModBlocks.TOWER_CORE.get().defaultBlockState(), 3);
        for (int i = 1; i <= dev.distantstock.block.TowerTier.I.couplers(); i++) {
            level.setBlock(towerPos.above(i), ModBlocks.TOWER_COUPLER.get().defaultBlockState(), 3);
        }
        level.setBlock(towerPos.above(dev.distantstock.block.TowerTier.I.couplers() + 1),
                ModBlocks.ETHER_RESONATOR.get().defaultBlockState(), 3);

        EventRegistry registry = EventRegistry.get(level.getServer());
        String source = EventRegistry.blockSource(level, towerPos);

        h.runAfterDelay(45, () -> {
            EventRegistry.Record stopped = registry.active(EventRegistry.Codes.TOWER_STOPPED,
                    "tower", source).orElse(null);
            h.assertTrue(stopped != null,
                    "live AlarmSampler did not raise TOWER_STOPPED for the built stopped tower");
            h.assertTrue(level.getBlockState(pos).getValue(LoggerBlock.STATUS) == LoggerBlock.Status.ERROR,
                    "a bound logger hid the tower-stop alarm for its network's covered dock");
            h.assertTrue(logger.rows().stream().anyMatch(row -> row.id().equals(stopped.id())),
                    "tower-stop alarm did not appear in the bound logger rows");
            dev.distantstock.event.AlarmSampler.clearTower(level.getServer(), level, towerPos);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void loggerCanClearScopeAndShowAllEvents(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID networkA = UUID.randomUUID();
        UUID networkB = UUID.randomUUID();
        logger.setCreateFrequency(networkA);
        EventRegistry registry = EventRegistry.get(level.getServer());
        String source = "all-scope-" + UUID.randomUUID();
        EventRegistry.Record other = registry.raise(EventRegistry.Severity.INFO,
                "OTHER_NETWORK", "test", source, "", networkB, null, 1);

        h.assertFalse(logger.rows().stream().anyMatch(row -> row.id().equals(other.id())),
                "scoped logger saw another network before scope was cleared");
        logger.clearBinding();
        h.assertTrue(logger.createFrequency() == null,
                "clearBinding left a Create frequency on the logger");
        h.assertTrue(logger.rows().stream().anyMatch(row -> row.id().equals(other.id())),
                "all-events logger still hid an event from another network");
        registry.clear("OTHER_NETWORK", "test", source, 2);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void networkLockNoticeIsHistoryOnlyAndNeverPrintable(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID network = UUID.randomUUID();
        logger.setCreateFrequency(network);
        EventRegistry registry = EventRegistry.get(level.getServer());
        String source = "network:" + network;

        long now = System.currentTimeMillis();
        EventRegistry.Record notice = registry.raise(EventRegistry.Severity.INFO,
                EventRegistry.Codes.NETWORK_LOCKED, "network", source,
                "operator locked the Create logistics network", network, null, now);
        registry.clear(EventRegistry.Codes.NETWORK_LOCKED, "network", source, now);

        h.assertTrue(logger.rows().stream().anyMatch(row -> row.id().equals(notice.id()) && !row.active()),
                "completed lock operation did not remain in logger history");
        h.assertTrue(logger.nextPrintableAlarm() == null,
                "network lock operation entered the incident-slip print queue");
        h.assertTrue(logger.status() == LoggerBlock.Status.NORMAL,
                "network lock operation changed the logger into WARN/ERROR state");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void loggerFeedsHalfTicketForAlarmThenFinishesPrint(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID network = UUID.randomUUID();
        logger.setCreateFrequency(network);
        h.assertTrue(logger.installPaperRoll(), "logger test fixture could not load paper");

        EventRegistry registry = EventRegistry.get(level.getServer());
        String source = "paper-feed-" + UUID.randomUUID();
        EventRegistry.Record event = registry.raise(EventRegistry.Severity.ERROR,
                "PAPER_FEED_TEST", "test", source, "", network, null, 1);

        // Give the newly placed block entity one full server-ticker registration window before
        // asserting the visual endpoint. Optional addons can shift GameTest block-entity tick order
        // by a handful of ticks; the actual paper feed still takes only 10 ticks once the alarm is seen.
        h.runAfterDelay(24, () -> {
            float half = logger.receiptExtension(0);
            var state = level.getBlockState(pos);
            var next = logger.nextPrintableAlarm();
            var stillActive = registry.find(event.id()).orElse(null);
            h.assertTrue(half >= .45f && half <= .51f,
                    "active alarm did not settle at a half-ticket: " + half
                            + " status=" + (state.hasProperty(dev.distantstock.block.LoggerBlock.STATUS)
                            ? state.getValue(dev.distantstock.block.LoggerBlock.STATUS) : null)
                            + " next=" + (next == null ? "null" : next.code())
                            + " event=" + (stillActive == null ? "missing"
                            : (stillActive.active() + "/" + stillActive.acknowledged()))
                            + " paper=" + logger.paperRemaining());
            java.util.concurrent.atomic.AtomicReference<net.minecraft.world.item.ItemStack> printed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            h.assertTrue(dev.distantstock.net.LoggerActionC2S.printAndAcknowledge(
                            logger, registry, event.id(), printed::set, 20),
                    "logger could not print the pending alarm");
            h.runAfterDelay(8, () -> {
                float full = logger.receiptExtension(0);
                h.assertTrue(full > .9f,
                        "printed ticket did not finish feeding out: " + full);
                registry.clear("PAPER_FEED_TEST", "test", source, 30);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void loggerSnapshotAlwaysKeepsActiveAlarms(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID network = UUID.randomUUID();
        UUID distantNetwork = UUID.randomUUID();
        logger.setCreateFrequency(network);
        h.assertTrue(logger.status() == LoggerBlock.Status.NORMAL,
                "paper empty incorrectly promoted the logger into a process WARN state");
        h.assertTrue("PE".equals(logger.displayCode()),
                "an empty logger did not show PE on the two status tubes");
        EventRegistry registry = EventRegistry.get(level.getServer());
        String prefix = "snapshot-" + UUID.randomUUID();

        EventRegistry.Record active = registry.raise(EventRegistry.Severity.WARN,
                "OLD_ACTIVE", "test", prefix + "-active", "", network, null, 1);
        for (int i = 0; i < LoggerBlockEntity.SNAPSHOT_LIMIT + 20; i++) {
            String source = prefix + "-history-" + i;
            registry.raise(EventRegistry.Severity.INFO, "HISTORY", "test", source, "",
                    network, null, 100 + i);
            registry.clear("HISTORY", "test", source, 200 + i);
        }
        var rows = logger.rows();
        h.assertTrue(rows.size() == LoggerBlockEntity.SNAPSHOT_LIMIT,
                "logger snapshot does not respect its row limit");
        h.assertTrue(rows.stream().anyMatch(row -> row.id().equals(active.id()) && row.active()),
                "recent history pushed an old active alarm out of the logger snapshot");

        registry.clear("OLD_ACTIVE", "test", prefix + "-active", 999);
        h.succeed();
    }



    @GameTest(template = "empty", timeoutTicks = 120)
    public static void ackSilencesWithoutPaperAndPrintClearsAcLatch(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID network = UUID.randomUUID();
        UUID distantNetwork = UUID.randomUUID();
        logger.setCreateFrequency(network);

        EventRegistry registry = EventRegistry.get(level.getServer());
        String source = "print-" + UUID.randomUUID();
        EventRegistry.Record event = registry.raise(EventRegistry.Severity.ERROR,
                EventRegistry.Codes.PARCEL_QUARANTINED, "parcel", source, "ownership conflict",
                network, distantNetwork, 100);

        h.assertTrue(dev.distantstock.block.SignalPanelBlockEntity.eventLevel(
                        registry.activeForFrequency(network)) == dev.distantstock.block.LampState.FATAL,
                "unacknowledged ERROR was not flashing red before printing");

        java.util.concurrent.atomic.AtomicReference<net.minecraft.world.item.ItemStack> printed =
                new java.util.concurrent.atomic.AtomicReference<>();
        h.assertTrue(logger.paperRemaining() == 0, "a fresh logger unexpectedly started with paper");
        boolean noPaper = dev.distantstock.net.LoggerActionC2S.printAndAcknowledge(
                logger, registry, event.id(), printed::set, 200);
        h.assertFalse(noPaper, "an empty logger printed without a replacement roll");
        h.assertTrue(printed.get() == null, "out-of-paper printing still produced a receipt");
        h.assertFalse(registry.find(event.id()).orElseThrow().acknowledged(),
                "failed print unexpectedly acknowledged/silenced the alarm");

        // Physical ACK is independent from paper: silence first, AC remains until the incident
        // slip is actually printed.
        h.assertTrue(registry.acknowledge(event.id(), 205),
                "out-of-paper alarm could not be acknowledged/silenced");
        logger.operatorEventChanged();
        EventRegistry.Record silenced = registry.find(event.id()).orElseThrow();
        h.assertTrue(silenced.acknowledged() && !silenced.printed(),
                "ACK did not produce the acknowledged-but-unprinted state");
        h.assertTrue(logger.nextUnacknowledgedAlarm() == null,
                "ACK did not remove the event from the buzzer queue");
        h.assertTrue(logger.nextPrintableAlarm() != null,
                "ACK incorrectly removed the event from the print queue");
        h.assertTrue(logger.status() == LoggerBlock.Status.ERROR_ACK,
                "ACK did not move the logger to steady acknowledged ERROR");
        h.assertTrue("AC".equals(logger.displayCode()),
                "acknowledged-but-unprinted ERROR did not latch AC");
        h.assertTrue(dev.distantstock.block.SignalPanelBlockEntity.eventLevel(
                        registry.activeForFrequency(network)) == dev.distantstock.block.LampState.FATAL_ACK,
                "ACK did not change the shared ERROR alarm from flashing to steady red");

        h.assertTrue(logger.installPaperRoll(), "logger refused a replacement paper roll while empty");
        h.assertTrue(logger.paperRemaining() == LoggerBlockEntity.PAPER_CAPACITY,
                "replacement roll did not load a full paper capacity");
        h.assertFalse(logger.installPaperRoll(), "logger accepted a second roll before the first was empty");

        boolean ok = dev.distantstock.net.LoggerActionC2S.printAndAcknowledge(
                logger, registry, event.id(), printed::set, 210);
        h.assertTrue(ok, "logger refused to print a visible active ERROR");
        h.assertTrue(logger.paperRemaining() == LoggerBlockEntity.PAPER_CAPACITY - 1,
                "one receipt did not consume exactly one sheet from the roll");

        var receipt = dev.distantstock.item.EventReceiptItem.read(printed.get()).orElse(null);
        h.assertTrue(receipt != null, "printed item did not contain an event receipt");
        h.assertTrue(receipt.eventId().equals(event.id())
                        && receipt.code().equals(EventRegistry.Codes.PARCEL_QUARANTINED)
                        && receipt.sourceId().equals(source)
                        && network.equals(receipt.createFrequency())
                        && distantNetwork.equals(receipt.distantNetworkId()),
                "printed receipt lost event identity");
        h.assertTrue(registry.find(event.id()).orElseThrow().acknowledged(),
                "printing did not acknowledge the event");
        h.assertTrue(registry.find(event.id()).orElseThrow().printed(),
                "printing did not mark the incident slip complete");
        h.assertTrue(dev.distantstock.block.SignalPanelBlockEntity.eventLevel(
                        registry.activeForFrequency(network)) == dev.distantstock.block.LampState.FATAL_ACK,
                "printing did not change the shared ERROR alarm from flashing to steady red");
        h.assertTrue(logger.status() == LoggerBlock.Status.ERROR_ACK,
                "printing did not move the logger from flashing ERROR to acknowledged ERROR");
        h.assertTrue("ER".equals(logger.displayCode()),
                "printing did not clear AC while the underlying ERROR remained active");
        h.assertTrue(level.getBlockState(pos).getValue(LoggerBlock.PRINTED),
                "successful print did not show the logger receipt");

        java.util.concurrent.atomic.AtomicInteger duplicate = new java.util.concurrent.atomic.AtomicInteger();
        h.assertFalse(dev.distantstock.net.LoggerActionC2S.printAndAcknowledge(
                        logger, registry, event.id(), stack -> duplicate.incrementAndGet(), 300),
                "an already printed event was printed and acknowledged twice");
        h.assertTrue(duplicate.get() == 0, "duplicate printing produced a second receipt");

        registry.clear(EventRegistry.Codes.PARCEL_QUARANTINED, "parcel", source, 400);
        h.runAfterDelay(65, () -> {
            h.assertFalse(level.getBlockState(pos).getValue(LoggerBlock.PRINTED),
                    "printed receipt stayed on the logger after its display interval");
            h.succeed();
        });
    }

    /**
     * The sneak half of the terminal gesture belongs to the item, not to the block.
     *
     * <p>Vanilla's {@code ServerPlayerGameMode#useItemOn} never calls {@code BlockState#useItemOn}
     * while the player sneaks with an item in hand, so a handler on the block is unreachable no
     * matter how correct it looks. That is where this gesture used to live, and the effect was
     * that a placed logger could not be unbound at all. The engine's dispatch cannot be driven
     * from here, but the handler the sneak-click actually lands on can be: this fails if the
     * gesture is moved back onto the block, or deleted as dead code.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sneakWithTerminalCanUnbindAPlacedLogger(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        logger.setCreateFrequency(UUID.randomUUID());
        h.assertTrue(logger.createFrequency() != null, "logger was not bound to begin with");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack terminal = new ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

        player.setShiftKeyDown(false);
        terminal.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        h.assertTrue(logger.createFrequency() != null,
                "a plain click unbound the logger; binding is the block's gesture to keep");

        player.setShiftKeyDown(true);
        terminal.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        h.assertTrue(logger.createFrequency() == null,
                "a sneak-click with the terminal did not restore the logger to all events");
        h.succeed();
    }

    private LoggerGameTests() {
    }
}
