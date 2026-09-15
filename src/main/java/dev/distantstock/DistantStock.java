package dev.distantstock;

import dev.distantstock.block.ModBlockEntities;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.fluid.ModFluids;
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
        ModFluids.FLUID_TYPES.register(bus);
        ModFluids.FLUIDS.register(bus);
        ModBlocks.BLOCKS.register(bus);
        ModBlockEntities.BES.register(bus);
        ModItems.ITEMS.register(bus);
        ModItems.TABS.register(bus);
        ModMenus.MENUS.register(bus);
        /*
         * Create: Deployer lets a panel type live on any board. It is optional, and the check is
         * what makes it optional: the class holding every reference to it is only named inside this
         * branch, so a pack without Deployer never resolves it and the mod keeps the two panel
         * blocks it has always had.
         */
        if (net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            dev.distantstock.panel.DeployerPanels.register(bus);
        }
    }
}
