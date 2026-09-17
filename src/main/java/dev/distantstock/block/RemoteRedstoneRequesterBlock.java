package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlock;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Create's redstone requester in the distant palette, with somewhere further to send its order.
 *
 * <p>The shape, the screen, the nine slots and the pulse are all Create's; the block exists to own
 * a block entity that can be bound to a warehouse on another server. Unbound it is a redstone
 * requester, and it behaves like one.
 */
public final class RemoteRedstoneRequesterBlock extends RedstoneRequesterBlock {
    public RemoteRedstoneRequesterBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends RedstoneRequesterBlockEntity> getBlockEntityType() {
        return ModBlockEntities.REMOTE_REDSTONE_REQUESTER.get();
    }

    /**
     * Points the machine at a warehouse, or takes the binding away again.
     *
     * <p>The same gesture every other distant device uses — hold a tuned requester and click — and
     * sneak clears it. Opening the screen is Create's own empty-hand interaction and is left alone:
     * the items and the address are set there, not here.
     */
    @Override
    protected ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
                                              Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof RequesterItem)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        if (!RequesterData.tuned(stack)) {
            if (!level.isClientSide) {
                RequesterItem.sayUntuned(player);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!(level.getBlockEntity(pos) instanceof RemoteRedstoneRequesterBlockEntity be)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        if (level.isClientSide) {
            return ItemInteractionResult.sidedSuccess(true);
        }
        if (player.isShiftKeyDown()) {
            be.bind(null);
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.remote_gauge.unbound"), true);
            return ItemInteractionResult.sidedSuccess(false);
        }
        var network = RequesterData.network(stack).orElse(null);
        if (network == null) {
            return ItemInteractionResult.sidedSuccess(false);
        }
        be.bind(new RemoteBinding(network, RequesterData.receivingGroup(stack).orElse(null),
                RequesterData.address(stack), RequesterData.homeAddress(stack)));
        player.displayClientMessage(Component.translatable("gui.distantstock.remote_gauge.bound",
                network.shortLabel()), true);
        return ItemInteractionResult.sidedSuccess(false);
    }
}
