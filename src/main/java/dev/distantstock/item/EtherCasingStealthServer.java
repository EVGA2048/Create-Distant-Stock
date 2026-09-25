package dev.distantstock.item;

import dev.distantstock.DistantStock;
import dev.distantstock.net.CloakStateS2C;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Server authority for cloak phase and hostile-target suppression. */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class EtherCasingStealthServer {
    private static final Set<UUID> CLOAKED = new HashSet<>();

    @SubscribeEvent
    public static void playerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level)) return;
        UUID id = player.getUUID();
        boolean cloaking = EtherCasingArmorItem.isCloaking(player);
        boolean wasCloaking = CLOAKED.contains(id);

        if (cloaking == wasCloaking) {
            return;
        }

        if (cloaking) CLOAKED.add(id);
        else CLOAKED.remove(id);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new CloakStateS2C(id, cloaking));

        if (!cloaking) return;

        // One bounded scan on the transition, not every tick. canBeSeenAsEnemy() then prevents
        // reacquisition for as long as the player stays crouched in the complete suit.
        for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(128), candidate ->
                candidate.getTarget() == player
                        || brainTargets(candidate, player)
                        || candidate instanceof NeutralMob neutral
                        && id.equals(neutral.getPersistentAngerTarget()))) {
            if (mob.getTarget() == player) mob.setTarget(null);
            if (brainTargets(mob, player)) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
            if (mob instanceof NeutralMob neutral && id.equals(neutral.getPersistentAngerTarget())) {
                neutral.stopBeingAngry();
            }
        }
    }

    /**
     * ATTACK_TARGET is not registered in every mob brain. Brain#getMemory deliberately throws for
     * an unregistered module, so querying it blindly while scanning arbitrary mobs can kick the
     * player with "Ticking player". getMemoryInternal returns null for that perfectly normal case.
     */
    private static boolean brainTargets(Mob mob, Player player) {
        var memory = mob.getBrain().getMemoryInternal(MemoryModuleType.ATTACK_TARGET);
        return memory != null && memory.orElse(null) == player;
    }

    /**
     * Some mobs do not run vanilla TargetingConditions when they (re)acquire a victim. Catch the
     * common NeoForge target-change hook as the final gate. Do not cancel: cancelling would keep an
     * old target. Replacing the new target with null makes both Mob#setTarget and Brain behaviours
     * actually drop it.
     */
    @SubscribeEvent
    public static void changeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof Player player
                && EtherCasingArmorItem.isCloaking(player)) {
            event.setNewAboutToBeSetTarget(null);
        }
    }

    /** A player who walks into view halfway through the animation must receive the current phase. */
    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer observer)) return;
        if (!(event.getTarget() instanceof Player target)) return;
        PacketDistributor.sendToPlayer(observer,
                new CloakStateS2C(target.getUUID(), EtherCasingArmorItem.isCloaking(target)));
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        CLOAKED.remove(id);
    }

    private EtherCasingStealthServer() {
    }
}
