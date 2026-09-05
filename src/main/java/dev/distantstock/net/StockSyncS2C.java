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

public record StockSyncS2C(boolean demo, List<Line> items, List<NetworkLine> networks) implements CustomPacketPayload {
    public record Line(String itemId, int count) {
    }

    public record NetworkLine(java.util.UUID freq, String server, int links) {
    }

    public static final Type<StockSyncS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "stock_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Line> LINE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Line::itemId,
            ByteBufCodecs.VAR_INT, Line::count,
            Line::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkLine> NETWORK_CODEC = StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, NetworkLine::freq,
            ByteBufCodecs.STRING_UTF8, NetworkLine::server,
            ByteBufCodecs.VAR_INT, NetworkLine::links,
            NetworkLine::new);
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
            networks.add(new NetworkLine(entry.freq(), entry.server(), entry.links()));
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
                networks.add(new NetworkDirectory.Entry(line.freq, line.server, line.links));
            }
            menu.networks = networks;
        });
    }
}
