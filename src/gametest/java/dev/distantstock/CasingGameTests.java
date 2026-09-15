package dev.distantstock;

import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.TowerCasingBlock;
import dev.distantstock.config.StockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The distant casing's window.
 *
 * <p>The window is a block state, not a render decision, because the texture is chosen while the
 * chunk is baked and that code never sees a world. So what is tested here is the state: that a
 * signal spreads along connected casings, that it stops at the configured range, and that it goes
 * away again when the signal does.
 *
 * <p>The arena is 8x8x8, so the run is short and the range cap is driven through the config
 * instead. Testing the default of 32 would need a 34-block line, and the property under test is
 * that the cap is read and honoured, not that it happens to be 32.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class CasingGameTests {
    private static final int Y = 2;
    private static final int Z = 2;
    /** The redstone block. Casings start one further along and run to the wall of the arena. */
    private static final int SOURCE_X = 1;
    private static final int FIRST_X = 2;
    private static final int LAST_X = 7;
    /** The wall of the long arena, and past the default range of 32 by enough to be unambiguous. */
    private static final int LONG_RUN = 39;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void signalSpreadsAlongConnectedCasings(GameTestHelper h) {
        layRun(h);
        h.setBlock(SOURCE_X, Y, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        settle(h, () -> {
            lit(h, FIRST_X, "the casing against the redstone block");
            lit(h, 5, "a casing four along");
            lit(h, LAST_X, "the far end of the run");
            h.succeed();
        });
    }

    /**
     * Past the range the line goes dark, which is the whole reason the range exists.
     *
     * <p>The run is longer than the range on purpose: a test that only checked "inside the range
     * is lit" would pass just as well with no range check at all.
     *
     * <p>A long arena rather than a lowered config. The cap is a single global value and the game
     * test runner runs its cases in parallel, so a case that turned it down would be turning it down
     * for every other case in the batch as well.
     */
    @GameTest(template = "empty_long", timeoutTicks = 300)
    public static void windowStopsAtTheRangeLimit(GameTestHelper h) {
        int range = StockConfig.casingRedstoneRange();
        if (range > LONG_RUN - FIRST_X - 2) {
            h.fail("this test needs an arena longer than casing.redstoneRange; lengthen empty_long");
            return;
        }
        for (int x = FIRST_X; x <= LONG_RUN; x++) {
            h.setBlock(x, Y, Z, ModBlocks.TOWER_CASING.get().defaultBlockState());
        }
        h.setBlock(SOURCE_X, Y, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        // The wave walks one casing per tick, so the wait is the whole run plus room to spare.
        h.runAfterDelay(LONG_RUN + 20, () -> {
            lit(h, FIRST_X + range, "the last casing inside the range");
            dark(h, FIRST_X + range + 2, "a casing past the range");
            dark(h, LONG_RUN, "the far end of an arena longer than the range");
            h.succeed();
        });
    }

    /** A window that stays open after the lever is pulled is worse than one that never opens. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void windowClosesWhenTheSignalGoes(GameTestHelper h) {
        layRun(h);
        h.setBlock(SOURCE_X, Y, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        settle(h, () -> {
            lit(h, 5, "the casing before the signal was removed");
            h.setBlock(SOURCE_X, Y, Z, Blocks.AIR.defaultBlockState());
            settle(h, () -> {
                dark(h, FIRST_X, "the casing against where the signal was");
                dark(h, 5, "a casing four along");
                h.succeed();
            });
        });
    }

    /**
     * Take one of two sources away and the line stays lit.
     *
     * <p>This is what separates a search from a latch. A casing that remembered "I was lit" would
     * go dark here, because the source it learned from is the one that just left.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oneSourceLeavingDoesNotCloseTheWindow(GameTestHelper h) {
        layRun(h);
        h.setBlock(SOURCE_X, Y, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.setBlock(LAST_X + 1, Y, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        settle(h, () -> {
            lit(h, 5, "the casing between two sources");
            h.setBlock(LAST_X + 1, Y, Z, Blocks.AIR.defaultBlockState());
            settle(h, () -> {
                lit(h, 5, "the casing after one of its two sources was removed");
                h.succeed();
            });
        });
    }

    /** Casings do not carry a signal through a gap, which is what "connected" means here. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aGapStopsTheWindow(GameTestHelper h) {
        layRun(h);
        h.setBlock(5, Y, Z, Blocks.IRON_BLOCK.defaultBlockState());
        h.setBlock(SOURCE_X, Y, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
        settle(h, () -> {
            lit(h, 4, "the casing on the near side of the gap");
            dark(h, 6, "the casing on the far side of the gap");
            h.succeed();
        });
    }

    private static void layRun(GameTestHelper h) {
        for (int x = FIRST_X; x <= LAST_X; x++) {
            h.setBlock(x, Y, Z, ModBlocks.TOWER_CASING.get().defaultBlockState());
        }
    }

    /**
     * The window settles over block ticks, one casing further along per tick as each state change
     * wakes its neighbours, so the wait is the run length plus room to spare.
     */
    private static void settle(GameTestHelper h, Runnable then) {
        h.runAfterDelay(40, then);
    }

    private static void lit(GameTestHelper h, int x, String what) {
        h.assertTrue(powered(h, x), what + " never lit up");
    }

    private static void dark(GameTestHelper h, int x, String what) {
        h.assertFalse(powered(h, x), what + " lit up when it should not have");
    }

    private static boolean powered(GameTestHelper h, int x) {
        BlockState state = h.getBlockState(new BlockPos(x, Y, Z));
        if (!(state.getBlock() instanceof TowerCasingBlock)) {
            throw new AssertionError("test block at x=" + x + " is " + state.getBlock()
                    + ", not a distant casing");
        }
        return state.getValue(TowerCasingBlock.POWERED);
    }

    private CasingGameTests() {
    }
}
