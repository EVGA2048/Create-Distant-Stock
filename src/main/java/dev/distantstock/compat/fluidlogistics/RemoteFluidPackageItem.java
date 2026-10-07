package dev.distantstock.compat.fluidlogistics;

import com.simibubi.create.content.logistics.box.PackageStyles.PackageStyle;
import com.yision.fluidlogistics.content.logistics.fluidPackage.FluidPackageItem;
import net.minecraft.world.item.Item;


/**
 * Distant Stock's optional FluidLogistics parcel.
 *
 * It deliberately extends the original author's FluidPackageItem instead of reimplementing any
 * fluid storage, unpacking or rendering rules. FluidLogistics remains authoritative for all of
 * those behaviours; Distant Stock adds only cross-server route metadata and a pale-blue shell.
 */
public final class RemoteFluidPackageItem extends FluidPackageItem {
    /** Keep the Distant Stock shell aligned to FluidLogistics 1.3.x 12x12 geometry while using FluidLogistics 1.3.x contents/API. */
    private static final PackageStyle REMOTE_FLUID_STYLE =
            new PackageStyle("distantstock_remote_fluid", 12, 12, 23f, true);

    public RemoteFluidPackageItem(Item.Properties properties) {
        super(properties.stacksTo(1), REMOTE_FLUID_STYLE);
    }

    @Override
    public String getDescriptionId() {
        return "item.distantstock.remote_fluid_package";
    }

}
