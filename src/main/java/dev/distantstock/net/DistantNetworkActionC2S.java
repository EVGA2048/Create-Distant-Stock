package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.link.DistantNetworkJoinService;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.menu.MenuSync;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.CreateNetworkAccess;
import dev.distantstock.stock.StockScanner;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/** Actions on the small Distant Stock network page opened from a requester. */
public record DistantNetworkActionC2S(int action, String value) implements CustomPacketPayload {
    private static final Logger LOG = LogManager.getLogger();
    public static final int REFRESH = 0;
    public static final int CREATE = 1;
    public static final int JOIN = 2;
    public static final int RESET_CODE = 3;
    public static final int LEAVE = 4;
    public static final int RENAME_WAREHOUSE = 5;

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
            StockScanner.scan(player.getServer());
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(player.getServer());
            UUID scope = menu.distantNetworkId(player);
            var current = DistantNetworkDirectory.isFormalId(scope)
                    ? directory.find(scope).orElse(null) : null;
            boolean joined = current != null && !current.legacy();
            if (msg.action == REFRESH) {
                sendCurrentState(player, menu, joined ? current.id() : null);
                return;
            }
            if (menu.isGauge() && !joined) {
                say(player, "message.distantstock.network.portable_required");
                DistantNetworkStateS2C.sendScope(player, null);
                return;
            }
            UUID localNode = TranserverBridge.localNodeUuid();
            if (localNode == null) {
                say(player, "message.distantstock.network.identity_unavailable");
                return;
            }

            if (msg.action == CREATE) {
                if (joined) {
                    say(player, "message.distantstock.network.already_joined", current.name());
                    return;
                }
                UUID node = localNode;
                DistantNetworkDirectory.Network created;
                try {
                    created = directory.create(msg.value, node, player.getUUID());
                } catch (IllegalArgumentException exception) {
                    var recover = directory.findOwnedByName(msg.value, player.getUUID(), node).orElse(null);
                    if (recover == null) {
                        LOG.warn("Distant network CREATE failed for player={} member={} name='{}': {}",
                                player.getUUID(), null, msg.value, exception.getMessage());
                        say(player, "message.distantstock.network.create_failed_detail", exception.getMessage());
                        DistantNetworkStateS2C.sendScope(player, null);
                        return;
                    }
                    created = recover;
                    LOG.info("Recovered owned Distant network '{}' ({}) for terminal",
                            created.name(), created.id());
                }
                menu.persistDistantNetworkContext(player, created.id());
                menu.refresh(player);
                say(player, "message.distantstock.network.created", created.name());
                DistantNetworkStateS2C.sendScope(player, created.id());
                return;
            }

            if (msg.action == JOIN) {
                if (joined) {
                    say(player, "message.distantstock.network.leave_first");
                    return;
                }
                if (!DistantNetworkJoinService.requestTerminal(
                        player.getServer(), player, menu.hand, msg.value)) {
                    boolean localCode = directory.findByCode(msg.value).isPresent();
                    if (!localCode && TranserverBridge.attachedApi() == null) {
                        say(player, "message.distantstock.network.join_not_local_no_transport");
                    } else {
                        say(player, "message.distantstock.network.join_send_failed");
                    }
                } else {
                    say(player, "message.distantstock.network.join_searching");
                }
                return;
            }

            if (!joined) {
                say(player, "message.distantstock.network.not_joined");
                DistantNetworkStateS2C.sendScope(player, null);
                return;
            }

            if (msg.action == RENAME_WAREHOUSE) {
                RemoteNetworkId member = MenuSync.resolve(menu.networkId(player), menu.freq(player));
                NetworkDirectory.Entry row = member == null ? null : NetworkDirectory.find(member).orElse(null);
                if (member == null || row == null || !row.local()
                        || !current.id().equals(directory.formalNetworkOf(member).orElse(null))) {
                    say(player, "message.distantstock.network.rename_local_only");
                    return;
                }
                if (!CreateNetworkAccess.mayAdministrate(member, row.freq(), player)) {
                    say(player, "message.distantstock.network.create_admin_required");
                    return;
                }
                if (!directory.renameMember(member, msg.value)) {
                    say(player, "message.distantstock.network.rename_failed");
                    return;
                }
                StockScanner.scan(player.getServer());
                menu.refresh(player);
                say(player, "message.distantstock.network.rename_ok", msg.value.trim());
                PacketDistributor.sendToPlayer(player, StockSyncS2C.of(
                        menu.demo, menu.stock, current.id()));
                DistantNetworkStateS2C.send(player, member, current.id());
                return;
            }

            if (msg.action == RESET_CODE) {
                UUID node = localNode;
                try {
                    directory.resetJoinCode(current.id(), player.getUUID(), node);
                    say(player, "message.distantstock.network.code_reset");
                } catch (IllegalArgumentException exception) {
                    say(player, "message.distantstock.network.code_reset_owner_only");
                }
            } else if (msg.action == LEAVE) {
                if (menu.isGauge()) {
                    say(player, "message.distantstock.network.portable_required");
                    return;
                }
                menu.clearDistantNetworkContext(player);
                menu.refresh(player);
                say(player, "message.distantstock.network.left", current.name());
            }
            sendCurrentState(player, menu, menu.distantNetworkId(player));
        });
    }

    private static void sendCurrentState(ServerPlayer player, RequesterMenu menu, UUID scope) {
        RemoteNetworkId member = MenuSync.resolve(menu.networkId(player), menu.freq(player));
        if (member != null) {
            DistantNetworkStateS2C.send(player, member, scope);
        } else {
            DistantNetworkStateS2C.sendScope(player, scope);
        }
    }

    private static void say(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
