package dev.distantstock.menu;

import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkClient;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.item.RequesterData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.distantstock.routing.RemoteNetworkId;

public final class MenuSync {
    public static void writeItem(FriendlyByteBuf buf, InteractionHand hand, ItemStack stack) {
        buf.writeBoolean(false);
        buf.writeEnum(hand);
        UUID freq = RequesterData.freq(stack);
        writeFreq(buf, freq);
        UUID scope = RequesterData.distantNetwork(stack)
                .filter(dev.distantstock.routing.DistantNetworkDirectory::isFormalId)
                .orElse(null);
        writeCatalog(buf, freq, scope);
        writeState(buf,
                RequesterData.network(stack).orElse(null),
                scope,
                RequesterData.address(stack), RequesterData.homeAddress(stack),
                RequesterData.receivingGroup(stack).orElse(null),
                RequesterData.receivingGroupName(stack).orElse(""));
    }

    public static void writeGauge(FriendlyByteBuf buf, BlockPos pos, GaugeBlockEntity gauge) {
        buf.writeBoolean(true);
        buf.writeBlockPos(pos);
        UUID freq = gauge == null ? null : gauge.freq();
        writeFreq(buf, freq);
        UUID scope = gauge != null && gauge.hasDistantNetworkId()
                && dev.distantstock.routing.DistantNetworkDirectory.isFormalId(gauge.distantNetworkId())
                ? gauge.distantNetworkId() : null;
        writeCatalog(buf, freq, scope);
        if (gauge == null) {
            writeState(buf, null, null, "", "", null, "");
            return;
        }
        UUID group = gauge.receivingGroup();
        writeState(buf, gauge.networkId(), gauge.hasDistantNetworkId() ? gauge.distantNetworkId() : null,
                gauge.address(), gauge.homeAddress(), group, groupName(gauge, group));
    }

    /**
     * The requester's durable configuration travels in the menu-opening payload itself.
     *
     * <p>Do not make the client reconstruct this from its ItemStack / block-entity mirror. The held
     * requester is edited while a menu with no inventory slots is open, so its client copy may be
     * stale; a block entity update can likewise arrive after the screen has already initialised.
     * Opening the screen is the one moment the server can hand over one coherent truth.
     */
    private static void writeState(FriendlyByteBuf buf, RemoteNetworkId network, UUID distantNetwork,
                                   String address, String homeAddress, UUID group, String groupName) {
        buf.writeBoolean(network != null);
        if (network != null) buf.writeNbt(network.save());
        buf.writeBoolean(distantNetwork != null);
        if (distantNetwork != null) buf.writeUUID(distantNetwork);
        buf.writeUtf(address == null ? "" : address, 40);
        buf.writeUtf(homeAddress == null ? "" : homeAddress, 40);
        buf.writeBoolean(group != null);
        if (group != null) buf.writeUUID(group);
        buf.writeUtf(groupName == null ? "" : groupName, dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
    }

    public static void readState(RequesterMenu menu, FriendlyByteBuf buf) {
        menu.openedNetworkId = buf.readBoolean()
                ? RemoteNetworkId.read(buf.readNbt()).orElse(null) : null;
        menu.openedDistantNetworkId = buf.readBoolean() ? buf.readUUID() : null;
        menu.openedAddress = buf.readUtf(40);
        menu.openedHomeAddress = buf.readUtf(40);
        menu.openedReceivingGroup = buf.readBoolean() ? buf.readUUID() : null;
        menu.openedReceivingGroupName = buf.readUtf(dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
        menu.hasOpenedState = true;
    }

    private static String groupName(GaugeBlockEntity gauge, UUID group) {
        if (gauge == null || group == null || gauge.getLevel() == null || gauge.getLevel().getServer() == null) {
            return "";
        }
        var server = gauge.getLevel().getServer();
        var local = dev.distantstock.routing.DockGroupDirectory.get(server).find(group).orElse(null);
        if (local != null) return local.name();
        var remote = dev.distantstock.routing.RemoteGroups.get(server).find(group).orElse(null);
        return remote == null ? "" : remote.name();
    }

    public static void writeCatalog(FriendlyByteBuf buf, UUID freq, UUID scope) {
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
        List<NetworkDirectory.Entry> networks = !dev.distantstock.routing.DistantNetworkDirectory.isFormalId(scope)
                ? List.of()
                : NetworkDirectory.visible(StockConfig.isHost()).stream()
                .filter(entry -> scope.equals(entry.distantNetworkId()))
                .toList();
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
            buf.writeUUID(entry.distantNetworkId() == null
                    ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID
                    : entry.distantNetworkId());
            buf.writeUtf(entry.warehouseName(), 64);
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
            UUID distantNetworkId = buf.readUUID();
            String warehouseName = buf.readUtf(64);
            directory.add(new NetworkDirectory.Entry(freq, server, links, networkId, local, packable,
                    distantNetworkId, warehouseName));
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
        NetworkDirectory.Entry knownEntry = NetworkDirectory.findByFreq(freq).orElse(null);
        RemoteNetworkId known = knownEntry == null ? null : knownEntry.networkId();
        if (networkId == null) {
            return known;
        }
        // The live local directory is authoritative for a local Create warehouse.  A portable
        // requester may have been tuned by an older Distant Stock version when singleplayer used a
        // different/null node id.  Requiring the node id to match here traps that item forever on
        // the stale identity: the network page shows the warehouse from the live directory, while
        // CREATE/JOIN looks up the old identity and concludes it is not local.  Frequency is unique
        // inside this local Create runtime, so a local row safely replaces the stale item copy.
        if (knownEntry != null && knownEntry.local() && known != null) {
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
