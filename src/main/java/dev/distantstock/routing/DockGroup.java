package dev.distantstock.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity, mutable display name, and who may use a group of receiving docks.
 *
 * <p>Renaming a group must not invalidate orders or parcels already in transit, so the name is only
 * ever a label: the id is the identity and nothing routes by name.
 *
 * <p><b>Members are the key.</b> A group admits its owner and the players the owner named, and
 * nobody else — a door into the warehouse, not a share of its ledgers. Everything that manages it
 * (renaming, opening and closing, deleting, naming more members) stays with the owner alone, which
 * is the split Create draws between using a logistics network and administrating one.
 *
 * <p><b>The lock is the way in, not the permission itself.</b> An open group is one anybody may add
 * themselves to; once they are on the list they are a member like any other, and closing the group
 * afterwards does not take that back. This is the model 运输蜂停泊港 uses, and it was copied on
 * purpose: the other reading — an open group that anybody may use without ever joining — makes the
 * member list decoration, and the member list is the thing that answers "who is using my warehouse".
 * A closed group admits only the names the owner wrote down, so the way in is to ask.
 *
 * <p>Names are kept beside the ids because a name is the only thing a server can show a player to
 * identify somebody by, and because the person let in is usually offline when the list is read.
 * They are a record, not a lookup key: a player who renames their account keeps the membership, and
 * their row is refreshed the next time the owner adds them.
 *
 * <p>Membership is per server. The other side of a crossing has its own directory and its own idea
 * of who may use a group, and none of this crosses with a parcel — the only thing the far end ever
 * learns about a group is the id that travels in an order and on the parcels of one.
 */
public record DockGroup(UUID id, String name, UUID owner, boolean open, Map<UUID, String> members) {
    public static final int MAX_NAME_LENGTH = 48;
    /** How many players one group may name. Caps the packet as much as it caps the list. */
    public static final int MAX_MEMBERS = 32;

    public DockGroup {
        Objects.requireNonNull(id, "id");
        name = normalizeName(name);
        if (members == null || members.isEmpty()) {
            members = Map.of();
        } else {
            // A row with no name is a row nobody can recognise, and the screen would draw a blank
            // where the player's name goes. Filled in here so every reader can assume a name.
            Map<UUID, String> named = new LinkedHashMap<>();
            members.forEach((member, memberName) -> named.put(member,
                    memberName == null || memberName.isBlank() ? "?" : memberName.trim()));
            members = Collections.unmodifiableMap(named);
        }
    }

    /** A group with nobody named in it: what every group was before members existed. */
    public DockGroup(UUID id, String name, UUID owner, boolean open) {
        this(id, name, owner, open, Map.of());
    }

    /**
     * Whether this player may add docks to the group or send parcels into it.
     *
     * <p>The owner, or a name on the list — and the lock is deliberately not consulted. An open
     * group is one anybody may *join*; it is not one anybody may skip the list with. Reading it the
     * other way is what made the member list decorative, and the list is the only thing that answers
     * "who is sending into my warehouse".
     */
    public boolean admits(UUID player) {
        if (owner == null) {
            // A group with no owner predates ownership, and one the server made through the admin
            // command has none either. Neither has anyone to keep out — and, just as important,
            // neither has a list for a player to join, so requiring one would lock players out of
            // the default group and with it every dock that was never configured.
            return true;
        }
        if (player == null) {
            return false;
        }
        return owner.equals(player) || members.containsKey(player);
    }

    /** Whether this player may rename it or change who it admits. */
    public boolean ownedBy(UUID player) {
        return owner != null && owner.equals(player);
    }

    /** Whether this player was named by the owner, rather than being the owner. */
    public boolean hasMember(UUID player) {
        return player != null && members.containsKey(player);
    }

    /**
     * Whether this player may let themselves in — 运输蜂停泊港 的「添加你自己」那一句。
     *
     * <p>An open group is one whose owner has already said yes to everybody, so the answer here is
     * the lock and nothing else. Being in already, and being the owner, are both refused for the
     * same reason: neither is a state that joining could change, and a "you joined" message for a
     * network you were already in reads as though something had been granted.
     *
     * <p>A group with no owner is not joinable either, which is a different point: its list means
     * nothing (everybody is admitted) and it has nobody to do the admitting. Names collected there
     * would be names on a list that decides nothing.
     *
     * <p>This is the rule the packet checks, so the screen and the server cannot disagree about who
     * may walk in.
     */
    public boolean joinableBy(UUID player) {
        return player != null && owner != null && open && !ownedBy(player) && !hasMember(player);
    }

    /** The name recorded for a player the owner let in, or null. */
    public String memberName(UUID player) {
        return player == null ? null : members.get(player);
    }

    /** Adds one player, keeping the order they were added in. Re-adding refreshes the name. */
    public DockGroup withMember(UUID player, String playerName) {
        if (player == null || owner != null && owner.equals(player)) {
            // The owner is in by definition; a row that could be removed would be a lie.
            return this;
        }
        if (!members.containsKey(player) && members.size() >= MAX_MEMBERS) {
            return this;
        }
        Map<UUID, String> next = new LinkedHashMap<>(members);
        next.put(player, playerName == null || playerName.isBlank() ? "?" : playerName.trim());
        return new DockGroup(id, name, owner, open, next);
    }

    public DockGroup withoutMember(UUID player) {
        if (!members.containsKey(player)) {
            return this;
        }
        Map<UUID, String> next = new LinkedHashMap<>(members);
        next.remove(player);
        return new DockGroup(id, name, owner, open, next);
    }

    public DockGroup rename(String newName) {
        return new DockGroup(id, newName, owner, open, members);
    }

    public DockGroup withOpen(boolean nextOpen) {
        return new DockGroup(id, name, owner, nextOpen, members);
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Dock group name must not be blank");
        }
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Dock group name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }
}
