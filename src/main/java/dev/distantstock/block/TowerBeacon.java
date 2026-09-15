package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A short-lived "something just moved through this tower" flag.
 *
 * <p>The light on top of a tower answers a question that is asked once every few ticks and is true
 * for a fraction of a second at a time: is a parcel crossing this tower right now. Nothing owns
 * that fact — the dock that sent it is somewhere else on the network and the tower has no record
 * of it — so it is kept here instead of being threaded through the delivery path.
 *
 * <p>A flag rather than a counter, with an expiry rather than a clear: a parcel that is dropped,
 * voided or crashes the server mid-flight would otherwise leave a counter that never comes back
 * down, and a tower that glows forever is worse than one that goes dark a little early.
 *
 * <p>Keyed by {@link GlobalPos} because the same coordinates in two dimensions are two different
 * towers, and the overworld and the nether share a coordinate system exactly.
 */
public final class TowerBeacon {
    /** How long one ping keeps the light on, in ticks. Comfortably longer than a dispatch. */
    public static final int DEFAULT_HOLD = 40;

    /** Past this many live entries the map is swept, so towers that never come back cannot pile up. */
    private static final int SWEEP_AT = 256;

    private static final Map<GlobalPos, Long> LIT = new ConcurrentHashMap<>();

    /** Marks the tower on {@code core} as carrying traffic for the next {@link #DEFAULT_HOLD} ticks. */
    public static void ping(Level level, BlockPos core) {
        ping(level, core, DEFAULT_HOLD);
    }

    public static void ping(Level level, BlockPos core, int ticks) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (LIT.size() > SWEEP_AT) {
            long now = level.getGameTime();
            LIT.values().removeIf(expiry -> expiry <= now);
        }
        LIT.put(GlobalPos.of(level.dimension(), core.immutable()), level.getGameTime() + ticks);
    }

    /** Whether the tower on {@code core} has carried traffic recently enough to still be lit. */
    public static boolean busy(Level level, BlockPos core) {
        if (level == null) {
            return false;
        }
        Long expiry = LIT.get(GlobalPos.of(level.dimension(), core));
        if (expiry == null) {
            return false;
        }
        if (expiry <= level.getGameTime()) {
            LIT.remove(GlobalPos.of(level.dimension(), core));
            return false;
        }
        return true;
    }

    private TowerBeacon() {
    }
}
