package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every loaded tower base, in the same shape as {@link LoadedDocks}.
 *
 * <p>A tower is identified by where it stands — dimension and base position — not by a generated
 * id. Rebuilding at the same coordinates is the same tower, which is what a player who takes a
 * mast apart and puts it back expects; a UUID minted at build time would need its own file, its own
 * invalidation and its own way of being wrong after a crash, and would buy nothing, because the
 * position already is unique.
 *
 * <p>Registration follows the dock's full set of callbacks rather than the gauge's single one:
 * {@code destroy}, {@code remove} and {@code onChunkUnloaded} all drop the entry. A gauge that
 * misses one of them only stops announcing its frequency for a while, but a tower left behind would
 * keep carrying devices from an unloaded chunk, and the parcels it pays for would come out of a
 * block entity that no longer exists.
 */
public final class LoadedTowers {
    private static final Set<TowerCoreBlockEntity> ALL = ConcurrentHashMap.newKeySet();

    public static void add(TowerCoreBlockEntity be) {
        ALL.add(be);
    }

    public static void remove(TowerCoreBlockEntity be) {
        ALL.remove(be);
    }

    /**
     * Every loaded tower base, sorted by dimension and position.
     *
     * <p>The order matters: everything downstream has to be able to recompute the same answer from
     * the same set, and a hash set hands its entries out in whatever order it likes. Single digits
     * of towers means the sort is free.
     */
    public static List<TowerCoreBlockEntity> all() {
        List<TowerCoreBlockEntity> towers = new ArrayList<>();
        for (TowerCoreBlockEntity be : ALL) {
            Level level = be.getLevel();
            if (be.isRemoved() || level == null || level.isClientSide) {
                continue;
            }
            towers.add(be);
        }
        towers.sort(Comparator
                .comparing((TowerCoreBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        return List.copyOf(towers);
    }

    /** The tower base at a position, for the code that only kept the owner position of a ticket. */
    public static TowerCoreBlockEntity at(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return null;
        }
        for (TowerCoreBlockEntity be : ALL) {
            if (!be.isRemoved() && be.getLevel() == level && be.getBlockPos().equals(pos)) {
                return be;
            }
        }
        return null;
    }

    /** Drops a level's towers. See {@link LoadedDocks#forget}: a client level never says goodbye. */
    public static void forget(Level level) {
        ALL.removeIf(be -> be.getLevel() == level);
    }

    private LoadedTowers() {
    }
}
