package dev.distantstock.link;

import dev.distantstock.routing.DockGroupDirectory;
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
                RemoteRoute route = new RemoteRoute(RemoteRoute.CURRENT_SCHEMA,
                        destination, record.request().receivingDockGroupId(),
                        record.request().correlationId(), record.request().childOrderId());
                // The second address is for a parcel that crosses, and only for one. An order the
                // packing server keeps — the group it names is one of its own — is packed, sorted and
                // delivered on one machine, and writing a home address onto it would put a note on a
                // parcel about a journey it is not taking. Compare against this node: the sentinel is
                // what "here" means on a save with no Transerver attached.
                UUID here = TranserverBridge.nodeId();
                boolean crosses = here == null
                        ? !destination.toString().equals(TranserverBridge.localNodeId())
                        : !here.equals(destination);
                boolean applied = CreateStock.request(record.request().networkId().createFrequency(), items,
                        record.request().address(), server, route,
                        crosses ? record.request().homeAddress() : "");
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

    /**
     * Which node the goods are finally delivered on: this one, or the one that placed the order.
     *
     * <p>A group named in this server's own directory is a group here, so the parcel stays here and
     * comes out of a dock on this server — that is what a destination on somebody else's server
     * announcement means, and the only way to order from their warehouse and have the goods handed
     * to a player standing next to it. Anything else names a group this server has never heard of: the
     * order goes home, and the goods come out of the dock the ordering player chose there.
     *
     * <p>The default group is excluded on purpose. It exists in every directory, including this
     * one, so it would otherwise read as "deliver here" — and it means the opposite: an order that
     * named no group is an order that wants its goods back where it came from.
     */
    private static UUID destinationNode(MinecraftServer server,
                                        InboundOrderInbox.Record record) {
        UUID group = record.request().receivingDockGroupId();
        if (group == null || group.equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return record.sourceNodeId();
        }
        if (!DockGroupDirectory.get(server).find(group).isPresent()) {
            return record.sourceNodeId();
        }
        UUID local = TranserverBridge.nodeId();
        // Without a Transerver attached this save is the only node there is; the sentinel is what
        // every other record in the mod uses to mean "here".
        return local == null ? UUID.fromString(TranserverBridge.localNodeId()) : local;
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
        UUID localNode = TranserverBridge.nodeId();
        if (localNode == null || !localNode.equals(request.networkId().nodeId())) {
            return DeliveryResult.REJECTED;
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
