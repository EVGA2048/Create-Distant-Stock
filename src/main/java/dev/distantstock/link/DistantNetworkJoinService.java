package dev.distantstock.link;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.block.RemoteGaugeBlockEntity;
import dev.distantstock.block.RemoteRedstoneRequesterBlockEntity;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server join-code handshake.
 *
 * <p>The code is never announced. A joining node broadcasts only the code the player typed; only
 * the authority that owns that code answers, and the accepted member stores the network UUID from
 * then on.
 */
public final class DistantNetworkJoinService {
    public record DeviceTarget(ResourceKey<Level> dimension, BlockPos pos, int slot) {
    }

    private record Pending(UUID player, RemoteNetworkId member, InteractionHand hand,
                           DeviceTarget device, long sentAt) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 10_000L;

    public static void register() {
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                DistantNetworkJoinService::receiveRequest);
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT,
                DistantNetworkJoinService::receiveAccept);
    }

    public static void stop() {
        PENDING.clear();
    }

    /**
     * Starts a join. Returns false only when nothing could possibly receive the request.
     * A local code is applied immediately without going over Transerver.
     */
    public static boolean request(MinecraftServer server, UUID player, RemoteNetworkId member, String code) {
        return request(server, player, member, null, null, code);
    }

    /** Join only the portable terminal to a Distant Stock network; no Create warehouse is enrolled. */
    public static boolean requestTerminal(MinecraftServer server, ServerPlayer player,
                                          InteractionHand hand, String code) {
        if (player == null || hand == null) return false;
        return request(server, player.getUUID(), null, hand, null, code);
    }

    /** Join one placed device/panel to a Distant Stock network without selecting a warehouse yet. */
    public static boolean requestDevice(MinecraftServer server, ServerPlayer player,
                                        ResourceKey<Level> dimension, BlockPos pos, int slot,
                                        String code) {
        if (player == null || dimension == null || pos == null) return false;
        return request(server, player.getUUID(), null, null,
                new DeviceTarget(dimension, pos.immutable(), slot), code);
    }

    private static boolean request(MinecraftServer server, UUID player, RemoteNetworkId member,
                                   InteractionHand hand, DeviceTarget device, String code) {
        if (server == null || player == null
                || (member == null && hand == null && device == null)) return false;
        String normalized;
        try {
            normalized = DistantNetworkDirectory.normalizeCode(code);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
        var local = directory.findByCode(normalized).orElse(null);
        if (local != null) {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
            if (member == null) {
                boolean bound = serverPlayer != null && (device != null
                        ? bindDevice(server, device, local.id())
                        : bindTerminal(serverPlayer, hand, local.id()));
                if (!bound) {
                    return false;
                }
                notify(server, player, "message.distantstock.network.joined", local.name());
                if (device == null) {
                    dev.distantstock.net.DistantNetworkStateS2C.sendScope(serverPlayer, local.id());
                } else {
                    dev.distantstock.net.DistantDeviceStateS2C.send(serverPlayer, device);
                }
                return true;
            }
            UUID current = directory.networkOf(member).orElse(null);
            // Legacy is not a real membership. Older saves may have persisted it explicitly, and
            // attach() deliberately supports Legacy -> formal migration. Do not reject that case
            // here before attach() gets a chance to perform the migration.
            if (current != null
                    && !DistantNetworkDirectory.LEGACY_NETWORK_ID.equals(current)
                    && !current.equals(local.id())) {
                return false;
            }
            if (!currentEquals(current, local.id()) && !directory.attach(member, local.id())) {
                return false;
            }
            dev.distantstock.stock.StockScanner.scan(server);
            notify(server, player, "message.distantstock.network.joined", local.name());
            if (serverPlayer != null) {
                if (serverPlayer.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
                    menu.persistDistantNetworkScope(serverPlayer, member, local.id());
                    menu.refresh(serverPlayer);
                }
                dev.distantstock.net.DistantNetworkStateS2C.send(serverPlayer, member);
            }
            return true;
        }

        if (TranserverBridge.attachedApi() == null || TranserverBridge.knownNodes().isEmpty()) {
            return false;
        }
        // Double-clicking Join should not create two independent timers. Otherwise one request may
        // succeed and the other later emit a bogus "not found" message for the same warehouse.
        boolean alreadyPending = PENDING.values().stream()
                .anyMatch(pending -> pending.player().equals(player)
                        && Objects.equals(pending.member(), member)
                        && pending.hand() == hand
                        && Objects.equals(pending.device(), device));
        if (alreadyPending) {
            return true;
        }
        UUID requestId = UUID.randomUUID();
        byte[] payload;
        try {
            payload = DistantNetworkJoinCodec.encodeRequest(
                    new DistantNetworkJoinCodec.Request(requestId, normalized, member));
        } catch (IOException exception) {
            return false;
        }
        PENDING.put(requestId, new Pending(player, member, hand, device, System.currentTimeMillis()));
        int sent = 0;
        UUID localNode = TranserverBridge.localNodeUuid();
        String self = localNode == null ? "" : localNode.toString();
        for (String node : TranserverBridge.knownNodes()) {
            if (node.equals(self)) continue;
            if (TranserverBridge.send(node, RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                    payload, requestId.toString()) != null) {
                sent++;
            }
        }
        if (sent == 0) {
            PENDING.remove(requestId);
            return false;
        }
        return true;
    }

    private static boolean bindDevice(MinecraftServer server, DeviceTarget target, UUID scope) {
        if (server == null || target == null || !DistantNetworkDirectory.isFormalId(scope)) return false;
        var level = server.getLevel(target.dimension());
        if (level == null) return false;
        var blockEntity = level.getBlockEntity(target.pos());
        if (blockEntity instanceof RemoteRedstoneRequesterBlockEntity requester && target.slot() < 0) {
            if (requester.binding() != null
                    && !scope.equals(requester.binding().distantNetworkId())) {
                requester.bind(null);
            }
            requester.setDistantNetworkScope(scope);
            return true;
        }
        FactoryPanelBlock.PanelSlot[] slots = FactoryPanelBlock.PanelSlot.values();
        if (target.slot() < 0 || target.slot() >= slots.length) return false;
        FactoryPanelBlock.PanelSlot slot = slots[target.slot()];
        if (blockEntity instanceof RemoteGaugeBlockEntity gauge) {
            if (!gauge.panels.get(slot).isActive()) return false;
            if (gauge.binding(slot) != null
                    && !scope.equals(gauge.binding(slot).distantNetworkId())) {
                gauge.unbind(slot);
            }
            gauge.setDistantNetworkScope(slot, scope);
            return true;
        }
        if (blockEntity instanceof SignalPanelBlockEntity signal && signal.isRemoteGauge(slot)) {
            if (signal.binding(slot) != null
                    && !scope.equals(signal.binding(slot).distantNetworkId())) {
                signal.unbind(slot);
            }
            signal.setDistantNetworkScope(slot, scope);
            return true;
        }
        if (blockEntity instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                && net.neoforged.fml.ModList.get().isLoaded("deployer")
                && dev.distantstock.panel.DeployerPanels.holdsRemoteGauge(board, slot)) {
            var binding = dev.distantstock.panel.DeployerPanels.bindingOf(board, slot);
            if (binding != null && !scope.equals(binding.distantNetworkId())) {
                dev.distantstock.panel.DeployerPanels.unbind(board, slot);
            }
            return dev.distantstock.panel.DeployerPanels.setDistantNetworkScope(board, slot, scope);
        }
        return false;
    }

    private static boolean currentEquals(UUID current, UUID wanted) {
        return current != null && current.equals(wanted);
    }

    public static void tick(MinecraftServer server) {
        acknowledgeCompleted();
        long cutoff = System.currentTimeMillis() - TIMEOUT_MS;
        PENDING.entrySet().removeIf(entry -> {
            if (entry.getValue().sentAt() >= cutoff) return false;
            notify(server, entry.getValue().player(), "message.distantstock.network.code_not_found");
            return true;
        });
    }

    private static CompletableFuture<DeliveryResult> receiveRequest(ReceivedMessage message) {
        final DistantNetworkJoinCodec.Request request;
        final UUID source;
        try {
            request = DistantNetworkJoinCodec.decodeRequest(message.payload());
            source = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        if (request.member() != null && !source.equals(request.member().nodeId())) {
            // A node may only enroll a Create network that actually belongs to that node.
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> {
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            var network = directory.findByCode(request.joinCode()).orElse(null);
            if (network == null || network.ownerNode() == null
                    || !network.ownerNode().equals(TranserverBridge.localNodeUuid())) {
                // The request is deliberately broadcast. "Not mine" is a handled no-op, not a
                // reason for Transerver to retry it for seven days.
                result.complete(DeliveryResult.APPLIED);
                return;
            }
            // The authority grants membership but does not persist the remote Create network as its
            // own SavedData membership. The warehouse's node is authoritative for that relationship
            // and announces it afterwards. Keeping a second permanent copy here would drift the
            // moment that remote warehouse leaves while this node is offline.
            try {
                byte[] payload = DistantNetworkJoinCodec.encodeAccept(
                        new DistantNetworkJoinCodec.Accept(request.requestId(), network.id(),
                                network.name(), network.ownerNode(), request.member()));
                UUID sent = TranserverBridge.send(source.toString(),
                        RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT, payload,
                        request.requestId().toString());
                result.complete(sent == null ? DeliveryResult.RETRY : DeliveryResult.APPLIED);
            } catch (IOException exception) {
                result.complete(DeliveryResult.REJECTED);
            }
        });
        return result;
    }

    private static boolean bindTerminal(ServerPlayer player, InteractionHand hand, UUID scope) {
        if (player == null || hand == null || !DistantNetworkDirectory.isFormalId(scope)) return false;
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof RequesterItem)) return false;
        RequesterData.setDistantNetwork(stack, scope);
        player.getInventory().setChanged();
        if (player.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
            menu.persistDistantNetworkContext(player, scope);
            menu.refresh(player);
        }
        return true;
    }

    private static CompletableFuture<DeliveryResult> receiveAccept(ReceivedMessage message) {
        final DistantNetworkJoinCodec.Accept accept;
        final UUID source;
        try {
            accept = DistantNetworkJoinCodec.decodeAccept(message.payload());
            source = UUID.fromString(message.source());
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        if (!source.equals(accept.ownerNode())) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        Pending pending = PENDING.get(accept.requestId());
        if (pending == null) {
            // Transerver is at-least-once. If we already applied this acceptance, acknowledge a
            // duplicate instead of turning it into a retry loop/dead letter.
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            boolean alreadyApplied = accept.member() == null
                    ? directory.find(accept.networkId()).isPresent()
                    : directory.networkOf(accept.member()).map(accept.networkId()::equals).orElse(false);
            return CompletableFuture.completedFuture(
                    alreadyApplied ? DeliveryResult.APPLIED : DeliveryResult.REJECTED);
        }
        if (!Objects.equals(pending.member(), accept.member())) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> {
            Pending accepted = PENDING.remove(accept.requestId());
            if (accepted == null || !Objects.equals(accepted.member(), accept.member())) {
                result.complete(DeliveryResult.REJECTED);
                return;
            }
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            directory.rememberReplica(accept.networkId(), accept.networkName(), accept.ownerNode());
            if (accept.member() == null) {
                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(accepted.player());
                boolean bound = serverPlayer != null && (accepted.device() != null
                        ? bindDevice(server, accepted.device(), accept.networkId())
                        : bindTerminal(serverPlayer, accepted.hand(), accept.networkId()));
                if (!bound) {
                    result.complete(DeliveryResult.REJECTED);
                    return;
                }
                notify(server, accepted.player(), "message.distantstock.network.joined", accept.networkName());
                if (accepted.device() == null) {
                    dev.distantstock.net.DistantNetworkStateS2C.sendScope(serverPlayer, accept.networkId());
                } else {
                    dev.distantstock.net.DistantDeviceStateS2C.send(serverPlayer, accepted.device());
                }
                result.complete(DeliveryResult.APPLIED);
                return;
            }
            UUID current = directory.networkOf(accept.member()).orElse(null);
            if (current != null && !current.equals(accept.networkId())) {
                result.complete(DeliveryResult.REJECTED);
                return;
            }
            directory.attach(accept.member(), accept.networkId());
            dev.distantstock.stock.StockScanner.scan(server);
            notify(server, accepted.player(), "message.distantstock.network.joined", accept.networkName());
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(accepted.player());
            if (serverPlayer != null) {
                if (serverPlayer.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
                    menu.persistDistantNetworkScope(serverPlayer, accept.member(), accept.networkId());
                    menu.refresh(serverPlayer);
                }
                dev.distantstock.net.DistantNetworkStateS2C.send(serverPlayer, accept.member());
            }
            result.complete(DeliveryResult.APPLIED);
        });
        return result;
    }

    private static void acknowledgeCompleted() {
        var api = TranserverBridge.attachedApi();
        if (api == null) return;
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST.equals(completed.channel())
                    || RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static void notify(MinecraftServer server, UUID playerId, String key, Object... args) {
        if (server == null || playerId == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), false);
        }
    }

    private DistantNetworkJoinService() {
    }
}
