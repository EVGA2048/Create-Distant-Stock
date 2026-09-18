package dev.distantstock.stock;

import dev.distantstock.config.StockConfig;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP / tick 只读写这里。主线程不准现算 Create。 */
public final class StockCache {
    public static final class Entry {
        public final String itemId;
        public final int count;

        public Entry(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        public ItemStack stack() {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(item);
        }
    }

    public enum Source {
        LOCAL, PEER, DEMO
    }

    private static final Map<UUID, List<Entry>> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WATCHED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WRITTEN = new ConcurrentHashMap<>();
    private static final Set<UUID> LOCAL = ConcurrentHashMap.newKeySet();
    private static final Map<RemoteNetworkId, List<Entry>> NETWORK_CACHE = new ConcurrentHashMap<>();
    private static final Map<RemoteNetworkId, Long> NETWORK_WATCHED = new ConcurrentHashMap<>();
    private static final Map<RemoteNetworkId, Long> NETWORK_WRITTEN = new ConcurrentHashMap<>();
    private static final Set<RemoteNetworkId> LOCAL_NETWORKS = ConcurrentHashMap.newKeySet();

    public static List<Entry> get(UUID freq) {
        if (freq == null) {
            return List.of();
        }
        List<Entry> list = CACHE.get(freq);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        if (StockConfig.DEMO_STOCK.get()) {
            return demo();
        }
        return List.of();
    }

    public static void put(UUID freq, List<Entry> list, Source source) {
        if (freq == null) {
            return;
        }
        if (source == Source.PEER && LOCAL.contains(freq)) {
            Long t = WRITTEN.get(freq);
            if (t != null && System.currentTimeMillis() - t < 15_000L) {
                return;
            }
        }
        CACHE.put(freq, List.copyOf(list));
        WRITTEN.put(freq, System.currentTimeMillis());
        if (source == Source.LOCAL) {
            LOCAL.add(freq);
        } else if (source == Source.PEER) {
            LOCAL.remove(freq);
        }
    }

    public static List<Entry> get(RemoteNetworkId networkId) {
        if (networkId == null) {
            return List.of();
        }
        List<Entry> list = NETWORK_CACHE.get(networkId);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return StockConfig.DEMO_STOCK.get() ? demo() : List.of();
    }

    public static void put(RemoteNetworkId networkId, List<Entry> list, Source source) {
        if (networkId == null) {
            return;
        }
        if (source == Source.PEER && LOCAL_NETWORKS.contains(networkId)) {
            Long written = NETWORK_WRITTEN.get(networkId);
            if (written != null && System.currentTimeMillis() - written < 15_000L) {
                return;
            }
        }
        NETWORK_CACHE.put(networkId, List.copyOf(list));
        NETWORK_WRITTEN.put(networkId, System.currentTimeMillis());
        if (source == Source.LOCAL) {
            LOCAL_NETWORKS.add(networkId);
        } else if (source == Source.PEER) {
            LOCAL_NETWORKS.remove(networkId);
        }
    }

    public static void watch(RemoteNetworkId networkId) {
        if (networkId != null) {
            NETWORK_WATCHED.put(networkId, System.currentTimeMillis());
        }
    }

    /**
     * Stops watching a network. Kept for a caller that really means "never again" — the ordinary
     * answer to a refusal is {@link #refuse}, which comes back on its own.
     */
    public static void unwatch(RemoteNetworkId networkId) {
        if (networkId != null) {
            NETWORK_WATCHED.remove(networkId);
        }
    }

    /** 一次"对面说不认识这张网络"，以及到现在为止连着被拒了几次。 */
    private record Refusal(long at, int count) {
    }

    private static final Map<RemoteNetworkId, Refusal> REFUSED = new ConcurrentHashMap<>();

    /** 退避的上限：连着被拒很多次之后，五分钟问一次就够。 */
    private static final long REFUSAL_CAP_MS = 5 * 60_000L;

    /**
     * 对面说"不认识这张网络"。
     *
     * <p><b>以前这里是永久停止</b>（{@code unwatch}）：拒一次就再也不问了，设计上写着"等有人重新把
     * 仪表指过来"。可对面**只是还没起来**的时候也会说不认识 —— 服务器刚重启、世界还没加载、请求台
     * 所在的区块还没打开 —— 于是这边永久静音，界面上永远是空的。玩家 2026-09-18 报的
     * 「重启服务器后已绑定网络的远仓终端看不到远程库存，必须拆掉请求台或重新加入才行」就是它：
     * 那两个动作都会重新 watch 一次，于是又有人去问了。
     *
     * <p>现在改成退避：连着被拒就越来越久才问一次（10 秒起、封顶五分钟）。收到过结果、或者玩家
     * 重新指向它（{@link #clearRefusal}）就作废。真正死掉的网络仍然是"几乎不问"，但不再是"永远不问"。
     */
    public static void refuse(RemoteNetworkId networkId) {
        if (networkId != null) {
            REFUSED.compute(networkId, (id, old) -> new Refusal(System.currentTimeMillis(),
                    old == null ? 1 : old.count() + 1));
        }
    }

    /** 被拒之后还要等多久才再问；没被拒过就是 0。 */
    public static long refusalWaitMs(RemoteNetworkId networkId) {
        Refusal refusal = networkId == null ? null : REFUSED.get(networkId);
        if (refusal == null) {
            return 0L;
        }
        long wait = Math.min(REFUSAL_CAP_MS, 10_000L << Math.min(refusal.count() - 1, 6));
        return Math.max(0L, wait - (System.currentTimeMillis() - refusal.at()));
    }

    /** 这张网络刚收到过东西，或者有人重新指向了它：把退避作废，下一次就再问。 */
    public static void clearRefusal(RemoteNetworkId networkId) {
        if (networkId != null) {
            REFUSED.remove(networkId);
        }
    }

    public static List<RemoteNetworkId> watchedNetworks(long maxAgeMs) {
        long now = System.currentTimeMillis();
        List<RemoteNetworkId> out = new ArrayList<>();
        NETWORK_WATCHED.forEach((id, time) -> {
            if (now - time <= maxAgeMs) {
                out.add(id);
            }
        });
        return out;
    }

    public static long ageMs(RemoteNetworkId networkId) {
        Long time = networkId == null ? null : NETWORK_WRITTEN.get(networkId);
        return time == null ? -1 : Math.max(0, System.currentTimeMillis() - time);
    }

    public static int size(RemoteNetworkId networkId) {
        List<Entry> list = networkId == null ? null : NETWORK_CACHE.get(networkId);
        return list == null ? 0 : list.size();
    }

    public static void watch(UUID freq) {
        if (freq != null) {
            WATCHED.put(freq, System.currentTimeMillis());
        }
    }

    public static List<UUID> watched(long maxAgeMs) {
        long now = System.currentTimeMillis();
        List<UUID> out = new ArrayList<>();
        WATCHED.forEach((id, t) -> {
            if (now - t <= maxAgeMs) {
                out.add(id);
            }
        });
        return out;
    }

    public static boolean isLocal(UUID freq) {
        return freq != null && LOCAL.contains(freq);
    }

    public static long ageMs(UUID freq) {
        if (freq == null) {
            return -1;
        }
        Long t = WRITTEN.get(freq);
        return t == null ? -1 : Math.max(0, System.currentTimeMillis() - t);
    }

    public static int size(UUID freq) {
        List<Entry> list = freq == null ? null : CACHE.get(freq);
        return list == null ? 0 : list.size();
    }

    private static List<Entry> demo() {
        return List.of(
                new Entry("minecraft:iron_ingot", 512),
                new Entry("minecraft:copper_ingot", 1024),
                new Entry("minecraft:glass", 2048),
                new Entry("minecraft:oak_planks", 4096),
                new Entry("minecraft:redstone", 256),
                new Entry("minecraft:andesite", 8192),
                new Entry("minecraft:bricks", 333),
                new Entry("minecraft:white_concrete", 777)
        );
    }

    private StockCache() {
    }
}
