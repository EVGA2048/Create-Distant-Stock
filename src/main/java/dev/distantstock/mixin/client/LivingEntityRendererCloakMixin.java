package dev.distantstock.mixin.client;

import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.item.EtherCasingCloakState;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hide the skin underneath the dissolving casing shell without suppressing the armor layers. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererCloakMixin {
    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void distantstock$phaseBodyOut(LivingEntity entity, boolean bodyVisible,
                                           boolean translucent, boolean glowing,
                                           CallbackInfoReturnable<RenderType> cir) {
        if (entity instanceof Player
                && EtherCasingArmorItem.hasFullSet(entity)
                && EtherCasingCloakState.bodyPhasedOut(entity)) {
            cir.setReturnValue(null);
        }
    }
}
