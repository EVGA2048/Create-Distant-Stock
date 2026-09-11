package dev.distantstock.routing;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity and mutable display name for a group of receiving docks.
 * Renaming a group must not invalidate orders or parcels already in transit.
 */
public record DockGroup(UUID id, String name) {
    public static final int MAX_NAME_LENGTH = 48;

    public DockGroup {
        Objects.requireNonNull(id, "id");
        name = normalizeName(name);
    }

    public DockGroup rename(String newName) {
        return new DockGroup(id, newName);
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
