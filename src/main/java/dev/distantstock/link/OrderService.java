package dev.distantstock.link;

import dev.distantstock.config.StockConfig;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.OrderDestination;
import dev.distantstock.routing.RemoteGroups;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OrderService {
    private static final Logger LOG = LogManager.getLogger();

    public enum Result {
        QUEUED, FAIL, EMPTY, NO_PEER
    }

    public static Result place(UUID freq, String address, List<LinkQueues.Line> lines) {
        return place(freq, address, DockGroupDirectory.DEFAULT_GROUP_ID, lines);
    }

    public static Result place(UUID freq, String address, UUID receivingDockGroupId, List<LinkQueues.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return Result.EMPTY;
        }
        if (CreateStock.hasNetwork(freq)) {
            List<StockCache.Entry> items = new ArrayList<>();
            for (LinkQueues.Line line : lines) {
                items.add(new StockCache.Entry(line.itemId(), line.count()));
            }
            boolean ok = CreateStock.request(freq, items, address);
            LinkQueues.lastPack(freq, ok ? LinkQueues.PackResult.SUCCESS : LinkQueues.PackResult.NO_STOCK);
            return ok ? Result.QUEUED : Result.FAIL;
        }
        if (!StockConfig.hasPeer()) {
            LinkQueues.lastPack(freq, LinkQueues.PackResult.UNLOADED);
            return Result.NO_PEER;
        }
        boolean ok = LinkQueues.offerOutboundOrder(new LinkQueues.Order(freq, address, lines, StockConfig.selfId(),
                receivingDockGroupId, UUID.randomUUID(), UUID.randomUUID()));
        if (ok) {
            LinkClient.wake();
            return Result.QUEUED;
        }
        return Result.FAIL;
    }

    public static Result place(MinecraftServer server, RemoteNetworkId networkId, UUID legacyFrequency,
                               String address, UUID receivingDockGroupId, List<LinkQueues.Line> lines) {
        return place(server, networkId, legacyFrequency, address, receivingDockGroupId, lines, "");
    }

    /**
     * The same, with the address the goods must wear once they are back on this side.
     *
     * <p>Two addresses because a parcel needs one on each side and the two servers name their doors
     * differently: the one it is packed with is the one the packing server sorts it by, and this one
     * is applied the moment it arrives here. Blank when the goods stay where they are packed — an
     * order into this server's own network never crosses, and neither does one that the packing side
     * keeps.
     */
    public static Result place(MinecraftServer server, RemoteNetworkId networkId, UUID legacyFrequency,
                               String address, UUID receivingDockGroupId, List<LinkQueues.Line> lines,
                               String homeAddress) {
        return place(server, networkId, legacyFrequency, null, address, receivingDockGroupId,
                lines, homeAddress);
    }

    /**
     * Scope-aware order entry point. A persisted binding should pass its Distant Stock network here
     * so a temporarily quiet remote warehouse does not fall back to Legacy merely because its live
     * announcement aged out.
     */
    public static Result place(MinecraftServer server, RemoteNetworkId networkId, UUID legacyFrequency,
                               UUID distantNetworkId, String address, UUID receivingDockGroupId,
                               List<LinkQueues.Line> lines, String homeAddress) {
        if (lines == null || lines.isEmpty()) {
            return Result.EMPTY;
        }
        if (server == null) {
            return Result.FAIL;
        }
        RemoteNetworkId sourceNetwork = canonicalLocalSource(networkId);
        UUID scope = distantNetworkId != null ? distantNetworkId
                : sourceNetwork == null ? DistantNetworkDirectory.LEGACY_NETWORK_ID
                : NetworkDirectory.find(sourceNetwork)
                .map(NetworkDirectory.Entry::distantNetworkId)
                .orElseGet(() -> DistantNetworkDirectory.get(server).scopeOf(sourceNetwork));
        if (!DistantNetworkDirectory.isFormalId(scope)) {
            return Result.FAIL;
        }
        if (sourceNetwork != null && distantNetworkId != null) {
            NetworkDirectory.Entry liveSource = NetworkDirectory.find(sourceNetwork).orElse(null);
            if (liveSource != null && !scope.equals(liveSource.distantNetworkId())) {
                // The selected warehouse left this Distant Stock network. A terminal/device keeps
                // its own network context; it must not follow the warehouse into a different one.
                return Result.FAIL;
            }
            UUID localNode = TranserverBridge.localNodeUuid();
            if (localNode == null) return Result.FAIL;
            if (sourceNetwork.nodeId().equals(localNode)) {
                UUID persistedLocalScope = DistantNetworkDirectory.get(server)
                        .formalNetworkOf(sourceNetwork).orElse(null);
                if (!scope.equals(persistedLocalScope)) {
                    return Result.FAIL;
                }
            }
        }
        OrderDestination.Answer destination = OrderDestination.resolve(
                server, null, scope, receivingDockGroupId);
        if (!destination.allowed()) {
            return Result.FAIL;
        }
        UUID destinationNode = destinationNode(server, destination);
        if (destinationNode == null) {
            return Result.FAIL;
        }
        if (sourceNetwork == null) {
            return placeLocal(server, legacyFrequency, address, receivingDockGroupId,
                    destinationNode, homeAddress, lines);
        }
        UUID localNode = TranserverBridge.localNodeUuid();
        if (localNode == null) return Result.FAIL;
        if (sourceNetwork.nodeId().equals(localNode)) {
            return placeLocal(server, sourceNetwork.createFrequency(), address, receivingDockGroupId,
                    destinationNode, homeAddress, lines);
        }
        UUID correlationId = UUID.randomUUID();
        UUID childOrderId = UUID.randomUUID();
        try {
            OrderRequestCodec.Request request = new OrderRequestCodec.Request(sourceNetwork,
                    scope, receivingDockGroupId, destinationNode, correlationId, childOrderId,
                    address == null ? "" : address,
                    homeAddress == null ? "" : homeAddress, lines);
            UUID messageId = TranserverBridge.send(sourceNetwork.nodeId().toString(), RoutingChannels.ORDER_REQUEST,
                    OrderRequestCodec.encode(request), correlationId.toString());
            if (messageId != null) {
                LOG.info("[DistantStock/Order] queued message={} correlation={} child={} target={} network={} group={} lines={}",
                        messageId, correlationId, childOrderId, sourceNetwork.nodeId(),
                        sourceNetwork.createFrequency(), receivingDockGroupId, lines.size());
            } else {
                LOG.warn("[DistantStock/Order] transport unavailable correlation={} child={} target={} network={} group={}",
                        correlationId, childOrderId, sourceNetwork.nodeId(),
                        sourceNetwork.createFrequency(), receivingDockGroupId);
            }
            return messageId == null ? Result.NO_PEER : Result.QUEUED;
        } catch (IOException | RuntimeException exception) {
            LOG.warn("[DistantStock/Order] encode/send failed target={} network={} group={}",
                    sourceNetwork.nodeId(), sourceNetwork.createFrequency(), receivingDockGroupId, exception);
            return Result.FAIL;
        }
    }

    /**
     * Upgrade a stale local device binding to the live Create-network identity.
     *
     * <p>An exact directory hit is authoritative, including remote rows. Only an identity that is
     * no longer known at all may fall back to a live local row with the same Create frequency.
     */
    private static RemoteNetworkId canonicalLocalSource(RemoteNetworkId stored) {
        if (stored == null) return null;
        if (NetworkDirectory.find(stored).isPresent()) return stored;
        return NetworkDirectory.findByFreq(stored.createFrequency())
                .filter(NetworkDirectory.Entry::local)
                .map(NetworkDirectory.Entry::networkId)
                .orElse(stored);
    }

    private static Result placeLocal(MinecraftServer server, UUID frequency, String address,
                                     UUID receivingGroup, UUID destinationNode, String homeAddress,
                                     List<LinkQueues.Line> lines) {
        if (lines == null || lines.isEmpty()) return Result.EMPTY;
        if (!CreateStock.hasNetwork(frequency)) return place(frequency, address, receivingGroup, lines);
        List<StockCache.Entry> items = new ArrayList<>();
        for (LinkQueues.Line line : lines) items.add(new StockCache.Entry(line.itemId(), line.count()));
        RemoteRoute route = new RemoteRoute(RemoteRoute.CURRENT_SCHEMA, destinationNode,
                receivingGroup == null ? DockGroupDirectory.DEFAULT_GROUP_ID : receivingGroup,
                UUID.randomUUID(), UUID.randomUUID());
        UUID localNode = TranserverBridge.localNodeUuid();
        if (localNode == null) return Result.FAIL;
        boolean crosses = !localNode.equals(destinationNode);
        boolean ok = CreateStock.request(frequency, items, address, server, route,
                crosses ? (homeAddress == null ? "" : homeAddress) : "");
        LinkQueues.lastPack(frequency, ok ? LinkQueues.PackResult.SUCCESS : LinkQueues.PackResult.NO_STOCK);
        return ok ? Result.QUEUED : Result.FAIL;
    }

    private static UUID destinationNode(MinecraftServer server, OrderDestination.Answer destination) {
        if (destination == null || !destination.allowed() || destination.group() == null) {
            return null;
        }
        if (destination.kind() == OrderDestination.Kind.HERE) {
            return TranserverBridge.localNodeUuid();
        }
        return RemoteGroups.get(server).find(destination.group())
                .map(RemoteGroups.Entry::node)
                .orElse(null);
    }

    public static void drainInbound(MinecraftServer server) {
        LinkQueues.Order order;
        int n = 0;
        while (n++ < 8 && (order = LinkQueues.pollInboundOrder()) != null) {
            List<StockCache.Entry> items = new ArrayList<>();
            for (LinkQueues.Line line : order.items) {
                items.add(new StockCache.Entry(line.itemId(), line.count()));
            }
            if (!CreateStock.hasNetwork(order.freq)) {
                LinkQueues.lastPack(order.freq, LinkQueues.PackResult.UNLOADED);
                continue;
            }
            // An order without a usable source node keeps a null route: the parcel then relies on the
            // sending dock's explicit default destination instead of an address-keyed guess.
            RemoteRoute route = routeFor(order);
            boolean ok = CreateStock.request(order.freq, items, order.address, server, route);
            LinkQueues.lastPack(order.freq, ok ? LinkQueues.PackResult.SUCCESS : LinkQueues.PackResult.NO_STOCK);
        }
    }

    private static RemoteRoute routeFor(LinkQueues.Order order) {
        try {
            return new RemoteRoute(RemoteRoute.CURRENT_SCHEMA, UUID.fromString(order.from),
                    order.receivingDockGroupId, order.correlationId, order.childOrderId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private OrderService() {
    }
}
