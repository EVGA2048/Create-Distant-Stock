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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-only rendering for the server-synchronised optical cloak phase. */
@EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
public final class EtherCasingCloakClient {
    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        // The server only synchronises the boolean state transition. Animation itself stays purely
        // visual/client-side, so there is no per-tick player packet traffic and no server-side
        // rendering state mixed into gameplay state.
        for (Player player : minecraft.level.players()) {
            boolean cloaking = player == minecraft.player
                    ? EtherCasingArmorItem.isCloaking(player)
                    : EtherCasingCloakState.isRequested(player.getUUID());
            EtherCasingCloakState.tick(player,
                    cloaking);
        }
    }

    @SubscribeEvent
    public static void renderPlayer(RenderPlayerEvent.Pre event) {
        if (EtherCasingCloakState.fullyCloaked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        // RemotePlayer instances are recreated on reconnect. Never carry an animation phase keyed
        // by an old UUID into the next connection; that stale client-only state is exactly the kind
        // of asymmetry which can make one observer see a frozen/standing player while another does not.
        EtherCasingCloakState.clearAll();
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        EtherCasingCloakState.clearAll();
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
