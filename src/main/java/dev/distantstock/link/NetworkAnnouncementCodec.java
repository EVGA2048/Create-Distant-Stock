package dev.distantstock.link;

import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.NetworkDirectory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NetworkAnnouncementCodec {
    private static final int MAGIC = 0x44534e41;
    /**
     * Version 1 carried only the network list; 2 appends the sender's own tick metrics; 3 adds
     * whether each network has a machine that can pack for it, and the sender's dock groups.
     *
     * <p>Older payloads are still read: every field added since 1 is read only when the version
     * says it is there, and a peer that speaks 2 leaves the newer answers at their defaults. The
     * defaults are the permissive ones — "can pack" and "no opinion about who may use this group" —
     * so a mixed pair of versions loses a warning, never an order.
     */
    private static final int VERSION = 3;
    private static final int VERSION_WITH_METRICS = 2;
    private static final int VERSION_WITHOUT_METRICS = 1;
    private static final int MAX_NETWORKS = 256;
    private static final int MAX_GROUPS = 64;
    private static final int MAX_MEMBERS = 32;
    private static final int MAX_TEXT = 256;

    /**
     * One announcement: what is on this node, and the numbers the other node draws about us.
     *
     * <p>The metrics ride here because the announcement is the only message that crosses on a
     * regular beat, and a peer's TPS is something every monitor shows. A second channel asking
     * "how are you" would be a second round trip for two floats that are already going the same way.
     *
     * <p>The dock groups ride here for the same reason and to the same end: they are the other half
     * of every cross-server destination, they change rarely, and the message that says what this
     * node has is exactly the message that should say what it can receive.
     */
    public static byte[] encode(List<NetworkDirectory.Entry> entries, double tps, double mspt,
                                List<Group> groups) throws IOException {
        if (entries.size() > MAX_NETWORKS) {
            throw new IOException("Too many announced networks");
        }
        if (groups.size() > MAX_GROUPS) {
            throw new IOException("Too many announced dock groups");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(entries.size());
        for (NetworkDirectory.Entry entry : entries) {
            if (entry.networkId() == null) {
                throw new IOException("Announced network is missing stable identity");
            }
            RemoteNetworkId id = entry.networkId();
            uuid(out, id.nodeId());
            uuid(out, id.worldId());
            string(out, id.dimensionId());
            uuid(out, id.createFrequency());
            string(out, entry.server());
            out.writeInt(Math.max(0, entry.links()));
            out.writeBoolean(entry.packable());
        }
        out.writeDouble(tps);
        out.writeDouble(mspt);
        out.writeInt(groups.size());
        for (Group group : groups) {
            uuid(out, group.id());
            string(out, group.name());
            out.writeBoolean(group.owner() != null);
            if (group.owner() != null) {
                uuid(out, group.owner());
            }
            out.writeBoolean(group.open());
            out.writeInt(Math.max(0, group.docks()));
            out.writeInt(group.members().size());
            for (Group.Member member : group.members()) {
                uuid(out, member.id());
                string(out, member.name());
            }
        }
        return bytes.toByteArray();
    }

    /**
     * One dock group of the sending node, as it looks to a stranger.
     *
     * <p>Members are named because a name is the only thing that can be shown for somebody who is
     * offline, and the ids travel with them because that is what a permission is decided on.
     *
     * @param docks how many receiving docks the group has, which is the difference between a
     *              destination and a name
     */
    public record Group(UUID id, String name, UUID owner, boolean open, int docks,
                        List<Member> members) {
        public record Member(UUID id, String name) {
        }
    }

    /** The two readings an announcement carries about the node that sent it. */
    public record Metrics(double tps, double mspt) {
        public static final Metrics UNKNOWN = new Metrics(0, 0);

        /** Whether these are real numbers rather than the placeholder for an older peer. */
        public boolean known() {
            return tps > 0;
        }
    }

    /** The metrics from the last {@link #decode}. */
    public static Metrics metrics(byte[] payload) {
        return lastMetrics;
    }

    /** The dock groups from the last {@link #decode}. See {@link #lastMetrics} for the pattern. */
    public static List<Group> groups(byte[] payload) {
        return lastGroups;
    }

    private static Metrics lastMetrics = Metrics.UNKNOWN;
    private static List<Group> lastGroups = List.of();

    public static List<NetworkDirectory.Entry> decode(byte[] payload) throws IOException {
        lastMetrics = Metrics.UNKNOWN;
        lastGroups = List.of();
        if (payload == null || payload.length < 12 || payload.length > 256 * 1024) {
            throw new IOException("Network announcement size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC) {
            throw new IOException("Unsupported network announcement");
        }
        int version = in.readInt();
        if (version < VERSION_WITHOUT_METRICS || version > VERSION) {
            throw new IOException("Unsupported network announcement");
        }
        int count = in.readInt();
        if (count < 0 || count > MAX_NETWORKS) {
            throw new IOException("Network announcement count is invalid");
        }
        List<NetworkDirectory.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID node = uuid(in);
            UUID world = uuid(in);
            String dimension = string(in);
            UUID frequency = uuid(in);
            String alias = string(in);
            int links = in.readInt();
            if (links < 0) {
                throw new IOException("Network link count is invalid");
            }
            // "Can pack" is new in 3, and a peer that does not send it means "no opinion" — which
            // is the permissive answer, so the terminal draws no warning about it.
            boolean packable = version < VERSION || in.readBoolean();
            RemoteNetworkId id = new RemoteNetworkId(1, node, world, dimension, frequency);
            // False by definition: this is a list that arrived from another node.
            entries.add(new NetworkDirectory.Entry(frequency, alias, links, id, false, packable));
        }
        if (version >= VERSION_WITH_METRICS && in.available() >= 16) {
            // Kept as the last thing read rather than returned: the network list is what every
            // caller is here for, and a caller that also wants the numbers asks for them.
            lastMetrics = new Metrics(in.readDouble(), in.readDouble());
        }
        if (version >= VERSION) {
            lastGroups = readGroups(in);
        }
        if (in.available() != 0) {
            throw new IOException("Network announcement contains trailing data");
        }
        return List.copyOf(entries);
    }

    private static List<Group> readGroups(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_GROUPS) {
            throw new IOException("Announced dock group count is invalid");
        }
        List<Group> groups = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = uuid(in);
            String name = string(in);
            UUID owner = in.readBoolean() ? uuid(in) : null;
            boolean open = in.readBoolean();
            int docks = in.readInt();
            int members = in.readInt();
            if (docks < 0 || members < 0 || members > MAX_MEMBERS) {
                throw new IOException("Announced dock group is invalid");
            }
            List<Group.Member> named = new ArrayList<>(members);
            for (int m = 0; m < members; m++) {
                named.add(new Group.Member(uuid(in), string(in)));
            }
            groups.add(new Group(id, name, owner, open, docks, List.copyOf(named)));
        }
        return List.copyOf(groups);
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) {
            throw new IOException("Announcement text exceeds limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String string(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_TEXT) {
            throw new IOException("Announcement text length is invalid");
        }
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) {
            throw new IOException("Unexpected end of announcement");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private NetworkAnnouncementCodec() {
    }
}
