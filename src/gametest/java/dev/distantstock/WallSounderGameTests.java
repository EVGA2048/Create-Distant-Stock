package dev.distantstock;

import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.WallSounderBlock;
import dev.distantstock.block.WallSounderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class WallSounderGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void doubleFlashPatternIsTwoShortPulses(GameTestHelper h) {
        h.assertTrue(WallSounderBlockEntity.flashOn(0), "first flash did not start on phase 0");
        h.assertTrue(WallSounderBlockEntity.flashOn(1), "first 100 ms flash ended too early");
        h.assertFalse(WallSounderBlockEntity.flashOn(2), "gap after first flash did not begin");
        h.assertFalse(WallSounderBlockEntity.flashOn(4), "gap between flashes ended too early");
        h.assertTrue(WallSounderBlockEntity.flashOn(5), "second flash did not start");
        h.assertTrue(WallSounderBlockEntity.flashOn(6), "second 100 ms flash ended too early");
        h.assertFalse(WallSounderBlockEntity.flashOn(7), "long dark interval did not begin");
        h.assertFalse(WallSounderBlockEntity.flashOn(29), "long dark interval ended too early");
        h.assertTrue(WallSounderBlockEntity.flashOn(30), "double-flash cycle did not repeat");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void redstoneAndSoundSelectionAreIndependent(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.RED_WALL_SOUNDER.get().defaultBlockState()
                .setValue(WallSounderBlock.FACING, Direction.NORTH), 3);
        var be = (WallSounderBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(be != null, "wall sounder block entity missing");
        h.assertTrue(be.toneIndex() == 1, "new wall sounder should start on sound slot 1");

        be.setToneIndex(0);
        h.assertTrue(be.toneIndex() == 0, "sound slot 0 was rejected");
        be.setToneIndex(2);
        h.assertTrue(be.toneIndex() == 2, "sound slot 2 was rejected");
        be.setToneIndex(99);
        h.assertTrue(be.toneIndex() == 2, "sound selection did not clamp above 2");
        be.setToneIndex(-8);
        h.assertTrue(be.toneIndex() == 0, "sound selection did not clamp below 0");

        level.setBlock(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        h.assertTrue(level.getBlockState(pos).getValue(WallSounderBlock.POWERED),
                "redstone power did not activate wall sounder");
        h.assertTrue(level.getBlockState(pos).getValue(WallSounderBlock.LIT),
                "wall sounder did not enter its first flash when powered");
        h.assertTrue(be.toneIndex() == 0,
                "redstone activation unexpectedly changed the selected sound slot");
        h.succeed();
    }
}
