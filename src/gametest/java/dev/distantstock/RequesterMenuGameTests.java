package dev.distantstock;

import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterMenu;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.menu.ModMenus;
import dev.distantstock.menu.RemoteRedstoneRequesterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 远仓红石请求器那张菜单，和 Create 自己那张必须是同一张，只差一个常量。
 *
 * <p>为什么值得写一条测试：这里出过一次"右键就被踢，提示网络协议错误"。菜单的槽位表在
 * {@code AbstractContainerMenu} 里记着三份，{@code addSlot} 往三份里各加一条，而**只有第一份是
 * public 的**。上一版为了把背包往下挪，先让父类摆好、再把第一份清空重加 —— 三份从此长短不一，
 * 服务端发出去的槽位和客户端手里的对不上。
 *
 * <p>所以这里逐格比对：这一张和 Create 那一张的槽位个数、容器、**类型**都要一样，坐标只差
 * {@link RemoteRedstoneRequesterMenu#BAND}（而且只有背包那 36 格差）。"类型"这一条不是凑数的 ——
 * 重加的时候把九个幽灵格写成普通 {@code Slot} 就会在这里红，而那正是会让点击落到错误地方、
 * 进而把两边状态搞散的那种改动。
 *
 * <p>最后走一遍 {@code broadcastChanges()}：那条路会按 {@code slots} 的下标去读
 * {@code remoteSlots}，两份长度不一致时会在这儿炸出来。
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class RequesterMenuGameTests {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void ourRequesterMenuIsCreatesPlusTheBand(GameTestHelper h) {
        BlockPos pos = new BlockPos(1, 2, 1);
        h.setBlock(pos, ModBlocks.REMOTE_REDSTONE_REQUESTER.get().defaultBlockState());
        if (!(h.getBlockEntity(pos) instanceof RedstoneRequesterBlockEntity requester)) {
            h.fail("远仓红石请求器没有方块实体");
            return;
        }

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        Inventory inventory = player.getInventory();
        RemoteRedstoneRequesterMenu ours = new RemoteRedstoneRequesterMenu(
                ModMenus.REMOTE_REQUESTER.get(), 0, inventory, requester);
        // 菜单类型在这一条里无所谓（比的是槽位表，类型只影响界面注册），所以借我们自己的用 ——
        // Create 那个 AllMenuTypes 走的是 Registrate 的 MenuEntry，而 Registrate 是它的 jar-in-jar，
        // 名字压根不在编译期类路径上。
        RedstoneRequesterMenu theirs = new RedstoneRequesterMenu(
                ModMenus.REMOTE_REQUESTER.get(), 1, inventory, requester);

        h.assertTrue(ours.slots.size() == theirs.slots.size(),
                "槽位个数不一样：我们 " + ours.slots.size() + "、Create " + theirs.slots.size());

        int moved = 0;
        for (int i = 0; i < theirs.slots.size(); i++) {
            Slot mine = ours.slots.get(i);
            Slot reference = theirs.slots.get(i);
            boolean backpack = reference.container instanceof Inventory;
            int expected = reference.y + (backpack ? RemoteRedstoneRequesterMenu.BAND : 0);
            h.assertTrue(mine.y == expected,
                    "第 " + i + " 格的行不对：我们 " + mine.y + "、应该是 " + expected);
            h.assertTrue(mine.x == reference.x, "第 " + i + " 格的列不对");
            h.assertTrue(mine.container == reference.container, "第 " + i + " 格换了容器");
            h.assertTrue(mine.getClass() == reference.getClass(),
                    "第 " + i + " 格换了类型：" + mine.getClass().getSimpleName()
                            + " 而不是 " + reference.getClass().getSimpleName());
            if (backpack) {
                moved++;
            }
        }
        h.assertTrue(moved == 36, "背包应该有 36 格往下挪，实际 " + moved);

        // 两边都要能按自己的槽位表广播一次 —— 三份账本一样长才不会在这儿炸。
        ours.broadcastChanges();
        theirs.broadcastChanges();
        h.succeed();
    }
}
