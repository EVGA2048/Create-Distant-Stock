package dev.distantstock;

import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.OrderDestination;
import dev.distantstock.routing.RemoteGroups;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Other servers' dock groups: how they arrive, and what they are allowed to decide.
 *
 * <p>This is what the pairing code used to be, and the tests that stood here checked the code —
 * that it was well formed, single use, and expired. All of that is gone with the codes. What is
 * left is the part that was always the real question: a destination on another server is a stable
 * id plus player-facing address metadata. The old member list is retained only for management
 * compatibility; delivery itself is intentionally not an ACL.
 *
 * <p>Runs without a world, the way the pairing cases did: everything here is arithmetic on a file,
 * and a case that wrote into the live save's own directory would be handing its rows to whatever
 * else is running in the same JVM.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class RemoteGroupsGameTests {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ab01");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000ab02");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000ab03");
    private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-00000000ab09");
    private static final UUID OTHER_NODE = UUID.fromString("00000000-0000-0000-0000-00000000ab0a");
    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-00000000ab0b");

    private static RemoteGroups.Entry row(UUID group, String name, UUID owner, boolean open,
                                          Map<UUID, String> members, int docks) {
        return new RemoteGroups.Entry(NODE, group, name, "乙服", 0L, open, owner, members, docks,
                SCOPE, true);
    }

    /**
     * An announcement replaces what was known about that node, and nothing else.
     *
     * <p>A group missing from the announcement has been deleted over there, and a row kept here
     * would go on being offered as a destination nothing can arrive at. The other half matters just
     * as much: a node's announcement says nothing about any other node's groups, and an
     * implementation that cleared the whole file would delete the second server's destinations every
     * time the first one spoke.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anAnnouncementReplacesThatNodesGroupsOnly(GameTestHelper h) {
        RemoteGroups groups = new RemoteGroups();
        UUID kept = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();

        groups.replaceFrom(NODE, List.of(
                row(kept, "仓库", OWNER, false, Map.of(MEMBER, "Iris_Aria0"), 2),
                row(gone, "旧组", OWNER, false, Map.of(), 1)), 1_000L);
        groups.replaceFrom(OTHER_NODE, List.of(
                new RemoteGroups.Entry(OTHER_NODE, elsewhere, "别人的组", "丙服", 0L, false, null,
                        Map.of(), 4)), 1_000L);

        // The second announcement no longer lists "旧组", and renames the one that stayed.
        groups.replaceFrom(NODE, List.of(
                row(kept, "新名字", OWNER, true, Map.of(MEMBER, "Iris_Aria0"), 3)), 2_000L);

        h.assertTrue(groups.find(gone).isEmpty(),
                "a group the far side stopped announcing is still a destination here");
        h.assertTrue(groups.find(kept).isPresent(), "a group that was still announced disappeared");
        h.assertTrue(groups.find(kept).get().name().equals("新名字"),
                "the rename did not arrive: " + groups.find(kept).get().name());
        h.assertTrue(groups.find(kept).get().docks() == 3,
                "the dock count did not arrive");
        h.assertTrue(groups.find(elsewhere).isPresent(),
                "one node's announcement deleted another node's groups");
        h.succeed();
    }

    /**
     * A row that was already known keeps the day it first appeared.
     *
     * <p>That timestamp is the eviction order, and the announcement repeats every 45 seconds. Reset
     * it on every announcement and a file that has been full for a year drops its oldest rows on a
     * schedule set by the peer's heartbeat instead of by anything the player did.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aRepeatedAnnouncementKeepsTheOriginalDate(GameTestHelper h) {
        RemoteGroups groups = new RemoteGroups();
        UUID group = UUID.randomUUID();
        groups.replaceFrom(NODE, List.of(row(group, "仓库", OWNER, false, Map.of(), 1)), 1_000L);
        groups.replaceFrom(NODE, List.of(row(group, "仓库", OWNER, false, Map.of(), 1)), 9_999L);
        h.assertTrue(groups.find(group).get().pairedAt() == 1_000L,
                "a repeat announcement reset the row's age to " + groups.find(group).get().pairedAt());
        h.succeed();
    }

    /** Legacy collaborator metadata still round-trips for address management. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theMemberListDecidesWhoMayNameIt(GameTestHelper h) {
        RemoteGroups groups = new RemoteGroups();
        UUID closed = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        UUID ownerless = UUID.randomUUID();
        groups.replaceFrom(NODE, List.of(
                row(closed, "锁着的", OWNER, false, Map.of(MEMBER, "Iris_Aria0"), 1),
                row(open, "开放的", OWNER, true, Map.of(), 1),
                row(ownerless, "服务器建的", null, false, Map.of(), 1)), 1_000L);

        h.assertTrue(groups.find(closed).get().admits(OWNER), "the owner was refused their own group");
        h.assertTrue(groups.find(closed).get().admits(MEMBER), "a listed member was refused");
        h.assertFalse(groups.find(closed).get().admits(STRANGER), "a stranger was admitted");
        h.assertFalse(groups.find(closed).get().admits(null), "a null player was admitted");
        // 开放只决定"能不能自己加进去"，不决定"能不能用" —— 和运输蜂停泊港一致。
        h.assertFalse(groups.find(open).get().admits(STRANGER),
                "an open group admitted somebody who never joined it");
        h.assertTrue(groups.find(ownerless).get().admits(STRANGER),
                "a group with no owner refused somebody: 老存档的组会全部锁死");
        h.succeed();
    }

    /**
     * Delivery permission is deliberately not the old member-list permission.
     *
     * <p>PUBLIC / UNLISTED controls discovery. Once somebody knows the exact receiving address,
     * naming it is enough to send there; the legacy member list remains management metadata only.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOrderToARemoteGroupAsksThatServersList(GameTestHelper h) {
        RemoteGroups groups = RemoteGroups.get(h.getLevel().getServer());
        UUID group = UUID.randomUUID();
        var before = groups.find(group);
        try {
            groups.replaceFrom(NODE, List.of(
                    row(group, "乙服仓库", OWNER, false, Map.of(MEMBER, "Iris_Aria0"), 1)), 1_000L);

            var mine = OrderDestination.resolve(h.getLevel().getServer(), MEMBER, SCOPE, group);
            h.assertTrue(mine.kind() == OrderDestination.Kind.THERE,
                    "a listed player was not allowed to name the remote group: " + mine.kind());
            var stranger = OrderDestination.resolve(h.getLevel().getServer(), STRANGER, SCOPE, group);
            h.assertTrue(stranger.kind() == OrderDestination.Kind.THERE,
                    "knowing the exact receiving address was not enough to send: " + stranger.kind());
            h.assertTrue(stranger.allowed(), "a known receiving address reported that it was refused");
        } finally {
            // 这一步写的是这份存档真正的远端组目录，别的用例也会读它。收尾必须是"把这一行拿掉"，
            // 不是"清空整张表"：gametest 是并行跑在同一个 JVM 里的。
            groups.replaceFrom(NODE, List.of(), 2_000L);
            if (before.isPresent()) {
                groups.replaceFrom(NODE, List.of(before.get()), 3_000L);
            }
        }
        h.succeed();
    }

    /**
     * A dismissal holds until the far side says something different, and not longer.
     *
     * <p>Hiding has to survive the next announcement or the button does nothing — the peer
     * re-announces its whole list every 45 seconds. It also has to stop surviving once the group
     * has changed, because the row that was dismissed is not the row being announced now: a group
     * that was renamed, or that the player was just added to, is worth seeing again.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aDismissedGroupStaysHiddenUntilItChanges(GameTestHelper h) {
        RemoteGroups groups = new RemoteGroups();
        UUID group = UUID.randomUUID();
        groups.replaceFrom(NODE, List.of(row(group, "仓库", OWNER, false, Map.of(), 1)), 1_000L);
        h.assertTrue(groups.forget(group), "there was nothing to dismiss");
        h.assertTrue(groups.find(group).isEmpty(), "a dismissed group was still offered");

        // The same announcement again: the peer repeating itself is not news.
        groups.replaceFrom(NODE, List.of(row(group, "仓库", OWNER, false, Map.of(), 1)), 2_000L);
        h.assertTrue(groups.find(group).isEmpty(), "a dismissed group came back on the next announcement");

        // Renamed over there: a different row, so it is offered again.
        groups.replaceFrom(NODE, List.of(row(group, "改过名的", OWNER, false, Map.of(), 1)), 3_000L);
        h.assertTrue(groups.find(group).isPresent(),
                "a renamed group stayed hidden: 玩家看不到对面改了什么");
        h.succeed();
    }

    /**
     * The rows survive a save and a load, membership and all.
     *
     * <p>What is written has to be what was read: the member list is a permission now, and a file
     * that lost it would come back with every remote group owned by nobody — which admits everybody,
     * so the failure is silent and in the permissive direction.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theFileKeepsTheMemberList(GameTestHelper h) {
        RemoteGroups groups = new RemoteGroups();
        UUID group = UUID.randomUUID();
        groups.replaceFrom(NODE, List.of(
                row(group, "仓库", OWNER, true, Map.of(MEMBER, "Iris_Aria0"), 5)), 1_000L);
        RemoteGroups reloaded = RemoteGroups.load(
                groups.save(new CompoundTag(), h.getLevel().registryAccess()),
                h.getLevel().registryAccess());

        var entry = reloaded.find(group).orElse(null);
        h.assertTrue(entry != null, "the row did not survive the round trip");
        h.assertTrue(entry.owner() != null && entry.owner().equals(OWNER), "the owner was lost");
        h.assertTrue(entry.members().size() == 1 && entry.members().containsKey(MEMBER),
                "the member list was lost: " + entry.members());
        h.assertTrue(entry.open() && entry.docks() == 5,
                "the flags were lost: open=" + entry.open() + " docks=" + entry.docks());
        h.assertFalse(entry.admits(STRANGER), "a reloaded row admitted a stranger");
        h.succeed();
    }

    /**
     * A row with no membership fields at all reads as ownerless, which admits everybody.
     *
     * <p>That is what a file written by the version before this one looks like, and it is the only
     * reading that does not silently lock a player out of a destination that worked yesterday. The
     * next announcement replaces the row with the current truth either way.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOlderFileReadsAsOwnerless(GameTestHelper h) {
        UUID group = UUID.randomUUID();
        CompoundTag written = new CompoundTag();
        CompoundTag row = new CompoundTag();
        row.putUUID("Node", NODE);
        row.putUUID("Group", group);
        row.putString("Name", "旧仓库");
        row.putString("Label", "乙服");
        row.putLong("Paired", 1_000L);
        var list = new net.minecraft.nbt.ListTag();
        list.add(row);
        written.put("Groups", list);

        RemoteGroups reloaded = RemoteGroups.load(written, h.getLevel().registryAccess());
        var entry = reloaded.find(group).orElse(null);
        h.assertTrue(entry != null, "an older file's row was dropped");
        h.assertFalse(entry.owner() != null, "an older row invented an owner");
        h.assertTrue(entry.admits(STRANGER), "an older row locked somebody out");
        h.succeed();
    }

    /** Two remote nodes using the same receiving-address name are a conflict, not two choices. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void duplicateRemoteAddressNamesAreNeverSilentlyDisambiguated(GameTestHelper h) {
        RemoteGroups groups = new RemoteGroups();
        UUID firstNode = UUID.randomUUID();
        UUID secondNode = UUID.randomUUID();
        UUID firstGroup = UUID.randomUUID();
        UUID secondGroup = UUID.randomUUID();
        String name = "工业区-" + UUID.randomUUID().toString().substring(0, 8);
        groups.replaceFrom(firstNode, List.of(new RemoteGroups.Entry(
                firstNode, firstGroup, name, "甲服", 0L, true, null, Map.of(), 1)), 1_000L);
        groups.replaceFrom(secondNode, List.of(new RemoteGroups.Entry(
                secondNode, secondGroup, name, "乙服", 0L, true, null, Map.of(), 1)), 1_000L);

        h.assertTrue(groups.named(name).size() == 2, "the duplicate remote address was not detected");
        h.assertTrue(groups.findByName(name).isEmpty(),
                "a bare duplicate address silently picked one remote server");
        h.assertTrue(groups.findByName("甲服·" + name).isEmpty(),
                "the legacy server prefix bypassed the global address conflict");
        h.succeed();
    }

    /**
     * A local address and a remote announcement with the same name block both ids until one side
     * changes its name.
     */
    @GameTest(template = "empty", timeoutTicks = 30)
    public static void localAndRemoteSameNameBlockNewRoutesUntilTheConflictClears(GameTestHelper h) {
        var server = h.getLevel().getServer();
        DockGroupDirectory locals = DockGroupDirectory.get(server);
        RemoteGroups remotes = RemoteGroups.get(server);
        UUID remoteNode = UUID.randomUUID();
        UUID remoteGroup = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "全局地址-" + suffix;
        DockGroup local = locals.createForNetwork(name, OWNER, scope, DockGroup.Visibility.PUBLIC);
        DockGroup other = locals.createForNetwork("另一个地址-" + suffix, OWNER, scope,
                DockGroup.Visibility.PUBLIC);

        try {
            remotes.replaceFrom(remoteNode, List.of(new RemoteGroups.Entry(
                    remoteNode, remoteGroup, name, "远端", 0L, true, null, Map.of(), 1,
                    scope, true)), 1_000L);

            var lookup = dev.distantstock.routing.ReceivingAddressResolver.resolve(server, scope, name);
            h.assertTrue(lookup.kind() == dev.distantstock.routing.ReceivingAddressResolver.Kind.CONFLICT,
                    "local+remote duplicate name did not become a conflict: " + lookup.kind());
            h.assertTrue(OrderDestination.resolve(server, OWNER, scope, local.id()).kind()
                            == OrderDestination.Kind.CONFLICT,
                    "the local UUID bypassed the name conflict");
            h.assertTrue(OrderDestination.resolve(server, OWNER, scope, remoteGroup).kind()
                            == OrderDestination.Kind.CONFLICT,
                    "the remote UUID bypassed the name conflict");
            h.assertFalse(dev.distantstock.routing.ReceivingAddressResolver.mayClaimLocalName(
                            server, other.id(), name),
                    "a second local group could rename itself onto a remote-conflicted address");

            // The far side changes its name: the original address immediately becomes unambiguous.
            remotes.replaceFrom(remoteNode, List.of(new RemoteGroups.Entry(
                    remoteNode, remoteGroup, "远端地址-" + suffix, "远端", 0L,
                    true, null, Map.of(), 1, scope, true)), 2_000L);
            var cleared = dev.distantstock.routing.ReceivingAddressResolver.resolve(server, scope, name);
            h.assertTrue(cleared.kind() == dev.distantstock.routing.ReceivingAddressResolver.Kind.LOCAL
                            && cleared.local().id().equals(local.id()),
                    "renaming the remote copy did not clear the conflict");
        } finally {
            remotes.replaceFrom(remoteNode, List.of(), 3_000L);
            locals.delete(local.id());
            locals.delete(other.id());
        }
        h.succeed();
    }

    private RemoteGroupsGameTests() {
    }
}
