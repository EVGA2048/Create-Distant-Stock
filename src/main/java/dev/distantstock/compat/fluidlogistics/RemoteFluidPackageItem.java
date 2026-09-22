package dev.distantstock.compat.fluidlogistics;

import com.yision.fluidlogistics.item.FluidPackageItem;
import dev.distantstock.item.RemotePackageItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Distant Stock's optional FluidLogistics parcel.
 *
 * It deliberately extends the original author's FluidPackageItem instead of reimplementing any
 * fluid storage, unpacking or rendering rules. FluidLogistics remains authoritative for all of
 * those behaviours; Distant Stock adds only cross-server route metadata and a pale-blue shell.
 */
public final class RemoteFluidPackageItem extends FluidPackageItem {
    public RemoteFluidPackageItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public String getDescriptionId() {
        return "item.distantstock.remote_fluid_package";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.addAll(RemotePackageItem.extraLines(stack));
    }
}
