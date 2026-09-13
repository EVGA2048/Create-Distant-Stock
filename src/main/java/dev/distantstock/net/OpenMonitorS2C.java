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

/** One-shot response to a player's explicit monitor interaction. */
public record OpenMonitorS2C(BlockPos source, LinkSnapshot.View view) implements CustomPacketPayload {
    public static final Type<OpenMonitorS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "open_monitor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMonitorS2C> STREAM_CODEC =
            StreamCodec.of(OpenMonitorS2C::write, OpenMonitorS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, OpenMonitorS2C message) {
        buf.writeBlockPos(message.source);
        LinkSnapshotS2C.writeView(buf, message.view);
    }

    private static OpenMonitorS2C read(RegistryFriendlyByteBuf buf) {
        return new OpenMonitorS2C(buf.readBlockPos(), LinkSnapshotS2C.readView(buf));
    }

    @Override
    public Type<OpenMonitorS2C> type() {
        return TYPE;
    }

    public static void handle(OpenMonitorS2C message, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandlers.openMonitor(message));
    }
}
