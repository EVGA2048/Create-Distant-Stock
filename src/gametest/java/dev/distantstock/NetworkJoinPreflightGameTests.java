package dev.distantstock;

import dev.distantstock.link.DistantNetworkJoinCodec;
import dev.distantstock.link.DistantNetworkJoinService;
import dev.distantstock.link.TranserverStockService;
import dev.distantstock.link.StockWireCodec;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RoutingChannels;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import net.minecraft.core.GlobalPos;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Exercises the actual asynchronous accept handler, rather than just the wire codec. */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class NetworkJoinPreflightGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void chatNetworkCreationDoesNotEnrollSelectedWarehouse(GameTestHelper h) throws Exception {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "NoAutoEnroll"));
        UUID localNode = TranserverBridge.localNodeUuid();
        var member = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, localNode,
                UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        var terminal = new net.minecraft.world.item.ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
        dev.distantstock.item.RequesterData.setNetwork(terminal, member,
                DistantNetworkDirectory.LEGACY_NETWORK_ID);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, terminal);
        var directory = DistantNetworkDirectory.get(h.getLevel().getServer());
        String name = "command-" + UUID.randomUUID().toString().substring(0, 8);
        h.getLevel().getServer().getCommands().getDispatcher().execute(
                "distantstock network create " + name,
                player.createCommandSourceStack().withPermission(2));
        var network = directory.findByName(name).orElseThrow();
        h.assertTrue(directory.formalNetworkOf(member).isEmpty(),
                "chat command silently imported a Create network without a dock click");
        h.assertTrue(dev.distantstock.item.RequesterData.network(terminal).isEmpty()
                        && dev.distantstock.item.RequesterData.distantNetwork(terminal)
                        .filter(network.id()::equals).isPresent(),
                "chat creation did not keep outer network and warehouse selection separate");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void shortNetworkUuidAndInviteCodeBothJoinSameAuthority(GameTestHelper h) {
        var directory = DistantNetworkDirectory.get(h.getLevel().getServer());
        UUID player = UUID.randomUUID();
        UUID node = TranserverBridge.localNodeUuid();
        var network = directory.create("short-join-" + UUID.randomUUID().toString().substring(0, 8),
                node, player);
        String id = DistantNetworkDirectory.shortJoinId(network.id());
        h.assertTrue(id.length() == 8 && id.matches("[0-9A-F]{8}"),
                "the network's public UUID join alias must be eight hex digits");
        h.assertTrue(directory.findByCode(id).filter(network::equals).isPresent()
                        && directory.findByCode(id.substring(0, 4) + "-" + id.substring(4))
                        .filter(network::equals).isPresent(),
                "both forms of the 8-digit public network ID must resolve the authority");
        h.assertTrue(directory.findByCode(network.joinCode()).filter(network::equals).isPresent(),
                "the existing private join code must remain accepted");
        String reset = directory.resetJoinCode(network.id(), player, node);
        h.assertTrue(!reset.equals(network.joinCode())
                        && directory.findByCode(id).map(row -> row.id().equals(network.id())).orElse(false),
                "resetting the invitation code must not change the network's 8-digit ID");
        UUID replicaId = UUID.randomUUID();
        directory.rememberReplica(replicaId, "replica-" + replicaId.toString().substring(0, 8), UUID.randomUUID());
        h.assertTrue(directory.findByCode(DistantNetworkDirectory.shortJoinId(replicaId)).isEmpty(),
                "a discovery-only replica must not impersonate the local join-code authority");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void chatCreatedReceivingGroupBelongsToJoinedNetwork(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "AddressScopeTest"));
        UUID scope = UUID.randomUUID();
        var item = new net.minecraft.world.item.ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
        dev.distantstock.item.RequesterData.setDistantNetwork(item, scope);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
        String name = "delivery-" + UUID.randomUUID().toString().substring(0, 8);
        server.getCommands().getDispatcher().execute(
                "distantstock group create " + name,
                player.createCommandSourceStack().withPermission(2));
        var group = dev.distantstock.routing.DockGroupDirectory.get(server)
                .findByName(scope, name).orElse(null);
        h.assertTrue(group != null, "the group-create command created an unusable Legacy address");
        h.assertTrue(dev.distantstock.routing.OrderDestination.resolve(
                        server, player.getUUID(), scope, group.id()).allowed(),
                "a newly created receiving address could not be used for cross-server ordering");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    @SuppressWarnings("unchecked")
    public static void fiveThousandStockRowsAreReassembledWithoutLargePackets(GameTestHelper h) throws Exception {
        var remote = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, UUID.randomUUID(),
                UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        var field = TranserverStockService.class.getDeclaredField("OUTSTANDING");
        field.setAccessible(true);
        var pending = (Map<RemoteNetworkId, Object>) field.get(null);
        var type = Class.forName(TranserverStockService.class.getName() + "$Pending");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var handler = TranserverStockService.class.getDeclaredMethod("result", ReceivedMessage.class);
        handler.setAccessible(true);
        int totalItems = 5_000;
        int pages = (totalItems + StockWireCodec.PAGE_ITEMS - 1) / StockWireCodec.PAGE_ITEMS;
        try {
            for (int page = 0; page < pages; page++) {
                UUID queryId = UUID.randomUUID();
                pending.put(remote, constructor.newInstance(queryId, page, System.currentTimeMillis()));
                var rows = new java.util.ArrayList<LinkQueues.Line>();
                int n = Math.min(StockWireCodec.PAGE_ITEMS,
                        totalItems - page * StockWireCodec.PAGE_ITEMS);
                for (int i = 0; i < n; i++) rows.add(new LinkQueues.Line("minecraft:stone", page * 128 + i + 1));
                var stock = new StockWireCodec.Result(queryId, remote, page, pages, rows);
                byte[] wire = StockWireCodec.encodeResult(stock);
                h.assertTrue(wire.length <= StockWireCodec.MAX_BYTES,
                        "warehouse generated an oversized Transerver message");
                var received = new ReceivedMessage(UUID.randomUUID(), RoutingChannels.STOCK_RESULT,
                        remote.nodeId().toString(), TranserverBridge.localNodeId(), queryId.toString(),
                        Instant.now(), Instant.now().plusSeconds(120), "application/x-distantstock", wire);
                var result = (CompletableFuture<DeliveryResult>) handler.invoke(null, received);
                h.assertTrue(result.join() == DeliveryResult.APPLIED,
                        "stock page was not accepted: " + page);
            }
            h.assertTrue(dev.distantstock.stock.StockCache.size(remote) == totalItems,
                    "large warehouse lost stock rows while streaming between servers");
            var cached = dev.distantstock.stock.StockCache.get(remote);
            var first = dev.distantstock.net.StockSyncS2C.pageOf(false, cached, UUID.randomUUID(), 0);
            var last = dev.distantstock.net.StockSyncS2C.pageOf(false, cached, UUID.randomUUID(), pages - 1);
            h.assertTrue(first.items().size() == 128 && first.total() == totalItems
                            && last.offset() + last.items().size() == totalItems,
                    "client UI could not page through the complete stock without a giant packet");
        } finally {
            pending.remove(remote);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void warehouseJoinsOnlyWhenOwnerClicksItsDock(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "JoinStockCheck"));
        var stranger = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        UUID frequency = UUID.randomUUID();
        var logistics = new LogisticsNetwork(frequency);
        logistics.owner = player.getUUID();
        logistics.locked = true;
        logistics.loadedLinks.add(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(net.minecraft.core.BlockPos.ZERO)));
        Create.LOGISTICS.logisticsNetworks.put(frequency, logistics);
        UUID anotherFrequency = UUID.randomUUID();
        var another = new LogisticsNetwork(anotherFrequency);
        another.owner = stranger.getUUID();
        another.loadedLinks.add(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new net.minecraft.core.BlockPos(1, 0, 0))));
        Create.LOGISTICS.logisticsNetworks.put(anotherFrequency, another);
        try {
            dev.distantstock.stock.StockScanner.scan(server);
            var local = dev.distantstock.stock.NetworkDirectory.findByFreq(frequency).orElseThrow();
            var item = new net.minecraft.world.item.ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
            var directory = DistantNetworkDirectory.get(server);
            var network = directory.create("enroll-" + frequency.toString().substring(0, 8),
                    TranserverBridge.localNodeUuid(), player.getUUID());
            dev.distantstock.item.RequesterData.setDistantNetwork(item, network.id());
            var before = dev.distantstock.net.StockSyncS2C.of(false, java.util.List.of(), network.id());
            h.assertTrue(before.networks().isEmpty(),
                    "the warehouse picker leaked unjoined Create networks across scopes");
            h.assertTrue(directory.formalNetworkOf(local.networkId()).isEmpty(),
                    "network creation silently enrolled an unrelated Create warehouse");
            var pos = h.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
            h.getLevel().setBlock(pos, dev.distantstock.block.ModBlocks.DOCK.get().defaultBlockState(), 3);
            var dock = (dev.distantstock.block.DockBlockEntity) h.getLevel().getBlockEntity(pos);
            dock.setNetwork(local.networkId());
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),
                    net.minecraft.core.Direction.UP, pos, false);
            stranger.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item.copy());
            h.getLevel().getBlockState(pos).useItemOn(stranger.getMainHandItem(), h.getLevel(),
                    stranger, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            h.assertTrue(directory.formalNetworkOf(local.networkId()).isEmpty(),
                    "stranger could import someone else's Create warehouse via a dock");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
            h.getLevel().getBlockState(pos).useItemOn(item, h.getLevel(), player,
                    net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            h.assertTrue(directory.formalNetworkOf(local.networkId()).filter(network.id()::equals).isPresent(),
                    "the owner dock click did not enroll the bound Create warehouse");
            var published = dev.distantstock.stock.NetworkDirectory.local().stream()
                    .filter(entry -> network.id().equals(entry.distantNetworkId())).toList();
            h.assertTrue(published.size() == 1 && published.getFirst().networkId().equals(local.networkId()),
                    "network catalog became visible but its warehouse was not advertised");
            var wire = dev.distantstock.link.NetworkAnnouncementCodec.encode(published, 20, 1,
                    java.util.List.of(), java.util.List.of(
                            new dev.distantstock.link.NetworkAnnouncementCodec.CatalogEntry(
                                    network.id(), network.name())));
            var decoded = dev.distantstock.link.NetworkAnnouncementCodec.decodeAnnouncement(wire);
            h.assertTrue(decoded.entries().size() == 1 &&
                            network.id().equals(decoded.entries().getFirst().distantNetworkId()),
                    "ES2 would not receive VL4's warehouse despite seeing the formal network");
            h.assertTrue(dev.distantstock.net.StockSyncS2C.of(false, java.util.List.of(),
                            network.id()).networks().stream().anyMatch(row -> frequency.equals(row.freq())),
                    "the terminal warehouse list did not include the enrolled source network");
            h.assertTrue(dev.distantstock.net.StockSyncS2C.of(false, java.util.List.of(), network.id())
                            .networks().stream().noneMatch(row -> anotherFrequency.equals(row.freq())),
                    "the warehouse picker leaked someone else's unjoined Create network");
        } catch (Exception error) {
            h.fail(error.toString());
        } finally {
            Create.LOGISTICS.logisticsNetworks.remove(frequency);
            Create.LOGISTICS.logisticsNetworks.remove(anotherFrequency);
            dev.distantstock.stock.StockScanner.scan(server);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void actualAnnouncementDiscoversEmptyNetworkWithoutJoining(GameTestHelper h) throws Exception {
        UUID source = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        var catalog = java.util.List.of(new dev.distantstock.link.NetworkAnnouncementCodec.CatalogEntry(
                network, "VL4 empty preflight"));
        var receive = dev.distantstock.link.NetworkAnnouncementService.class
                .getDeclaredMethod("receive", ReceivedMessage.class);
        receive.setAccessible(true);
        byte[] payload = dev.distantstock.link.NetworkAnnouncementCodec.encode(
                java.util.List.of(), 20, 1, java.util.List.of(), catalog);
        var message = new ReceivedMessage(UUID.randomUUID(), RoutingChannels.NETWORK_ANNOUNCE,
                source.toString(), TranserverBridge.localNodeId(), null, Instant.now(),
                Instant.now().plusSeconds(60), "application/x-distantstock", payload);
        var result = (CompletableFuture<?>) receive.invoke(null, message);
        h.assertTrue(result.join() == DeliveryResult.APPLIED, "actual announcement handler rejected catalog");
        h.assertTrue(dev.distantstock.link.NetworkAnnouncementService.remoteCatalog().get(source).equals(catalog),
                "actual announcement did not populate the command discovery catalog");
        h.assertTrue(DistantNetworkDirectory.get(h.getLevel().getServer()).find(network).isEmpty(),
                "discovery granted network membership");
        byte[] empty = dev.distantstock.link.NetworkAnnouncementCodec.encode(
                java.util.List.of(), 20, 1, java.util.List.of(), java.util.List.of());
        receive.invoke(null, new ReceivedMessage(UUID.randomUUID(), RoutingChannels.NETWORK_ANNOUNCE,
                source.toString(), TranserverBridge.localNodeId(), null, Instant.now(),
                Instant.now().plusSeconds(60), "application/x-distantstock", empty));
        h.assertTrue(dev.distantstock.link.NetworkAnnouncementService.remoteCatalog().get(source).isEmpty(),
                "empty authority announcement left stale discovery entries");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void queuedLegacyMemberAcceptCannotEnrollWarehouse(GameTestHelper h) throws Exception {
        var directory = DistantNetworkDirectory.get(h.getLevel().getServer());
        var member = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, TranserverBridge.localNodeUuid(),
                UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        UUID network = UUID.randomUUID();
        var result = receive(accept(pending(member, 180), member, network, UUID.randomUUID()));
        h.runAfterDelay(5, () -> {
            h.assertTrue(result.isDone() && result.join() == DeliveryResult.REJECTED,
                    "queued pre-upgrade member acceptance bypassed physical dock authorization");
            h.assertTrue(directory.formalNetworkOf(member).isEmpty(),
                    "stale queued join silently enrolled a Create network");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void expiredAcceptDoesNotCreateNetwork(GameTestHelper h) throws Exception {
        var directory = DistantNetworkDirectory.get(h.getLevel().getServer());
        var member = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, TranserverBridge.localNodeUuid(),
                UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        UUID network = UUID.randomUUID();
        var result = receive(accept(pending(member, 301), member, network, UUID.randomUUID()));
        h.runAfterDelay(5, () -> {
            h.assertTrue(result.isDone() && result.join() == DeliveryResult.REJECTED,
                    "expired request was applied");
            h.assertTrue(directory.find(network).isEmpty() && directory.networkOf(member).isEmpty(),
                    "expired request mutated the directory");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void terminalReplyCannotBindReplacementOrOverwriteNetwork(GameTestHelper h) throws Exception {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "JoinPreflight"));
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        var original = new net.minecraft.world.item.ItemStack(dev.distantstock.item.ModItems.REQUESTER.get());
        var previous = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                TranserverBridge.localNodeUuid(), UUID.randomUUID(),
                "minecraft:overworld", UUID.randomUUID());
        dev.distantstock.item.RequesterData.setNetwork(original, previous,
                DistantNetworkDirectory.LEGACY_NETWORK_ID);
        var replacement = original.copy();
        var method = DistantNetworkJoinService.class.getDeclaredMethod("bindTerminal",
                net.minecraft.server.level.ServerPlayer.class, net.minecraft.world.InteractionHand.class,
                net.minecraft.world.item.ItemStack.class, UUID.class);
        method.setAccessible(true);
        UUID network = UUID.randomUUID();
        player.setItemInHand(hand, replacement);
        h.assertFalse((boolean) method.invoke(null, player, hand, original, network),
                "reply bound a replacement terminal");
        h.assertTrue(dev.distantstock.item.RequesterData.network(replacement)
                        .filter(previous::equals).isPresent()
                        && dev.distantstock.item.RequesterData.distantNetwork(replacement)
                        .filter(DistantNetworkDirectory.LEGACY_NETWORK_ID::equals).isPresent(),
                "replacement terminal was mutated");
        player.setItemInHand(hand, original);
        h.assertTrue((boolean) method.invoke(null, player, hand, original, network),
                "reply failed to bind the original terminal");
        h.assertTrue(dev.distantstock.item.RequesterData.network(original).isEmpty()
                        && dev.distantstock.item.RequesterData.freq(original) == null,
                "successful network join kept a stale warehouse selection from another network");
        h.assertTrue(dev.distantstock.item.RequesterData.distantNetwork(original)
                        .filter(network::equals).isPresent(),
                "clearing the stale selection also cleared the joined network");
        h.assertFalse((boolean) method.invoke(null, player, hand, original, UUID.randomUUID()),
                "reply overwrote an existing network");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void inviteCommandWorksWithoutTerminalAndRejectsOtherOwner(GameTestHelper h) throws Exception {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "CodePreflight"));
        var server = h.getLevel().getServer();
        var directory = DistantNetworkDirectory.get(server);
        var network = directory.create("code-" + UUID.randomUUID(), TranserverBridge.localNodeUuid(),
                player.getUUID());
        var output = new java.util.ArrayList<String>();
        var source = player.createCommandSourceStack().withPermission(2).withSource(
                new net.minecraft.commands.CommandSource() {
                    public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                        output.add(message.getString());
                    }
                    public boolean acceptsSuccess() { return true; }
                    public boolean acceptsFailure() { return true; }
                    public boolean shouldInformAdmins() { return false; }
                });
        server.getCommands().getDispatcher().execute(
                "distantstock network code \"" + network.name() + "\"", source);
        h.assertTrue(output.stream().anyMatch(line -> line.contains(network.joinCode())),
                "actual code command did not return the owner's code without a terminal");
        var other = directory.create("other-" + UUID.randomUUID(), TranserverBridge.localNodeUuid(),
                UUID.randomUUID());
        output.clear();
        server.getCommands().getDispatcher().execute(
                "distantstock network code \"" + other.name() + "\"", source);
        h.assertTrue(!output.isEmpty() && output.stream().noneMatch(line -> line.contains(other.joinCode())),
                "actual code command let an operator read another owner's code");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void remoteMemberAcceptCannotMigrateLegacyOrCreateReplica(GameTestHelper h) throws Exception {
        var directory = DistantNetworkDirectory.get(h.getLevel().getServer());
        UUID node = TranserverBridge.localNodeUuid();
        var member = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node,
                UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        directory.attach(member, DistantNetworkDirectory.LEGACY_NETWORK_ID);
        UUID request = pending(member);
        UUID owner = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        var message = accept(request, member, network, owner);
        var result = receive(message);
        h.runAfterDelay(5, () -> {
            h.assertTrue(result.isDone() && result.join() == DeliveryResult.REJECTED,
                    "remote accept bypassed physical dock authorization");
            h.assertTrue(directory.formalNetworkOf(member).isEmpty(),
                    "remote join persisted warehouse membership without clicking a dock");
            h.assertTrue(directory.find(network).isEmpty(),
                    "unauthorized member accept created a phantom replica");
            try {
                var duplicate = receive(message);
                h.runAfterDelay(5, () -> {
                    h.assertTrue(duplicate.isDone() && duplicate.join() == DeliveryResult.REJECTED,
                            "duplicate legacy member acceptance should remain rejected");
                    h.succeed();
                });
            } catch (Exception error) {
                h.fail(error.toString());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void remoteAcceptCannotMoveAnAlreadyJoinedWarehouse(GameTestHelper h) throws Exception {
        var directory = DistantNetworkDirectory.get(h.getLevel().getServer());
        UUID node = TranserverBridge.localNodeUuid();
        var member = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node,
                UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        var existing = directory.create("preflight-" + UUID.randomUUID(), node, UUID.randomUUID(), member);
        UUID other = UUID.randomUUID();
        var result = receive(accept(pending(member), member, other, UUID.randomUUID()));
        h.runAfterDelay(5, () -> {
            h.assertTrue(result.isDone() && result.join() == DeliveryResult.REJECTED,
                    "remote accept moved an already joined warehouse");
            h.assertTrue(directory.formalNetworkOf(member).filter(existing.id()::equals).isPresent(),
                    "failed join damaged the existing membership");
            h.assertTrue(directory.find(other).isEmpty(), "failed join left a phantom network");
            h.succeed();
        });
    }

    @SuppressWarnings("unchecked")
    private static UUID pending(RemoteNetworkId member) throws Exception {
        return pending(member, 0);
    }

    @SuppressWarnings("unchecked")
    private static UUID pending(RemoteNetworkId member, long ageSeconds) throws Exception {
        var field = DistantNetworkJoinService.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        var type = Class.forName(DistantNetworkJoinService.class.getName() + "$Pending");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        args[0] = UUID.randomUUID();
        args[1] = member;
        // All target fields are null for a warehouse join; the final field is elapsed-time origin.
        args[args.length - 1] = System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(ageSeconds);
        UUID id = UUID.randomUUID();
        ((Map<UUID, Object>) field.get(null)).put(id, constructor.newInstance(args));
        return id;
    }

    private static ReceivedMessage accept(UUID request, RemoteNetworkId member, UUID network, UUID owner)
            throws Exception {
        return new ReceivedMessage(UUID.randomUUID(), RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT,
                owner.toString(), TranserverBridge.localNodeId(), request.toString(), Instant.now(),
                Instant.now().plusSeconds(60), "application/x-distantstock",
                DistantNetworkJoinCodec.encodeAccept(new DistantNetworkJoinCodec.Accept(
                        request, network, "Remote preflight", owner, member)));
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) throws Exception {
        var method = DistantNetworkJoinService.class.getDeclaredMethod("receiveAccept", ReceivedMessage.class);
        method.setAccessible(true);
        return (CompletableFuture<DeliveryResult>) method.invoke(null, message);
    }
}
