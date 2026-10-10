package dev.distantstock.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.distantstock.item.EtherCasingArmorItem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hide the cape while the resonant quartz chestplate is worn, the way vanilla hides it for an elytra.
 *
 * Vanilla's own guard is `!itemstack.is(Items.ELYTRA)` -- an identity test against one item, so a
 * chestplate that grants elytra flight by any other means leaves the cape drawn underneath it, and
 * the two overlap on the player's back.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void distantstock$hideCapeForResonantQuartz(PoseStack pose, MultiBufferSource buffer, int light,
                                                        AbstractClientPlayer player, float limbSwing,
                                                        float limbSwingAmount, float partialTick,
                                                        float ageInTicks, float netHeadYaw, float headPitch,
                                                        CallbackInfo ci) {
        if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof EtherCasingArmorItem) {
            ci.cancel();
        }
    }
}
