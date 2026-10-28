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

    private static ItemStack heldTerminal(ServerPlayer player, InteractionHand hand) {
        return player == null || hand == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    private static void syncTerminalMenu(ServerPlayer player, dev.distantstock.menu.RequesterMenu menu) {
        menu.resetStockStream();
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                dev.distantstock.net.StockSyncS2C.of(menu.demo, menu.stock, menu.distantNetworkId(player)));
        dev.distantstock.menu.RequesterMenu.sendGroupList(player, menu.device(player));
    }

    /**
     * Join an already selected local Create warehouse to the terminal's chosen scope.
     * Only Create administrators may make this membership change, and existing memberships
     * in another formal scope are never silently reassigned. An untuned portable requester is
     * still allowed to join a Distant Stock network without creating a warehouse.
     */
    public static boolean enrollSelectedLocalWarehouse(MinecraftServer server, ServerPlayer player,
                                                       ItemStack terminal, UUID scope) {
        if (server == null || player == null || terminal.isEmpty()
                || !DistantNetworkDirectory.isFormalId(scope)) return false;
        var directory = DistantNetworkDirectory.get(server);
        UUID freq = RequesterData.freq(terminal);
        if (freq == null) return false;
        var entry = dev.distantstock.stock.NetworkDirectory.findByFreq(freq)
                .filter(dev.distantstock.stock.NetworkDirectory.Entry::local).orElse(null);
        if (entry == null || entry.networkId() == null
                || !dev.distantstock.stock.CreateNetworkAccess.mayAdministrate(
                        entry.networkId(), freq, player)) return false;
        UUID existing = directory.formalNetworkOf(entry.networkId()).orElse(null);
        if (existing != null && !existing.equals(scope)) return false;
        if (existing == null && !directory.attach(entry.networkId(), scope)) return false;
        dev.distantstock.stock.StockScanner.scan(server);
        return true;
    }

    private record Pending(UUID player, RemoteNetworkId member, InteractionHand hand,
                           DeviceTarget device, ItemStack terminal, long sentAt) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    // A durable Router backlog may delay the request AND its acceptance. Expiring at 60 seconds
    // rejected otherwise-valid replies during routine queue recovery, leaving terminals unjoined.
    // The wire message expires after 360 seconds, so a late reply cannot mutate the next session.
    private static final long TIMEOUT_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);

    public static void register() {
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                DistantNetworkJoinService::receiveRequest);
        TranserverBridge.handler(RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT,
                DistantNetworkJoinService::receiveAccept);
    }

    public static void stop() {
        PENDING.clear();
    }

    public static int pendingCount() { return PENDING.size(); }

    /**
     * Starts a join. Returns false only when nothing could possibly receive the request.
     * A local code is applied immediately without going over Transerver.
     */
    public static boolean request(MinecraftServer server, UUID player, RemoteNetworkId member, String code) {
        return request(server, player, member, null, null, null, code);
    }

    /** Join only the portable terminal to a Distant Stock network; no Create warehouse is enrolled. */
    public static boolean requestTerminal(MinecraftServer server, ServerPlayer player,
                                          InteractionHand hand, String code) {
        if (player == null || hand == null) return false;
        ItemStack terminal = player.getItemInHand(hand);
        if (!(terminal.getItem() instanceof RequesterItem)) return false;
        return request(server, player.getUUID(), null, hand, null, terminal, code);
    }

    /** Join one placed device/panel to a Distant Stock network without selecting a warehouse yet. */
    public static boolean requestDevice(MinecraftServer server, ServerPlayer player,
                                        ResourceKey<Level> dimension, BlockPos pos, int slot,
                                        String code) {
        if (player == null || dimension == null || pos == null) return false;
        return request(server, player.getUUID(), null, null,
                new DeviceTarget(dimension, pos.immutable(), slot), null, code);
    }

    private static boolean request(MinecraftServer server, UUID player, RemoteNetworkId member,
                                   InteractionHand hand, DeviceTarget device, ItemStack terminal, String code) {
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
                        : bindTerminal(serverPlayer, hand, terminal, local.id()));
                if (!bound) {
                    return false;
                }
                if (device == null) {
                    enrollSelectedLocalWarehouse(server, serverPlayer,
                            heldTerminal(serverPlayer, hand), local.id());
                }
                dev.distantstock.stock.StockScanner.scan(server);
                if (serverPlayer.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu
                        && device == null && menu.hand == hand && !menu.isGauge()) {
                    menu.refresh(serverPlayer);
                    syncTerminalMenu(serverPlayer, menu);
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
        PENDING.put(requestId, new Pending(player, member, hand, device, terminal, System.nanoTime()));
        int sent = 0;
        UUID localNode = TranserverBridge.localNodeUuid();
        String self = localNode == null ? "" : localNode.toString();
        for (String node : TranserverBridge.knownNodes()) {
            if (node.equals(self)) continue;
            if (TranserverBridge.queueSend(node, RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                    payload, requestId.toString())) {
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
        long now = System.nanoTime();
        PENDING.entrySet().removeIf(entry -> {
            if (now - entry.getValue().sentAt() < TIMEOUT_NANOS) return false;
            notify(server, entry.getValue().player(), "message.distantstock.network.join_timeout");
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
        return DistantNetworkJoinService.<CompletableFuture<DeliveryResult>>onServer(server, () -> {
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            var network = directory.findByCode(request.joinCode()).orElse(null);
            if (network == null || network.ownerNode() == null
                    || !network.ownerNode().equals(TranserverBridge.localNodeUuid())) {
                // The request is deliberately broadcast. "Not mine" is a handled no-op, not a
                // reason for Transerver to retry it for seven days.
                return CompletableFuture.completedFuture(DeliveryResult.APPLIED);
            }
            // The authority grants membership but does not persist the remote Create network as its
            // own SavedData membership. The warehouse's node is authoritative for that relationship
            // and announces it afterwards. Keeping a second permanent copy here would drift the
            // moment that remote warehouse leaves while this node is offline.
            try {
                byte[] payload = DistantNetworkJoinCodec.encodeAccept(
                        new DistantNetworkJoinCodec.Accept(request.requestId(), network.id(),
                                network.name(), network.ownerNode(), request.member()));
                var sent = TranserverBridge.sendAsync(source.toString(),
                        RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT, payload,
                        request.requestId().toString());
                return sent.handle((id, error) -> error == null ? DeliveryResult.APPLIED : DeliveryResult.RETRY);
            } catch (IOException exception) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
        }).thenCompose(result -> result);
    }

    private static boolean bindTerminal(ServerPlayer player, InteractionHand hand, ItemStack expected, UUID scope) {
        if (player == null || hand == null || !DistantNetworkDirectory.isFormalId(scope)) return false;
        ItemStack stack = player.getItemInHand(hand);
        if (stack != expected || !(stack.getItem() instanceof RequesterItem)) return false;
        UUID current = RequesterData.distantNetwork(stack).orElse(null);
        if (DistantNetworkDirectory.isFormalId(current) && !scope.equals(current)) return false;
        RequesterData.setDistantNetwork(stack, scope);
        player.getInventory().setChanged();
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
        if (!source.equals(accept.ownerNode())
                || !DistantNetworkDirectory.isFormalId(accept.networkId())
                || accept.networkName().isBlank()
                || accept.networkName().trim().length() > DistantNetworkDirectory.MAX_NAME_LENGTH) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        // SavedData and the pending-to-applied transition belong to the game thread, including
        // duplicate deliveries. A transport callback must not read Minecraft SavedData directly.
        return onServer(server, () -> {
            DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
            Pending pending = PENDING.get(accept.requestId());
            var known = directory.find(accept.networkId()).orElse(null);
            if (known != null && !Objects.equals(known.ownerNode(), accept.ownerNode())) {
                return DeliveryResult.REJECTED;
            }
            if (pending == null) {
                boolean applied = known != null && (accept.member() == null
                        || directory.formalNetworkOf(accept.member()).filter(accept.networkId()::equals).isPresent());
                return applied ? DeliveryResult.APPLIED : DeliveryResult.REJECTED;
            }
            if (!Objects.equals(pending.member(), accept.member())) return DeliveryResult.REJECTED;
            if (System.nanoTime() - pending.sentAt() >= TIMEOUT_NANOS) return DeliveryResult.REJECTED;
            if (accept.member() == null) {
                ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
                if (player == null) return DeliveryResult.REJECTED;
                if (pending.device() == null) {
                    ItemStack held = pending.hand() == null ? ItemStack.EMPTY : player.getItemInHand(pending.hand());
                    UUID current = RequesterData.distantNetwork(held).orElse(null);
                    if (held != pending.terminal() || !(held.getItem() instanceof RequesterItem)
                            || (DistantNetworkDirectory.isFormalId(current) && !accept.networkId().equals(current))) {
                        PENDING.remove(accept.requestId());
                        notify(server, pending.player(), "message.distantstock.network.join_target_changed");
                        return DeliveryResult.REJECTED;
                    }
                }
                // Validate the target before writing a replica; a declined join must not create
                // a phantom entry in the local network list.
                boolean bound = pending.device() != null
                        ? bindDevice(server, pending.device(), accept.networkId())
                        : bindTerminal(player, pending.hand(), pending.terminal(), accept.networkId());
                if (!bound) return DeliveryResult.REJECTED;
                directory.rememberReplica(accept.networkId(), accept.networkName(), accept.ownerNode());
                // Binding a portable terminal is not the same as enrolling a warehouse. If the
                // terminal is already tuned to a local warehouse the player administers, enroll
                // that warehouse as part of the requested join. This lets VL4 advertise its stock
                // rather than exposing an empty network shell to the other server.
                enrollSelectedLocalWarehouse(server, player, heldTerminal(player, pending.hand()),
                        accept.networkId());
                dev.distantstock.stock.StockScanner.scan(server);
                if (player.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
                    menu.refresh(player);
                    syncTerminalMenu(player, menu);
                }
                PENDING.remove(accept.requestId());
                notify(server, pending.player(), "message.distantstock.network.joined", accept.networkName());
                if (pending.device() == null) {
                    dev.distantstock.net.DistantNetworkStateS2C.sendScope(player, accept.networkId());
                } else {
                    dev.distantstock.net.DistantDeviceStateS2C.send(player, pending.device());
                }
                return DeliveryResult.APPLIED;
            }
            UUID current = directory.formalNetworkOf(accept.member()).orElse(null);
            if (current != null && !current.equals(accept.networkId())) {
                PENDING.remove(accept.requestId());
                return DeliveryResult.REJECTED;
            }
            directory.rememberReplica(accept.networkId(), accept.networkName(), accept.ownerNode());
            if (!accept.networkId().equals(current) && !directory.attach(accept.member(), accept.networkId())) {
                return DeliveryResult.REJECTED;
            }
            PENDING.remove(accept.requestId());
            dev.distantstock.stock.StockScanner.scan(server);
            notify(server, pending.player(), "message.distantstock.network.joined", accept.networkName());
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player != null) {
                // /distantstock network join may run from chat with no open requester menu.
                // Persist the new scope on the same terminal rather than waiting for a menu
                // callback that will never run in that case.
                ItemStack terminal = dev.distantstock.item.RequesterFind.find(player);
                if (!terminal.isEmpty() && accept.member().equals(RequesterData.network(terminal).orElse(null))) {
                    RequesterData.setNetwork(terminal, accept.member(), accept.networkId());
                    player.getInventory().setChanged();
                }
                if (player.containerMenu instanceof dev.distantstock.menu.RequesterMenu menu) {
                    menu.persistDistantNetworkScope(player, accept.member(), accept.networkId());
                    menu.refresh(player);
                    syncTerminalMenu(player, menu);
                }
                dev.distantstock.net.DistantNetworkStateS2C.send(player, accept.member());
            }
            return DeliveryResult.APPLIED;
        });
    }

    private static <T> CompletableFuture<T> onServer(MinecraftServer server,
                                                              java.util.function.Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    result.complete(action.get());
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private static void acknowledgeCompleted() {
        var api = TranserverBridge.attachedApi();
        if (api == null) return;
        for (var completed : TranserverBridge.completedSends(256)) {
            if (RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST.equals(completed.channel())
                    || RoutingChannels.DISTANT_NETWORK_JOIN_ACCEPT.equals(completed.channel())) {
                TranserverBridge.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static void notify(MinecraftServer server, UUID playerId, String key, Object... args) {
        if (server == null || playerId == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            String fallback = switch (key) {
                case "message.distantstock.network.join_timeout" ->
                        "加入请求在 60 秒内未收到确认，请检查邀请码、主节点和 Transerver 队列后重试。";
                case "message.distantstock.network.join_target_changed" ->
                        "等待期间手持终端或其网络已改变，本次加入已取消，请重新发起。";
                default -> null;
            };
            player.displayClientMessage(fallback == null ? Component.translatable(key, args)
                    : Component.translatableWithFallback(key, fallback, args), false);
        }
    }

    private DistantNetworkJoinService() {
    }
}
