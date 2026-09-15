package dev.distantstock.routing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** World-persistent directory for stable receiving-dock group identities. */
public final class DockGroupDirectory extends SavedData {
    public static final UUID DEFAULT_GROUP_ID = UUID.fromString("d157a17c-570c-4d3c-9a0c-000000000001");
    public static final String DEFAULT_GROUP_NAME = "默认收货港组";
    private static final String DATA_NAME = "distantstock_dock_groups";
    private static final Factory<DockGroupDirectory> FACTORY =
            new Factory<>(DockGroupDirectory::new, DockGroupDirectory::load);

    private final Map<UUID, DockGroup> groups = new LinkedHashMap<>();

    public DockGroupDirectory() {
        // The default group belongs to nobody, because it is the one every dock starts in. It has
        // to stay open or an existing save would lock itself out the moment this shipped.
        groups.put(DEFAULT_GROUP_ID, new DockGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME, null, true));
    }

    public static DockGroupDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** A group made by the server, open to everyone. What the admin command makes. */
    public DockGroup create(String name) {
        return create(name, null, true);
    }

    /**
     * A group made by a player, closed by default.
     *
     * <p>Closed rather than open because of what a group is for: it decides whose dock the parcels
     * come out of. Anyone who wants their dock shared can say so afterwards; the other default
     * would hand a stranger's parcels to a player who did not know they were building a warehouse.
     */
    public DockGroup createFor(String name, UUID owner) {
        return create(name, owner, false);
    }

    public DockGroup create(String name, UUID owner, boolean open) {
        DockGroup group = new DockGroup(UUID.randomUUID(), name, owner, open);
        groups.put(group.id(), group);
        setDirty();
        return group;
    }

    /**
     * The system with this name, if there is one.
     *
     * <p>Names are how a player refers to a system — there is no id to type and no list to pick
     * from in the world — so this is the lookup the requester's field runs on. Matching ignores
     * case but not space: two systems called the same thing would be indistinguishable in every
     * readout, so the second one is refused rather than silently created.
     */
    public Optional<DockGroup> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        for (DockGroup group : groups.values()) {
            if (group.name().equalsIgnoreCase(wanted)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    public Optional<DockGroup> find(UUID id) {
        return Optional.ofNullable(groups.get(id));
    }

    public DockGroup require(UUID id) {
        return find(id).orElseGet(() -> groups.get(DEFAULT_GROUP_ID));
    }

    public DockGroup rename(UUID id, String name) {
        DockGroup current = groups.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown dock group: " + id);
        }
        DockGroup renamed = current.rename(name);
        groups.put(id, renamed);
        setDirty();
        return renamed;
    }

    public List<DockGroup> all() {
        List<DockGroup> result = new ArrayList<>(groups.values());
        result.sort(Comparator.comparing(DockGroup::name).thenComparing(DockGroup::id));
        return List.copyOf(result);
    }

    /** Records a group's openness. Guarded by the caller; this only writes. */
    public DockGroup setOpen(UUID id, boolean open) {
        DockGroup current = groups.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown dock group: " + id);
        }
        DockGroup next = current.withOpen(open);
        groups.put(id, next);
        setDirty();
        return next;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (DockGroup group : groups.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", group.id());
            entry.putString("Name", group.name());
            // Written only when there is something to write, so a file made before groups had
            // owners loads back as the same open, ownerless groups it was saved as.
            if (group.owner() != null) {
                entry.putUUID("Owner", group.owner());
            }
            entry.putBoolean("Open", group.open());
            entries.add(entry);
        }
        tag.put("Groups", entries);
        return tag;
    }

    private static DockGroupDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        DockGroupDirectory directory = new DockGroupDirectory();
        ListTag entries = tag.getList("Groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("Id")) {
                continue;
            }
            try {
                UUID owner = entry.hasUUID("Owner") ? entry.getUUID("Owner") : null;
                // A missing flag means the file predates ownership, and every group in it was
                // open. Defaulting the other way would lock players out of their own docks on the
                // first load after an update.
                boolean open = !entry.contains("Open") || entry.getBoolean("Open");
                DockGroup group = new DockGroup(entry.getUUID("Id"), entry.getString("Name"), owner, open);
                directory.groups.put(group.id(), group);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return directory;
    }
}
