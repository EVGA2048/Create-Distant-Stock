package dev.distantstock.block;

import dev.distantstock.DistantStock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, DistantStock.MODID);

    public static final DeferredHolder<net.minecraft.world.level.block.Block, DockBlock> DOCK =
            BLOCKS.register("dock", () -> new DockBlock(machine()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, GaugeBlock> GAUGE =
            BLOCKS.register("gauge", () -> new GaugeBlock(machine().noOcclusion()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, MonitorBlock> MONITOR =
            BLOCKS.register("monitor", () -> new MonitorBlock(panel()));

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(1.5f)
                .sound(SoundType.COPPER);
    }

    private static BlockBehaviour.Properties panel() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(1.2f)
                .sound(SoundType.STONE)
                .noOcclusion();
    }

    private ModBlocks() {
    }
}
