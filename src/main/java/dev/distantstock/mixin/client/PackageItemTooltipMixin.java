package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.item.RemotePackageItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Show Distant Stock routing metadata on every Create package colour, not only the blue item. */
@Mixin(value = PackageItem.class, remap = false)
public abstract class PackageItemTooltipMixin {
    @Inject(method = "appendHoverText", at = @At("TAIL"), require = 1)
    private void distantstock$appendRemoteRoute(ItemStack stack, Item.TooltipContext context,
                                                List<Component> tooltip, TooltipFlag flag,
                                                CallbackInfo ci) {
        tooltip.addAll(RemotePackageItem.extraLines(stack));
    }
}
