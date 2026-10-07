package dev.distantstock.block;

import java.util.List;
import java.util.Map;

/** Pure recipient-selection checks; no running game server is required. */
public final class NetworkBroadcastBusCheck {
    public static void main(String[] args) {
        overlappingReceiversDeliverOnceFromNearest();
        partiallyOverlappingReceiversKeepEveryUniquePlayer();
        disjointReceiversKeepTheirOwnPlayers();
    }

    private static void overlappingReceiversDeliverOnceFromNearest() {
        Receiver west = new Receiver("west", 0, 48);
        Receiver east = new Receiver("east", 20, 48);
        Player player = new Player("overlap", 15);

        Map<Player, Receiver> selected = select(List.of(west, east), List.of(player));

        require(selected.size() == 1, "overlapping receiver ranges duplicated a player");
        require(selected.get(player) == east, "overlapping player did not select the nearest receiver");
    }

    private static void partiallyOverlappingReceiversKeepEveryUniquePlayer() {
        Receiver west = new Receiver("west", 0, 10);
        Receiver east = new Receiver("east", 15, 10);
        Player westOnly = new Player("west-only", -5);
        Player overlap = new Player("overlap", 8);
        Player eastOnly = new Player("east-only", 20);
        Player outside = new Player("outside", 40);

        Map<Player, Receiver> selected = select(
                List.of(west, east), List.of(westOnly, overlap, eastOnly, outside));

        require(selected.size() == 3, "partial overlap lost or duplicated a covered player");
        require(selected.get(westOnly) == west, "west-only player selected the wrong receiver");
        require(selected.get(overlap) == east, "overlap player did not select the nearest receiver");
        require(selected.get(eastOnly) == east, "east-only player selected the wrong receiver");
        require(!selected.containsKey(outside), "player outside every range received a broadcast");
    }

    private static void disjointReceiversKeepTheirOwnPlayers() {
        Receiver west = new Receiver("west", 0, 5);
        Receiver east = new Receiver("east", 20, 5);
        Player westPlayer = new Player("west-player", 0);
        Player eastPlayer = new Player("east-player", 20);

        Map<Player, Receiver> selected = select(List.of(west, east), List.of(westPlayer, eastPlayer));

        require(selected.size() == 2, "disjoint receiver ranges changed the recipient count");
        require(selected.get(westPlayer) == west, "west player selected the wrong receiver");
        require(selected.get(eastPlayer) == east, "east player selected the wrong receiver");
    }

    private static Map<Player, Receiver> select(List<Receiver> receivers, List<Player> players) {
        return NetworkBroadcastBus.selectNearest(receivers, players,
                (receiver, player) -> {
                    double distance = receiver.x() - player.x();
                    return distance * distance;
                },
                Receiver::radius);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Receiver(String name, double x, int radius) {
    }

    private record Player(String name, double x) {
    }

    private NetworkBroadcastBusCheck() {
    }
}
