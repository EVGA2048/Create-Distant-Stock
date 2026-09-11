package dev.distantstock.routing;

import com.simibubi.create.content.logistics.packager.PackagingRequest;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persists the route belonging to a Create order until its packages reach a remote dock. */
public final class OrderRouteDirectory extends SavedData {
    private static final String DATA_NAME = "distantstock_order_routes";
    private static final int MAX_ENTRIES = 4096;
    private static final Factory<OrderRouteDirectory> FACTORY =
            new Factory<>(OrderRouteDirectory::new, OrderRouteDirectory::load);

    private record Entry(RemoteRoute route, long createdAt) {
    }

    private final Map<Integer, Entry> routes = new LinkedHashMap<>();

    public static OrderRouteDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void remember(Collection<PackagingRequest> requests, RemoteRoute route) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (PackagingRequest request : requests) {
            Entry previous = routes.put(request.orderId(), new Entry(route, now));
            if (previous != null && !previous.route().equals(route)) {
                throw new IllegalStateException("Create order ID collision: " + request.orderId());
            }
            changed = true;
        }
        while (routes.size() > MAX_ENTRIES) {
            Integer oldest = routes.entrySet().stream()
                    .min(Comparator.comparingLong(row -> row.getValue().createdAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            routes.remove(oldest);
        }
        if (changed) {
            setDirty();
        }
    }

    public Optional<RemoteRoute> find(int createOrderId) {
        Entry entry = routes.get(createOrderId);
        return entry == null ? Optional.empty() : Optional.of(entry.route());
    }

    /** Removes the route for a consumed order. Call after the parcel enters the escrow. */
    public boolean consume(int createOrderId) {
        if (routes.remove(createOrderId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, Entry> row : routes.entrySet()) {
            CompoundTag saved = new CompoundTag();
            RemoteRoute route = row.getValue().route();
            saved.putInt("OrderId", row.getKey());
            saved.putLong("CreatedAt", row.getValue().createdAt());
            saved.putInt("Schema", route.schemaVersion());
            saved.putUUID("DestinationNode", route.destinationNodeId());
            saved.putUUID("ReceivingDockGroup", route.receivingDockGroupId());
            saved.putUUID("Correlation", route.correlationId());
            saved.putUUID("ChildOrder", route.childOrderId());
            list.add(saved);
        }
        tag.put("Routes", list);
        return tag;
    }

    private static OrderRouteDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        OrderRouteDirectory directory = new OrderRouteDirectory();
        ListTag list = tag.getList("Routes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag saved = list.getCompound(i);
            if (!saved.contains("OrderId", Tag.TAG_INT)
                    || !saved.hasUUID("DestinationNode")
                    || !saved.hasUUID("ReceivingDockGroup")
                    || !saved.hasUUID("Correlation")
                    || !saved.hasUUID("ChildOrder")) {
                continue;
            }
            try {
                RemoteRoute route = new RemoteRoute(
                        saved.getInt("Schema"),
                        saved.getUUID("DestinationNode"),
                        saved.getUUID("ReceivingDockGroup"),
                        saved.getUUID("Correlation"),
                        saved.getUUID("ChildOrder"));
                directory.routes.put(saved.getInt("OrderId"),
                        new Entry(route, saved.getLong("CreatedAt")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return directory;
    }
}
