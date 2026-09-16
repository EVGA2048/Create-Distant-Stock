package dev.distantstock;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.List;
import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class OrderRouteGameTests {
    @GameTest(template = "empty")
    public static void outOfOrderFragmentsSurviveSaveReload(GameTestHelper h) throws Exception {
        var directory = new OrderRouteDirectory();
        var route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        directory.remember(List.of(request(17)), route);
        // Last link/final fragment first, then a duplicate: neither means all earlier fragments arrived.
        directory.packageEscrowed(parcel(17, 1, true, 1, true));
        directory.packageEscrowed(parcel(17, 1, true, 1, true));
        h.assertTrue(directory.find(17).isPresent(), "last fragment prematurely consumed route");
        var loader = OrderRouteDirectory.class.getDeclaredMethod("load", CompoundTag.class,
                net.minecraft.core.HolderLookup.Provider.class);
        loader.setAccessible(true);
        directory = (OrderRouteDirectory) loader.invoke(null,
                directory.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        directory.packageEscrowed(parcel(17, 0, false, 0, true));
        h.assertTrue(directory.find(17).isPresent(), "missing middle fragment was ignored after reload");
        directory.packageEscrowed(parcel(17, 1, true, 0, false));
        h.assertTrue(directory.find(17).isEmpty(), "all fragments arrived but route was not released");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void collisionNeverOverwritesExistingRoute(GameTestHelper h) {
        var directory = new OrderRouteDirectory();
        var original = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        directory.remember(List.of(request(21)), original);
        var foreign = parcel(21, 0, true, 0, true);
        dev.distantstock.routing.RemoteRouteData.write(foreign, RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID()));
        directory.packageEscrowed(foreign);
        h.assertTrue(directory.find(21).isPresent(), "foreign parcel consumed a coincident local order ID");
        boolean refused = false;
        try {
            directory.remember(List.of(request(22), request(21)), RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID()));
        } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "order ID collision was not refused");
        h.assertTrue(directory.find(21).orElseThrow().equals(original), "collision overwrote original destination");
        h.assertTrue(directory.find(22).isEmpty(), "failed registration left a partial route");
        h.succeed();
    }

    private static PackagingRequest request(int id) {
        return PackagingRequest.create(new ItemStack(Items.IRON_INGOT), 1, "factory", 0,
                new MutableBoolean(true), 0, id, PackageOrderWithCrafts.simple(List.of()));
    }

    private static ItemStack parcel(int order, int link, boolean finalLink, int index, boolean last) {
        var stack = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.setOrder(stack, order, link, finalLink, index, last, PackageOrderWithCrafts.simple(List.of()));
        return stack;
    }
}
