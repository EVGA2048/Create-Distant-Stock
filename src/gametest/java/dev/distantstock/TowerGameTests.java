package dev.distantstock;

import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.TowerStructure;
import dev.distantstock.block.TowerTier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A tower, and the things that look like one but are not.
 *
 * <p>The rule is a base, contiguous couplers, and a resonator on top. Every test here is one way to
 * fail it: too short, a gap, no cap, no base. The arena is 8 tall, which is exactly enough for the
 * shortest tower there is — the taller tiers are the table's business and are checked as numbers.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class TowerGameTests {
    private static final int X = 3;
    private static final int Z = 3;

    /**
     * The tier table's edges, without a world.
     *
     * <p>Four couplers is a pile of parts; five is the first tower. Past seventeen the tier stops
     * climbing but the tower does not stop working — it reads as capped rather than as broken,
     * because a player who stacked one segment too many should not have to take the mast apart to
     * find out which one was one too many.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void tierTableEdges(GameTestHelper h) {
        h.assertTrue(TowerTier.forCouplers(0).isEmpty(), "an empty mast is a tower");
        h.assertTrue(TowerTier.forCouplers(4).isEmpty(), "four couplers is a tower");
        h.assertTrue(TowerTier.forCouplers(5).orElseThrow() == TowerTier.I, "five couplers is not tier I");
        h.assertTrue(TowerTier.forCouplers(6).orElseThrow() == TowerTier.I, "six couplers skipped a tier");
        h.assertTrue(TowerTier.forCouplers(7).orElseThrow() == TowerTier.II, "seven couplers is not tier II");
        h.assertTrue(TowerTier.forCouplers(17).orElseThrow() == TowerTier.VII, "seventeen is not tier VII");
        h.assertTrue(TowerTier.forCouplers(40).orElseThrow() == TowerTier.VII, "a tall mast fell off the table");
        h.assertFalse(TowerTier.capped(17), "the top tier reads as capped");
        h.assertTrue(TowerTier.capped(18), "one past the top does not read as capped");

        // Chunk loading has to climb slower than the rest, or the last tiers cost more server than
        // they are worth. Two neighbouring steps with the same side is the shape of that promise.
        h.assertTrue(TowerTier.III.chunkSide() == TowerTier.II.chunkSide(),
                "tier III widened the loaded area as well as the radius");
        h.assertTrue(TowerTier.VII.chunkSide() > TowerTier.V.chunkSide(),
                "the top tiers never widen the loaded area at all");
        for (TowerTier tier : TowerTier.values()) {
            h.assertTrue(tier.devices() > 0 && tier.radius() > 0 && tier.stress() > 0,
                    "tier " + tier + " has a dead value");
            h.assertTrue(tier.couplers() % 2 == 1, "tier " + tier + " has an even threshold");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theShortestTowerIsATower(GameTestHelper h) {
        build(h, TowerTier.I.couplers(), true);
        TowerStructure.Mast mast = TowerStructure.mast(h.getLevel(), base(h)).orElse(null);
        h.assertTrue(mast != null, "a core with five couplers and a cap is not a tower");
        h.assertTrue(mast.couplers() == 5, "counted " + mast.couplers() + " couplers instead of 5");
        h.assertTrue(mast.tier() == TowerTier.I, "the shortest tower is not tier I");
        h.assertTrue(TowerStructure.assembled(h.getLevel(), base(h).above(6)),
                "the cap does not see the mast beneath it");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void oneCouplerShortIsNotATower(GameTestHelper h) {
        build(h, TowerTier.I.couplers() - 1, true);
        h.assertTrue(TowerStructure.mast(h.getLevel(), base(h)).isEmpty(),
                "four couplers was accepted as a tower");
        h.succeed();
    }

    /** The mast has to be contiguous. A hole in the middle is not a shorter tower, it is no tower. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aGapInTheMastBreaksIt(GameTestHelper h) {
        build(h, TowerTier.I.couplers(), true);
        h.setBlock(X, 4, Z, Blocks.AIR.defaultBlockState());
        h.assertTrue(TowerStructure.mast(h.getLevel(), base(h)).isEmpty(),
                "a mast with a hole in it still counted as a tower");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aMastWithoutACapIsNotATower(GameTestHelper h) {
        build(h, TowerTier.I.couplers(), false);
        h.assertTrue(TowerStructure.mast(h.getLevel(), base(h)).isEmpty(),
                "a resonator-less mast counted as a tower");
        h.succeed();
    }

    /**
     * Couplers and a cap standing on plain stone are a pile of parts.
     *
     * <p>Worth its own test because the failure is silent: every block looks right and the only
     * thing missing is the base the whole thing is supposed to stand on.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aMastWithoutABaseIsNotATower(GameTestHelper h) {
        build(h, TowerTier.I.couplers(), true);
        h.setBlock(X, 0, Z, Blocks.STONE.defaultBlockState());
        h.assertTrue(TowerStructure.mast(h.getLevel(), base(h)).isEmpty(),
                "a mast standing on stone counted as a tower");
        // And the cap's own view agrees, which is what the renderer asks.
        h.assertFalse(TowerStructure.assembled(h.getLevel(), base(h).above(6)),
                "the cap still thought it was on a tower");
        h.succeed();
    }

    /**
     * The base's position in the real world.
     *
     * <p>{@code setBlock} takes arena coordinates and {@link TowerStructure} reads the level, so
     * every scan has to be handed the absolute position. Getting this wrong is not a crash: the
     * scans simply read empty space somewhere else, every negative test passes for the wrong
     * reason, and only the positive one notices.
     */
    private static BlockPos base(GameTestHelper h) {
        return h.absolutePos(new BlockPos(X, 0, Z));
    }

    /** Core at the bottom, {@code couplers} segments above it, and a resonator if asked for. */
    private static void build(GameTestHelper h, int couplers, boolean cap) {
        h.setBlock(X, 0, Z, ModBlocks.TOWER_CORE.get().defaultBlockState());
        for (int i = 1; i <= couplers; i++) {
            h.setBlock(X, i, Z, ModBlocks.TOWER_COUPLER.get().defaultBlockState());
        }
        if (cap) {
            h.setBlock(X, couplers + 1, Z, ModBlocks.ETHER_RESONATOR.get().defaultBlockState());
        }
    }

    private TowerGameTests() {
    }
}
