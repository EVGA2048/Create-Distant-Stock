package dev.distantstock.item;

import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.menu.MenuSync;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public final class RequesterItem extends Item {
    /**
     * Says the terminal has not joined a network yet.
     *
     * <p>The gesture that configures a device is the same one that opens its screen, and the two are
     * told apart by whether the terminal is tuned. A click that silently does nothing — or worse,
     * opens the screen the player was not asking for — leaves them with a device they think they
     * have pointed somewhere. Saying it out loud costs one line above the hotbar.
     */
    public static void sayUntuned(net.minecraft.world.entity.player.Player player) {
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("gui.distantstock.untuned"), true);
    }

    public RequesterItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                RequesterData.clearBinding(stack);
                player.displayClientMessage(Component.translatable("item.distantstock.requester.unbound"), true);
                player.getInventory().setChanged();
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            var network = RequesterData.network(stack).orElse(null);
            UUID freq = RequesterData.freq(stack);
            if (!dev.distantstock.stock.CreateNetworkAccess.mayInteract(network, freq, player)) {
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.network.interact_denied"), true);
                return InteractionResultHolder.fail(stack);
            }
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new RequesterMenu(id, inv, hand),
                    Component.translatable("gui.distantstock.title")
            ), buf -> MenuSync.writeItem(buf, hand, stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * The sneak half of the logger gesture: gives a placed logger back to "all events".
     *
     * <p>This has to live here rather than in {@code LoggerBlock#useItemOn}, because a sneak-click
     * never reaches the block. Vanilla's {@code ServerPlayerGameMode#useItemOn} skips
     * {@code BlockState#useItemOn} entirely when the player is sneaking with an item in hand that
     * does not sneak-bypass, and hands the click to {@code Item#useOn} instead. A terminal does not
     * bypass, so the block's own shift branch was unreachable and a placed logger could not be
     * unbound by any gesture at all.
     *
     * <p>Nothing else is claimed here. A plain click belongs to the block -- it answers
     * {@code SUCCESS} while the terminal is held and consumes the interaction -- so this only ever
     * sees the sneak case, and passes on everything that is not a logger.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!(context.getLevel().getBlockEntity(context.getClickedPos())
                instanceof LoggerBlockEntity logger)) {
            return InteractionResult.PASS;
        }
        if (!context.getLevel().isClientSide) {
            logger.clearBinding();
            player.displayClientMessage(Component.translatable(
                    "gui.distantstock.logger.scope_all"), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.distantstock.requester.desc").withStyle(ChatFormatting.AQUA));
        tip.add(Component.translatable("item.distantstock.requester.unbind_hint").withStyle(ChatFormatting.GRAY));
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
