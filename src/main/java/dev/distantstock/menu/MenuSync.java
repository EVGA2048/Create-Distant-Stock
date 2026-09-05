package dev.distantstock.menu;

import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkClient;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MenuSync {
    public static void writeItem(FriendlyByteBuf buf, InteractionHand hand, UUID freq) {
        buf.writeBoolean(false);
        buf.writeEnum(hand);
        writeFreq(buf, freq);
        writeCatalog(buf, freq);
    }

    public static void writeGauge(FriendlyByteBuf buf, BlockPos pos, UUID freq) {
        buf.writeBoolean(true);
        buf.writeBlockPos(pos);
        writeFreq(buf, freq);
        writeCatalog(buf, freq);
    }

    public static void writeCatalog(FriendlyByteBuf buf, UUID freq) {
        warm(freq);
        List<StockCache.Entry> list = StockCache.get(freq);
        buf.writeBoolean(StockConfig.DEMO_STOCK.get());
        int n = Math.min(list.size(), 512);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            StockCache.Entry e = list.get(i);
            buf.writeUtf(e.itemId);
            buf.writeVarInt(e.count);
        }
        List<NetworkDirectory.Entry> networks = NetworkDirectory.visible(StockConfig.isHost());
        buf.writeVarInt(Math.min(networks.size(), 128));
        for (int i = 0; i < networks.size() && i < 128; i++) {
            NetworkDirectory.Entry entry = networks.get(i);
            buf.writeUUID(entry.freq());
            buf.writeUtf(entry.server(), 64);
            buf.writeVarInt(entry.links());
        }
    }

    public static void readCatalog(RequesterMenu menu, FriendlyByteBuf buf) {
        menu.demo = buf.readBoolean();
        int n = buf.readVarInt();
        List<StockCache.Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new StockCache.Entry(buf.readUtf(), buf.readVarInt()));
        }
        menu.stock = list;
        int networks = buf.readVarInt();
        List<NetworkDirectory.Entry> directory = new ArrayList<>(networks);
        for (int i = 0; i < networks; i++) {
            directory.add(new NetworkDirectory.Entry(buf.readUUID(), buf.readUtf(64), buf.readVarInt()));
        }
        menu.networks = directory;
    }

    public static void warm(UUID freq) {
        if (freq == null) {
            return;
        }
        StockCache.watch(freq);
        if (CreateStock.hasNetwork(freq)) {
            StockCache.put(freq, CreateStock.summary(freq), StockCache.Source.LOCAL);
        }
        LinkClient.wake();
    }

    private static void writeFreq(FriendlyByteBuf buf, UUID freq) {
        buf.writeBoolean(freq != null);
        if (freq != null) {
            buf.writeUUID(freq);
        }
    }

    private MenuSync() {
    }
}
