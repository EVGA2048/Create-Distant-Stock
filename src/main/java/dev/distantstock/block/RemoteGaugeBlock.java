package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.item.ModItems;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;

import java.util.Arrays;
import java.util.Objects;
import java.util.List;

/** Wall-mounted factory panel, deliberately separate from the requester desk. */
public final class RemoteGaugeBlock extends FactoryPanelBlock {
    public RemoteGaugeBlock(Properties properties) { super(properties); }

    @Override
    public BlockEntityType<? extends FactoryPanelBlockEntity> getBlockEntityType() {
        return ModBlockEntities.REMOTE_GAUGE.get();
    }

    /**
     * Create's panel tick, plus the board's own beat for the panels that order from another server.
     *
     * <p>Both halves are needed and the panels come first. {@code IBE} hands out a
     * {@code SmartBlockEntityTicker} for this board by default, and that is what ticks the four
     * panel behaviours — drop it and the gauges stop animating and stop reading their networks;
     * the ordering beat rides along behind it.
     *
     * <p>The ordering half is server only: a client that ran it would file orders for a world it
     * only holds a copy of.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (!Objects.equals(type, ModBlockEntities.REMOTE_GAUGE.get())) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (!(be instanceof RemoteGaugeBlockEntity gauge)) {
                return;
            }
            PANELS.tick(lvl, pos, st, gauge);
            if (!lvl.isClientSide) {
                RemoteGaugeBlockEntity.serverTick(lvl, pos, st, gauge);
            }
        };
    }

    /** The ticker {@code IBE} would have handed out: it adopts the level, then ticks the panels. */
    private static final SmartBlockEntityTicker<RemoteGaugeBlockEntity> PANELS =
            new SmartBlockEntityTicker<>();

    /**
     * Points one panel at a warehouse, or takes the binding away again.
     *
     * <p>The gesture is the one the docks already use — hold a tuned requester and click the
     * machine — and it resolves to the panel under the crosshair, so a board's four panels are
     * four separate devices that can come from four different places. Sneaking clears it.
     *
     * <p>Only a requester will do, not a Create stock link. The order has to name a warehouse on
     * another node, world and network at once, and a link's UUID names a logistics network in this
     * one world and nothing else — a panel bound to one would have nowhere to send the order.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof RemoteGaugeBlockEntity be)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        if (!(stack.getItem() instanceof RequesterItem)) {
            // A placed remote gauge may be given (or changed to) its local inventory-monitor
            // network after placement. This is intentionally separate from the Distant Stock
            // source network configured below with a requester / Join Code.
            if (stack.getItem() instanceof dev.distantstock.item.RemoteGaugeItem
                    && LogisticallyLinkedBlockItem.isTuned(stack)) {
                PanelSlot slot = getTargetedSlot(pos, state, hit.getLocation());
                if (slot == null || !be.panels.get(slot).isActive()) {
                    return ItemInteractionResult.sidedSuccess(level.isClientSide);
                }
                if (!level.isClientSide) {
                    java.util.UUID localNetwork = LogisticallyLinkedBlockItem.networkFromStack(stack);
                    if (localNetwork != null
                            && com.simibubi.create.Create.LOGISTICS.mayInteract(localNetwork, player)) {
                        be.panels.get(slot).setNetwork(localNetwork);
                        be.setChanged();
                        be.sendData();
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.remote_gauge.local_network_bound"), true);
                    } else {
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.network.interact_denied"), true);
                    }
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        java.util.UUID distantNetworkId = RequesterData.distantNetwork(stack)
                .filter(dev.distantstock.routing.DistantNetworkDirectory::isFormalId)
                .orElse(null);
        if (distantNetworkId == null) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable(
                    "message.distantstock.network.required"), true);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        PanelSlot slot = getTargetedSlot(pos, state, hit.getLocation());
        if (slot == null || !be.panels.get(slot).isActive()) {
            // Nothing to bind onto. Saying so is better than binding a slot that is not there: the
            // player is aiming at the wrong corner of the board.
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.remote_gauge.no_panel"), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) {
            return ItemInteractionResult.sidedSuccess(true);
        }
        if (player.isShiftKeyDown()) {
            be.unbind(slot);
            be.setDistantNetworkScope(slot, null);
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.remote_gauge.unbound"), true);
            return ItemInteractionResult.sidedSuccess(false);
        }
        be.setDistantNetworkScope(slot, distantNetworkId);
        var network = networkFromStack(stack);
        if (network == null) {
            player.displayClientMessage(Component.translatable(
                    "message.distantstock.device.network_paired"), true);
            return ItemInteractionResult.sidedSuccess(false);
        }
        java.util.UUID warehouseScope = RequesterData.formalDistantNetwork(stack, level.getServer())
                .orElse(null);
        if (!distantNetworkId.equals(warehouseScope)) {
            player.displayClientMessage(Component.translatable(
                    "message.distantstock.network.warehouse_other_short"), true);
            return ItemInteractionResult.sidedSuccess(false);
        }
        // A requester carries where its goods come from and where they come out, and both are
        // needed: a panel bound to a warehouse but to no group would order into nowhere.
        be.bind(slot, new RemoteBinding(network, distantNetworkId,
                RequesterData.receivingGroup(stack).orElse(null),
                RequesterData.address(stack), RequesterData.homeAddress(stack)));
        player.displayClientMessage(Component.translatable("gui.distantstock.remote_gauge.bound",
                network.shortLabel()), true);
        return ItemInteractionResult.sidedSuccess(false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (LogisticallyLinkedBlockItem.isTuned(stack)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof RemoteGaugeBlockEntity be)) {
            return;
        }
        // FactoryPanelBehaviour starts life with a random UUID. For an intentionally untuned remote
        // gauge that UUID would look like a real local Create network and could later be queried.
        // Replace it with our explicit serializable "unconfigured local inventory" marker.
        for (var panel : be.panels.values()) {
            if (panel != null && panel.isActive()) {
                panel.setNetwork(RemoteGaugeBlockEntity.UNCONFIGURED_LOCAL_NETWORK);
            }
        }
        be.setChanged();
        be.sendData();
    }

    /** The warehouse a stack names, or null for anything that cannot name one across servers. */
    static RemoteNetworkId networkFromStack(ItemStack stack) {
        if (stack.getItem() instanceof RequesterItem && RequesterData.tuned(stack)) {
            return RequesterData.network(stack).orElse(null);
        }
        return null;
    }

    /**
     * Peels one occupied slot off per hit, so the block is never destroyed carrying more than
     * one.
     *
     * Create's block entity pops a factory gauge for every panel past the first when it is
     * destroyed, with no creative check, and its own one-slot-per-hit routine reads the slot
     * from {@code player.pick}, which misses whenever the crosshair lands on a free slot
     * because those carry no hitbox. Together those made a remote gauge drop Create's block in
     * creative mode. Draining here leaves the destroy pass empty and hands back our own item.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity be
                && be.activePanels() > 1) {
            List<PanelSlot> active = Arrays.stream(PanelSlot.values())
                    .filter(slot -> be.panels.get(slot).isActive())
                    .toList();
            var hit = player.pick(player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1, 1, false);
            PanelSlot aimed = getTargetedSlot(pos, state, hit.getLocation());
            be.removePanel(active.contains(aimed) ? aimed : active.get(0));
            if (!player.isCreative()) {
                player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.REMOTE_GAUGE.get()));
            }
            be.sendData();
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
}
