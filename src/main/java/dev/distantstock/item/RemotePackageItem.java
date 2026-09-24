package dev.distantstock.item;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
     * Distant Stock lines shared by ordinary-colour and blue Create packages.
     *
     * <p>Create already renders the package's current address as {@code @address}. For a parcel
     * produced by a remote terminal, these two extra lines complete the three-address model:
     * current/remote address + Distant Dock receiving address + post-crossing/local address.
     */
    public static List<Component> extraLines(ItemStack stack) {
        List<Component> lines = new java.util.ArrayList<>();
        String receiving = RemoteRouteData.receivingAddress(stack);
        if (!receiving.isEmpty()) {
            lines.add(Component.translatable("item.distantstock.remote_package.receiving_address", receiving)
                    .withStyle(ChatFormatting.YELLOW));
        }
        String home = RemoteRouteData.homeAddress(stack);
        if (!home.isEmpty()) {
            lines.add(Component.translatable("item.distantstock.remote_package.home_address", home)
                    .withStyle(ChatFormatting.AQUA));
        }
        // 这一行只在"它还要去别的地方"的时候画：落了地就不该再挂着一条已经走完的路。
        if (RemoteRouteData.crossServer(stack)) {
            RemoteRouteData.read(stack).ifPresent(route -> {
                // The label was written when the route was, on the server, where the directories that
                // know what a group is called live. A parcel routed by an older build carries none and
                // falls back on the ids — which is what this line printed before it could say a name.
                String label = RemoteRouteData.label(stack);
                lines.add(Component.translatable("item.distantstock.remote_package.crossing",
                        label.isEmpty()
                                ? shortId(route.destinationNodeId()) + " · " + shortId(route.receivingDockGroupId())
                                : label).withStyle(ChatFormatting.LIGHT_PURPLE));
            });
        }
        return lines;
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
