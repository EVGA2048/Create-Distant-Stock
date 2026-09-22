package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.DistantStock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Reliable empty-hand inventory access for diagnostic/cache Frogports.
 *
 * NeoForge 1.21 splits empty-hand use from item use. Create's FrogportBlock only implements the
 * item-use path, so our 18-slot diagnostic/cache inventory would otherwise be unreachable with an
 * empty hand. This event deliberately handles only empty-hand clicks on our two block entities.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class SpecialFrogportInteractionEvents {

    @SubscribeEvent
    public static void openInventory(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !event.getEntity().getMainHandItem().isEmpty()
                || event.getEntity().isShiftKeyDown()) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof FrogportBlockEntity frog)) return;
        if (!(frog instanceof DiagnosticFrogportBlockEntity) && !(frog instanceof CacheFrogportBlockEntity)) return;

        if (!event.getLevel().isClientSide) {
            event.getEntity().openMenu(frog, event.getPos());
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
    }

    private SpecialFrogportInteractionEvents() {
    }
}
