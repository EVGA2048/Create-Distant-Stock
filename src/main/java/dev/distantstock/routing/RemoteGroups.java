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

/**
 * The dock groups on other servers that this one has been let into.
 *
 * <p>Every order in this mod names its destination as a (node, group) pair, and until now only the
 * local half of that was reachable: a player could pick a group out of this server's own directory
 * and nothing else, because a group on another server has no name here, no id here, and no way to
 * be looked up. A redeemed {@link PairingCodes code} writes one row into this file, and from then
 * on the other server's group is a destination like any other — it appears in the list a requester
 * shows, it survives restarts, and orders naming it go out with that node and that id.
 *
 * <p><b>The row is a memory, not a permission.</b> Nothing here is consulted to decide whether a
 * parcel may be delivered: the receiving server has the group and its own switches, and it does the
 * deciding. This is what the player on this side is allowed to <em>name</em>. Deleting a row is
 * therefore safe and costs nothing but the ability to address that group again — in-flight parcels
 * still land, which is the same rule the rest of the mod follows.
 */
public final class RemoteGroups extends SavedData {
    private static final String DATA_NAME = "distantstock_remote_groups";
    private static final Factory<RemoteGroups> FACTORY =
            new Factory<>(RemoteGroups::new, RemoteGroups::load);
    /** How many remote groups one directory keeps. A list nobody can scroll is not a list. */
    public static final int MAX_ENTRIES = 64;

    /**
     * One group on another server.
     *
     * @param node  the Transerver node that holds the directory this group lives in
     * @param label how that node is shown to players. Taken from the node id, which is the only
     *              name a peer has: Transerver identifies nodes by UUID and nothing in its status
     *              carries a display name. The group's own name travels with it and is authored by
     *              whoever owns it, so the pair reads as "乙服·仓库" when the owner named it well.
     */
    public record Entry(UUID node, UUID group, String name, String label, long pairedAt) {
        public String display() {
            return label + "·" + name;
        }
    }

    private final Map<UUID, Entry> byGroup = new LinkedHashMap<>();

    public static RemoteGroups get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Records a group learned from another server, replacing whatever was known about that id. */
    public Entry add(Entry entry, long now) {
        Entry stored = new Entry(entry.node(), entry.group(), entry.name(), entry.label(), now);
        byGroup.put(stored.group(), stored);
        while (byGroup.size() > MAX_ENTRIES) {
            UUID oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Entry candidate : byGroup.values()) {
                if (candidate.pairedAt() < oldestAt) {
                    oldestAt = candidate.pairedAt();
                    oldest = candidate.group();
                }
            }
            if (oldest == null) {
                break;
            }
            byGroup.remove(oldest);
        }
        setDirty();
        return stored;
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>(byGroup.values());
        out.sort(Comparator.comparing(Entry::label).thenComparing(Entry::name).thenComparing(Entry::group));
        return List.copyOf(out);
    }

    /** The entry with this group id, whoever's server it is on. */
    public Optional<Entry> find(UUID group) {
        return Optional.ofNullable(group == null ? null : byGroup.get(group));
    }

    /**
     * The entry whose name matches, for the one field in this mod where players type a destination.
     *
     * <p>Matched the way {@link DockGroupDirectory#findByName} matches, and deliberately only after
     * the local directory has been asked: a local group is the one the player can see the docks of,
     * so a name that means something here must not be shadowed by a copy of it from elsewhere.
     */
    public Optional<Entry> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        for (Entry entry : byGroup.values()) {
            if (entry.name().equalsIgnoreCase(wanted) || entry.display().equalsIgnoreCase(wanted)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean forget(UUID group) {
        if (byGroup.remove(group) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (Entry entry : byGroup.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Node", entry.node());
            row.putUUID("Group", entry.group());
            row.putString("Name", entry.name());
            row.putString("Label", entry.label());
            row.putLong("Paired", entry.pairedAt());
            entries.add(row);
        }
        tag.put("Groups", entries);
        return tag;
    }

    private static RemoteGroups load(CompoundTag tag, HolderLookup.Provider registries) {
        RemoteGroups data = new RemoteGroups();
        ListTag entries = tag.getList("Groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag row = entries.getCompound(i);
            // Every field is required: a row without a node cannot be addressed, and one without a
            // group id cannot be matched. A half-row would be a destination that fails at the far
            // end of a parcel's journey, which is the worst place to find out.
            if (!row.hasUUID("Node") || !row.hasUUID("Group")) {
                continue;
            }
            UUID group = row.getUUID("Group");
            data.byGroup.put(group, new Entry(row.getUUID("Node"), group, row.getString("Name"),
                    row.getString("Label"), row.getLong("Paired")));
        }
        return data;
    }
}
