package dev.distantstock.link;

import dev.distantstock.routing.RemoteNetworkId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Versioned, bounded order request wire format. Sending waits for a durable target inbox. */
public final class OrderRequestCodec {
    private static final int MAGIC = 0x44534f52;
    /** Version 1 carried one address; 2 appends the address the parcel wears once it is home. */
    private static final int VERSION = 2;
    private static final int VERSION_WITHOUT_HOME_ADDRESS = 1;
    private static final int MAX_LINES = 512;
    private static final int MAX_TEXT_BYTES = 512;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /**
     * One order, as the server that will pack it needs to read it.
     *
     * <p>Two addresses, because a parcel needs one on each side of a crossing and the two servers
     * have their own door names. {@code address} is the one it is packed with — the one the packing
     * server's own network sorts it by. {@code homeAddress} is the one it must wear once it arrives
     * on the ordering side; blank when the goods are staying where they were packed, which is a
     * perfectly ordinary order and the reason this field is optional rather than required.
     */
    public record Request(RemoteNetworkId networkId, UUID receivingDockGroupId,
                          UUID correlationId, UUID childOrderId, String address, String homeAddress,
                          List<LinkQueues.Line> lines) {
        public Request {
            address = address == null ? "" : address;
            homeAddress = homeAddress == null ? "" : homeAddress;
            lines = List.copyOf(lines);
        }

        /** An order with one address: what a build before home addresses sent, and still sends. */
        public Request(RemoteNetworkId networkId, UUID receivingDockGroupId, UUID correlationId,
                       UUID childOrderId, String address, List<LinkQueues.Line> lines) {
            this(networkId, receivingDockGroupId, correlationId, childOrderId, address, "", lines);
        }
    }

    public static byte[] encode(Request request) throws IOException {
        if (request.lines().isEmpty() || request.lines().size() > MAX_LINES) {
            throw new IOException("Order line count is invalid");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        RemoteNetworkId id = request.networkId();
        writeUuid(out, id.nodeId());
        writeUuid(out, id.worldId());
        writeString(out, id.dimensionId());
        writeUuid(out, id.createFrequency());
        writeUuid(out, request.receivingDockGroupId());
        writeUuid(out, request.correlationId());
        writeUuid(out, request.childOrderId());
        writeString(out, request.address());
        writeString(out, request.homeAddress());
        out.writeInt(request.lines().size());
        for (LinkQueues.Line line : request.lines()) {
            if (line.count() <= 0) {
                throw new IOException("Order line count must be positive");
            }
            writeString(out, line.itemId());
            out.writeInt(line.count());
        }
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Order payload exceeds limit");
        }
        return result;
    }

    public static Request decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Order payload size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC) {
            throw new IOException("Unsupported order request format");
        }
        int version = in.readInt();
        if (version != VERSION && version != VERSION_WITHOUT_HOME_ADDRESS) {
            throw new IOException("Unsupported order request format");
        }
        RemoteNetworkId networkId = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                readUuid(in), readUuid(in), readString(in), readUuid(in));
        UUID group = readUuid(in);
        UUID correlation = readUuid(in);
        UUID child = readUuid(in);
        String address = readString(in);
        // A version 1 order has no home address: it was sent by a build that had only one address
        // to give, and reading a field that is not there would eat the line count. Both this server
        // and the sender are usually the same build, but a rolling restart is exactly when this
        // matters — the two halves of a pair are restarted one at a time.
        String homeAddress = version >= VERSION ? readString(in) : "";
        int count = in.readInt();
        if (count <= 0 || count > MAX_LINES) {
            throw new IOException("Order line count is invalid");
        }
        List<LinkQueues.Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String itemId = readString(in);
            int amount = in.readInt();
            if (amount <= 0) {
                throw new IOException("Order line count must be positive");
            }
            lines.add(new LinkQueues.Line(itemId, amount));
        }
        if (in.available() != 0) {
            throw new IOException("Order payload contains trailing data");
        }
        return new Request(networkId, group, correlation, child, address, homeAddress, lines);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IOException("Order text exceeds limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) {
            throw new IOException("Order text length is invalid");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected end of order request");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private OrderRequestCodec() {
    }
}
