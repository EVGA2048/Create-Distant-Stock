package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime peer capability table; deliberately separate from the stable network-announcement wire. */
public final class ProtocolHelloService {
    public static final int CURRENT_PROTOCOL = 1;
    private static final long REFRESH_MS = 45_000;
    private static final long STALE_MS = 120_000;
    private static final Set<String> REQUIRED = Set.of(
            "package-dispatch-v1", "manifest-v1", "parcel-return-v1");
    private static final Map<String, Info> PEERS = new ConcurrentHashMap<>();
    private static volatile long lastSentAt;

    public enum Compatibility { COMPATIBLE, INCOMPATIBLE, UNKNOWN }

    public record Info(String node, int protocol, Set<String> capabilities, long seenAt) {
        public Info {
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        }
    }

    public static void register() {
        TranserverBridge.handler(RoutingChannels.PROTOCOL_HELLO, message -> {
            try {
                ProtocolHelloCodec.Hello hello = ProtocolHelloCodec.decode(message.payload());
                PEERS.put(message.source(), new Info(message.source(), hello.protocol(), hello.capabilities(),
                        System.currentTimeMillis()));
                return CompletableFuture.completedFuture(DeliveryResult.APPLIED);
            } catch (IOException bad) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
        });
    }

    public static Set<String> localCapabilities() {
        LinkedHashSet<String> caps = new LinkedHashSet<>();
        caps.add("package-dispatch-v1");
        caps.add("manifest-v1");
        caps.add("home-address-v1");
        caps.add("parcel-return-v1");
        caps.add("parcel-trace-v1");
        caps.add("chain-diagnostics-v2");
        caps.add("cache-lease-v1");
        if (ModList.get().isLoaded("fluidlogistics")) caps.add("fluid-package-v2");
        return Set.copyOf(caps);
    }

    public static void publish() {
        acknowledgeCompleted();
        UUID self = TranserverBridge.nodeId();
        if (self == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSentAt < REFRESH_MS) return;
        Set<String> recipients = TranserverBridge.knownNodes();
        if (recipients.isEmpty()) return;
        try {
            byte[] payload = ProtocolHelloCodec.encode(new ProtocolHelloCodec.Hello(
                    CURRENT_PROTOCOL, localCapabilities()));
            for (String node : recipients) {
                if (!node.equals(self.toString())) {
                    TranserverBridge.send(node, RoutingChannels.PROTOCOL_HELLO, payload, null);
                }
            }
            lastSentAt = now;
        } catch (IOException ignored) {
        }
        PEERS.entrySet().removeIf(entry -> now - entry.getValue().seenAt() > STALE_MS * 3);
    }

    public static Info peer(String node) {
        if (node == null || node.isBlank()) return null;
        Info info = PEERS.get(node);
        if (info == null || System.currentTimeMillis() - info.seenAt() > STALE_MS) return null;
        return info;
    }

    public static Compatibility compatibility(String node) {
        if (node == null || node.isBlank() || TranserverBridge.isLocal(node)) return Compatibility.COMPATIBLE;
        Info info = peer(node);
        if (info == null) return Compatibility.UNKNOWN;
        if (info.protocol() != CURRENT_PROTOCOL || !info.capabilities().containsAll(REQUIRED)) {
            return Compatibility.INCOMPATIBLE;
        }
        return Compatibility.COMPATIBLE;
    }

    public static List<String> missingRequired(String node) {
        Info info = peer(node);
        if (info == null) return List.of();
        List<String> missing = new ArrayList<>();
        for (String cap : REQUIRED) if (!info.capabilities().contains(cap)) missing.add(cap);
        if (info.protocol() != CURRENT_PROTOCOL) {
            missing.add("protocol=" + info.protocol() + " expected=" + CURRENT_PROTOCOL);
        }
        return List.copyOf(missing);
    }

    /** Test seam for compatibility classification without a live Transerver router. */
    public static void rememberForTesting(String node, int protocol, Set<String> capabilities) {
        if (node != null && !node.isBlank()) {
            PEERS.put(node, new Info(node, protocol, capabilities, System.currentTimeMillis()));
        }
    }

    public static Map<String, Info> peers() {
        long now = System.currentTimeMillis();
        Map<String, Info> out = new java.util.LinkedHashMap<>();
        PEERS.values().stream().sorted(java.util.Comparator.comparing(Info::node)).forEach(info -> {
            if (now - info.seenAt() <= STALE_MS) out.put(info.node(), info);
        });
        return Map.copyOf(out);
    }

    public static void stop() {
        PEERS.clear();
        lastSentAt = 0;
    }

    private static void acknowledgeCompleted() {
        for (var completed : TranserverBridge.completedSends(256)) {
            if (RoutingChannels.PROTOCOL_HELLO.equals(completed.channel())) {
                TranserverBridge.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private ProtocolHelloService() {
    }
}
