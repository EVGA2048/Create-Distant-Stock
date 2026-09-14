package dev.distantstock.item;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockItem;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.SignalPanelBlockEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Lets both gauge items occupy free slots of one mixed Create-style panel. */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class GaugePlacementEvents {
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void install(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        boolean remote = stack.is(ModItems.REMOTE_GAUGE.get());
        var createGauge = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge"));
        boolean factory = stack.is(createGauge.asItem());
        if (!remote && !factory) return;
        var level = event.getLevel();
        // An empty slot has no hitbox, so aiming at one lands the click on the wall behind the panel.
        var pos = SignalLampPanelItem.panelUnder(level, event.getPos(), event.getHitVec().getDirection());
        if (pos == null) return;
        var state = level.getBlockState(pos);
        if (factory && state.is(createGauge)) return;
        if (!level.mayInteract(event.getEntity(), pos)
                || !event.getEntity().mayUseItemAt(pos, event.getHitVec().getDirection(), stack)) return;
        var slot = FactoryPanelBlock.getTargetedSlot(pos, state, event.getHitVec().getLocation());
        if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity old)
                || old.panels.get(slot).isActive() || !FactoryPanelBlockItem.isTuned(stack)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        if (!level.isClientSide) {
            SignalPanelBlockEntity panel = state.is(ModBlocks.SIGNAL_PANEL.get())
                    ? (SignalPanelBlockEntity) old
                    : SignalLampPanelItem.convertFactoryPanel(level, pos, state);
            if (panel == null || !panel.addPanel(slot,
                    LogisticallyLinkedBlockItem.networkFromStack(FactoryPanelBlockItem.fixCtrlCopiedStack(stack)))) {
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
            panel.setRemoteGauge(slot, remote);
            if (!event.getEntity().isCreative()) stack.shrink(1);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }
}
