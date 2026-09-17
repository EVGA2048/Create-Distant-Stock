package dev.distantstock.link;

import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.PlayerNames;
import dev.distantstock.routing.RemoteGroups;
import dev.distantstock.routing.RemoteRoute;
import net.minecraft.server.MinecraftServer;

/**
 * Where a parcel is going, in words a player can read.
 *
 * <p>The route itself is two uuids and a schema number, and the parcel's tooltip printed the first
 * eight characters of each: "→ 5d2a90b4 · ca029954". Neither half names anything. This is the same
 * route said out loud — "远仓B · 甲服仓库" — which is what the second line of that tooltip was for.
 *
 * <p>Written onto the parcel when the route is, rather than looked up when the tooltip is drawn:
 * the tooltip runs on the client, where neither directory exists, and a parcel has to be able to
 * say where it is going while it sits in a chest on a server nobody is looking at.
 */
public final class RouteLabels {
    /**
     * The destination as a place: which server, then which group on it.
     *
     * <p>Each half falls back on its own. A group nobody here has heard of — one from a peer that
     * has stopped announcing it, or that this server hid — still leaves the server's name, and a
     * node that has never announced itself leaves the group's.
     */
    public static String describe(MinecraftServer server, RemoteRoute route) {
        if (server == null || route == null) {
            return "";
        }
        String node = dev.distantstock.routing.PeerNames.label(server, route.destinationNodeId());
        String group = groupName(server, route.receivingDockGroupId());
        if (group.isEmpty()) {
            return node;
        }
        return node.isEmpty() ? group : node + " · " + group;
    }

    /**
     * The group's name: its own if this server holds it, the remembered one if a peer announced
     * it, and its uuid prefix if it is neither — a route can name a group this server has no
     * record of, and that is not an error, it is simply something it cannot name.
     */
    private static String groupName(MinecraftServer server, java.util.UUID group) {
        if (group == null) {
            return "";
        }
        var local = DockGroupDirectory.get(server).find(group);
        if (local.isPresent()) {
            return local.get().name();
        }
        return RemoteGroups.get(server).find(group)
                .map(RemoteGroups.Entry::name)
                .orElse(PlayerNames.shortId(group));
    }

    private RouteLabels() {
    }
}
