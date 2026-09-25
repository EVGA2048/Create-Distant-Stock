package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherCasingCloakState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Server-authoritative Resonant Quartz cloak animation phase for local and remote players. */
public record CloakStateS2C(UUID playerId, boolean cloaking) implements CustomPacketPayload {
    public static final Type<CloakStateS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "cloak_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CloakStateS2C> STREAM_CODEC =
            StreamCodec.of(CloakStateS2C::write, CloakStateS2C::read);

    private static void write(RegistryFriendlyByteBuf buf, CloakStateS2C message) {
        buf.writeUUID(message.playerId);
        buf.writeBoolean(message.cloaking);
    }

    private static CloakStateS2C read(RegistryFriendlyByteBuf buf) {
        return new CloakStateS2C(buf.readUUID(), buf.readBoolean());
    }

    @Override
    public Type<CloakStateS2C> type() {
        return TYPE;
    }

    public static void handle(CloakStateS2C message, IPayloadContext context) {
        context.enqueueWork(() -> EtherCasingCloakState.setRequested(message.playerId, message.cloaking));
    }
}
