package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.config.StockConfig;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import dev.distantstock.routing.RemoteNetworkId;

public record StockSyncS2C(boolean demo, List<Line> items, List<NetworkLine> networks) implements CustomPacketPayload {
    public record Line(String itemId, int count) {
    }

    /**
     * One network as the terminal lists it, with which server it is on.
     *
     * @param packable whether the machine behind it can actually pack an order — see
     *                 {@link NetworkDirectory.Entry#packable}. The terminal greys a network that
     *                 cannot, rather than letting a player place an order that will never be filled.
     */
    public record NetworkLine(java.util.UUID freq, String server, int links, RemoteNetworkId networkId,
                              boolean local, boolean packable) {
    }

    public static final Type<StockSyncS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "stock_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Line> LINE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Line::itemId,
            ByteBufCodecs.VAR_INT, Line::count,
            Line::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkLine> NETWORK_CODEC =
            StreamCodec.of(StockSyncS2C::writeNetwork, StockSyncS2C::readNetwork);
    public static final StreamCodec<RegistryFriendlyByteBuf, StockSyncS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StockSyncS2C::demo,
            ByteBufCodecs.collection(ArrayList::new, LINE_CODEC), StockSyncS2C::items,
            ByteBufCodecs.collection(ArrayList::new, NETWORK_CODEC), StockSyncS2C::networks,
            StockSyncS2C::new);

    @Override
    public Type<StockSyncS2C> type() {
        return TYPE;
    }

    public static StockSyncS2C of(boolean demo, List<StockCache.Entry> stock) {
        List<Line> lines = new ArrayList<>();
        int n = Math.min(stock.size(), 512);
        for (int i = 0; i < n; i++) {
            StockCache.Entry e = stock.get(i);
            lines.add(new Line(e.itemId, e.count));
        }
        List<NetworkLine> networks = new ArrayList<>();
        for (NetworkDirectory.Entry entry : NetworkDirectory.visible(StockConfig.isHost())) {
            networks.add(new NetworkLine(entry.freq(), entry.server(), entry.links(), entry.networkId(),
                    entry.local(), entry.packable()));
        }
        return new StockSyncS2C(demo, lines, networks);
    }

    public static void handle(StockSyncS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player().containerMenu instanceof RequesterMenu menu)) {
                return;
            }
            menu.demo = msg.demo;
            List<StockCache.Entry> list = new ArrayList<>();
            for (Line line : msg.items) {
                list.add(new StockCache.Entry(line.itemId, line.count));
            }
            menu.stock = list;
            List<NetworkDirectory.Entry> networks = new ArrayList<>();
            for (NetworkLine line : msg.networks) {
                networks.add(new NetworkDirectory.Entry(line.freq, line.server, line.links, line.networkId,
                        line.local, line.packable));
            }
            menu.networks = networks;
        });
    }

    private static void writeNetwork(RegistryFriendlyByteBuf buf, NetworkLine line) {
        buf.writeUUID(line.freq());
        buf.writeUtf(line.server(), 64);
        buf.writeVarInt(line.links());
        buf.writeBoolean(line.networkId() != null);
        if (line.networkId() != null) {
            buf.writeNbt(line.networkId().save());
        }
        buf.writeBoolean(line.local());
        buf.writeBoolean(line.packable());
    }

    private static NetworkLine readNetwork(RegistryFriendlyByteBuf buf) {
        java.util.UUID freq = buf.readUUID();
        String server = buf.readUtf(64);
        int links = buf.readVarInt();
        RemoteNetworkId networkId = buf.readBoolean()
                ? RemoteNetworkId.read(buf.readNbt()).orElse(null) : null;
        boolean local = buf.readBoolean();
        boolean packable = buf.readBoolean();
        return new NetworkLine(freq, server, links, networkId, local, packable);
    }
}
