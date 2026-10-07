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
 * <p>The authored base is part of the structure: the centre core must be surrounded by all eight
 * Distant Casing blocks in the 3x3 ring. The shaft underneath is operational rather than structural:
 * a complete tower can stand without power, but it cannot run until rotation reaches the core.
 */
public final class TowerStructure {
    /** Couplers needed before a mast is a tower at all. The tier table starts here. */
    public static final int MIN_COUPLERS = 5;

    /** A complete mast: how many couplers, and the base it stands on. */
    public record Mast(int couplers, TowerTier tier) {
    }

    /**
     * Whether the core is surrounded by the complete 3x3 Distant Casing ring shown in Ponder.
     * The centre is the core itself; all eight neighbours at the same Y must be tower casing.
     */
    public static boolean baseComplete(Level level, BlockPos core) {
        if (level == null || core == null || !level.getBlockState(core).is(ModBlocks.TOWER_CORE.get())) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (!level.getBlockState(core.offset(dx, 0, dz)).is(ModBlocks.TOWER_CASING.get())) {
                    return false;
                }
            }
        }
        return true;
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

    /**
     * Resolves any structural tower block to its core.
     *
     * <p>The control UI is opened from all four authored tower parts, but every setting belongs to
     * the core. Returning one canonical position keeps the screen, packets and permission checks
     * from having four slightly different ideas of what was clicked.
     */
    public static Optional<BlockPos> coreForPart(Level level, BlockPos part) {
        if (level == null || part == null) return Optional.empty();
        if (level.getBlockState(part).is(ModBlocks.TOWER_CORE.get())) {
            return Optional.of(part.immutable());
        }
        if (level.getBlockState(part).is(ModBlocks.TOWER_CASING.get())) {
            TowerCoreBlockEntity core = TowerCasingBlock.coreFor(level, part);
            return core == null ? Optional.empty() : Optional.of(core.getBlockPos().immutable());
        }
        if (level.getBlockState(part).is(ModBlocks.ETHER_RESONATOR.get())) {
            return coreUnder(level, part).map(BlockPos::immutable);
        }
        if (level.getBlockState(part).is(ModBlocks.TOWER_COUPLER.get())) {
            BlockPos cursor = part;
            for (int i = 0; i < 64; i++) {
                cursor = cursor.below();
                if (level.getBlockState(cursor).is(ModBlocks.TOWER_CORE.get())) {
                    return Optional.of(cursor.immutable());
                }
                if (!level.getBlockState(cursor).is(ModBlocks.TOWER_COUPLER.get())) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the mast under this cap is complete and tall enough to be a tower. */
    public static boolean assembled(Level level, BlockPos cap) {
        return coreUnder(level, cap)
                .filter(core -> baseComplete(level, core))
                .flatMap(core -> mast(level, core)).isPresent();
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
