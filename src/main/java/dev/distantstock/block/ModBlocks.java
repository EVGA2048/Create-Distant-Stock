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
    public static final DeferredHolder<net.minecraft.world.level.block.Block, RemoteRedstoneRequesterBlock> REMOTE_REDSTONE_REQUESTER =
            BLOCKS.register("remote_redstone_requester", () -> new RemoteRedstoneRequesterBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "redstone_requester")))));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, SignalPanelBlock> SIGNAL_PANEL =
            BLOCKS.register("signal_panel", () -> new SignalPanelBlock(panel()));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> CYAN_INDICATOR_LAMP = lamp("cyan_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> ORANGE_INDICATOR_LAMP = lamp("orange_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> RED_INDICATOR_LAMP = lamp("red_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> GREEN_INDICATOR_LAMP = lamp("green_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> WHITE_INDICATOR_LAMP = lamp("white_indicator_lamp");
    public static final DeferredHolder<net.minecraft.world.level.block.Block, IndicatorLampBlock> BRASS_INDICATOR_LAMP = lamp("brass_indicator_lamp");

    /**
     * Non-occluding because half of its job is to be seen through. A full-cube occlusion shape
     * culls whatever is directly behind the block, so a window in a casing wall would look through
     * the culled face of the core it is wrapped around and out the other side of the world.
     */
    public static final DeferredHolder<net.minecraft.world.level.block.Block, TowerCasingBlock> TOWER_CASING =
            BLOCKS.register("tower_casing", () -> new TowerCasingBlock(tower().noOcclusion()
                    // The window lights the room it is in. A machine that glows when it is switched
                    // on is the whole point of an indicator, and block light is what makes it read
                    // as a light rather than as a brightly painted texture.
                    .lightLevel(state -> state.getValue(TowerCasingBlock.POWERED) ? 12 : 0)));
    public static final DeferredHolder<net.minecraft.world.level.block.Block, TowerCouplerBlock> TOWER_COUPLER =
            BLOCKS.register("tower_coupler", () -> new TowerCouplerBlock(tower().noOcclusion()
                    // The crystal through the middle of a coupler glows, and block light is the only
                    // way a model can say so: a texture cannot be told to ignore the light it is in.
                    // Only the segments that are part of a mast light up — a coupler stacked on
                    // nothing has no crystal running through it — which is what the two neighbour
                    // properties already say.
                    .lightLevel(state -> state.getValue(TowerCouplerBlock.ABOVE)
                            || state.getValue(TowerCouplerBlock.BELOW) ? 12 : 0)));
    /** The 3x3 base's centre: the one part of a tower that takes rotation, from the shaft below. */
    public static final DeferredHolder<net.minecraft.world.level.block.Block, TowerCoreBlock> TOWER_CORE =
            BLOCKS.register("tower_core", () -> new TowerCoreBlock(tower().noOcclusion()));
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
                .lightLevel(state -> state.getValue(IndicatorLampBlock.LIT) ? 14 : 0)));
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
