package dev.distantstock.link;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Small wire receipt that feeds the source-side parcel flight recorder. */
public final class PackageTraceCodec {
    private static final int MAGIC = 0x44535452; // DSTR
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 256;

    public record Notice(UUID parcelId, long at, String stage, String detail) {
    }

    public static byte[] encode(Notice notice) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(notice.parcelId().getMostSignificantBits());
        out.writeLong(notice.parcelId().getLeastSignificantBits());
        out.writeLong(notice.at());
        string(out, notice.stage());
        string(out, notice.detail());
        return bytes.toByteArray();
    }

    public static Notice decode(byte[] payload) throws IOException {
        if (payload == null || payload.length < 32 || payload.length > 2048) {
            throw new IOException("Package trace size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported package trace");
        }
        UUID parcel = new UUID(in.readLong(), in.readLong());
        long at = in.readLong();
        String stage = string(in);
        String detail = string(in);
        if (in.available() != 0) throw new IOException("Package trace contains trailing data");
        return new Notice(parcel, at, stage, detail);
    }

    private static void string(DataOutputStream out, String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IOException("Package trace text exceeds limit");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String string(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_TEXT) throw new IOException("Package trace text length is invalid");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Unexpected end of package trace");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private PackageTraceCodec() {
    }
}
