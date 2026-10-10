package dev.distantstock.mixin;

import dev.distantstock.item.EtherCasingStealthServer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Combat AI obeys the same delayed/disrupted cloak state as rendering. */
@Mixin(Player.class)
public abstract class PlayerCloakTargetMixin {
    @Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
    private void distantstock$cloakFromHostiles(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (EtherCasingStealthServer.isCloaked(self)) {
            cir.setReturnValue(false);
        }
    }
}
