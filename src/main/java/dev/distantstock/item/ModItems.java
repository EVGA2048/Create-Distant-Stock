package dev.distantstock.item;

import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, DistantStock.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DistantStock.MODID);

    public static final DeferredHolder<Item, Item> REQUESTER = ITEMS.register("requester",
            () -> new RequesterItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, DockItem> DOCK = ITEMS.register("dock",
            () -> new DockItem(ModBlocks.DOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> GAUGE = ITEMS.register("gauge",
            () -> new BlockItem(ModBlocks.GAUGE.get(), new Item.Properties()));
    public static final DeferredHolder<Item, com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockItem> REMOTE_GAUGE = ITEMS.register("remote_gauge",
            () -> new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockItem(ModBlocks.REMOTE_GAUGE.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> MONITOR = ITEMS.register("monitor",
            () -> new BlockItem(ModBlocks.MONITOR.get(), new Item.Properties()));
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
    public static final DeferredHolder<Item, RemotePackageItem> REMOTE_PACKAGE = ITEMS.register("remote_package",
            () -> new RemotePackageItem(new Item.Properties()));
    public static final DeferredHolder<Item, Item> MANUAL = ITEMS.register("manual",
            () -> new ManualItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.distantstock"))
            .icon(() -> new ItemStack(REQUESTER.get()))
            .displayItems((params, out) -> {
                out.accept(REQUESTER.get());
                out.accept(DOCK.get());
                out.accept(GAUGE.get());
                out.accept(REMOTE_GAUGE.get());
                out.accept(MONITOR.get());
                out.accept(REMOTE_PACKAGER.get());
                out.accept(CYAN_INDICATOR_LAMP.get());
                out.accept(ORANGE_INDICATOR_LAMP.get());
                out.accept(RED_INDICATOR_LAMP.get());
                out.accept(GREEN_INDICATOR_LAMP.get());
                out.accept(WHITE_INDICATOR_LAMP.get());
                out.accept(BRASS_SIGNAL_LAMP.get());
                out.accept(REMOTE_PACKAGE.get());
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
