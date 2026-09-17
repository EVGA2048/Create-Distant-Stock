package dev.distantstock.menu;

import dev.distantstock.DistantStock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, DistantStock.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<RequesterMenu>> REQUESTER = MENUS.register("requester",
            () -> IMenuTypeExtension.create(RequesterMenu::fromNetwork));

    /** The brass lamp's watch list. Named apart from the monitor block's own screen. */
    public static final DeferredHolder<MenuType<?>, MenuType<MonitorMenu>> LAMP_MONITOR = MENUS.register("lamp_monitor",
            () -> IMenuTypeExtension.create(MonitorMenu::fromNetwork));

    /**
     * 远仓红石请求器：Create 那个请求器界面，加上我们那两行。
     *
     * <p>为什么要有自己的菜单类型：界面是照菜单类型注册的，而**标题**是服务端在 openMenu 时
     * 发过来的（{@code MenuProvider.getDisplayName()}）。沿用 Create 的类型就只能叫「红石请求器」——
     * 和旁边那台原版长得一模一样，玩家分不出哪台是远仓的。类型是我们自己的，标题也就是我们的。
     *
     * <p>菜单本身仍是 Create 那一套：九个幽灵槽位、地址框、那个勾，全是它的实现。我们只覆写了
     * {@code addSlots()}，把玩家背包那 36 格整体往下挪一段，给加长的窗口让位
     * （见 {@link RemoteRedstoneRequesterMenu}）。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<RemoteRedstoneRequesterMenu>>
            REMOTE_REQUESTER = MENUS.register("remote_requester",
            () -> IMenuTypeExtension.create(ModMenus::remoteRequesterMenu));

    /** 在静态方法里引用那个 holder：写进 lambda 里会被 javac 判成"初始化器自引用"。 */
    private static RemoteRedstoneRequesterMenu remoteRequesterMenu(
            int id, net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.network.RegistryFriendlyByteBuf buffer) {
        return new RemoteRedstoneRequesterMenu(REMOTE_REQUESTER.get(), id, inventory, buffer);
    }

    private ModMenus() {
    }
}
