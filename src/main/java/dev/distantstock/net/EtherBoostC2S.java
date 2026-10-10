package dev.distantstock.net;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherFlightServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record EtherBoostC2S(boolean boosting) implements CustomPacketPayload {
    public static final Type<EtherBoostC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "ether_boost"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EtherBoostC2S> STREAM_CODEC =
            StreamCodec.of(EtherBoostC2S::write, EtherBoostC2S::read);

    private static void write(RegistryFriendlyByteBuf buf, EtherBoostC2S message) {
        buf.writeBoolean(message.boosting);
    }

    private static EtherBoostC2S read(RegistryFriendlyByteBuf buf) {
        return new EtherBoostC2S(buf.readBoolean());
    }

    @Override
    public Type<EtherBoostC2S> type() {
        return TYPE;
    }

    public static void handle(EtherBoostC2S message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            EtherFlightServer.setBoosting(player, message.boosting);
        });
    }
}
