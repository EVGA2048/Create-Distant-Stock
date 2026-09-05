package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.client.AdminScreen;
import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AdminConfigS2C(
        String role,
        String selfId,
        String bind,
        String token,
        String peers,
        String linkLabel,
        boolean peerUp
) implements CustomPacketPayload {
    public static final Type<AdminConfigS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "admin_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminConfigS2C> STREAM_CODEC =
            StreamCodec.of(AdminConfigS2C::write, AdminConfigS2C::read);

    public static AdminConfigS2C fromConfig() {
        return new AdminConfigS2C(
                StockConfig.role(),
                StockConfig.selfId(),
                String.valueOf(StockConfig.BIND.get()),
                StockConfig.TOKEN.get() == null ? "" : StockConfig.TOKEN.get(),
                StockConfig.peersText(),
                LinkSnapshot.linkLabel(),
                LinkSnapshot.peerUp
        );
    }

    private static void write(RegistryFriendlyByteBuf buf, AdminConfigS2C m) {
        buf.writeUtf(m.role);
        buf.writeUtf(m.selfId);
        buf.writeUtf(m.bind);
        buf.writeUtf(m.token);
        buf.writeUtf(m.peers);
        buf.writeUtf(m.linkLabel);
        buf.writeBoolean(m.peerUp);
    }

    private static AdminConfigS2C read(RegistryFriendlyByteBuf buf) {
        return new AdminConfigS2C(
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readBoolean()
        );
    }

    @Override
    public Type<AdminConfigS2C> type() {
        return TYPE;
    }

    public static void handle(AdminConfigS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> Minecraft.getInstance().setScreen(new AdminScreen(msg)));
    }
}
