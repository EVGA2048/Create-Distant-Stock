package dev.distantstock.stock;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.WorldIdentity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 主线程才能碰 Create 物流。 */
public final class CreateStock {
    public static boolean hasNetwork(UUID freq) {
        if (freq == null || Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) {
            return false;
        }
        return Create.LOGISTICS.logisticsNetworks.containsKey(freq);
    }

    public static List<NetworkDirectory.Entry> openNetworks(MinecraftServer server, String serverId, UUID nodeId) {
        if (Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) {
            return List.of();
        }
        List<NetworkDirectory.Entry> out = new ArrayList<>();
        for (Map.Entry<UUID, com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork> row
                : Create.LOGISTICS.logisticsNetworks.entrySet()) {
            var network = row.getValue();
            if (network == null || network.locked || network.loadedLinks == null || network.loadedLinks.isEmpty()) {
                continue;
            }
            RemoteNetworkId networkId = null;
            if (server != null && nodeId != null) {
                GlobalPos link = network.loadedLinks.iterator().next();
                ServerLevel level = server.getLevel(link.dimension());
                if (level != null) {
                    networkId = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, nodeId,
                            WorldIdentity.get(level), link.dimension().location().toString(), row.getKey());
                }
            }
            // Whether an order for this network would actually be packed by anything. Asked here
            // rather than where the list is drawn, because the answer is a fact about this server's
            // loaded machines and only this side can look.
            boolean packable = dev.distantstock.block.RemotePackagerBlockEntity.canPack(row.getKey());
            out.add(new NetworkDirectory.Entry(row.getKey(), serverId, network.loadedLinks.size(), networkId,
                    true, packable));
        }
        return out;
    }

    /**
     * Cheap snapshot of a network's health for the signal lamps. Only touches set sizes and the
     * promise queue's emptiness, so it is safe to call on a timer.
     *
     * @param maxMissing how many offline link positions to carry along for the goggle readout
     */
    public static NetworkHealth health(UUID freq, int maxMissing) {
        if (!hasNetwork(freq)) {
            return NetworkHealth.UNKNOWN;
        }
        var network = Create.LOGISTICS.logisticsNetworks.get(freq);
        if (network == null) {
            return NetworkHealth.UNKNOWN;
        }
        Set<GlobalPos> loaded = network.loadedLinks == null ? Set.of() : network.loadedLinks;
        Set<GlobalPos> total = network.totalLinks == null ? Set.of() : network.totalLinks;
        List<BlockPos> missing = new ArrayList<>();
        for (GlobalPos link : total) {
            if (missing.size() >= maxMissing) {
                break;
            }
            if (!loaded.contains(link)) {
                missing.add(link.pos());
            }
        }
        // totalLinks is a hash set, so sort to keep two identical samples equal.
        missing.sort(java.util.Comparator.comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ()));
        boolean idle = network.panelPromises == null || network.panelPromises.isEmpty();
        return new NetworkHealth(true, loaded.size(), total.size(), idle, network.locked, List.copyOf(missing));
    }

    /**
     * How much of one item a network holds, and how much of it is already promised to arrive.
     * Both numbers come straight from Create, so a monitor shows the same figures a stock ticker
     * would without keeping its own copy of anything.
     *
     * @return {@code {stock, promised}}
     */
    public static int[] itemStock(UUID freq, ItemStack item) {
        if (!hasNetwork(freq) || item.isEmpty()) {
            return new int[]{0, 0};
        }
        var network = Create.LOGISTICS.logisticsNetworks.get(freq);
        if (network == null) {
            return new int[]{0, 0};
        }
        InventorySummary sum = LogisticsManager.getSummaryOfNetwork(freq, true);
        int stock = sum == null ? 0 : sum.getCountOf(item);
        int promised = 0;
        if (network.panelPromises != null) {
            for (var promise : network.panelPromises.flatten(false)) {
                var ordered = promise.promisedStack;
                if (ordered != null && !ordered.stack.isEmpty()
                        && ItemStack.isSameItemSameComponents(ordered.stack, item)) {
                    promised += ordered.count;
                }
            }
        }
        return new int[]{stock, promised};
    }

    public static int deviceCount(UUID freq) {
        if (freq == null || Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) {
            return 0;
        }
        var network = Create.LOGISTICS.logisticsNetworks.get(freq);
        if (network == null) {
            return 0;
        }
        return network.loadedLinks == null ? 0 : network.loadedLinks.size();
    }

    /**
     * 一张网络的库存，按物品聚合。
     *
     * <p><b>用的是 Create 的近似汇总，不是它那份精确的。</b>两个缓存的差别在存活时间上：
     * {@code ACCURATE_SUMMARIES} 是 {@code TickBasedCache(1)} —— 活一个 tick，"取一次"就等于
     * "把整个网络重新数一遍"；{@code SUMMARIES} 是 {@code TickBasedCache(20)}，而且和 Create 自己的
     * 仓管界面共用同一份。我们原来用的是前者，于是每秒钟对每一张网络全量重数一次 —— 仓库越大越贵，
     * 而且跟有没有人在看没有任何关系。
     *
     * <p>代价是数字最多旧一秒。对这个用途（界面上的列表、发给对端的摘要）无所谓：真正要紧的那次
     * 核对发生在对面打包的时候，Create 自己会在那里用精确的那份再确认一遍。
     */
    public static List<StockCache.Entry> summary(UUID freq) {
        if (!hasNetwork(freq)) {
            return List.of();
        }
        InventorySummary sum = LogisticsManager.getSummaryOfNetwork(freq, false);
        if (sum == null || sum.isEmpty()) {
            return List.of();
        }
        List<StockCache.Entry> out = new ArrayList<>();
        for (BigItemStack row : sum.getStacksByCount()) {
            if (row.stack == null || row.stack.isEmpty() || row.count <= 0) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(row.stack.getItem());
            out.add(new StockCache.Entry(id.toString(), row.count));
        }
        return out;
    }

    public static boolean request(UUID freq, List<StockCache.Entry> items, String address) {
        return request(freq, items, address, null, null);
    }

    public static boolean request(UUID freq, List<StockCache.Entry> items, String address,
                                  MinecraftServer server, RemoteRoute route) {
        return request(freq, items, address, server, route, "");
    }

    /**
     * The same, for an order whose parcels must wear a different address once they are home.
     *
     * <p>{@code homeAddress} is written onto every parcel of this order and applied on the far side
     * of the crossing — see {@link dev.distantstock.routing.RemoteRouteData#applyHomeAddress}. Blank
     * is the ordinary case: goods that stay on this server never need a second address.
     */
    public static boolean request(UUID freq, List<StockCache.Entry> items, String address,
                                  MinecraftServer server, RemoteRoute route, String homeAddress) {
        if (!hasNetwork(freq) || items == null || items.isEmpty()) {
            return false;
        }
        List<BigItemStack> stacks = new ArrayList<>();
        for (StockCache.Entry e : items) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.itemId));
            if (item == null || item == Items.AIR || e.count <= 0) {
                continue;
            }
            stacks.add(new BigItemStack(new ItemStack(item), e.count));
        }
        if (stacks.isEmpty()) {
            return false;
        }
        String dest = address == null ? "" : address;
        PackageOrderWithCrafts order = PackageOrderWithCrafts.simple(stacks);
        if (route == null) {
            return LogisticsManager.broadcastPackageRequest(
                    freq, LogisticallyLinkedBehaviour.RequestType.PLAYER, order, null, dest);
        }
        if (server == null) {
            throw new IllegalArgumentException("A server is required for routed package requests");
        }
        var requests = LogisticsManager.findPackagersForRequest(freq, order, null, dest);
        if (requests.isEmpty()) {
            return false;
        }
        for (PackagerBlockEntity packager : requests.keySet()) {
            if (packager.isTooBusyFor(LogisticallyLinkedBehaviour.RequestType.PLAYER)) {
                return false;
            }
        }
        try {
            if (!OrderRouteDirectory.get(server).remember(requests.values(), route, homeAddress)) {
                // 记不下路线就不发这个订单。以前这里是一句"满了就抛"，抛出来的东西被下面
                // catch 住、变成同一个 false —— 结果一样，但异常当控制流，而且满了之后**每**
                // 一单都这样，没有任何提示。现在满了会先清掉过期的（见 OrderRouteDirectory.expire），
                // 真的还满就明说。
                org.apache.logging.log4j.LogManager.getLogger().warn(
                        "[DistantStock/Order] refused before packaging: {} unfinished orders, no room",
                        OrderRouteDirectory.get(server).size());
                return false;
            }
        } catch (IllegalStateException exception) {
            org.apache.logging.log4j.LogManager.getLogger().warn(
                    "[DistantStock/Order] refused before packaging: {}", exception.getMessage());
            return false;
        }
        LogisticsManager.performPackageRequests(requests);
        return true;
    }

    private CreateStock() {
    }
}
