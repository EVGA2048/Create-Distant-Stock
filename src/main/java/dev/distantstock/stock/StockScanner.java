package dev.distantstock.stock;

import dev.distantstock.config.StockConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import dev.distantstock.link.TranserverBridge;

/** 主线程定时把 LogisticsManager 扫进 StockCache。 */
public final class StockScanner {
    private static final int MAX_LARGE_STOCK_SCANS_PER_BEAT = 2;
    private static final List<Supplier<Iterable<UUID>>> EXTRA = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void extra(Supplier<Iterable<UUID>> src) {
        EXTRA.add(src);
    }

    /** Removes every registered supplier, for the world that registered them going away. */
    public static void clearExtra() {
        EXTRA.clear();
    }

    public static void scan(MinecraftServer server) {
        UUID localNode = TranserverBridge.localNodeUuid();
        if (localNode == null) {
            return;
        }
        var distantNetworks = dev.distantstock.routing.DistantNetworkDirectory.get(server);
        List<NetworkDirectory.Entry> discovered = CreateStock.openNetworks(
                server, StockConfig.selfId(), localNode);
        for (NetworkDirectory.Entry entry : discovered) {
            if (entry.networkId() != null) {
                distantNetworks.canonicalizeLocalMember(entry.networkId());
            }
        }
        List<NetworkDirectory.Entry> local = discovered.stream()
                .map(entry -> new NetworkDirectory.Entry(entry.freq(), entry.server(), entry.links(),
                        entry.networkId(), entry.local(), entry.packable(),
                        distantNetworks.scopeOf(entry.networkId()),
                        distantNetworks.memberName(entry.networkId()).orElse("")))
                .toList();
        NetworkDirectory.replaceLocal(local);
        var watchedNetworks = new java.util.HashSet<>(StockCache.watchedNetworks(5 * 60_000L));
        var snapshots = new java.util.HashMap<UUID, List<StockCache.Entry>>();
        for (NetworkDirectory.Entry entry : local) {
            // A big warehouse must not be fully enumerated every second merely because a
            // Distant Stock node exists. Only watched inventories are sampled, at most every
            // five seconds; several open terminals consume the same cached snapshot.
            if (entry.networkId() != null && watchedNetworks.contains(entry.networkId())
                    && (StockCache.ageMs(entry.networkId()) < 0
                    || StockCache.ageMs(entry.networkId()) >= 5_000L)
                    && (snapshots.containsKey(entry.freq())
                    || snapshots.size() < MAX_LARGE_STOCK_SCANS_PER_BEAT)) {
                StockCache.put(entry.networkId(), snapshots.computeIfAbsent(entry.freq(),
                        CreateStock::summary), StockCache.Source.LOCAL);
            }
        }
        Set<UUID> freqs = new LinkedHashSet<>(StockCache.watched(5 * 60_000L));
        for (Supplier<Iterable<UUID>> src : EXTRA) {
            Iterable<UUID> it = src.get();
            if (it == null) {
                continue;
            }
            for (UUID id : it) {
                if (id != null) {
                    freqs.add(id);
                }
            }
        }
        for (UUID freq : freqs) {
            StockCache.watch(freq);
            if (CreateStock.hasNetwork(freq) && (StockCache.ageMs(freq) < 0
                    || StockCache.ageMs(freq) >= 5_000L)
                    && (snapshots.containsKey(freq)
                    || snapshots.size() < MAX_LARGE_STOCK_SCANS_PER_BEAT)) {
                StockCache.put(freq, snapshots.computeIfAbsent(freq, CreateStock::summary),
                        StockCache.Source.LOCAL);
            }
        }
    }

    private StockScanner() {
    }
}
