package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.client.ClientPayloadHandlers;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerReadout;
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
        buf.writeBoolean(view.peerFresh());
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
        writeTower(buf, view.tower());
    }

    /**
     * The tower readout, written as one block at the very end of the view.
     *
     * <p>Kept in its own pair of methods so the values it holds are read in the same order they were
     * written without the reader having to scan a hundred lines of the link half to check. Every
     * field is written unconditionally, attached or not: a branch would be one more thing the two
     * sides could disagree about, and the unattached case is a handful of bytes.
     *
     * <p>Both halves of a member are positional, and the per-tower block comes last in each: a
     * field added in the middle of it shifts every value behind it, and the failure is a screen
     * full of plausible numbers rather than an error.
     */
    /**
     * The most members a system may report.
     *
     * <p>A count is the one thing a malformed packet can turn into an allocation, so the reader
     * refuses anything past this before it reserves a list. Sixty-four is far more towers than a
     * system can usefully merge and far less than a packet could ask for.
     */
    static final int MAX_TOWER_MEMBERS = 64;

    private static void writeTower(RegistryFriendlyByteBuf buf, TowerReadout tower) {
        buf.writeBoolean(tower.attached());
        buf.writeLong(tower.carrierPos());
        buf.writeUtf(tower.dimension());
        buf.writeVarInt(tower.carried());
        buf.writeVarInt(tower.limit());
        buf.writeFloat(tower.stress());
        buf.writeFloat(tower.speed());
        buf.writeVarInt(tower.maxSide());
        buf.writeVarInt(tower.selectedSide());
        buf.writeVarInt(tower.members().size());
        for (TowerReadout.Member member : tower.members()) {
            buf.writeLong(member.pos());
            buf.writeUtf(member.tier());
            buf.writeVarInt(member.radius());
            buf.writeVarInt(member.devices());
            buf.writeBoolean(member.running());
            buf.writeFloat(member.speed());
            buf.writeVarInt(member.ether());
            buf.writeFloat(member.capacity());
            buf.writeBoolean(member.overstressed());
            buf.writeVarInt(member.chunkRadius());
            buf.writeBoolean(member.loading());
            buf.writeBoolean(member.carrying());
            buf.writeVarInt(member.sent());
            buf.writeVarInt(member.received());
        }
    }

    private static TowerReadout readTower(RegistryFriendlyByteBuf buf) {
        boolean attached = buf.readBoolean();
        long carrierPos = buf.readLong();
        String dimension = buf.readUtf();
        int carried = buf.readVarInt();
        int limit = buf.readVarInt();
        float stress = buf.readFloat();
        float speed = buf.readFloat();
        int maxSide = buf.readVarInt();
        int selectedSide = buf.readVarInt();
        int memberCount = buf.readVarInt();
        if (memberCount < 0 || memberCount > MAX_TOWER_MEMBERS) {
            // A count is the one thing a malformed packet can turn into an allocation, and the
            // screen has room for a handful of rows anyway.
            throw new io.netty.handler.codec.DecoderException("tower member count " + memberCount);
        }
        java.util.List<TowerReadout.Member> members = new java.util.ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            // The same order writeTower uses, field for field.
            members.add(new TowerReadout.Member(buf.readLong(), buf.readUtf(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean(), buf.readFloat(),
                    buf.readVarInt(), buf.readFloat(), buf.readBoolean(),
                    buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarInt()));
        }
        return new TowerReadout(attached, carrierPos, dimension, carried, limit, stress, speed,
                maxSide, selectedSide, members);
    }

    static LinkSnapshot.View readView(RegistryFriendlyByteBuf buf) {
        return new LinkSnapshot.View(
                // Kept in the order of the record's components, which is the order writeView uses.
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
                buf.readBoolean(),
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
                buf.readVarInt(),
                readTower(buf)
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
