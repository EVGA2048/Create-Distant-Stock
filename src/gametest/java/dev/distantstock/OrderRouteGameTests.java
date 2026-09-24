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

    /** The wire request itself freezes all three player-facing addresses before it leaves home. */
    @GameTest(template = "empty")
    public static void orderRequestCodecCarriesThreeAddresses(GameTestHelper h) throws Exception {
        UUID node = UUID.randomUUID();
        var network = new dev.distantstock.routing.RemoteNetworkId(
                dev.distantstock.routing.RemoteNetworkId.CURRENT_SCHEMA,
                node, UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());
        var request = new dev.distantstock.link.OrderRequestCodec.Request(
                network, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                "111", "接收港-甲", "222",
                List.of(new dev.distantstock.link.LinkQueues.Line("minecraft:iron_ingot", 3)));

        var decoded = dev.distantstock.link.OrderRequestCodec.decode(
                dev.distantstock.link.OrderRequestCodec.encode(request));
        h.assertTrue("111".equals(decoded.address()), "wire request lost pre-crossing address");
        h.assertTrue("接收港-甲".equals(decoded.receivingAddress()),
                "wire request lost Distant Dock receiving address");
        h.assertTrue("222".equals(decoded.homeAddress()), "wire request lost post-crossing/local address");
        h.succeed();
    }

    /** Remote packager colour is cosmetic: transmuting to blue must preserve all route metadata. */
    @GameTest(template = "empty")
    public static void blueRemotePackageKeepsSameThreeAddresses(GameTestHelper h) {
        int orderId = 42;
        RemoteRoute route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        OrderRouteDirectory directory = OrderRouteDirectory.get(h.getLevel().getServer());
        h.assertTrue(directory.remember(List.of(request(orderId)), route, "222", "接收港-乙"),
                "remote order route could not be remembered");

        ItemStack ordinary = com.simibubi.create.content.logistics.box.PackageStyles.getDefaultBox();
        PackageItem.addAddress(ordinary, "111");
        PackageItem.setOrder(ordinary, orderId, 0, true, 0, true,
                PackageOrderWithCrafts.simple(List.of()));
        h.assertTrue(dev.distantstock.routing.RemoteOrderParcelStamp.stamp(
                ordinary, h.getLevel().getServer()), "ordinary parcel was not stamped first");

        ItemStack blue = ordinary.transmuteCopy(ModItems.REMOTE_PACKAGE.get());
        h.assertTrue(blue.is(ModItems.REMOTE_PACKAGE.get()), "remote packager did not select blue package item");
        h.assertTrue("111".equals(PackageItem.getAddress(blue)), "blue transmute lost current address");
        h.assertTrue("接收港-乙".equals(dev.distantstock.routing.RemoteRouteData.receivingAddress(blue)),
                "blue transmute lost receiving address");
        h.assertTrue("222".equals(dev.distantstock.routing.RemoteRouteData.homeAddress(blue)),
                "blue transmute lost local/home address");
        h.assertTrue(dev.distantstock.routing.RemoteRouteData.read(blue).orElseThrow().equals(route),
                "blue transmute lost machine route");
        h.succeed();
    }

    /**
     * Remote-terminal routing is order metadata, not a package colour. A vanilla Create packager's
     * ordinary cardboard box must keep its item type while carrying all three address semantics.
     */
    @GameTest(template = "empty")
    public static void ordinaryCreatePackageKeepsColourAndGetsThreeAddressRoute(GameTestHelper h) {
        int orderId = 41;
        String remoteAddress = "111";
        String receivingAddress = "接收港-甲";
        String homeAddress = "222";
        RemoteRoute route = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        OrderRouteDirectory directory = OrderRouteDirectory.get(h.getLevel().getServer());
        h.assertTrue(directory.remember(List.of(request(orderId)), route, homeAddress, receivingAddress),
                "remote order route could not be remembered");

        ItemStack ordinary = com.simibubi.create.content.logistics.box.PackageStyles.getDefaultBox();
        var originalItem = ordinary.getItem();
        PackageItem.addAddress(ordinary, remoteAddress);
        PackageItem.setOrder(ordinary, orderId, 0, true, 0, true,
                PackageOrderWithCrafts.simple(List.of()));

        h.assertTrue(dev.distantstock.routing.RemoteOrderParcelStamp.stamp(
                        ordinary, h.getLevel().getServer()),
                "ordinary Create package was not stamped as a Distant Stock order");
        h.assertTrue(ordinary.getItem() == originalItem,
                "routing metadata changed an ordinary package into the blue remote package");
        h.assertTrue(remoteAddress.equals(PackageItem.getAddress(ordinary)),
                "stamping changed the pre-crossing/Create address");
        h.assertTrue(receivingAddress.equals(
                        dev.distantstock.routing.RemoteRouteData.receivingAddress(ordinary)),
                "package lost the Distant Dock receiving address");
        h.assertTrue(homeAddress.equals(dev.distantstock.routing.RemoteRouteData.homeAddress(ordinary)),
                "package lost the post-crossing/local address");
        h.assertTrue(dev.distantstock.routing.RemoteRouteData.read(ordinary).orElseThrow().equals(route),
                "package lost the machine route behind the receiving address");
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

    /**
     * 一单永远凑不齐的订单不会把整个文件堵死。
     *
     * <p>记进去的那一行从"下单"活到"最后一块被托管"，正常是几秒钟。不正常的那一种 —— 包裹被敲了、
     * 卡在没人取的港里、打包机被关了 —— 以前没有任何东西会放弃它，于是行数一直涨，涨到 4096 之后
     * **每一单**都失败，而且是从一个当控制流用的异常里失败的。
     *
     * <p>这里直接写一行老到过期，然后确认新的一单还记不记得进去。过期那一行所属的包裹如果哪天真的
     * 到了，它找不到路线、不会被改写成远仓包裹、会亮着橙灯躺在港里 —— 看得见，也拿得出来，这正是
     * 这条取舍想要的失败方式。
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void anOrderNobodyFinishesStopsBlockingTheFile(GameTestHelper h) throws Exception {
        OrderRouteDirectory directory = new OrderRouteDirectory();
        var stale = RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID());
        directory.remember(List.of(request(31)), stale);

        // 把这一行的时间戳往回拨一周多，就像它是一年前下的那一单。反射而不是等一周：这条路要验的
        // 是"过期之后会怎样"，不是时钟本身。
        java.lang.reflect.Field routes = OrderRouteDirectory.class.getDeclaredField("routes");
        routes.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, Object> rows = (java.util.Map<Integer, Object>) routes.get(directory);
        Object row = rows.get(31);
        h.assertTrue(row != null, "第一条路线压根没记下来");
        java.lang.reflect.Field createdAt = row.getClass().getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.setLong(row, System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000);

        h.assertTrue(directory.remember(List.of(request(32)),
                        RemoteRoute.create(UUID.randomUUID(), UUID.randomUUID())),
                "一单过期的订单把文件堵死了：新订单记不进去");
        h.assertTrue(directory.find(31).isEmpty(), "过期的路线还留着");
        h.assertTrue(directory.find(32).isPresent(), "新的路线没有记下来");
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
