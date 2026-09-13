package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.client.ClientPayloadHandlers;
import dev.distantstock.link.LinkSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Periodic data for an already-open monitor screen. This payload can never open a screen. */
public record LinkSnapshotS2C(BlockPos source, LinkSnapshot.View view) implements CustomPacketPayload {
    public static final Type<LinkSnapshotS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "link_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LinkSnapshotS2C> STREAM_CODEC =
            StreamCodec.of(LinkSnapshotS2C::write, LinkSnapshotS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, LinkSnapshotS2C message) {
        buf.writeBlockPos(message.source);
        writeView(buf, message.view);
    }

    private static LinkSnapshotS2C read(RegistryFriendlyByteBuf buf) {
        return new LinkSnapshotS2C(buf.readBlockPos(), readView(buf));
    }

    static void writeView(RegistryFriendlyByteBuf buf, LinkSnapshot.View view) {
        buf.writeUtf(view.selfId());
        buf.writeUtf(view.peerId());
        buf.writeDouble(view.localTps());
        buf.writeDouble(view.localMspt());
        buf.writeVarInt(view.orderDepth());
        buf.writeVarInt(view.packageDepth());
        buf.writeVarInt(view.inFlight());
        buf.writeBoolean(view.peerUp());
        buf.writeDouble(view.peerTps());
        buf.writeDouble(view.peerMspt());
        buf.writeDouble(view.peerRttMs());
        buf.writeVarInt(view.peerFails());
        buf.writeVarInt(view.peersUp());
        buf.writeVarInt(view.peersTotal());
        buf.writeBoolean(view.transerverAttached());
        buf.writeBoolean(view.transerverUp());
        buf.writeUtf(view.transerverNodeId());
        buf.writeUtf(view.transerverAlias());
        buf.writeUtf(view.transerverFailure());
        buf.writeVarInt(view.transerverOutbox());
        buf.writeVarInt(view.transerverInbox());
        buf.writeVarInt(view.transerverCompleted());
        buf.writeVarInt(view.transerverDeadLetters());
    }

    static LinkSnapshot.View readView(RegistryFriendlyByteBuf buf) {
        return new LinkSnapshot.View(
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
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }

    @Override
    public Type<LinkSnapshotS2C> type() {
        return TYPE;
    }

    public static void handle(LinkSnapshotS2C message, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandlers.updateMonitor(message));
    }
}
