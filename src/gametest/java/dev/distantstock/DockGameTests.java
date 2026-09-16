package dev.distantstock;

import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.DockStatus;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.ModItems;
import net.minecraft.core.BlockPos;
import dev.distantstock.routing.TowerActivation;
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

    /**
     * Two systems, two towers, one parcel: a dock in one group sends it and a dock in the other
     * receives it.
     *
     * <p>This is the whole of "two towers can hand goods to each other", end to end and in one save:
     * the sending dock is on a tower, the receiving dock is on another one, the two are in different
     * dock groups, and the parcel travels on the destination the sender carries rather than on
     * anything either tower knows about the other. Every step between those two is the real one —
     * the ship window, the local branch of the transport, the group lookup, the receiving dock's own
     * insert.
     *
     * <p>Both docks are pinned as carried rather than having real towers built over them. A tower
     * that is really turning claims its whole dimension and switches off every other distant device
     * in it, which in a level shared with the rest of the suite means this case would take half the
     * tests down with it. The pin is the same seam the activation cases use, and it answers exactly
     * the question the gates ask.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aParcelCrossesFromOneTowerToAnother(GameTestHelper h) {
        net.minecraft.server.level.ServerLevel level = h.getLevel();
        java.util.UUID node = java.util.UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId());

        BlockPos senderPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos receiverPos = h.absolutePos(new BlockPos(6, 2, 2));
        level.setBlock(senderPos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        level.setBlock(receiverPos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity sender = (DockBlockEntity) level.getBlockEntity(senderPos);
        DockBlockEntity receiver = (DockBlockEntity) level.getBlockEntity(receiverPos);
        h.assertTrue(sender != null && receiver != null, "the docks did not appear");

        // Two systems with two names, so neither dock can be receiving by accident.
        var directory = dev.distantstock.routing.DockGroupDirectory.get(level.getServer());
        String stamp = java.util.UUID.randomUUID().toString().substring(0, 8);
        dev.distantstock.routing.DockGroup from = directory.createFor("tower-a-" + stamp, null);
        dev.distantstock.routing.DockGroup to = directory.createFor("tower-b-" + stamp, null);
        sender.setGroupId(from.id());
        receiver.setGroupId(to.id());

        // Each on its own tower, and the sender pointed at the other system.
        TowerActivation.pinDevice(dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(), senderPos),
                true, dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(),
                        h.absolutePos(new BlockPos(2, 0, 2))));
        TowerActivation.pinDevice(dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(), receiverPos),
                true, dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(),
                        h.absolutePos(new BlockPos(6, 0, 2))));
        try {
            sender.setExport(java.util.UUID.randomUUID());
            sender.setDefaultDestination(node, to.id());
            h.assertTrue(sender.canSend(), "the sending dock is not in a state to send");

            var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    senderPos, net.minecraft.core.Direction.UP);
            h.assertTrue(handler != null && handler
                            .insertItem(0, new ItemStack(ModItems.REMOTE_PACKAGE.get()), false).isEmpty(),
                    "the parcel did not enter the sending dock");

            // Long enough for the dock's transmit window to open, close, and the transport to hand
            // the parcel over on its own beat.
            h.runAfterDelay(160, () -> {
                h.assertTrue(!receiver.displayedStack().isEmpty(),
                        "the parcel never reached the receiving tower's dock");
                h.assertTrue(sender.displayedStack().isEmpty(),
                        "the parcel is in both docks");
                h.succeed();
            });
        } finally {
            h.runAfterDelay(240, TowerActivation::unpinDevices);
        }
    }

    /**
     * 绑了网络的港在物品栏里要看得出来。
     *
     * <p>Create 的每一个已链接物品都带附魔光效，它是「这个港已经知道自己在哪张网上」在放下之前
     * 唯一的迹象。少了它，绑过的港和空白的港在快捷栏里长得一模一样，只能放下才知道。
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aBoundDockGlintsInTheInventory(GameTestHelper h) {
        ItemStack blank = new ItemStack(ModItems.DOCK.get());
        h.assertTrue(!blank.hasFoil(), "空白的港不该发光");

        ItemStack bound = new ItemStack(ModItems.DOCK.get());
        dev.distantstock.item.RequesterData.setNetwork(bound,
                new dev.distantstock.routing.RemoteNetworkId(
                        dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA,
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                        "minecraft:overworld", java.util.UUID.randomUUID()));
        h.assertTrue(bound.hasFoil(), "绑了网络的港必须发光，否则分不出绑没绑");
        h.succeed();
    }

}
