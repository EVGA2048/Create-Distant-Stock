package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SaveAdminC2S(
        String role,
        String selfId,
        String bind,
        String token,
        String peers
) implements CustomPacketPayload {
    public static final Type<SaveAdminC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "save_admin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveAdminC2S> STREAM_CODEC =
            StreamCodec.of(SaveAdminC2S::write, SaveAdminC2S::read);

    private static void write(RegistryFriendlyByteBuf buf, SaveAdminC2S m) {
        buf.writeUtf(m.role, 16);
        buf.writeUtf(m.selfId, 32);
        buf.writeUtf(m.bind, 64);
        buf.writeUtf(m.token, 128);
        buf.writeUtf(m.peers, 512);
    }

    private static SaveAdminC2S read(RegistryFriendlyByteBuf buf) {
        return new SaveAdminC2S(
                buf.readUtf(16),
                buf.readUtf(32),
                buf.readUtf(64),
                buf.readUtf(128),
                buf.readUtf(512)
        );
    }

    @Override
    public Type<SaveAdminC2S> type() {
        return TYPE;
    }

    public static void handle(SaveAdminC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) {
                return;
            }
            StockConfig.apply(msg.role, msg.selfId, msg.bind, msg.token, msg.peers);
            LinkServer.start();
            player.sendSystemMessage(Component.translatable("gui.distantstock.admin.saved"));
            PacketDistributor.sendToPlayer(player, AdminConfigS2C.fromConfig());
        });
    }
}
