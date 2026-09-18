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
 *   <li>A group of the <b>other</b> server's, learned from its announcement — the goods stay there
 *       and come out of its docks. That is how a player orders from somebody else's warehouse and
 *       has the goods handed to a player standing next to it.
 * </ul>
 *
 * <p>Extracted from the packet handler so the rule can be tested without a player clicking a
 * screen — and because the interesting case is the one that used to be silent: a group id nobody
 * recognises. It fell back to the default group, which matches <em>every</em> dock whose address is
 * blank, so a requester still holding a deleted group's id would quietly post the goods to whoever
 * happened to be listening. A destination nobody can name is not a destination.
 *
 * <p><b>Both halves are judged here, and only here.</b> A local group has always been checked
 * against its own member list. A remote one could not be: the order arrives on the other server with
 * no player on it, so that server has nobody to check. What it can do — and now does — is send its
 * member list with every announcement, which puts the judgement back on this side, at the one moment
 * a player is attached to the order. A peer that has never said who is in a group leaves no opinion
 * here, and nothing is refused on a guess.
 */
public final class OrderDestination {
    public enum Kind {
        /**
         * 没选组 —— 空的 id，或者那个"默认收货港组"占位。
         *
         * <p>以前这一档是放行的：落在本服的默认组，也就是"哪个港没加入组就哪个港收"。玩家
         * 2026-09-18 报的就是它 ——「默认港组容易出事，发的东西都进虚空了」：默认组等于没有收件人，
         * 货发出去谁都不认，最后就没了。现在**必须新建或加入一个组**才发得出去，这一档改成拒绝。
         */
        NO_GROUP,
        /** A group of this server's, and the player may use it. */
        HERE,
        /** A group on another server, learned from its announcement, that this player may use. */
        THERE,
        /** A group of this server's that the player is not in. */
        REFUSED,
        /** A group nobody can name. */
        UNKNOWN
    }

    public record Answer(Kind kind, UUID group) {
        /** Whether the order may be placed at all. */
        public boolean allowed() {
            return kind == Kind.HERE || kind == Kind.THERE;
        }
    }

    public static Answer resolve(MinecraftServer server, UUID player, UUID asked) {
        if (server == null) {
            return new Answer(Kind.UNKNOWN, null);
        }
        if (asked == null || asked.equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            // 没选组不放行 —— 见 {@link Kind#NO_GROUP}。这是"货进虚空"那条路的入口。
            return new Answer(Kind.NO_GROUP, null);
        }
        DockGroup group = DockGroupDirectory.get(server).find(asked).orElse(null);
        if (group == null) {
            // 不在本服目录里：看看是不是对面服务器公告过来的组。是的话，用**对面那份名单**判这个人
            // 能不能用 —— 跨服的订单上不带玩家，对面判不了，这边是唯一判得了的地方。
            var remote = RemoteGroups.get(server).find(asked).orElse(null);
            if (remote == null) {
                return new Answer(Kind.UNKNOWN, null);
            }
            return remote.admits(player)
                    ? new Answer(Kind.THERE, asked)
                    : new Answer(Kind.REFUSED, null);
        }
        return group.admits(player)
                ? new Answer(Kind.HERE, group.id())
                : new Answer(Kind.REFUSED, null);
    }

    private OrderDestination() {
    }
}
