package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Renders a parcel rising into the recessed ether surface while the dock dispatches it. */
public final class DockRenderer implements BlockEntityRenderer<DockBlockEntity> {

    public DockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(DockBlockEntity be, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        float progress = be.transmitProgress(partialTicks);
        ItemStack parcel = be.transmittingStack();
        if (progress < 0 || parcel.isEmpty() || be.getLevel() == null) {
            return;
        }

        float eased = progress * progress * (3 - 2 * progress);
        float y = lerp(.23f, .715f, eased);
        float absorption = clamp((progress - .68f) / .32f);
        float scale = lerp(.52f, .13f, absorption * absorption);

        Direction facing = be.getBlockState().getValue(DockBlock.FACING);
        poseStack.pushPose();
        poseStack.translate(.5, y, .5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        poseStack.scale(scale, scale, scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(
                parcel, ItemDisplayContext.FIXED,
                progress > .68f ? LightTexture.FULL_BRIGHT : packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, buffers, be.getLevel(),
                (int) be.getBlockPos().asLong());
        poseStack.popPose();
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }
}
