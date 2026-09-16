package dev.distantstock.routing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The codes a server hands out for its own dock groups, and the one place they are spent.
 *
 * <p>This is the answer to the one question cross-server play cannot otherwise answer: <b>how does
 * a player on the other server name a group here?</b> Group ids are UUIDs, group names are free
 * text that only means anything in the directory that holds them, and the receiving player cannot
 * walk over to this server and right-click the dock. So the owner of a group mints a code, the code
 * travels by whatever the two players already use to talk, and the other server redeems it into a
 * destination it can point orders at. See {@code docs/design/DOCK-UX-V2.zh-CN.md} for the whole
 * design; what follows is only what the file has to get right.
 *
 * <p><b>A code is a capability, not a name.</b> Redeeming it does not ask this server for
 * permission again — it hands over the group's id, and from then on the other server's orders name
 * that id like any local one. So a code is short-lived (ten minutes by default), single-use, and
 * revocable by the owner, which is the same shape as everything else that has to travel through
 * chat. It is not a security boundary: the group's own switches still decide who may add a dock to
 * it or point a sender at it here.
 *
 * <p><b>Ownership is checked by the caller.</b> Minting a code for somebody else's group would let
 * anyone open a warehouse they do not own, so {@code PairCodeC2S} and the command both ask
 * {@link DockGroup#ownedBy} first; this class only mints.
 */
public final class PairingCodes extends SavedData {
    /**
     * The alphabet a code is drawn from: no {@code 0/O}, no {@code 1/I/L}, nothing a player can
     * misread when copying it out of chat or reading it over voice.
     */
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;
    public static final int DEFAULT_MINUTES = 10;
    public static final int MAX_MINUTES = 60;

    private static final String DATA_NAME = "distantstock_pairing_codes";
    private static final Factory<PairingCodes> FACTORY =
            new Factory<>(PairingCodes::new, PairingCodes::load);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Tried before giving up on a collision. At six characters from thirty-one, one is plenty. */
    private static final int MINT_ATTEMPTS = 64;

    /**
     * One code, as the file holds it.
     *
     * @param issuer the player who minted it, or null when nobody did — an operator at the server
     *               console has no player behind the command. It is a name for the log, not a
     *               permission: nothing is decided by it, which is why its absence is a null and
     *               not a reason to refuse the mint.
     */
    public record Code(String code, UUID group, UUID issuer, long expiresAt, int usesLeft) {
        public boolean live(long now) {
            return usesLeft > 0 && now < expiresAt;
        }
    }

    /** What a successful claim hands back to the redeeming server. */
    public record Grant(UUID group, String name, UUID issuer, long expiresAt) {
    }

    private final Map<String, Code> codes = new LinkedHashMap<>();

    public static PairingCodes get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Mints a code for one group, valid for {@code minutes} and good for a single redemption.
     *
     * <p>The expiry is stored as an absolute time rather than a countdown because the file outlives
     * the server process: a code minted a minute before a restart must not get a fresh ten minutes
     * on the way back up.
     */
    public Code issue(UUID group, UUID issuer, int minutes, long now) {
        prune(now);
        int span = Math.clamp(minutes, 1, MAX_MINUTES);
        long expiresAt = now + span * 60_000L;
        for (int attempt = 0; attempt < MINT_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (codes.containsKey(candidate)) {
                continue;
            }
            Code code = new Code(candidate, group, issuer, expiresAt, 1);
            codes.put(candidate, code);
            setDirty();
            return code;
        }
        throw new IllegalStateException("Could not mint a pairing code");
    }

    /**
     * Spends one use of a code, if it is still live and still names a group.
     *
     * <p>The typed string is normalized first — case, spaces and the hyphens people add when they
     * read a code out loud are all noise, and refusing "abc-123" because it was not "ABC123" would
     * be a machine being pedantic at a human who did everything right.
     *
     * <p>A code whose group has been deleted in the meantime is spent and refused rather than left
     * live: what it points at is gone, and a code that keeps failing at the last step is worse than
     * one that fails here.
     */
    public Optional<Grant> claim(DockGroupDirectory directory, String typed, long now) {
        String key = normalize(typed);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Code code = codes.get(key);
        if (code == null || !code.live(now)) {
            return Optional.empty();
        }
        DockGroup group = directory.find(code.group()).orElse(null);
        spend(code, now);
        if (group == null) {
            return Optional.empty();
        }
        return Optional.of(new Grant(group.id(), group.name(), code.issuer(), code.expiresAt()));
    }

    /** Withdraws a code before anyone spends it. The second half of the owner's control over it. */
    public boolean revoke(String typed) {
        if (codes.remove(normalize(typed)) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** The codes still live now, newest first, for the list an owner reads. */
    public java.util.List<Code> live(long now) {
        prune(now);
        java.util.List<Code> out = new java.util.ArrayList<>(codes.values());
        out.sort(java.util.Comparator.comparingLong(Code::expiresAt).reversed());
        return java.util.List.copyOf(out);
    }

    /** How a code is compared and stored: uppercase, no spaces, no hyphens. */
    public static String normalize(String typed) {
        if (typed == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < typed.length() && out.length() < CODE_LENGTH; i++) {
            char c = Character.toUpperCase(typed.charAt(i));
            if (c == ' ' || c == '-' || c == '_') {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Whether a typed string could be one of ours at all, so a typo is refused before a lookup. */
    public static boolean wellFormed(String typed) {
        String key = normalize(typed);
        if (key.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (ALPHABET.indexOf(key.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private void spend(Code code, long now) {
        int left = code.usesLeft() - 1;
        if (left <= 0) {
            codes.remove(code.code());
        } else {
            codes.put(code.code(), new Code(code.code(), code.group(), code.issuer(),
                    code.expiresAt(), left));
        }
        // An expired code is dropped here rather than left for the next prune: the claim that just
        // spent it is the last moment anything will look at it.
        prune(now);
        setDirty();
    }

    private void prune(long now) {
        if (codes.values().removeIf(code -> !code.live(now))) {
            setDirty();
        }
    }

    private static String randomCode() {
        StringBuilder out = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (Code code : codes.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Code", code.code());
            entry.putUUID("Group", code.group());
            // Written only when there is something to write. A code minted from the console has no
            // player behind it, and the issuer is a name for the log — putting a null UUID here
            // throws out of the middle of a save, which is a crash on the way down rather than a
            // missing field. The same rule DockGroupDirectory follows for Owner.
            if (code.issuer() != null) {
                entry.putUUID("Issuer", code.issuer());
            }
            entry.putLong("Expires", code.expiresAt());
            entry.putInt("Uses", code.usesLeft());
            entries.add(entry);
        }
        tag.put("Codes", entries);
        return tag;
    }

    /**
     * Reads the file back.
     *
     * <p>Public rather than private so a game test can round-trip a file it built itself.
     * What it protects against is not a parsing bug but a writing one — a field that cannot be
     * written is only visible at save time, and save time is when a server is going down.
     */
    public static PairingCodes load(CompoundTag tag, HolderLookup.Provider registries) {
        PairingCodes data = new PairingCodes();
        ListTag entries = tag.getList("Codes", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("Group")) {
                continue;
            }
            String code = normalize(entry.getString("Code"));
            if (code.length() != CODE_LENGTH) {
                continue;
            }
            // Absent for a code nobody with a name minted. Read as absent rather than as a reason
            // to drop the row: the code still works, and which operator typed it is not what it is
            // for.
            UUID issuer = entry.hasUUID("Issuer") ? entry.getUUID("Issuer") : null;
            data.codes.put(code, new Code(code, entry.getUUID("Group"), issuer,
                    entry.getLong("Expires"), Math.max(0, entry.getInt("Uses"))));
        }
        return data;
    }
}
