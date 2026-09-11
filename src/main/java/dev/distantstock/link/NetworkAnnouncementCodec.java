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
    private static final int VERSION = 1;
    private static final int MAX_NETWORKS = 256;
    private static final int MAX_TEXT = 256;

    public static byte[] encode(List<NetworkDirectory.Entry> entries) throws IOException {
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
        return bytes.toByteArray();
    }

    public static List<NetworkDirectory.Entry> decode(byte[] payload) throws IOException {
        if (payload == null || payload.length < 12 || payload.length > 256 * 1024) {
            throw new IOException("Network announcement size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
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
            entries.add(new NetworkDirectory.Entry(frequency, alias, links, id));
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
