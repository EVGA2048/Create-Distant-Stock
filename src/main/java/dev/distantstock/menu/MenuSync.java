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
import dev.distantstock.routing.RemoteNetworkId;

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
            buf.writeBoolean(entry.networkId() != null);
            if (entry.networkId() != null) {
                buf.writeNbt(entry.networkId().save());
            }
            // Which server it is on, so the list can draw them apart. The client cannot work it out
            // for itself: it does not know its own node id, and both halves write an alias.
            buf.writeBoolean(entry.local());
            // Whether an order for it would be packed by anything. Sent here as well as on the
            // periodic sync, because this is the packet that fills the list the moment the screen
            // opens — without it the first thing a player sees is a network drawn as working.
            buf.writeBoolean(entry.packable());
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
            UUID freq = buf.readUUID();
            String server = buf.readUtf(64);
            int links = buf.readVarInt();
            dev.distantstock.routing.RemoteNetworkId networkId = buf.readBoolean()
                    ? dev.distantstock.routing.RemoteNetworkId.read(buf.readNbt()).orElse(null) : null;
            boolean local = buf.readBoolean();
            boolean packable = buf.readBoolean();
            directory.add(new NetworkDirectory.Entry(freq, server, links, networkId, local, packable));
        }
        menu.networks = directory;
    }

    public static void warm(UUID freq) {
        warm(null, freq);
    }

    /**
     * 设备只记得频率的时候，把网络 id 从目录里补出来。
     *
     * <p>跨服库存那条链路是按**网络 id** 问的（{@code TranserverStockService} 遍历的就是网络 id 那份
     * 监视表），而频率是同一张网络的旧名字。手里只有频率的设备于是没人替它去问，界面上就是永远空的
     * —— 玩家 2026-09-18 报的「重启服务器后看不到远程库存、必须重新加入一次」正是它：重新加入会把
     * 网络 id 写到物品上，才终于有人开口问。目录里两张表都有，够用了。
     */
    public static RemoteNetworkId resolve(RemoteNetworkId networkId, UUID freq) {
        if (freq == null) {
            return networkId;
        }
        RemoteNetworkId known = NetworkDirectory.findByFreq(freq)
                .map(NetworkDirectory.Entry::networkId)
                .orElse(null);
        if (networkId == null) {
            return known;
        }
        // 设备身上那一份可能比目录旧：它记着的是**上一次开机**的世界 id（那次身份没落盘，见
        // WorldIdentity），而目录里这一份每个公告周期都会刷新。同一个节点、同一个频率就是同一张网络，
        // 换成新的是安全的；节点不一样（两边撞了频率）就一律不动 —— 那才是真正需要犹豫的情况。
        return known != null && known.nodeId().equals(networkId.nodeId()) ? known : networkId;
    }

    public static void warm(RemoteNetworkId networkId, UUID freq) {
        if (freq == null) {
            return;
        }
        if (networkId != null) {
            // **只记监视，不碰退避**：这个方法是每 2 秒跑一次的（界面开着就一直跑），在这儿清退避
            // 等于没有退避 —— 对面真没有这张网络时，每次都会变成一封死信，正是 StockCache.refuse
            // 要防的那件事。退避只由"收到过结果"和"玩家重新指向它"来清。
            StockCache.watch(networkId);
        }
        StockCache.watch(freq);
        if (CreateStock.hasNetwork(freq)) {
            List<StockCache.Entry> summary = CreateStock.summary(freq);
            StockCache.put(freq, summary, StockCache.Source.LOCAL);
            StockCache.put(networkId, summary, StockCache.Source.LOCAL);
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
