package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.link.DistantNetworkJoinService;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.menu.MenuSync;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockScanner;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Actions on the small Distant Stock network page opened from a requester. */
public record DistantNetworkActionC2S(int action, String value) implements CustomPacketPayload {
    public static final int REFRESH = 0;
    public static final int CREATE = 1;
    public static final int JOIN = 2;
    public static final int RESET_CODE = 3;
    public static final int LEAVE = 4;

    public static final Type<DistantNetworkActionC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "distant_network_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DistantNetworkActionC2S> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DistantNetworkActionC2S::action,
                    ByteBufCodecs.STRING_UTF8, DistantNetworkActionC2S::value,
                    DistantNetworkActionC2S::new);

    @Override
    public Type<DistantNetworkActionC2S> type() {
        return TYPE;
    }

    public static void handle(DistantNetworkActionC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof RequesterMenu menu)
                    || msg.value == null || msg.value.length() > 64) {
                return;
            }
            RemoteNetworkId member = MenuSync.resolve(menu.networkId(player), menu.freq(player));
            if (msg.action == REFRESH) {
                DistantNetworkStateS2C.send(player, member, menu.distantNetworkId(player));
                return;
            }
            if (member == null) {
                say(player, "message.distantstock.network.bind_local_first");
                DistantNetworkStateS2C.send(player, null);
                return;
            }
            NetworkDirectory.Entry row = NetworkDirectory.find(member).orElse(null);
            boolean local = row != null && row.local();
            if (!local) {
                say(player, "message.distantstock.network.select_local_first");
                DistantNetworkStateS2C.send(player, member, menu.distantNetworkId(player));
                return;
            }

            DistantNetworkDirectory directory = DistantNetworkDirectory.get(player.getServer());
            UUID scope = directory.scopeOf(member);
            var current = directory.find(scope).orElse(null);
            boolean joined = current != null && !current.legacy();

            if (msg.action == CREATE) {
                if (joined) {
                    say(player, "message.distantstock.network.already_joined", current.name());
                    return;
                }
                UUID node = TranserverBridge.nodeId();
                if (node == null) {
                    say(player, "message.distantstock.network.no_transerver");
                    return;
                }
                try {
                    var created = directory.create(msg.value, node, player.getUUID(), member);
                    StockScanner.scan(player.getServer());
                    menu.persistDistantNetworkScope(player, member, created.id());
                    menu.refresh(player);
                    say(player, "message.distantstock.network.created", created.name());
                } catch (IllegalArgumentException exception) {
                    say(player, "message.distantstock.network.create_failed");
                }
                DistantNetworkStateS2C.send(player, member, menu.distantNetworkId(player));
                return;
            }

            if (msg.action == JOIN) {
                if (joined) {
                    say(player, "message.distantstock.network.leave_first");
                    return;
                }
                if (!DistantNetworkJoinService.request(player.getServer(), player.getUUID(), member, msg.value)) {
                    say(player, "message.distantstock.network.join_send_failed");
                } else {
                    say(player, "message.distantstock.network.join_searching");
                }
                return;
            }

            if (!joined) {
                say(player, "message.distantstock.network.not_joined");
                DistantNetworkStateS2C.send(player, member);
                return;
            }

            if (msg.action == RESET_CODE) {
                UUID node = TranserverBridge.nodeId();
                try {
                    directory.resetJoinCode(current.id(), player.getUUID(), node);
                    say(player, "message.distantstock.network.code_reset");
                } catch (IllegalArgumentException exception) {
                    say(player, "message.distantstock.network.code_reset_owner_only");
                }
            } else if (msg.action == LEAVE) {
                if (directory.wouldOrphanAuthority(member, player.getUUID(), TranserverBridge.nodeId())) {
                    say(player, "message.distantstock.network.authority_last");
                    DistantNetworkStateS2C.send(player, member);
                    return;
                }
                directory.detach(member);
                StockScanner.scan(player.getServer());
                menu.persistDistantNetworkScope(player, member,
                        DistantNetworkDirectory.LEGACY_NETWORK_ID);
                menu.refresh(player);
                say(player, "message.distantstock.network.left", current.name());
            }
            DistantNetworkStateS2C.send(player, member, menu.distantNetworkId(player));
        });
    }

    private static void say(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
