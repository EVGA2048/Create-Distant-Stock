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

    /** Creates one receiving address inside a real Distant Stock network. */
    public DockGroup createForNetwork(String name, UUID owner, UUID distantNetworkId,
                                      DockGroup.Visibility visibility) {
        return create(name, owner, false, distantNetworkId, visibility);
    }

    public DockGroup create(String name, UUID owner, boolean open) {
        return create(name, owner, open, DistantNetworkDirectory.LEGACY_NETWORK_ID,
                DockGroup.Visibility.PUBLIC);
    }

    public DockGroup create(String name, UUID owner, boolean open, UUID distantNetworkId,
                            DockGroup.Visibility visibility) {
        DockGroup group = new DockGroup(UUID.randomUUID(), name, owner, open, Map.of(),
                distantNetworkId, visibility);
        // Refused rather than added. Two groups sharing a name are two groups no lookup, readout
        // or command can tell apart, and the failure would be silent and permanent — the file would
        // hold both and every later lookup would return whichever came first.
        //
        // Thrown, not quietly returned as the existing one: a create that returns somebody else's
        // group is how a player ends up adding their docks to a stranger's warehouse.
        if (!named(group.distantNetworkId(), group.name()).isEmpty()) {
            throw new IllegalArgumentException("Dock group already exists: " + group.name());
        }
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
        List<DockGroup> matches = named(DistantNetworkDirectory.LEGACY_NETWORK_ID, name);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** Every legacy local group carrying this player-facing name. */
    public List<DockGroup> named(String name) {
        return named(DistantNetworkDirectory.LEGACY_NETWORK_ID, name);
    }

    public Optional<DockGroup> findByName(UUID distantNetworkId, String name) {
        List<DockGroup> matches = named(distantNetworkId, name);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /**
     * Player-facing receiving addresses are names, scoped by one Distant Stock network. The UUID
     * remains an internal routing identity only. Typing the same address on two docks therefore
     * resolves to the same group automatically; the first use creates it.
     */
    public DockGroup resolveAddress(UUID distantNetworkId, String name) {
        UUID scope = distantNetworkId == null
                ? DistantNetworkDirectory.LEGACY_NETWORK_ID : distantNetworkId;
        String clean = name == null ? "" : name.trim();
        if (clean.isBlank()) {
            return groups.get(DEFAULT_GROUP_ID);
        }
        Optional<DockGroup> existing = findByName(scope, clean);
        if (existing.isPresent()) {
            return existing.get();
        }
        return create(clean, null, true, scope, DockGroup.Visibility.PUBLIC);
    }

    /** Every local address carrying this name inside one Distant Stock network. */
    public List<DockGroup> named(UUID distantNetworkId, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        UUID scope = distantNetworkId == null
                ? DistantNetworkDirectory.LEGACY_NETWORK_ID : distantNetworkId;
        String wanted = name.trim();
        return groups.values().stream()
                .filter(group -> group.distantNetworkId().equals(scope))
                .filter(group -> group.name().equalsIgnoreCase(wanted))
                .toList();
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
        boolean takenByAnother = named(current.distantNetworkId(), renamed.name()).stream()
                .anyMatch(existing -> !existing.id().equals(id));
        if (takenByAnother) {
            throw new IllegalArgumentException("Dock group already exists: " + renamed.name());
        }
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

    public DockGroup setVisibility(UUID id, DockGroup.Visibility visibility) {
        DockGroup current = groups.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown dock group: " + id);
        }
        DockGroup next = current.withVisibility(visibility);
        groups.put(id, next);
        setDirty();
        return next;
    }

    public DockGroup setDistantNetwork(UUID id, UUID distantNetworkId) {
        DockGroup current = groups.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown dock group: " + id);
        }
        if (!named(distantNetworkId, current.name()).stream()
                .allMatch(group -> group.id().equals(id))) {
            throw new IllegalArgumentException("Receiving address already exists in target network: "
                    + current.name());
        }
        DockGroup next = current.inDistantNetwork(distantNetworkId);
        groups.put(id, next);
        setDirty();
        return next;
    }

    /**
     * Names one player in a group, so a closed group lets them in too.
     *
     * <p>Guarded by the caller, like {@link #setOpen}: this only writes. The owner is the one who
     * decides, and a caller that has not checked ownership is the whole of the attack surface —
     * being named only ever grants what the owner could grant anyway.
     */
    public DockGroup addMember(UUID id, UUID player, String playerName) {
        return writeMember(id, group -> group.withMember(player, playerName));
    }

    public DockGroup removeMember(UUID id, UUID player) {
        return writeMember(id, group -> group.withoutMember(player));
    }

    private DockGroup writeMember(UUID id, java.util.function.UnaryOperator<DockGroup> change) {
        DockGroup current = groups.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown dock group: " + id);
        }
        DockGroup next = change.apply(current);
        if (next == current) {
            // Nothing changed: writing anyway would mark the file dirty on every no-op, and a
            // re-add of somebody already in the list would look like an edit it is not.
            return current;
        }
        groups.put(id, next);
        setDirty();
        return next;
    }

    /**
     * Removes a group for good, sending the docks that were in it back to the default one.
     *
     * <p>The docks are not touched beyond their group: deleting a system must never break a machine.
     * A dock that was receiving for a group nobody can address any more would otherwise sit there
     * looking busy forever, so it goes back to the group every dock starts in.
     *
     * <p>The default group cannot be deleted. Every dock starts in it and it is the fallback for
     * every lookup that misses, so removing it would leave those lookups with nothing to return.
     */
    public boolean delete(UUID id) {
        if (id == null || id.equals(DEFAULT_GROUP_ID)) {
            return false;
        }
        if (groups.remove(id) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (DockGroup group : groups.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", group.id());
            entry.putString("Name", group.name());
            entry.putUUID("DistantNetwork", group.distantNetworkId());
            entry.putString("Visibility", group.visibility().name());
            // Written only when there is something to write, so a file made before groups had
            // owners loads back as the same open, ownerless groups it was saved as.
            if (group.owner() != null) {
                entry.putUUID("Owner", group.owner());
            }
            entry.putBoolean("Open", group.open());
            if (!group.members().isEmpty()) {
                ListTag members = new ListTag();
                group.members().forEach((member, memberName) -> {
                    CompoundTag row = new CompoundTag();
                    row.putUUID("Id", member);
                    row.putString("Name", memberName);
                    members.add(row);
                });
                entry.put("Members", members);
            }
            entries.add(entry);
        }
        tag.put("Groups", entries);
        return tag;
    }

    /** Public so the file can be read back in a test: a save format is only as good as its round trip. */
    public static DockGroupDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
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
                // Absent means the file predates members: a group with nobody named in it, which
                // is exactly what it was. A row without an id is skipped rather than defaulted —
                // a member keyed on a made-up uuid would be a stranger the owner never named.
                Map<UUID, String> members = new LinkedHashMap<>();
                ListTag rows = entry.getList("Members", Tag.TAG_COMPOUND);
                for (int row = 0; row < rows.size() && members.size() < DockGroup.MAX_MEMBERS; row++) {
                    CompoundTag member = rows.getCompound(row);
                    if (member.hasUUID("Id")) {
                        members.put(member.getUUID("Id"), member.getString("Name"));
                    }
                }
                UUID distantNetworkId = entry.hasUUID("DistantNetwork")
                        ? entry.getUUID("DistantNetwork") : DistantNetworkDirectory.LEGACY_NETWORK_ID;
                DockGroup.Visibility visibility;
                try {
                    visibility = DockGroup.Visibility.valueOf(entry.getString("Visibility"));
                } catch (IllegalArgumentException ignored) {
                    visibility = DockGroup.Visibility.PUBLIC;
                }
                DockGroup group = new DockGroup(entry.getUUID("Id"), entry.getString("Name"),
                        owner, open, members, distantNetworkId, visibility);
                directory.groups.put(group.id(), group);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return directory;
    }
}
