package dev.distantstock.block;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.DistantStock;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Routes a parcel in the player's hand into the dock, without asking vanilla whether the block may
 * be used.
 *
 * <p>The click was not reliably reaching {@link DockBlock#useItemOn}. Vanilla gates that call before
 * the block hears about it, and the gate is written differently on the two sides:
 *
 * <pre>
 * // server, ServerPlayerGameMode
 * boolean flag1 = player.isSecondaryUseActive() &amp;&amp; holdingSomething
 *         &amp;&amp; !(mainHand.doesSneakBypassUse(..) &amp;&amp; offhand.doesSneakBypassUse(..));
 *
 * // client, MultiPlayerGameMode
 * boolean flag1 = player.isSecondaryUseActive()
 *         &amp;&amp; (!mainHand.doesSneakBypassUse(..) || !offhand.doesSneakBypassUse(..));
 * </pre>
 *
 * <p>Both hands have to bypass the sneak for the server's version to clear, and an empty off hand
 * never does, so sneaking skipped the block outright. The unsneaking case had no such explanation:
 * it passed both gates on paper and still did nothing, and reading the block's own code could not
 * show why.
 *
 * <p>So the gate is stepped over. This handler calls the block itself and cancels the event, and
 * cancellation is checked at the very top of both call paths after every handler has run, so nothing
 * that ran before can take the click back. The block keeps the logic, so there is still one
 * implementation and the game tests that call {@code useItemOn} directly still exercise it.
 *
 * <p>"Plain right-click does not work" turned out not to be about this at all. Logging every parcel
 * right-click, with the block it landed on, showed six attempts at a `create:depot` next to the
 * dock — a block lower and one block over, where a slightly low crosshair lands — and every attempt
 * that reached the dock being taken, sneaking or not. The gate above is real and the sneak case
 * genuinely needed stepping over; the rest was aim.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class DockInteractionEvents {

    @SubscribeEvent
    public static void offerParcelToDock(PlayerInteractEvent.RightClickBlock event) {
        if (!PackageItem.isPackage(event.getItemStack())) {
            return;
        }

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!(state.getBlock() instanceof DockBlock)) {
            return;
        }
        ItemInteractionResult result = state.useItemOn(event.getItemStack(), event.getLevel(),
                event.getEntity(), event.getHand(), event.getHitVec());
        if (result.consumesAction()) {
            event.setCancellationResult(result.result());
            event.setCanceled(true);
        }
    }

    private DockInteractionEvents() {
    }
}
