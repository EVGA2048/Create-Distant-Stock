package dev.distantstock;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.RemotePackagerBlockEntity;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Full-machine checks for the custom packager, not just package-item transmutation. */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class RemotePackagerGameTests {

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void inactiveRemotePackagerRefusesOrderBeforePackagingLoop(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(4, 2, 4));
        h.getLevel().setBlock(pos, ModBlocks.REMOTE_PACKAGER.get().defaultBlockState(), 3);
        RemotePackagerBlockEntity packager =
                (RemotePackagerBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(packager != null, "remote packager block entity did not create");
        // Deliberately no TestTowers.carried(): Create must reject before performPackageRequests()
        // rather than flashing the link and spinning attemptToSend() 100 no-op iterations.
        h.assertTrue(packager.isTooBusyFor(LogisticallyLinkedBehaviour.RequestType.PLAYER),
                "inactive remote packager still advertises itself as ready for player orders");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void remotePackagerCreatesAndExportsBluePackage(GameTestHelper h) {
        BlockPos packagerPos = h.absolutePos(new BlockPos(4, 2, 4));
        Direction facing = Direction.EAST;
        BlockPos inventoryPos = packagerPos.relative(facing.getOpposite());

        h.getLevel().setBlock(inventoryPos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = (ChestBlockEntity) h.getLevel().getBlockEntity(inventoryPos);
        h.assertTrue(chest != null, "test chest did not create");
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 8));

        h.getLevel().setBlock(packagerPos,
                ModBlocks.REMOTE_PACKAGER.get().defaultBlockState()
                        .setValue(PackagerBlock.FACING, facing), 3);
        RemotePackagerBlockEntity packager =
                (RemotePackagerBlockEntity) h.getLevel().getBlockEntity(packagerPos);
        h.assertTrue(packager != null, "remote packager block entity did not create");
        TestTowers.carried(h, packagerPos);

        int orderId = 8801;
        RemoteRoute route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        h.assertTrue(OrderRouteDirectory.get(h.getLevel().getServer()).remember(
                        List.of(request(orderId)), route, "222", "接收港-测试"),
                "could not remember remote order route");

        h.runAfterDelay(2, () -> {
            ArrayList<PackagingRequest> requests = new ArrayList<>();
            requests.add(request(orderId));
            packager.attemptToSend(requests);

            h.assertTrue(!packager.heldBox.isEmpty(),
                    "remote packager consumed a request but did not create heldBox");
            h.assertTrue(packager.heldBox.is(ModItems.REMOTE_PACKAGE.get()),
                    "remote packager created a non-blue package");
            h.assertTrue("111".equals(PackageItem.getAddress(packager.heldBox)),
                    "remote packager lost pre-crossing address");
            h.assertTrue("接收港-测试".equals(RemoteRouteData.receivingAddress(packager.heldBox)),
                    "remote packager lost receiving-dock address");
            h.assertTrue("222".equals(RemoteRouteData.homeAddress(packager.heldBox)),
                    "remote packager lost local/home address");

            h.runAfterDelay(24, () -> {
                var handler = h.getLevel().getCapability(
                        Capabilities.ItemHandler.BLOCK, packagerPos, Direction.UP);
                h.assertTrue(handler != null, "remote packager exposes no item capability");
                ItemStack extracted = handler.extractItem(0, 1, false);
                h.assertTrue(extracted.is(ModItems.REMOTE_PACKAGE.get()),
                        "remote packager animation completed but capability exported no blue package");
                h.assertTrue(packager.heldBox.isEmpty(),
                        "exported package remained stuck in heldBox");
                h.succeed();
            });
        });
    }

    private static PackagingRequest request(int id) {
        return PackagingRequest.create(new ItemStack(Items.IRON_INGOT), 1, "111", 0,
                new MutableBoolean(true), 0, id, PackageOrderWithCrafts.simple(List.of()));
    }

    private RemotePackagerGameTests() {}
}
