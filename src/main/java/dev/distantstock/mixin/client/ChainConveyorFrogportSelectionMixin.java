package dev.distantstock.mixin.client;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorInteractionHandler;
import dev.distantstock.client.SpecialFrogportSelection;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Create's chain selector checks the exact vanilla Frogport BlockEntry. For those checks only,
 * present Distant Stock's diagnostic/cache Frogport as the vanilla Frogport item.
 */
@Mixin(ChainConveyorInteractionHandler.class)
public abstract class ChainConveyorFrogportSelectionMixin {

    @ModifyArg(method = "isActive",
            at = @At(value = "INVOKE",
                    target = "Lcom/tterrag/registrate/util/entry/BlockEntry;isIn(Lnet/minecraft/world/item/ItemStack;)Z"),
            index = 0)
    private static ItemStack distantstock$specialFrogportActivatesChainSelection(ItemStack stack) {
        return SpecialFrogportSelection.normalizeForCreateCheck(stack);
    }

    @ModifyArg(method = "onUse",
            at = @At(value = "INVOKE",
                    target = "Lcom/tterrag/registrate/util/entry/BlockEntry;isIn(Lnet/minecraft/world/item/ItemStack;)Z"),
            index = 0)
    private static ItemStack distantstock$specialFrogportCreatesTarget(ItemStack stack) {
        return SpecialFrogportSelection.normalizeForCreateCheck(stack);
    }
}
