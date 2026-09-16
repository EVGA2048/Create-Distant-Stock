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
    /** Version 1 carried only the network list; 2 appends the sender's own tick metrics. */
    private static final int VERSION = 2;
    private static final int VERSION_WITHOUT_METRICS = 1;
    private static final int MAX_NETWORKS = 256;
    private static final int MAX_TEXT = 256;

    /**
     * One announcement: the networks on this node, and the numbers the other node draws about us.
     *
     * <p>The metrics ride here because the announcement is the only message that crosses on a
     * regular beat, and a peer's TPS is something every monitor shows. A second channel asking
     * "how are you" would be a second round trip for two floats that are already going the same way.
     */
    public static byte[] encode(List<NetworkDirectory.Entry> entries, double tps, double mspt)
            throws IOException {
        if (entries.size() > MAX_NETWORKS) {
            throw new IOException("Too many announced networks");
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
        }
        out.writeDouble(tps);
        out.writeDouble(mspt);
        return bytes.toByteArray();
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

    private static Metrics lastMetrics = Metrics.UNKNOWN;

    public static List<NetworkDirectory.Entry> decode(byte[] payload) throws IOException {
        lastMetrics = Metrics.UNKNOWN;
        if (payload == null || payload.length < 12 || payload.length > 256 * 1024) {
            throw new IOException("Network announcement size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC) {
            throw new IOException("Unsupported network announcement");
        }
        int version = in.readInt();
        if (version != VERSION && version != VERSION_WITHOUT_METRICS) {
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
            RemoteNetworkId id = new RemoteNetworkId(1, node, world, dimension, frequency);
            // False by definition: this is a list that arrived from another node.
            entries.add(new NetworkDirectory.Entry(frequency, alias, links, id, false));
        }
        if (version >= VERSION && in.available() >= 16) {
            // Kept as the last thing read rather than returned: the network list is what every
            // caller is here for, and a caller that also wants the numbers asks for them.
            lastMetrics = new Metrics(in.readDouble(), in.readDouble());
        }
        if (in.available() != 0) {
            throw new IOException("Network announcement contains trailing data");
        }
        return List.copyOf(entries);
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
