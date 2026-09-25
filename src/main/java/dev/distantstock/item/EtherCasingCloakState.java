package dev.distantstock.item;

import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-populated render state kept free of client-only Minecraft classes. */
public final class EtherCasingCloakState {
    /** First half: solid casing -> active transparent casing. Second half: casing -> invisible. */
    public static final int ACTIVE_PHASES = 6;
    public static final int PHASES = 12;
    private static final Map<UUID, Integer> PHASE = new HashMap<>();

    public static int get(LivingEntity entity) {
        return entity == null ? 0 : PHASE.getOrDefault(entity.getUUID(), 0);
    }

    public static void tick(LivingEntity entity, boolean cloaking) {
        int current = get(entity);
        int next = cloaking
                ? Math.min(PHASES, current + 1)
                : Math.max(0, current - 1);
        if (next == 0) PHASE.remove(entity.getUUID());
        else PHASE.put(entity.getUUID(), next);
    }

    public static boolean fullyCloaked(LivingEntity entity) {
        return get(entity) >= PHASES;
    }

    public static boolean bodyPhasedOut(LivingEntity entity) {
        return get(entity) > ACTIVE_PHASES;
    }

    private EtherCasingCloakState() {
    }
}
