package dev.distantstock.item;

import com.simibubi.create.content.logistics.packagePort.PackagePortItem;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Diagnostic Frogport item with an explicit Create logistics-network scope.
 *
 * <p>It intentionally stays a {@link PackagePortItem}: Create uses that class to flush the
 * client-side chain target after placement. Before placement the player must copy a valid Create
 * logistics frequency from an existing logistics-linked block.</p>
 */
public final class DiagnosticFrogportItem extends PackagePortItem {
    public DiagnosticFrogportItem(Block block, Properties properties) {
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
                            "message.distantstock.diagnostic_frogport.bind_no_network"), true);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && level instanceof ServerLevel) {
                RequesterData.setFreq(stack, linked.freqId);
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.diagnostic_frogport.bound",
                        RequesterData.shortFreq(linked.freqId)), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!RequesterData.tuned(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.diagnostic_frogport.bind_first"), true);
            }
            return InteractionResult.FAIL;
        }
        return super.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                   net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) {
            RequesterData.clearBinding(stack);
            player.displayClientMessage(Component.translatable(
                    "message.distantstock.diagnostic_frogport.unbound"), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        if (RequesterData.tuned(stack)) {
            tooltip.add(Component.translatable(
                    "message.distantstock.diagnostic_frogport.item_bound",
                    RequesterData.shortFreq(RequesterData.freq(stack)))
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable(
                    "message.distantstock.diagnostic_frogport.item_unbound")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return RequesterData.tuned(stack);
    }
}
