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
 * <p><b>The row is discovery/routing memory, not delivery permission.</b> PUBLIC / UNLISTED decides
 * whether ordinary UI lists the address; knowing the exact address is enough to deliver to it. The
 * legacy owner/member/open fields still ride along because old management screens and dock-join
 * gestures use them, but {@code OrderDestination} deliberately does not use them to authorize an
 * order.
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
     * @param owner    who manages it, or null for a group nobody owns. Legacy management metadata.
     * @param members  legacy collaborators, by id and by name; not a delivery ACL.
     * @param docks    how many receiving docks the group has over there, as of the last
     *                 announcement. A group with none is a destination nothing can arrive at, and
     *                 that is worth saying before an order is placed rather than after.
     */
    public record Entry(UUID node, UUID group, String name, String label, long pairedAt,
                        boolean open, UUID owner, Map<UUID, String> members, int docks,
                        UUID distantNetworkId, boolean listed) {
        public Entry(UUID node, UUID group, String name, String label, long pairedAt,
                     boolean open, UUID owner, Map<UUID, String> members, int docks) {
            this(node, group, name, label, pairedAt, open, owner, members, docks,
                    DistantNetworkDirectory.LEGACY_NETWORK_ID, true);
        }

        public Entry {
            distantNetworkId = distantNetworkId == null
                    ? DistantNetworkDirectory.LEGACY_NETWORK_ID : distantNetworkId;
        }

        public String display() {
            return label + "·" + name;
        }

        /** Legacy collaborator membership, retained for management compatibility only. */
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
                entry.open(), entry.owner(), entry.members(), entry.docks(),
                entry.distantNetworkId(), entry.listed());
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
                    row.open(), row.owner(), row.members(), row.docks(),
                    row.distantNetworkId(), row.listed()));
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
                && first.members().equals(second.members())
                && first.distantNetworkId().equals(second.distantNetworkId())
                && first.listed() == second.listed();
    }

    /** Everything about a row a player can see, as one string. See {@link #forget}. */
    private static String fingerprint(Entry entry) {
        return entry.name() + " " + entry.open() + " " + entry.docks() + " "
                + entry.owner() + " " + entry.members() + " "
                + entry.distantNetworkId() + " " + entry.listed();
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

    /**
     * Every remote group carrying this bare player-facing name.
     *
     * <p>The name is becoming a network-wide receiving address, so callers that care about routing
     * must see all rows instead of whichever one happened to be inserted first.
     */
    public List<Entry> named(String name) {
        return named(DistantNetworkDirectory.LEGACY_NETWORK_ID, name);
    }

    public List<Entry> named(UUID distantNetworkId, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        UUID scope = distantNetworkId == null
                ? DistantNetworkDirectory.LEGACY_NETWORK_ID : distantNetworkId;
        String wanted = name.trim();
        return byGroup.values().stream()
                .filter(entry -> entry.distantNetworkId().equals(scope))
                .filter(entry -> entry.name().equalsIgnoreCase(wanted))
                .toList();
    }

    /**
     * Rows a legacy input string can refer to.
     *
     * <p>{@link Entry#display()} remains accepted so terminals saved by older builds keep working,
     * but it is no longer a way to disambiguate duplicate receiving-address names. The resolver
     * checks the entry's bare name afterwards and refuses the whole conflict.
     */
    public List<Entry> matchingInput(String input) {
        return matchingInput(DistantNetworkDirectory.LEGACY_NETWORK_ID, input);
    }

    public List<Entry> matchingInput(UUID distantNetworkId, String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        UUID scope = distantNetworkId == null
                ? DistantNetworkDirectory.LEGACY_NETWORK_ID : distantNetworkId;
        String wanted = input.trim();
        return byGroup.values().stream()
                .filter(entry -> entry.distantNetworkId().equals(scope))
                .filter(entry -> entry.name().equalsIgnoreCase(wanted)
                        || entry.display().equalsIgnoreCase(wanted))
                .toList();
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
        List<Entry> matches = matchingInput(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        java.util.Set<String> canonicalNames = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        matches.forEach(entry -> canonicalNames.add(entry.name()));
        if (canonicalNames.size() != 1) {
            return Optional.empty();
        }
        List<Entry> sameAddress = named(canonicalNames.iterator().next());
        return sameAddress.stream().map(Entry::group).distinct().count() == 1
                ? Optional.of(sameAddress.getFirst())
                : Optional.empty();
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
            row.putUUID("DistantNetwork", entry.distantNetworkId());
            row.putBoolean("Listed", entry.listed());
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
                    Map.copyOf(members), row.getInt("Docks"),
                    row.hasUUID("DistantNetwork") ? row.getUUID("DistantNetwork")
                            : DistantNetworkDirectory.LEGACY_NETWORK_ID,
                    !row.contains("Listed") || row.getBoolean("Listed")));
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
