package dev.distantstock;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.GaugePlacementEvents;
import dev.distantstock.item.ModItems;
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

    /** A factory gauge board facing north on a wall at a known spot. */
    private static BlockPos createBoard(GameTestHelper h) {
        BlockPos wall = h.absolutePos(new BlockPos(2, 2, 3));
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
