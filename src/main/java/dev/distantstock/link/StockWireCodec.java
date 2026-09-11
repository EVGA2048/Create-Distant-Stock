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

public final class StockWireCodec {
    private static final int QUERY_MAGIC = 0x44535351;
    private static final int RESULT_MAGIC = 0x44535352;
    private static final int VERSION = 1;
    private static final int MAX_ITEMS = 4096;

    public record Query(UUID queryId, RemoteNetworkId networkId) {
    }

    public record Result(UUID queryId, RemoteNetworkId networkId, List<LinkQueues.Line> items) {
        public Result {
            items = List.copyOf(items);
        }
    }

    public static byte[] encodeQuery(Query query) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(QUERY_MAGIC);
        out.writeInt(VERSION);
        uuid(out, query.queryId());
        network(out, query.networkId());
        return bytes.toByteArray();
    }

    public static Query decodeQuery(byte[] payload) throws IOException {
        DataInputStream in = input(payload, 16 * 1024);
        if (in.readInt() != QUERY_MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported stock query");
        }
        Query query = new Query(uuid(in), network(in));
        end(in);
        return query;
    }

    public static byte[] encodeResult(Result result) throws IOException {
        if (result.items().size() > MAX_ITEMS) {
            throw new IOException("Stock result has too many items");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(RESULT_MAGIC);
        out.writeInt(VERSION);
        uuid(out, result.queryId());
        network(out, result.networkId());
        out.writeInt(result.items().size());
        for (LinkQueues.Line line : result.items()) {
            text(out, line.itemId(), 256);
            out.writeInt(line.count());
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > 1024 * 1024) {
            throw new IOException("Stock result exceeds wire limit");
        }
        return payload;
    }

    public static Result decodeResult(byte[] payload) throws IOException {
        DataInputStream in = input(payload, 1024 * 1024);
        if (in.readInt() != RESULT_MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported stock result");
        }
        UUID queryId = uuid(in);
        RemoteNetworkId networkId = network(in);
        int size = in.readInt();
        if (size < 0 || size > MAX_ITEMS) {
            throw new IOException("Stock result item count is invalid");
        }
        List<LinkQueues.Line> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String item = text(in, 256);
            int count = in.readInt();
            if (count < 0) {
                throw new IOException("Stock count is invalid");
            }
            items.add(new LinkQueues.Line(item, count));
        }
        end(in);
        return new Result(queryId, networkId, items);
    }

    private static void network(DataOutputStream out, RemoteNetworkId id) throws IOException {
        uuid(out, id.nodeId());
        uuid(out, id.worldId());
        text(out, id.dimensionId(), 256);
        uuid(out, id.createFrequency());
    }

    private static RemoteNetworkId network(DataInputStream in) throws IOException {
        return new RemoteNetworkId(1, uuid(in), uuid(in), text(in, 256), uuid(in));
    }

    private static DataInputStream input(byte[] payload, int max) throws IOException {
        if (payload == null || payload.length < 8 || payload.length > max) {
            throw new IOException("Stock payload size is invalid");
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static void text(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) {
            throw new IOException("Stock text exceeds limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > max) {
            throw new IOException("Stock text length is invalid");
        }
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) {
            throw new IOException("Unexpected end of stock payload");
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

    private static void end(DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new IOException("Stock payload contains trailing data");
        }
    }

    private StockWireCodec() {
    }
}
