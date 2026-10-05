package dev.distantstock;

import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.NixieClockBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class NixieClockGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void formatsMinecraftTimeInBothModes(GameTestHelper h) {
        h.assertTrue("06:00".equals(NixieClockBlockEntity.formatTime(0, true)), "0 ticks should be 06:00");
        h.assertTrue("12:00".equals(NixieClockBlockEntity.formatTime(6000, true)), "6000 ticks should be noon");
        h.assertTrue("00:00".equals(NixieClockBlockEntity.formatTime(18000, true)), "18000 ticks should be midnight");
        h.assertTrue("12:00".equals(NixieClockBlockEntity.formatTime(18000, false)), "12h midnight should be 12:00");
        h.assertTrue("1:30".equals(NixieClockBlockEntity.formatTime(19500, false)), "12h conversion should keep minutes");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void matchesCreateCuckooChimeWindows(GameTestHelper h) {
        h.assertTrue(NixieClockBlockEntity.chimeSlot(6000) == 0, "noon chime window missing");
        h.assertTrue(NixieClockBlockEntity.chimeSlot(6080) == 0, "noon five-minute window too short");
        h.assertTrue(NixieClockBlockEntity.chimeSlot(6100) == -1, "noon chime window too long");
        // 18:32 is 12,000 ticks after 06:00 plus roughly 533 ticks into the hour.
        h.assertTrue(NixieClockBlockEntity.chimeSlot(12534) == 1, "dusk chime window missing");
        h.assertTrue(NixieClockBlockEntity.chimeSlot(12600) == -1, "dusk chime window too long");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void storesDyeAndHourMode(GameTestHelper h) {
        BlockPos relative = new BlockPos(2, 1, 2);
        h.setBlock(relative, ModBlocks.NIXIE_CLOCK.get().defaultBlockState());
        BlockPos absolute = h.absolutePos(relative);
        h.assertTrue(h.getLevel().getBlockEntity(absolute) instanceof NixieClockBlockEntity,
                "nixie clock did not create its block entity");
        NixieClockBlockEntity clock = (NixieClockBlockEntity) h.getLevel().getBlockEntity(absolute);
        clock.setColor(DyeColor.CYAN);
        clock.toggleHourMode();
        clock.toggleMuted();
        h.assertTrue(clock.color() == DyeColor.CYAN, "clock did not retain dye colour");
        h.assertTrue(!clock.twentyFourHour(), "clock did not toggle into 12-hour mode");
        h.assertTrue(clock.muted(), "clock did not enter muted state");
        h.succeed();
    }

    private NixieClockGameTests() {
    }
}
