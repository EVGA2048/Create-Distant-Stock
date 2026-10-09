package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntFunction;

/** Dispatches one broadcast to loaded output devices on the same Create logistics network. */
public final class NetworkBroadcastBus {
    public interface Receiver {
        ServerLevel broadcastLevel();
        BlockPos broadcastPosition();
        int broadcastRadius();
        void deliverNetworkBroadcast(ServerPlayer player, String text, int soundProfile);
    }

    /** One dispatch, identified precisely enough to recognise a repeat of the very same one. */
    private record Dispatch(ResourceKey<Level> dimension, long gameTime, UUID network,
                            String text, int soundProfile) {
    }

    private static final Set<Dispatch> SENT = new HashSet<>();
    private static long lastTick = Long.MIN_VALUE;

    /**
     * One lever can switch several broadcasters on one frequency at once, and each of those blocks
     * sees its own redstone edge and dispatches separately. {@link #selectNearest} stops a single
     * dispatch from reaching one player twice through two overlapping speakers, but it cannot see
     * across dispatches — so the whole line is still delivered once per broadcaster that fired.
     *
     * The guard is deliberately scoped to one game tick, not to the text. Repeating the same words
     * on purpose — a lever flipped off and on again — has to keep working, and the flashes that
     * land in the same tick are the ones that were never separate events to begin with. The set is
     * emptied whenever the tick advances, so it never holds more than the broadcasters that fired
     * together.
     */
    public static int send(Level level, UUID network, String text, int soundProfile) {
        if (network == null || text == null || text.isBlank()) return 0;
        if (level == null) return 0;
        long gameTime = level.getGameTime();
        if (gameTime != lastTick) {
            SENT.clear();
            lastTick = gameTime;
        }
        if (!SENT.add(new Dispatch(level.dimension(), gameTime, network, text, soundProfile))) return 0;

        Map<ServerLevel, List<Receiver>> receiversByLevel = new LinkedHashMap<>();
        for (LogisticallyLinkedBehaviour link : LogisticallyLinkedBehaviour.getAllPresent(network, false)) {
            if (!(link.blockEntity instanceof Receiver receiver)) continue;
            ServerLevel receiverLevel = receiver.broadcastLevel();
            if (receiverLevel == null) continue;
            receiversByLevel.computeIfAbsent(receiverLevel, ignored -> new ArrayList<>()).add(receiver);
        }

        int delivered = 0;
        for (var entry : receiversByLevel.entrySet()) {
            Map<ServerPlayer, Receiver> recipients = selectNearest(
                    entry.getValue(), entry.getKey().players(),
                    (receiver, player) -> player.distanceToSqr(
                            receiver.broadcastPosition().getX() + .5,
                            receiver.broadcastPosition().getY() + .5,
                            receiver.broadcastPosition().getZ() + .5),
                    Receiver::broadcastRadius);
            recipients.forEach((player, receiver) ->
                    receiver.deliverNetworkBroadcast(player, text, soundProfile));
            delivered += recipients.size();
        }
        return delivered;
    }

    static <P, R> Map<P, R> selectNearest(Iterable<R> receivers, Iterable<P> players,
                                           ToDoubleBiFunction<R, P> distanceSquared,
                                           ToIntFunction<R> radius) {
        Map<P, Candidate<R>> selected = new LinkedHashMap<>();
        for (R receiver : receivers) {
            double maxDistance = (double) radius.applyAsInt(receiver) * radius.applyAsInt(receiver);
            for (P player : players) {
                double distance = distanceSquared.applyAsDouble(receiver, player);
                if (distance > maxDistance) continue;
                Candidate<R> current = selected.get(player);
                if (current == null || distance < current.distanceSquared()) {
                    selected.put(player, new Candidate<>(receiver, distance));
                }
            }
        }

        Map<P, R> result = new LinkedHashMap<>();
        selected.forEach((player, candidate) -> result.put(player, candidate.receiver()));
        return result;
    }

    private record Candidate<R>(R receiver, double distanceSquared) {
    }

    private NetworkBroadcastBus() {
    }
}
