package dev.distantstock.routing;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import dev.distantstock.link.RouteLabels;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * Applies Distant Stock routing metadata to any Create package produced for a remembered remote
 * terminal order. Package colour/type is intentionally untouched here.
 *
 * <p>This separation is important: a vanilla Create packager produces a vanilla-coloured box, a
 * Distant Stock packager produces the blue box, but both carry exactly the same three-address
 * routing data when they fulfil the same remote-terminal order.
 */
public final class RemoteOrderParcelStamp {
    public static boolean stamp(ItemStack stack, MinecraftServer server) {
        if (stack == null || stack.isEmpty() || server == null
                || !PackageItem.isPackage(stack) || !PackageItem.hasOrderData(stack)) {
            return false;
        }
        int orderId = PackageItem.getOrderId(stack);
        OrderRouteDirectory directory = OrderRouteDirectory.get(server);
        RemoteRoute route = directory.find(orderId).orElse(null);
        if (route == null) {
            return false;
        }

        RemoteRouteData.write(stack, route,
                RouteLabels.describe(server, route),
                directory.receivingAddress(orderId),
                directory.homeAddress(orderId));
        return true;
    }

    /** Stamp every package currently produced/queued by one Create packager. */
    public static boolean stamp(PackagerBlockEntity packager, MinecraftServer server) {
        if (packager == null || server == null) return false;
        boolean changed = stamp(packager.heldBox, server);
        if (packager.queuedExitingPackages != null) {
            for (BigItemStack queued : packager.queuedExitingPackages) {
                if (queued != null) changed |= stamp(queued.stack, server);
            }
        }
        return changed;
    }

    private RemoteOrderParcelStamp() {
    }
}
