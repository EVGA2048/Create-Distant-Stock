package dev.distantstock.compat.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The neutral seam between Distant Stock and Sable.
 *
 * <p>Nothing outside {@code compat.sable} names a Sable type. The rest of the mod asks this class
 * for a global position, a distance, or a loading ticket; without Sable installed the default
 * implementation answers with vanilla coordinates and does nothing, which is exactly what the mod
 * did before this adaptation existed.
 *
 * <p>The implementation is installed from {@link SableCompat#init()} inside a
 * {@code ModList.isLoaded("sable")} branch, so a pack without Sable never resolves the class that
 * imports {@code dev.ryanhcode.sable.*}.
 */
public final class SableBridge {
    public interface Impl {
        /** A plot position becomes the position the player sees; anything else is unchanged. */
        Vec3 toGlobal(Level level, Vec3 pos);

        /** Distance in global space; either endpoint may be inside a sub-level. */
        double distanceSq(Level level, Vec3 first, Vec3 second);

        /**
         * The coverage space a position belongs to. Two towers in different spaces never merge,
         * and a device is only ever carried by a tower in its own space.
         *
         * <p>Without Sable every position in a dimension shares that dimension's space, which is
         * exactly the old rule. With Sable a ship gets its own space keyed by the sub-level UUID,
         * so a tower flying through the overworld cannot gate every factory standing in it.
         */
        String spaceKey(Level level, BlockPos pos);

        /**
         * Keeps the sub-level a tower stands in loaded while its loading switch is on.
         *
         * @return true when the tower is inside a sub-level, whether or not the ticket could be
         *         added. The caller uses this to stay away from NeoForge chunk tickets either way:
         *         forcing a plot position through the vanilla chunk map is the one thing this
         *         adaptation must never do.
         */
        boolean keepLoaded(Level level, BlockPos tower);

        /** Removes the ticket added by {@link #keepLoaded}. */
        void release(Level level, BlockPos tower);
    }

    private static final Impl VANILLA = new Impl() {
        @Override
        public Vec3 toGlobal(Level level, Vec3 pos) {
            return pos;
        }

        @Override
        public double distanceSq(Level level, Vec3 first, Vec3 second) {
            return first.distanceToSqr(second);
        }

        @Override
        public String spaceKey(Level level, BlockPos pos) {
            return level.dimension().location().toString();
        }

        @Override
        public boolean keepLoaded(Level level, BlockPos tower) {
            return false;
        }

        @Override
        public void release(Level level, BlockPos tower) {
        }
    };

    private static volatile Impl impl = VANILLA;

    public static void install(Impl implementation) {
        impl = implementation == null ? VANILLA : implementation;
    }

    public static Vec3 toGlobal(Level level, Vec3 pos) {
        return impl.toGlobal(level, pos);
    }

    public static double distanceSq(Level level, Vec3 first, Vec3 second) {
        return impl.distanceSq(level, first, second);
    }

    public static String spaceKey(Level level, BlockPos pos) {
        return impl.spaceKey(level, pos);
    }

    public static boolean keepLoaded(Level level, BlockPos tower) {
        return impl.keepLoaded(level, tower);
    }

    public static void release(Level level, BlockPos tower) {
        impl.release(level, tower);
    }

    /** The block a player would point at, after projecting a plot position into the world. */
    public static BlockPos globalBlockPos(Level level, BlockPos pos) {
        return BlockPos.containing(toGlobal(level, Vec3.atCenterOf(pos)));
    }

    private SableBridge() {
    }
}
