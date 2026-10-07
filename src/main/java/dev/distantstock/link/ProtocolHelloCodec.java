package dev.distantstock.link;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Independent compatibility hello. Kept outside network announcements so old peers still decode them. */
public final class ProtocolHelloCodec {
    private static final int MAGIC = 0x4453484c; // DSHL
    private static final int VERSION = 1;
    private static final int MAX_CAPS = 64;
    private static final int MAX_TEXT = 96;

    public record Hello(int protocol, Set<String> capabilities) {
        public Hello {
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        }
    }

    public static byte[] encode(Hello hello) throws IOException {
        if (hello.capabilities().size() > MAX_CAPS) throw new IOException("Too many capabilities");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(hello.protocol());
        out.writeInt(hello.capabilities().size());
        for (String cap : hello.capabilities()) string(out, cap);
        return bytes.toByteArray();
    }

    public static Hello decode(byte[] payload) throws IOException {
        if (payload == null || payload.length < 16 || payload.length > 8192) {
            throw new IOException("Protocol hello size is invalid");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported protocol hello");
        }
        int protocol = in.readInt();
        int count = in.readInt();
        if (protocol < 0 || count < 0 || count > MAX_CAPS) throw new IOException("Invalid protocol hello");
        Set<String> capabilities = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) capabilities.add(string(in));
        if (in.available() != 0) throw new IOException("Protocol hello contains trailing data");
        return new Hello(protocol, capabilities);
    }

    private static void string(DataOutputStream out, String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IOException("Protocol capability too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String string(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_TEXT) throw new IOException("Protocol capability length is invalid");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Unexpected end of protocol hello");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private ProtocolHelloCodec() {
    }
}
