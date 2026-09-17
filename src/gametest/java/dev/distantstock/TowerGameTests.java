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

    /**
     * The board on the monitor's face turns through its readings by itself.
     *
     * <p>The board is a real Create flap display now, and what it shows is pushed in from the
     * server as two lines of text. Both halves are easy to get silently wrong: {@code
     * applyTextManually} on a board whose layout was never built does nothing at all, and a line
     * longer than the board's four characters overflows the face instead of being clipped. Neither
     * raises anything, so both are checked here.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theMonitorBoardTurnsItsPages(GameTestHelper h) {
        h.setBlock(X, 1, Z, ModBlocks.MONITOR.get().defaultBlockState());
        BlockPos pos = h.absolutePos(new BlockPos(X, 1, Z));
        h.assertTrue(h.getLevel().getBlockEntity(pos) instanceof dev.distantstock.block.MonitorBlockEntity,
                "the monitor block has no monitor in it");
        dev.distantstock.block.MonitorBlockEntity be =
                (dev.distantstock.block.MonitorBlockEntity) h.getLevel().getBlockEntity(pos);
        h.runAfterDelay(60, () -> {
            java.util.List<com.simibubi.create.content.trains.display.FlapDisplayLayout> lines = be.getLines();
            h.assertTrue(lines != null && lines.size() >= 2,
                    "the board built " + (lines == null ? "no" : lines.size()) + " lines");
            String label = lines.get(0).getSections().getFirst().getText().getString();
            String value = lines.get(1).getSections().getFirst().getText().getString();
            h.assertTrue(!label.isBlank(), "the board's top line is empty");
            h.assertTrue(!value.isBlank(), "the board's second line is empty");
            h.assertTrue(label.length() <= 4 && value.length() <= 4,
                    "the board was given \"" + label + "\" / \"" + value + "\", which does not fit");
            h.assertTrue(java.util.List.of("TPS", "MSPT", "PING", "BACK", "PEER", "DEV").contains(label),
                    "the board is showing \"" + label + "\", which is not one of its readings");
            h.succeed();
        });
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
            // Taken back down before the case ends. A tower that is turning claims its whole
            // dimension and switches off every distant device outside its reach, and this level is
            // shared with every other case in the run: a tower left standing here does not fail
            // this test, it fails the dock tests in whichever batch runs next.
            h.setBlock(X, 1, Z, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            dev.distantstock.routing.TowerActivation.markDirty();
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

        h.setBlock(X + 1, 1, Z, closed.setValue(dev.distantstock.block.TowerCasingBlock.PORT,
                dev.distantstock.block.TowerCasingBlock.Port.NORTH));
        var open = h.getLevel().getBlockState(casing);
        // The port is one face, and only that face: a pipe arriving at the casing's east side finds
        // a wall even when its north side is open.
        h.assertTrue(dev.distantstock.block.TowerCasingBlock.portTank(h.getLevel(), casing, open,
                        net.minecraft.core.Direction.EAST) == null,
                "a port opened more than the face it was put on");
        h.assertTrue(dev.distantstock.block.TowerCasingBlock.portTank(h.getLevel(), casing, open,
                        net.minecraft.core.Direction.UP) == null,
                "the port opened onto the face the driveshaft uses");

        // Asked the way a pipe asks: through the capability registry, by the block entity it
        // requires. Create's pipes refuse to connect to a block with no block entity at all, so a
        // test that called the block's own helper would pass while every pipe in the game failed.
        h.assertTrue(h.getLevel().getBlockEntity(casing) != null,
                "the casing has no block entity, so no pipe will ever look at it");
        var tank = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                casing, net.minecraft.core.Direction.NORTH);
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

    /**
     * A monitor standing beside a tower is attached to it, without being told which tower.
     *
     * <p>The screen's tower page says "no tower carries this monitor" whenever the snapshot has no
     * answer for its position, and that answer comes from a rebuild that has to have seen the
     * monitor at all. Both halves are easy to get wrong in ways nobody notices from the code — a
     * device registry that never marks the snapshot dirty leaves a monitor standing in plain sight
     * reporting that it is nowhere.
     *
     * <p>Nothing is pinned here, and the tower is left standing without a motor, which is the one
     * arrangement the shared level allows: a tower that is really turning claims its whole
     * dimension and switches off every distant device outside its reach — the mod working as
     * designed — and would leave the dock cases running beside this one with their devices dark.
     * This case used to pin the carrier in to get around that; the monitor finds its tower by the
     * geometry now, so the world alone answers.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aMonitorBesideATowerAttachesToIt(GameTestHelper h) {
        build(h, TowerTier.I.couplers(), true);
        BlockPos tower = h.absolutePos(new BlockPos(X, 0, Z));
        BlockPos monitor = h.absolutePos(new BlockPos(X + 4, 1, Z + 4));
        h.getLevel().setBlock(monitor, ModBlocks.MONITOR.get().defaultBlockState(), 3);

        h.runAfterDelay(60, () -> {
            h.assertTrue(((dev.distantstock.block.TowerCoreBlockEntity)
                            h.getLevel().getBlockEntity(tower)).tier() != null,
                    "the tower never recognised itself");
            dev.distantstock.routing.TowerReadout readout =
                    dev.distantstock.routing.TowerReadout.survey(h.getLevel(), monitor);
            h.assertTrue(readout.attached(),
                    "a monitor five blocks from a tower reported that nothing carries it");
            h.assertTrue(readout.members().size() == 1,
                    "the readout saw " + readout.members().size() + " towers instead of one");
            h.succeed();
        });
    }

    /**
     * The self-lock: a monitor a tower carries nothing to still gets that tower's page.
     *
     * <p>A monitor's whole tower half is drawn on the answer to "who carries me", and every switch
     * that can take that answer away is drawn on the page itself. Switch the tower's device switch
     * off and nothing in its reach is carried any more — this monitor included — so the page folds
     * up and takes with it the switch that would unfold it. The operator is left with a monitor
     * that says there is no tower over it while standing under one, and no way back.
     *
     * <p>The state is pinned rather than switched, because the shared level will not take a tower
     * that is actually turning (see the case above): with the pin the monitor reads exactly as it
     * does under a tower whose switch is off — un-carried and not activated — while the tower
     * standing over it is real iron. The geometry is what has to answer, and it is deliberately
     * outside the pinned seam.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aMonitorNotCarriedStillGetsItsTowersPage(GameTestHelper h) {
        build(h, TowerTier.I.couplers(), true);
        BlockPos tower = h.absolutePos(new BlockPos(X, 0, Z));
        BlockPos monitor = h.absolutePos(new BlockPos(X + 4, 1, Z + 4));
        h.getLevel().setBlock(monitor, ModBlocks.MONITOR.get().defaultBlockState(), 3);

        dev.distantstock.routing.TowerActivation.pinDevice(
                dev.distantstock.routing.TowerSystem.TowerId.of(h.getLevel().dimension(), monitor),
                false, null);
        try {
            h.runAfterDelay(60, () -> {
                h.assertTrue(!dev.distantstock.routing.TowerActivation.active(h.getLevel(), monitor),
                        "the switch did not take: the monitor still reads as activated");
                h.assertTrue(dev.distantstock.routing.TowerActivation.carrier(h.getLevel(), monitor) == null,
                        "the switch did not take: something still carries the monitor");

                dev.distantstock.routing.TowerReadout readout =
                        dev.distantstock.routing.TowerReadout.survey(h.getLevel(), monitor);
                h.assertTrue(readout.attached(),
                        "a switched-off monitor under a tower lost its tower page");
                h.assertTrue(readout.members().size() == 1,
                        "the page came back with " + readout.members().size() + " towers instead of one");
                h.assertTrue(readout.members().getFirst().tier().equals(TowerTier.I.name()),
                        "the page came back with tier " + readout.members().getFirst().tier());

                // The other half of the promise, and the reason the fix cannot be "always attached":
                // a monitor with no tower near it still says so.
                dev.distantstock.routing.TowerReadout far = dev.distantstock.routing.TowerReadout
                        .survey(h.getLevel(), monitor.offset(4096, 0, 0));
                h.assertTrue(!far.attached(), "a monitor four thousand blocks from any tower claimed one");
                h.succeed();
            });
        } finally {
            // 只放掉自己钉的那个。清空整张表会把**同时在跑**的别的用例的 pin 一起擦掉 —— gametest
            // 是并行跑在同一个 JVM 里的，那张表是全局的。
            h.runAfterDelay(120, () -> dev.distantstock.routing.TowerActivation.unpinDevice(
                    dev.distantstock.routing.TowerSystem.TowerId.of(h.getLevel().dimension(), monitor)));
        }
    }
}
