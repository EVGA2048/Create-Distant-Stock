package dev.distantstock.link;

import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteGroups;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.StockCache;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Durable remote-order intake and conservative main-thread application. */
public final class TranserverOrderService {
    private static final Logger LOG = LogManager.getLogger();

    public static void register() {
        TranserverBridge.handler(RoutingChannels.ORDER_REQUEST, TranserverOrderService::receive);
    }

    private static UUID knownDestinationScope(MinecraftServer server, UUID group) {
        var local = DockGroupDirectory.get(server).find(group).orElse(null);
        if (local != null) {
            return local.distantNetworkId();
        }
        return RemoteGroups.get(server).find(group)
                .map(RemoteGroups.Entry::distantNetworkId).orElse(null);
    }

    private static UUID knownDestinationNode(MinecraftServer server, UUID group) {
        if (DockGroupDirectory.get(server).find(group).isPresent()) {
            return TranserverBridge.localNodeUuid();
        }
        return RemoteGroups.get(server).find(group).map(RemoteGroups.Entry::node).orElse(null);
    }

    public static void tick(MinecraftServer server) {
        InboundOrderInbox inbox = InboundOrderInbox.get(server);
        int processed = 0;
        for (InboundOrderInbox.Record record : inbox.records()) {
            if (processed >= 4 || record.state() != InboundOrderInbox.State.RECEIVED) {
                continue;
            }
            processed++;
            inbox.state(record.childOrderId(), InboundOrderInbox.State.PROCESSING, "");
            inbox.flush(server);
            try {
                var items = new ArrayList<StockCache.Entry>();
                for (LinkQueues.Line line : record.request().lines()) {
                    items.add(new StockCache.Entry(line.itemId(), line.count()));
                }
                UUID destination = destinationNode(server, record);
                if (destination == null) {
                    // Old v1/v2 requests do not carry the destination node. Wait until this server
                    // has learned the receiving address rather than guessing that it means "source".
                    inbox.state(record.childOrderId(), InboundOrderInbox.State.RECEIVED,
                            "receiving-address node is not known yet");
                    inbox.flush(server);
                    continue;
                }
                RemoteRoute route = new RemoteRoute(RemoteRoute.CURRENT_SCHEMA,
                        destination, record.request().receivingDockGroupId(),
                        record.request().correlationId(), record.request().childOrderId());
                // The second address is for a parcel that crosses, and only for one. An order the
                // packing server keeps — the group it names is one of its own — is packed, sorted and
                // delivered on one machine, and writing a home address onto it would put a note on a
                // parcel about a journey it is not taking. Compare against this node's stable
                // identity, which exists independently of transport availability.
                UUID here = TranserverBridge.localNodeUuid();
                if (here == null) {
                    inbox.state(record.childOrderId(), InboundOrderInbox.State.RECEIVED,
                            "local node identity is not available yet");
                    inbox.flush(server);
                    continue;
                }
                boolean crosses = !here.equals(destination);
                boolean applied = CreateStock.request(record.request().networkId().createFrequency(), items,
                        record.request().address(), server, route,
                        crosses ? record.request().homeAddress() : "",
                        record.request().receivingAddress());
                inbox.state(record.childOrderId(), applied ? InboundOrderInbox.State.APPLIED
                        : InboundOrderInbox.State.RECEIVED, applied ? "" : "network busy or stock unavailable");
                if (applied) {
                    LOG.info("[DistantStock/Order] applied correlation={} child={} source={} network={} group={} lines={}",
                            record.request().correlationId(), record.childOrderId(), record.sourceNodeId(),
                            record.request().networkId().createFrequency(),
                            record.request().receivingDockGroupId(), record.request().lines().size());
                }
            } catch (RuntimeException exception) {
                inbox.state(record.childOrderId(), InboundOrderInbox.State.PROCESSING,
                        "ambiguous after exception: " + exception.getClass().getSimpleName());
                LOG.error("[DistantStock/Order] ambiguous failure correlation={} child={} source={}",
                        record.request().correlationId(), record.childOrderId(), record.sourceNodeId(), exception);
            }
            inbox.flush(server);
        }
    }

    /** Resolve old requests conservatively; v3 carries this answer explicitly from the ordering node. */
    private static UUID destinationNode(MinecraftServer server,
                                        InboundOrderInbox.Record record) {
        if (record.request().destinationNodeId() != null) {
            return record.request().destinationNodeId();
        }
        UUID group = record.request().receivingDockGroupId();
        if (group == null || group.equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return null;
        }
        if (DockGroupDirectory.get(server).find(group).isPresent()) {
            return TranserverBridge.localNodeUuid();
        }
        return RemoteGroups.get(server).find(group).map(RemoteGroups.Entry::node).orElse(null);
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        final OrderRequestCodec.Request request;
        final UUID sourceNode;
        try {
            request = OrderRequestCodec.decode(message.payload());
            sourceNode = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(accept(server, sourceNode, request)));
        return result;
    }

    private static DeliveryResult accept(MinecraftServer server, UUID sourceNode, OrderRequestCodec.Request request) {
        UUID localNode = TranserverBridge.localNodeUuid();
        if (localNode == null || !localNode.equals(request.networkId().nodeId())) {
            return DeliveryResult.REJECTED;
        }
        if (!DistantNetworkDirectory.isFormalId(request.distantNetworkId())) {
            return DeliveryResult.REJECTED;
        }
        if (request.distantNetworkId() != null) {
            UUID localScope = DistantNetworkDirectory.get(server)
                    .formalNetworkOf(request.networkId()).orElse(null);
            if (!request.distantNetworkId().equals(localScope)) {
                // This Create warehouse is not a member of the Distant Stock network named by the
                // request. Never let the ordering node move a warehouse across network boundaries.
                return DeliveryResult.REJECTED;
            }
        }
        if (request.receivingDockGroupId() == null
                || request.receivingDockGroupId().equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return DeliveryResult.REJECTED;
        }
        // When this node already knows the receiving address, an explicit v3 destination must agree
        // with that directory entry. If the address announcement is merely late, the explicit node
        // from the ordering server is still sufficient and avoids the old A/B-only inference.
        UUID knownDestination = knownDestinationNode(server, request.receivingDockGroupId());
        if (request.destinationNodeId() != null && knownDestination != null
                && !request.destinationNodeId().equals(knownDestination)) {
            return DeliveryResult.REJECTED;
        }
        if (request.distantNetworkId() != null) {
            UUID knownScope = knownDestinationScope(server, request.receivingDockGroupId());
            if (knownScope != null && !knownScope.equals(request.distantNetworkId())) {
                return DeliveryResult.REJECTED;
            }
        }
        ResourceLocation dimensionId = ResourceLocation.tryParse(request.networkId().dimensionId());
        if (dimensionId == null) {
            return DeliveryResult.REJECTED;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) {
            return DeliveryResult.RETRY;
        }
        if (!CreateStock.hasNetwork(request.networkId().createFrequency())) {
            // 网络不在这儿。世界 id 对得上 = 它只是还没加载（服务器刚起来、区块还没打开）→ RETRY；
            // 对不上 = 这个世界被换过了，那张网络真的没了 → REJECTED。
            //
            // **先看频率、后看世界 id**，和 TranserverStockService.validateLocal 同一条规则：频率才是
            // 这张网络的名字，世界 id 只是对面**记着的我们**长什么样 —— 它可以是上一次开机的（那次身份
            // 没落盘，见 WorldIdentity）。反过来判的话，重启一次跨服下单就全断了。
            return WorldIdentity.get(level).equals(request.networkId().worldId())
                    ? DeliveryResult.RETRY : DeliveryResult.REJECTED;
        }
        InboundOrderInbox inbox = InboundOrderInbox.get(server);
        InboundOrderInbox.Record record = inbox.receive(sourceNode, request);
        if (record == null) {
            LOG.warn("[DistantStock/Order] rejected identity conflict correlation={} child={} source={}",
                    request.correlationId(), request.childOrderId(), sourceNode);
            return DeliveryResult.REJECTED;
        }
        if (record.state() == InboundOrderInbox.State.RECEIVED) {
            LOG.debug("[DistantStock/Order] accepted correlation={} child={} source={} network={} group={}",
                    request.correlationId(), request.childOrderId(), sourceNode,
                    request.networkId().createFrequency(), request.receivingDockGroupId());
        }
        return switch (record.state()) {
            case APPLIED -> DeliveryResult.APPLIED;
            case REJECTED -> DeliveryResult.REJECTED;
            case RECEIVED, PROCESSING -> {
                if (record.state() == InboundOrderInbox.State.RECEIVED) {
                    inbox.flush(server);
                }
                yield DeliveryResult.RETRY;
            }
        };
    }

    private TranserverOrderService() {
    }
}
