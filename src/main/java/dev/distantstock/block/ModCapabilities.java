package dev.distantstock.block;

import dev.distantstock.DistantStock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCapabilities {
    @SubscribeEvent
    public static void caps(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.DOCK.get(),
                (be, side) -> side == Direction.DOWN ? be.bottomFace : be.automation);
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.REMOTE_PACKAGER.get(),
                (be, side) -> be.inventory);
    }

    private ModCapabilities() {
    }
}
