package dev.distantstock.routing;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity, mutable display name, and who may use a group of receiving docks.
 *
 * <p>Renaming a group must not invalidate orders or parcels already in transit, so the name is only
 * ever a label: the id is the identity and nothing routes by name.
 *
 * <p><b>Ownership is the lock.</b> A group names the player who made it and whether it stands open
 * to everyone else. Closing one does not lock the docks already in it — they are where they are —
 * it stops anyone else from adding a dock of their own or pointing a sender at it. That is the
 * question a player actually asks: not "who can see my group" but "who can make their parcels come
 * out of my dock".
 */
public record DockGroup(UUID id, String name, UUID owner, boolean open) {
    public static final int MAX_NAME_LENGTH = 48;

    public DockGroup {
        Objects.requireNonNull(id, "id");
        name = normalizeName(name);
    }

    /** Whether this player may add docks to the group or send parcels into it. */
    public boolean admits(UUID player) {
        if (open || owner == null) {
            // A group with no owner predates ownership, and one the server made through the admin
            // command has none either. Neither has anyone to keep out.
            return true;
        }
        return owner.equals(player);
    }

    /** Whether this player may rename it or change who it admits. */
    public boolean ownedBy(UUID player) {
        return owner != null && owner.equals(player);
    }

    public DockGroup rename(String newName) {
        return new DockGroup(id, newName, owner, open);
    }

    public DockGroup withOpen(boolean nextOpen) {
        return new DockGroup(id, name, owner, nextOpen);
    }

    public DockGroup withOwner(UUID nextOwner) {
        return new DockGroup(id, name, nextOwner, open);
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
