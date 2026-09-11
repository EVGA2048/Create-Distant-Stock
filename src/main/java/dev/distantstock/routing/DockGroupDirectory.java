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
        groups.put(DEFAULT_GROUP_ID, new DockGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME));
    }

    public static DockGroupDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public DockGroup create(String name) {
        DockGroup group = new DockGroup(UUID.randomUUID(), name);
        groups.put(group.id(), group);
        setDirty();
        return group;
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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (DockGroup group : groups.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", group.id());
            entry.putString("Name", group.name());
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
                DockGroup group = new DockGroup(entry.getUUID("Id"), entry.getString("Name"));
                directory.groups.put(group.id(), group);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return directory;
    }
}
