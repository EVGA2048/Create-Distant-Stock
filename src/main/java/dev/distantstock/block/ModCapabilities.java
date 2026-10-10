package dev.distantstock.block;

import dev.distantstock.DistantStock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack;

@EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCapabilities {
    @SubscribeEvent
    public static void caps(RegisterCapabilitiesEvent e) {
        // Every tank takes any fluid. The list is the single answer to "which items are tanks", so a
        // tier added to ModItems and forgotten here is a tank a spout cannot fill -- which is why
        // the registration loops over it rather than naming items one at a time.
        for (var tank : dev.distantstock.item.ModItems.FLUID_TANKS) {
            e.registerItem(Capabilities.FluidHandler.ITEM,
                    (stack, ignored) -> new dev.distantstock.item.TankItemHandler(stack),
                    tank.get());
        }
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.DOCK.get(),
                (be, side) -> side == Direction.DOWN ? be.bottomFace : be.automation);
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.REMOTE_PACKAGER.get(),
                (be, side) -> be.inventory);
        // The tank is reachable from every face except the bottom, which already carries the shaft
        // the tower is driven from: a pipe and a driveshaft cannot share a face, and the shaft has
        // the stronger claim on the one it already has.
        e.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.TOWER_CORE.get(),
                (be, side) -> side == Direction.DOWN ? null : be.tank());
        // A finished skirt walls the core in on all four sides, so the tank needs a door in the
        // wall: a casing the player opened with a wrench. See TowerCasingBlock#portTank.
        e.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.TOWER_CASING.get(),
                (be, side) -> TowerCasingBlock.portTank(be.getLevel(), be.getBlockPos(),
                        be.getBlockState(), side));
    }

    private ModCapabilities() {
    }
}
