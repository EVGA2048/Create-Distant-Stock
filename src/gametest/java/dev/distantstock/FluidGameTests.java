package dev.distantstock;

import dev.distantstock.fluid.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class FluidGameTests {

    /**
     * Molten amethyst burns. The damage lives on the liquid block because 1.21.1 removed the fluid
     * entity hook, so this is exactly the kind of wiring that fails silently.
     *
     * The two fluids are tested one per test: they spread, and a shared arena lets the melt reach
     * the entity standing in the condensate.
     */
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void moltenAmethystHurts(GameTestHelper h) {
        Pig burned = standingIn(h, ModFluids.MOLTEN_AMETHYST_BLOCK.get());
        h.runAfterDelay(60, () -> {
            h.assertTrue(burned.isDeadOrDying() || burned.getHealth() < burned.getMaxHealth(),
                    "molten amethyst did not hurt the pig (health " + burned.getHealth() + ")");
            h.succeed();
        });
    }

    /** Ether is safe to stand in: the art brief asks for water's behaviour, not lava's bite. */
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void etherDoesNotHurt(GameTestHelper h) {
        Pig wet = standingIn(h, ModFluids.ETHER_BLOCK.get());
        h.runAfterDelay(60, () -> {
            h.assertTrue(wet.getHealth() == wet.getMaxHealth(),
                    "ether hurt the pig (health " + wet.getHealth() + ")");
            h.succeed();
        });
    }

    /**
     * A pig rather than a zombie: the test world is in daylight, and a zombie burning in the sun
     * looks exactly like the fluid hurting it.
     */
    private static Pig standingIn(GameTestHelper h, net.minecraft.world.level.block.Block fluid) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, fluid.defaultBlockState(), 3);
        Pig pig = new Pig(net.minecraft.world.entity.EntityType.PIG, level);
        pig.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        level.addFreshEntity(pig);
        return pig;
    }

    /** Both fluids are registered as placeable sources so pipes and buckets have something to move. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void bothFluidsExistAsSourceAndFlowing(GameTestHelper h) {
        h.assertTrue(ModFluids.ETHER.get().isSource(ModFluids.ETHER.get().defaultFluidState()), "ether has no source");
        h.assertTrue(ModFluids.ETHER_FLOW.get().isSource(ModFluids.ETHER_FLOW.get().defaultFluidState()) == false,
                "the flowing ether is marked as a source");
        h.assertTrue(ModFluids.MOLTEN_AMETHYST.get().isSource(
                ModFluids.MOLTEN_AMETHYST.get().defaultFluidState()), "molten amethyst has no source");
        h.assertTrue(ModFluids.ETHER_BLOCK.get().defaultBlockState().getFluidState()
                .isSource(), "the ether liquid block is not a source");
        h.assertTrue(ModFluids.MOLTEN_AMETHYST_BLOCK.get().defaultBlockState().getFluidState()
                .isSource(), "the molten amethyst liquid block is not a source");
        h.succeed();
    }

    private FluidGameTests() {
    }
}
