package dev.distantstock.link;

import dev.distantstock.block.LoadedDocks;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import dev.transerver.api.TranserverApi;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, live confirmation that a remote receiving address can accept a parcel now.
 *
 * <p>Network announcements remain discovery data. They are deliberately not a shipping permit:
 * even a few seconds of stale dock count is enough to let a parcel leave its source and sit in the
 * transport layer waiting for a dock that no longer exists. A sender asks immediately before
 * escrow instead. One positive answer authorises one parcel and is consumed after that parcel
 * leaves, so a burst of packages does not all ride one old answer.
 */
public final class ReceiverProbeService {
    public enum State { AVAILABLE, UNAVAILABLE, PENDING }

    private record Key(UUID node, UUID distantNetwork, UUID group) {
    }

    private record Pending(UUID probeId, long sentAt) {
    }

    private record Answer(boolean available, long receivedAt) {
    }

    private record Request(UUID probeId, UUID distantNetwork, UUID group) {
    }

    private record Result(UUID probeId, UUID distantNetwork, UUID group, boolean available) {
    }

    /** A positive answer is intentionally very short-lived. */
    private static final long ANSWER_TTL_MS = 750L;
    private static final long PENDING_RETRY_MS = 1_500L;
    private static final long FORGET_MS = 10_000L;

    private static final Map<Key, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Key> PENDING_BY_ID = new ConcurrentHashMap<>();
    private static final Map<Key, Answer> ANSWERS = new ConcurrentHashMap<>();

    public static void register() {
        TranserverBridge.handler(RoutingChannels.RECEIVER_PROBE_REQUEST, ReceiverProbeService::receiveRequest);
        TranserverBridge.handler(RoutingChannels.RECEIVER_PROBE_RESULT, ReceiverProbeService::receiveResult);
    }

    public static void stop() {
        PENDING.clear();
        PENDING_BY_ID.clear();
        ANSWERS.clear();
    }

    /**
     * Current answer for one destination. A remote UNKNOWN never means yes: it starts/refreshes a
     * probe and returns PENDING until the destination itself answers.
     */
    public static State state(MinecraftServer server, UUID destinationNode,
                              UUID distantNetwork, UUID group) {
        if (server == null || destinationNode == null || group == null
                || !DistantNetworkDirectory.isFormalId(distantNetwork)) {
            return State.UNAVAILABLE;
        }

        if (TranserverBridge.isLocal(destinationNode.toString())) {
            var local = DockGroupDirectory.get(server).find(group).orElse(null);
            if (local == null || !local.distantNetworkId().equals(distantNetwork)) {
                return State.UNAVAILABLE;
            }
            return LoadedDocks.availableReceiversInGroup(group) > 0
                    ? State.AVAILABLE : State.UNAVAILABLE;
        }

        Key key = new Key(destinationNode, distantNetwork, group);
        long now = System.currentTimeMillis();
        Answer answer = ANSWERS.get(key);
        if (answer != null && now - answer.receivedAt() <= ANSWER_TTL_MS) {
            return answer.available() ? State.AVAILABLE : State.UNAVAILABLE;
        }

        Pending pending = PENDING.get(key);
        boolean timedOut = pending != null && now - pending.sentAt() >= PENDING_RETRY_MS;
        if (pending == null || timedOut) {
            if (pending != null) PENDING_BY_ID.remove(pending.probeId(), key);
            UUID probeId = UUID.randomUUID();
            try {
                UUID message = TranserverBridge.send(destinationNode.toString(),
                        RoutingChannels.RECEIVER_PROBE_REQUEST,
                        encodeRequest(new Request(probeId, distantNetwork, group)), probeId.toString());
                if (message == null) {
                    return State.UNAVAILABLE;
                }
                Pending next = new Pending(probeId, now);
                PENDING.put(key, next);
                PENDING_BY_ID.put(probeId, key);
            } catch (IOException | RuntimeException ignored) {
                return State.UNAVAILABLE;
            }
            // The first probe gets a short grace period. If a whole probe window elapsed without a
            // reply, the dock must not sit forever in a silent PENDING state: retry in the
            // background, but report the destination unavailable so the orange lamp/logger warn.
            if (timedOut) return State.UNAVAILABLE;
        }
        return State.PENDING;
    }

    /** One live yes authorises only one parcel. */
    public static void consumePositive(UUID destinationNode, UUID distantNetwork, UUID group) {
        if (destinationNode == null || distantNetwork == null || group == null) return;
        ANSWERS.remove(new Key(destinationNode, distantNetwork, group));
    }

    public static void tick() {
        acknowledgeCompleted();
        long cutoff = System.currentTimeMillis() - FORGET_MS;
        ANSWERS.entrySet().removeIf(entry -> entry.getValue().receivedAt() < cutoff);
        for (var it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().sentAt() < cutoff) {
                PENDING_BY_ID.remove(entry.getValue().probeId(), entry.getKey());
                it.remove();
            }
        }
    }

    private static CompletableFuture<DeliveryResult> receiveRequest(ReceivedMessage message) {
        final Request request;
        try {
            request = decodeRequest(message.payload());
        } catch (IOException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> applied = new CompletableFuture<>();
        server.execute(() -> {
            var networkDirectory = DistantNetworkDirectory.get(server);
            var group = DockGroupDirectory.get(server).find(request.group()).orElse(null);
            boolean available = networkDirectory.isFormalNetwork(request.distantNetwork())
                    && group != null
                    && group.distantNetworkId().equals(request.distantNetwork())
                    && LoadedDocks.availableReceiversInGroup(request.group()) > 0;
            try {
                UUID sent = TranserverBridge.send(message.source(), RoutingChannels.RECEIVER_PROBE_RESULT,
                        encodeResult(new Result(request.probeId(), request.distantNetwork(),
                                request.group(), available)), request.probeId().toString());
                applied.complete(sent == null ? DeliveryResult.RETRY : DeliveryResult.APPLIED);
            } catch (IOException | RuntimeException exception) {
                applied.complete(DeliveryResult.RETRY);
            }
        });
        return applied;
    }

    private static CompletableFuture<DeliveryResult> receiveResult(ReceivedMessage message) {
        final Result result;
        final UUID source;
        try {
            result = decodeResult(message.payload());
            source = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        Key expected = PENDING_BY_ID.remove(result.probeId());
        if (expected == null || !expected.node().equals(source)
                || !expected.distantNetwork().equals(result.distantNetwork())
                || !expected.group().equals(result.group())) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        Pending pending = PENDING.get(expected);
        if (pending != null && pending.probeId().equals(result.probeId())) {
            PENDING.remove(expected, pending);
        }
        ANSWERS.put(expected, new Answer(result.available(), System.currentTimeMillis()));
        return CompletableFuture.completedFuture(DeliveryResult.APPLIED);
    }

    private static void acknowledgeCompleted() {
        TranserverApi api = TranserverBridge.attachedApi();
        if (api == null) return;
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.RECEIVER_PROBE_REQUEST.equals(completed.channel())
                    || RoutingChannels.RECEIVER_PROBE_RESULT.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static byte[] encodeRequest(Request request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(48);
        DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, request.probeId());
        writeUuid(out, request.distantNetwork());
        writeUuid(out, request.group());
        return bytes.toByteArray();
    }

    private static Request decodeRequest(byte[] payload) throws IOException {
        if (payload == null || payload.length != 48) throw new IOException("invalid receiver probe request");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new Request(readUuid(in), readUuid(in), readUuid(in));
    }

    private static byte[] encodeResult(Result result) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(49);
        DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, result.probeId());
        writeUuid(out, result.distantNetwork());
        writeUuid(out, result.group());
        out.writeBoolean(result.available());
        return bytes.toByteArray();
    }

    private static Result decodeResult(byte[] payload) throws IOException {
        if (payload == null || payload.length != 49) throw new IOException("invalid receiver probe result");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new Result(readUuid(in), readUuid(in), readUuid(in), in.readBoolean());
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private ReceiverProbeService() {
    }
}
