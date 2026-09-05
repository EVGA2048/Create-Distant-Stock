package dev.distantstock;

import dev.distantstock.block.ModBlockEntities;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.ModItems;
import dev.distantstock.menu.ModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(DistantStock.MODID)
public final class DistantStock {
    public static final String MODID = "distantstock";

    public DistantStock(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, StockConfig.SPEC);
        ModBlocks.BLOCKS.register(bus);
        ModBlockEntities.BES.register(bus);
        ModItems.ITEMS.register(bus);
        ModItems.TABS.register(bus);
        ModMenus.MENUS.register(bus);
    }
}
