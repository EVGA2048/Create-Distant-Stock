package dev.distantstock.display;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Display Link data sources contributed to Create by Distant Stock. */
public final class ModDisplaySources {
    public static final DeferredRegister<DisplaySource> SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, DistantStock.MODID);

    public static final DeferredHolder<DisplaySource, LoggerDisplaySource> LOGGER =
            SOURCES.register("logger", LoggerDisplaySource::new);

    public static void register(IEventBus bus) {
        SOURCES.register(bus);
        bus.addListener(ModDisplaySources::commonSetup);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> DisplaySource.BY_BLOCK.add(ModBlocks.LOGGER.get(), LOGGER.get()));
    }

    private ModDisplaySources() {
    }
}
