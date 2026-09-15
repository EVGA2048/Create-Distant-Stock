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
    public static final DeferredHolder<net.minecraft.world.level.block.Block, RemoteGaugeBlock> REMOTE_GAUGE =
            BLOCKS.register("remote_gauge", () -> new RemoteGaugeBlock(panel()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, MonitorBlock> MONITOR =
            BLOCKS.register("monitor", () -> new MonitorBlock(panel()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, RemotePackagerBlock> REMOTE_PACKAGER =
            BLOCKS.register("remote_packager", () -> new RemotePackagerBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "packager")))
                            .noOcclusion()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, SignalPanelBlock> SIGNAL_PANEL =
            BLOCKS.register("signal_panel", () -> new SignalPanelBlock(panel()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> CYAN_INDICATOR_LAMP = lamp("cyan_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> ORANGE_INDICATOR_LAMP = lamp("orange_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> RED_INDICATOR_LAMP = lamp("red_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> GREEN_INDICATOR_LAMP = lamp("green_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> WHITE_INDICATOR_LAMP = lamp("white_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> BRASS_INDICATOR_LAMP = lamp("brass_indicator_lamp");

    public static final DeferredHolder<net.minecraft.world.level.block.Block, TowerCasingBlock> TOWER_CASING =
            BLOCKS.register("tower_casing", () -> new TowerCasingBlock(tower()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, TowerCouplerBlock> TOWER_COUPLER =
            BLOCKS.register("tower_coupler", () -> new TowerCouplerBlock(tower().noOcclusion()));
    /**
     * The core is a plain block until the tower assembly lands, when it becomes a kinetic block
     * taking rotation from the shaft below it. Its model is the 3x3 base's centre, so it keeps the
     * tower's own material and sound rather than the generic machine ones.
     */
    public static final DeferredHolder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.Block> TOWER_CORE =
            BLOCKS.register("tower_core", () -> new net.minecraft.world.level.block.Block(tower().noOcclusion()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, ResonatorBlock> ETHER_RESONATOR =
            BLOCKS.register("ether_resonator", () -> new ResonatorBlock(tower().noOcclusion()));

    private static BlockBehaviour.Properties tower() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(3.0f, 6.0f)
                .sound(SoundType.COPPER);
    }

    private static DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> lamp(String name) {
        return BLOCKS.register(name, () -> new IndicatorLampBlock(panel()
                .lightLevel(state -> state.getValue(IndicatorLampBlock.LIT) ? 10 : 0)));
    }

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

    public static net.minecraft.world.level.block.entity.BlockEntityType<SignalPanelBlockEntity> SIGNAL_PANEL_ENTITY_TYPE() {
        return ModBlockEntities.SIGNAL_PANEL.get();
    }
}
