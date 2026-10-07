package dev.distantstock.item;

import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client-populated render state kept free of client-only Minecraft classes. */
public final class EtherCasingCloakState {
    /** First half: solid casing -> active transparent casing. Second half: casing -> invisible. */
    public static final int ACTIVE_PHASES = 6;
    public static final int PHASES = 12;
    private static final Map<UUID, Integer> PHASE = new HashMap<>();
    private static final Set<UUID> REQUESTED = new HashSet<>();

    public static int get(LivingEntity entity) {
        return entity == null ? 0 : get(entity.getUUID());
    }

    public static int get(UUID entity) {
        return entity == null ? 0 : PHASE.getOrDefault(entity, 0);
    }

    /** Server-authoritative phase sync, also used when a new observer starts tracking a player. */
    public static void set(UUID entity, int phase) {
        if (entity == null) return;
        int clamped = Math.max(0, Math.min(PHASES, phase));
        if (clamped == 0) PHASE.remove(entity);
        else PHASE.put(entity, clamped);
    }

    public static void clear(UUID entity) {
        if (entity != null) {
            PHASE.remove(entity);
            REQUESTED.remove(entity);
        }
    }

    public static void clearAll() {
        PHASE.clear();
        REQUESTED.clear();
    }

    /** Client-side desired cloak state, supplied by the server on state transitions. */
    public static void setRequested(UUID entity, boolean cloaking) {
        if (entity == null) return;
        if (cloaking) REQUESTED.add(entity);
        else REQUESTED.remove(entity);
    }

    public static boolean isRequested(UUID entity) {
        return entity != null && REQUESTED.contains(entity);
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
