package dev.distantstock.link;

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
                RemoteRoute route = new RemoteRoute(RemoteRoute.CURRENT_SCHEMA, record.sourceNodeId(),
                        record.request().receivingDockGroupId(), record.request().correlationId(),
                        record.request().childOrderId());
                boolean applied = CreateStock.request(record.request().networkId().createFrequency(), items,
                        record.request().address(), server, route);
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
        if (!WorldIdentity.get(level).equals(request.networkId().worldId())) {
            return DeliveryResult.REJECTED;
        }
        if (!CreateStock.hasNetwork(request.networkId().createFrequency())) {
            return DeliveryResult.RETRY;
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
