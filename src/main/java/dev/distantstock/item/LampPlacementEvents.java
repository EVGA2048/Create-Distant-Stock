package dev.distantstock.item;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.DistantStock;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class LampPlacementEvents {
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void install(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof SignalLampPanelItem lamp)
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof FactoryPanelBlock)) return;
        if (!event.getLevel().mayInteract(event.getEntity(), event.getPos())
                || !event.getEntity().mayUseItemAt(event.getPos(), event.getHitVec().getDirection(), event.getItemStack())) return;
        // FactoryPanelBlock consumes unknown held items before BlockItem.useOn can run.
        event.setCancellationResult(lamp.useOn(new UseOnContext(event.getEntity(), event.getHand(), event.getHitVec())));
        event.setCanceled(true);
    }
}
