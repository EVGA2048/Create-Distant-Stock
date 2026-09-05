package dev.distantstock.stock;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 主线程才能碰 Create 物流。 */
public final class CreateStock {
    public static boolean hasNetwork(UUID freq) {
        if (freq == null || Create.LOGISTICS == null || Create.LOGISTICS.logisticsNetworks == null) {
            return false;
        }
        return Create.LOGISTICS.logisticsNetworks.containsKey(freq);
    }

    public static List<NetworkDirectory.Entry> openNetworks(String serverId) {
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
            out.add(new NetworkDirectory.Entry(row.getKey(), serverId, network.loadedLinks.size()));
        }
        return out;
    }

    public static List<StockCache.Entry> summary(UUID freq) {
        if (!hasNetwork(freq)) {
            return List.of();
        }
        InventorySummary sum = LogisticsManager.getSummaryOfNetwork(freq, true);
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
        return LogisticsManager.broadcastPackageRequest(
                freq,
                LogisticallyLinkedBehaviour.RequestType.PLAYER,
                PackageOrderWithCrafts.simple(stacks),
                null,
                dest);
    }

    private CreateStock() {
    }
}
