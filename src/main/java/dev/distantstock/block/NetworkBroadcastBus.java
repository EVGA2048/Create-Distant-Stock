package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public static int send(UUID network, String text, int soundProfile) {
        if (network == null || text == null || text.isBlank()) return 0;
        Map<ServerLevel, List<Receiver>> receiversByLevel = new LinkedHashMap<>();
        for (LogisticallyLinkedBehaviour link : LogisticallyLinkedBehaviour.getAllPresent(network, false)) {
            if (!(link.blockEntity instanceof Receiver receiver)) continue;
            ServerLevel level = receiver.broadcastLevel();
            if (level == null) continue;
            receiversByLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(receiver);
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
