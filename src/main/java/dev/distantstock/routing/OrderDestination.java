package dev.distantstock.routing;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * What the group an order names actually means.
 *
 * <p>One field on the terminal, two very different answers, and the answer is decided by which
 * server's directory the group lives in:
 *
 * <ul>
 *   <li>A group of <b>this</b> server's — the goods are delivered here, out of that group's docks.
 *   <li>A group brought in by a <b>pairing code</b>, which lives on the other server — the goods
 *       stay there and come out of its docks. That is how a player orders from somebody else's
 *       warehouse and has the goods handed to a player standing next to it.
 * </ul>
 *
 * <p>Extracted from the packet handler so the rule can be tested without a player clicking a
 * screen — and because the interesting case is the one that used to be silent: a group id nobody
 * recognises. It fell back to the default group, which matches <em>every</em> dock whose address is
 * blank, so a requester still holding a deleted group's id would quietly post the goods to whoever
 * happened to be listening. A destination nobody can name is not a destination.
 */
public final class OrderDestination {
    public enum Kind {
        /** No group asked for: the default group, on this server. */
        HERE_DEFAULT,
        /** A group of this server's, and the player may use it. */
        HERE,
        /** A group from the other server, learned from a pairing code. */
        THERE,
        /** A group of this server's that the player is not in. */
        REFUSED,
        /** A group nobody can name. */
        UNKNOWN
    }

    public record Answer(Kind kind, UUID group) {
        /** Whether the order may be placed at all. */
        public boolean allowed() {
            return kind != Kind.REFUSED && kind != Kind.UNKNOWN;
        }
    }

    public static Answer resolve(MinecraftServer server, UUID player, UUID asked) {
        if (server == null) {
            return new Answer(Kind.UNKNOWN, null);
        }
        if (asked == null || asked.equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return new Answer(Kind.HERE_DEFAULT, DockGroupDirectory.DEFAULT_GROUP_ID);
        }
        DockGroup group = DockGroupDirectory.get(server).find(asked).orElse(null);
        if (group == null) {
            return RemoteGroups.get(server).find(asked).isPresent()
                    ? new Answer(Kind.THERE, asked)
                    : new Answer(Kind.UNKNOWN, null);
        }
        return group.admits(player)
                ? new Answer(Kind.HERE, group.id())
                : new Answer(Kind.REFUSED, null);
    }

    private OrderDestination() {
    }
}
