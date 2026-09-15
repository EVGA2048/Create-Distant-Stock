package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.OptionalInt;

/**
 * Where a tower is and how tall it stands.
 *
 * <p>The mast is defined by walking down from the cap: contiguous couplers, then the base core. Any
 * other block in between — air, a wrong block, a coupler stack that never reaches a core — means
 * there is no tower here. Written down once so the renderer, the directory and the goggles all
 * agree on what "complete" means.
 *
 * <p>Not in the scan yet: the base's 3x3 skirt, the shaft under the core, and rotation. Those are
 * the assembly stage's, and they all hang off this same walk.
 */
public final class TowerStructure {
    /** Couplers needed before a mast is a tower at all. The tier table starts here. */
    public static final int MIN_COUPLERS = 5;

    /**
     * The coupler count of the mast under {@code cap}, if it reaches a base core.
     *
     * <p>Empty when the block below is not a coupler, or when the stack runs into anything other
     * than a {@code tower_core}.
     */
    public static OptionalInt mastLength(Level level, BlockPos cap) {
        int count = 0;
        BlockPos cursor = cap.below();
        while (level.getBlockState(cursor).getBlock() instanceof TowerCouplerBlock) {
            count++;
            cursor = cursor.below();
        }
        return level.getBlockState(cursor).getBlock() == ModBlocks.TOWER_CORE.get()
                ? OptionalInt.of(count)
                : OptionalInt.empty();
    }

    /** Whether the mast under this cap is tall enough to count as a tower. */
    public static boolean assembled(Level level, BlockPos cap) {
        return mastLength(level, cap).orElse(0) >= MIN_COUPLERS;
    }

    private TowerStructure() {
    }
}
