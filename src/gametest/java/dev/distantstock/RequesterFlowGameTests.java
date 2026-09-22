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
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;

/** Exercises the requester service with real Create packaging; only the conveyor handoff is simulated. */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class RequesterFlowGameTests {

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void remoteRedstoneRequesterDropKeepsDistantScopeWithoutWarehouseBinding(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.REMOTE_REDSTONE_REQUESTER.get().defaultBlockState(), 3);
        var requester = (dev.distantstock.block.RemoteRedstoneRequesterBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(requester != null, "远仓红石请求器没有方块实体");

        UUID scope = UUID.randomUUID();
        requester.setDistantNetworkScope(scope);
        h.assertTrue(requester.binding() == null,
                "测试夹具意外给只有远仓 scope 的请求器创建了仓库 binding");

        CompoundTag safe = new CompoundTag();
        requester.writeSafe(safe, level.registryAccess());
        h.assertTrue(safe.hasUUID("DistantNetworkScope") && scope.equals(safe.getUUID("DistantNetworkScope")),
                "请求器掉落数据没有保存独立的 Distant Stock network scope");

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.REMOTE_REDSTONE_REQUESTER.get().defaultBlockState(), 3);
        var reloaded = (dev.distantstock.block.RemoteRedstoneRequesterBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(reloaded != null && reloaded != requester, "重新放置后请求器没有重建");
        reloaded.loadWithComponents(safe, level.registryAccess());

        h.assertTrue(scope.equals(reloaded.distantNetworkScope()),
                "只有 scope、尚未绑定来源仓库的请求器在搬动后退回了 Legacy/未加入状态");
        h.assertTrue(reloaded.binding() == null,
                "搬动一个尚未选择来源仓库的请求器凭空生成了 binding");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void freshDockItemBindsToCreateNetworkUsingStableNodeIdentity(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        UUID localNode = dev.distantstock.link.TranserverBridge.localNodeUuid();
        h.assertTrue(localNode != null, "stable Transerver node identity is unavailable");

        UUID freq = UUID.randomUUID();
        BlockPos linkPos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(linkPos, net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse("create:stock_link")).defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), 3);
        var link = (PackagerLinkBlockEntity) level.getBlockEntity(linkPos);
        h.assertTrue(link != null, "Create stock link did not create its block entity");

        var logistics = new com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork(freq);
        com.simibubi.create.Create.LOGISTICS.logisticsNetworks.put(freq, logistics);
        try {
            LogisticallyLinkedBehaviour.remove(link.behaviour);
            link.behaviour.freqId = freq;
            logistics.loadedLinks.add(GlobalPos.of(level.dimension(), linkPos));
            LogisticallyLinkedBehaviour.keepAlive(link.behaviour);

            ItemStack dock = new ItemStack(dev.distantstock.item.ModItems.DOCK.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, dock);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(linkPos), Direction.UP, linkPos, false);
            var result = dock.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            h.assertTrue(result.consumesAction(), "a fresh Distant Stock dock could not copy a valid Create network");

            var bound = dev.distantstock.item.RequesterData.network(dock).orElse(null);
            h.assertTrue(bound != null, "dock binding reported success without storing a RemoteNetworkId");
            h.assertTrue(localNode.equals(bound.nodeId()),
                    "dock stored transport-session identity instead of the stable local node identity");
            h.assertTrue(freq.equals(bound.createFrequency()), "dock stored the wrong Create logistics frequency");
        } finally {
            com.simibubi.create.Create.LOGISTICS.logisticsNetworks.remove(freq);
        }
        h.succeed();
    }

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

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void deviceCachedScopeCannotOverrideLiveCreateMembership(GameTestHelper h) {
        var level = h.getLevel();
        UUID freq = UUID.randomUUID();
        UUID localNode = UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId());
        var network = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, localNode,
                dev.distantstock.routing.WorldIdentity.get(level), level.dimension().location().toString(), freq);
        var directory = dev.distantstock.routing.DistantNetworkDirectory.get(level.getServer());
        var formal = directory.create("scope-source-" + freq.toString().substring(0, 8),
                localNode, UUID.randomUUID(), network);

        var oldRows = dev.distantstock.stock.NetworkDirectory.local();
        try {
            dev.distantstock.stock.NetworkDirectory.replaceLocal(java.util.List.of(
                    new dev.distantstock.stock.NetworkDirectory.Entry(freq, "唯一真值仓库", 1,
                            network, true, true, formal.id())));

            ItemStack first = new ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
            ItemStack second = new ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
            UUID staleA = UUID.randomUUID();
            UUID staleB = UUID.randomUUID();
            dev.distantstock.item.RequesterData.setNetwork(first, network, staleA);
            dev.distantstock.item.RequesterData.setNetwork(second, network, staleB);

            h.assertTrue(dev.distantstock.item.RequesterData.formalDistantNetwork(first, level.getServer())
                            .filter(formal.id()::equals).isPresent(),
                    "terminal A's cached Distant network overrode the live Create membership");
            h.assertTrue(dev.distantstock.item.RequesterData.formalDistantNetwork(second, level.getServer())
                            .filter(formal.id()::equals).isPresent(),
                    "terminal B's different cached Distant network overrode the same live membership");
        } finally {
            dev.distantstock.stock.NetworkDirectory.replaceLocal(oldRows);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void freshRequestDeskHasNoCreateOrDistantNetworkContext(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.GAUGE.get().defaultBlockState(), 3);
        var desk = (dev.distantstock.block.GaugeBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(desk != null && desk.freq() == null && desk.networkId() == null
                        && !desk.hasDistantNetworkId(),
                "a freshly placed request desk already carried a Create/Distant network binding");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        dev.distantstock.menu.MenuSync.writeGauge(wire, pos, desk);
        var opened = dev.distantstock.menu.RequesterMenu.fromNetwork(34, player.getInventory(), wire);
        h.assertTrue(opened.openedNetworkId() == null && opened.openedDistantNetworkId().isEmpty(),
                "a fresh request desk's open payload invented a Distant network context");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void stalePortableLocalNodeIdResolvesToLiveSingleplayerWarehouse(GameTestHelper h) {
        var level = h.getLevel();
        UUID freq = UUID.randomUUID();
        UUID liveNode = UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId());
        var live = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, liveNode,
                dev.distantstock.routing.WorldIdentity.get(level), level.dimension().location().toString(), freq);
        var stale = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, UUID.randomUUID(),
                UUID.randomUUID(), level.dimension().location().toString(), freq);

        var oldRows = dev.distantstock.stock.NetworkDirectory.local();
        try {
            dev.distantstock.stock.NetworkDirectory.replaceLocal(java.util.List.of(
                    new dev.distantstock.stock.NetworkDirectory.Entry(freq, "本地测试仓库", 1,
                            live, true, true,
                            dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID)));
            h.assertTrue(live.equals(dev.distantstock.menu.MenuSync.resolve(stale, freq)),
                    "a stale portable local node id overruled the live singleplayer warehouse identity");

            var directory = dev.distantstock.routing.DistantNetworkDirectory.get(level.getServer());
            var formal = directory.create("identity-migration-" + freq.toString().substring(0, 8),
                    stale.nodeId(), UUID.randomUUID(), stale);
            h.assertTrue(directory.migrateMemberIdentity(stale, live),
                    "formal membership could not migrate from the old local id to the live id");
            h.assertTrue(directory.formalNetworkOf(live).filter(formal.id()::equals).isPresent(),
                    "migrated live local identity lost its Distant Stock network membership");
            h.assertTrue(directory.networkOf(stale).isEmpty(),
                    "obsolete local member identity remained attached after migration");
        } finally {
            dev.distantstock.stock.NetworkDirectory.replaceLocal(oldRows);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void requesterOpenPayloadCarriesServerSettingsAndClearedPortableGroup(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.GAUGE.get().defaultBlockState(), 3);
        var desk = (dev.distantstock.block.GaugeBlockEntity) level.getBlockEntity(pos);
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        UUID localNode = UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId());
        UUID scope = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        var network = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, localNode,
                dev.distantstock.routing.WorldIdentity.get(level), level.dimension().location().toString(),
                UUID.randomUUID());
        desk.setNetwork(network, scope);
        desk.setAddress("对面-111");
        desk.setHomeAddress("本端-222");
        desk.setReceivingGroup(groupId);

        FriendlyByteBuf gaugeWire = new FriendlyByteBuf(Unpooled.buffer());
        dev.distantstock.menu.MenuSync.writeGauge(gaugeWire, pos, desk);
        var openedDesk = dev.distantstock.menu.RequesterMenu.fromNetwork(31, player.getInventory(), gaugeWire);
        h.assertTrue(network.equals(openedDesk.openedNetworkId()),
                "request desk open payload lost its RemoteNetworkId");
        h.assertTrue(openedDesk.openedDistantNetworkId().filter(scope::equals).isPresent(),
                "request desk open payload lost its Distant Stock network");
        h.assertTrue("对面-111".equals(openedDesk.openedAddress())
                        && "本端-222".equals(openedDesk.openedHomeAddress()),
                "request desk open payload did not carry its saved addresses");
        h.assertTrue(openedDesk.openedReceivingGroup().filter(groupId::equals).isPresent(),
                "request desk open payload lost its receiving address id");

        ItemStack portable = new ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
        dev.distantstock.item.RequesterData.setNetwork(portable, network, scope);
        dev.distantstock.item.RequesterData.setAddress(portable, "远端");
        dev.distantstock.item.RequesterData.setHomeAddress(portable, "本地");
        dev.distantstock.item.RequesterData.setReceivingGroup(portable, UUID.randomUUID(), "333");
        player.setItemInHand(InteractionHand.MAIN_HAND, portable);
        var serverMenu = new dev.distantstock.menu.RequesterMenu(32, player.getInventory(), InteractionHand.MAIN_HAND);
        serverMenu.writeDockGroup(player, "", dev.distantstock.net.SetDockGroupC2S.SELECT);
        h.assertTrue(dev.distantstock.item.RequesterData.receivingGroup(portable).isEmpty()
                        && dev.distantstock.item.RequesterData.receivingGroupName(portable).isEmpty(),
                "clearing 333 from a portable requester did not clear the server ItemStack");

        FriendlyByteBuf itemWire = new FriendlyByteBuf(Unpooled.buffer());
        dev.distantstock.menu.MenuSync.writeItem(itemWire, InteractionHand.MAIN_HAND, portable);
        var openedPortable = dev.distantstock.menu.RequesterMenu.fromNetwork(33, player.getInventory(), itemWire);
        h.assertTrue(openedPortable.openedReceivingGroup().isEmpty()
                        && openedPortable.openedReceivingGroupName().isEmpty(),
                "a cleared portable requester resurrected the old 333 in its next open payload");
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
        UUID localNode = UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId());
        var network = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, localNode,
                stableId ? dev.distantstock.routing.WorldIdentity.get(level) : UUID.randomUUID(),
                level.dimension().location().toString(), frequency);
        UUID owner = UUID.randomUUID();
        var distant = dev.distantstock.routing.DistantNetworkDirectory.get(level.getServer())
                .create("request-flow-" + frequency.toString().substring(0, 8), localNode, owner, network);
        var groups = DockGroupDirectory.get(level.getServer());
        UUID from = groups.createForNetwork("from-" + frequency, owner, distant.id(),
                dev.distantstock.routing.DockGroup.Visibility.PUBLIC).id();
        UUID to = groups.createForNetwork("to-" + frequency, owner, distant.id(),
                dev.distantstock.routing.DockGroup.Visibility.PUBLIC).id();
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
            // openNetworks() deliberately ignores a Create network until at least one stock link is
            // loaded. The keepAlive bookkeeping settles on Create's tick; make the visibility fact
            // explicit here because this assertion is about Distant Stock's singleplayer node id,
            // not Create's delayed registration timing.
            com.simibubi.create.Create.LOGISTICS.logisticsNetworks.get(frequency).loadedLinks.add(
                    net.minecraft.core.GlobalPos.of(level.dimension(), packPos.above()));
            packager.recheckIfLinksPresent();
            h.assertTrue(packager.getAvailableItems().getCountOf(new ItemStack(Items.IRON_INGOT)) == 640,
                    "fixture packager cannot see its inventory");
            dev.distantstock.stock.StockScanner.scan(level.getServer());
            var scanned = dev.distantstock.stock.NetworkDirectory.findByFreq(frequency).orElse(null);
            h.assertTrue(scanned != null && scanned.local() && scanned.networkId() != null
                            && localNode.equals(scanned.networkId().nodeId()),
                    "singleplayer StockScanner published the local Create network without a stable RemoteNetworkId");
            h.assertTrue(OrderService.place(level.getServer(), network, frequency, distant.id(),
                    "factory", to, List.of(new LinkQueues.Line("minecraft:iron_ingot", 640)), "")
                    == OrderService.Result.QUEUED,
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
