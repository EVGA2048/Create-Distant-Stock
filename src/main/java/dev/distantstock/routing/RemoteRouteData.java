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
    private static final String CROSS_SERVER = "CrossServer";
    /**
     * Where the route goes, in words: "远仓B · 甲服仓库".
     *
     * <p>Written here because here is the only place that can write it — the directories that turn
     * a group id into a name live on the server, and the tooltip that reads this runs on a client
     * that has neither. Optional: a parcel routed by an older build has no label and falls back on
     * the ids.
     */
    private static final String LABEL = "DestinationLabel";
    private static final int MAX_LABEL = 96;

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

    /**
     * Marks a parcel as one that is going to another server rather than to a dock on this one.
     *
     * <p>Set where the parcel leaves a dock, because that is the last place that knows. It is what
     * lets the tooltip tell the truth about a parcel standing in a chest: "this one crosses" is a
     * different sentence from "this belongs to a group over here", and the route alone cannot say
     * which — both carry a destination node, and one of them is this server.
     */
    public static void markCrossServer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!custom.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag route = custom.getCompound(ROOT);
        route.putBoolean(CROSS_SERVER, true);
        custom.put(ROOT, route);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
    }

    /** The destination in words, or empty when the parcel carries no label. */
    public static String label(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return custom.contains(ROOT, CompoundTag.TAG_COMPOUND)
                ? custom.getCompound(ROOT).getString(LABEL) : "";
    }

    /** Whether this parcel is on its way to another server, as far as this side can tell. */
    public static boolean crossServer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return custom.contains(ROOT, CompoundTag.TAG_COMPOUND)
                && custom.getCompound(ROOT).getBoolean(CROSS_SERVER);
    }

    public static void write(ItemStack stack, RemoteRoute value) {
        write(stack, value, "");
    }

    /**
     * The same, with the destination spelled out for the player who will read the tooltip.
     *
     * <p>A blank label is written as no label rather than as an empty string, so a caller that has
     * nothing to say leaves the parcel exactly as the id-only version would have.
     */
    public static void write(ItemStack stack, RemoteRoute value, String label) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot route an empty item stack");
        }
        String described = label == null ? "" : label.trim();
        if (described.length() > MAX_LABEL) {
            described = described.substring(0, MAX_LABEL);
        }
        String written = described;
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag custom = current.copyTag();
            CompoundTag route = new CompoundTag();
            route.putInt(SCHEMA, value.schemaVersion());
            route.putUUID(DESTINATION_NODE, value.destinationNodeId());
            route.putUUID(RECEIVING_GROUP, value.receivingDockGroupId());
            route.putUUID(CORRELATION, value.correlationId());
            route.putUUID(CHILD_ORDER, value.childOrderId());
            if (!written.isEmpty()) {
                route.putString(LABEL, written);
            }
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
