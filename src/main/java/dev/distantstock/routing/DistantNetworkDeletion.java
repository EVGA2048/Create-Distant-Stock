package dev.distantstock.routing;

import dev.distantstock.block.LoadedDocks;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockScanner;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One authoritative cleanup path for a deleted Distant Stock network, local or remote. */
public final class DistantNetworkDeletion {
    public record Result(String name, int members, int groups, int loadedDocks) {
    }

    public static Result apply(MinecraftServer server, UUID networkId) {
        if (server == null || !DistantNetworkDirectory.isFormalId(networkId)) return null;
        DistantNetworkDirectory directory = DistantNetworkDirectory.get(server);
        var network = directory.find(networkId).orElse(null);
        if (network == null) return null;

        DockGroupDirectory groups = DockGroupDirectory.get(server);
        List<DockGroup> ownedGroups = groups.all().stream()
                .filter(group -> networkId.equals(group.distantNetworkId()))
                .toList();
        int loadedDocks = 0;
        for (DockGroup group : ownedGroups) {
            var docks = LoadedDocks.allInGroup(group.id());
            loadedDocks += docks.size();
            for (var dock : docks) {
                dock.setGroupId(DockGroupDirectory.DEFAULT_GROUP_ID);
            }
            groups.delete(group.id());
        }

        List<RemoteNetworkId> detached = directory.delete(networkId);
        List<NetworkDirectory.Entry> live = new ArrayList<>();
        for (var entry : NetworkDirectory.local()) {
            if (networkId.equals(entry.distantNetworkId())) {
                live.add(new NetworkDirectory.Entry(entry.freq(), entry.server(), entry.links(),
                        entry.networkId(), entry.local(), entry.packable(),
                        DistantNetworkDirectory.LEGACY_NETWORK_ID, entry.warehouseName()));
            } else {
                live.add(entry);
            }
        }
        NetworkDirectory.replaceLocal(live);
        StockScanner.scan(server);
        return new Result(network.name(), detached.size(), ownedGroups.size(), loadedDocks);
    }

    private DistantNetworkDeletion() {
    }
}
