package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.RequesterData;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.CreateNetworkAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import dev.distantstock.routing.RemoteNetworkId;

public record JoinNetworkC2S(UUID freq, RemoteNetworkId networkId) implements CustomPacketPayload {
    public static final Type<JoinNetworkC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "join_network"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JoinNetworkC2S> STREAM_CODEC =
            StreamCodec.of(JoinNetworkC2S::write, JoinNetworkC2S::read);

    public JoinNetworkC2S(UUID freq) {
        this(freq, null);
    }

    private static void write(RegistryFriendlyByteBuf buf, JoinNetworkC2S message) {
        buf.writeUUID(message.freq());
        buf.writeBoolean(message.networkId() != null);
        if (message.networkId() != null) {
            buf.writeNbt(message.networkId().save());
        }
    }

    private static JoinNetworkC2S read(RegistryFriendlyByteBuf buf) {
        UUID freq = buf.readUUID();
        RemoteNetworkId networkId = buf.readBoolean()
                ? RemoteNetworkId.read(buf.readNbt()).orElse(null) : null;
        return new JoinNetworkC2S(freq, networkId);
    }

    @Override
    public Type<JoinNetworkC2S> type() {
        return TYPE;
    }

    public static void handle(JoinNetworkC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof RequesterMenu menu)) {
                return;
            }
            NetworkDirectory.Entry entry = msg.networkId == null
                    ? NetworkDirectory.find(msg.freq, StockConfig.isHost()).orElse(null)
                    : NetworkDirectory.find(msg.networkId).orElse(null);
            if (entry == null) {
                return;
            }
            java.util.UUID targetScope;
            if (entry.local() && entry.networkId() != null) {
                targetScope = dev.distantstock.routing.DistantNetworkDirectory.get(player.getServer())
                        .formalNetworkOf(entry.networkId())
                        .orElse(dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID);
            } else {
                targetScope = entry.distantNetworkId() == null
                        ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID
                        : entry.distantNetworkId();
            }
            // A warehouse may only be selected after this terminal/request desk already has a
            // formal Distant Stock network context, and only from that network's member set. The
            // server enforces this even if a crafted client sends an arbitrary Create frequency.
            java.util.UUID currentScope = menu.distantNetworkId(player);
            if (!dev.distantstock.routing.DistantNetworkDirectory.isFormalId(currentScope)
                    || !currentScope.equals(targetScope)) {
                return;
            }
            if (entry.local() && !CreateNetworkAccess.mayInteract(entry.networkId(), entry.freq(), player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.distantstock.network.interact_denied"), true);
                return;
            }
            if (menu.gauge(player) != null) {
                if (entry.networkId() == null) {
                    menu.gauge(player).setFreq(msg.freq);
                } else {
                    menu.gauge(player).setNetwork(entry.networkId(), targetScope);
                }
            } else {
                ItemStack device = menu.device(player);
                if (!device.isEmpty()) {
                    if (entry.networkId() == null) {
                        RequesterData.setFreq(device, msg.freq);
                    } else {
                        RequesterData.setNetwork(device, entry.networkId(), targetScope);
                    }
                }
            }
            menu.selectedFreq = msg.freq;
            if (entry.networkId() != null) {
                // 玩家又选了一次这张网络：之前「对面说不认识它」的退避作废，马上去问。
                dev.distantstock.stock.StockCache.clearRefusal(entry.networkId());
            }
            menu.refresh(player);
            PacketDistributor.sendToPlayer(player, StockSyncS2C.of(
                    menu.demo, menu.stock, menu.distantNetworkId(player)));
            CreateNetworkLockS2C.send(player, menu);
        });
    }
}
