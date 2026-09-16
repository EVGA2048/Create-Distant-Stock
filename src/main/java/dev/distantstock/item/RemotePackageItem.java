package dev.distantstock.item;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The sealed parcel used by the remote logistics line.
 *
 * It deliberately keeps Create's PackageItem behaviour so addresses,
 * order data and the nine-slot contents component remain compatible with
 * Create's funnels, package pumps and package entities.
 */
public final class RemotePackageItem extends PackageItem {
    private static final PackageStyles.PackageStyle STYLE =
            new PackageStyles.PackageStyle("cardboard", 12, 12, 23f, false);

    public RemotePackageItem(Item.Properties properties) {
        super(properties.stacksTo(1), STYLE);
        // This item is selected explicitly by the remote packager. It should
        // not become one of Create's random cardboard styles.
        PackageStyles.ALL_BOXES.remove(this);
        PackageStyles.STANDARD_BOXES.remove(this);
    }

    @Override
    public String getDescriptionId() {
        return "item.distantstock.remote_package";
    }

    /**
     * Create's own tooltip, plus where this parcel is going when that is another server.
     *
     * <p>Create already prints the address and the contents; that address is the one the receiving
     * dock filters by, and it is the same string on both sides of the crossing. What it cannot print
     * is the part this mod adds — that the parcel is bound for a node rather than for a group here —
     * so a parcel in a chest could not be told apart from one that will never leave.
     *
     * <p>The second line disappears on its own when the parcel arrives: the route is cleared the
     * moment it is handed to a dock, so what is left is the local address. That is the whole of the
     * rule the player asked for — both addresses while it is crossing, the local one once it lands.
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tip, flag);
        if (!RemoteRouteData.crossServer(stack)) {
            return;
        }
        RemoteRouteData.read(stack).ifPresent(route -> {
            // The label was written when the route was, on the server, where the directories that
            // know what a group is called live. A parcel routed by an older build carries none and
            // falls back on the ids — which is what this line printed before it could say a name.
            String label = RemoteRouteData.label(stack);
            tip.add(Component.translatable("item.distantstock.remote_package.crossing",
                    label.isEmpty()
                            ? shortId(route.destinationNodeId()) + " · " + shortId(route.receivingDockGroupId())
                            : label).withStyle(ChatFormatting.LIGHT_PURPLE));
        });
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
