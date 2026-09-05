package dev.distantstock.server;

import dev.distantstock.DistantStock;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class ManualGift {
    private static final String ROOT = "PlayerPersisted";
    private static final String KEY = "distantstock.manual";

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!StockConfig.GIVE_MANUAL.get()) {
            return;
        }
        if (!(e.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        CompoundTag persist = data.getCompound(ROOT);
        if (persist.getBoolean(KEY)) {
            return;
        }
        persist.putBoolean(KEY, true);
        data.put(ROOT, persist);
        ItemStack book = new ItemStack(ModItems.MANUAL.get());
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }

    private ManualGift() {
    }
}
