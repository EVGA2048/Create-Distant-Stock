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
    private static final List<Supplier<Iterable<UUID>>> EXTRA = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void extra(Supplier<Iterable<UUID>> src) {
        EXTRA.add(src);
    }

    /** Removes every registered supplier, for the world that registered them going away. */
    public static void clearExtra() {
        EXTRA.clear();
    }

    public static void scan(MinecraftServer server) {
        List<NetworkDirectory.Entry> local = CreateStock.openNetworks(
                server, StockConfig.selfId(), TranserverBridge.nodeId());
        NetworkDirectory.replaceLocal(local);
        for (NetworkDirectory.Entry entry : local) {
            if (entry.networkId() != null) {
                StockCache.put(entry.networkId(), CreateStock.summary(entry.freq()), StockCache.Source.LOCAL);
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
            if (CreateStock.hasNetwork(freq)) {
                StockCache.put(freq, CreateStock.summary(freq), StockCache.Source.LOCAL);
            }
        }
    }

    private StockScanner() {
    }
}
