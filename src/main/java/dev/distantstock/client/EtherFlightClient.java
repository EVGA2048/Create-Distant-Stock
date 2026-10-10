package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.net.EtherBoostC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
public final class EtherFlightClient {
    private static boolean sentBoosting = false;

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean shouldBoost = player != null
                && player.isFallFlying()
                && player.getItemBySlot(EquipmentSlot.CHEST).is(dev.distantstock.item.ModItems.ETHER_CASING_CHESTPLATE.get())
                && mc.options.keyJump.isDown();
        if (shouldBoost != sentBoosting) {
            PacketDistributor.sendToServer(new EtherBoostC2S(shouldBoost));
            sentBoosting = shouldBoost;
        }
    }

    private EtherFlightClient() {
    }
}
