package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.stock.NetworkDirectory;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import dev.transerver.api.TranserverApi;

public final class NetworkAnnouncementService {
    /**
     * How often the announcement is repeated even when nothing about it changed.
     *
     * <p>Announcing only on change was the whole of why the other server's networks disappeared
     * after five minutes: the receiver drops a peer it has not heard from in five minutes, and a
     * node whose network list never changes has no reason of its own to speak again. Well under
     * that timeout, and rare enough to be nothing on the wire.
     */
    private static final long REFRESH_MS = 45_000;

    private static List<NetworkDirectory.Entry> lastLocal = List.of();
    private static Set<String> lastRecipients = Set.of();
    private static long lastSentAt;

    public static void register() {
        TranserverBridge.handler(RoutingChannels.NETWORK_ANNOUNCE, NetworkAnnouncementService::receive);
    }

    /**
     * Says what this node has, and how it is doing, to every other node.
     *
     * <p>Two reasons to speak. The list changing is worth saying at once — a network that appeared a
     * second ago should be joinable on the other server a second from now. And saying it again after
     * {@link #REFRESH_MS} of silence, because the receiver forgets a peer it has not heard from and
     * a node whose list never changes would otherwise go quiet for ever and be forgotten.
     *
     * <p>Compared by the list rather than by the payload: the payload also carries this node's tick
     * metrics, which change every second, so encoding equality would send an announcement on every
     * beat.
     */
    public static void publish() {
        acknowledgeCompleted();
        UUID self = TranserverBridge.nodeId();
        if (self == null) {
            return;
        }
        List<NetworkDirectory.Entry> local = NetworkDirectory.local().stream()
                .filter(entry -> entry.networkId() != null).toList();
        Set<String> recipients = TranserverBridge.knownNodes();
        if (recipients.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = !local.equals(lastLocal) || !recipients.equals(lastRecipients);
        if (!changed && now - lastSentAt < REFRESH_MS) {
            return;
        }
        try {
            byte[] payload = NetworkAnnouncementCodec.encode(local,
                    LinkSnapshot.localTps, LinkSnapshot.localMspt);
            for (String node : recipients) {
                if (!node.equals(self.toString())) {
                    TranserverBridge.send(node, RoutingChannels.NETWORK_ANNOUNCE, payload, null);
                }
            }
            lastLocal = List.copyOf(local);
            lastRecipients = Set.copyOf(recipients);
            lastSentAt = now;
        } catch (IOException ignored) {
        }
    }

    private static void acknowledgeCompleted() {
        TranserverApi api = TranserverBridge.attachedApi();
        if (api == null) {
            return;
        }
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.NETWORK_ANNOUNCE.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        try {
            UUID source = UUID.fromString(message.source());
            List<NetworkDirectory.Entry> entries = NetworkAnnouncementCodec.decode(message.payload());
            if (entries.stream().anyMatch(entry -> !entry.networkId().nodeId().equals(source))) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
            MinecraftServer server = TranserverBridge.server();
            if (server == null || !server.isRunning()) {
                return CompletableFuture.completedFuture(DeliveryResult.RETRY);
            }
            NetworkAnnouncementCodec.Metrics metrics = NetworkAnnouncementCodec.metrics(message.payload());
            CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
            server.execute(() -> {
                NetworkDirectory.replacePeer(message.source(), entries);
                if (metrics.known()) {
                    // This is where a peer's TPS comes from in Transerver mode: nothing else crosses
                    // on a regular beat, and the monitor shows the number.
                    LinkSnapshot.peerMetrics(message.source(), metrics.tps(), metrics.mspt());
                }
                result.complete(DeliveryResult.APPLIED);
            });
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
    }

    private NetworkAnnouncementService() {
    }
}
