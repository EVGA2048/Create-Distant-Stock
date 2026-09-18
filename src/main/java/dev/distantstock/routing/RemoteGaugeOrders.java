package dev.distantstock.routing;

import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.OrderService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The arithmetic and the one order call behind a remote gauge panel.
 *
 * <p>A remote gauge is a factory gauge whose goods come from another server. The reading half is
 * Create's: the panel watches its own logistics network and reports how many of its item that
 * network holds. What changes is what happens when the number is too low — instead of asking the
 * local packagers to find the item, the panel files an order with the warehouse the panel is bound
 * to, and the goods arrive at a dock group on this side.
 *
 * <p><b>One order in flight per panel.</b> Nothing tells us a parcel has arrived: it lands in a
 * dock, and whether it reaches the network the panel watches depends on what the player built
 * around that dock. So the panel keeps its own count of what it has asked for and refuses to ask
 * again while that count is outstanding. It is cleared when the stock the panel watches reaches the
 * target — goods booked in, order settled — or when {@link #TIMEOUT_TICKS} have passed, at which
 * point the panel would rather order again than sit quiet forever on a parcel that went somewhere
 * else. Without this the panel would place an order every beat: the network it watches would still
 * read low the whole time the goods were in transit, and each beat would look exactly like the
 * first.
 *
 * <p>The maths is pure and lives here rather than in the block entity so the shapes that matter —
 * a satisfied panel orders nothing, a short one orders the gap, a full stack is the most it will
 * ask for at once, and an outstanding order silences it — can be checked without a server, a
 * network or a parcel.
 */
public final class RemoteGaugeOrders {
    /** How long an order stays counted against its panel before it is written off. Two minutes. */
    public static final int TIMEOUT_TICKS = 20 * 120;

    private RemoteGaugeOrders() {
    }

    /**
     * What the panel wants to hold: the number in its value box, read the way Create reads it.
     *
     * <p>The value box has two rows and the panel stores which one was used in {@code upTo}: the
     * first is a count of items, the second a count of stacks. Create's own storage monitor
     * multiplies by the stack size for the second, and a gauge that read the number without the row
     * would ask for 64 times what the player set.
     */
    public static int target(int amount, boolean upTo, int maxStackSize) {
        return Math.max(0, amount) * (upTo ? 1 : Math.max(1, maxStackSize));
    }

    /**
     * How many to order right now: nothing, unless there is a gap and nothing is outstanding.
     *
     * @param cap the most one order may ask for, so a panel set to a large target cannot pull a
     *            warehouse's whole stock in a single parcel
     */
    public static int plan(int target, int have, int outstanding, int cap) {
        if (outstanding > 0) {
            return 0;
        }
        int gap = target - have;
        if (gap <= 0 || cap <= 0) {
            return 0;
        }
        return Math.min(gap, cap);
    }

    /**
     * Files the order, and says whether it was accepted.
     *
     * <p>False is a real answer and the caller must not count it as outstanding: the transport may
     * be down, the network may not be bound, or the far side may refuse. A panel that counted a
     * rejected order would go quiet for two minutes over an order that never existed.
     */
    public static boolean order(MinecraftServer server, RemoteNetworkId network, String address,
                                java.util.UUID receivingGroup, ItemStack item, int count) {
        if (item.isEmpty() || count <= 0) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        return orderAll(server, network, address, receivingGroup, "",
                List.of(new LinkQueues.Line(id, count)));
    }

    /**
     * Files one order for everything in the list.
     *
     * <p>One order rather than one per line: the far side packs it as a single request with a single
     * correlation, and a redstone requester configured with nine items is one shipment, not nine.
     */
    public static boolean orderAll(MinecraftServer server, RemoteNetworkId network, String address,
                                   java.util.UUID receivingGroup, List<LinkQueues.Line> lines) {
        return orderAll(server, network, address, receivingGroup, "", lines);
    }

    /**
     * The same, for an order whose parcels must wear a different address once they are home.
     *
     * <p>{@code homeAddress} is written onto every parcel of this order and applied on the far side
     * of the crossing — see {@link dev.distantstock.routing.RemoteRouteData#applyHomeAddress}. Blank
     * is the ordinary case: goods that stay on the server they were packed on never need a second
     * address.
     */
    public static boolean orderAll(MinecraftServer server, RemoteNetworkId network, String address,
                                   java.util.UUID receivingGroup, String homeAddress,
                                   List<LinkQueues.Line> lines) {
        if (server == null || network == null || lines == null || lines.isEmpty()) {
            return false;
        }
        // 没选接收港组的订单不出去：默认组等于没有收件人，货发出去谁都不认（玩家 2026-09-18 报的
        // 「发的东西都进虚空了」）。面板是自动下单的，没人看着，所以这里必须自己拦住。
        //
        // **这里只判"选没选组"，不判权限**：面板的绑定是玩家当初站在终端前绑的，而这一单上根本没有
        // 玩家，拿 {@code OrderDestination} 去问会得到"你不是组主"那种与订单无关的答案 —— 本地组是
        // 别人建的时候，那会让面板永远下不了单。
        if (receivingGroup == null
                || receivingGroup.equals(dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return false;
        }
        OrderService.Result result = OrderService.place(server, network, network.createFrequency(),
                address == null ? "" : address, receivingGroup, List.copyOf(lines),
                homeAddress == null ? "" : homeAddress);
        return result == OrderService.Result.QUEUED;
    }
}
