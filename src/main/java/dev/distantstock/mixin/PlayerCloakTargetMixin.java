package dev.distantstock.mixin;

import dev.distantstock.item.EtherCasingArmorItem;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Full Resonant Quartz suit + crouch is optically absent to combat AI from the first crouch tick. */
@Mixin(Player.class)
public abstract class PlayerCloakTargetMixin {
    @Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
    private void distantstock$cloakFromHostiles(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (EtherCasingArmorItem.isCloaking(self)) {
            cir.setReturnValue(false);
        }
    }
}
