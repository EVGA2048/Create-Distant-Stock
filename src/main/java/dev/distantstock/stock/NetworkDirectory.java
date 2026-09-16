package dev.distantstock.stock;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import dev.distantstock.routing.RemoteNetworkId;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** 主线程发布本地网络，IO 线程替换远端快照，GUI/HTTP 只读。 */
public final class NetworkDirectory {
    public record Entry(UUID freq, String server, int links, RemoteNetworkId networkId,
                        boolean local) {
        public Entry(UUID freq, String server, int links) {
            this(freq, server, links, null, true);
        }

        /**
         * Whether this network is on another server.
         *
         * <p>Asked by the terminal's list, which draws the two kinds differently: a player has to be
         * able to tell "my warehouse" from "the one across the link" before pointing a machine at it,
         * and an alias alone does not say which is which.
         */
        public boolean remote() {
            return !local;
        }
    }

    private static volatile List<Entry> local = List.of();
    private record PeerSnapshot(List<Entry> entries, long updatedAt) {
    }
    private static final Map<String, PeerSnapshot> PEERS = new ConcurrentHashMap<>();

    public static void replaceLocal(Collection<Entry> entries) {
        local = List.copyOf(entries);
    }

    public static void replacePeer(Collection<Entry> entries) {
        replacePeer("legacy", entries);
    }

    public static void replacePeer(String sourceNode, Collection<Entry> entries) {
        PEERS.put(sourceNode, new PeerSnapshot(List.copyOf(entries), System.currentTimeMillis()));
    }

    public static List<Entry> local() {
        return local;
    }

    public static List<Entry> peer() {
        return aggregatePeers();
    }

    public static List<Entry> visible(boolean host) {
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : local) {
            result.put(key(entry), entry);
        }
        for (Entry entry : aggregatePeers()) {
            result.putIfAbsent(key(entry), entry);
        }
        return List.copyOf(result.values());
    }

    public static boolean contains(UUID freq, boolean host) {
        if (freq == null) {
            return false;
        }
        for (Entry entry : visible(host)) {
            if (freq.equals(entry.freq())) {
                return true;
            }
        }
        return false;
    }

    public static Optional<Entry> find(UUID freq, boolean host) {
        if (freq == null) {
            return Optional.empty();
        }
        return visible(host).stream().filter(entry -> freq.equals(entry.freq())).findFirst();
    }

    public static Optional<Entry> find(RemoteNetworkId networkId) {
        if (networkId == null) {
            return Optional.empty();
        }
        return visible(false).stream().filter(entry -> networkId.equals(entry.networkId())).findFirst();
    }

    private static List<Entry> aggregatePeers() {
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        List<Entry> result = new java.util.ArrayList<>();
        PEERS.entrySet().removeIf(entry -> entry.getValue().updatedAt() < cutoff);
        PEERS.values().forEach(snapshot -> result.addAll(snapshot.entries()));
        return List.copyOf(result);
    }

    private static String key(Entry entry) {
        return entry.networkId() == null
                ? entry.server() + ":" + entry.freq()
                : entry.networkId().nodeId() + ":" + entry.networkId().worldId() + ":"
                + entry.networkId().dimensionId() + ":" + entry.networkId().createFrequency();
    }

    private NetworkDirectory() {
    }
}
