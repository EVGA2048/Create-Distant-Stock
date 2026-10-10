package dev.distantstock.item;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.link.TranserverBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.UUID;

/** Logger item that must copy a Create logistics network before it can be placed. */
public final class LoggerItem extends BlockItem {
    public LoggerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null) return InteractionResult.PASS;

        var linked = LogisticallyLinkedBehaviour.get(level, context.getClickedPos(),
                LogisticallyLinkedBehaviour.TYPE);
        if (linked != null) {
            if (!LogisticallyLinkedBehaviour.isValidLink(linked)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.bind_no_network"), true);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                switch (bind(stack, serverLevel, linked.freqId)) {
                    case BOUND -> player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.bound",
                            RequesterData.shortFreq(linked.freqId)), true);
                    case NO_REGISTRY -> player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.bind_failed"), true);
                    case NO_NODE -> player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.bind_no_node"), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!RequesterData.tuned(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.logger.bind_first"), true);
            }
            return InteractionResult.FAIL;
        }
        return super.useOn(context);
    }

    /**
     * Copies a Create logistics network onto the item, as a distant address.
     *
     * <p>Reports what happened rather than assuming it worked. The old version wrote the frequency
     * first and then returned early -- with no message -- when Create's registry was missing or
     * when there was no Transerver node identity. What came out was a stack with a frequency, a
     * glint and a "bound" tooltip but no distant network, which placed a logger that was quietly
     * local-only while the player believed it covered both servers.
     *
     * <p>So nothing is written until the whole address resolves, and a failed attempt leaves the
     * item exactly as it was. The dock item already answers the same two conditions out loud; this
     * is the same rule, kept one file over.
     */
    private static BindOutcome bind(ItemStack stack, ServerLevel level, UUID frequency) {
        if (Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) {
            return BindOutcome.NO_REGISTRY;
        }
        var network = Create.LOGISTICS.logisticsNetworks.get(frequency);
        GlobalPos link = network == null || network.loadedLinks == null || network.loadedLinks.isEmpty()
                ? null : network.loadedLinks.iterator().next();
        ServerLevel home = link == null ? level : level.getServer().getLevel(link.dimension());
        // The dimension a network's first loaded link sits in is the one its identity is taken
        // from, so the two halves of the address always describe the same place.
        if (home == null) home = level;
        UUID node = TranserverBridge.localNodeUuid();
        if (node == null) return BindOutcome.NO_NODE;
        RemoteNetworkId remote = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node,
                WorldIdentity.get(home), home.dimension().location().toString(), frequency);
        UUID distant = DistantNetworkDirectory.get(level.getServer()).scopeOf(remote);
        RequesterData.setFreq(stack, frequency);
        RequesterData.setNetwork(stack, remote, distant);
        return BindOutcome.BOUND;
    }

    /** Why a bind attempt did or did not leave a usable address on the item. */
    private enum BindOutcome {
        BOUND,
        /** Create's own registry is not up yet, so there is no network to copy. */
        NO_REGISTRY,
        /** No Transerver node identity, so this server has no name to put in the address. */
        NO_NODE
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                   net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) {
            RequesterData.clearBinding(stack);
            player.displayClientMessage(Component.translatable(
                    "message.distantstock.logger.unbound"), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        if (RequesterData.tuned(stack)) {
            tooltip.add(Component.translatable("message.distantstock.logger.item_bound",
                    RequesterData.shortFreq(RequesterData.freq(stack))).withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("message.distantstock.logger.item_unbound")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return RequesterData.tuned(stack);
    }
}
