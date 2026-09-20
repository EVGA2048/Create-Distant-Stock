package dev.distantstock;

import dev.distantstock.block.ConditionLinkerBlock;
import dev.distantstock.block.ConditionLinkerBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.StackLightBlock;
import dev.distantstock.block.StackLightBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class ConditionLinkerGameTests {

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void colouredSidesDriveIndependentStackLightChannels(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos linkerPos = h.absolutePos(new BlockPos(4, 2, 4));
        BlockPos lightPos = h.absolutePos(new BlockPos(8, 2, 4));

        level.setBlock(linkerPos, ModBlocks.CONDITION_LINKER.get().defaultBlockState()
                .setValue(ConditionLinkerBlock.FACING, Direction.NORTH), 3);
        level.setBlock(lightPos, ModBlocks.STACK_LIGHT.get().defaultBlockState(), 3);

        var linker = (ConditionLinkerBlockEntity) level.getBlockEntity(linkerPos);
        var light = (StackLightBlockEntity) level.getBlockEntity(lightPos);
        h.assertTrue(linker != null && light != null, "condition blocks did not create block entities");
        linker.setTarget(level.dimension().location(), lightPos);

        // North / red.
        BlockPos redInput = linkerPos.north();
        level.setBlock(redInput, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        linker.sampleAndSend();
        assertLamp(h, lightPos, true, false, false);
        h.assertFalse(light.buzzerEnabled(), "red input accidentally enabled buzzer");

        // East / yellow, red removed.
        level.setBlock(redInput, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(linkerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        linker.sampleAndSend();
        assertLamp(h, lightPos, false, true, false);

        // South / green.
        level.setBlock(linkerPos.east(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(linkerPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        linker.sampleAndSend();
        assertLamp(h, lightPos, false, false, true);

        // West / plain side enables the buzzer. Merely enabling it, with no lamp edge, stays idle.
        level.setBlock(linkerPos.south(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(linkerPos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        linker.sampleAndSend();
        assertLamp(h, lightPos, false, false, false);
        h.assertTrue(light.buzzerEnabled(), "plain side did not enable buzzer");
        h.assertFalse(light.buzzerActive(), "buzzer became active without red fault lamp");

        // Red + buzzer enable arms the repeating fault alarm. Yellow/green are one-shot edge chirps
        // instead, so they deliberately do not make buzzerActive() true while held.
        level.setBlock(redInput, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        linker.sampleAndSend();
        assertLamp(h, lightPos, true, false, false);
        h.assertTrue(light.buzzerActive(), "red + buzzer-enable did not arm audible alarm");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void rotatingLinkerRotatesItsInputLabels(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos linkerPos = h.absolutePos(new BlockPos(4, 2, 4));
        BlockPos lightPos = h.absolutePos(new BlockPos(8, 2, 4));

        level.setBlock(linkerPos, ModBlocks.CONDITION_LINKER.get().defaultBlockState()
                .setValue(ConditionLinkerBlock.FACING, Direction.EAST), 3);
        level.setBlock(lightPos, ModBlocks.STACK_LIGHT.get().defaultBlockState(), 3);
        var linker = (ConditionLinkerBlockEntity) level.getBlockEntity(linkerPos);
        linker.setTarget(level.dimension().location(), lightPos);

        // Local NORTH/red rotates clockwise to world EAST.
        level.setBlock(linkerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        linker.sampleAndSend();
        assertLamp(h, lightPos, true, false, false);
        h.succeed();
    }

    private static void assertLamp(GameTestHelper h, BlockPos pos,
                                   boolean red, boolean yellow, boolean green) {
        var state = h.getLevel().getBlockState(pos);
        h.assertTrue(state.getValue(StackLightBlock.RED) == red, "wrong red lamp state");
        h.assertTrue(state.getValue(StackLightBlock.YELLOW) == yellow, "wrong yellow lamp state");
        h.assertTrue(state.getValue(StackLightBlock.GREEN) == green, "wrong green lamp state");
    }

    private ConditionLinkerGameTests() {
    }
}
