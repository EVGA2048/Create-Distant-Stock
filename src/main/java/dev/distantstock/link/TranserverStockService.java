package dev.distantstock.link;

import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.StockCache;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import dev.transerver.api.TranserverApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TranserverStockService {
    private static final Map<RemoteNetworkId, UUID> OUTSTANDING = new ConcurrentHashMap<>();

    public static void register() {
        TranserverBridge.handler(RoutingChannels.STOCK_QUERY, TranserverStockService::query);
        TranserverBridge.handler(RoutingChannels.STOCK_RESULT, TranserverStockService::result);
    }

    public static void tick() {
        acknowledgeCompleted();
        UUID local = TranserverBridge.nodeId();
        if (local == null) {
            return;
        }
        for (RemoteNetworkId network : StockCache.watchedNetworks(5 * 60_000L)) {
            if (network.nodeId().equals(local) || OUTSTANDING.containsKey(network)) {
                continue;
            }
            long age = StockCache.ageMs(network);
            if (age >= 0 && age < 5_000L) {
                continue;
            }
            try {
                UUID queryId = UUID.randomUUID();
                UUID messageId = TranserverBridge.send(network.nodeId().toString(), RoutingChannels.STOCK_QUERY,
                        StockWireCodec.encodeQuery(new StockWireCodec.Query(queryId, network)), queryId.toString());
                if (messageId != null) {
                    OUTSTANDING.put(network, queryId);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static CompletableFuture<DeliveryResult> query(ReceivedMessage message) {
        final StockWireCodec.Query query;
        try {
            query = StockWireCodec.decodeQuery(message.payload());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> applied = new CompletableFuture<>();
        server.execute(() -> {
            DeliveryResult valid = validateLocal(server, query.networkId());
            if (valid != DeliveryResult.APPLIED) {
                applied.complete(valid);
                return;
            }
            try {
                var lines = CreateStock.summary(query.networkId().createFrequency()).stream()
                        .map(item -> new LinkQueues.Line(item.itemId, item.count)).toList();
                StockWireCodec.Result result = new StockWireCodec.Result(query.queryId(), query.networkId(), lines);
                UUID sent = TranserverBridge.send(message.source(), RoutingChannels.STOCK_RESULT,
                        StockWireCodec.encodeResult(result), query.queryId().toString());
                applied.complete(sent == null ? DeliveryResult.RETRY : DeliveryResult.APPLIED);
            } catch (IOException | RuntimeException exception) {
                applied.complete(DeliveryResult.RETRY);
            }
        });
        return applied;
    }

    private static CompletableFuture<DeliveryResult> result(ReceivedMessage message) {
        final StockWireCodec.Result result;
        try {
            result = StockWireCodec.decodeResult(message.payload());
            if (!result.networkId().nodeId().equals(UUID.fromString(message.source()))) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> applied = new CompletableFuture<>();
        server.execute(() -> {
            var entries = new ArrayList<StockCache.Entry>();
            for (LinkQueues.Line line : result.items()) {
                ResourceLocation id = ResourceLocation.tryParse(line.itemId());
                if (id == null || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                    continue;
                }
                entries.add(new StockCache.Entry(line.itemId(), line.count()));
            }
            StockCache.put(result.networkId(), entries, StockCache.Source.PEER);
            OUTSTANDING.remove(result.networkId(), result.queryId());
            applied.complete(DeliveryResult.APPLIED);
        });
        return applied;
    }

    private static DeliveryResult validateLocal(MinecraftServer server, RemoteNetworkId network) {
        UUID node = TranserverBridge.nodeId();
        if (node == null || !node.equals(network.nodeId())) {
            return DeliveryResult.REJECTED;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(network.dimensionId());
        if (dimension == null) {
            return DeliveryResult.REJECTED;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            return DeliveryResult.RETRY;
        }
        if (!WorldIdentity.get(level).equals(network.worldId())) {
            return DeliveryResult.REJECTED;
        }
        return CreateStock.hasNetwork(network.createFrequency())
                ? DeliveryResult.APPLIED : DeliveryResult.RETRY;
    }

    private static void acknowledgeCompleted() {
        TranserverApi api = TranserverBridge.attachedApi();
        if (api == null) {
            return;
        }
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.STOCK_QUERY.equals(completed.channel())) {
                try {
                    StockWireCodec.Query query = StockWireCodec.decodeQuery(completed.payload());
                    if (completed.state() == dev.transerver.api.DeliveryState.REJECTED) {
                        OUTSTANDING.remove(query.networkId(), query.queryId());
                    }
                } catch (IOException ignored) {
                }
                api.acknowledgeCompletedSend(completed.messageId());
            } else if (RoutingChannels.STOCK_RESULT.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private TranserverStockService() {
    }
}
