package dev.distantstock.block;

import dev.distantstock.DistantStock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DistantStock.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LinkerBlockEntity>> LINKER =
            BES.register("linker", () -> BlockEntityType.Builder.of(LinkerBlockEntity::new, ModBlocks.LINKER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GaugeBlockEntity>> GAUGE =
            BES.register("gauge", () -> BlockEntityType.Builder.of(GaugeBlockEntity::new, ModBlocks.GAUGE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>> MONITOR =
            BES.register("monitor", () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, ModBlocks.MONITOR.get()).build(null));

    private ModBlockEntities() {
    }
}
