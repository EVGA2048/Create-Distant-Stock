package dev.distantstock;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Optional-compat regression tests; when FluidLogistics is absent this class verifies nothing loads. */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class FluidLogisticsCompatGameTests {
    @GameTest(template = "empty")
    public static void remoteFluidPackageExistsOnlyWithAddonAndPreservesOriginalParcelData(GameTestHelper h)
            throws Exception {
        if (!ModList.get().isLoaded("fluidlogistics")) {
            h.assertTrue(!BuiltInRegistries.ITEM.containsKey(
                            ResourceLocation.fromNamespaceAndPath("distantstock", "remote_fluid_package")),
                    "remote fluid package registered even though FluidLogistics is absent");
            h.succeed();
            return;
        }

        var originalId = ResourceLocation.fromNamespaceAndPath("fluidlogistics", "rare_fluid_package");
        var remoteId = ResourceLocation.fromNamespaceAndPath("distantstock", "remote_fluid_package");
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(originalId),
                "FluidLogistics package is missing while addon reports loaded");
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(remoteId),
                "Distant Stock did not conditionally register its FluidLogistics subclass");

        var originalItem = BuiltInRegistries.ITEM.get(originalId);
        var remoteItem = BuiltInRegistries.ITEM.get(remoteId);

        int order = 7401;
        ItemStack original = new ItemStack(originalItem);
        PackageItem.addAddress(original, "FLUID-REMOTE-TEST");
        PackageItem.setOrder(original, order, 0, true, 0, true,
                PackageOrderWithCrafts.simple(List.of()));

        RemoteRoute route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        var directory = OrderRouteDirectory.get(h.getLevel().getServer());
        h.assertTrue(directory.remember(List.of(request(order)), route, "HOME-FLUID"),
                "could not remember route for fluid compat test");

        Class<?> compat = Class.forName("dev.distantstock.compat.fluidlogistics.FluidLogisticsCompat");
        Method promote = compat.getMethod("promoteIfRemoteOrder", ItemStack.class,
                net.minecraft.server.MinecraftServer.class);
        ItemStack remote = (ItemStack) promote.invoke(null, original, h.getLevel().getServer());

        h.assertTrue(remote.getItem() == remoteItem,
                "remote order stayed as the author's brown local fluid package");
        h.assertTrue("FLUID-REMOTE-TEST".equals(PackageItem.getAddress(remote)),
                "promotion lost the original package address/data components");
        h.assertTrue(PackageItem.getOrderId(remote) == order,
                "promotion lost Create order data");
        h.assertTrue(RemoteRouteData.read(remote).filter(route::equals).isPresent(),
                "promoted fluid package did not receive Distant Stock route metadata");

        ItemStack local = new ItemStack(originalItem);
        PackageItem.setOrder(local, 7402, 0, true, 0, true,
                PackageOrderWithCrafts.simple(List.of()));
        ItemStack untouched = (ItemStack) promote.invoke(null, local, h.getLevel().getServer());
        h.assertTrue(untouched.getItem() == originalItem,
                "ordinary FluidLogistics package was recoloured without a remembered remote order");

        directory.consume(order);
        h.succeed();
    }

    private static PackagingRequest request(int id) {
        return PackagingRequest.create(new ItemStack(Items.IRON_INGOT), 1, "fluid-test", 0,
                new MutableBoolean(true), 0, id, PackageOrderWithCrafts.simple(List.of()));
    }
}
