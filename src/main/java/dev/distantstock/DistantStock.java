package dev.distantstock;

import dev.distantstock.block.ModBlockEntities;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.display.ModDisplaySources;
import dev.distantstock.display.ModDisplayTargets;
import dev.distantstock.fluid.ModFluids;
import dev.distantstock.item.ModItems;
import dev.distantstock.item.ModArmorMaterials;
import dev.distantstock.item.ModDataComponents;
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
        ModArmorMaterials.MATERIALS.register(bus);
        ModDataComponents.COMPONENTS.register(bus);
        ModItems.ITEMS.register(bus);
        ModSounds.SOUNDS.register(bus);
        ModItems.TABS.register(bus);
        ModMenus.MENUS.register(bus);
        ModDisplaySources.register(bus);
        ModDisplayTargets.register(bus);
        /*
         * Create: Deployer lets a panel type live on any board. It is optional, and the check is
         * what makes it optional: the class holding every reference to it is only named inside this
         * branch, so a pack without Deployer never resolves it and the mod keeps the two panel
         * blocks it has always had.
         */
        if (net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            dev.distantstock.panel.DeployerPanels.register(bus);
        }
        // FluidLogistics is a true optional compat surface. This class references its API and is
        // therefore never resolved on packs that do not have the mod installed.
        if (net.neoforged.fml.ModList.get().isLoaded("fluidlogistics")) {
            dev.distantstock.compat.fluidlogistics.FluidLogisticsCompat.register(bus);
        }
        // Thirst Was Taken, on the same terms. What it buys is a tank of water being water: the
        // purity it was drawn at, the effects that come with it, and the thirst it quenches.
        if (net.neoforged.fml.ModList.get().isLoaded("thirst")) {
            dev.distantstock.compat.thirst.ThirstDrinkCompat.register();
        }
        /*
         * CreateLazyTick 2.7.32 and older caches sequenced-assembly deployer steps by the held
         * item alone. Every step changes the workpiece's component, not the item, so the cache
         * hands back the first step's recipe and the deployer consumes the same material again
         * while progress moves on. Server start is after config load, which is the earliest point
         * the switch can be changed for the session; 2.7.33 and newer are left alone.
         */
        if (net.neoforged.fml.ModList.get().isLoaded("createlazytick")) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.neoforged.neoforge.event.server.ServerAboutToStartEvent.class,
                    event -> dev.distantstock.compat.createlazytick.CreateLazyTickCompat
                            .disableBrokenDeployerCache());
        }
        /*
         * Sable is optional and the implementation class imports its API, so the class is only
         * named inside this branch. The try is for a pack that has Sable but a version whose API
         * has moved: the bridge keeps its vanilla fallback rather than taking the whole mod down.
         */
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            try {
                dev.distantstock.compat.sable.SableCompat.init();
            } catch (Exception | LinkageError failure) {
                org.apache.logging.log4j.LogManager.getLogger().error(
                        "[DistantStock/Sable] could not enable sub-level support; "
                                + "towers keep vanilla block coordinates", failure);
            }
        }
    }

}
