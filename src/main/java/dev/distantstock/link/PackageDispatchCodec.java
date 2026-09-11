package dev.distantstock.link;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** Strict, versioned wire format for a single parcel dispatch. */
public final class PackageDispatchCodec {
    private static final int MAGIC = 0x4453504b; // DSPK
    private static final int VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    private static final int MAX_ADDRESS_BYTES = 512;
    private static final int MAX_ID_BYTES = 256;
    private static final int MAX_PACKAGE_BYTES = 2 * 1024 * 1024;

    /**
     * On-wire parcel dispatch.
     * <p>{@code wireDigest} is the SHA-256 of the serialised envelope (header + body before the
     * trailing digest). It is only meaningful after {@link #encode}/{@link #decode}; records
     * produced by {@link #create} carry an empty placeholder.</p>
     */
    public record Dispatch(UUID parcelId, UUID receivingDockGroupId, String address,
                           PayloadManifest manifest, String encodedPackage, byte[] wireDigest) {
        public Dispatch {
            wireDigest = Arrays.copyOf(wireDigest, wireDigest.length);
        }

        @Override
        public byte[] wireDigest() {
            return Arrays.copyOf(wireDigest, wireDigest.length);
        }
    }

    public static Dispatch create(UUID parcelId, UUID groupId, String address, String encodedPackage) {
        return create(parcelId, groupId, address, PayloadManifest.EMPTY, encodedPackage);
    }

    public static Dispatch create(UUID parcelId, UUID groupId, String address,
                                  PayloadManifest manifest, String encodedPackage) {
        return new Dispatch(parcelId, groupId, address == null ? "" : address,
                manifest == null ? PayloadManifest.EMPTY : manifest, encodedPackage, new byte[0]);
    }

    public static byte[] encode(Dispatch dispatch) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeUuid(out, dispatch.parcelId());
            writeUuid(out, dispatch.receivingDockGroupId());
            writeString(out, dispatch.address(), MAX_ADDRESS_BYTES);
            writeManifest(out, dispatch.manifest());
            writeString(out, dispatch.encodedPackage(), MAX_PACKAGE_BYTES);
            byte[] body = bytes.toByteArray();
            byte[] wireDigest = digest(body);
            out.writeInt(wireDigest.length);
            out.write(wireDigest);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode parcel dispatch", exception);
        }
    }

    public static Dispatch decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_PACKAGE_BYTES + 1024 * 1024) {
            throw new IOException("Parcel dispatch payload has an invalid size");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC) {
            throw new IOException("Unsupported parcel dispatch format");
        }
        int version = in.readInt();
        if (version != VERSION && version != LEGACY_VERSION) {
            throw new IOException("Unsupported parcel dispatch format");
        }
        UUID parcelId = readUuid(in);
        UUID groupId = readUuid(in);
        String address = readString(in, MAX_ADDRESS_BYTES);
        PayloadManifest manifest = version >= VERSION ? readManifest(in) : PayloadManifest.EMPTY;
        String encodedPackage = readString(in, MAX_PACKAGE_BYTES);
        int digestLength = in.readInt();
        if (digestLength != 32) {
            throw new IOException("Parcel dispatch digest length is invalid");
        }
        byte[] expected = in.readNBytes(digestLength);
        byte[] actual = version == LEGACY_VERSION
                ? digest(encodedPackage)
                : digest(Arrays.copyOf(payload, payload.length - Integer.BYTES - digestLength));
        if (expected.length != digestLength || in.available() != 0
                || !MessageDigest.isEqual(expected, actual)) {
            throw new IOException("Parcel dispatch is incomplete or corrupt");
        }
        return new Dispatch(parcelId, groupId, address, manifest, encodedPackage, expected);
    }

    private static void writeManifest(DataOutputStream out, PayloadManifest manifest) throws IOException {
        writeIds(out, manifest.itemIds());
        writeIds(out, manifest.componentIds());
    }

    private static PayloadManifest readManifest(DataInputStream in) throws IOException {
        return new PayloadManifest(readIds(in), readIds(in));
    }

    private static void writeIds(DataOutputStream out, java.util.List<String> ids) throws IOException {
        if (ids.size() > PayloadManifest.MAX_ENTRIES) {
            throw new IOException("Manifest entry count exceeds limit");
        }
        out.writeInt(ids.size());
        for (String id : ids) {
            writeString(out, id, MAX_ID_BYTES);
        }
    }

    private static java.util.List<String> readIds(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > PayloadManifest.MAX_ENTRIES) {
            throw new IOException("Manifest entry count is invalid");
        }
        java.util.List<String> ids = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(readString(in, MAX_ID_BYTES));
        }
        return java.util.List.copyOf(ids);
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IOException("String exceeds wire limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > maxBytes) {
            throw new IOException("String has an invalid wire length");
        }
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) {
            throw new IOException("Unexpected end of parcel dispatch");
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

    private static byte[] digest(String encodedPackage) {
        return digest(encodedPackage.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private PackageDispatchCodec() {
    }
}
