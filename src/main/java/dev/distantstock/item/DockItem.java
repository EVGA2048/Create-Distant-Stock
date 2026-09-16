package dev.distantstock.item;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import dev.distantstock.block.DockBlock;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.link.TranserverBridge;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import java.util.UUID;

/** Dock item that can copy a Create logistics frequency before placement. */
public final class DockItem extends BlockItem {
    public DockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (player == null || level.isClientSide) {
            return player == null ? InteractionResult.PASS : InteractionResult.sidedSuccess(true);
        }
        if (!level.mayInteract(player, context.getClickedPos())) {
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        var behaviour = LogisticallyLinkedBehaviour.get(level, context.getClickedPos(),
                LogisticallyLinkedBehaviour.TYPE);
        if (player.isShiftKeyDown() && (behaviour == null || !LogisticallyLinkedBehaviour.isValidLink(behaviour))) {
            stack.remove(DataComponents.CUSTOM_DATA);
            player.displayClientMessage(Component.translatable("gui.distantstock.dock_unbound"), true);
            return InteractionResult.SUCCESS;
        }
        if (behaviour != null && !LogisticallyLinkedBehaviour.isValidLink(behaviour)
                && !player.isShiftKeyDown()) {
            // There is a stock link here but it is not attached to a network we could read, so the
            // click used to fall through to placement and look like nothing happened at all.
            // Saying which case it is costs a line and saves the player from guessing.
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.dock_bind_no_network"), true);
            return InteractionResult.FAIL;
        }
        if (behaviour != null && LogisticallyLinkedBehaviour.isValidLink(behaviour)) {
            UUID node = TranserverBridge.nodeId();
            if (node == null) {
                // A distant binding records which server the network lives on, and that identity is
                // the Transerver node id. With no node configured there is nothing to write into the
                // item, so say that instead of blaming the stock link.
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.dock_bind_no_node"), true);
                return InteractionResult.FAIL;
            }
            if (!(level instanceof ServerLevel serverLevel)
                    || Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) {
                player.displayClientMessage(Component.translatable("gui.distantstock.dock_bind_failed"), true);
                return InteractionResult.FAIL;
            }
            var network = Create.LOGISTICS.logisticsNetworks.get(behaviour.freqId);
            GlobalPos link = network == null || network.loadedLinks == null || network.loadedLinks.isEmpty()
                    ? null : network.loadedLinks.iterator().next();
            ServerLevel linkLevel = link == null ? null : serverLevel.getServer().getLevel(link.dimension());
            if (linkLevel == null) {
                return InteractionResult.FAIL;
            }
            RequesterData.setNetwork(stack, new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node,
                    WorldIdentity.get(linkLevel), link.dimension().location().toString(), behaviour.freqId));
            player.displayClientMessage(Component.translatable("gui.distantstock.dock_bound"), true);
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    /**
     * The glint a Create item shows once it has been given a frequency.
     *
     * <p>Every one of Create's linked items does this, and it is the only sign a dock carries before
     * it is placed that it already knows which network it belongs to. Without it a bound dock and a
     * blank one are two identical-looking blocks in the hotbar, and the player has no way to tell
     * which is which short of placing it.
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return RequesterData.network(stack).isPresent();
    }
}
