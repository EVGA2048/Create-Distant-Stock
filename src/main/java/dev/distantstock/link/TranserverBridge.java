package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    public static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        attachIfReady();
    }

    public static void tick() {
        attachIfReady();
        TranserverApi api = attached;
        if (api == null) {
            LinkSnapshot.transerverUnavailable();
            return;
        }
        try {
            NodeStatus status = api.status();
            NodeIdentity identity = TranserverServices.identity().orElse(null);
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
        } catch (RuntimeException exception) {
            LinkSnapshot.transerverUnavailable();
        }
    }

    public static void stop() {
        server = null;
        attached = null;
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
        TranserverApi api = attached;
        if (api == null) {
            return null;
        }
        try {
            return UUID.fromString(api.status().nodeId());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 没有挂上 Transerver 时用的节点 id：一个存档自己就是一个节点。
     *
     * <p>The sentinel exists because a single-player save still ships the whole Transerver parcel
     * pipeline: {@code TranserverBridge.attachedApi()} being null only means no Transerver server
     * is configured, not that this save has nowhere to deliver. Sending a parcel to "this node" has
     * to be expressible in the same UUID form a real node id uses, because that is the form
     * {@code ParcelEscrow.Record.destinationNode()} persists and compares.
     */
    public static final String LOCAL_NODE_ID = "00000000-0000-0000-0000-000000000001";

    /** 本机节点 id。挂上了就是 Transerver 的，没挂上就是哨兵。 */
    public static String localNodeId() {
        UUID attached = nodeId();
        return attached == null ? LOCAL_NODE_ID : attached.toString();
    }

    /**
     * 记录里的目的地是不是本机。空白也算——那是比节点 id 更早的记录。
     *
     * <p>A blank destination is what every record written before node ids existed carries, and this
     * save is the only node that could ever have meant. Treating it as local is what lets those
     * parcels finish instead of sitting in the escrow forever.
     */
    public static boolean isLocal(String destination) {
        return destination == null || destination.isBlank() || localNodeId().equals(destination);
    }

    public static Set<String> knownNodes() {
        TranserverApi api = attached;
        if (api == null) {
            return Set.of();
        }
        try {
            return api.knownNodes();
        } catch (RuntimeException exception) {
            return Set.of();
        }
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
                return handler == null
                        ? CompletableFuture.completedFuture(DeliveryResult.RETRY)
                        : handler.handle(message);
            });
        }
        attached = available;
        LOG.info("Distant Stock attached to Transerver node {}", available.status().nodeId());
    }

    private TranserverBridge() {
    }
}
