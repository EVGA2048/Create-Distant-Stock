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
    public static final DeferredHolder<Item, BlockItem> DOCK = ITEMS.register("dock",
            () -> new BlockItem(ModBlocks.DOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> GAUGE = ITEMS.register("gauge",
            () -> new BlockItem(ModBlocks.GAUGE.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> MONITOR = ITEMS.register("monitor",
            () -> new BlockItem(ModBlocks.MONITOR.get(), new Item.Properties()));
    public static final DeferredHolder<Item, Item> MANUAL = ITEMS.register("manual",
            () -> new ManualItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.distantstock"))
            .icon(() -> new ItemStack(REQUESTER.get()))
            .displayItems((params, out) -> {
                out.accept(REQUESTER.get());
                out.accept(DOCK.get());
                out.accept(GAUGE.get());
                out.accept(MONITOR.get());
                out.accept(MANUAL.get());
            })
            .build());

    private ModItems() {
    }
}
