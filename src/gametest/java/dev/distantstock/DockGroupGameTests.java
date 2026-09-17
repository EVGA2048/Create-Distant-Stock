package dev.distantstock;

import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockMode;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.RemoteGaugeOrders;
import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
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

    /**
     * A name on the list is a key to a locked group, and nothing more.
     *
     * <p>Two halves, and both are the point: the member is let in without the group being opened,
     * and being let in does not make them the owner. A member who could rename it, delete it or
     * name somebody else would be an owner with fewer words — and the "×" on their row is the
     * owner's, which only means anything if the row after it is really gone.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aNamedMemberMayUseAClosedGroupAndNothingElse(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        DockGroup group = directory.createFor("members " + UUID.randomUUID().toString().substring(0, 8), OWNER);
        h.assertFalse(group.admits(STRANGER), "the new group started open");

        directory.addMember(group.id(), STRANGER, "张三");
        DockGroup named = directory.require(group.id());
        h.assertTrue(named.admits(STRANGER), "a named member was still refused");
        h.assertTrue(named.hasMember(STRANGER), "the member was not on the list");
        h.assertTrue("张三".equals(named.memberName(STRANGER)), "the member's name was not kept");
        h.assertFalse(named.ownedBy(STRANGER), "being named made the member an owner");
        h.assertFalse(named.open(), "naming somebody opened the group to everyone");

        // Somebody else, who nobody named, is still outside.
        h.assertFalse(named.admits(UUID.randomUUID()), "an unnamed player was let into a locked group");

        directory.removeMember(group.id(), STRANGER);
        h.assertFalse(directory.require(group.id()).admits(STRANGER),
                "taking the name back out left the member in");
        h.succeed();
    }

    /** The owner is in by definition: naming them is a no-op, not a row that can be removed. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theOwnerIsNotAMemberRow(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        DockGroup group = directory.createFor("ownerrow " + UUID.randomUUID().toString().substring(0, 8), OWNER);
        directory.addMember(group.id(), OWNER, "owner");
        h.assertTrue(directory.require(group.id()).members().isEmpty(),
                "the owner was written into the member list as a row that could be crossed out");
        h.succeed();
    }

    /**
     * The list is a small file, and it has to come back the way it went in.
     *
     * <p>Names are the whole of what a player reads on that screen, so a round trip that kept the
     * ids and dropped the names would leave a list of blanks — and one that lost the names
     * altogether would take the keys to a locked warehouse with it.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theMemberListSurvivesItsFile(GameTestHelper h) {
        DockGroupDirectory directory = new DockGroupDirectory();
        DockGroup group = directory.createFor("存档 " + UUID.randomUUID().toString().substring(0, 8), OWNER);
        directory.addMember(group.id(), STRANGER, "李四");
        UUID third = UUID.randomUUID();
        directory.addMember(group.id(), third, "王五");

        DockGroupDirectory loaded = DockGroupDirectory.load(directory.save(new CompoundTag(), null), null);
        DockGroup back = loaded.find(group.id()).orElse(null);
        h.assertTrue(back != null, "the group did not survive its own file");
        h.assertTrue(back.members().size() == 2, "the member list lost rows on the way through the file");
        h.assertTrue("李四".equals(back.memberName(STRANGER)), "a member's name was lost");
        h.assertTrue("王五".equals(back.memberName(third)), "the second member's name was lost");
        h.assertTrue(back.admits(STRANGER) && back.admits(third), "a reloaded member is refused");
        h.assertFalse(back.ownedBy(STRANGER), "a reloaded member became the owner");
        h.succeed();
    }

    /**
     * 下单时那个组去哪个服务器：本服的组＝货回来，配对码引进来的组＝货留对面。
     *
     * <p>这条规则以前有个静默分支：**谁都不认识的组 id 会被当成默认组** —— 而默认组意味着
     * "地址空的港谁都收"。于是一个还揣着已删除组的终端，会把货悄悄投给随便哪个港。这条用例
     * 就是钉住"不认识就拒绝"，因为它的失败方式在生产环境里是"货不见了"。
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOrderGoesWhereItsGroupLivesAndIsRefusedWhenNobodyKnowsIt(GameTestHelper h) {
        DockGroupDirectory directory = DockGroupDirectory.get(h.getLevel().getServer());
        UUID owner = UUID.randomUUID();

        DockGroup mine = directory.createFor("下单组 " + UUID.randomUUID().toString().substring(0, 8), owner);
        var here = dev.distantstock.routing.OrderDestination.resolve(h.getLevel().getServer(), owner, mine.id());
        h.assertTrue(here.allowed() && here.group().equals(mine.id()), "本服的组没有被认成本服的");
        h.assertTrue(here.kind() == dev.distantstock.routing.OrderDestination.Kind.HERE,
                "本服的组被判成了别的去向");

        // 别人锁着的组：拒绝，且要说得出理由（界面用 REFUSED 那条文案）。
        DockGroup theirs = directory.createFor("别人组 " + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        var refused = dev.distantstock.routing.OrderDestination.resolve(h.getLevel().getServer(), owner, theirs.id());
        h.assertFalse(refused.allowed(), "别人锁着的组被放行了");
        h.assertTrue(refused.kind() == dev.distantstock.routing.OrderDestination.Kind.REFUSED,
                "别人锁着的组没有报成 REFUSED");

        // 一个谁也不认识的 id：必须是 UNKNOWN，绝不能变成默认组。
        UUID ghost = UUID.randomUUID();
        var unknown = dev.distantstock.routing.OrderDestination.resolve(h.getLevel().getServer(), owner, ghost);
        h.assertFalse(unknown.allowed(), "不存在的组被放行了（这正是货会悄悄落到别人港里的那条路）");
        h.assertTrue(unknown.kind() == dev.distantstock.routing.OrderDestination.Kind.UNKNOWN,
                "不存在的组没有报成 UNKNOWN");

        // 空 id 与默认组仍然是"本服的默认组"，这条不能一起收紧掉。
        var fallback = dev.distantstock.routing.OrderDestination.resolve(h.getLevel().getServer(), owner, null);
        h.assertTrue(fallback.allowed()
                        && fallback.group().equals(DockGroupDirectory.DEFAULT_GROUP_ID),
                "没有指定组的订单不再落到默认组了");
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

    /**
     * The remote gauge's order arithmetic, which is the whole of when a board spends.
     *
     * <p>Four answers have to hold, and each of them is a way the feature fails loudly in play: a
     * satisfied panel that ordered anyway would spend on every beat forever; a short one that
     * ordered nothing would be a display; one that ignored the cap would pull a warehouse's whole
     * stock in a single parcel; and one that ordered again while an order was outstanding would
     * file the same order every second until the goods arrived.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aRemoteGaugeOrdersTheGapAndNothingElse(GameTestHelper h) {
        int stack = 64;
        h.assertTrue(RemoteGaugeOrders.target(3, true, stack) == 3,
                "a target set in items was read as stacks");
        h.assertTrue(RemoteGaugeOrders.target(3, false, stack) == 192,
                "a target set in stacks ignored the stack size");

        h.assertTrue(RemoteGaugeOrders.plan(192, 192, 0, 64) == 0, "a full panel ordered anyway");
        h.assertTrue(RemoteGaugeOrders.plan(192, 200, 0, 64) == 0, "a panel above its target ordered");
        h.assertTrue(RemoteGaugeOrders.plan(192, 100, 0, 64) == 64, "the gap was not capped");
        h.assertTrue(RemoteGaugeOrders.plan(192, 172, 0, 64) == 20, "the gap below the cap was not used");
        h.assertTrue(RemoteGaugeOrders.plan(192, 0, 64, 64) == 0,
                "a panel with an order outstanding filed a second one");
        h.assertTrue(RemoteGaugeOrders.plan(10, 0, 0, 0) == 0, "a cap of nothing still ordered");
        h.succeed();
    }

    /**
     * A board with no binding does not order, however short it looks.
     *
     * <p>This is the promise the whole feature rests on. Every remote gauge board placed before the
     * feature existed has no binding, and a panel on one of them reads its network exactly as a
     * panel with a binding does — on a server with no logistics network it reads zero against a
     * target of sixty-four, which is precisely the shape of a panel that should order. If the
     * binding check ever stopped being the first thing the beat does, every factory gauge in every
     * world would start buying from a warehouse nobody pointed it at.
     *
     * <p>The counter is the transport's own order queue rather than the board's state: what the test
     * has to catch is an order that left, wherever it went.
     */
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void anUnboundGaugeNeverOrders(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, ModBlocks.REMOTE_GAUGE.get().defaultBlockState(), 3);
        dev.distantstock.block.RemoteGaugeBlockEntity board = (dev.distantstock.block.RemoteGaugeBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(board != null, "the board did not appear");

        // A panel with an item on it and a target it is nowhere near.
        var slot = com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot.TOP_LEFT;
        board.addPanel(slot, java.util.UUID.randomUUID());
        var panel = board.panels.get(slot);
        h.assertTrue(panel.isActive(), "the panel did not become active");
        panel.setFilter(new ItemStack(dev.distantstock.item.ModItems.REMOTE_PACKAGE.get()));
        panel.count = 64;
        h.assertTrue(panel.getLevelInStorage() == 0, "a board with no network read a stock level");

        int before = dev.distantstock.link.LinkQueues.orderDepth();
        h.runAfterDelay(40, () -> {
            h.assertTrue(dev.distantstock.link.LinkQueues.orderDepth() == before,
                    "an unbound gauge filed an order");

            // Now bind it to a warehouse that cannot be reached: this test world has no transport
            // and no network, so the order is refused. The panel must not count a refused order as
            // outstanding — a board that went quiet for two minutes over an order that never
            // existed would be a board that stopped working for no reason the operator could see.
            board.bind(slot, new dev.distantstock.routing.RemoteNetworkId(
                            dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA,
                            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                            "minecraft:overworld", java.util.UUID.randomUUID()),
                    java.util.UUID.randomUUID(), "");
            h.runAfterDelay(60, () -> {
                h.assertTrue(board.outstanding(slot) == 0,
                        "a refused order was counted as in flight");
                h.assertTrue(dev.distantstock.link.LinkQueues.orderDepth() == before,
                        "a bound gauge with no transport still got an order out");
                h.succeed();
            });
        });
    }
    /**
     * 删除一个系统：组没了，里面的港退回默认系统，默认系统删不掉。
     *
     * <p>「港退回默认」是这条里最要紧的一句 —— 删系统绝不能把机器弄坏。留在里面的港若还指着
     * 一个再也无人能寻址的系统，会一直看上去在忙。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void deletingASystemSendsItsDocksHome(GameTestHelper h) {
        var directory = DockGroupDirectory.get(h.getLevel().getServer());
        DockGroup doomed = directory.create("要删的 " + java.util.UUID.randomUUID().toString().substring(0, 8));

        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        dev.distantstock.block.DockBlockEntity dock =
                (dev.distantstock.block.DockBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(dock != null, "港没有出现");
        dock.setGroupId(doomed.id());

        h.assertTrue(!directory.delete(DockGroupDirectory.DEFAULT_GROUP_ID),
                "默认系统被删掉了 —— 每个查询的兜底就没了");
        h.assertTrue(directory.delete(doomed.id()), "系统没删掉");
        h.assertTrue(directory.find(doomed.id()).isEmpty(), "删完还能查到");

        // LoadedDocks 要下一拍才注册，所以退回默认组的断言要等一拍。
        h.runAfterDelay(2, () -> {
            for (var each : dev.distantstock.block.LoadedDocks.allInGroup(doomed.id())) {
                each.setGroupId(DockGroupDirectory.DEFAULT_GROUP_ID);
            }
            h.assertTrue(!dev.distantstock.block.LoadedDocks.allInGroup(doomed.id()).contains(dock),
                    "港还留在被删掉的系统里");
            h.succeed();
        });
    }

}
