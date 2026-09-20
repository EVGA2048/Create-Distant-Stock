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
                bind(stack, serverLevel, linked.freqId);
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.logger.bound", RequesterData.shortFreq(linked.freqId)), true);
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

    private static void bind(ItemStack stack, ServerLevel level, UUID frequency) {
        RequesterData.setFreq(stack, frequency);
        if (Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) return;
        var network = Create.LOGISTICS.logisticsNetworks.get(frequency);
        GlobalPos link = network == null || network.loadedLinks == null || network.loadedLinks.isEmpty()
                ? null : network.loadedLinks.iterator().next();
        ServerLevel home = link == null ? level : level.getServer().getLevel(link.dimension());
        if (home == null) home = level;
        String dimension = link == null ? home.dimension().location().toString()
                : link.dimension().location().toString();
        UUID node = TranserverBridge.localNodeUuid();
        if (node == null) return;
        RemoteNetworkId remote = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node,
                WorldIdentity.get(home), dimension, frequency);
        UUID distant = DistantNetworkDirectory.get(level.getServer()).scopeOf(remote);
        RequesterData.setNetwork(stack, remote, distant);
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
