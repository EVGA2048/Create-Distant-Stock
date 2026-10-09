package dev.distantstock.mixin.client;

import dev.distantstock.item.EtherCasingCloakState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The oval on the ground is drawn by the entity dispatcher, after and outside the renderer it
 * dispatches to — so cancelling PlayerRenderer hides the body and leaves its shadow sitting on the
 * ground on its own. The shadow's radius is the only per-entity input it has, and the dispatcher
 * skips the shadow entirely when that radius is not positive, so shrinking it to nothing is what
 * takes the oval away without touching the shadow of anything else.
 *
 * The radius is faded rather than dropped because the suit phases out over twelve ticks: the armour
 * is still partly visible early in the dissolve, and a shadow that disappeared on the first crouch
 * tick would be the same mismatch with the opposite sign.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererShadowMixin {
    @Shadow
    protected float shadowRadius;

    @Inject(method = "getShadowRadius", at = @At("HEAD"), cancellable = true)
    private void distantstock$fadeCloakShadow(Entity entity, CallbackInfoReturnable<Float> cir) {
        if (!(entity instanceof Player player)) return;
        int phase = EtherCasingCloakState.get(player);
        if (phase <= 0) return;
        float remaining = 1.0F - (float) phase / EtherCasingCloakState.PHASES;
        cir.setReturnValue(Math.max(0.0F, this.shadowRadius * remaining));
    }
}
