package dev.distantstock.item;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import net.minecraft.world.item.Item;

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
}
