package dev.distantstock.link;

import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Small wire format for joining one Create logistics network to a Distant Stock network. */
public final class DistantNetworkJoinCodec {
    private static final int MAGIC_REQUEST = 0x44534a52; // DSJR
    private static final int MAGIC_ACCEPT = 0x44534a41;  // DSJA
    private static final int VERSION = 2;
    private static final int MAX_TEXT = 256;

    public record Request(UUID requestId, String joinCode, RemoteNetworkId member) {
    }

    public record Accept(UUID requestId, UUID networkId, String networkName, UUID ownerNode,
                         RemoteNetworkId member) {
    }

    public static byte[] encodeRequest(Request request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC_REQUEST);
        out.writeInt(VERSION);
        uuid(out, request.requestId());
        string(out, DistantNetworkDirectory.normalizeCode(request.joinCode()));
        out.writeBoolean(request.member() != null);
        if (request.member() != null) network(out, request.member());
        return bytes.toByteArray();
    }

    public static Request decodeRequest(byte[] payload) throws IOException {
        DataInputStream in = input(payload, MAGIC_REQUEST);
        Request request = new Request(uuid(in), string(in), in.readBoolean() ? network(in) : null);
        if (in.available() != 0) throw new IOException("Trailing join-request data");
        return request;
    }

    public static byte[] encodeAccept(Accept accept) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC_ACCEPT);
        out.writeInt(VERSION);
        uuid(out, accept.requestId());
        uuid(out, accept.networkId());
        string(out, accept.networkName());
        uuid(out, accept.ownerNode());
        out.writeBoolean(accept.member() != null);
        if (accept.member() != null) network(out, accept.member());
        return bytes.toByteArray();
    }

    public static Accept decodeAccept(byte[] payload) throws IOException {
        DataInputStream in = input(payload, MAGIC_ACCEPT);
        Accept accept = new Accept(uuid(in), uuid(in), string(in), uuid(in),
                in.readBoolean() ? network(in) : null);
        if (in.available() != 0) throw new IOException("Trailing join-accept data");
        return accept;
    }

    private static DataInputStream input(byte[] payload, int magic) throws IOException {
        if (payload == null || payload.length < 8 || payload.length > 16 * 1024) {
            throw new IOException("Join payload size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != magic || in.readInt() != VERSION) {
            throw new IOException("Unsupported join payload");
        }
        return in;
    }

    private static void network(DataOutputStream out, RemoteNetworkId id) throws IOException {
        uuid(out, id.nodeId());
        uuid(out, id.worldId());
        string(out, id.dimensionId());
        uuid(out, id.createFrequency());
    }

    private static RemoteNetworkId network(DataInputStream in) throws IOException {
        try {
            return new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                    uuid(in), uuid(in), string(in), uuid(in));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Create network identity", exception);
        }
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        if (id == null) throw new IOException("Missing UUID");
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IOException("Text field is too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String string(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_TEXT || length > in.available()) throw new IOException("Text field is invalid");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private DistantNetworkJoinCodec() {
    }
}
