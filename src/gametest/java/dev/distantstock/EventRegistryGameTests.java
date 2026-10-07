package dev.distantstock;

import dev.distantstock.event.EventRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class EventRegistryGameTests {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void activeEventDeduplicatesAcknowledgesClearsAndCanReoccur(GameTestHelper h) {
        EventRegistry registry = new EventRegistry();
        String source = "minecraft:overworld@1,2,3";
        UUID frequency = UUID.randomUUID();
        UUID distantNetwork = UUID.randomUUID();

        EventRegistry.Record first = registry.raise(EventRegistry.Severity.WARN,
                EventRegistry.Codes.DOCK_NO_ADDRESS, "dock", source, "first",
                frequency, distantNetwork, 10);
        EventRegistry.Record repeated = registry.raise(EventRegistry.Severity.INFO,
                EventRegistry.Codes.DOCK_NO_ADDRESS, "dock", source, "repeat", 20);
        h.assertTrue(first.id().equals(repeated.id()),
                "one continuing fault created a second event row");
        h.assertTrue(repeated.count() == 2,
                "one continuing fault did not increment its occurrence count");
        h.assertTrue(repeated.severity() == EventRegistry.Severity.WARN,
                "a later INFO report downgraded an active WARN");
        h.assertTrue(registry.size() == 1, "deduplication still grew the event history");
        h.assertTrue(registry.activeForFrequency(frequency).size() == 1,
                "Create-network alarm scope did not find its active event");
        h.assertTrue(registry.activeForDistantNetwork(distantNetwork).size() == 1,
                "Distant-network alarm scope did not find its active event");

        h.assertTrue(registry.acknowledge(first.id(), 30), "active event could not be acknowledged");
        EventRegistry.Record acknowledged = registry.find(first.id()).orElseThrow();
        h.assertTrue(acknowledged.active() && acknowledged.acknowledged(),
                "ACK incorrectly cleared the active condition");
        h.assertFalse(acknowledged.printed(),
                "ACK incorrectly marked the incident slip as printed");
        h.assertTrue(acknowledged.acknowledgedAt() == 30,
                "ACK timestamp was not recorded");

        h.assertTrue(registry.markPrinted(first.id(), 35),
                "an acknowledged event could not be marked printed");
        EventRegistry.Record printed = registry.find(first.id()).orElseThrow();
        h.assertTrue(printed.acknowledged() && printed.printed() && printed.printedAt() == 35,
                "printing did not preserve ACK and record its own timestamp");

        h.assertTrue(registry.clear(EventRegistry.Codes.DOCK_NO_ADDRESS, "dock", source, 40),
                "active event could not be cleared");
        EventRegistry.Record cleared = registry.find(first.id()).orElseThrow();
        h.assertFalse(cleared.active(), "CLEAR left the event active");
        h.assertTrue(cleared.acknowledged() && cleared.printed(),
                "CLEAR forgot the operator ACK/print state");
        h.assertTrue(cleared.clearedAt() == 40, "CLEAR timestamp was not recorded");

        EventRegistry.Record next = registry.raise(EventRegistry.Severity.ERROR,
                EventRegistry.Codes.DOCK_NO_ADDRESS, "dock", source, "again", 50);
        h.assertFalse(next.id().equals(first.id()),
                "a condition that reoccurred after CLEAR reused the old event id");
        h.assertFalse(next.acknowledged(), "a new occurrence inherited the old ACK");
        h.assertTrue(registry.size() == 2, "cleared history was discarded when the fault reoccurred");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void eventSaveRoundTripKeepsAlarmStateAndHistoryIsBounded(GameTestHelper h) {
        EventRegistry registry = new EventRegistry();
        EventRegistry.Record keep = registry.raise(EventRegistry.Severity.ERROR,
                EventRegistry.Codes.ADDRESS_CONFLICT, "dock", "save-test", "detail", 100);
        registry.acknowledge(keep.id(), 110);
        registry.markPrinted(keep.id(), 115);
        registry.clear(EventRegistry.Codes.ADDRESS_CONFLICT, "dock", "save-test", 120);

        CompoundTag saved = registry.save(new CompoundTag(), h.getLevel().registryAccess());
        EventRegistry loaded = EventRegistry.load(saved, h.getLevel().registryAccess());
        EventRegistry.Record copy = loaded.find(keep.id()).orElse(null);
        h.assertTrue(copy != null, "event vanished during save/load");
        h.assertTrue(!copy.active() && copy.acknowledged() && copy.printed()
                        && copy.printedAt() == 115 && copy.clearedAt() == 120,
                "event state changed during save/load");

        for (int i = 0; i < EventRegistry.MAX_HISTORY_RECORDS + 40; i++) {
            String source = "bounded-" + i;
            loaded.raise(EventRegistry.Severity.INFO, "TEST", "test", source, "", 1_000L + i);
            loaded.clear("TEST", "test", source, 2_000L + i);
        }
        long history = loaded.recent(Integer.MAX_VALUE).stream().filter(event -> !event.active()).count();
        h.assertTrue(history <= EventRegistry.MAX_HISTORY_RECORDS,
                "event history grew past its hard limit: " + history);

        // Active alarms are not history and must never disappear just because the log window is full.
        EventRegistry active = new EventRegistry();
        for (int i = 0; i < EventRegistry.MAX_HISTORY_RECORDS + 40; i++) {
            active.raise(EventRegistry.Severity.ERROR, "ACTIVE", "test", "active-" + i,
                    "", 10_000L + i);
        }
        h.assertTrue(active.active().size() == EventRegistry.MAX_HISTORY_RECORDS + 40,
                "the history limit silently evicted active alarms");
        h.succeed();
    }

    private EventRegistryGameTests() {
    }
}
