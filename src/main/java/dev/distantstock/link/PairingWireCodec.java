package dev.distantstock.link;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The two messages a pairing code needs to cross a server boundary.
 *
 * <p>A claim goes out from the server the player is on to every node it knows, because the code
 * itself says nothing about where it was minted — a six-character code has no room for a node id,
 * and asking each peer is both simpler and self-correcting: whichever server actually minted it
 * answers, and the rest say nothing at all.
 *
 * <p>A grant comes back to the asking node with the group's id and name. That is the whole point of
 * the exchange: the group id is the thing that could never be typed, and the name is what the
 * player will see in the destination list.
 *
 * <p>Both are small, bounded and checked the way the stock codec is: a magic number, a version, and
 * a length limit on the one string that could otherwise be an allocation.
 */
public final class PairingWireCodec {
    private static final int CLAIM_MAGIC = 0x44535043;
    private static final int GRANT_MAGIC = 0x44535047;
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 512;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024;

    /** "Do you have this code?" — sent to every known node, answered by at most one. */
    public record Claim(UUID correlationId, String code, String player) {
    }

    /**
     * The answer.
     *
     * <p>A refusal is a real message and not silence: the server that minted the code knows why it
     * did not work — expired, already spent, or its group was deleted — and "no server recognised
     * that code" would send the player looking for a typo that is not there.
     */
    public record Grant(UUID correlationId, boolean accepted, UUID nodeId, UUID groupId,
                        String groupName, String reason) {
        public static Grant refused(UUID correlationId, UUID nodeId, String reason) {
            return new Grant(correlationId, false, nodeId, null, "", reason);
        }
    }

    public static byte[] encodeClaim(Claim claim) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(CLAIM_MAGIC);
        out.writeInt(VERSION);
        uuid(out, claim.correlationId());
        text(out, claim.code());
        text(out, claim.player());
        return finish(bytes);
    }

    public static Claim decodeClaim(byte[] payload) throws IOException {
        DataInputStream in = input(payload);
        if (in.readInt() != CLAIM_MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported pairing claim");
        }
        Claim claim = new Claim(uuid(in), text(in), text(in));
        end(in);
        return claim;
    }

    public static byte[] encodeGrant(Grant grant) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(GRANT_MAGIC);
        out.writeInt(VERSION);
        uuid(out, grant.correlationId());
        out.writeBoolean(grant.accepted());
        uuid(out, grant.nodeId());
        out.writeBoolean(grant.groupId() != null);
        if (grant.groupId() != null) {
            uuid(out, grant.groupId());
        }
        text(out, grant.groupName());
        text(out, grant.reason());
        return finish(bytes);
    }

    public static Grant decodeGrant(byte[] payload) throws IOException {
        DataInputStream in = input(payload);
        if (in.readInt() != GRANT_MAGIC || in.readInt() != VERSION) {
            throw new IOException("Unsupported pairing grant");
        }
        UUID correlationId = uuid(in);
        boolean accepted = in.readBoolean();
        UUID nodeId = uuid(in);
        UUID groupId = in.readBoolean() ? uuid(in) : null;
        Grant grant = new Grant(correlationId, accepted, nodeId, groupId, text(in), text(in));
        end(in);
        if (grant.accepted() && grant.groupId() == null) {
            throw new IOException("Accepted pairing grant without a group");
        }
        return grant;
    }

    private static byte[] finish(ByteArrayOutputStream bytes) throws IOException {
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Pairing payload exceeds limit");
        }
        return payload;
    }

    private static DataInputStream input(byte[] payload) throws IOException {
        if (payload == null || payload.length < 8 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Pairing payload size is invalid");
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IOException("Pairing text exceeds limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_TEXT_BYTES) {
            throw new IOException("Pairing text length is invalid");
        }
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) {
            throw new IOException("Unexpected end of pairing payload");
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
            throw new IOException("Pairing payload contains trailing data");
        }
    }

    private PairingWireCodec() {
    }
}
