package dev.distantstock.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageRenderer;
import dev.distantstock.compat.fluidlogistics.FluidLogisticsClientCompat;
import net.createmod.catnip.math.AngleHelper;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FluidLogistics cancels Create's package renderer and draws its own brown shell for every
 * FluidPackageItem. Run first for our optional subclass: draw the pale-blue shell, then delegate
 * only the dynamic fluid contents back to FluidLogistics.
 */
// Mixin HEAD callbacks are emitted in reverse application order here: a lower priority places this
// callback before FluidLogistics' default-priority callback in the transformed render method.
@Mixin(value = PackageRenderer.class, priority = 900)
public abstract class RemoteFluidPackageRendererMixin {
    private static final ResourceLocation REMOTE_FLUID =
            ResourceLocation.fromNamespaceAndPath("distantstock", "remote_fluid_package");

    @Inject(method = "render(Lcom/simibubi/create/content/logistics/box/PackageEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void distantstock$renderRemoteFluidPackage(PackageEntity entity, float yaw, float partialTicks,
                                                       PoseStack pose, MultiBufferSource buffers, int light,
                                                       CallbackInfo ci) {
        if (entity.box.isEmpty()
                || !REMOTE_FLUID.equals(BuiltInRegistries.ITEM.getKey(entity.box.getItem()))) {
            return;
        }

        PackageRenderer.renderBox(entity, yaw, pose, buffers, light,
                FluidLogisticsClientCompat.REMOTE_FLUID_PACKAGE);

        pose.pushPose();
        TransformStack.of(pose)
                .rotate(-AngleHelper.rad(yaw + 90), Direction.UP)
                .nudge(entity.getId());
        pose.translate(0, .02, 0);
        FluidLogisticsClientCompat.renderContentsForEntity(entity.box, pose, buffers, light);
        pose.popPose();

        ci.cancel();
    }
}
