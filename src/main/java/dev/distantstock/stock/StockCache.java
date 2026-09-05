package dev.distantstock.stock;

import dev.distantstock.config.StockConfig;
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
