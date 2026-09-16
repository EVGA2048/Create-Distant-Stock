package dev.distantstock;

import dev.distantstock.link.PairingWireCodec;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.PairingCodes;
import dev.distantstock.routing.RemoteGroups;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.util.UUID;

/**
 * Pairing codes: minted here, spendable once, and what arrives when one is spent.
 *
 * <p>Almost all of this is arithmetic on two files, so it is checked without a world — the one part
 * that needs servers is the crossing itself, and that is the part a game test cannot reach: it
 * needs two of them, which is what {@code docs/dev/TRANSERVER-TEST.zh-CN.md} is for. What is
 * checked here is every rule that decides whether a crossing would succeed or fail silently.
 *
 * <p>The files are built fresh per case rather than read from the save. The game test runner shares
 * one level between cases running in parallel, and a case that wrote a live code into the world's
 * own directory would be handing it to whatever else ran at the same moment.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class PairingGameTests {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000aa02");

    /**
     * A minted code looks like a code, and spending it is the end of it.
     *
     * <p>Single use is the whole of what makes a short code safe to paste into a chat: anybody who
     * reads it may use it, and after that nobody may. A code that kept working would turn a
     * momentary leak into a permanent key to somebody's warehouse.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aCodeIsSpentOnce(GameTestHelper h) {
        DockGroupDirectory directory = new DockGroupDirectory();
        DockGroup group = directory.createFor("测试仓库", OWNER);
        PairingCodes codes = new PairingCodes();

        PairingCodes.Code code = codes.issue(group.id(), OWNER, PairingCodes.DEFAULT_MINUTES, 1_000L);
        h.assertTrue(PairingCodes.wellFormed(code.code()), "a minted code is not well formed: " + code.code());
        h.assertTrue(code.code().length() == PairingCodes.CODE_LENGTH,
                "a minted code is " + code.code().length() + " characters");

        h.assertTrue(codes.claim(directory, code.code(), 2_000L).isPresent(),
                "a fresh code was refused");
        h.assertTrue(codes.claim(directory, code.code(), 2_000L).isEmpty(),
                "the same code worked twice");
        // Typed the way a player would: lower case, with the hyphen they read out loud.
        PairingCodes.Code second = codes.issue(group.id(), OWNER, PairingCodes.DEFAULT_MINUTES, 1_000L);
        String typed = second.code().substring(0, 3).toLowerCase() + "-" + second.code().substring(3);
        h.assertTrue(codes.claim(directory, typed, 2_000L).isPresent(),
                "a code typed in lower case with a hyphen was refused");
        h.succeed();
    }

    /** Expiry and revocation, which are the two ways a code stops working before it is spent. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void expiredAndRevokedCodesAreDead(GameTestHelper h) {
        DockGroupDirectory directory = new DockGroupDirectory();
        DockGroup group = directory.createFor("测试仓库", OWNER);
        PairingCodes codes = new PairingCodes();

        PairingCodes.Code shortLived = codes.issue(group.id(), OWNER, 1, 1_000L);
        h.assertTrue(codes.claim(directory, shortLived.code(), 1_000L + 59_000L).isPresent(),
                "a code died before its minute was up");
        PairingCodes.Code expired = codes.issue(group.id(), OWNER, 1, 1_000L);
        h.assertTrue(codes.claim(directory, expired.code(), 1_000L + 61_000L).isEmpty(),
                "an expired code still worked");
        h.assertTrue(codes.live(1_000L + 61_000L).isEmpty(), "an expired code is still listed");

        PairingCodes.Code revoked = codes.issue(group.id(), OWNER, PairingCodes.DEFAULT_MINUTES, 5_000L);
        h.assertTrue(codes.revoke(revoked.code()), "revoking a live code reported nothing removed");
        h.assertTrue(codes.claim(directory, revoked.code(), 6_000L).isEmpty(),
                "a revoked code still worked");

        // A code whose group was deleted in the meantime is spent and refused: what it pointed at
        // is gone, and a code that keeps failing at the last step is worse than one that fails here.
        PairingCodes.Code orphan = codes.issue(group.id(), OWNER, PairingCodes.DEFAULT_MINUTES, 7_000L);
        directory.delete(group.id());
        h.assertTrue(codes.claim(directory, orphan.code(), 8_000L).isEmpty(),
                "a code for a deleted group was accepted");
        h.assertTrue(codes.claim(directory, orphan.code(), 8_000L).isEmpty(),
                "a code for a deleted group survived being spent");
        h.succeed();
    }

    /** The two messages that cross a server boundary, and what is refused on the way in. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theWireFormatSurvivesTheCrossing(GameTestHelper h) throws IOException {
        UUID correlation = UUID.randomUUID();
        UUID node = UUID.randomUUID();
        UUID group = UUID.randomUUID();

        PairingWireCodec.Claim claim = new PairingWireCodec.Claim(correlation, "ABC234", "甲");
        h.assertTrue(PairingWireCodec.decodeClaim(PairingWireCodec.encodeClaim(claim)).equals(claim),
                "a claim did not survive its own codec");

        PairingWireCodec.Grant grant = new PairingWireCodec.Grant(correlation, true, node, group,
                "乙服的仓库", "");
        h.assertTrue(PairingWireCodec.decodeGrant(PairingWireCodec.encodeGrant(grant)).equals(grant),
                "a grant did not survive its own codec");
        PairingWireCodec.Grant refused = PairingWireCodec.Grant.refused(correlation, node, "unknown");
        h.assertTrue(PairingWireCodec.decodeGrant(PairingWireCodec.encodeGrant(refused)).equals(refused),
                "a refusal did not survive its own codec");

        // An accepted grant with no group in it would write a destination that is not one.
        byte[] headless = PairingWireCodec.encodeGrant(
                new PairingWireCodec.Grant(correlation, true, node, null, "x", ""));
        try {
            PairingWireCodec.decodeGrant(headless);
            h.fail("an accepted grant without a group was accepted");
        } catch (IOException expected) {
            // The one answer that must never be produced.
        }
        try {
            PairingWireCodec.decodeClaim(new byte[64]);
            h.fail("a claim of zeroes was accepted");
        } catch (IOException expected) {
            // A message that is not ours is refused rather than guessed at.
        }
        h.succeed();
    }

    /** What the redeeming server keeps, and the two ways a screen looks a destination up. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aRedeemedGroupIsRemembered(GameTestHelper h) {
        RemoteGroups remotes = new RemoteGroups();
        UUID node = UUID.randomUUID();
        UUID group = UUID.randomUUID();

        remotes.add(new RemoteGroups.Entry(node, group, "乙服的仓库", "3f9a21c4", 0), 1_000L);
        h.assertTrue(remotes.find(group).isPresent(), "a remembered group is not found by id");
        h.assertTrue(remotes.findByName("乙服的仓库").isPresent(), "a remembered group is not found by name");
        h.assertTrue(remotes.findByName("3f9a21c4·乙服的仓库").isPresent(),
                "a remembered group is not found by what the list draws");
        h.assertTrue(remotes.findByName("别的组").isEmpty(), "a name nobody wrote was found");
        h.assertTrue(remotes.all().getFirst().display().equals("3f9a21c4·乙服的仓库"),
                "a remembered group does not read as where it is from: "
                        + remotes.all().getFirst().display());

        // Re-pairing the same group replaces the row rather than adding a second one: two rows for
        // one destination is a list where picking either looks the same and only one is right.
        remotes.add(new RemoteGroups.Entry(node, group, "改过名的仓库", "3f9a21c4", 0), 2_000L);
        h.assertTrue(remotes.all().size() == 1, "re-pairing the same group made a second row");
        h.assertTrue(remotes.findByName("改过名的仓库").isPresent(), "the second pairing did not land");
        h.assertTrue(remotes.forget(group), "forgetting a remembered group reported nothing removed");
        h.assertTrue(remotes.all().isEmpty(), "a forgotten group is still listed");
        h.succeed();
    }

    /**
     * A code minted here is refused here, and says where to look instead.
     *
     * <p>Redeeming a local code would write a destination that the local half of the mod then
     * refuses at order time, because a group on this server is gated by ownership. Refusing now,
     * with the group's name, is the only answer that does not waste the player's next ten minutes.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aLocalCodeIsNotADestination(GameTestHelper h) {
        DockGroupDirectory directory = new DockGroupDirectory();
        DockGroup group = directory.createFor("本服仓库", OWNER);
        PairingCodes codes = new PairingCodes();

        PairingCodes.Code code = codes.issue(group.id(), OWNER, PairingCodes.DEFAULT_MINUTES, 1_000L);
        var claimed = codes.claim(directory, code.code(), 2_000L);
        h.assertTrue(claimed.isPresent(), "a live local code did not claim");
        h.assertTrue(claimed.get().name().equals(group.name()),
                "the claim came back with the wrong group name");
        // And what the player is told to use instead is a real name in this directory.
        h.assertTrue(directory.findByName(claimed.get().name()).isPresent(),
                "the name the claim handed back is not a group here");
        h.assertTrue(!group.admits(STRANGER), "a closed group admits a stranger");
        h.succeed();
    }

    /**
     * A server's own codes prune themselves.
     *
     * <p>The list is what the owner reads and what the file holds; a code that has expired is not a
     * code, and leaving it in either place would make the file grow with every mint for as long as
     * the world lives.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theFileDoesNotGrowForever(GameTestHelper h) {
        PairingCodes codes = new PairingCodes();
        UUID group = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            codes.issue(group, OWNER, 1, 1_000L + i);
        }
        h.assertTrue(codes.live(1_100L).size() == 8, "not every minted code is live");
        h.assertTrue(codes.live(1_000L + 61_000L).isEmpty(),
                "expired codes are still in the list after a prune");
        h.succeed();
    }

    /**
     * A code minted from the console survives being written to the file.
     *
     * <p>This is a regression test with a crash behind it. A code minted by an operator at the
     * server console has no player behind it, so its issuer is null — and the save wrote that null
     * straight into an NBT UUID, which throws <em>out of the middle of a save</em>. The server did
     * not fail to write one file: it failed to finish saving at all, and the watchdog killed it a
     * minute later with "a single server tick took 60 seconds". Nothing in the game pointed at the
     * pairing code; the stack trace was the only clue, on the way down.
     *
     * <p>So the test does what the save does: mint without an issuer, save, load, and check the
     * code still works. A file that cannot round-trip is a file that takes the server with it.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aCodeWithNoIssuerSurvivesItsFile(GameTestHelper h) {
        DockGroupDirectory directory = new DockGroupDirectory();
        DockGroup group = directory.createFor("控制台仓库", null);
        PairingCodes codes = new PairingCodes();
        PairingCodes.Code code = codes.issue(group.id(), null, PairingCodes.DEFAULT_MINUTES, 1_000L);

        CompoundTag saved = codes.save(new CompoundTag(), null);
        PairingCodes loaded = PairingCodes.load(saved, null);

        h.assertTrue(loaded.live(2_000L).size() == 1,
                "a code minted from the console did not survive its own file");
        // Read before the claim, which spends the code and takes it out of the list.
        h.assertTrue(loaded.live(2_000L).getFirst().issuer() == null,
                "an issuer appeared from nowhere");
        h.assertTrue(loaded.claim(directory, code.code(), 2_000L).isPresent(),
                "the code that came back cannot be claimed");
        h.succeed();
    }

    /**
     * A peer's own name comes back with its file, and is what a readout uses.
     *
     * <p>The name is the whole point of remembering it: it is what a destination list, a parcel's
     * tooltip and a pairing message print where they used to print eight characters of a uuid. A
     * name that did not survive the restart would put those back to reading "5d2a90b4" after every
     * reboot — which is exactly when a player is looking at the list trying to work out who is who.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aPeerNameSurvivesItsFile(GameTestHelper h) {
        dev.distantstock.routing.PeerNames names = new dev.distantstock.routing.PeerNames();
        names.remember(OWNER, "远仓B");
        h.assertTrue("远仓B".equals(names.name(OWNER)), "the name was not kept in memory");
        h.assertTrue(names.name(STRANGER).isEmpty(), "a node nobody named reported a name");

        dev.distantstock.routing.PeerNames loaded = dev.distantstock.routing.PeerNames.load(
                names.save(new CompoundTag(), null), null);
        h.assertTrue("远仓B".equals(loaded.name(OWNER)), "the name did not survive its own file");

        // Re-remembering the same node is how a rename arrives: the last one heard wins.
        loaded.remember(OWNER, "远仓乙");
        h.assertTrue("远仓乙".equals(loaded.name(OWNER)), "a renamed node kept its old name");
        loaded.remember(OWNER, "   ");
        h.assertTrue("远仓乙".equals(loaded.name(OWNER)), "a blank alias overwrote a real one");

        // And with no directory to ask, a node is still called something rather than nothing.
        h.assertTrue(dev.distantstock.link.PairingService.label(STRANGER, null)
                        .equals(STRANGER.toString().substring(0, 8)),
                "a node with no name printed nothing at all");
        h.succeed();
    }

    private PairingGameTests() {
    }
}
