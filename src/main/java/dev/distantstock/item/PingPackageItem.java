package dev.distantstock.item;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Orange diagnostic parcel sent by a Diagnostic Frogport.
 *
 * It intentionally remains a real Create PackageItem: it rides chain conveyors, can be taken off
 * by a player, and can be put back on exactly like an ordinary parcel.
 */
public final class PingPackageItem extends PackageItem {
    private static final PackageStyles.PackageStyle STYLE =
            new PackageStyles.PackageStyle("cardboard", 12, 12, 23f, false);

    public PingPackageItem(Item.Properties properties) {
        super(properties.stacksTo(1), STYLE);
        PackageStyles.ALL_BOXES.remove(this);
        PackageStyles.STANDARD_BOXES.remove(this);
    }

    @Override
    public String getDescriptionId() {
        return "item.distantstock.ping_package";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        var data = dev.distantstock.diagnostics.PingPackageData.read(stack);
        if (data != null) {
            tooltip.add(Component.translatable("item.distantstock.ping_package.target", data.originalAddress())
                    .withStyle(ChatFormatting.GOLD));
            if (!data.valid()) {
                tooltip.add(Component.translatable("item.distantstock.ping_package.stale")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
