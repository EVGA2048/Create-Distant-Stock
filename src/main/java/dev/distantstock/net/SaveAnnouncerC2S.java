package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.AnnouncerBlockEntity;
import dev.distantstock.block.BroadcastSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Saves the operator-editable template/radius. Display Link parameters stay server-owned. */
public record SaveAnnouncerC2S(BlockPos source, String template, String prefix, int radius, int soundProfile) implements CustomPacketPayload {
    public static final Type<SaveAnnouncerC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "save_announcer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SaveAnnouncerC2S> STREAM_CODEC =
            StreamCodec.of(SaveAnnouncerC2S::write, SaveAnnouncerC2S::read);

    private static void write(RegistryFriendlyByteBuf buf, SaveAnnouncerC2S message) {
        buf.writeBlockPos(message.source());
        buf.writeUtf(message.template() == null ? "" : message.template(), AnnouncerBlockEntity.MAX_TEMPLATE);
        buf.writeUtf(message.prefix() == null ? "" : message.prefix(), AnnouncerBlockEntity.MAX_PREFIX);
        buf.writeVarInt(message.radius());
        buf.writeVarInt(message.soundProfile());
    }

    private static SaveAnnouncerC2S read(RegistryFriendlyByteBuf buf) {
        return new SaveAnnouncerC2S(buf.readBlockPos(),
                buf.readUtf(AnnouncerBlockEntity.MAX_TEMPLATE),
                buf.readUtf(AnnouncerBlockEntity.MAX_PREFIX), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<SaveAnnouncerC2S> type() {
        return TYPE;
    }

    public static void handle(SaveAnnouncerC2S message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.level().getBlockEntity(message.source()) instanceof BroadcastSource be)) return;
            if (player.distanceToSqr(message.source().getX() + .5,
                    message.source().getY() + .5, message.source().getZ() + .5) > 64) return;
            be.configure(message.template(), message.radius(), message.soundProfile(), message.prefix());
        });
    }
}
