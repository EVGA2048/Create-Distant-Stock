package dev.distantstock;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.distantstock.link.PackageCodec;
import dev.distantstock.link.PayloadManifest;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
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

        var originalId = ResourceLocation.fromNamespaceAndPath("fluidlogistics", "fluid_package");
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
                "remote order stayed as the author's local fluid package");
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


    @GameTest(template = "empty")
    public static void latestResourcePackagerOutputPromotesRemoteFluidOrder(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("fluidlogistics")) {
            h.succeed();
            return;
        }

        var fluidPackagerId = ResourceLocation.fromNamespaceAndPath("fluidlogistics", "fluid_packager");
        var fluidPackageId = ResourceLocation.fromNamespaceAndPath("fluidlogistics", "fluid_package");
        h.assertTrue(BuiltInRegistries.BLOCK.containsKey(fluidPackagerId),
                "FluidLogistics 1.3.x fluid packager block is missing");
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(fluidPackageId),
                "FluidLogistics 1.3.x fluid package item is missing");

        var pos = h.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, BuiltInRegistries.BLOCK.get(fluidPackagerId).defaultBlockState(), 3);
        h.assertTrue(h.getLevel().getBlockEntity(pos)
                        instanceof com.simibubi.create.content.logistics.packager.PackagerBlockEntity,
                "FluidLogistics 1.3.x fluid packager no longer derives from Create PackagerBlockEntity");
        var owner = (com.simibubi.create.content.logistics.packager.PackagerBlockEntity)
                h.getLevel().getBlockEntity(pos);

        int order = 7410;
        ItemStack produced = new ItemStack(BuiltInRegistries.ITEM.get(fluidPackageId));
        PackageItem.addAddress(produced, "FLUID-ENGINE-REMOTE");
        PackageItem.setOrder(produced, order, 0, true, 0, true,
                PackageOrderWithCrafts.simple(List.of()));

        RemoteRoute route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        var directory = OrderRouteDirectory.get(h.getLevel().getServer());
        h.assertTrue(directory.remember(List.of(request(order)), route, "HOME-FLUID-ENGINE"),
                "could not remember route for latest FluidLogistics engine test");

        Class<?> resourcePackagers = Class.forName("com.yision.fluidlogistics.api.packager.ResourcePackagers");
        Object optional = resourcePackagers
                .getMethod("ownerOf", com.simibubi.create.content.logistics.packager.PackagerBlockEntity.class)
                .invoke(null, owner);
        Object resourcePackager = ((java.util.Optional<?>) optional).orElseThrow();

        Class<?> engine = Class.forName(
                "com.yision.fluidlogistics.content.logistics.packageResource.ResourcePackagerEngine");
        Method output = java.util.Arrays.stream(engine.getDeclaredMethods())
                .filter(method -> method.getName().equals("output") && method.getParameterCount() == 3)
                .findFirst().orElseThrow();
        output.setAccessible(true);
        output.invoke(null, owner, resourcePackager, produced);

        var remoteId = ResourceLocation.fromNamespaceAndPath("distantstock", "remote_fluid_package");
        h.assertTrue(BuiltInRegistries.ITEM.getKey(owner.heldBox.getItem()).equals(remoteId),
                "FluidLogistics 1.3.x ResourcePackagerEngine output bypassed Distant Stock promotion");
        h.assertTrue(PackageItem.getOrderId(owner.heldBox) == order,
                "latest FluidLogistics promotion lost Create order data");
        h.assertTrue(RemoteRouteData.read(owner.heldBox).filter(route::equals).isPresent(),
                "latest FluidLogistics output did not receive Distant Stock route metadata");

        directory.consume(order);
        h.succeed();
    }


    @GameTest(template = "empty")
    public static void latestFluidContentsSurvivePromotionAndWireCodec(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("fluidlogistics")) {
            h.succeed();
            return;
        }

        var fluidPackageId = ResourceLocation.fromNamespaceAndPath("fluidlogistics", "fluid_package");
        ItemStack original = new ItemStack(BuiltInRegistries.ITEM.get(fluidPackageId));
        FluidStack water = new FluidStack(Fluids.WATER, 1000);

        Class<?> contentHelper = Class.forName(
                "com.yision.fluidlogistics.content.logistics.fluidPackage.FluidPackageContentHelper");
        contentHelper.getMethod("setCanonicalContents", ItemStack.class, FluidStack.class)
                .invoke(null, original, water);

        int order = 7420;
        PackageItem.addAddress(original, "FLUID-WIRE-REMOTE");
        PackageItem.setOrder(original, order, 0, true, 0, true,
                PackageOrderWithCrafts.simple(List.of()));
        RemoteRoute route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        var directory = OrderRouteDirectory.get(h.getLevel().getServer());
        h.assertTrue(directory.remember(List.of(request(order)), route, "HOME-FLUID-WIRE"),
                "could not remember route for fluid wire-format test");

        Class<?> compat = Class.forName("dev.distantstock.compat.fluidlogistics.FluidLogisticsCompat");
        ItemStack remote = (ItemStack) compat
                .getMethod("promoteIfRemoteOrder", ItemStack.class, net.minecraft.server.MinecraftServer.class)
                .invoke(null, original, h.getLevel().getServer());

        PayloadManifest manifest = PayloadManifest.fromPackage(remote);
        h.assertTrue(manifest.itemIds().contains("fluidlogistics:compressed_storage_tank"),
                "remote fluid package manifest missed FluidLogistics compressed tank payload");
        h.assertTrue(manifest.componentIds().contains("fluidlogistics:fluid_tank_content"),
                "remote fluid package manifest missed FluidLogistics 1.3.x fluid DataComponent");

        String encoded = PackageCodec.encode(remote, h.getLevel().registryAccess());
        h.assertTrue(!encoded.isBlank(), "remote fluid package failed Distant Stock wire encoding");
        ItemStack decoded = PackageCodec.decode(encoded, h.getLevel().registryAccess());
        h.assertTrue(!decoded.isEmpty(), "remote fluid package failed Distant Stock wire decoding");

        FluidStack decodedFluid = (FluidStack) contentHelper
                .getMethod("getSingleContainedFluid", ItemStack.class)
                .invoke(null, decoded);
        h.assertTrue(!decodedFluid.isEmpty()
                        && FluidStack.isSameFluidSameComponents(water, decodedFluid)
                        && decodedFluid.getAmount() == water.getAmount(),
                "FluidLogistics 1.3.x fluid contents changed across remote promotion/wire codec: "
                        + decodedFluid);
        h.assertTrue(RemoteRouteData.read(decoded).filter(route::equals).isPresent(),
                "Distant Stock route metadata was lost during fluid package wire round-trip");

        directory.consume(order);
        h.succeed();
    }

    private static PackagingRequest request(int id) {
        return PackagingRequest.create(new ItemStack(Items.IRON_INGOT), 1, "fluid-test", 0,
                new MutableBoolean(true), 0, id, PackageOrderWithCrafts.simple(List.of()));
    }
}
