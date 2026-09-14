package dev.distantstock.fluid;

import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The two ether materials.
 *
 * Both are ordinary flowing fluids, deliberately not lava: the art brief asks for lava's slow
 * churn without lava's damage, so neither one burns, ignites or hurts anything standing in it.
 * The liquid blocks live here rather than in {@code ModBlocks} because a fluid needs its block and
 * its block needs the fluid, and keeping both in one class avoids a static initialisation cycle.
 */
public final class ModFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, DistantStock.MODID);
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, DistantStock.MODID);

    /** How hard molten amethyst hits. Lava deals four, so this is deliberately half as vicious. */
    private static final float MOLTEN_DAMAGE = 2.0F;
    /** Both fluids glow; the condensate softly, the melt like lava. */
    public static final int ETHER_LIGHT = 10;
    public static final int MOLTEN_LIGHT = 15;

    /** Spreads like water: far, thin, and you can swim in it. Glows softly. */
    public static final DeferredHolder<FluidType, FluidType> ETHER_TYPE = FLUID_TYPES.register("ether",
            () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid.distantstock.ether")
                    .density(1000).viscosity(1000).temperature(300).lightLevel(ETHER_LIGHT)
                    .canDrown(true).canSwim(true).supportsBoating(true).canConvertToSource(false)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> ETHER =
            FLUIDS.register("ether", () -> new BaseFlowingFluid.Source(etherProperties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> ETHER_FLOW =
            FLUIDS.register("ether_flow", () -> new BaseFlowingFluid.Flowing(etherProperties()));

    /** Creeps like lava: short reach, thick, glowing. It burns, but without lava's fire. */
    public static final DeferredHolder<FluidType, FluidType> MOLTEN_AMETHYST_TYPE = FLUID_TYPES.register("molten_amethyst",
            () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid.distantstock.molten_amethyst")
                    .density(3000).viscosity(6000).temperature(1300).lightLevel(MOLTEN_LIGHT)
                    .canDrown(false).canSwim(false).supportsBoating(false).canConvertToSource(false)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> MOLTEN_AMETHYST =
            FLUIDS.register("molten_amethyst", () -> new BaseFlowingFluid.Source(moltenAmethystProperties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> MOLTEN_AMETHYST_FLOW =
            FLUIDS.register("molten_amethyst_flow", () -> new BaseFlowingFluid.Flowing(moltenAmethystProperties()));

    public static final DeferredHolder<Block, LiquidBlock> ETHER_BLOCK = ModBlocks.BLOCKS.register("ether",
            () -> new LiquidBlock(ETHER.get(), waterLike(ETHER_LIGHT)));
    public static final DeferredHolder<Block, LiquidBlock> MOLTEN_AMETHYST_BLOCK = ModBlocks.BLOCKS.register("molten_amethyst",
            () -> new MoltenAmethystBlock(MOLTEN_AMETHYST.get(), waterLike(MOLTEN_LIGHT).mapColor(MapColor.FIRE)));

    /**
     * The damage lives on the block rather than the fluid: in 1.21.1 neither {@code Fluid} nor
     * {@code FlowingFluid} has an entity hook any more, and the liquid block covers sources and
     * flowing alike. Scaled down from lava and without setting anything alight; fire immunity
     * still applies, so fire resistance keeps working.
     */
    private static final class MoltenAmethystBlock extends LiquidBlock {
        private MoltenAmethystBlock(net.minecraft.world.level.material.FlowingFluid fluid,
                                    BlockBehaviour.Properties properties) {
            super(fluid, properties);
        }

        @Override
        protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
            if (!entity.fireImmune()) {
                entity.hurt(level.damageSources().lava(), MOLTEN_DAMAGE);
            }
        }
    }

    private static BaseFlowingFluid.Properties etherProperties() {
        return new BaseFlowingFluid.Properties(ETHER_TYPE, ETHER, ETHER_FLOW)
                .block(ETHER_BLOCK)
                .bucket(ModItems.ETHER_BUCKET)
                .slopeFindDistance(4).levelDecreasePerBlock(1).tickRate(5);
    }

    private static BaseFlowingFluid.Properties moltenAmethystProperties() {
        return new BaseFlowingFluid.Properties(MOLTEN_AMETHYST_TYPE, MOLTEN_AMETHYST, MOLTEN_AMETHYST_FLOW)
                .block(MOLTEN_AMETHYST_BLOCK)
                .bucket(ModItems.MOLTEN_AMETHYST_BUCKET)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(30);
    }

    private static BlockBehaviour.Properties waterLike(int light) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WATER).replaceable().noCollission().randomTicks()
                .strength(100.0F).pushReaction(PushReaction.DESTROY).noLootTable()
                .lightLevel(state -> light).liquid().sound(SoundType.EMPTY);
    }

    private ModFluids() {
    }
}
