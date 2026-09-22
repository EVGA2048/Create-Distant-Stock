package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.packagePort.PackagePortTargetSelectionHandler;
import dev.distantstock.client.SpecialFrogportSelection;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keep Create's target preview alive while holding either Distant Stock Frogport. */
@Mixin(PackagePortTargetSelectionHandler.class)
public abstract class PackagePortTargetSelectionMixin {

    @ModifyArg(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lcom/tterrag/registrate/util/entry/BlockEntry;isIn(Lnet/minecraft/world/item/ItemStack;)Z"),
            index = 0)
    private static ItemStack distantstock$keepSpecialFrogportTargeting(ItemStack stack) {
        return SpecialFrogportSelection.normalizeForCreateCheck(stack);
    }
}
