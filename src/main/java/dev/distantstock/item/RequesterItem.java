package dev.distantstock.item;

import dev.distantstock.menu.MenuSync;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public final class RequesterItem extends Item {
    public RequesterItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new RequesterMenu(id, inv, hand),
                    Component.translatable("gui.distantstock.title")
            ), buf -> MenuSync.writeItem(buf, hand, RequesterData.freq(stack)));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.distantstock.requester.desc").withStyle(ChatFormatting.AQUA));
        UUID freq = RequesterData.freq(stack);
        if (freq == null) {
            tip.add(Component.translatable("gui.distantstock.untuned").withStyle(ChatFormatting.GRAY));
        } else {
            tip.add(Component.translatable("gui.distantstock.freq", RequesterData.shortFreq(freq))
                    .withStyle(ChatFormatting.DARK_AQUA));
            String addr = RequesterData.address(stack);
            if (!addr.isEmpty()) {
                tip.add(Component.literal(addr).withStyle(ChatFormatting.WHITE));
            }
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return RequesterData.tuned(stack);
    }
}
