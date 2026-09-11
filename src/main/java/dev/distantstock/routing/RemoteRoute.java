package dev.distantstock.routing;

import java.util.Objects;
import java.util.UUID;

/**
 * Logical route stored by Distant Stock. Physical addresses such as IPs,
 * domains and Router URLs deliberately do not belong here.
 */
public record RemoteRoute(
        int schemaVersion,
        UUID destinationNodeId,
        UUID receivingDockGroupId,
        UUID correlationId,
        UUID childOrderId
) {
    public static final int CURRENT_SCHEMA = 1;

    public RemoteRoute {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported remote route schema: " + schemaVersion);
        }
        Objects.requireNonNull(destinationNodeId, "destinationNodeId");
        Objects.requireNonNull(receivingDockGroupId, "receivingDockGroupId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(childOrderId, "childOrderId");
    }

    public static RemoteRoute create(UUID destinationNodeId, UUID receivingDockGroupId) {
        return new RemoteRoute(CURRENT_SCHEMA, destinationNodeId, receivingDockGroupId,
                UUID.randomUUID(), UUID.randomUUID());
    }
}
