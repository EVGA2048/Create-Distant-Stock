package dev.distantstock.item;

import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, DistantStock.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DistantStock.MODID);

    public static final DeferredHolder<Item, Item> REQUESTER = ITEMS.register("requester",
            () -> new RequesterItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, DockItem> DOCK = ITEMS.register("dock",
            () -> new DockItem(ModBlocks.DOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> DIAGNOSTIC_FROGPORT = block("diagnostic_frogport",
            ModBlocks.DIAGNOSTIC_FROGPORT);
    public static final DeferredHolder<Item, BlockItem> CACHE_FROGPORT = block("cache_frogport",
            ModBlocks.CACHE_FROGPORT);
    public static final DeferredHolder<Item, BlockItem> GAUGE = ITEMS.register("gauge",
            () -> new BlockItem(ModBlocks.GAUGE.get(), new Item.Properties()));
    public static final DeferredHolder<Item, RemoteGaugeItem> REMOTE_GAUGE = ITEMS.register("remote_gauge",
            () -> new RemoteGaugeItem(ModBlocks.REMOTE_GAUGE.get(), new Item.Properties()));
    public static final DeferredHolder<Item, com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockItem> REMOTE_REDSTONE_REQUESTER =
            ITEMS.register("remote_redstone_requester",
                    () -> new com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockItem(
                            ModBlocks.REMOTE_REDSTONE_REQUESTER.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> MONITOR = ITEMS.register("monitor",
            () -> new BlockItem(ModBlocks.MONITOR.get(), new Item.Properties()));
    public static final DeferredHolder<Item, LoggerItem> LOGGER = ITEMS.register("logger",
            () -> new LoggerItem(ModBlocks.LOGGER.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> STACK_LIGHT = ITEMS.register("stack_light",
            () -> new BlockItem(ModBlocks.STACK_LIGHT.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> RED_WALL_SOUNDER = block("red_wall_sounder",
            ModBlocks.RED_WALL_SOUNDER);
    public static final DeferredHolder<Item, BlockItem> ORANGE_WALL_SOUNDER = block("orange_wall_sounder",
            ModBlocks.ORANGE_WALL_SOUNDER);
    public static final DeferredHolder<Item, ConditionLinkerItem> CONDITION_LINKER = ITEMS.register("condition_linker",
            () -> new ConditionLinkerItem(ModBlocks.CONDITION_LINKER.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, BlockItem> REMOTE_PACKAGER = ITEMS.register("remote_packager",
            () -> new BlockItem(ModBlocks.REMOTE_PACKAGER.get(), new Item.Properties()));
    public static final DeferredHolder<Item, SignalLampPanelItem> CYAN_INDICATOR_LAMP = lamp("cyan_indicator_lamp", SignalLampPanelItem.Color.CYAN);
    public static final DeferredHolder<Item, SignalLampPanelItem> ORANGE_INDICATOR_LAMP = lamp("orange_indicator_lamp", SignalLampPanelItem.Color.ORANGE);
    public static final DeferredHolder<Item, SignalLampPanelItem> RED_INDICATOR_LAMP = lamp("red_indicator_lamp", SignalLampPanelItem.Color.RED);
    public static final DeferredHolder<Item, SignalLampPanelItem> GREEN_INDICATOR_LAMP = lamp("green_indicator_lamp", SignalLampPanelItem.Color.GREEN);
    public static final DeferredHolder<Item, SignalLampPanelItem> WHITE_INDICATOR_LAMP = lamp("white_indicator_lamp", SignalLampPanelItem.Color.WHITE);
    public static final DeferredHolder<Item, SignalLampPanelItem> BRASS_SIGNAL_LAMP = ITEMS.register("brass_signal_lamp",
            () -> new SignalLampPanelItem(ModBlocks.BRASS_INDICATOR_LAMP.get(), new Item.Properties(),
                    SignalLampPanelItem.Material.BRASS,
                    SignalLampPanelItem.Color.WHITE));
    public static final DeferredHolder<Item, BlockItem> TOWER_CASING = block("tower_casing", ModBlocks.TOWER_CASING);
    public static final DeferredHolder<Item, BlockItem> TOWER_CORE = block("tower_core", ModBlocks.TOWER_CORE);
    public static final DeferredHolder<Item, BlockItem> TOWER_COUPLER = block("tower_coupler", ModBlocks.TOWER_COUPLER);
    public static final DeferredHolder<Item, BlockItem> ETHER_RESONATOR = block("ether_resonator", ModBlocks.ETHER_RESONATOR);
    public static final DeferredHolder<Item, RemotePackageItem> REMOTE_PACKAGE = ITEMS.register("remote_package",
            () -> new RemotePackageItem(new Item.Properties()));
    /** System-generated diagnostic parcel. Deliberately omitted from the creative tab. */
    public static final DeferredHolder<Item, PingPackageItem> PING_PACKAGE = ITEMS.register("ping_package",
            () -> new PingPackageItem(new Item.Properties()));
    public static final DeferredHolder<Item, EventReceiptItem> EVENT_RECEIPT = ITEMS.register("event_receipt",
            () -> new EventReceiptItem(new Item.Properties().stacksTo(16)));
    public static final DeferredHolder<Item, Item> LOGGER_PAPER_ROLL = ITEMS.register("logger_paper_roll",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredHolder<Item, Item> MANUAL = ITEMS.register("manual",
            () -> new ManualItem(new Item.Properties().stacksTo(1)));

    /** Ground ender pearl, the second ingredient of raw ether quartz. */
    public static final DeferredHolder<Item, Item> ENDER_DUST = ITEMS.register("ender_dust",
            () -> new Item(new Item.Properties()));
    /** Base ether material; polished stock is what the casings and higher-tier parts are made from. */
    public static final DeferredHolder<Item, Item> ETHER_QUARTZ = ITEMS.register("ether_quartz",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> POLISHED_ETHER_QUARTZ = ITEMS.register("polished_ether_quartz",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> INCOMPLETE_ETHER_MECHANISM = ITEMS.register("incomplete_ether_mechanism",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> ETHER_MECHANISM = ITEMS.register("ether_mechanism",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, BucketItem> ETHER_BUCKET = ITEMS.register("ether_bucket",
            () -> new BucketItem(dev.distantstock.fluid.ModFluids.ETHER.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
    public static final DeferredHolder<Item, BucketItem> MOLTEN_AMETHYST_BUCKET = ITEMS.register("molten_amethyst_bucket",
            () -> new BucketItem(dev.distantstock.fluid.ModFluids.MOLTEN_AMETHYST.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
    /**
     * Bottled forms. A bottle is a quarter bucket, so it uses {@link FluidBottleItem} rather than
     * {@link BucketItem}, which would pour a whole source block. Filling is handled by
     * {@link BottleFillingEvents} because a bottle has no {@code Fluid#getBucket} hook.
     */
    public static final DeferredHolder<Item, FluidBottleItem> ETHER_BOTTLE = ITEMS.register("ether_bottle",
            () -> new FluidBottleItem(dev.distantstock.fluid.ModFluids.ETHER.get(),
                    new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, FluidBottleItem> MOLTEN_AMETHYST_BOTTLE = ITEMS.register("molten_amethyst_bottle",
            () -> new FluidBottleItem(dev.distantstock.fluid.ModFluids.MOLTEN_AMETHYST.get(),
                    new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.distantstock"))
            .icon(() -> new ItemStack(REQUESTER.get()))
            .displayItems((params, out) -> {
                out.accept(REQUESTER.get());
                out.accept(DOCK.get());
                out.accept(DIAGNOSTIC_FROGPORT.get());
                out.accept(CACHE_FROGPORT.get());
                out.accept(GAUGE.get());
                out.accept(REMOTE_GAUGE.get());
                out.accept(REMOTE_REDSTONE_REQUESTER.get());
                out.accept(MONITOR.get());
                out.accept(LOGGER.get());
                out.accept(STACK_LIGHT.get());
                out.accept(RED_WALL_SOUNDER.get());
                out.accept(ORANGE_WALL_SOUNDER.get());
                out.accept(CONDITION_LINKER.get());
                out.accept(REMOTE_PACKAGER.get());
                out.accept(CYAN_INDICATOR_LAMP.get());
                out.accept(ORANGE_INDICATOR_LAMP.get());
                out.accept(RED_INDICATOR_LAMP.get());
                out.accept(GREEN_INDICATOR_LAMP.get());
                out.accept(WHITE_INDICATOR_LAMP.get());
                out.accept(BRASS_SIGNAL_LAMP.get());
                out.accept(TOWER_CASING.get());
                out.accept(TOWER_CORE.get());
                out.accept(TOWER_COUPLER.get());
                out.accept(ETHER_RESONATOR.get());
                out.accept(REMOTE_PACKAGE.get());
                if (net.neoforged.fml.ModList.get().isLoaded("fluidlogistics")) {
                    out.accept(dev.distantstock.compat.fluidlogistics.FluidLogisticsCompat.remoteFluidPackage());
                }
                out.accept(EVENT_RECEIPT.get());
                out.accept(LOGGER_PAPER_ROLL.get());
                out.accept(ENDER_DUST.get());
                out.accept(ETHER_QUARTZ.get());
                out.accept(POLISHED_ETHER_QUARTZ.get());
                out.accept(INCOMPLETE_ETHER_MECHANISM.get());
                out.accept(ETHER_MECHANISM.get());
                out.accept(ETHER_BUCKET.get());
                out.accept(MOLTEN_AMETHYST_BUCKET.get());
                out.accept(ETHER_BOTTLE.get());
                out.accept(MOLTEN_AMETHYST_BOTTLE.get());
                out.accept(MANUAL.get());
            })
            .build());

    private static DeferredHolder<Item, BlockItem> block(String name,
                                                          DeferredHolder<net.minecraft.world.level.block.Block, ? extends net.minecraft.world.level.block.Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private static DeferredHolder<Item, SignalLampPanelItem> lamp(String name, SignalLampPanelItem.Color color) {
        return ITEMS.register(name, () -> new SignalLampPanelItem(switch (color) {
                    case CYAN -> ModBlocks.CYAN_INDICATOR_LAMP.get();
                    case ORANGE -> ModBlocks.ORANGE_INDICATOR_LAMP.get();
                    case RED -> ModBlocks.RED_INDICATOR_LAMP.get();
                    case GREEN -> ModBlocks.GREEN_INDICATOR_LAMP.get();
                    case WHITE -> ModBlocks.WHITE_INDICATOR_LAMP.get();
                }, new Item.Properties(),
                SignalLampPanelItem.Material.ANDESITE, color));
    }

    private ModItems() {
    }
}
