package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.DistantStock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Reliable empty-hand inventory access for the diagnostic Frogport.
 *
 * NeoForge 1.21 splits empty-hand use from item use. Create's FrogportBlock only implements the
 * item-use path, so the diagnostic inventory would otherwise be unreachable with an empty hand.
 * CacheFrogportBlock owns its own empty-hand menu path because it also reserves sneak interaction
 * for its release-delay value settings.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class SpecialFrogportInteractionEvents {

    @SubscribeEvent
    public static void openInventory(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !event.getEntity().getMainHandItem().isEmpty()
                || event.getEntity().isShiftKeyDown()) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof DiagnosticFrogportBlockEntity frog)) return;

        if (!event.getLevel().isClientSide) {
            event.getEntity().openMenu(frog, event.getPos());
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
    }

    private SpecialFrogportInteractionEvents() {
    }
}
