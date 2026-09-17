package dev.distantstock;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.OrderService;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Exercises the requester service with real Create packaging; only the conveyor handoff is simulated. */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class RequesterFlowGameTests {
    /**
     * 请求台重启之后还记得自己调在哪张网络上。
     *
     * <p>玩家报的是"重启服务器以后请求台看不见东西了，要拆掉重新放、重新设频率"。这条检查的正是那件
     * 事里唯一属于我们的一段：落盘和读回。世界存档走的是 BlockEntity 的那对方法，而它们的调用时机
     * 和普通方块实体不同（存档时只写、读档时只读，中间还会来一次只给客户端的同步包），少写一个键
     * 或者读错一个键，表现都只是"重启以后它变回没配过的样子"。
     *
     * <p>这里模拟的是一次真正的存取：写进 NBT、把方块拆掉、重新放一个、再读回来。
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void theDeskKeepsItsTuningAcrossAReload(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.GAUGE.get().defaultBlockState(), 3);
        var desk = (dev.distantstock.block.GaugeBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(desk != null, "请求台没有出现");

        UUID freq = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        desk.setFreq(freq);
        desk.setAddress("111");
        desk.setHomeAddress("222");
        desk.setReceivingGroup(group);

        var saved = desk.saveWithoutMetadata(level.registryAccess());

        // 拆掉再放一个：读回来的必须是新那一个，而不是同一个对象记着的旧值。
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.GAUGE.get().defaultBlockState(), 3);
        var reloaded = (dev.distantstock.block.GaugeBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(reloaded != null && reloaded != desk, "重置方块之后请求台没有重建");
        reloaded.loadWithComponents(saved, level.registryAccess());

        h.assertTrue(freq.equals(reloaded.freq()),
                "频率没留下来 —— 重启之后这台机器就不认识自己的库存了：" + reloaded.freq());
        h.assertTrue("111".equals(reloaded.address()), "远端地址没留下来：" + reloaded.address());
        h.assertTrue("222".equals(reloaded.homeAddress()), "本端地址没留下来：" + reloaded.homeAddress());
        h.assertTrue(group.equals(reloaded.receivingGroup()),
                "收货港组没留下来：" + reloaded.receivingGroup());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void localRequestRoutesEveryVanillaPackageToSelectedGroup(GameTestHelper h) {
        runRequest(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void localStableNetworkIdWorksWithoutTranserver(GameTestHelper h) {
        runRequest(h, true);
    }

    private static void runRequest(GameTestHelper h, boolean stableId) {
        var level = h.getLevel();
        UUID frequency = UUID.randomUUID();
        var groups = DockGroupDirectory.get(level.getServer());
        UUID from = groups.create("from-" + frequency).id();
        UUID to = groups.create("to-" + frequency).id();
        BlockPos packPos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(packPos.south(), Blocks.BARREL.defaultBlockState(), 3);
        var barrel = (BarrelBlockEntity) level.getBlockEntity(packPos.south());
        // Ten stacks require more than one nine-slot parcel.
        for (int i = 0; i < 10; i++) barrel.setItem(i, new ItemStack(Items.IRON_INGOT, 64));
        level.setBlock(packPos, net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("create:packager")).defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH), 3);
        level.setBlock(packPos.above(), net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("create:stock_link")).defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        var packager = (PackagerBlockEntity) level.getBlockEntity(packPos);
        var link = (PackagerLinkBlockEntity) level.getBlockEntity(packPos.above());
        // 三个港只有两种地址，而地址已经不参与选港了：谁是收件方由组决定，诱饵港落在另一个组里。
        DockBlockEntity sender = dock(h, new BlockPos(4, 2, 2), from);
        DockBlockEntity target = dock(h, new BlockPos(6, 2, 2), to);
        DockBlockEntity decoy = dock(h, new BlockPos(6, 2, 5), from);
        sender.setExport(frequency);
        int[] received = {0};
        int[] parcels = {0};
        int[] orderId = {-1};
        h.runAfterDelay(5, () -> {
            LogisticallyLinkedBehaviour.remove(link.behaviour);
            link.behaviour.freqId = frequency;
            com.simibubi.create.Create.LOGISTICS.logisticsNetworks.put(frequency,
                    new com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork(frequency));
            LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
            packager.recheckIfLinksPresent();
            h.assertTrue(packager.getAvailableItems().getCountOf(new ItemStack(Items.IRON_INGOT)) == 640,
                    "fixture packager cannot see its inventory");
            var network = stableId ? new dev.distantstock.routing.RemoteNetworkId(
                    dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA,
                    UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId()),
                    dev.distantstock.routing.WorldIdentity.get(level), level.dimension().location().toString(), frequency) : null;
            h.assertTrue(OrderService.place(level.getServer(), network, frequency, "factory", to,
                    List.of(new LinkQueues.Line("minecraft:iron_ingot", 640))) == OrderService.Result.QUEUED,
                    "request was not queued");
        });
        h.succeedWhen(() -> {
            var output = level.getCapability(Capabilities.ItemHandler.BLOCK, sender.getBlockPos(), Direction.UP);
            ItemStack pending = packager.inventory.extractItem(0, 1, true);
            if (!pending.isEmpty() && output.insertItem(0, pending, true).isEmpty()) {
                orderId[0] = PackageItem.getOrderId(pending);
                output.insertItem(0, packager.inventory.extractItem(0, 1, false), false);
            }
            var receiving = level.getCapability(Capabilities.ItemHandler.BLOCK, target.getBlockPos(), Direction.UP);
            ItemStack arrived = receiving.extractItem(0, 1, false);
            if (!arrived.isEmpty()) {
                parcels[0]++;
                var contents = PackageItem.getContents(arrived);
                for (int i = 0; i < contents.getSlots(); i++) {
                    ItemStack item = contents.getStackInSlot(i);
                    h.assertTrue(item.isEmpty() || item.is(Items.IRON_INGOT), "unexpected parcel content");
                    received[0] += item.getCount();
                }
            }
            h.assertTrue(decoy.displayedStack().isEmpty(), "parcel arrived in the wrong dock group");
            h.assertTrue(received[0] == 640, "received " + received[0] + "/640 items in " + parcels[0] + " parcels");
            h.assertTrue(parcels[0] >= 2, "fixture did not exercise a split order");
            h.assertTrue(dev.distantstock.routing.OrderRouteDirectory.get(level.getServer()).find(orderId[0]).isEmpty(),
                    "completed order route was not cleaned up");
            h.assertTrue(barrel.isEmpty() && packager.heldBox.isEmpty() && sender.displayedStack().isEmpty(),
                    "items were duplicated or left at the source");
        });
    }

    private static DockBlockEntity dock(GameTestHelper h, BlockPos relative, UUID group) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(relative);
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(pos);
        dock.setGroupId(group);
        dock.setImport();
        TowerActivation.pinDevice(TowerSystem.TowerId.of(level.dimension(), pos), true,
                TowerSystem.TowerId.of(level.dimension(), pos.below()));
        return dock;
    }
}
