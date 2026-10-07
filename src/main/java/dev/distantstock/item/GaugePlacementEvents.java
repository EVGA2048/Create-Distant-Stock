package dev.distantstock.item;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockItem;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.SignalPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
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
        boolean foreign = !remote && !factory && isPanelItem(stack);
        if (!remote && !factory && !foreign) return;
        var level = event.getLevel();
        // An empty slot has no hitbox, so aiming at one lands the click on the wall behind the panel.
        var pos = SignalLampPanelItem.panelUnder(level, event.getPos(), event.getHitVec().getDirection());
        if (pos == null) return;
        var state = level.getBlockState(pos);
        if (foreign) {
            if (!isOurBoard(state)) return;
            handToTheBoard(event, level, pos, state, stack);
            return;
        }
        if (factory && state.is(createGauge)) return;
        var slot = FactoryPanelBlock.getTargetedSlot(pos, state, event.getHitVec().getLocation());
        if (remote && FactoryPanelBlockItem.isTuned(stack)
                && level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity board
                && board.panels.get(slot).isActive()
                && isRemoteGauge(board, slot)) {
            retuneExistingRemoteGauge(event, board, slot, stack);
            return;
        }
        if (remote && !isOurBoard(state) && net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            installOnSomeoneElsesBoard(event, level, pos, state, stack);
            return;
        }
        if (!level.mayInteract(event.getEntity(), pos)
                || !event.getEntity().mayUseItemAt(pos, event.getHitVec().getDirection(), stack)) return;
        boolean tuned = FactoryPanelBlockItem.isTuned(stack);
        if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity old)
                || old.panels.get(slot).isActive() || (!remote && !tuned)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        if (!level.isClientSide) {
            SignalPanelBlockEntity panel = state.is(ModBlocks.SIGNAL_PANEL.get())
                    ? (SignalPanelBlockEntity) old
                    : SignalLampPanelItem.convertFactoryPanel(level, pos, state);
            java.util.UUID localNetwork = tuned
                    ? LogisticallyLinkedBlockItem.networkFromStack(FactoryPanelBlockItem.fixCtrlCopiedStack(stack))
                    : dev.distantstock.block.RemoteGaugeBlockEntity.UNCONFIGURED_LOCAL_NETWORK;
            if (panel == null || !panel.addPanel(slot, localNetwork)) {
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
            panel.setRemoteGauge(slot, remote);
            if (remote && !tuned) {
                // FactoryPanelBehaviour cannot serialize null. The marker is never treated as an
                // actual Create logistics network by Distant Stock's ordering path.
                panel.panels.get(slot).setNetwork(
                        dev.distantstock.block.RemoteGaugeBlockEntity.UNCONFIGURED_LOCAL_NETWORK);
            }
            if (!event.getEntity().isCreative()) stack.shrink(1);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    /**
     * Retunes only an existing distant gauge panel's local Create inventory network.
     *
     * <p>This is the second half of "place first, configure later". RightClickBlock fires before the
     * block's own {@code useItemOn}; without this branch the generic panel-placement guard sees the
     * occupied slot and cancels the click as "cannot place another panel", so the player's tuning
     * gesture never reaches the gauge.
     */
    private static void retuneExistingRemoteGauge(PlayerInteractEvent.RightClickBlock event,
                                                  FactoryPanelBlockEntity board,
                                                  FactoryPanelBlock.PanelSlot slot,
                                                  ItemStack stack) {
        var level = event.getLevel();
        var player = event.getEntity();
        java.util.UUID network = LogisticallyLinkedBlockItem.networkFromStack(
                FactoryPanelBlockItem.fixCtrlCopiedStack(stack));
        if (network == null) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        if (!level.mayInteract(player, board.getBlockPos())
                || !player.mayUseItemAt(board.getBlockPos(), event.getHitVec().getDirection(), stack)) {
            return;
        }
        if (!level.isClientSide) {
            if (!com.simibubi.create.Create.LOGISTICS.mayInteract(network, player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.distantstock.network.interact_denied"), true);
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
            board.panels.get(slot).setNetwork(network);
            board.setChanged();
            board.notifyUpdate();
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.distantstock.remote_gauge.local_network_bound"), true);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    private static boolean isRemoteGauge(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        if (board instanceof dev.distantstock.block.RemoteGaugeBlockEntity) {
            return dev.distantstock.block.RemoteGaugeBlockEntity.isOurPanel(board.panels.get(slot));
        }
        if (board instanceof dev.distantstock.block.SignalPanelBlockEntity signal) {
            return signal.isRemoteGauge(slot);
        }
        return net.neoforged.fml.ModList.get().isLoaded("deployer")
                && dev.distantstock.panel.DeployerPanels.holdsRemoteGauge(board, slot);
    }

    /**
     * Puts a panel from another mod on one of our boards by asking the board to take it.
     *
     * <p>This click never reaches the board on its own. The player is aiming past a free slot at the
     * wall behind it — a slot with no panel in it has no hitbox, so that is the only way to point at
     * one — and an ordinary placement is what answers: a plain factory gauge is set down where the
     * board stood, and the board entity, with every warehouse binding and lamp on it, is destroyed.
     * Create's own board survives the same click because its placement branch recognises its own
     * block; ours is a different block that branch will never know, and a mod's panel item has no
     * reason to care which board it is aimed at.
     *
     * <p>So the click is aimed at the board instead of at the wall. {@code useItemOn} is where a
     * panel item's own placement lives — Create's reads it there, and so does anything built on top
     * of Create's panel — and it is the only place that knows which kind of panel is being put
     * down. If nothing takes it, the player gets a refusal rather than a board he has to rebuild.
     */
    private static void handToTheBoard(PlayerInteractEvent.RightClickBlock event, Level level,
                                       BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                                       ItemStack stack) {
        BlockHitResult aimed = new BlockHitResult(event.getHitVec().getLocation(),
                event.getHitVec().getDirection(), pos, false);
        InteractionResult result = level.isClientSide
                ? InteractionResult.SUCCESS
                : state.useItemOn(stack, level, event.getEntity(), event.getHand(), aimed).result();
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    /**
     * Puts a remote gauge into a free slot of a board that is not ours — and leaves the board alone.
     *
     * <p>This is the whole reason for depending on Deployer, and it only runs when Deployer is
     * present: instead of turning the board into one of our blocks so that our own slots exist, the
     * panel is added to the board as a kind of panel, and whoever's block it was keeps being that
     * block. A player can put one beside a plain factory gauge, or beside a gauge from another mod,
     * on a board they already built.
     *
     * <p>Without Deployer the click falls through to the older path and the board is converted:
     * worse, but it is what has always happened and it needs nothing installed.
     */
    private static void installOnSomeoneElsesBoard(PlayerInteractEvent.RightClickBlock event,
                                                   Level level, BlockPos pos,
                                                   net.minecraft.world.level.block.state.BlockState state,
                                                   ItemStack stack) {
        if (!level.mayInteract(event.getEntity(), pos)
                || !event.getEntity().mayUseItemAt(pos, event.getHitVec().getDirection(), stack)) {
            return;
        }
        var slot = FactoryPanelBlock.getTargetedSlot(pos, state, event.getHitVec().getLocation());
        if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity board)
                || board.panels.get(slot).isActive()) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        if (!level.isClientSide) {
            var network = FactoryPanelBlockItem.isTuned(stack)
                    ? LogisticallyLinkedBlockItem.networkFromStack(
                    FactoryPanelBlockItem.fixCtrlCopiedStack(stack))
                    : dev.distantstock.block.RemoteGaugeBlockEntity.UNCONFIGURED_LOCAL_NETWORK;
            if (!dev.distantstock.panel.DeployerPanels.install(board, slot, network)) {
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
            if (!event.getEntity().isCreative()) {
                stack.shrink(1);
            }
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    /** Whether this stack is a block that is some kind of factory panel. */
    private static boolean isPanelItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FactoryPanelBlock;
    }

    private static boolean isOurBoard(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(ModBlocks.REMOTE_GAUGE.get()) || state.is(ModBlocks.SIGNAL_PANEL.get());
    }
}
