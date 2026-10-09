package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.item.EtherCasingCloakState;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class EtherWingLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "distantstock", "textures/models/armor/ether_casing_wings.png");
    private final ElytraModel<T> model;

    public EtherWingLayer(RenderLayerParent<T, M> parent, ElytraModel<T> model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (!(chest.getItem() instanceof EtherCasingArmorItem)) return;

        int phase = EtherCasingCloakState.get(entity);
        if (phase > EtherCasingCloakState.ACTIVE_PHASES) return;

        pose.pushPose();
        pose.translate(0, 0, 0.125f);
        getParentModel().copyPropertiesTo(model);
        model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        var vc = buffer.getBuffer(RenderType.armorCutoutNoCull(TEXTURE));
        model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
