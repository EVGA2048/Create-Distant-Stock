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

    /** Create's blocks by name, because its {@code AllBlocks} entries are not on this classpath. */
    private static net.minecraft.world.level.block.Block block(String path) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", path));
    }

    private TowerGameTests() {
    }

    /**
     * A shaft under the base drives the tower, and a shaft anywhere else does not.
     *
     * <p>Every other case in this file stands a mast up and then asks the snapshot what it carries;
     * not one of them ever put a shaft under it, so "the tower actually turns" was never checked at
     * all. The block only accepts rotation on its underside, which makes the difference between a
     * tower and a very expensive pillar exactly one face wide.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void onlyTheUndersideTakesRotation(GameTestHelper h) {
        // A motor under a shaft under the core: the arrangement the tower is designed around, and
        // the reason the base has to sit one block up off the ground.
        h.setBlock(X, 0, Z, block("creative_motor").defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING,
                        net.minecraft.core.Direction.UP));
        h.setBlock(X, 1, Z, block("shaft").defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        net.minecraft.core.Direction.Axis.Y));
        h.setBlock(X, 2, Z, ModBlocks.TOWER_CORE.get().defaultBlockState());

        dev.distantstock.block.TowerCoreBlockEntity core = (dev.distantstock.block.TowerCoreBlockEntity) h.getLevel()
                .getBlockEntity(h.absolutePos(new BlockPos(X, 2, Z)));
        h.assertTrue(core != null, "the core did not appear");
        h.assertTrue(ModBlocks.TOWER_CORE.get().hasShaftTowards(h.getLevel(),
                        h.absolutePos(new BlockPos(X, 2, Z)), core.getBlockState(),
                        net.minecraft.core.Direction.DOWN),
                "the core refuses a shaft from below");
        h.assertFalse(ModBlocks.TOWER_CORE.get().hasShaftTowards(h.getLevel(),
                        h.absolutePos(new BlockPos(X, 2, Z)), core.getBlockState(),
                        net.minecraft.core.Direction.UP),
                "the core accepts a shaft from above, where the mast goes");

        // Create propagates rotation on the network's own beat, and the motor's speed is a value
        // box setting rather than a fixed number.
        h.runAfterDelay(40, () -> {
            h.assertTrue(Math.abs(core.getSpeed()) > 0,
                    "a driven shaft under the core left the tower standing still");
            h.succeed();
        });
    }

    /**
     * A casing opened with a wrench is a way into the tank, and a closed one is not.
     *
     * <p>A finished tower walls its own base in: the core answers pipes on four sides and a skirt
     * covers all four of them, so without a port the ether has no way in at all. The test fills the
     * tank through a casing rather than reading a capability back, because "the pipe connects" is
     * not the thing that has to be true — the ether arriving is.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aWrenchedCasingLetsEtherIntoTheTank(GameTestHelper h) {
        h.setBlock(X, 1, Z, ModBlocks.TOWER_CORE.get().defaultBlockState());
        dev.distantstock.block.TowerCoreBlockEntity core = (dev.distantstock.block.TowerCoreBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(X, 1, Z)));
        h.assertTrue(core != null, "the core did not appear");

        BlockPos casing = h.absolutePos(new BlockPos(X + 1, 1, Z));
        h.setBlock(X + 1, 1, Z, ModBlocks.TOWER_CASING.get().defaultBlockState());
        var closed = h.getLevel().getBlockState(casing);
        h.assertTrue(dev.distantstock.block.TowerCasingBlock.portTank(h.getLevel(), casing, closed,
                        net.minecraft.core.Direction.UP) == null,
                "a closed casing offered a pipe the tank");

        h.setBlock(X + 1, 1, Z, closed.setValue(dev.distantstock.block.TowerCasingBlock.PORT, true));
        var open = h.getLevel().getBlockState(casing);
        h.assertTrue(dev.distantstock.block.TowerCasingBlock.portTank(h.getLevel(), casing, open,
                        net.minecraft.core.Direction.DOWN) == null,
                "the port opened onto the face the driveshaft uses");

        var tank = dev.distantstock.block.TowerCasingBlock.portTank(h.getLevel(), casing, open,
                net.minecraft.core.Direction.UP);
        h.assertTrue(tank != null, "an open port reached no tank");
        int filled = tank.fill(new net.neoforged.neoforge.fluids.FluidStack(
                dev.distantstock.fluid.ModFluids.ETHER.get(), 250),
                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(filled == 250, "the port took " + filled + " mB instead of 250");
        h.assertTrue(core.ether() == 250,
                "the ether did not arrive in the tower: " + core.ether() + " mB");
        h.succeed();
    }

    /**
     * A casing in the skirt hands the goggles the base's information instead of its own.
     *
     * <p>The base is the only block of a tower that knows anything, and eight casings stand between
     * a player and it. Pointed at a casing, the overlay has to end up at the base — and pointed at a
     * casing that is in no tower at all, at that casing, which is how "no tower here" stays
     * distinguishable from "the tower failed to load".
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aSkirtCasingShowsTheBasesReadout(GameTestHelper h) {
        h.setBlock(X, 0, Z, ModBlocks.TOWER_CORE.get().defaultBlockState());
        h.setBlock(X + 1, 0, Z, ModBlocks.TOWER_CASING.get().defaultBlockState());
        BlockPos core = h.absolutePos(new BlockPos(X, 0, Z));
        BlockPos skirt = h.absolutePos(new BlockPos(X + 1, 0, Z));

        var casing = ModBlocks.TOWER_CASING.get();
        h.assertTrue(casing.getInformationSource(h.getLevel(), skirt,
                        h.getLevel().getBlockState(skirt)).equals(core),
                "a skirt casing did not report the base");

        // A casing on its own, with a base two squares away rather than next to it, reports itself:
        // the ring is the eight squares around the base and nothing else.
        h.setBlock(X + 3, 0, Z, ModBlocks.TOWER_CASING.get().defaultBlockState());
        BlockPos stray = h.absolutePos(new BlockPos(X + 3, 0, Z));
        h.assertTrue(casing.getInformationSource(h.getLevel(), stray,
                        h.getLevel().getBlockState(stray)).equals(stray),
                "a casing in no tower claimed to be part of one");
        h.succeed();
    }
}
