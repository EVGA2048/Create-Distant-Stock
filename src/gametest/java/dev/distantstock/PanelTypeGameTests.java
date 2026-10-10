package dev.distantstock;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.GaugePlacementEvents;
import dev.distantstock.item.ModItems;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.panel.DeployerPanels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * A remote gauge as a panel type: it sits on somebody else's board, and the board stays theirs.
 *
 * <p>These are the cases that only exist when Create: Deployer is installed. Without it the remote
 * gauge is a block of its own and the only way onto a factory gauge board is to convert that board
 * into one of ours — which is what {@code GaugePlacementEvents} still does for packs that do not
 * have Deployer, and what these cases exist to prove no longer happens when it is there.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class PanelTypeGameTests {
    private static final FactoryPanelBlock.PanelSlot SLOT = FactoryPanelBlock.PanelSlot.BOTTOM_RIGHT;

    /** Create's own panel block, by name: its {@code AllBlocks} entries are not on this classpath. */
    private static final Block CREATE_GAUGE = BuiltInRegistries.BLOCK.get(
            ResourceLocation.fromNamespaceAndPath("create", "factory_gauge"));

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void panelTypeInstallsOnACreateBoard(GameTestHelper h) {
        var board = createBoard(h);
        var be = (FactoryPanelBlockEntity) h.getLevel().getBlockEntity(board);
        UUID network = UUID.randomUUID();

        h.assertTrue(DeployerPanels.install(be, SLOT, network), "the panel would not install");
        h.assertTrue(DeployerPanels.holdsRemoteGauge(be, SLOT), "the slot does not hold our panel");
        h.assertTrue(be.panels.get(SLOT).isActive(), "the panel is in the slot but not enabled");
        h.assertTrue(be.activePanels() == 1, "the board counts a different number of panels");
        h.assertTrue(h.getLevel().getBlockState(board).is(CREATE_GAUGE),
                "install changed the block the board is made of");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aTakenSlotRefusesThePanel(GameTestHelper h) {
        var board = createBoard(h);
        var be = (FactoryPanelBlockEntity) h.getLevel().getBlockEntity(board);

        h.assertTrue(DeployerPanels.install(be, SLOT, UUID.randomUUID()), "the panel would not install");
        h.assertTrue(!DeployerPanels.install(be, SLOT, UUID.randomUUID()),
                "a second panel took a slot that was already occupied");
        h.assertTrue(be.activePanels() == 1, "the refusal still changed the board");
        h.succeed();
    }

    /**
     * The gesture, end to end: a player holding a tuned remote gauge, pointing past a free slot at
     * the wall behind it, on a board that is not ours.
     *
     * <p>This is the case that used to convert the board into one of our blocks. It must now leave
     * the board alone and add a panel of our type to it instead.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void theClickAddsThePanelAndLeavesTheBoardAlone(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos wall = h.absolutePos(new BlockPos(2, 2, 3));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        BlockPos board = wall.north();
        level.setBlock(board, CREATE_GAUGE.defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);

        ItemStack gauge = tunedGauge(h);
        player.setItemInHand(InteractionHand.MAIN_HAND, gauge);

        var state = level.getBlockState(board);
        Vec3 hit = null;
        for (int ix = 0; ix <= 4 && hit == null; ix++) {
            for (int iy = 0; iy <= 4 && hit == null; iy++) {
                Vec3 candidate = Vec3.atLowerCornerOf(wall).add(ix * .25, iy * .25, 0);
                if (FactoryPanelBlock.getTargetedSlot(board, state, candidate) == SLOT) {
                    hit = candidate;
                }
            }
        }
        h.assertTrue(hit != null, "no hit position on the wall resolves to the slot this case aims at");

        player.setPos(hit.x, hit.y, hit.z - 2);
        player.setShiftKeyDown(false);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND,
                wall, new BlockHitResult(hit, Direction.NORTH, wall, false));
        GaugePlacementEvents.install(event);

        h.assertTrue(event.isCanceled(), "the click was left to ordinary placement");
        h.assertTrue(level.getBlockState(board).is(CREATE_GAUGE),
                "the click replaced the board with "
                        + BuiltInRegistries.BLOCK.getKey(level.getBlockState(board).getBlock()));
        var be = (FactoryPanelBlockEntity) level.getBlockEntity(board);
        h.assertTrue(DeployerPanels.holdsRemoteGauge(be, SLOT), "no remote gauge landed in the slot");
        h.assertTrue(gauge.getCount() == 2, "the click did not consume exactly one gauge");
        h.succeed();
    }

    /**
     * A remote gauge on somebody else's board orders by the same rules as one on ours.
     *
     * <p>The rules are the ones the board has always been held to: a panel with no binding files
     * nothing, and a panel whose order is refused does not count it as in flight. Both are checked
     * against the transport's own order queue rather than any state of ours, because what has to be
     * caught is an order that left, wherever it went.
     *
     * <p>This world has no towers and no transport, which is exactly the state a pack without either
     * is in — devices are not gated when nothing is gating them, and an order placed into an
     * unreachable network is refused rather than queued. The other half — an order that actually
     * leaves — needs a peer on the other end of the transport and is not reachable here; the same
     * limit applies to the board's own case, and both are checked in play.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aPanelOnACreateBoardOrdersFromTheFarSide(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos boardPos = createBoard(h);
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(boardPos);
        h.assertTrue(DeployerPanels.install(board, SLOT, UUID.randomUUID()), "the panel would not install");

        var panel = board.panels.get(SLOT);
        panel.setFilter(new ItemStack(ModItems.REMOTE_PACKAGE.get()));
        panel.count = 64;
        h.assertTrue(panel.getLevelInStorage() == 0, "a board with no network read a stock level");

        int before = LinkQueues.orderDepth();
        h.runAfterDelay(40, () -> {
            h.assertTrue(LinkQueues.orderDepth() == before, "an unbound panel filed an order");

            h.assertTrue(DeployerPanels.bind(board, SLOT, new RemoteNetworkId(
                            RemoteNetworkId.CURRENT_SCHEMA, UUID.randomUUID(), UUID.randomUUID(),
                            "minecraft:overworld", UUID.randomUUID()),
                    UUID.randomUUID(), ""), "the panel would not take a binding");
            h.runAfterDelay(60, () -> {
                h.assertTrue(DeployerPanels.outstandingIn(board, SLOT) == 0,
                        "a refused order was counted as in flight");
                h.assertTrue(LinkQueues.orderDepth() == before,
                        "a bound panel with no transport still got an order out");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void theLampLandsOnACreateBoard(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos boardPos = createBoard(h);
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(boardPos);

        ItemStack lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get());
        h.assertTrue(DeployerPanels.installLamp(board, SLOT, lamp), "the lamp would not install");
        h.assertTrue(DeployerPanels.holdsSignalLamp(board, SLOT), "the slot does not hold our lamp");
        h.assertTrue(level.getBlockState(boardPos).is(CREATE_GAUGE),
                "installing a lamp changed the block the board is made of");
        var behaviour = board.panels.get(SLOT);
        h.assertTrue(behaviour.getFilter().is(ModItems.CYAN_INDICATOR_LAMP.get()),
                "the lamp item did not become the panel's filter");
        h.succeed();
    }

    /**
     * A lamp with nothing to look at stays dark.
     *
     * <p>The one reading that would send someone looking in the wrong place is a green light on a
     * panel that is not connected to anything, so "no state" is a real answer here rather than a
     * missing one.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void anUnwiredLampIsDark(GameTestHelper h) {
        BlockPos boardPos = createBoard(h);
        var board = (FactoryPanelBlockEntity) h.getLevel().getBlockEntity(boardPos);
        h.assertTrue(DeployerPanels.installLamp(board, SLOT,
                new ItemStack(ModItems.BRASS_SIGNAL_LAMP.get())), "the lamp would not install");

        var behaviour = (dev.distantstock.panel.SignalLampPanelBehaviour) board.panels.get(SLOT);
        h.assertTrue(behaviour.isLampSlot(), "the slot does not read as a lamp");
        h.assertTrue(behaviour.lampState() == null, "an unwired lamp reported a state");
        h.succeed();
    }

    /**
     * The reported case: a lamp beside a factory gauge on Create's board, as the client sees it.
     *
     * <p>Deployer replaces the slot tag Create writes, and the lamp's filter used to go with it: the
     * client got a lamp panel holding nothing, which is not a lamp, so it was drawn as a factory
     * gauge while the goggles — reading the panel type — still said "signal lamp".
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aLampBesideAGaugeReachesTheClientAsALamp(GameTestHelper h) {
        var level = h.getLevel();
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(createBoard(h));
        h.assertTrue(board.addPanel(FactoryPanelBlock.PanelSlot.TOP_LEFT, UUID.randomUUID()),
                "the factory gauge would not go on");
        h.assertTrue(DeployerPanels.installLamp(board, SLOT,
                new ItemStack(ModItems.ORANGE_INDICATOR_LAMP.get())), "the lamp would not install");

        var mirror = (FactoryPanelBlockEntity) level.getBlockEntity(createBoardAt(h, 5));
        mirror.readClient(board.writeClient(new CompoundTag(), level.registryAccess()),
                level.registryAccess());

        h.assertTrue(mirror.panels.get(SLOT) instanceof dev.distantstock.panel.SignalLampPanelBehaviour,
                "the client does not see a lamp panel in the slot");
        var lamp = (dev.distantstock.panel.SignalLampPanelBehaviour) mirror.panels.get(SLOT);
        h.assertTrue(lamp.isLampSlot(), "the client's lamp lost its item, so it draws as a gauge");
        h.assertTrue(lamp.getFilter().is(ModItems.ORANGE_INDICATOR_LAMP.get()),
                "the client's lamp holds a different item");
        h.assertTrue(mirror.panels.get(FactoryPanelBlock.PanelSlot.TOP_LEFT).isActive(),
                "the factory gauge beside it did not reach the client");
        h.succeed();
    }

    /** Item, mode and binding of a lamp on Create's board survive a reload. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aLampOnACreateBoardSurvivesReload(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = createBoard(h);
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(DeployerPanels.installLamp(board, SLOT,
                new ItemStack(ModItems.RED_INDICATOR_LAMP.get())), "the lamp would not install");
        board.panels.get(SLOT).count = 1;
        UUID freq = UUID.randomUUID();
        h.assertTrue(DeployerPanels.bindLamp(board, SLOT, freq), "the lamp would not bind");

        var reloaded = reload(h, pos, board);

        h.assertTrue(DeployerPanels.holdsSignalLamp(reloaded, SLOT), "the lamp panel was lost");
        var lamp = (dev.distantstock.panel.SignalLampPanelBehaviour) reloaded.panels.get(SLOT);
        h.assertTrue(lamp.getFilter().is(ModItems.RED_INDICATOR_LAMP.get()), "the lamp item was lost");
        h.assertTrue(lamp.inverted(), "the lamp's mode was lost");
        h.assertTrue(freq.equals(lamp.lampNetwork()), "the lamp's binding was lost");
        h.succeed();
    }

    /**
     * Item, amount and frequency of a remote gauge on Create's board survive a reload.
     *
     * <p>Lost, the panel came back with no item to count, and Deployer's base class ignores the
     * frequency it is given, so a retune never stuck either.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aRemoteGaugeOnACreateBoardSurvivesReload(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = createBoard(h);
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        UUID freq = UUID.randomUUID();
        h.assertTrue(DeployerPanels.install(board, SLOT, freq), "the panel would not install");
        var gauge = board.panels.get(SLOT);
        h.assertTrue(freq.equals(gauge.network), "the panel ignored the frequency it was given");
        gauge.setFilter(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
        gauge.count = 12;
        gauge.upTo = true;

        var reloaded = reload(h, pos, board);

        h.assertTrue(DeployerPanels.holdsRemoteGauge(reloaded, SLOT), "the remote gauge was lost");
        var back = reloaded.panels.get(SLOT);
        h.assertTrue(back.getFilter().is(net.minecraft.world.item.Items.IRON_INGOT),
                "the gauge's item was lost");
        h.assertTrue(back.count == 12, "the gauge's amount was lost: " + back.count);
        h.assertTrue(back.upTo, "the gauge's up-to setting was lost");
        h.assertTrue(freq.equals(back.network), "the gauge's frequency was lost");
        h.succeed();
    }

    /**
     * A lamp wired to an Extra Gauges logic gauge follows the gate, not its inverse.
     *
     * <p>The logic gauge keeps {@code redstonePowered} as the opposite of its answer, so a lamp that
     * read it like a Create gauge was lit exactly while the gate was false.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aLampFollowsAnExtraGaugesLogicGauge(GameTestHelper h) {
        var logicType = net.liukrast.deployer.lib.registry.DeployerRegistries.PANEL.get(
                ResourceLocation.fromNamespaceAndPath("extra_gauges", "logic"));
        if (logicType == null) {
            h.fail("extra_gauges is not loaded, so there is no logic gauge to wire a lamp to");
            return;
        }
        var board = (FactoryPanelBlockEntity) h.getLevel().getBlockEntity(createBoard(h));
        var gateSlot = FactoryPanelBlock.PanelSlot.TOP_LEFT;
        var gate = logicType.create(board, gateSlot);
        gate.active = true;
        board.attachBehaviourLate(gate);
        board.panels.put(gateSlot, gate);
        h.assertTrue(DeployerPanels.installLamp(board, SLOT,
                new ItemStack(ModItems.GREEN_INDICATOR_LAMP.get())), "the lamp would not install");
        var lamp = (dev.distantstock.panel.SignalLampPanelBehaviour) board.panels.get(SLOT);

        gate.targeting.add(lamp.getPanelPosition());
        lamp.targetedBy.put(gate.getPanelPosition(),
                new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection(
                        gate.getPanelPosition(), 1));

        gate.redstonePowered = false; // the gate reads true
        h.assertTrue(lamp.lit(), "the lamp is dark while the gate is true");
        gate.redstonePowered = true; // the gate reads false
        h.assertTrue(!lamp.lit(), "the lamp is lit while the gate is false");
        h.succeed();
    }

    /**
     * An Extra Gauges logic gauge pointed at a remote gauge pauses it, as it pauses a factory gauge.
     *
     * <p>Deployer seals the redstone check on its panel types, so a remote gauge that did not read
     * its inputs itself — and had none to read — ignored the gate entirely.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aLogicGaugePausesARemoteGauge(GameTestHelper h) {
        var logicType = net.liukrast.deployer.lib.registry.DeployerRegistries.PANEL.get(
                ResourceLocation.fromNamespaceAndPath("extra_gauges", "logic"));
        if (logicType == null) {
            h.fail("extra_gauges is not loaded, so there is no logic gauge to wire a gauge to");
            return;
        }
        var board = (FactoryPanelBlockEntity) h.getLevel().getBlockEntity(createBoard(h));
        var gateSlot = FactoryPanelBlock.PanelSlot.TOP_LEFT;
        var gate = logicType.create(board, gateSlot);
        gate.active = true;
        board.attachBehaviourLate(gate);
        board.panels.put(gateSlot, gate);
        h.assertTrue(DeployerPanels.install(board, SLOT, UUID.randomUUID()), "the panel would not install");
        var remote = board.panels.get(SLOT);

        gate.targeting.add(remote.getPanelPosition());
        remote.targetedBy.put(gate.getPanelPosition(),
                new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection(
                        gate.getPanelPosition(), 1));

        gate.redstonePowered = false; // the gate reads true
        remote.checkForRedstoneInput();
        h.assertTrue(remote.redstonePowered, "a true gate did not pause the remote gauge");
        gate.redstonePowered = true; // the gate reads false
        remote.checkForRedstoneInput();
        h.assertTrue(!remote.redstonePowered, "a false gate left the remote gauge paused");
        h.succeed();
    }

    /** Saves the board, replaces the block and loads the save into the new one. */
    private static FactoryPanelBlockEntity reload(GameTestHelper h, BlockPos pos,
                                                  FactoryPanelBlockEntity board) {
        var level = h.getLevel();
        CompoundTag saved = board.saveWithoutMetadata(level.registryAccess());
        BlockState state = level.getBlockState(pos);
        level.removeBlock(pos, false);
        level.setBlock(pos, state, 3);
        var reloaded = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        reloaded.loadWithComponents(saved, level.registryAccess());
        return reloaded;
    }

    /** A factory gauge board facing north on a wall at a known spot. */
    private static BlockPos createBoard(GameTestHelper h) {
        return createBoardAt(h, 2);
    }

    private static BlockPos createBoardAt(GameTestHelper h, int x) {
        BlockPos wall = h.absolutePos(new BlockPos(x, 2, 3));
        h.getLevel().setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        BlockPos board = wall.north();
        h.getLevel().setBlock(board, CREATE_GAUGE.defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        return board;
    }

    /** A remote gauge item carrying a frequency, the way a bound terminal hands one over. */
    private static ItemStack tunedGauge(GameTestHelper h) {
        ItemStack stack = new ItemStack(ModItems.REMOTE_GAUGE.get(), 3);
        CompoundTag data = new CompoundTag();
        data.putUUID("Freq", UUID.randomUUID());
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        h.assertTrue(LogisticallyLinkedBlockItem.isTuned(stack), "the remote gauge is not tuned");
        return stack;
    }
}
