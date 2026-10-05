package dev.distantstock.compat.fluidlogistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.DistantStock;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteOrderParcelStamp;
import com.yision.fluidlogistics.content.logistics.fluidPackage.FluidPackageItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Optional bridge for Create: FluidLogistics.
 *
 * <p>This class is loaded only when FluidLogistics is present. Nothing outside this package names
 * its API types; callers use the registry id or static methods behind a ModList check, so Distant
 * Stock remains loadable without the addon.</p>
 */
public final class FluidLogisticsCompat {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, DistantStock.MODID);

    public static final DeferredHolder<Item, RemoteFluidPackageItem> REMOTE_FLUID_PACKAGE =
            ITEMS.register("remote_fluid_package",
                    () -> new RemoteFluidPackageItem(new Item.Properties()));

    private FluidLogisticsCompat() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    public static Item remoteFluidPackage() {
        return REMOTE_FLUID_PACKAGE.get();
    }

    /**
     * Upgrade one FluidLogistics parcel only when it belongs to an order remembered by Distant Stock.
     * Manual/redstone fluid parcels have no remembered remote order and stay exactly as the author
     * designed them.
     */
    public static ItemStack promoteIfRemoteOrder(ItemStack stack, MinecraftServer server) {
        if (stack == null || stack.isEmpty() || server == null || !PackageItem.isPackage(stack)
                || !PackageItem.hasOrderData(stack)) {
            return stack;
        }

        boolean fluidPackage = FluidPackageItem.isFluidPackage(stack);
        boolean alreadyRemote = stack.getItem() == REMOTE_FLUID_PACKAGE.get();
        if (!fluidPackage && !alreadyRemote) {
            return stack;
        }

        int orderId = PackageItem.getOrderId(stack);
        OrderRouteDirectory directory = OrderRouteDirectory.get(server);
        if (directory.find(orderId).isEmpty()) {
            return stack;
        }

        ItemStack remote = alreadyRemote ? stack : stack.transmuteCopy(REMOTE_FLUID_PACKAGE.get());
        RemoteOrderParcelStamp.stamp(remote, server);
        return remote;
    }
}
