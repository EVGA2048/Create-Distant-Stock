package dev.distantstock.routing;

import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persists the route belonging to a Create order until its packages reach a remote dock. */
public final class OrderRouteDirectory extends SavedData {
    private static final String DATA_NAME = "distantstock_order_routes";
    private static final int MAX_ENTRIES = 4096;
    private static final Factory<OrderRouteDirectory> FACTORY =
            new Factory<>(OrderRouteDirectory::new, OrderRouteDirectory::load);

    private static final class Entry {
        final RemoteRoute route;
        final long createdAt;
        /**
         * The address the parcels of this order must wear once they are home, or "".
         *
         * <p>Kept beside the route because it is decided with it — from the same order request, at
         * the same moment — and because the packager that stamps the route onto a parcel needs both.
         * Blank for an order whose goods stay on this server, and for one from a build that had only
         * one address to give.
         */
        final String homeAddress;
        final Map<Integer, java.util.Set<Integer>> received = new LinkedHashMap<>();
        final Map<Integer, Integer> lastPackage = new LinkedHashMap<>();
        int lastLink = -1;

        Entry(RemoteRoute route, String homeAddress, long createdAt) {
            this.route = route;
            this.homeAddress = homeAddress == null ? "" : homeAddress;
            this.createdAt = createdAt;
        }
        RemoteRoute route() { return route; }
        long createdAt() { return createdAt; }
    }

    private final Map<Integer, Entry> routes = new LinkedHashMap<>();

    public static OrderRouteDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void remember(Collection<PackagingRequest> requests, RemoteRoute route) {
        remember(requests, route, "");
    }

    /** The same, for an order whose parcels have to wear a different address once they are home. */
    public void remember(Collection<PackagingRequest> requests, RemoteRoute route, String homeAddress) {
        long now = System.currentTimeMillis();
        var newIds = new java.util.HashSet<Integer>();
        for (PackagingRequest request : requests) {
            Entry previous = routes.get(request.orderId());
            if (previous != null && !previous.route().equals(route)) {
                throw new IllegalStateException("Create order ID collision: " + request.orderId());
            }
            if (previous == null) newIds.add(request.orderId());
        }
        // Never evict an in-flight order just to admit another: its next parcel still needs its route.
        if (routes.size() + newIds.size() > MAX_ENTRIES) {
            throw new IllegalStateException("Too many unfinished routed orders");
        }
        for (Integer id : newIds) routes.put(id, new Entry(route, homeAddress, now));
        if (!newIds.isEmpty()) setDirty();
    }

    /** Called only after durable escrow ownership; Create fragments can arrive out of order. */
    public void packageEscrowed(ItemStack parcel) {
        if (!PackageItem.hasOrderData(parcel)) return;
        int orderId = PackageItem.getOrderId(parcel);
        Entry entry = routes.get(orderId);
        if (entry == null) return;
        // A forwarded foreign parcel can reuse a local Create integer order ID.
        if (RemoteRouteData.read(parcel).filter(route -> !route.equals(entry.route())).isPresent()) return;
        int link = PackageItem.getLinkIndex(parcel);
        int index = PackageItem.getIndex(parcel);
        if (link < 0 || index < 0) return;
        entry.received.computeIfAbsent(link, ignored -> new java.util.HashSet<>()).add(index);
        if (PackageItem.isFinal(parcel)) entry.lastPackage.put(link, index);
        if (PackageItem.isFinalLink(parcel)) entry.lastLink = link;
        setDirty();
        if (entry.lastLink < 0 || entry.received.size() != (long) entry.lastLink + 1) return;
        for (int i = 0; i <= entry.lastLink; i++) {
            var indexes = entry.received.get(i);
            Integer last = entry.lastPackage.get(i);
            if (indexes == null || last == null || indexes.size() != (long) last + 1) return;
            for (int j = 0; j <= last; j++) if (!indexes.contains(j)) return;
        }
        consume(orderId);
    }

    public Optional<RemoteRoute> find(int createOrderId) {
        Entry entry = routes.get(createOrderId);
        return entry == null ? Optional.empty() : Optional.of(entry.route());
    }

    /**
     * The address an order's parcels wear once they are home, or "" when there is none.
     *
     * <p>Asked separately from the route because the packager writes the route onto every parcel of
     * the order and the address onto the same ones — one lookup would be nicer, and this is the same
     * map read twice, so the cost of two is one hash.
     */
    public String homeAddress(int createOrderId) {
        Entry entry = routes.get(createOrderId);
        return entry == null ? "" : entry.homeAddress;
    }

    /** Removes a completely accounted-for order, never just its first parcel. */
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
            // 只在有东西可写的时候写：老存档读回来仍是「没有第二个地址」，而不是空字符串。
            if (!row.getValue().homeAddress.isEmpty()) {
                saved.putString("HomeAddress", row.getValue().homeAddress);
            }
            saved.putInt("LastLink", row.getValue().lastLink);
            ListTag progress = new ListTag();
            row.getValue().received.forEach((link, indexes) -> {
                CompoundTag part = new CompoundTag();
                part.putInt("Link", link);
                part.putIntArray("Indexes", indexes.stream().mapToInt(Integer::intValue).sorted().toArray());
                if (row.getValue().lastPackage.containsKey(link))
                    part.putInt("Last", row.getValue().lastPackage.get(link));
                progress.add(part);
            });
            saved.put("Progress", progress);
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
                // 缺键 = 这个订单只有一个地址（老存档，或者货根本不过海），读回来是空串。
                Entry entry = new Entry(route, saved.getString("HomeAddress"), saved.getLong("CreatedAt"));
                entry.lastLink = saved.contains("LastLink", Tag.TAG_INT) ? saved.getInt("LastLink") : -1;
                ListTag progress = saved.getList("Progress", Tag.TAG_COMPOUND);
                for (int j = 0; j < progress.size(); j++) {
                    CompoundTag part = progress.getCompound(j);
                    int link = part.getInt("Link");
                    if (link < 0) continue;
                    var indexes = new java.util.HashSet<Integer>();
                    for (int index : part.getIntArray("Indexes")) if (index >= 0) indexes.add(index);
                    entry.received.put(link, indexes);
                    if (part.contains("Last", Tag.TAG_INT) && part.getInt("Last") >= 0)
                        entry.lastPackage.put(link, part.getInt("Last"));
                }
                directory.routes.put(saved.getInt("OrderId"), entry);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return directory;
    }
}
