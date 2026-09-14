package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class LampPlacementEvents {
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void install(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof SignalLampPanelItem lamp)) return;
        // An empty slot has no hitbox, so the click lands on the wall behind the panel.
        BlockPos pos = SignalLampPanelItem.panelUnder(event.getLevel(), event.getPos(),
                event.getHitVec().getDirection());
        if (pos == null) return;
        var hit = new net.minecraft.world.phys.BlockHitResult(event.getHitVec().getLocation(),
                event.getHitVec().getDirection(), pos, event.getHitVec().isInside());
        if (!event.getLevel().mayInteract(event.getEntity(), pos)
                || !event.getEntity().mayUseItemAt(pos, hit.getDirection(), event.getItemStack())) return;
        // FactoryPanelBlock consumes unknown held items before BlockItem.useOn can run.
        event.setCancellationResult(lamp.useOn(new UseOnContext(event.getEntity(), event.getHand(), hit)));
        event.setCanceled(true);
    }
}
