package dev.distantstock;

import dev.distantstock.block.FlapClockBlockEntity;
import dev.distantstock.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class FlapClockGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void isSingleLineTwoCellLogicalBoard(GameTestHelper h) {
        BlockPos relative = new BlockPos(2, 1, 2);
        h.setBlock(relative, ModBlocks.FLAP_CLOCK.get().defaultBlockState());
        FlapClockBlockEntity clock = (FlapClockBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(relative));
        h.assertTrue(clock != null, "flap clock did not create its block entity");
        clock.updateControllerStatus();
        h.assertTrue(clock.xSize == 2, "flap clock must use two logical Create widths for HH:MM");
        h.assertTrue(clock.ySize == 1, "flap clock logical height changed");
        h.assertTrue(clock.getLines().size() == 1, "flap clock should render one real flap line, not two");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void storesColourAndHourMode(GameTestHelper h) {
        BlockPos relative = new BlockPos(2, 1, 2);
        h.setBlock(relative, ModBlocks.FLAP_CLOCK.get().defaultBlockState());
        FlapClockBlockEntity clock = (FlapClockBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(relative));
        h.assertTrue(clock != null, "flap clock did not create its block entity");
        clock.setDisplayColour(DyeColor.LIGHT_BLUE);
        clock.toggleHourMode();
        clock.toggleMuted();
        h.assertTrue(clock.displayColour() == DyeColor.LIGHT_BLUE, "flap colour did not change");
        h.assertTrue(!clock.twentyFourHour(), "flap clock did not enter 12-hour mode");
        h.assertTrue(clock.muted(), "flap clock did not enter muted state");
        h.succeed();
    }

    private FlapClockGameTests() {}
}
