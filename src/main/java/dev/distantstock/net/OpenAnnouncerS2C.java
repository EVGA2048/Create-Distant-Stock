package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.AnnouncerBlockEntity;
import dev.distantstock.block.BroadcastSource;
import dev.distantstock.block.NetworkBroadcasterBlockEntity;
import dev.distantstock.client.ClientPayloadHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server-authored snapshot for the local PA terminal editor. */
public record OpenAnnouncerS2C(BlockPos source, String template, String prefix, int radius, int soundProfile, boolean networked, List<String> parameters)
        implements CustomPacketPayload {
    public static final Type<OpenAnnouncerS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "open_announcer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAnnouncerS2C> STREAM_CODEC =
            StreamCodec.of(OpenAnnouncerS2C::write, OpenAnnouncerS2C::read);

    public OpenAnnouncerS2C {
        template = template == null ? "{1}" : template;
        prefix = prefix == null ? AnnouncerBlockEntity.DEFAULT_PREFIX : prefix;
        radius = Math.clamp(radius, AnnouncerBlockEntity.MIN_RADIUS, AnnouncerBlockEntity.MAX_RADIUS);
        soundProfile = Math.clamp(soundProfile, 0, 2);
        parameters = parameters == null ? List.of("", "", "", "") : List.copyOf(parameters);
    }

    public static OpenAnnouncerS2C from(BroadcastSource be) {
        return new OpenAnnouncerS2C(be.getBlockPos(), be.template(), be.prefix(), be.radius(), be.soundProfile(),
                be instanceof NetworkBroadcasterBlockEntity, be.parameters());
    }

    private static void write(RegistryFriendlyByteBuf buf, OpenAnnouncerS2C message) {
        buf.writeBlockPos(message.source());
        buf.writeUtf(message.template(), AnnouncerBlockEntity.MAX_TEMPLATE);
        buf.writeUtf(message.prefix(), AnnouncerBlockEntity.MAX_PREFIX);
        buf.writeVarInt(message.radius());
        buf.writeVarInt(message.soundProfile());
        buf.writeBoolean(message.networked());
        for (int i = 0; i < AnnouncerBlockEntity.PARAMETER_COUNT; i++) {
            String value = i < message.parameters().size() ? message.parameters().get(i) : "";
            buf.writeUtf(value, AnnouncerBlockEntity.MAX_PARAMETER);
        }
    }

    private static OpenAnnouncerS2C read(RegistryFriendlyByteBuf buf) {
        BlockPos source = buf.readBlockPos();
        String template = buf.readUtf(AnnouncerBlockEntity.MAX_TEMPLATE);
        String prefix = buf.readUtf(AnnouncerBlockEntity.MAX_PREFIX);
        int radius = buf.readVarInt();
        int soundProfile = buf.readVarInt();
        boolean networked = buf.readBoolean();
        ArrayList<String> params = new ArrayList<>(AnnouncerBlockEntity.PARAMETER_COUNT);
        for (int i = 0; i < AnnouncerBlockEntity.PARAMETER_COUNT; i++) {
            params.add(buf.readUtf(AnnouncerBlockEntity.MAX_PARAMETER));
        }
        return new OpenAnnouncerS2C(source, template, prefix, radius, soundProfile, networked, params);
    }

    @Override
    public Type<OpenAnnouncerS2C> type() {
        return TYPE;
    }

    public static void handle(OpenAnnouncerS2C message, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandlers.openAnnouncer(message));
    }
}
