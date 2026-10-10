package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherCasingCloakState;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;
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
    private static final DustParticleOptions CYAN = new DustParticleOptions(
            new Vector3f(110 / 255f, 210 / 255f, 200 / 255f), 0.75f);
    private static final DustParticleOptions WHITE = new DustParticleOptions(
            new Vector3f(245 / 255f, 250 / 255f, 255 / 255f), 0.65f);

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        // The server only synchronises the boolean state transition. Animation itself stays purely
        // visual/client-side, so there is no per-tick player packet traffic and no server-side
        // rendering state mixed into gameplay state.
        for (Player player : minecraft.level.players()) {
            int before = EtherCasingCloakState.get(player);
            boolean cloaking = EtherCasingCloakState.isRequested(player.getUUID());
            EtherCasingCloakState.tick(player, cloaking);
            int after = EtherCasingCloakState.get(player);
            if (before != after && after > 0 && after < EtherCasingCloakState.PHASES
                    && (player.tickCount & 1) == 0) {
                phaseParticle(minecraft, player, after);
            }
        }
    }

    private static void phaseParticle(Minecraft minecraft, Player player, int phase) {
        if (minecraft.level == null) return;
        var random = minecraft.level.random;
        // One or two tiny blue/white motes are enough to sell the material transition. The fully
        // cloaked phase emits nothing, so the effect never becomes an "invisible player locator".
        int count = phase == EtherCasingCloakState.ACTIVE_PHASES ? 2 : 1;
        for (int i = 0; i < count; i++) {
            double x = player.getX() + (random.nextDouble() - 0.5) * 0.9;
            double y = player.getY() + 0.25 + random.nextDouble() * 1.45;
            double z = player.getZ() + (random.nextDouble() - 0.5) * 0.9;
            var dust = ((phase + i + player.tickCount) & 1) == 0 ? CYAN : WHITE;
            minecraft.level.addParticle(dust, x, y, z,
                    (random.nextDouble() - 0.5) * 0.015, 0.018,
                    (random.nextDouble() - 0.5) * 0.015);
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
