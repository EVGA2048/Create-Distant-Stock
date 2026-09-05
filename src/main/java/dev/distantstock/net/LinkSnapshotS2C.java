package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.client.MonitorScreen;
import dev.distantstock.link.LinkSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LinkSnapshotS2C(LinkSnapshot.View view) implements CustomPacketPayload {
    public static final Type<LinkSnapshotS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "link_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LinkSnapshotS2C> STREAM_CODEC =
            StreamCodec.of(LinkSnapshotS2C::write, LinkSnapshotS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, LinkSnapshotS2C m) {
        LinkSnapshot.View v = m.view;
        buf.writeUtf(v.selfId());
        buf.writeUtf(v.peerId());
        buf.writeDouble(v.localTps());
        buf.writeDouble(v.localMspt());
        buf.writeVarInt(v.orderDepth());
        buf.writeVarInt(v.packageDepth());
        buf.writeVarInt(v.inFlight());
        buf.writeBoolean(v.peerUp());
        buf.writeDouble(v.peerTps());
        buf.writeDouble(v.peerMspt());
        buf.writeDouble(v.peerRttMs());
        buf.writeVarInt(v.peerFails());
        buf.writeVarInt(v.peersUp());
        buf.writeVarInt(v.peersTotal());
    }

    private static LinkSnapshotS2C read(RegistryFriendlyByteBuf buf) {
        return new LinkSnapshotS2C(new LinkSnapshot.View(
                buf.readUtf(),
                buf.readUtf(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()
        ));
    }

    @Override
    public Type<LinkSnapshotS2C> type() {
        return TYPE;
    }

    public static void handle(LinkSnapshotS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> Minecraft.getInstance().setScreen(new MonitorScreen(msg.view())));
    }
}
