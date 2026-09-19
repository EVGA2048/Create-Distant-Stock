package dev.distantstock.link;

import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server join-code handshake.
 *
 * <p>The code is never announced. A joining node broadcasts only the code the player typed; only
 * the authority that owns that code answers, and the accepted member stores the network UUID from
 * then on.
 */
public final class DistantNetworkJoinService {
    private record Pending(UUID player, RemoteNetworkId member, long sentAt) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 10_000L;

    public static void register() {
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                DistantNetworkJoinService::receiveRequest);
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT,
                DistantNetworkJoinService::receiveAccept);
    }

    public static void stop() {
        PENDING.clear();
    }

    /**
     * Starts a join. Returns false only when nothing could possibly receive the request.
     * A local code is applied immediately without going over Transerver.
     */
    public static boolean request(MinecraftServer server, UUID player, RemoteNetworkId member, String code) {
        if (server == null || player == null || member == null) return false;
        String normalized;
        try {
            normalized = DistantNetworkDirectory.normalizeCode(code);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
        var local = directory.findByCode(normalized).orElse(null);
        if (local != null) {
            UUID current = directory.networkOf(member).orElse(null);
            if (current != null && !current.equals(local.id())) {
                return false;
            }
            directory.attach(member, local.id());
            dev.distantstock.stock.StockScanner.scan(server);
            notify(server, player, "message.distantstock.network.joined", local.name());
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
            if (serverPlayer != null) {
                if (serverPlayer.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
                    menu.persistDistantNetworkScope(serverPlayer, member, local.id());
                    menu.refresh(serverPlayer);
                }
                dev.distantstock.net.DistantNetworkStateS2C.send(serverPlayer, member);
            }
            return true;
        }

        if (TranserverBridge.attachedApi() == null || TranserverBridge.knownNodes().isEmpty()) {
            return false;
        }
        // Double-clicking Join should not create two independent timers. Otherwise one request may
        // succeed and the other later emit a bogus "not found" message for the same warehouse.
        boolean alreadyPending = PENDING.values().stream()
                .anyMatch(pending -> pending.player().equals(player) && pending.member().equals(member));
        if (alreadyPending) {
            return true;
        }
        UUID requestId = UUID.randomUUID();
        byte[] payload;
        try {
            payload = DistantNetworkJoinCodec.encodeRequest(
                    new DistantNetworkJoinCodec.Request(requestId, normalized, member));
        } catch (IOException exception) {
            return false;
        }
        PENDING.put(requestId, new Pending(player, member, System.currentTimeMillis()));
        int sent = 0;
        String self = TranserverBridge.localNodeId();
        for (String node : TranserverBridge.knownNodes()) {
            if (node.equals(self)) continue;
            if (TranserverBridge.send(node, RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                    payload, requestId.toString()) != null) {
                sent++;
            }
        }
        if (sent == 0) {
            PENDING.remove(requestId);
            return false;
        }
        return true;
    }

    public static void tick(MinecraftServer server) {
        acknowledgeCompleted();
        long cutoff = System.currentTimeMillis() - TIMEOUT_MS;
        PENDING.entrySet().removeIf(entry -> {
            if (entry.getValue().sentAt() >= cutoff) return false;
            notify(server, entry.getValue().player(), "message.distantstock.network.code_not_found");
            return true;
        });
    }

    private static CompletableFuture<DeliveryResult> receiveRequest(ReceivedMessage message) {
        final DistantNetworkJoinCodec.Request request;
        final UUID source;
        try {
            request = DistantNetworkJoinCodec.decodeRequest(message.payload());
            source = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        if (!source.equals(request.member().nodeId())) {
            // A node may only enroll a Create network that actually belongs to that node.
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> {
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            var network = directory.findByCode(request.joinCode()).orElse(null);
            if (network == null || network.ownerNode() == null
                    || !network.ownerNode().equals(TranserverBridge.nodeId())) {
                // The request is deliberately broadcast. "Not mine" is a handled no-op, not a
                // reason for Transerver to retry it for seven days.
                result.complete(DeliveryResult.APPLIED);
                return;
            }
            // The authority grants membership but does not persist the remote Create network as its
            // own SavedData membership. The warehouse's node is authoritative for that relationship
            // and announces it afterwards. Keeping a second permanent copy here would drift the
            // moment that remote warehouse leaves while this node is offline.
            try {
                byte[] payload = DistantNetworkJoinCodec.encodeAccept(
                        new DistantNetworkJoinCodec.Accept(request.requestId(), network.id(),
                                network.name(), network.ownerNode(), request.member()));
                UUID sent = TranserverBridge.send(source.toString(),
                        RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT, payload,
                        request.requestId().toString());
                result.complete(sent == null ? DeliveryResult.RETRY : DeliveryResult.APPLIED);
            } catch (IOException exception) {
                result.complete(DeliveryResult.REJECTED);
            }
        });
        return result;
    }

    private static CompletableFuture<DeliveryResult> receiveAccept(ReceivedMessage message) {
        final DistantNetworkJoinCodec.Accept accept;
        final UUID source;
        try {
            accept = DistantNetworkJoinCodec.decodeAccept(message.payload());
            source = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        if (!source.equals(accept.ownerNode())) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        Pending pending = PENDING.get(accept.requestId());
        if (pending == null) {
            // Transerver is at-least-once. If we already applied this acceptance, acknowledge a
            // duplicate instead of turning it into a retry loop/dead letter.
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            boolean alreadyApplied = directory.networkOf(accept.member())
                    .map(accept.networkId()::equals).orElse(false);
            return CompletableFuture.completedFuture(
                    alreadyApplied ? DeliveryResult.APPLIED : DeliveryResult.REJECTED);
        }
        if (!pending.member().equals(accept.member())) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> {
            Pending accepted = PENDING.remove(accept.requestId());
            if (accepted == null || !accepted.member().equals(accept.member())) {
                result.complete(DeliveryResult.REJECTED);
                return;
            }
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            UUID current = directory.networkOf(accept.member()).orElse(null);
            if (current != null && !current.equals(accept.networkId())) {
                result.complete(DeliveryResult.REJECTED);
                return;
            }
            directory.rememberReplica(accept.networkId(), accept.networkName(), accept.ownerNode());
            directory.attach(accept.member(), accept.networkId());
            dev.distantstock.stock.StockScanner.scan(server);
            notify(server, accepted.player(), "message.distantstock.network.joined", accept.networkName());
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(accepted.player());
            if (serverPlayer != null) {
                if (serverPlayer.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
                    menu.persistDistantNetworkScope(serverPlayer, accept.member(), accept.networkId());
                    menu.refresh(serverPlayer);
                }
                dev.distantstock.net.DistantNetworkStateS2C.send(serverPlayer, accept.member());
            }
            result.complete(DeliveryResult.APPLIED);
        });
        return result;
    }

    private static void acknowledgeCompleted() {
        var api = TranserverBridge.attachedApi();
        if (api == null) return;
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST.equals(completed.channel())
                    || RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static void notify(MinecraftServer server, UUID playerId, String key, Object... args) {
        if (server == null || playerId == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), false);
        }
    }

    private DistantNetworkJoinService() {
    }
}
