package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.stock.NetworkDirectory;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import dev.transerver.api.TranserverApi;

public final class NetworkAnnouncementService {
    private static byte[] lastAnnouncement;
    private static Set<String> lastRecipients = Set.of();

    public static void register() {
        TranserverBridge.handler(RoutingChannels.NETWORK_ANNOUNCE, NetworkAnnouncementService::receive);
    }

    public static void publishIfChanged() {
        acknowledgeCompleted();
        UUID self = TranserverBridge.nodeId();
        if (self == null) {
            return;
        }
        List<NetworkDirectory.Entry> local = NetworkDirectory.local().stream()
                .filter(entry -> entry.networkId() != null).toList();
        try {
            byte[] payload = NetworkAnnouncementCodec.encode(local);
            Set<String> recipients = TranserverBridge.knownNodes();
            if (recipients.isEmpty()) {
                return;
            }
            if (Arrays.equals(payload, lastAnnouncement) && recipients.equals(lastRecipients)) {
                return;
            }
            for (String node : recipients) {
                if (!node.equals(self.toString())) {
                    TranserverBridge.send(node, RoutingChannels.NETWORK_ANNOUNCE, payload, null);
                }
            }
            lastAnnouncement = payload;
            lastRecipients = Set.copyOf(recipients);
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
            CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
            server.execute(() -> {
                NetworkDirectory.replacePeer(message.source(), entries);
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
