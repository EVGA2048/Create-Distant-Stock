package dev.distantstock.menu;

import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * Create 的请求器菜单，只改一件事：玩家背包那 36 格往下挪一段。
 *
 * <p>我们的界面把窗口的棕色段加长了 {@link #BAND} 像素来放那两行远仓字段（见
 * {@code RemoteRedstoneRequesterScreen}），窗口里原有的东西 —— 九个幽灵槽位 —— 相对窗口没动，
 * 所以它们不用挪。**背包是唯一跟着窗口底边走的东西**：不挪的话，物品栏的框和格子就留在原处，
 * 而那一段现在被棕色区占了，玩家看到的是自己的东西叠在窗口上。
 *
 * <p>槽位的位置在菜单里定，画的时候加上窗口原点 —— 所以"把背包挪下去"这件事只能在这里做，
 * 屏幕上怎么画都改不了它。
 *
 * <p><b>这里必须一次摆好，不能先让父类摆完再撤回重加</b> —— 上一版就是那么写的，玩家一右键就被
 * "网络协议错误"踢出去。两个原因，第二个才是要命的：
 *
 * <ul>
 *   <li>{@code AbstractContainerMenu} 里记账的有三份：{@code slots}（public 的列表）、
 *       {@code lastSlots} 和 {@code remoteSlots}（两个 private final，谁也清不掉）。{@code addSlot()}
 *       往三份里各加一条，而重加一遍只清得掉第一份 —— 三份从此长度不一。</li>
 *   <li>重加时写的那个 {@code new Slot(...)} 是**普通 Slot**，而 Create 那九个格子是
 *       {@code SorterProofSlot}（绑在幽灵物品栏那个 {@code ItemStackHandler} 上）。换成普通 Slot
 *       之后它们指向的是一个空壳容器：点下去两边动的不是同一份东西，槽位内容当场对不上。</li>
 * </ul>
 *
 * <p>所以照着 Create 自己的 {@code addSlots()} 重写一遍（它先 {@code addPlayerSlots}、后加九个
 * {@code SorterProofSlot}，**顺序不能变**：快捷移动是按下标认槽位的），只把那一个 y 加上
 * {@link #BAND}。{@code RequesterMenuGameTests} 会逐格比对两张菜单的坐标、容器和类型 ——
 * 上面第二个原因就是它抓出来的。
 */
public final class RemoteRedstoneRequesterMenu extends RedstoneRequesterMenu {
    /** 窗口棕色段加长的高度，和屏幕上那两个常量是一份：见 RemoteRedstoneRequesterScreen。 */
    public static final int BAND = 58;

    /** Create 的槽位坐标，原样照抄；只有背包的 y 要加 BAND。 */
    private static final int PLAYER_X = 5;
    private static final int PLAYER_Y = 142;
    private static final int GHOST_X = 27;
    private static final int GHOST_Y = 28;
    private static final int GHOST_STEP = 20;

    public RemoteRedstoneRequesterMenu(MenuType<?> type, int id, Inventory inventory,
                                       RedstoneRequesterBlockEntity requester) {
        super(type, id, inventory, requester);
    }

    public RemoteRedstoneRequesterMenu(MenuType<?> type, int id, Inventory inventory,
                                       RegistryFriendlyByteBuf buffer) {
        super(type, id, inventory, buffer);
    }

    @Override
    protected void addSlots() {
        addPlayerSlots(PLAYER_X, PLAYER_Y + BAND);
        for (int i = 0; i < 9; i++) {
            addSlot(new SorterProofSlot(ghostInventory, i, GHOST_X + GHOST_STEP * i, GHOST_Y));
        }
    }
}
