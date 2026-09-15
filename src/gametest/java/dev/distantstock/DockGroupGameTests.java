package dev.distantstock;

import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockMode;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import dev.distantstock.net.SetDockGroupC2S;
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
        // A name nobody has used before. The test world is a save like any other and keeps its
        // dock groups between runs: a fixed name here made a group owned by the previous run's
        // player, and every run after that was silently refused entry to its own test fixture.
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
        DockGroup made = directory.createFor("mine " + UUID.randomUUID().toString().substring(0, 8), OWNER);
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
        String name = "Found By Name " + UUID.randomUUID().toString().substring(0, 8);
        DockGroup made = directory.createFor(name, OWNER);
        h.assertTrue(directory.findByName(name.toLowerCase(java.util.Locale.ROOT)).isPresent(),
                "looking a group up ignoring case did not find it");
        h.assertTrue(directory.findByName(name).orElseThrow().id().equals(made.id()),
                "a name found the wrong group");
        h.assertTrue(directory.findByName("  " + name + "  ").isPresent(),
                "a name with padding around it did not find the group");
        h.assertTrue(directory.findByName("nobody called it this").isEmpty(),
                "an unused name found a group");
        h.assertTrue(directory.findByName("").isEmpty(), "a blank name found a group");
        h.succeed();
    }

    private DockGroupGameTests() {
    }

    /** Two groups may not share a name; a lookup could not tell them apart. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void twoGroupsMayNotShareAName(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        String name = "Twice " + UUID.randomUUID().toString().substring(0, 8);
        directory.createFor(name, OWNER);
        try {
            directory.createFor(name, STRANGER);
            h.fail("a second group was made with a name that was already taken");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(directory.findByName(name).orElseThrow().ownedBy(OWNER),
                    "the duplicate attempt disturbed the group that already had the name");
        }
        h.succeed();
    }

    /**
     * A request desk keeps the group it is pointed at, in the desk.
     *
     * <p>It used to keep it nowhere: the screen wrote the group to whatever the player was holding,
     * so opening a desk with an empty hand showed a group field that silently did nothing and every
     * order went to the default system. The desk is the machine that places the order, so the desk
     * is what has to hold the answer.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aDeskKeepsItsOwnReceivingGroup(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(1, 2, 1));
        h.getLevel().setBlock(pos, ModBlocks.GAUGE.get().defaultBlockState(), 3);
        GaugeBlockEntity desk = (GaugeBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(desk != null, "the desk did not appear");

        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        RequesterMenu menu = new RequesterMenu(0, player.getInventory(), pos);
        h.assertTrue(DockGroupDirectory.DEFAULT_GROUP_ID.equals(desk.receivingGroup()),
                "a fresh desk was pointed somewhere other than the default group");

        // A name nobody has used before. The test world is a save like any other and keeps its dock
        // groups between runs: a fixed name here made a group owned by the previous run's player,
        // and every run after that was silently refused entry to its own fixture — the write went
        // nowhere and the failure looked like a bug in the desk.
        String name = "甲站收货-" + UUID.randomUUID();
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        menu.writeDockGroup(player, name, SetDockGroupC2S.SELECT);
        DockGroup stored = directory.find(desk.receivingGroup()).orElse(null);
        h.assertTrue(stored != null && stored.name().equals(name),
                "the desk did not take the group it was given: " + desk.receivingGroup()
                        + " isGauge=" + menu.isGauge()
                        + " playerLevel=" + player.level().dimension().location()
                        + " sameLevel=" + (player.level() == h.getLevel())
                        + " beThere=" + (player.level().getBlockEntity(pos) != null));
        h.assertTrue(menu.carriedGroup(player).orElse(null).equals(desk.receivingGroup()),
                "the menu answered with a different group than the desk stored");

        // A second desk given the same name points at the same group, rather than making a twin:
        // two systems sharing a name would make every dock's readout ambiguous.
        BlockPos other = h.absolutePos(new BlockPos(3, 2, 1));
        h.getLevel().setBlock(other, ModBlocks.GAUGE.get().defaultBlockState(), 3);
        GaugeBlockEntity second = (GaugeBlockEntity) h.getLevel().getBlockEntity(other);
        new RequesterMenu(1, player.getInventory(), other)
                .writeDockGroup(player, name, SetDockGroupC2S.SELECT);
        h.assertTrue(second.receivingGroup().equals(desk.receivingGroup()),
                "the same name made two groups");
        h.succeed();
    }
}
