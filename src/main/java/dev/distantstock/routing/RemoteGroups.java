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
 * The dock groups on other servers, as those servers describe them.
 *
 * <p>Every order in this mod names its destination as a (node, group) pair, and only the local half
 * of that is in this server's own directory: a group on another server has no name here, no id here,
 * and no way to be looked up. This file is the other half. Rows arrive on the network announcement —
 * the message peers already send each other every 45 seconds — and from then on the other server's
 * groups are destinations like any other: they appear in the list a requester shows and orders
 * naming them go out with that node and that id.
 *
 * <p><b>It used to be a pairing code.</b> An operator minted one, read it out to the other server's
 * operator, and that player typed it into the terminal; redeeming it wrote one row here. The user
 * never used it once ("我到今天都没用过") and the reason is plain: it made an address something a
 * human has to be handed, for a link both servers are already talking over. An announcement carries
 * the same four facts and keeps carrying them, so a group renamed or deleted on the far side stops
 * being offered here on its own.
 *
 * <p><b>The row is a memory, not a permission</b> — except for the one thing the far side cannot
 * check for itself. The receiving server decides whether a *dock* may join a group, and it decides
 * that from its own directory; nothing here is consulted for it. What it cannot decide is whether
 * the player who placed an order was allowed to name the group, because the order arrives with no
 * player on it. So the member list rides along, and {@code OrderDestination} uses it on this side,
 * where the player is. A peer that has never announced a group leaves no row and no opinion, and
 * nothing is refused on a guess.
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
     * @param node     the Transerver node that holds the directory this group lives in
     * @param label    how that node is shown to players. The node names itself on every
     *                 announcement and that name is remembered, so this is a live answer rather
     *                 than the uuid prefix it falls back to for a node that has gone quiet.
     * @param open     whether anybody may add themselves to it — over there, by asking that
     *                 server. It is shown here and nothing here acts on it.
     * @param owner    who keeps it, or null for a group nobody owns. Read by {@link #admits}.
     * @param members  the players that server named, by id and by name.
     * @param docks    how many receiving docks the group has over there, as of the last
     *                 announcement. A group with none is a destination nothing can arrive at, and
     *                 that is worth saying before an order is placed rather than after.
     */
    public record Entry(UUID node, UUID group, String name, String label, long pairedAt,
                        boolean open, UUID owner, Map<UUID, String> members, int docks) {
        public String display() {
            return label + "·" + name;
        }

        /**
         * Whether the player who asked may name this group in an order.
         *
         * <p>Ownerless admits everybody, the same rule {@link DockGroup#admits} follows, so a group
         * left over from before members existed — or one an admin made — never locks anyone out. A
         * group that has an owner and has not listed this player refuses, which is the whole point
         * of shipping the list across.
         */
        public boolean admits(UUID player) {
            return owner == null || (player != null && (owner.equals(player) || members.containsKey(player)));
        }
    }

    private final Map<UUID, Entry> byGroup = new LinkedHashMap<>();
    /** Rows a player dismissed, and what they looked like then. See {@link #forget}. */
    private final Map<UUID, String> hidden = new LinkedHashMap<>();

    public static RemoteGroups get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Records a group learned from another server, replacing whatever was known about that id. */
    public Entry add(Entry entry, long now) {
        Entry stored = new Entry(entry.node(), entry.group(), entry.name(), entry.label(), now,
                entry.open(), entry.owner(), entry.members(), entry.docks());
        byGroup.put(stored.group(), stored);
        trim();
        setDirty();
        return stored;
    }

    /**
     * Replaces everything this server knew about one node's groups with what that node just said.
     *
     * <p>A wholesale replacement rather than a merge, because an announcement is the node's whole
     * list: a group missing from it has been deleted over there, and a row kept here would go on
     * being offered as a destination that nothing will ever arrive at. Renames are the same case
     * and are handled by the same line.
     *
     * <p>{@code pairedAt} is carried over for a group that was already known, so the eviction order
     * still means "when did this destination first appear" instead of being reset by every
     * announcement — a file that has been full for a year would otherwise drop its oldest rows on a
     * schedule set by the peer's heartbeat.
     *
     * <p>Writes to disk only when something actually differs. The announcement repeats every 45
     * seconds for as long as the link is up, and a save file rewritten on that beat is a save file
     * rewritten forever.
     */
    public void replaceFrom(UUID node, List<Entry> rows, long now) {
        Map<UUID, Entry> announced = new LinkedHashMap<>();
        for (Entry row : rows) {
            Entry known = byGroup.get(row.group());
            long since = known != null && known.node().equals(node) ? known.pairedAt() : now;
            announced.put(row.group(), new Entry(node, row.group(), row.name(), row.label(), since,
                    row.open(), row.owner(), row.members(), row.docks()));
        }
        boolean changed = false;
        for (var it = byGroup.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            if (entry.node().equals(node) && !announced.containsKey(entry.group())) {
                it.remove();
                changed = true;
            }
        }
        for (Entry row : announced.values()) {
            if (fingerprint(row).equals(hidden.get(row.group()))) {
                // Dismissed, and the far side is still saying the same thing about it. Dropping the
                // row here as well keeps the two files from disagreeing.
                changed |= byGroup.remove(row.group()) != null;
                continue;
            }
            hidden.remove(row.group());
            Entry previous = byGroup.put(row.group(), row);
            changed |= previous == null || !sameContent(previous, row);
        }
        if (changed) {
            trim();
            setDirty();
        }
    }

    /** Whether two rows would be drawn the same. Ignores the timestamp, which is not display data. */
    private static boolean sameContent(Entry first, Entry second) {
        return first.open() == second.open() && first.docks() == second.docks()
                && java.util.Objects.equals(first.owner(), second.owner())
                && first.name().equals(second.name()) && first.label().equals(second.label())
                && first.members().equals(second.members());
    }

    /** Everything about a row a player can see, as one string. See {@link #forget}. */
    private static String fingerprint(Entry entry) {
        return entry.name() + " " + entry.open() + " " + entry.docks() + " "
                + entry.owner() + " " + entry.members();
    }

    private void trim() {
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

    /**
     * Hides a row the player is not interested in.
     *
     * <p>Not a delete, because nothing here can be deleted for good: the far server announces its
     * whole list every 45 seconds and would put the row straight back, so a button that removed it
     * would look like it did nothing at all. What is stored instead is the row as it looked when it
     * was dismissed, and the hide holds only while the far side says the same thing —
     * {@link #fingerprint}. Renamed over there, or a new member list, and it is offered again,
     * which is right: the row that was dismissed is not the row that is being announced now.
     */
    public boolean forget(UUID group) {
        Entry gone = byGroup.remove(group);
        if (gone == null) {
            return false;
        }
        hidden.put(group, fingerprint(gone));
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
            row.putBoolean("Open", entry.open());
            row.putInt("Docks", entry.docks());
            if (entry.owner() != null) {
                row.putUUID("Owner", entry.owner());
            }
            if (!entry.members().isEmpty()) {
                ListTag members = new ListTag();
                entry.members().forEach((member, memberName) -> {
                    CompoundTag line = new CompoundTag();
                    line.putUUID("Id", member);
                    line.putString("Name", memberName);
                    members.add(line);
                });
                row.put("Members", members);
            }
            entries.add(row);
        }
        tag.put("Groups", entries);
        if (!hidden.isEmpty()) {
            ListTag dismissed = new ListTag();
            hidden.forEach((group, seen) -> {
                CompoundTag line = new CompoundTag();
                line.putUUID("Group", group);
                line.putString("Seen", seen);
                dismissed.add(line);
            });
            tag.put("Hidden", dismissed);
        }
        return tag;
    }

    /**
     * Public so the file can be read back in a test: what is written has to be what was read,
     * and the member list is a permission now — a load that lost it would come back with every
     * remote group owned by nobody, which admits everybody.
     */
    public static RemoteGroups load(CompoundTag tag, HolderLookup.Provider registries) {
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
            // The membership fields are absent in a file written by an older version, and absent
            // means "no owner" here — which admits everybody, the same answer those rows had when
            // they were written. The next announcement replaces the row with the current truth.
            UUID owner = row.hasUUID("Owner") ? row.getUUID("Owner") : null;
            Map<UUID, String> members = new LinkedHashMap<>();
            ListTag lines = row.getList("Members", Tag.TAG_COMPOUND);
            for (int m = 0; m < lines.size() && m < DockGroup.MAX_MEMBERS; m++) {
                CompoundTag line = lines.getCompound(m);
                if (line.hasUUID("Id")) {
                    members.put(line.getUUID("Id"), line.getString("Name"));
                }
            }
            data.byGroup.put(group, new Entry(row.getUUID("Node"), group, row.getString("Name"),
                    row.getString("Label"), row.getLong("Paired"), row.getBoolean("Open"), owner,
                    Map.copyOf(members), row.getInt("Docks")));
        }
        ListTag dismissed = tag.getList("Hidden", Tag.TAG_COMPOUND);
        for (int i = 0; i < dismissed.size() && i < MAX_ENTRIES; i++) {
            CompoundTag line = dismissed.getCompound(i);
            if (line.hasUUID("Group")) {
                data.hidden.put(line.getUUID("Group"), line.getString("Seen"));
            }
        }
        return data;
    }
}
