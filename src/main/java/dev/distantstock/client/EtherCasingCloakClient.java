package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.item.EtherCasingCloakState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Client-only optical cloak: the armour shell dissolves in casing-like patches, then the wearer vanishes. */
@EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
public final class EtherCasingCloakClient {
    @SubscribeEvent
    public static void playerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        boolean cloaking = EtherCasingArmorItem.isCloaking(player);
        EtherCasingCloakState.tick(player, cloaking);
    }

    @SubscribeEvent
    public static void renderPlayer(RenderPlayerEvent.Pre event) {
        if (EtherCasingCloakState.fullyCloaked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void renderHand(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (EtherCasingCloakState.bodyPhasedOut(player)) {
            event.setCanceled(true);
        }
    }

    private EtherCasingCloakClient() {
    }
}
