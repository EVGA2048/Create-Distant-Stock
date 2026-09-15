package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.DistantStock;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.item.SignalLampPanelItem;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Routes a parcel or a terminal in the player's hand into the device it is aimed at, without asking
 * vanilla whether the block may be used.
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
 * <p>The terminal needs the same door for the same reason, and only for the gestures that are
 * sneaking: the gate is written in terms of {@code isSecondaryUseActive()}, so writing a dock's
 * address or joining it to a group — both sneak-clicks — was being swallowed before the block heard
 * about it. Reported from play as "the terminal will not hand its link to the dock", which is the
 * same failure the parcels had.
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
    public static void offerItemToDevice(PlayerInteractEvent.RightClickBlock event) {
        boolean parcel = PackageItem.isPackage(event.getItemStack());
        boolean terminal = event.getItemStack().getItem() instanceof RequesterItem;
        if (!parcel && !terminal) {
            return;
        }

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!isDistantDevice(state)) {
            if (terminal && state.getBlock() instanceof FactoryPanelBlock) {
                offerTerminalToPanel(event);
            }
            return;
        }
        ItemInteractionResult result = state.useItemOn(event.getItemStack(), event.getLevel(),
                event.getEntity(), event.getHand(), event.getHitVec());
        if (result.consumesAction()) {
            event.setCancellationResult(result.result());
            event.setCanceled(true);
        }
    }

    /**
     * Binds a remote gauge that lives on a board which is not ours.
     *
     * <p>Our own boards read this click themselves. A remote gauge on somebody else's board cannot:
     * the block that receives the click is theirs, and it has never heard of a warehouse across
     * servers. So the gesture is answered here, on the same rule as everywhere else — a tuned
     * terminal binds the panel under the crosshair, and sneaking clears it.
     *
     * <p>This only runs with Create: Deployer installed, which is the only way a panel of ours is on
     * another mod's board in the first place.
     */
    private static void offerTerminalToPanel(PlayerInteractEvent.RightClickBlock event) {
        if (!net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            return;
        }
        var level = event.getLevel();
        ItemStack stack = event.getItemStack();
        RemoteNetworkId network = RemoteGaugeBlock.networkFromStack(stack);
        if (network == null) {
            return;
        }
        BlockPos pos = SignalLampPanelItem.panelUnder(level, event.getPos(),
                event.getHitVec().getDirection());
        if (pos == null || !(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity board)) {
            return;
        }
        BlockState panelState = level.getBlockState(pos);
        var slot = FactoryPanelBlock.getTargetedSlot(pos, panelState, event.getHitVec().getLocation());
        if (slot == null || !board.panels.get(slot).isActive()
                || !dev.distantstock.panel.DeployerPanels.holdsRemoteGauge(board, slot)) {
            // Not one of ours: another mod's panel, or an empty slot. Either way the board's own
            // click logic is what should answer.
            return;
        }
        if (level.isClientSide) {
            event.setCancellationResult(ItemInteractionResult.sidedSuccess(true).result());
            event.setCanceled(true);
            return;
        }
        var player = event.getEntity();
        if (player.isShiftKeyDown()) {
            dev.distantstock.panel.DeployerPanels.unbind(board, slot);
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.remote_gauge.unbound"), true);
        } else {
            dev.distantstock.panel.DeployerPanels.bind(board, slot, network,
                    RequesterData.receivingGroup(stack).orElse(null), RequesterData.address(stack));
            player.displayClientMessage(Component.translatable("gui.distantstock.remote_gauge.bound",
                    network.shortLabel()), true);
        }
        event.setCancellationResult(ItemInteractionResult.sidedSuccess(false).result());
        event.setCanceled(true);
    }

    /**
     * Whether this is a block that gives meaning to a parcel or a terminal in the hand.
     *
     * <p>The list is the devices a player configures by clicking them: the dock, the request desk,
     * the remote gauge boards and the remote requester, and the monitor. Everything else keeps
     * vanilla's gate, because nothing else has a gesture that the gate can eat.
     */
    private static boolean isDistantDevice(BlockState state) {
        return state.getBlock() instanceof DockBlock
                || state.getBlock() instanceof GaugeBlock
                || state.getBlock() instanceof RemoteGaugeBlock
                || state.getBlock() instanceof RemoteRedstoneRequesterBlock
                || state.getBlock() instanceof MonitorBlock;
    }

    private DockInteractionEvents() {
    }
}
