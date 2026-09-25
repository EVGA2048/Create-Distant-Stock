package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Drops any existing hostile lock once when the wearer enters a crouched cloak. */
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

        if (!cloaking) {
            CLOAKED.remove(id);
            return;
        }
        if (wasCloaking) return;
        CLOAKED.add(id);

        // One bounded scan on the transition, not every tick. canBeSeenAsEnemy() then prevents
        // reacquisition for as long as the player stays crouched in the complete suit.
        for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(96), candidate ->
                candidate.getTarget() == player
                        || candidate.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player)) {
            if (mob.getTarget() == player) mob.setTarget(null);
            if (mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
        }
    }

    private EtherCasingStealthServer() {
    }
}
