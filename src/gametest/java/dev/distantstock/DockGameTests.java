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

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void receiverProbeRequiresSameNetworkAndLiveReceivingDock(GameTestHelper h) {
        var level = h.getLevel();
        UUID localNode = UUID.fromString(dev.distantstock.link.TranserverBridge.localNodeId());
        var member = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, localNode,
                dev.distantstock.routing.WorldIdentity.get(level), level.dimension().location().toString(),
                UUID.randomUUID());
        var distant = dev.distantstock.routing.DistantNetworkDirectory.get(level.getServer())
                .create("probe-" + UUID.randomUUID().toString().substring(0, 8), localNode,
                        UUID.randomUUID(), member);
        var groups = dev.distantstock.routing.DockGroupDirectory.get(level.getServer());
        var live = groups.createForNetwork("333-" + UUID.randomUUID().toString().substring(0, 6),
                null, distant.id(), dev.distantstock.routing.DockGroup.Visibility.PUBLIC);
        var empty = groups.createForNetwork("empty-" + UUID.randomUUID().toString().substring(0, 6),
                null, distant.id(), dev.distantstock.routing.DockGroup.Visibility.PUBLIC);

        BlockPos receiverPos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(receiverPos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity receiver = (DockBlockEntity) level.getBlockEntity(receiverPos);
        receiver.setImport();
        receiver.setGroupId(live.id());
        // setBlock has not necessarily run the BE's onLoad hook before this same test tick; make
        // the live-dock registry explicit because that registry is exactly what the probe reads.
        dev.distantstock.block.LoadedDocks.add(receiver);
        TowerActivation.pinDevice(dev.distantstock.routing.TowerSystem.TowerId.of(
                level.dimension(), receiverPos), true, null);
        try {
            h.assertTrue(dev.distantstock.link.ReceiverProbeService.state(level.getServer(), localNode,
                            distant.id(), live.id()) == dev.distantstock.link.ReceiverProbeService.State.AVAILABLE,
                    "live receiver in the same Distant Stock network was not available");
            h.assertTrue(dev.distantstock.link.ReceiverProbeService.state(level.getServer(), localNode,
                            distant.id(), empty.id()) == dev.distantstock.link.ReceiverProbeService.State.UNAVAILABLE,
                    "an address with no receiving dock was reported available");
            h.assertTrue(dev.distantstock.link.ReceiverProbeService.state(level.getServer(), localNode,
                            UUID.randomUUID(), live.id()) == dev.distantstock.link.ReceiverProbeService.State.UNAVAILABLE,
                    "a live dock leaked across Distant Stock network scope");
            h.succeed();
        } finally {
            TowerActivation.unpinDevice(dev.distantstock.routing.TowerSystem.TowerId.of(
                    level.dimension(), receiverPos));
            dev.distantstock.block.LoadedDocks.remove(receiver);
            groups.delete(live.id());
            groups.delete(empty.id());
        }
    }

    /** Handing a parcel to the dock by hand has to use the same slot a hopper fills. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void packageRightClickEntersDock(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(pos);
        TestTowers.carried(h, pos);
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
     * A named receiving address is not enough by itself: the latest peer announcement must say
     * that at least one dock behind that address can actually receive.  Otherwise the source keeps
     * custody, flashes orange and raises one WARN for the logger instead of handing the parcel to
     * escrow and discovering the missing receiver on the far side.
     */
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void remoteAddressWithNoReceiverStaysAtTheSendingDock(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(pos);
        TestTowers.carried(h, pos);

        UUID remoteNode = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        dev.distantstock.routing.RemoteGroups.get(level.getServer()).add(
                new dev.distantstock.routing.RemoteGroups.Entry(remoteNode, group, "333", "peer",
                        0, true, null, java.util.Map.of(), 0), System.currentTimeMillis());

        dock.setExport(UUID.randomUUID());
        dock.setDefaultDestination(remoteNode, group);
        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        com.simibubi.create.content.logistics.box.PackageItem.addAddress(parcel, "Line-333");
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        h.assertTrue(handler != null && handler.insertItem(0, parcel, false).isEmpty(),
                "parcel did not enter the outgoing bay");

        h.runAfterDelay(100, () -> {
            h.assertTrue(!dock.displayedStack().isEmpty(),
                    "parcel left even though address 333 advertised zero receiving docks");
            h.assertTrue(dock.fallbackSlots() == 0,
                    "no-receiver parcel was returned instead of being held for automatic recovery");
            h.assertTrue(dock.status() == DockStatus.BLOCKED,
                    "no receiver did not switch the dock to the orange blocked lamp");
            // Do not compare the server-global escrow size here: GameTests run in parallel and
            // unrelated parcel tests legitimately enqueue/dequeue their own escrow entries during
            // this 100-tick window. The stronger parcel-local assertion above proves this dock kept
            // custody of the exact outgoing stack instead of handing it to transport.
            String source = dev.distantstock.event.EventRegistry.blockSource(level, pos);
            var event = dev.distantstock.event.EventRegistry.get(level.getServer())
                    .active(dev.distantstock.event.EventRegistry.Codes.DOCK_NO_RECEIVER, "dock", source);
            h.assertTrue(event.isPresent() && event.get().severity() == dev.distantstock.event.EventRegistry.Severity.WARN,
                    "no receiver did not raise the logger WARN event");
            h.succeed();
        });
    }

    /**
     * A parcel with neither a route of its own nor a configured default has no destination at all.
     * It belongs on the physical fallback face, not in the transport queue and not wedged forever in
     * the sending bay.
     */
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void routelessPackageMovesToFallback(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(pos);
        TestTowers.carried(h, pos);
        dock.setExport(UUID.randomUUID());
        h.assertTrue(dock.canSend(), "dock did not enter send mode");
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        h.assertTrue(handler != null && handler.insertItem(0, new ItemStack(ModItems.REMOTE_PACKAGE.get()), false).isEmpty(),
                "parcel did not enter the outgoing slot");
        // Long enough for the dock to reach the end of its transmit window and try to ship.
        h.runAfterDelay(100, () -> {
            h.assertTrue(!dock.displayedStack().isEmpty(),
                    "routeless parcel vanished instead of being returned");
            h.assertTrue(dock.fallbackSlots() == 1,
                    "routeless parcel stayed in the outbound bay instead of moving to fallback");
            h.assertTrue(dock.status() == DockStatus.BLOCKED,
                    "undrained fallback did not raise the blocked lamp, got " + dock.status());
            h.succeed();
        });
    }

    /**
     * 护目镜那一行「接收港组：X（本组 N 个港）」在客户端要说出真名字和真数字。
     *
     * <p>报的是"仓管里 A服港组后面写着 1，摸港却显示本组 0 个港"，而且名字是 `fce02bd0` 这样的
     * uuid 前八位。原因不是算错了，是**在错的机器上算**：护目镜是客户端画的，而客户端没有 server ——
     * `DockGroupDirectory` 查不到（退回 uuid 前缀），`LoadedDocks` 那边连它自己都不算数（
     * `deliverable` 要求"服务端那一份"），所以那个数字永远是 0。和当年「不计费」是同一个坑。
     *
     * <p>所以这条用例查的不是"算得对不对"，是"**客户端手里有没有那份答案**"：把服务端算好的
     * 那一份，按方块更新的方式过一遍，再看客户端画出来的那行字。它必须和这个港真实挂着的组对得上。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theGoggleNamesTheGroupAndCountsItsDocks(GameTestHelper h) {
        var level = h.getLevel();
        var group = dev.distantstock.routing.DockGroupDirectory.get(level.getServer())
                .create("护目镜组 " + java.util.UUID.randomUUID().toString().substring(0, 8));

        BlockPos mine = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos neighbour = h.absolutePos(new BlockPos(4, 2, 2));
        level.setBlock(mine, ModBlocks.DOCK.get().defaultBlockState(), 3);
        level.setBlock(neighbour, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(mine);
        TestTowers.carried(h, mine);
        TestTowers.carried(h, neighbour);
        dock.setGroupId(group.id());
        ((DockBlockEntity) level.getBlockEntity(neighbour)).setGroupId(group.id());

        // 一拍之后再看：方块实体是这一刻才注册进 LoadedDocks 的（onLoad），同刻去数一定是 0。
        // 这个文件里其它用例也都是这么等的。
        h.runAfterDelay(2, () -> {
            int onServer = dev.distantstock.block.LoadedDocks.countInGroup(group.id());
            h.assertTrue(onServer == 2, "服务端自己数出来的是 " + onServer + " 台港，应该是 2");

            // 服务端那一份：写出来的更新标签就是客户端收到的东西。
            var update = dock.getUpdateTag(level.registryAccess());
            String name = update.getString("GroupName");
            int count = update.getInt("GroupDocks");
            h.assertTrue(name.equals(group.name()),
                    "同步给客户端的组名是「" + name + "」，应该是「" + group.name() + "」");
            h.assertTrue(count == 2,
                    "同步给客户端的港数是 " + count + "，这个组里有两台港");

            // 客户端那一份：一个**没有 level 的**方块实体，和客户端一样没有 server 可问。它只读同步
            // 过来的那两个字段，画出来的必须还是真名字和真数字 —— 名字不能退回 uuid 前八位，数字不能是 0。
            var mirror = new DockBlockEntity(dev.distantstock.block.ModBlockEntities.DOCK.get(), mine,
                    ModBlocks.DOCK.get().defaultBlockState());
            mirror.loadClientUpdate(update, level.registryAccess());
            h.assertTrue(mirror.knownGroupName().equals(group.name()),
                    "客户端画出来的组名是「" + mirror.knownGroupName() + "」，应该是「" + group.name() + "」");
            h.assertTrue(mirror.knownGroupDocks() == 2,
                    "客户端画出来的港数是 " + mirror.knownGroupDocks() + "，应该是 2");
            h.assertTrue(mirror.groupId().equals(group.id()),
                    "同步过来的港挂错了组：" + mirror.groupId() + " 而不是 " + group.id());
            h.succeed();
        });
    }

    /**
     * An address is not a destination: a parcel leaving for another node with the wildcard group
     * stays here and says so.
     *
     * <p>The regression this pins down. Pointing a terminal at a dock writes the group the terminal
     * is holding, and a terminal holding none writes the <em>default</em> group — the one every dock
     * belongs to, which means "whoever is listening". So a player who set only an address got a
     * route, the route was not empty, the old gate was satisfied, and the parcel went: across the
     * link to the other node, where it landed in whichever receiving dock matched the address.
     * Nothing reported anything, because from the dock's point of view it had a destination.
     *
     * <p>The line is drawn at the node boundary on purpose. A parcel staying on this node with the
     * default group is the ordinary in-server case and always has been; only a parcel being handed
     * to another server has to name a group a human chose.
     */
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void aParcelWithNoGroupDoesNotLeaveTheNode(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        var dock = (DockBlockEntity) level.getBlockEntity(pos);
        TestTowers.carried(h, pos);
        dock.setExport(UUID.randomUUID());
        // Somewhere else, and the default group: exactly what the gesture leaves behind when the
        // terminal carries no group.
        dock.setDefaultDestination(UUID.randomUUID(), dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID);
        h.assertTrue(dock.canSend(), "dock did not enter send mode");
        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        com.simibubi.create.content.logistics.box.PackageItem.addAddress(parcel, "111");
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        h.assertTrue(handler != null && handler.insertItem(0, parcel, false).isEmpty(),
                "parcel did not enter the outgoing slot");
        h.runAfterDelay(100, () -> {
            h.assertTrue(!dock.displayedStack().isEmpty(),
                    "a parcel with an address and no group was sent to another node anyway");
            h.assertTrue(dock.fallbackSlots() == 1,
                    "a parcel with no receiving address was not moved to the bottom fallback");
            h.assertTrue(dock.status() == DockStatus.BLOCKED,
                    "a parcel with no group did not raise the blocked lamp, got " + dock.status());
            String source = dev.distantstock.event.EventRegistry.blockSource(level, pos);
            var events = dev.distantstock.event.EventRegistry.get(level.getServer());
            h.assertTrue(events.active(dev.distantstock.event.EventRegistry.Codes.DOCK_NO_ADDRESS,
                            "dock", source).isPresent(),
                    "missing receiving address did not raise a shared WARN event");
            h.assertTrue(events.active(dev.distantstock.event.EventRegistry.Codes.DOCK_RETURN_BLOCKED,
                            "dock", source).isPresent(),
                    "blocked fallback did not raise a shared WARN event");
            h.succeed();
        });
    }

    /** A hopper/chute-equivalent inventory below the dock receives an invalid outbound parcel. */
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void noAddressParcelIsAutomaticallyReturnedBelow(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 3, 2));
        BlockPos below = pos.below();
        level.setBlock(below, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(pos);
        TestTowers.carried(h, pos);
        dock.setExport(UUID.randomUUID());
        dock.setDefaultDestination(UUID.randomUUID(),
                dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID);

        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        com.simibubi.create.content.logistics.box.PackageItem.addAddress(parcel, "111");
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        h.assertTrue(handler != null && handler.insertItem(0, parcel, false).isEmpty(),
                "parcel did not enter the outgoing bay");

        h.runAfterDelay(120, () -> {
            var chest = level.getBlockEntity(below);
            h.assertTrue(chest instanceof net.minecraft.world.Container,
                    "the return container disappeared");
            net.minecraft.world.Container inventory = (net.minecraft.world.Container) chest;
            boolean found = false;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).is(ModItems.REMOTE_PACKAGE.get())) {
                    found = true;
                    break;
                }
            }
            h.assertTrue(found, "the invalid parcel was not returned into the inventory below");
            h.assertTrue(dock.displayedStack().isEmpty(),
                    "the dock kept a duplicate after returning the parcel below");
            String source = dev.distantstock.event.EventRegistry.blockSource(level, pos);
            var events = dev.distantstock.event.EventRegistry.get(level.getServer());
            h.assertTrue(events.active(dev.distantstock.event.EventRegistry.Codes.DOCK_NO_ADDRESS,
                            "dock", source).isEmpty(),
                    "resolved no-address return stayed active in the event registry");
            h.assertTrue(events.recent(64).stream().anyMatch(event ->
                            event.code().equals(dev.distantstock.event.EventRegistry.Codes.DOCK_NO_ADDRESS)
                                    && event.sourceId().equals(source) && !event.active()),
                    "resolved no-address return disappeared instead of remaining in history");
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
        var member = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA, node,
                dev.distantstock.routing.WorldIdentity.get(level), level.dimension().location().toString(),
                java.util.UUID.randomUUID());
        var distant = dev.distantstock.routing.DistantNetworkDirectory.get(level.getServer())
                .create("tower-hop-" + stamp, node, java.util.UUID.randomUUID(), member);
        dev.distantstock.routing.DockGroup from = directory.createForNetwork("tower-a-" + stamp, null,
                distant.id(), dev.distantstock.routing.DockGroup.Visibility.PUBLIC);
        dev.distantstock.routing.DockGroup to = directory.createForNetwork("tower-b-" + stamp, null,
                distant.id(), dev.distantstock.routing.DockGroup.Visibility.PUBLIC);
        sender.setGroupId(from.id());
        receiver.setGroupId(to.id());

        // Each on its own tower, and the sender pointed at the other system.
        // carrier 留空：这两台港要"能工作"，而测试世界里没有塔可以记账（见 TestTowers）。
        TowerActivation.pinDevice(dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(), senderPos),
                true, null);
        TowerActivation.pinDevice(dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(), receiverPos),
                true, null);
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
                // 十分钟流量那张表的**入口**在这一端：塔的读数只有在港真的记了一笔之后才有东西可
                // 汇总。这条曾经是断的 —— survey() 把港的环收进 Map 又丢掉了，只剩一个两参的
                // of(towers, devices)，于是仪表上"十分钟流量"永远是 0/0（真机上是 GPT 看出来的）。
                // 聚合那一半由 TowerActivationGameTests 里的用例守着，这里守"港自己有没有记"。
                var sent = sender.traffic();
                var arrived = receiver.traffic();
                h.assertTrue(sent.sent() == 1 && sent.received() == 0,
                        "发件的港记成了 " + sent + "，该是 1/0");
                h.assertTrue(arrived.received() == 1 && arrived.sent() == 0,
                        "收件的港记成了 " + arrived + "，该是 0/1");
                h.succeed();
            });
        } finally {
            h.runAfterDelay(240, () -> {
                TowerActivation.unpinDevice(
                        dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(), senderPos));
                TowerActivation.unpinDevice(
                        dev.distantstock.routing.TowerSystem.TowerId.of(level.dimension(), receiverPos));
            });
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


    /**
     * 卡住的和退回去的东西，溜槽拿得走。
     *
     * <p>报的是"没有收货港组的包裹应该能被下方的交互取走，我放了智能溜槽但是没有"。原因是对外的
     * 那个接口只有一个格子、而且只从"收到"那一格取 —— 卡在发出格里的包裹和回退面上的东西，
     * 自动化根本看不见。修机器不该比玩家手动能做的更少。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aStuckParcelCanBePulledOutFromBelow(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        dev.distantstock.block.DockBlockEntity dock =
                (dev.distantstock.block.DockBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(dock != null, "港没有出现");
        TestTowers.carried(h, pos);

        // 能力问的是"哪个方块的哪一面"：港自己，朝下的那一面。
        var below = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                pos, Direction.DOWN);
        h.assertTrue(below != null, "港的下面读不到物品接口 —— 溜槽就是这么接的");

        ItemStack parcel = new ItemStack(dev.distantstock.item.ModItems.REMOTE_PACKAGE.get());
        com.simibubi.create.content.logistics.box.PackageItem.addAddress(parcel, "没有这个港");
        h.assertTrue(below.insertItem(0, parcel, false).isEmpty(), "包裹塞不进港（0 号格应该是「交给它发」）");

        // 收货模式的港永远不会发这件包裹：它在这里没有出路，所以现在就该能拿走。
        h.assertTrue(!dock.transmitting(), "没有网络也没有传输出口，却被判成正在传输");
        h.assertTrue(!below.extractItem(1, 1, true).isEmpty(),
                "收货模式港里的包裹从下面取不走 —— 智能溜槽就是这么接的");
        h.assertTrue(dock.takeStuck(h.makeMockPlayer(GameType.SURVIVAL)),
                "手动能拿走的包裹，溜槽却拿不走 —— 两条路的规矩不一致");
        h.succeed();
    }
}
