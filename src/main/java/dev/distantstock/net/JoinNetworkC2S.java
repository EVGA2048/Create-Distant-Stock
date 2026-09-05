package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.RequesterData;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.stock.NetworkDirectory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record JoinNetworkC2S(UUID freq) implements CustomPacketPayload {
    public static final Type<JoinNetworkC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "join_network"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JoinNetworkC2S> STREAM_CODEC =
            StreamCodec.composite(net.minecraft.core.UUIDUtil.STREAM_CODEC, JoinNetworkC2S::freq, JoinNetworkC2S::new);

    @Override
    public Type<JoinNetworkC2S> type() {
        return TYPE;
    }

    public static void handle(JoinNetworkC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof RequesterMenu menu)
                    || !NetworkDirectory.contains(msg.freq, StockConfig.isHost())) {
                return;
            }
            if (menu.gauge(player) != null) {
                menu.gauge(player).setFreq(msg.freq);
            } else {
                ItemStack device = menu.device(player);
                if (!device.isEmpty()) {
                    RequesterData.setFreq(device, msg.freq);
                }
            }
            menu.selectedFreq = msg.freq;
            menu.refresh(player);
            PacketDistributor.sendToPlayer(player, StockSyncS2C.of(menu.demo, menu.stock));
        });
    }
}
