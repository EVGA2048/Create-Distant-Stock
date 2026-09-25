package dev.distantstock.link;

import dev.distantstock.routing.DistantNetworkDeletion;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Authority-signed tombstone for a deleted Distant Stock network. */
public final class DistantNetworkDeleteService {
    public static void register() {
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_DELETE,
                DistantNetworkDeleteService::receive);
    }

    public static void broadcast(DistantNetworkDirectory.Network network) {
        if (network == null || network.legacy() || network.ownerNode() == null) return;
        UUID self = TranserverBridge.localNodeUuid();
        if (self == null || !self.equals(network.ownerNode())) return;
        byte[] payload;
        try {
            payload = encode(network.id(), network.ownerNode());
        } catch (IOException impossible) {
            return;
        }
        for (String node : TranserverBridge.knownNodes()) {
            if (node.equals(self.toString())) continue;
            TranserverBridge.send(node, RoutingChannels.DISTANT_NETWORK_DELETE, payload,
                    network.id().toString());
        }
    }

    public static void tick() {
        var api = TranserverBridge.attachedApi();
        if (api == null) return;
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.DISTANT_NETWORK_DELETE.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        final UUID networkId;
        final UUID ownerNode;
        final UUID source;
        try {
            UUID[] decoded = decode(message.payload());
            networkId = decoded[0];
            ownerNode = decoded[1];
            source = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        if (!source.equals(ownerNode)) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> {
            var directory = DistantNetworkDirectory.get(server);
            var known = directory.find(networkId).orElse(null);
            if (known == null) {
                result.complete(DeliveryResult.APPLIED);
                return;
            }
            if (known.ownerNode() == null || !known.ownerNode().equals(ownerNode)) {
                result.complete(DeliveryResult.REJECTED);
                return;
            }
            DistantNetworkDeletion.apply(server, networkId);
            NetworkAnnouncementService.publish();
            result.complete(DeliveryResult.APPLIED);
        });
        return result;
    }

    private static byte[] encode(UUID network, UUID owner) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(network.getMostSignificantBits());
            out.writeLong(network.getLeastSignificantBits());
            out.writeLong(owner.getMostSignificantBits());
            out.writeLong(owner.getLeastSignificantBits());
        }
        return bytes.toByteArray();
    }

    private static UUID[] decode(byte[] payload) throws IOException {
        if (payload == null || payload.length != 32) throw new IOException("invalid delete tombstone");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            UUID network = new UUID(in.readLong(), in.readLong());
            UUID owner = new UUID(in.readLong(), in.readLong());
            return new UUID[]{network, owner};
        }
    }

    private DistantNetworkDeleteService() {
    }
}
