package dev.distantstock.routing;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/** Stores stable cross-server routing on the package without touching its Create address. */
public final class RemoteRouteData {
    private static final String ROOT = "DistantStockRoute";
    private static final String SCHEMA = "Schema";
    private static final String DESTINATION_NODE = "DestinationNode";
    private static final String RECEIVING_GROUP = "ReceivingDockGroup";
    private static final String CORRELATION = "Correlation";
    private static final String CHILD_ORDER = "ChildOrder";
    // "CrossServer" 这个键不再写了，也不再读：它当年是出港时盖上的一个布尔标记，而没有任何地方会
    // 把它去掉，于是落地之后的包裹永远显示着一条已经走完的路。现在答案每次现算（见 crossServer）。
    // 老存档里残留的这个键会被忽略。
    /**
     * Where the route goes, in words: "远仓B · 甲服仓库".
     *
     * <p>Written here because here is the only place that can write it — the directories that turn
     * a group id into a name live on the server, and the tooltip that reads this runs on a client
     * that has neither. Optional: a parcel routed by an older build has no label and falls back on
     * the ids.
     */
    private static final String LABEL = "DestinationLabel";
    private static final int MAX_LABEL = 96;
    /**
     * The address the parcel wears once it is on the other side — its address at home.
     *
     * <p>One address cannot serve both ends of a crossing. A parcel leaving a warehouse on B is
     * sorted on B by the address written on it, and sorted again on A by the same field, and the two
     * servers have their own door names: the B-side frogport it was claimed by and the A-side dock it
     * has to land in are rarely called the same thing. So the order carries both, this one rides
     * along while the parcel is on B, and the crossing swaps them.
     *
     * <p>Absent on a parcel that never crosses, and on one from a build that had only one address.
     */
    private static final String HOME_ADDRESS = "HomeAddress";
    private static final int MAX_ADDRESS = 128;

    public static Optional<RemoteRoute> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!custom.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag route = custom.getCompound(ROOT);
        if (!route.contains(SCHEMA, CompoundTag.TAG_INT)
                || !route.hasUUID(DESTINATION_NODE)
                || !route.hasUUID(RECEIVING_GROUP)
                || !route.hasUUID(CORRELATION)
                || !route.hasUUID(CHILD_ORDER)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RemoteRoute(
                    route.getInt(SCHEMA),
                    route.getUUID(DESTINATION_NODE),
                    route.getUUID(RECEIVING_GROUP),
                    route.getUUID(CORRELATION),
                    route.getUUID(CHILD_ORDER)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** The destination in words, or empty when the parcel carries no label. */
    public static String label(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return custom.contains(ROOT, CompoundTag.TAG_COMPOUND)
                ? custom.getCompound(ROOT).getString(LABEL) : "";
    }

    /** The address this parcel will wear once it is on the other side, or empty. */
    public static String homeAddress(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return custom.contains(ROOT, CompoundTag.TAG_COMPOUND)
                ? custom.getCompound(ROOT).getString(HOME_ADDRESS) : "";
    }

    /**
     * Gives the parcel the address it uses on this side, and forgets the one it crossed with.
     *
     * <p>This is the crossing. A parcel arrives wearing the address of the server it came from —
     * that is the only address it could have been sorted by over there — and the first thing that
     * has to happen on this side is that it wears this side's, or the dock it was sent to will not
     * recognise it. The old one is not kept: it names a door on a server this parcel will not visit
     * again, and a tooltip that went on offering it would be offering a journey that is over.
     *
     * <p>Does nothing and reports false when the parcel carries no home address — which is the
     * ordinary case for a parcel that never crosses, and for one packed by a build that had only
     * one address to give it. Nothing is guessed in that case: an address the sender did not name
     * is not one this code may invent.
     */
    public static boolean applyHomeAddress(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String home = homeAddress(stack);
        if (home.isEmpty()) {
            return false;
        }
        com.simibubi.create.content.logistics.box.PackageItem.addAddress(stack, home);
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag custom = current.copyTag();
            if (custom.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
                CompoundTag route = custom.getCompound(ROOT);
                route.remove(HOME_ADDRESS);
                custom.put(ROOT, route);
            }
            return CustomData.of(custom);
        });
        return true;
    }

    /**
     * 这件包裹是不是"还要去别的服务器"——问的是它身上那条路线指向哪儿，不是问谁在什么时候给它盖过章。
     *
     * <p>原来存的是打包/出港那一刻盖上的一个布尔标记，而**没有任何地方会把它去掉**：包裹过了海、
     * 换了地址、落了地，那个标记还在，于是它一辈子显示「跨服寄往…」——一条已经走完的路，每次摸它
     * 都被再告知一次。落地的证据本来就在数据里：路线指向的那台服务器**就是这一台**。所以这里改成
     * 每次现算 —— 一台服务器不会变成另一台，这个答案也不会过期。
     */
    public static boolean crossServer(ItemStack stack) {
        return read(stack)
                .map(route -> !dev.distantstock.link.TranserverBridge.isLocal(
                        route.destinationNodeId().toString()))
                .orElse(false);
    }

    public static void write(ItemStack stack, RemoteRoute value) {
        write(stack, value, "", "");
    }

    /**
     * The same, with the destination spelled out for the player who will read the tooltip.
     *
     * <p>A blank label is written as no label rather than as an empty string, so a caller that has
     * nothing to say leaves the parcel exactly as the id-only version would have.
     */
    public static void write(ItemStack stack, RemoteRoute value, String label) {
        write(stack, value, label, "");
    }

    /**
     * The whole of what a parcel can be told about where it is going: the route, the destination in
     * words, and the address it should wear on the other side.
     *
     * <p>One writer for all of it because all of it is decided at the same moment — when the parcel
     * is packed, on the server that packs it, from the order that asked for it. Splitting it into
     * several calls would give a caller the chance to write a route without the address that makes
     * the route usable.
     */
    public static void write(ItemStack stack, RemoteRoute value, String label, String homeAddress) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot route an empty item stack");
        }
        String described = bounded(label, MAX_LABEL);
        // 地址原样保留两端空白以外的部分：空白地址与「没有地址」是同一件事，都不写。
        String home = bounded(homeAddress, MAX_ADDRESS);
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag custom = current.copyTag();
            CompoundTag route = new CompoundTag();
            route.putInt(SCHEMA, value.schemaVersion());
            route.putUUID(DESTINATION_NODE, value.destinationNodeId());
            route.putUUID(RECEIVING_GROUP, value.receivingDockGroupId());
            route.putUUID(CORRELATION, value.correlationId());
            route.putUUID(CHILD_ORDER, value.childOrderId());
            if (!described.isEmpty()) {
                route.putString(LABEL, described);
            }
            if (!home.isEmpty()) {
                route.putString(HOME_ADDRESS, home);
            }
            custom.put(ROOT, route);
            return CustomData.of(custom);
        });
    }

    private static String bounded(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }

    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag custom = current.copyTag();
            custom.remove(ROOT);
            return CustomData.of(custom);
        });
    }

    private RemoteRouteData() {
    }
}
