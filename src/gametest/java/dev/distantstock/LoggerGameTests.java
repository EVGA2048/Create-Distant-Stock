package dev.distantstock;

import dev.distantstock.block.LoggerBlock;
import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.event.EventRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class LoggerGameTests {

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

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void loggerSnapshotAlwaysKeepsActiveAlarms(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(pos);
        UUID network = UUID.randomUUID();
        UUID distantNetwork = UUID.randomUUID();
        logger.setCreateFrequency(network);
        h.assertTrue(logger.status() == LoggerBlock.Status.WARN,
                "an empty logger still reported NORMAL instead of paper-empty warning");
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
    public static void printingEventCreatesReceiptAndAcknowledgesAlarm(GameTestHelper h) {
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
                "out-of-paper printing acknowledged/silenced the alarm");

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
        h.assertTrue(dev.distantstock.block.SignalPanelBlockEntity.eventLevel(
                        registry.activeForFrequency(network)) == dev.distantstock.block.LampState.FATAL_ACK,
                "printing did not change the shared ERROR alarm from flashing to steady red");
        h.assertTrue(logger.status() == LoggerBlock.Status.ERROR_ACK,
                "printing did not move the logger from flashing ERROR to acknowledged ERROR");
        h.assertTrue("AC".equals(logger.displayCode()),
                "acknowledged ERROR did not switch the two nixies to AC");
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

    private LoggerGameTests() {
    }
}
