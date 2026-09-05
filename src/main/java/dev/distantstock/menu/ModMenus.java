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

    private ModMenus() {
    }
}
