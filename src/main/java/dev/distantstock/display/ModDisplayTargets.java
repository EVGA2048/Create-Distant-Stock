package dev.distantstock.display;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.api.registry.CreateRegistries;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Display Link targets contributed by Distant Stock. */
public final class ModDisplayTargets {
    public static final DeferredRegister<DisplayTarget> TARGETS =
            DeferredRegister.create(CreateRegistries.DISPLAY_TARGET, DistantStock.MODID);
    public static final DeferredHolder<DisplayTarget, AnnouncerDisplayTarget> ANNOUNCER =
            TARGETS.register("announcer", AnnouncerDisplayTarget::new);

    public static void register(IEventBus bus) {
        TARGETS.register(bus);
        bus.addListener(ModDisplayTargets::commonSetup);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DisplayTarget.BY_BLOCK.register(ModBlocks.ANNOUNCER.get(), ANNOUNCER.get());
            DisplayTarget.BY_BLOCK.register(ModBlocks.NETWORK_BROADCASTER.get(), ANNOUNCER.get());
        });
    }

    private ModDisplayTargets() {
    }
}
