package dev.distantstock.link;

import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.PairingCodes;
import dev.distantstock.routing.RemoteGroups;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redeeming a pairing code, from the player who types it to the group it names.
 *
 * <p>Two servers are involved and neither can see the other's files. The player is on the server
 * that wants a destination; the code was minted on the server that owns the group. So the ask goes
 * out to every node this one knows — the code carries no server id, and asking all of them costs
 * one small message each — and whichever server minted it answers. A server that has never heard of
 * the code stays silent, which is why the timeout is reported as "nobody recognised it" rather than
 * as a failure.
 *
 * <p><b>Codes are redeemed here, not in the screen.</b> The screen sends a string; everything below
 * runs on the server thread or on the link's own threads with an {@code execute} back onto the
 * server, and the player is told the outcome in chat. A redemption that succeeded is written into
 * {@link RemoteGroups} before the player is told about it, so a restart between the two cannot lose
 * a destination the player has already been promised.
 */
public final class PairingService {
    private static final Logger LOG = LogManager.getLogger();

    /**
     * How long a redemption waits for an answer.
     *
     * <p>Generous for one hop and short enough that nobody sits watching a screen: both servers are
     * up (the player is asking one of them right now), so a healthy link answers in well under a
     * second. What the wait covers is the other server being down or its inbox backed up.
     */
    private static final long TIMEOUT_MILLIS = 10_000;

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(UUID playerId, String playerName, String code, long deadline) {
    }

    public static void register() {
        TranserverBridge.handler(RoutingChannels.PAIR_CLAIM, PairingService::claim);
        TranserverBridge.handler(RoutingChannels.PAIR_GRANT, PairingService::grant);
    }

    /** Drops redemptions nobody answered, and says so to the player who asked. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : Map.copyOf(PENDING).entrySet()) {
            Pending pending = entry.getValue();
            if (pending.deadline() > now || !PENDING.remove(entry.getKey(), pending)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.pair.no_answer", pending.code()), false);
            }
        }
    }

    /**
     * Asks for a code: first here, then across the link.
     *
     * <p>This server is asked first because it may be the one that minted it — two players on one
     * server can use the codes among themselves, and a player testing the feature alone should not
     * have to stand up a second server to find out it works. A code that belongs to this server is
     * refused rather than turned into a destination: its group is already in the local directory,
     * and a local group is gated by {@code DockGroup.admits} when an order is placed. Honouring the
     * code here would mean a destination that is selectable and then refused at the moment it
     * matters, which is worse than saying so now.
     */
    public static void redeem(MinecraftServer server, Player player, String typed) {
        String code = PairingCodes.normalize(typed);
        if (code.isEmpty()) {
            player.displayClientMessage(Component.translatable("gui.distantstock.pair.empty"), false);
            return;
        }
        if (!PairingCodes.wellFormed(code)) {
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.pair.malformed", PairingCodes.CODE_LENGTH), false);
            return;
        }
        DockGroupDirectory directory = DockGroupDirectory.get(server);
        var local = PairingCodes.get(server).claim(directory, code, System.currentTimeMillis());
        if (local.isPresent()) {
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.pair.local", local.get().name()), false);
            return;
        }
        var nodes = TranserverBridge.knownNodes();
        if (nodes.isEmpty()) {
            player.displayClientMessage(Component.translatable("gui.distantstock.pair.no_link"), false);
            return;
        }
        UUID correlationId = UUID.randomUUID();
        Pending pending = new Pending(player.getUUID(), player.getName().getString(), code,
                System.currentTimeMillis() + TIMEOUT_MILLIS);
        // Claimed for this correlation before anything is sent: an answer can arrive on the link's
        // thread the moment the first message is out, and a reply with nowhere to land would be
        // dropped as a stranger's.
        PENDING.put(correlationId, pending);
        int sent = 0;
        for (String node : nodes) {
            try {
                byte[] payload = PairingWireCodec.encodeClaim(
                        new PairingWireCodec.Claim(correlationId, code, pending.playerName()));
                if (TranserverBridge.send(node, RoutingChannels.PAIR_CLAIM, payload,
                        correlationId.toString()) != null) {
                    sent++;
                }
            } catch (IOException | RuntimeException ignored) {
                // One node refusing the message is not the whole answer: the one that minted the
                // code may well be a different one, and it is still going to be asked.
            }
        }
        if (sent == 0) {
            PENDING.remove(correlationId);
            player.displayClientMessage(Component.translatable("gui.distantstock.pair.no_link"), false);
        }
    }

    /**
     * The minting server's side: does this code exist, and is it still good?
     *
     * <p>Answered on the server thread because claiming touches a saved file. A refusal carries its
     * reason: "spent" and "expired" and "no such code" are three different things for the player to
     * do next, and only this server can tell them apart.
     */
    private static CompletableFuture<DeliveryResult> claim(ReceivedMessage message) {
        final PairingWireCodec.Claim claim;
        try {
            claim = PairingWireCodec.decodeClaim(message.payload());
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> answered = new CompletableFuture<>();
        server.execute(() -> {
            UUID node = TranserverBridge.nodeId();
            if (node == null) {
                answered.complete(DeliveryResult.RETRY);
                return;
            }
            var grant = PairingCodes.get(server).claim(DockGroupDirectory.get(server), claim.code(),
                    System.currentTimeMillis());
            PairingWireCodec.Grant reply;
            if (grant.isPresent()) {
                PairingCodes.Grant found = grant.get();
                reply = new PairingWireCodec.Grant(claim.correlationId(), true, node, found.group(),
                        found.name(), "");
            } else {
                reply = PairingWireCodec.Grant.refused(claim.correlationId(), node,
                        PairingCodes.wellFormed(claim.code()) ? "unknown" : "malformed");
            }
            try {
                byte[] payload = PairingWireCodec.encodeGrant(reply);
                UUID sent = TranserverBridge.send(message.source(), RoutingChannels.PAIR_GRANT, payload,
                        claim.correlationId().toString());
                answered.complete(sent == null ? DeliveryResult.RETRY : DeliveryResult.APPLIED);
            } catch (IOException | RuntimeException exception) {
                answered.complete(DeliveryResult.RETRY);
            }
        });
        return answered;
    }

    /** The asking server's side: the first accepted answer wins, and the destination is written. */
    private static CompletableFuture<DeliveryResult> grant(ReceivedMessage message) {
        final PairingWireCodec.Grant grant;
        try {
            grant = PairingWireCodec.decodeGrant(message.payload());
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        // Removed here rather than after the world work: two servers can both answer — a code can
        // be claimed from the wrong node in a race, and the second answer must not overwrite the
        // first destination or send a second message.
        Pending pending = PENDING.remove(grant.correlationId());
        if (pending == null) {
            return CompletableFuture.completedFuture(DeliveryResult.APPLIED);
        }
        CompletableFuture<DeliveryResult> applied = new CompletableFuture<>();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (grant.accepted()) {
                RemoteGroups.get(server).add(new RemoteGroups.Entry(grant.nodeId(), grant.groupId(),
                        grant.groupName(), label(grant.nodeId(), server), 0), System.currentTimeMillis());
                if (player != null) {
                    player.displayClientMessage(Component.translatable("gui.distantstock.pair.paired",
                            grant.groupName(), label(grant.nodeId(), server)), false);
                }
            } else if (player != null) {
                // The reason travels on the wire so it can be logged and, one day, shown; what the
                // player needs is the one thing this message cannot be confused with — a server did
                // answer, and it said no. "Nobody recognised the code" is a different problem.
                LOG.info("[DistantStock/Pair] refused code={} node={} reason={}",
                        pending.code(), grant.nodeId(), grant.reason());
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.pair.refused", pending.code()), false);
            }
            applied.complete(DeliveryResult.APPLIED);
        });
        return applied;
    }

    /**
     * What a node is called on a screen.
     *
     * <p>Transerver identifies nodes by UUID and hands out no display name, so the first eight
     * characters are the most a player can be given for the server itself. The group's own name
     * travels with it and is written by whoever owns it, so the pair reads as a place.
     */
    public static String label(UUID node, MinecraftServer server) {
        if (node == null) {
            return "";
        }
        if (server != null && node.toString().equals(TranserverBridge.localNodeId())) {
            return "本服";
        }
        return node.toString().substring(0, 8);
    }

    private PairingService() {
    }
}
