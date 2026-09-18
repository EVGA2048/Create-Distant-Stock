package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.stock.NetworkDirectory;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import dev.transerver.api.TranserverApi;

public final class NetworkAnnouncementService {
    /**
     * How often the announcement is repeated even when nothing about it changed.
     *
     * <p>Announcing only on change was the whole of why the other server's networks disappeared
     * after five minutes: the receiver drops a peer it has not heard from in five minutes, and a
     * node whose network list never changes has no reason of its own to speak again. Well under
     * that timeout, and rare enough to be nothing on the wire.
     */
    private static final long REFRESH_MS = 45_000;

    private static List<NetworkDirectory.Entry> lastLocal = List.of();
    private static List<NetworkAnnouncementCodec.Group> lastGroups = List.of();
    private static Set<String> lastRecipients = Set.of();
    private static long lastSentAt;

    public static void register() {
        TranserverBridge.handler(RoutingChannels.NETWORK_ANNOUNCE, NetworkAnnouncementService::receive);
    }

    /**
     * Says what this node has, and how it is doing, to every other node.
     *
     * <p>Two reasons to speak. The list changing is worth saying at once — a network that appeared a
     * second ago should be joinable on the other server a second from now. And saying it again after
     * {@link #REFRESH_MS} of silence, because the receiver forgets a peer it has not heard from and
     * a node whose list never changes would otherwise go quiet for ever and be forgotten.
     *
     * <p>Compared by the list rather than by the payload: the payload also carries this node's tick
     * metrics, which change every second, so encoding equality would send an announcement on every
     * beat.
     */
    public static void publish() {
        acknowledgeCompleted();
        UUID self = TranserverBridge.nodeId();
        if (self == null) {
            return;
        }
        List<NetworkDirectory.Entry> local = NetworkDirectory.local().stream()
                .filter(entry -> entry.networkId() != null).toList();
        Set<String> recipients = TranserverBridge.knownNodes();
        if (recipients.isEmpty()) {
            return;
        }
        List<NetworkAnnouncementCodec.Group> groups = localGroups();
        long now = System.currentTimeMillis();
        boolean changed = !local.equals(lastLocal) || !groups.equals(lastGroups)
                || !recipients.equals(lastRecipients);
        if (!changed && now - lastSentAt < REFRESH_MS) {
            return;
        }
        try {
            byte[] payload = NetworkAnnouncementCodec.encode(local,
                    LinkSnapshot.localTps, LinkSnapshot.localMspt, groups);
            for (String node : recipients) {
                if (!node.equals(self.toString())) {
                    TranserverBridge.send(node, RoutingChannels.NETWORK_ANNOUNCE, payload, null);
                }
            }
            lastLocal = List.copyOf(local);
            lastGroups = List.copyOf(groups);
            lastRecipients = Set.copyOf(recipients);
            lastSentAt = now;
        } catch (IOException ignored) {
        }
    }

    /**
     * This node's dock groups, as they will be shown and judged on the other side.
     *
     * <p>Everything a destination needs and nothing that only makes sense here: the id to address
     * it, the name to draw, who keeps it, whether anybody may join it, how many docks are behind it,
     * and who is on the list. Membership travels so that the *sending* side can refuse an order from
     * somebody the group would not admit — the receiving server cannot check that itself, because an
     * order arrives with no player on it.
     *
     * <p>Dock counts are live, which is also why the announce beat exists: a group whose docks were
     * all broken is a destination that will not deliver, and the other server should stop drawing it
     * as though it would.
     */
    private static List<NetworkAnnouncementCodec.Group> localGroups() {
        MinecraftServer server = TranserverBridge.server();
        if (server == null) {
            return List.of();
        }
        List<NetworkAnnouncementCodec.Group> out = new java.util.ArrayList<>();
        for (dev.distantstock.routing.DockGroup group
                : dev.distantstock.routing.DockGroupDirectory.get(server).all()) {
            if (group.id().equals(dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID)) {
                // 默认组不公告。它不是谁能选的去处 —— 它是"这个港还没加入任何组"那个占位，选它等于
                // 没选（货发出去没有收件人）。对面看到它只会多一行点不得的名字。
                continue;
            }
            List<NetworkAnnouncementCodec.Group.Member> members = new java.util.ArrayList<>();
            group.members().forEach((id, name) -> members.add(
                    new NetworkAnnouncementCodec.Group.Member(id, name)));
            out.add(new NetworkAnnouncementCodec.Group(group.id(), group.name(), group.owner(),
                    group.open(), dev.distantstock.block.LoadedDocks.allInGroup(group.id()).size(),
                    List.copyOf(members)));
            if (out.size() >= 64) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static void acknowledgeCompleted() {
        TranserverApi api = TranserverBridge.attachedApi();
        if (api == null) {
            return;
        }
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.NETWORK_ANNOUNCE.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        try {
            UUID source = UUID.fromString(message.source());
            List<NetworkDirectory.Entry> entries = NetworkAnnouncementCodec.decode(message.payload());
            if (entries.stream().anyMatch(entry -> !entry.networkId().nodeId().equals(source))) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
            MinecraftServer server = TranserverBridge.server();
            if (server == null || !server.isRunning()) {
                return CompletableFuture.completedFuture(DeliveryResult.RETRY);
            }
            NetworkAnnouncementCodec.Metrics metrics = NetworkAnnouncementCodec.metrics(message.payload());
            // Read out of the codec's last-decode slot, the same way the metrics are: one walk of
            // the payload, and the fields a caller may want are kept beside the list it returns.
            List<NetworkAnnouncementCodec.Group> groups = NetworkAnnouncementCodec.groups(message.payload());
            CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
            server.execute(() -> {
                NetworkDirectory.replacePeer(message.source(), entries);
                // The announcer names itself on every entry it sends. Written down here so the
                // readouts that mention another node — a remote dock group, the second line on a
                // crossing parcel — can say "远仓B" instead of a uuid prefix.
                String alias = entries.stream().map(NetworkDirectory.Entry::server)
                        .filter(name -> name != null && !name.isBlank())
                        .findFirst().orElse("");
                if (!alias.isBlank()) {
                    dev.distantstock.routing.PeerNames.get(server).remember(source, alias);
                }
                rememberGroups(server, source, alias, groups);

                if (metrics.known()) {
                    // This is where a peer's TPS comes from in Transerver mode: nothing else crosses
                    // on a regular beat, and the monitor shows the number.
                    LinkSnapshot.peerMetrics(message.source(), metrics.tps(), metrics.mspt());
                }
                result.complete(DeliveryResult.APPLIED);
            });
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
    }

    /**
     * Writes the announced groups into this server's list of other servers' groups.
     *
     * <p>This is what a pairing code used to do, by hand and once: a group on the far side becomes
     * a destination here, with the name its owner gave it and the node's own name in front of it.
     * Doing it on every announcement instead means a rename over there is a rename here, and a
     * group that no longer exists stops being offered rather than staying in the list for ever.
     *
     * <p>An announcement that carries no groups still replaces: the peer is telling the truth about
     * an empty list, and the last thing to do with that is keep offering the groups it used to have.
     */
    private static void rememberGroups(MinecraftServer server, UUID node, String alias,
                                       List<NetworkAnnouncementCodec.Group> groups) {
        dev.distantstock.routing.RemoteGroups directory =
                dev.distantstock.routing.RemoteGroups.get(server);
        String label = alias == null || alias.isBlank()
                ? node.toString().substring(0, 8) : alias;
        List<dev.distantstock.routing.RemoteGroups.Entry> rows = new java.util.ArrayList<>();
        for (NetworkAnnouncementCodec.Group group : groups) {
            Map<UUID, String> members = new java.util.LinkedHashMap<>();
            for (NetworkAnnouncementCodec.Group.Member member : group.members()) {
                members.put(member.id(), member.name());
            }
            rows.add(new dev.distantstock.routing.RemoteGroups.Entry(node, group.id(), group.name(),
                    label, 0, group.open(), group.owner(), Map.copyOf(members), group.docks()));
        }
        directory.replaceFrom(node, rows, System.currentTimeMillis());
    }

    private NetworkAnnouncementService() {
    }
}
