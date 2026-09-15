package dev.distantstock.block;

import dev.distantstock.DistantStock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DistantStock.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DockBlockEntity>> DOCK =
            BES.register("dock", () -> BlockEntityType.Builder.of(ModBlockEntities::dockEntity,
                    ModBlocks.DOCK.get()).build(null));

    private static DockBlockEntity dockEntity(net.minecraft.core.BlockPos pos,
                                              net.minecraft.world.level.block.state.BlockState state) {
        return new DockBlockEntity(DOCK.get(), pos, state);
    }
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GaugeBlockEntity>> GAUGE =
            BES.register("gauge", () -> BlockEntityType.Builder.of(GaugeBlockEntity::new, ModBlocks.GAUGE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity>> REMOTE_GAUGE =
            BES.register("remote_gauge", () -> BlockEntityType.Builder.of(ModBlockEntities::remoteGaugeEntity,
                    ModBlocks.REMOTE_GAUGE.get()).build(null));

    private static com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity remoteGaugeEntity(
            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity(REMOTE_GAUGE.get(), pos, state);
    }
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorBlockEntity>> MONITOR =
            BES.register("monitor", () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, ModBlocks.MONITOR.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RemotePackagerBlockEntity>> REMOTE_PACKAGER =
            BES.register("remote_packager", () -> BlockEntityType.Builder.of(RemotePackagerBlockEntity::new,
                    ModBlocks.REMOTE_PACKAGER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SignalPanelBlockEntity>> SIGNAL_PANEL =
            BES.register("signal_panel", () -> BlockEntityType.Builder.of(SignalPanelBlockEntity::new,
                    ModBlocks.SIGNAL_PANEL.get()).build(null));

    /**
     * The core is a kinetic block entity because it is the tower's only rotating part, and the tier
     * lives here too: the table a tower has reached follows from the mast above it, which this walks.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TowerCoreBlockEntity>> TOWER_CORE =
            BES.register("tower_core", () -> BlockEntityType.Builder.of(TowerCoreBlockEntity::new,
                    ModBlocks.TOWER_CORE.get()).build(null));

    /**
     * The resonator's arms turn about the block's centre and sweep past its sides, so they are drawn
     * by a renderer rather than baked into the model. Nothing else about the tower needs a block
     * entity yet — the casing, core and couplers are all stateless.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResonatorBlockEntity>> ETHER_RESONATOR =
            BES.register("ether_resonator", () -> BlockEntityType.Builder.of(ResonatorBlockEntity::new,
                    ModBlocks.ETHER_RESONATOR.get()).build(null));

    private ModBlockEntities() {
    }
}
