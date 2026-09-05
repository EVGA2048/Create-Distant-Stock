package dev.distantstock.menu;

import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterFind;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.stock.StockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RequesterMenu extends AbstractContainerMenu {
    public final InteractionHand hand;
    public final BlockPos gaugePos;
    public List<StockCache.Entry> stock = new ArrayList<>();
    public boolean demo;

    public RequesterMenu(int id, Inventory inv, InteractionHand hand) {
        super(ModMenus.REQUESTER.get(), id);
        this.hand = hand;
        this.gaugePos = null;
        refresh(inv.player);
    }

    public RequesterMenu(int id, Inventory inv, BlockPos gaugePos) {
        super(ModMenus.REQUESTER.get(), id);
        this.hand = InteractionHand.MAIN_HAND;
        this.gaugePos = gaugePos;
        refresh(inv.player);
    }

    public static RequesterMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        if (buf.readBoolean()) {
            return new RequesterMenu(id, inv, buf.readBlockPos());
        }
        return new RequesterMenu(id, inv, buf.readEnum(InteractionHand.class));
    }

    public boolean isGauge() {
        return gaugePos != null;
    }

    public GaugeBlockEntity gauge(Player player) {
        if (gaugePos == null || player.level() == null) {
            return null;
        }
        return player.level().getBlockEntity(gaugePos) instanceof GaugeBlockEntity be ? be : null;
    }

    public UUID freq(Player player) {
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return be.freq();
        }
        return RequesterData.freq(device(player));
    }

    public String address(Player player) {
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            return be.address();
        }
        return RequesterData.address(device(player));
    }

    public void writeAddress(Player player, String address) {
        GaugeBlockEntity be = gauge(player);
        if (be != null) {
            be.setAddress(address);
            return;
        }
        ItemStack stack = device(player);
        if (!stack.isEmpty()) {
            RequesterData.setAddress(stack, address);
        }
    }

    public ItemStack device(Player player) {
        if (isGauge()) {
            return new ItemStack(ModBlocks.GAUGE.get());
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof RequesterItem) {
            return stack;
        }
        return RequesterFind.find(player);
    }

    public boolean tuned(Player player) {
        return freq(player) != null;
    }

    public void refresh(Player player) {
        UUID freq = freq(player);
        StockCache.watch(freq);
        stock = new ArrayList<>(StockCache.get(freq));
        demo = StockConfig.DEMO_STOCK.get();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (isGauge()) {
            return gauge(player) != null && player.distanceToSqr(gaugePos.getX() + 0.5, gaugePos.getY() + 0.5, gaugePos.getZ() + 0.5) < 64;
        }
        return device(player).getItem() instanceof RequesterItem;
    }
}
