package dev.distantstock.routing;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/** Stores stable cross-server routing on the package without touching its Create address. */
public final class RemoteRouteData {
    private static final String ROOT = "DistantStockRoute";
    private static final String SCHEMA = "Schema";
    private static final String DESTINATION_NODE = "DestinationNode";
    private static final String RECEIVING_GROUP = "ReceivingDockGroup";
    private static final String CORRELATION = "Correlation";
    private static final String CHILD_ORDER = "ChildOrder";

    public static Optional<RemoteRoute> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!custom.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag route = custom.getCompound(ROOT);
        if (!route.contains(SCHEMA, CompoundTag.TAG_INT)
                || !route.hasUUID(DESTINATION_NODE)
                || !route.hasUUID(RECEIVING_GROUP)
                || !route.hasUUID(CORRELATION)
                || !route.hasUUID(CHILD_ORDER)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RemoteRoute(
                    route.getInt(SCHEMA),
                    route.getUUID(DESTINATION_NODE),
                    route.getUUID(RECEIVING_GROUP),
                    route.getUUID(CORRELATION),
                    route.getUUID(CHILD_ORDER)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static void write(ItemStack stack, RemoteRoute value) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot route an empty item stack");
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag custom = current.copyTag();
            CompoundTag route = new CompoundTag();
            route.putInt(SCHEMA, value.schemaVersion());
            route.putUUID(DESTINATION_NODE, value.destinationNodeId());
            route.putUUID(RECEIVING_GROUP, value.receivingDockGroupId());
            route.putUUID(CORRELATION, value.correlationId());
            route.putUUID(CHILD_ORDER, value.childOrderId());
            custom.put(ROOT, route);
            return CustomData.of(custom);
        });
    }

    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag custom = current.copyTag();
            custom.remove(ROOT);
            return CustomData.of(custom);
        });
    }

    private RemoteRouteData() {
    }
}
