package dev.distantstock.mixin.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The activated casing stage needs real alpha blending; vanilla armor normally uses cutout only. */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {
    @Redirect(
            method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;armorCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"))
    private RenderType distantstock$translucentResonantQuartz(ResourceLocation texture) {
        if ("distantstock".equals(texture.getNamespace())
                && texture.getPath().startsWith("textures/models/armor/ether_casing_layer_")) {
            return RenderType.entityTranslucent(texture);
        }
        return RenderType.armorCutoutNoCull(texture);
    }
}
