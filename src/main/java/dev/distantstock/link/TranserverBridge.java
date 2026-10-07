package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.CompletedSend;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.MessageHandler;
import dev.transerver.api.NodeIdentity;
import dev.transerver.api.NodeStatus;
import dev.transerver.api.SendOptions;
import dev.transerver.api.TranserverApi;
import dev.transerver.api.TranserverServices;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Set;

/** The only boundary through which Distant Stock talks to Transerver. */
public final class TranserverBridge {
    private static final Logger LOG = LogManager.getLogger();
    private static final Map<String, MessageHandler> HANDLERS = new ConcurrentHashMap<>();
    private static volatile TranserverApi attached;
    private static volatile MinecraftServer server;

    /**
     * Everything below is a cache of Transerver operations that may touch disk or HTTP. None of
     * those operations is allowed on Minecraft's server tick thread.
     */
    private static volatile NodeStatus cachedStatus;
    private static volatile NodeIdentity cachedIdentity;
    private static volatile Set<String> cachedKnownNodes = Set.of();
    private static volatile List<CompletedSend> cachedCompletedSends = List.of();
    private static final Set<UUID> ACK_PENDING = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService maintenance;
    private static final int COMPLETION_BATCH = 4096;
    private static final int ACKS_PER_PASS = 128;

    public static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        attachIfReady();
        startMaintenance();
    }

    public static void tick() {
        attachIfReady();
        TranserverApi api = attached;
        NodeStatus status = cachedStatus;
        if (api == null || status == null) {
            LinkSnapshot.transerverUnavailable();
            return;
        }
        NodeIdentity identity = cachedIdentity;
        LinkSnapshot.transerver(
                status.nodeId(),
                identity == null ? status.nodeId() : identity.alias(),
                status.transportUp(),
                status.lastFailure(),
                status.outboxDepth(),
                status.inboxDepth(),
                status.completedSendDepth(),
                status.deadLetterDepth()
        );
    }

    public static synchronized void stop() {
        server = null;
        attached = null;
        cachedStatus = null;
        cachedIdentity = null;
        cachedKnownNodes = Set.of();
        cachedCompletedSends = List.of();
        ACK_PENDING.clear();
        if (maintenance != null) {
            maintenance.shutdownNow();
            maintenance = null;
        }
        LinkSnapshot.transerverUnavailable();
    }

    public static MinecraftServer server() {
        return server;
    }

    public static UUID send(String destination, String channel, byte[] payload, String correlationId) {
        TranserverApi api = attached;
        if (api == null) {
            return null;
        }
        return api.send(destination, channel, payload,
                new SendOptions(correlationId, "application/x-distantstock", Instant.now().plus(7, ChronoUnit.DAYS)))
                .messageId();
    }

    public static TranserverApi attachedApi() {
        return attached;
    }

    public static UUID nodeId() {
        // Do not call api.status() here. Transerver's file-backed implementation calculates queue
        // depths while building NodeStatus, which scans and reads its queue directories. The node
        // identity is persisted separately and is the same stable UUID without any disk-queue scan.
        if (attached == null) {
            return null;
        }
        return localNodeUuid();
    }

    /**
     * Stable identity of this Transerver node, independent of transport availability.
     *
     * <p>Transerver publishes its persisted {@code node-identity.properties} identity even when the
     * router transport is disabled. Distant Stock deliberately has no fallback/sentinel UUID: a
     * missing identity is a startup/configuration failure, not a different node.
     */
    public static UUID localNodeUuid() {
        NodeIdentity identity = TranserverServices.identity().orElse(null);
        return identity == null ? null : identity.nodeId();
    }

    public static String localNodeId() {
        UUID id = localNodeUuid();
        return id == null ? "" : id.toString();
    }

    /**
     * 记录里的目的地是不是本机。空白也算——那是比节点 id 更早的记录。
     *
     * <p>A blank destination is what every record written before node ids existed carries, and this
     * save is the only node that could ever have meant. Treating it as local is what lets those
     * parcels finish instead of sitting in the escrow forever.
     */
    public static boolean isLocal(String destination) {
        if (destination == null || destination.isBlank()) {
            // Very old records predate node identities and can only have meant this save.
            return true;
        }
        UUID local = localNodeUuid();
        return local != null && local.toString().equals(destination);
    }

    /**
     * Cached router membership. The real Transerver call performs a synchronous HTTP request, so
     * callers on the game thread must never invoke it directly.
     */
    public static Set<String> knownNodes() {
        return attached == null ? Set.of() : cachedKnownNodes;
    }

    /** Cached completed sends, populated by the maintenance thread without game-thread disk I/O. */
    public static List<CompletedSend> completedSends(int limit) {
        if (limit <= 0 || attached == null) return List.of();
        var out = new java.util.ArrayList<CompletedSend>(Math.min(limit, cachedCompletedSends.size()));
        for (CompletedSend completed : cachedCompletedSends) {
            if (ACK_PENDING.contains(completed.messageId())) continue;
            out.add(completed);
            if (out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    /** Queue an acknowledgement for the maintenance thread; never fsync/delete on the tick thread. */
    public static void acknowledgeCompletedSend(UUID messageId) {
        if (messageId != null && attached != null) ACK_PENDING.add(messageId);
    }

    public static void handler(String channel, MessageHandler handler) {
        if (!RoutingChannels.all().contains(channel)) {
            throw new IllegalArgumentException("Unknown Distant Stock channel: " + channel);
        }
        if (handler == null) {
            HANDLERS.remove(channel);
        } else {
            HANDLERS.put(channel, handler);
        }
    }

    private static synchronized void attachIfReady() {
        TranserverApi available = TranserverServices.api().orElse(null);
        if (available == null || available == attached) {
            return;
        }
        for (String channel : RoutingChannels.all()) {
            available.registerHandler(channel, message -> {
                MessageHandler handler = HANDLERS.get(message.channel());
                if (handler == null) {
                    return CompletableFuture.completedFuture(DeliveryResult.RETRY);
                }
                try {
                    CompletionStage<DeliveryResult> stage = handler.handle(message);
                    if (stage == null) {
                        LOG.error("Distant Stock Transerver handler returned null: channel={} message={} source={}",
                                message.channel(), message.messageId(), message.source());
                        return CompletableFuture.completedFuture(DeliveryResult.RETRY);
                    }
                    return stage.handle((result, error) -> {
                        if (error != null) {
                            LOG.error("Distant Stock Transerver handler failed: channel={} message={} source={}",
                                    message.channel(), message.messageId(), message.source(), error);
                            return DeliveryResult.RETRY;
                        }
                        if (result == null) {
                            LOG.error("Distant Stock Transerver handler completed with null: channel={} message={} source={}",
                                    message.channel(), message.messageId(), message.source());
                            return DeliveryResult.RETRY;
                        }
                        return result;
                    });
                } catch (RuntimeException error) {
                    LOG.error("Distant Stock Transerver handler threw before returning: channel={} message={} source={}",
                            message.channel(), message.messageId(), message.source(), error);
                    return CompletableFuture.completedFuture(DeliveryResult.RETRY);
                }
            });
        }
        attached = available;
        NodeIdentity identity = TranserverServices.identity().orElse(null);
        cachedIdentity = identity;
        cachedStatus = null;
        cachedKnownNodes = Set.of();
        cachedCompletedSends = List.of();
        LOG.info("Distant Stock attached to Transerver node {}",
                identity == null ? "<identity pending>" : identity.nodeId());
    }

    private static synchronized void startMaintenance() {
        if (maintenance != null && !maintenance.isShutdown()) return;
        maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "distantstock-transerver-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(TranserverBridge::maintenancePass, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Slow Transerver API surface. status() scans its file store in old builds, knownNodes() is a
     * blocking HTTP request, completedSends() sorts/reads files, and acknowledgements mutate that
     * store. Keeping the whole set here prevents a slow router or a large queue from extending a
     * Minecraft tick.
     */
    private static void maintenancePass() {
        TranserverApi api = attached;
        if (api == null) return;

        // Ack first so a large completed-send directory shrinks instead of being scanned forever,
        // but cap one pass: old stores can contain thousands of results and each acknowledgement
        // may fsync. A bounded drain gives Transerver's own pump thread regular chances at the store.
        int acked = 0;
        for (UUID id : java.util.List.copyOf(ACK_PENDING)) {
            if (acked >= ACKS_PER_PASS) break;
            try {
                api.acknowledgeCompletedSend(id);
                ACK_PENDING.remove(id);
                acked++;
            } catch (RuntimeException ignored) {
                // Keep it queued; a later pass retries without stalling the server tick.
            }
        }

        try {
            cachedIdentity = TranserverServices.identity().orElse(cachedIdentity);
        } catch (RuntimeException ignored) {
        }
        try {
            cachedStatus = api.status();
        } catch (RuntimeException ignored) {
        }
        try {
            cachedKnownNodes = Set.copyOf(api.knownNodes());
        } catch (RuntimeException ignored) {
            // Keep the last good membership during a router hiccup.
        }
        try {
            List<CompletedSend> completed = api.completedSends(COMPLETION_BATCH);
            cachedCompletedSends = List.copyOf(completed);
            // These channels never had a consumer for transport completion. Without this, their
            // SEND_RESULTS files accumulate forever and every status/completion scan gets slower.
            for (CompletedSend send : completed) {
                String channel = send.channel();
                if (RoutingChannels.ORDER_REQUEST.equals(channel)
                        || RoutingChannels.ORDER_RESULT.equals(channel)
                        || RoutingChannels.PACKAGE_STRIP.equals(channel)) {
                    ACK_PENDING.add(send.messageId());
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private TranserverBridge() {
    }
}
