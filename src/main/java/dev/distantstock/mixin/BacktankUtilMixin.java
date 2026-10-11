package dev.distantstock.mixin;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.item.EtherCasingBalance;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep charging, consumption and Create's air display on the same armor capacity. */
@Mixin(BacktankUtil.class)
public abstract class BacktankUtilMixin {
    @Inject(method = "maxAir(Lnet/minecraft/world/item/ItemStack;)I", at = @At("RETURN"), cancellable = true)
    private static void distantstock$armorReserve(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (stack.getItem() instanceof EtherCasingArmorItem) {
            cir.setReturnValue((int) Math.min(Integer.MAX_VALUE,
                    (long) cir.getReturnValueI() * EtherCasingBalance.RESERVE_MULTIPLIER));
        }
    }
}
