package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.OrderService;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PlaceOrderC2S(List<Line> lines) implements CustomPacketPayload {
    public record Line(String itemId, int count) {
    }

    public static final Type<PlaceOrderC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "place_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Line> LINE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Line::itemId,
            ByteBufCodecs.VAR_INT, Line::count,
            Line::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceOrderC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, LINE_CODEC), PlaceOrderC2S::lines,
            PlaceOrderC2S::new);

    @Override
    public Type<PlaceOrderC2S> type() {
        return TYPE;
    }

    public static void handle(PlaceOrderC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p.containerMenu instanceof RequesterMenu menu)) {
                return;
            }
            if (!menu.tuned(p)) {
                p.displayClientMessage(Component.translatable("gui.distantstock.untuned"), true);
                return;
            }
            if (msg.lines == null || msg.lines.isEmpty()) {
                p.displayClientMessage(Component.translatable("gui.distantstock.need_item"), true);
                return;
            }
            UUID freq = menu.freq(p);
            String address = menu.address(p);
            List<LinkQueues.Line> items = new ArrayList<>();
            for (Line line : msg.lines) {
                if (line.count > 0 && line.itemId != null && !line.itemId.isBlank()) {
                    items.add(new LinkQueues.Line(line.itemId, line.count));
                }
            }
            OrderService.Result result = OrderService.place(freq, address, items);
            GaugeBlockEntity be = menu.gauge(p);
            if (be != null) {
                be.lastOrder(result);
            }
            p.displayClientMessage(Component.translatable(switch (result) {
                case QUEUED -> "gui.distantstock.queued";
                case EMPTY -> "gui.distantstock.need_item";
                case NO_PEER -> "gui.distantstock.no_peer";
                case FAIL -> "gui.distantstock.order_fail";
            }), true);
        });
    }
}
