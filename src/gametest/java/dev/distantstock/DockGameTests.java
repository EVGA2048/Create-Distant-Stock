package dev.distantstock;

import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.DockStatus;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class DockGameTests {

    /** Handing a parcel to the dock by hand has to use the same slot a hopper fills. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void packageRightClickEntersDock(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(pos);
        var parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, parcel);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var result = level.getBlockState(pos)
                .useItemOn(parcel, level, player, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(result.consumesAction(), "right click with a parcel was refused");
        h.assertTrue(dock != null && dock.displayedStack().is(ModItems.REMOTE_PACKAGE.get()),
                "parcel did not enter the dock");
        h.assertTrue(parcel.isEmpty(), "survival right click did not consume the parcel");
        h.succeed();
    }

    /**
     * A parcel with neither a route of its own nor a configured default has no destination at all.
     * The dock has to keep it and report the missing target instead of handing it to the transport
     * queue, where it would be dropped.
     */
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void routelessPackageStaysInDock(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(pos);
        dock.setExport(UUID.randomUUID());
        h.assertTrue(dock.canSend(), "dock did not enter send mode");
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        h.assertTrue(handler != null && handler.insertItem(0, new ItemStack(ModItems.REMOTE_PACKAGE.get()), false).isEmpty(),
                "parcel did not enter the outgoing slot");
        // Long enough for the dock to reach the end of its transmit window and try to ship.
        h.runAfterDelay(100, () -> {
            h.assertTrue(!dock.displayedStack().isEmpty(),
                    "routeless parcel was sent away instead of being held");
            h.assertTrue(dock.status() == DockStatus.BLOCKED,
                    "routeless parcel did not raise the blocked lamp, got " + dock.status());
            h.succeed();
        });
    }

    private DockGameTests() {
    }
}
