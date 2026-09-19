package dev.distantstock;

import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.block.RemoteBinding;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class DistantNetworkGameTests {
    private static final UUID OWNER_NODE =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OWNER_PLAYER =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void networkSaveRoundTripKeepsCodeAndCreateMembership(GameTestHelper h) {
        DistantNetworkDirectory directory = new DistantNetworkDirectory();
        RemoteNetworkId member = member();
        var network = directory.create("Nexus", OWNER_NODE, OWNER_PLAYER, member);

        h.assertTrue(network.joinCode().matches("[A-Z0-9]{4}-[A-Z0-9]{4}"),
                "join code is not in the 1F2A-5B7G shape: " + network.joinCode());
        h.assertTrue(directory.scopeOf(member).equals(network.id()),
                "the first Create network did not join the network it created");

        CompoundTag saved = directory.save(new CompoundTag(), h.getLevel().registryAccess());
        DistantNetworkDirectory loaded = DistantNetworkDirectory.load(saved, h.getLevel().registryAccess());
        var copy = loaded.find(network.id()).orElse(null);
        h.assertTrue(copy != null, "the Distant Stock network vanished during save/load");
        h.assertTrue(copy.name().equals("Nexus"), "the network name changed during save/load");
        h.assertTrue(copy.joinCode().equals(network.joinCode()), "the long-lived join code was not persisted");
        h.assertTrue(loaded.scopeOf(member).equals(network.id()),
                "Create-network membership vanished during save/load");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void remoteBindingKeepsDistantNetworkScopeAcrossSave(GameTestHelper h) {
        RemoteNetworkId warehouse = member();
        UUID scope = UUID.randomUUID();
        UUID address = UUID.randomUUID();
        RemoteBinding binding = new RemoteBinding(
                warehouse, scope, address, "打包口", "落地口");
        RemoteBinding loaded = RemoteBinding.read(binding.save());
        h.assertTrue(loaded != null, "a saved remote binding could not be read");
        h.assertTrue(loaded.distantNetworkKnown(),
                "a current remote binding forgot that its Distant Stock scope was explicit");
        h.assertTrue(scope.equals(loaded.distantNetworkId()),
                "the remote binding forgot its Distant Stock network scope");
        h.assertTrue(address.equals(loaded.receivingGroup()),
                "the remote binding forgot its receiving address");

        CompoundTag old = new CompoundTag();
        old.put("Network", warehouse.save());
        old.putUUID("Group", address);
        old.putString("Address", "旧打包口");
        RemoteBinding legacy = RemoteBinding.read(old);
        h.assertTrue(legacy != null
                        && !legacy.distantNetworkKnown()
                        && DistantNetworkDirectory.LEGACY_NETWORK_ID.equals(legacy.distantNetworkId()),
                "a binding from before Distant-network scopes did not migrate to Legacy");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void remoteWarehouseCannotDragABindingIntoAnotherDistantNetwork(GameTestHelper h) {
        UUID sourceNode = UUID.fromString("66666666-6666-6666-6666-666666666666");
        RemoteNetworkId warehouse = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                sourceNode, UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        UUID nexus = UUID.randomUUID();
        UUID snc = UUID.randomUUID();
        String peer = sourceNode.toString();
        dev.distantstock.stock.NetworkDirectory.replacePeer(peer, java.util.List.of(
                new dev.distantstock.stock.NetworkDirectory.Entry(
                        warehouse.createFrequency(), "moved warehouse", 1, warehouse,
                        false, true, snc)));
        try {
            var result = dev.distantstock.link.OrderService.place(h.getLevel().getServer(),
                    warehouse, warehouse.createFrequency(), nexus, "", UUID.randomUUID(),
                    java.util.List.of(new dev.distantstock.link.LinkQueues.Line(
                            "minecraft:stone", 1)), "");
            h.assertTrue(result == dev.distantstock.link.OrderService.Result.FAIL,
                    "an old Nexus binding followed its warehouse into another Distant Stock network");
        } finally {
            dev.distantstock.stock.NetworkDirectory.replacePeer(peer, java.util.List.of());
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void oneCreateWarehouseCannotBelongToTwoDistantNetworks(GameTestHelper h) {
        DistantNetworkDirectory directory = new DistantNetworkDirectory();
        RemoteNetworkId warehouse = member();
        directory.create("First", OWNER_NODE, OWNER_PLAYER, warehouse);
        try {
            directory.create("Second", OWNER_NODE, OWNER_PLAYER, warehouse);
            h.fail("the same Create warehouse created a second Distant Stock network without leaving");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        var secondNetwork = directory.create("Other", OWNER_NODE, OWNER_PLAYER, member());
        h.assertFalse(directory.attach(warehouse, secondNetwork.id()),
                "attach silently moved a Create warehouse between two Distant Stock networks");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void joinHandshakeMovesOneCreateNetworkWithoutLeakingTheCode(GameTestHelper h)
            throws Exception {
        UUID authorityNode = OWNER_NODE;
        UUID joiningNode = UUID.fromString("55555555-5555-5555-5555-555555555555");
        DistantNetworkDirectory authority = new DistantNetworkDirectory();
        RemoteNetworkId authorityWarehouse = member();
        var network = authority.create("Nexus", authorityNode, OWNER_PLAYER, authorityWarehouse);

        DistantNetworkDirectory joining = new DistantNetworkDirectory();
        RemoteNetworkId joiningWarehouse = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                joiningNode, UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        UUID requestId = UUID.randomUUID();

        // B -> A: exactly what crosses Transerver.
        var wireRequest = dev.distantstock.link.DistantNetworkJoinCodec.decodeRequest(
                dev.distantstock.link.DistantNetworkJoinCodec.encodeRequest(
                        new dev.distantstock.link.DistantNetworkJoinCodec.Request(
                                requestId, network.joinCode(), joiningWarehouse)));
        var matched = authority.findByCode(wireRequest.joinCode()).orElse(null);
        h.assertTrue(matched != null && matched.id().equals(network.id()),
                "the authority could not resolve the join code from the wire request");
        // A -> B: acceptance contains the stable network identity, never the secret code.
        var wireAccept = dev.distantstock.link.DistantNetworkJoinCodec.decodeAccept(
                dev.distantstock.link.DistantNetworkJoinCodec.encodeAccept(
                        new dev.distantstock.link.DistantNetworkJoinCodec.Accept(
                                wireRequest.requestId(), matched.id(), matched.name(),
                                matched.ownerNode(), wireRequest.member())));
        h.assertTrue(wireAccept.requestId().equals(requestId)
                        && wireAccept.member().equals(joiningWarehouse),
                "the accept no longer correlates to the join request/member");
        joining.rememberReplica(wireAccept.networkId(), wireAccept.networkName(), wireAccept.ownerNode());
        joining.attach(wireAccept.member(), wireAccept.networkId());

        h.assertTrue(authority.networkOf(joiningWarehouse).isEmpty(),
                "the authority persisted a remote warehouse membership that should belong to its node");
        h.assertTrue(joining.scopeOf(joiningWarehouse).equals(network.id()),
                "the joining node did not remember its Create warehouse membership");
        var replica = joining.find(network.id()).orElse(null);
        h.assertTrue(replica != null && replica.joinCode().isEmpty(),
                "the joining node received the authority's long-lived join code");
        h.assertTrue(joining.findByCode(network.joinCode()).isEmpty(),
                "a replica can answer future joins even though only the authority should hold the code");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void authorityOwnerCannotLeaveItsLastLocalWarehouse(GameTestHelper h) {
        DistantNetworkDirectory directory = new DistantNetworkDirectory();
        RemoteNetworkId first = member();
        var network = directory.create("Authority Guard", OWNER_NODE, OWNER_PLAYER, first);
        h.assertTrue(directory.wouldOrphanAuthority(first, OWNER_PLAYER, OWNER_NODE),
                "the owner's last authority-side warehouse was allowed to orphan its network");

        RemoteNetworkId second = member();
        directory.attach(second, network.id());
        h.assertFalse(directory.wouldOrphanAuthority(first, OWNER_PLAYER, OWNER_NODE),
                "one of two authority-side warehouses could not be removed");
        h.assertFalse(directory.wouldOrphanAuthority(first, UUID.randomUUID(), OWNER_NODE),
                "a non-owner was treated as the authority owner");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void legacyReceivingAddressesRemainUsableAfterWarehouseMigration(GameTestHelper h) {
        var server = h.getLevel().getServer();
        DockGroupDirectory addresses = DockGroupDirectory.get(server);
        String name = "旧地址-" + UUID.randomUUID().toString().substring(0, 6);
        DockGroup legacy = addresses.createFor(name, OWNER_PLAYER);
        UUID nexus = UUID.randomUUID();
        try {
            var resolved = dev.distantstock.routing.ReceivingAddressResolver.resolve(server, nexus, name);
            h.assertTrue(resolved.kind() == dev.distantstock.routing.ReceivingAddressResolver.Kind.LOCAL
                            && resolved.local().id().equals(legacy.id()),
                    "a migrated warehouse could no longer resolve its old receiving address");
            var order = dev.distantstock.routing.OrderDestination.resolve(
                    server, OWNER_PLAYER, nexus, legacy.id());
            h.assertTrue(order.allowed() && order.kind() == dev.distantstock.routing.OrderDestination.Kind.HERE,
                    "a saved legacy receiving-address UUID stopped working after warehouse migration");
        } finally {
            addresses.delete(legacy.id());
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void resettingCodeDoesNotDisconnectExistingMembers(GameTestHelper h) {
        DistantNetworkDirectory directory = new DistantNetworkDirectory();
        RemoteNetworkId member = member();
        var network = directory.create("Parallel", OWNER_NODE, OWNER_PLAYER, member);
        String old = network.joinCode();

        String next = directory.resetJoinCode(network.id(), OWNER_PLAYER, OWNER_NODE);
        h.assertFalse(next.equals(old), "resetting the code returned the same code");
        h.assertTrue(directory.findByCode(old).isEmpty(), "the old join code still works after reset");
        h.assertTrue(directory.findByCode(next).map(found -> found.id().equals(network.id())).orElse(false),
                "the new join code does not resolve the same network");
        h.assertTrue(directory.scopeOf(member).equals(network.id()),
                "resetting the join code disconnected an existing Create network");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void receivingAddressNamesAreUniqueOnlyInsideOneDistantNetwork(GameTestHelper h) {
        DockGroupDirectory addresses = new DockGroupDirectory();
        UUID nexus = UUID.randomUUID();
        UUID snc = UUID.randomUUID();
        String name = "工业区-" + UUID.randomUUID().toString().substring(0, 6);

        DockGroup first = addresses.createForNetwork(
                name, OWNER_PLAYER, nexus, DockGroup.Visibility.PUBLIC);
        DockGroup otherNetwork = addresses.createForNetwork(
                name, OWNER_PLAYER, snc, DockGroup.Visibility.UNLISTED);
        h.assertFalse(first.id().equals(otherNetwork.id()),
                "two different Distant Stock networks unexpectedly shared one address identity");

        try {
            addresses.createForNetwork(name, OWNER_PLAYER, nexus, DockGroup.Visibility.PUBLIC);
            h.fail("one Distant Stock network accepted two receiving addresses with the same name");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        CompoundTag saved = addresses.save(new CompoundTag(), h.getLevel().registryAccess());
        DockGroupDirectory loaded = DockGroupDirectory.load(saved, h.getLevel().registryAccess());
        h.assertTrue(loaded.findByName(nexus, name).map(group -> group.id().equals(first.id())).orElse(false),
                "the Nexus address did not survive its scope");
        h.assertTrue(loaded.findByName(snc, name)
                        .map(group -> group.id().equals(otherNetwork.id())
                                && group.visibility() == DockGroup.Visibility.UNLISTED)
                        .orElse(false),
                "the SNC unlisted address did not survive its scope/visibility");
        h.succeed();
    }

    private static RemoteNetworkId member() {
        return new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, OWNER_NODE, UUID.randomUUID(),
                "minecraft:overworld", UUID.randomUUID());
    }

    private DistantNetworkGameTests() {
    }
}
