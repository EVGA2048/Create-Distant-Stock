package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Where a tower is and how tall it stands.
 *
 * <p>A tower is a base with a mast on it and a resonator on top of that. The mast is contiguous
 * couplers with nothing else mixed in, and it has to reach the base — a resonator floating on a
 * stack that never meets a core is a pile of parts, not a tower. Written down once so the renderer,
 * the base's own block entity and the goggle readout cannot drift apart on what "built" means.
 *
 * <p>Not in the scan yet: the base's 3x3 skirt and the shaft underneath it. Neither changes whether
 * a tower exists, only how well it works, and both are the assembly stage's business.
 */
public final class TowerStructure {
    /** Couplers needed before a mast is a tower at all. The tier table starts here. */
    public static final int MIN_COUPLERS = 5;

    /** A complete mast: how many couplers, and the base it stands on. */
    public record Mast(int couplers, TowerTier tier) {
    }

    /**
     * The mast standing on {@code core}, if there is one.
     *
     * <p>Empty when the block above is not a coupler, when the couplers run into anything other than
     * a resonator at the top, or when the mast is shorter than the first tier.
     */
    public static Optional<Mast> mast(Level level, BlockPos core) {
        if (level.getBlockState(core).getBlock() != ModBlocks.TOWER_CORE.get()) {
            return Optional.empty();
        }
        int couplers = 0;
        BlockPos cursor = core.above();
        while (level.getBlockState(cursor).getBlock() instanceof TowerCouplerBlock) {
            couplers++;
            cursor = cursor.above();
        }
        if (!(level.getBlockState(cursor).getBlock() instanceof ResonatorBlock)) {
            return Optional.empty();
        }
        int height = couplers;
        return TowerTier.forCouplers(height).map(tier -> new Mast(height, tier));
    }

    /**
     * The base a cap is standing on, by walking down through the couplers.
     *
     * <p>The renderer's way in: it has the resonator's position and nothing else. Deliberately does
     * not call {@link #mast} — that would walk back up what this just walked down, and the two are
     * asked at different times from different places.
     */
    public static Optional<BlockPos> coreUnder(Level level, BlockPos cap) {
        BlockPos cursor = cap.below();
        int couplers = 0;
        while (level.getBlockState(cursor).getBlock() instanceof TowerCouplerBlock) {
            couplers++;
            cursor = cursor.below();
        }
        return couplers > 0 && level.getBlockState(cursor).getBlock() == ModBlocks.TOWER_CORE.get()
                ? Optional.of(cursor)
                : Optional.empty();
    }

    /** Whether the mast under this cap is complete and tall enough to be a tower. */
    public static boolean assembled(Level level, BlockPos cap) {
        return coreUnder(level, cap).flatMap(core -> mast(level, core)).isPresent();
    }

    /**
     * Whether a tower is turning fast enough to work.
     *
     * <p>Two conditions, and they are not the same: a tower whose network is overstressed reports a
     * speed of zero, and a tower the player has not built a shaft under reports zero because there
     * is no network at all. Both are "not working", which is what every caller wants to know.
     */
    public static boolean running(Level level, BlockPos core) {
        return level.getBlockEntity(core) instanceof TowerCoreBlockEntity be && be.isRunning();
    }

    private TowerStructure() {
    }
}
