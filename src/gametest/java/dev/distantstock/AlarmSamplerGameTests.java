package dev.distantstock;

import dev.distantstock.event.AlarmSampler;
import dev.distantstock.event.EventRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class AlarmSamplerGameTests {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void towerAlarmReportsOneRootCauseAndClearsOnRecovery(GameTestHelper h) {
        EventRegistry events = new EventRegistry();
        String source = "minecraft:overworld@10,64,10";

        AlarmSampler.reportTower(events, source, true, false, false,
                4000, 250, 2, 10);
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_STOPPED, "tower", source).isPresent(),
                "stopped built tower did not raise TOWER_STOPPED");
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_OVERSTRESSED, "tower", source).isEmpty(),
                "ordinary stopped tower also raised overstress");

        AlarmSampler.reportTower(events, source, true, false, true,
                4000, 250, 2, 20);
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_STOPPED, "tower", source).isEmpty(),
                "overstressed tower kept the less specific stopped alarm active");
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_OVERSTRESSED, "tower", source).isPresent(),
                "overstressed tower did not raise TOWER_OVERSTRESSED");

        AlarmSampler.reportTower(events, source, true, true, false,
                4000, 250, 2, 30);
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_STOPPED, "tower", source).isEmpty()
                        && events.active(EventRegistry.Codes.TOWER_OVERSTRESSED, "tower", source).isEmpty(),
                "healthy running tower did not clear mechanical alarms");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void etherAlarmRequiresWorkingLoadedTowerAndDoesNotCountSeconds(GameTestHelper h) {
        EventRegistry events = new EventRegistry();
        String source = "minecraft:overworld@20,64,20";

        // Empty but carrying nothing: no operator-facing outage yet.
        AlarmSampler.reportTower(events, source, true, true, false, 0, 250, 0, 1);
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_NO_ETHER, "tower", source).isEmpty(),
                "idle empty tower raised a no-ether outage");

        AlarmSampler.reportTower(events, source, true, true, false, 0, 250, 3, 2);
        var first = events.active(EventRegistry.Codes.TOWER_NO_ETHER, "tower", source).orElse(null);
        h.assertTrue(first != null, "loaded tower unable to pay for one parcel did not alarm");
        for (int i = 0; i < 100; i++) {
            AlarmSampler.reportTower(events, source, true, true, false, 0, 250, 3, 3 + i);
        }
        var same = events.active(EventRegistry.Codes.TOWER_NO_ETHER, "tower", source).orElseThrow();
        h.assertTrue(same.id().equals(first.id()) && same.count() == 1,
                "level-triggered tower alarm counted sampling seconds as repeated incidents");

        AlarmSampler.reportTower(events, source, true, true, false, 250, 250, 3, 200);
        h.assertTrue(events.active(EventRegistry.Codes.TOWER_NO_ETHER, "tower", source).isEmpty(),
                "refilled tower did not clear TOWER_NO_ETHER");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void linkAlarmOnlyStartsAfterARealAttachmentAndClearsOnRecovery(GameTestHelper h) {
        EventRegistry events = new EventRegistry();
        AlarmSampler.stop();

        AlarmSampler.reportLink(events, true, false, false, "", 1);
        h.assertTrue(events.active(EventRegistry.Codes.LINK_OFFLINE, "link", "transerver").isEmpty(),
                "a Transerver service that was never attached produced a false outage");

        AlarmSampler.reportLink(events, true, true, true, "", 2);
        AlarmSampler.reportLink(events, true, true, false, "socket closed", 3);
        h.assertTrue(events.active(EventRegistry.Codes.LINK_OFFLINE, "link", "transerver").isPresent(),
                "a previously attached transport going down did not raise LINK_OFFLINE");

        AlarmSampler.reportLink(events, true, false, false, "service disappeared", 4);
        h.assertTrue(events.active(EventRegistry.Codes.LINK_OFFLINE, "link", "transerver").isPresent(),
                "a lost API attachment cleared an existing transport outage");

        AlarmSampler.reportLink(events, true, true, true, "", 5);
        h.assertTrue(events.active(EventRegistry.Codes.LINK_OFFLINE, "link", "transerver").isEmpty(),
                "recovered transport did not clear LINK_OFFLINE");
        AlarmSampler.stop();
        h.succeed();
    }

    private AlarmSamplerGameTests() {
    }
}
