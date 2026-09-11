package dev.distantstock.routing;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stable identity of one Create logistics network on one world and node. */
public record RemoteNetworkId(int schemaVersion, UUID nodeId, UUID worldId,
                              String dimensionId, UUID createFrequency) {
    public static final int CURRENT_SCHEMA = 1;

    public RemoteNetworkId {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported remote network schema: " + schemaVersion);
        }
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(createFrequency, "createFrequency");
        if (dimensionId == null || dimensionId.isBlank() || dimensionId.length() > 256) {
            throw new IllegalArgumentException("dimensionId is invalid");
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", schemaVersion);
        tag.putUUID("Node", nodeId);
        tag.putUUID("World", worldId);
        tag.putString("Dimension", dimensionId);
        tag.putUUID("Frequency", createFrequency);
        return tag;
    }

    public static Optional<RemoteNetworkId> read(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Node") || !tag.hasUUID("World") || !tag.hasUUID("Frequency")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RemoteNetworkId(tag.getInt("Schema"), tag.getUUID("Node"),
                    tag.getUUID("World"), tag.getString("Dimension"), tag.getUUID("Frequency")));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String shortLabel() {
        return nodeId.toString().substring(0, 8) + "/" + worldId.toString().substring(0, 8)
                + "/" + createFrequency.toString().substring(0, 8);
    }
}
