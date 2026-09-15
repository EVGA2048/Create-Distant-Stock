package dev.distantstock;

import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockMode;
import dev.distantstock.routing.DockGroupDirectory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Who may use a dock group.
 *
 * <p>The lock is the whole feature, so what is tested is the lock: that a group made by a player
 * turns everyone else away, that opening it lets them in, and that the two cases which must never
 * refuse — the default group and anything that predates ownership — do not.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class DockGroupGameTests {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aClosedGroupAdmitsOnlyItsOwner(GameTestHelper h) {
        DockGroup closed = new DockGroup(UUID.randomUUID(), "private", OWNER, false);
        h.assertTrue(closed.admits(OWNER), "the owner was refused their own group");
        h.assertFalse(closed.admits(STRANGER), "a stranger was let into a closed group");
        h.assertFalse(closed.admits(null), "a nameless player was let into a closed group");
        h.assertTrue(closed.ownedBy(OWNER), "the owner did not own their own group");
        h.assertFalse(closed.ownedBy(STRANGER), "a stranger owned somebody else's group");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOpenGroupAdmitsEveryone(GameTestHelper h) {
        DockGroup open = new DockGroup(UUID.randomUUID(), "public", OWNER, true);
        h.assertTrue(open.admits(STRANGER), "an open group refused a stranger");
        // Being let in is not the same as owning it: only the owner may rename it or change the lock.
        h.assertFalse(open.ownedBy(STRANGER), "an open group made a stranger its owner");
        h.succeed();
    }

    /**
     * A group with no owner admits everyone.
     *
     * <p>This is what the default group is, and what every group in a save made before ownership
     * existed is. Refusing them would lock players out of their own docks on the first load after
     * an update, which is the one outcome this feature must not have.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anUnownedGroupAdmitsEveryone(GameTestHelper h) {
        DockGroup legacy = new DockGroup(UUID.randomUUID(), "old", null, false);
        h.assertTrue(legacy.admits(STRANGER), "an ownerless group refused a player");
        h.assertTrue(legacy.admits(null), "an ownerless group refused a nameless player");
        h.assertFalse(legacy.ownedBy(STRANGER), "an ownerless group reported an owner");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theDefaultGroupStaysOpen(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        DockGroup fallback = directory.require(DockGroupDirectory.DEFAULT_GROUP_ID);
        h.assertTrue(fallback.admits(STRANGER),
                "the default group is closed, so every dock that was never configured is now locked");
        h.assertTrue(fallback.ownedBy(null) == false, "the default group acquired an owner");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aPlayersGroupStartsClosedAndCanBeOpened(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        DockGroup made = directory.createFor("a player's system", OWNER);
        h.assertFalse(made.open(), "a group made by a player started open");
        h.assertTrue(made.ownedBy(OWNER), "a group made by a player was not theirs");
        h.assertFalse(made.admits(STRANGER), "a freshly made group let a stranger in");

        directory.setOpen(made.id(), true);
        h.assertTrue(directory.require(made.id()).admits(STRANGER), "opening a group changed nothing");

        directory.setOpen(made.id(), false);
        h.assertFalse(directory.require(made.id()).admits(STRANGER), "closing a group changed nothing");
        h.succeed();
    }

    /** The name is how a player refers to a system, so it has to find one back. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aGroupIsFoundByItsName(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        DockGroup made = directory.createFor("Found By Name", OWNER);
        h.assertTrue(directory.findByName("found by name").isPresent(),
                "looking a group up ignoring case did not find it");
        h.assertTrue(directory.findByName("Found By Name").orElseThrow().id().equals(made.id()),
                "a name found the wrong group");
        h.assertTrue(directory.findByName("  Found By Name  ").isPresent(),
                "a name with padding around it did not find the group");
        h.assertTrue(directory.findByName("nobody called it this").isEmpty(),
                "an unused name found a group");
        h.assertTrue(directory.findByName("").isEmpty(), "a blank name found a group");
        h.succeed();
    }

    /**
     * The wrench's cycle has to reach every mode and come back.
     *
     * <p>BIDIRECTIONAL was unreachable before this existed — a fresh dock receives, the requester's
     * click forces sending, and nothing offered the third — so a test that only checked one step
     * would not have caught it.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theModeCycleReachesEveryMode(GameTestHelper h) {
        DockMode mode = DockMode.RECEIVE;
        java.util.EnumSet<DockMode> seen = java.util.EnumSet.noneOf(DockMode.class);
        for (int i = 0; i < DockMode.values().length; i++) {
            seen.add(mode);
            mode = mode.next();
        }
        h.assertTrue(seen.size() == DockMode.values().length,
                "cycling the dock mode did not reach every mode: " + seen);
        h.assertTrue(mode == DockMode.RECEIVE, "the mode cycle did not come back to where it started");
        h.succeed();
    }

    private DockGroupGameTests() {
    }
}
