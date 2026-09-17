package dev.distantstock.link;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import dev.distantstock.routing.DockSelection;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RouteResolution;
import java.util.Optional;
import java.util.List;
import dev.distantstock.stock.NetworkDirectory;

/** Dependency-free protocol checks, runnable with ./gradlew verifyWireCodec. */
public final class PackageDispatchCodecCheck {
    public static void main(String[] args) throws Exception {
        UUID parcelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        PayloadManifest manifest = new PayloadManifest(
                List.of("minecraft:iron_ingot", "create:precision_mechanism"),
                List.of("minecraft:custom_name"));
        PackageDispatchCodec.Dispatch original = PackageDispatchCodec.create(
                parcelId, groupId, "工厂 A / 收货", manifest, "H4sIAAAAAAAA/test-payload");
        byte[] encoded = PackageDispatchCodec.encode(original);
        PackageDispatchCodec.Dispatch decoded = PackageDispatchCodec.decode(encoded);

        require(decoded.parcelId().equals(parcelId), "parcel ID changed");
        require(decoded.receivingDockGroupId().equals(groupId), "dock group changed");
        require(decoded.address().equals(original.address()), "UTF-8 address changed");
        require(decoded.encodedPackage().equals(original.encodedPackage()), "package changed");
        require(decoded.manifest().equals(manifest), "payload manifest changed");

        byte[] corrupt = Arrays.copyOf(encoded, encoded.length);
        corrupt[corrupt.length / 2] ^= 0x20;
        rejects(corrupt, "corrupt payload was accepted");

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        rejects(trailing, "trailing data was accepted");

        byte[] wrongMagic = Arrays.copyOf(encoded, encoded.length);
        wrongMagic[0] ^= 0x01;
        rejects(wrongMagic, "wrong magic was accepted");

        RemoteNetworkId network = new RemoteNetworkId(1, UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", UUID.randomUUID());
        OrderRequestCodec.Request order = new OrderRequestCodec.Request(network, groupId,
                UUID.randomUUID(), UUID.randomUUID(), "装配线", List.of(
                new LinkQueues.Line("minecraft:iron_ingot", 64),
                new LinkQueues.Line("create:precision_mechanism", 3)));
        byte[] orderBytes = OrderRequestCodec.encode(order);
        require(OrderRequestCodec.decode(orderBytes).equals(order), "order request changed during round trip");
        byte[] orderTrailing = Arrays.copyOf(orderBytes, orderBytes.length + 1);
        rejectsOrder(orderTrailing, "order trailing data was accepted");

        // 第二个地址跟着订单过海，所以它也必须原样回来。
        OrderRequestCodec.Request twoAddresses = new OrderRequestCodec.Request(network, groupId,
                UUID.randomUUID(), UUID.randomUUID(), "甲站发货口", "甲站收货口", List.of(
                new LinkQueues.Line("minecraft:iron_ingot", 8)));
        require(OrderRequestCodec.decode(OrderRequestCodec.encode(twoAddresses)).equals(twoAddresses),
                "order request lost its second address");
        // 空的本端地址是常事（货不过海），往返之后不能变成别的什么东西。
        require(OrderRequestCodec.decode(OrderRequestCodec.encode(order)).homeAddress().isEmpty(),
                "an order that named no home address came back with one");
        acceptsVersionOneOrder();

        List<NetworkDirectory.Entry> directory = List.of(
                new NetworkDirectory.Entry(network.createFrequency(), "仓库服", 4, network, false, false));
        // False for local, because that is what an announcement is: another server's networks,
        // decoded on this side. The flag is not on the wire — the receiver is what makes them
        // remote. False for packable is a fact about the sender and does travel.
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        List<NetworkAnnouncementCodec.Group> groups = List.of(
                new NetworkAnnouncementCodec.Group(groupId, "乙服仓库", owner, true, 3,
                        List.of(new NetworkAnnouncementCodec.Group.Member(member, "Iris_Aria0"))),
                new NetworkAnnouncementCodec.Group(UUID.randomUUID(), "无主", null, false, 0, List.of()));
        byte[] announcement = NetworkAnnouncementCodec.encode(directory, 19.75, 4.25, groups);
        List<NetworkDirectory.Entry> readBack = NetworkAnnouncementCodec.decode(announcement);
        require(readBack.size() == 1, "the announcement came back with " + readBack.size() + " networks");
        require(readBack.getFirst().freq().equals(network.createFrequency()),
                "the announcement came back with a different network");
        require(!readBack.getFirst().packable(),
                "a network the sender cannot pack for came back saying it could");
        // The metrics ride in the same payload, so the round trip has to bring those back too —
        // they are what the monitor draws for the other server.
        NetworkAnnouncementCodec.Metrics carried = NetworkAnnouncementCodec.metrics(announcement);
        require(carried.known() && carried.tps() == 19.75 && carried.mspt() == 4.25,
                "the announcement lost the sender's metrics");
        // The dock groups ride in it as well, and they are what a cross-server destination is made
        // of: an id to address, a name to draw, and the member list that decides who may use it.
        List<NetworkAnnouncementCodec.Group> readGroups = NetworkAnnouncementCodec.groups(announcement);
        require(readGroups.equals(groups), "the announcement lost or changed its dock groups: " + readGroups);
        // An ownerless group has to survive as ownerless, or every group an admin made would come
        // back owned by nobody-knows-whom and stop admitting the players it used to.
        require(readGroups.get(1).owner() == null, "an ownerless group came back with an owner");
        byte[] announcementTrailing = Arrays.copyOf(announcement, announcement.length + 1);
        try {
            NetworkAnnouncementCodec.decode(announcementTrailing);
            throw new AssertionError("announcement trailing data was accepted");
        } catch (IOException expected) {
        }
        // A version 2 payload — no group section, no "can pack" — still has to read, because the
        // other server is not obliged to be upgraded in the same minute. What it must not do is
        // leave the previous payload's groups lying in the codec's slot: a caller that reads them
        // without decoding would hand the last peer's destinations to this one.
        NetworkAnnouncementCodec.groups(announcement);
        NetworkAnnouncementCodec.decode(announcementVersionTwo(directory, 12.5, 3.5));
        require(NetworkAnnouncementCodec.groups(
                announcementVersionTwo(directory, 12.5, 3.5)).isEmpty(),
                "an older payload inherited the dock groups of the payload before it");
        require(NetworkAnnouncementCodec.metrics(
                announcementVersionTwo(directory, 12.5, 3.5)).tps() == 12.5,
                "a version 2 payload lost its metrics");

        StockWireCodec.Query stockQuery = new StockWireCodec.Query(UUID.randomUUID(), network);
        require(StockWireCodec.decodeQuery(StockWireCodec.encodeQuery(stockQuery)).equals(stockQuery),
                "stock query changed during round trip");
        StockWireCodec.Result stockResult = new StockWireCodec.Result(stockQuery.queryId(), network, List.of(
                new LinkQueues.Line("minecraft:iron_ingot", 512),
                new LinkQueues.Line("create:brass_ingot", 32)));
        byte[] stockBytes = StockWireCodec.encodeResult(stockResult);
        require(StockWireCodec.decodeResult(stockBytes).equals(stockResult),
                "stock result changed during round trip");
        try {
            StockWireCodec.decodeResult(Arrays.copyOf(stockBytes, stockBytes.length + 1));
            throw new AssertionError("stock result trailing data was accepted");
        } catch (IOException expected) {
        }

        PackageStripCodec.Notice notice = new PackageStripCodec.Notice(parcelId,
                List.of("item:example:missing_ore", "component:example:missing_component"),
                "missing_registry_entries");
        byte[] noticeBytes = PackageStripCodec.encode(notice);
        require(PackageStripCodec.decode(noticeBytes).equals(notice),
                "strip notice changed during round trip");
        try {
            PackageStripCodec.decode(Arrays.copyOf(noticeBytes, noticeBytes.length + 1));
            throw new AssertionError("strip notice trailing data was accepted");
        } catch (IOException expected) {
        }
        try {
            PackageStripCodec.decode(new byte[0]);
            throw new AssertionError("an empty strip notice was accepted");
        } catch (IOException expected) {
        }

        require(ParcelEscrowPump.shouldResend(0, true, ParcelEscrowPump.STRIP_LIMIT),
                "the first strip must be sent again");
        require(!ParcelEscrowPump.shouldResend(0, false, ParcelEscrowPump.STRIP_LIMIT),
                "a parcel nothing was removed from must not be sent again");
        require(!ParcelEscrowPump.shouldResend(ParcelEscrowPump.STRIP_LIMIT, true, ParcelEscrowPump.STRIP_LIMIT),
                "the strip limit must stop resending");

        // Route resolution: the parcel's own route wins, then its Create order, then the dock default.
        RemoteRoute parcelRoute = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        RemoteRoute orderRoute = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        RemoteRoute dockDefault = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        require(RouteResolution.resolve(Optional.of(parcelRoute), Optional.of(orderRoute), Optional.of(dockDefault))
                .orElseThrow().equals(parcelRoute), "the parcel route must win over the order route");
        require(RouteResolution.resolve(Optional.empty(), Optional.of(orderRoute), Optional.of(dockDefault))
                .orElseThrow().equals(orderRoute), "the order route must win over the dock default");
        require(RouteResolution.resolve(Optional.empty(), Optional.empty(), Optional.of(dockDefault))
                .orElseThrow().equals(dockDefault), "a plain parcel must use the dock default");
        require(RouteResolution.resolve(Optional.empty(), Optional.empty(), Optional.empty()).isEmpty(),
                "a parcel with no route at all must not be sent anywhere");

        // Dock selection: the highest priority tier that has room takes turns; a full group is reported.
        List<DockSelection.Candidate> candidates = List.of(
                new DockSelection.Candidate(0, true),
                new DockSelection.Candidate(5, false),
                new DockSelection.Candidate(5, true),
                new DockSelection.Candidate(1, true));
        require(DockSelection.select(candidates, 0) == 2, "the free dock of the highest priority must win");
        require(DockSelection.select(candidates, 3) == 2, "a busy higher tier must not be selected");
        require(DockSelection.select(List.of(
                new DockSelection.Candidate(3, true),
                new DockSelection.Candidate(3, true)), 1) == 1, "equal priorities must take turns");
        require(DockSelection.select(List.of(new DockSelection.Candidate(0, false)), 0) == 0,
                "a full group must report its first dock so the stall can be explained");
        require(DockSelection.select(List.of(), 0) == -1, "an empty group must report no dock");
    }

    /**
     * 老版本发来的订单还要能读。
     *
     * <p>配对的两台服务器是一台一台重启的，所以「一台已经会写第二个地址、另一台还不会」是
     * 升级当天的常态，不是异常。版本 1 的载荷里没有那段字符串，按版本号跳过它 —— 跳过而不是
     * 读一个空串，因为读空串会把后面的行数当成地址吃掉，整张订单从此错位。
     */
    private static void acceptsVersionOneOrder() throws Exception {
        RemoteNetworkId network = new RemoteNetworkId(1, UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", UUID.randomUUID());
        UUID group = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeInt(0x44534f52);
        out.writeInt(1);
        for (UUID id : List.of(network.nodeId(), network.worldId())) {
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
        }
        writeString(out, network.dimensionId());
        out.writeLong(network.createFrequency().getMostSignificantBits());
        out.writeLong(network.createFrequency().getLeastSignificantBits());
        for (UUID id : List.of(group, correlation, child)) {
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
        }
        writeString(out, "旧版地址");
        out.writeInt(1);
        writeString(out, "minecraft:iron_ingot");
        out.writeInt(16);
        OrderRequestCodec.Request decoded = OrderRequestCodec.decode(bytes.toByteArray());
        require(decoded.address().equals("旧版地址"), "a version 1 order lost its address");
        require(decoded.homeAddress().isEmpty(), "a version 1 order invented a home address");
        require(decoded.lines().size() == 1 && decoded.lines().getFirst().count() == 16,
                "a version 1 order lost its lines behind the missing field");
    }

    /**
     * A version 2 announcement, written out by hand.
     *
     * <p>Built here rather than by asking the codec for an old version, because the point of the
     * check is that the reader still understands a payload this build can no longer produce — a
     * peer that has not been updated keeps sending them, and the day it stops being able to read
     * one is the day a linked pair of servers has to be upgraded in lockstep.
     */
    private static byte[] announcementVersionTwo(List<NetworkDirectory.Entry> entries, double tps,
                                                 double mspt) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeInt(0x44534e41);
        out.writeInt(2);
        out.writeInt(entries.size());
        for (NetworkDirectory.Entry entry : entries) {
            RemoteNetworkId id = entry.networkId();
            out.writeLong(id.nodeId().getMostSignificantBits());
            out.writeLong(id.nodeId().getLeastSignificantBits());
            out.writeLong(id.worldId().getMostSignificantBits());
            out.writeLong(id.worldId().getLeastSignificantBits());
            writeString(out, id.dimensionId());
            out.writeLong(id.createFrequency().getMostSignificantBits());
            out.writeLong(id.createFrequency().getLeastSignificantBits());
            writeString(out, entry.server());
            out.writeInt(entry.links());
        }
        out.writeDouble(tps);
        out.writeDouble(mspt);
        return bytes.toByteArray();
    }

    private static void writeString(java.io.DataOutputStream out, String value) throws Exception {
        byte[] raw = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static void rejectsOrder(byte[] payload, String message) throws Exception {
        try {
            OrderRequestCodec.decode(payload);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void rejects(byte[] payload, String message) throws Exception {
        try {
            PackageDispatchCodec.decode(payload);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private PackageDispatchCodecCheck() {
    }
}
