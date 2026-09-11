package dev.distantstock.link;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Target to source notice that lists the registry entries a rejected parcel needs. The source server owns
 * the mods, so only it can physically remove those items and hand them back to its own logistics.
 *
 * <p>Transerver reports a rejection as a bare state without a payload, which is why this is a separate
 * channel instead of a field on the delivery result.
 */
public final class PackageStripCodec {
    private static final int MAGIC = 0x44535354; // DSST
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_ID_BYTES = 256;
    private static final int MAX_DETAIL_BYTES = 256;

    public record Notice(UUID parcelId, List<String> missingIds, String detail) {
        public Notice {
            missingIds = List.copyOf(missingIds);
            detail = detail == null ? "" : detail;
        }
    }

    public static byte[] encode(Notice notice) throws IOException {
        if (notice.missingIds().isEmpty() || notice.missingIds().size() > MAX_ENTRIES) {
            throw new IOException("Strip notice entry count is invalid");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        writeUuid(out, notice.parcelId());
        out.writeInt(notice.missingIds().size());
        for (String id : notice.missingIds()) {
            writeString(out, id, MAX_ID_BYTES);
        }
        writeString(out, notice.detail(), MAX_DETAIL_BYTES);
        return bytes.toByteArray();
    }

    public static Notice decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            throw new IOException("Strip notice payload is empty");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported strip notice format");
        }
        UUID parcelId = readUuid(in);
        int count = in.readInt();
        if (count <= 0 || count > MAX_ENTRIES) {
            throw new IOException("Strip notice entry count is invalid");
        }
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(readString(in, MAX_ID_BYTES));
        }
        String detail = readString(in, MAX_DETAIL_BYTES);
        if (in.available() != 0) {
            throw new IOException("Strip notice contains trailing data");
        }
        return new Notice(parcelId, ids, detail);
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IOException("Strip notice text exceeds limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maxBytes) {
            throw new IOException("Strip notice text length is invalid");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected end of strip notice");
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

    private PackageStripCodec() {
    }
}
