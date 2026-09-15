package dev.distantstock;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.RemoteGaugeBlockEntity;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.item.GaugePlacementEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A panel from another mod must be able to land on one of our boards, and must not flatten it.
 *
 * <p>Create's factory gauge is replaceable by another factory gauge — putting the same block down
 * again is how a second panel is added — and the branch that turns that gesture into "add a panel"
 * recognises its board by block identity. Our boards are different blocks with the same shape, so
 * that branch never runs for them: the item places a plain factory gauge where the distant board
 * stood, and the board entity, with its warehouse bindings and lamps, is gone.
 *
 * <p>These cases stand in for the mods whose panel items a player will actually be holding. Extra
 * Gauges is used because it is the one in the pack this is played in, and because its items are not
 * Create's: nothing about them passes through our own placement handler.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class PanelBoardGameTests {
    private static final FactoryPanelBlock.PanelSlot SLOT = FactoryPanelBlock.PanelSlot.BOTTOM_RIGHT;

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void foreignPanelLeavesTheDistantBoardStanding(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Item gauge = foreignPanelItem(h);
        if (gauge == null) {
            return;
        }

        BlockPos wall = h.absolutePos(new BlockPos(2, 2, 3));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        BlockPos board = wall.north();
        level.setBlock(board, ModBlocks.REMOTE_GAUGE.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        h.assertTrue(level.getBlockEntity(board) instanceof RemoteGaugeBlockEntity,
                "board did not come up as a distant gauge board");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(gauge, 8));
        h.assertTrue(clickBehindTheBoard(h, player, wall, board),
                "the click was left to ordinary placement, which is what flattens the board");

        h.assertTrue(level.getBlockEntity(board) instanceof RemoteGaugeBlockEntity,
                "a foreign panel item replaced the distant board with "
                        + BuiltInRegistries.BLOCK.getKey(level.getBlockState(board).getBlock()));
        h.assertTrue(level.getBlockState(board).is(ModBlocks.REMOTE_GAUGE.get()),
                "the block standing where the board was is no longer the board");
        var panels = (FactoryPanelBlockEntity) level.getBlockEntity(board);
        var occupant = panels.panels.get(SLOT);
        h.assertTrue(occupant != null && !occupant.getClass().equals(
                        com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour.class),
                "the foreign panel did not land in the targeted slot: " + occupant);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void foreignPanelLeavesTheLampBoardStanding(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Item gauge = foreignPanelItem(h);
        if (gauge == null) {
            return;
        }

        BlockPos wall = h.absolutePos(new BlockPos(2, 2, 3));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        BlockPos board = wall.north();
        level.setBlock(board, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(gauge, 8));
        h.assertTrue(clickBehindTheBoard(h, player, wall, board),
                "the click was left to ordinary placement, which is what flattens the board");

        h.assertTrue(level.getBlockEntity(board) instanceof SignalPanelBlockEntity,
                "a foreign panel item replaced the lamp board with "
                        + BuiltInRegistries.BLOCK.getKey(level.getBlockState(board).getBlock()));
        h.succeed();
    }

    /**
     * Right-clicks the wall behind an empty slot, which is where a player's aim actually lands: a
     * slot with no panel in it has no hitbox, so the click ray reaches the wall behind the board.
     *
     * <p>The interaction event is raised by hand because that is where the game raises it — before
     * the item is ever asked to place anything — and a test that called {@code useOn} directly
     * would be testing a path no player can reach.
     */
    private static boolean clickBehindTheBoard(GameTestHelper h, net.minecraft.world.entity.player.Player player,
                                               BlockPos wall, BlockPos board) {
        var state = h.getLevel().getBlockState(board);
        for (int ix = 0; ix <= 4; ix++) {
            for (int iy = 0; iy <= 4; iy++) {
                Vec3 hit = Vec3.atLowerCornerOf(wall).add(ix * .25, iy * .25, 0);
                if (FactoryPanelBlock.getTargetedSlot(board, state, hit) != SLOT) {
                    continue;
                }
                player.setPos(hit.x, hit.y, hit.z - 2);
                player.setShiftKeyDown(false);
                var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND,
                        wall, new BlockHitResult(hit, Direction.NORTH, wall, false));
                GaugePlacementEvents.install(event);
                return event.isCanceled();
            }
        }
        h.fail("no hit position on the wall resolves to the slot this case aims at");
        return false;
    }

    /** A panel item from another mod, or null when that mod is not loaded. */
    private static Item foreignPanelItem(GameTestHelper h) {
        Item item = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("extra_gauges", "logic_gauge"));
        if (item == Items.AIR) {
            h.fail("extra_gauges is not loaded, so there is no foreign panel item to test with");
            return null;
        }
        return item;
    }
}
