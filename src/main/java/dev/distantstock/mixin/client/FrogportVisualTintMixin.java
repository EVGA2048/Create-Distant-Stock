package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportRenderer;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.distantstock.client.SpecialFrogportModels;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Special Frogports use Create's vanilla FrogportRenderer animation with authored partial models.
 *
 * Their Flywheel visualizer is deliberately disabled in ClientSetup. This mixin only prevents
 * FrogportRenderer's global Flywheel early-return for our two BE types and swaps Create's partial
 * models for texture-authored orange/green variants. No vertex tinting is involved.
 */
@Mixin(FrogportRenderer.class)
public abstract class FrogportVisualTintMixin {
    @Unique
    private FrogportBlockEntity distantstock$currentFrog;

    @Inject(method = "renderSafe(Lcom/simibubi/create/content/logistics/packagePort/frogport/FrogportBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"))
    private void distantstock$capture(FrogportBlockEntity frog, float partialTick,
                                      com.mojang.blaze3d.vertex.PoseStack pose,
                                      net.minecraft.client.renderer.MultiBufferSource buffers,
                                      int light, int overlay, CallbackInfo ci) {
        distantstock$currentFrog = frog;
    }

    @Redirect(method = "renderSafe(Lcom/simibubi/create/content/logistics/packagePort/frogport/FrogportBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z"))
    private boolean distantstock$renderSpecialEvenWithFlywheel(LevelAccessor level) {
        if (distantstock$currentFrog instanceof DiagnosticFrogportBlockEntity
                || distantstock$currentFrog instanceof CacheFrogportBlockEntity) {
            return false;
        }
        return VisualizationManager.supportsVisualization(level);
    }

    @ModifyArg(method = "renderSafe(Lcom/simibubi/create/content/logistics/packagePort/frogport/FrogportBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/createmod/catnip/render/CachedBuffers;partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/createmod/catnip/render/SuperByteBuffer;"),
            index = 0)
    private PartialModel distantstock$replacePart(PartialModel original) {
        return SpecialFrogportModels.replace(distantstock$currentFrog, original);
    }

    @Inject(method = "renderSafe(Lcom/simibubi/create/content/logistics/packagePort/frogport/FrogportBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"))
    private void distantstock$clear(FrogportBlockEntity frog, float partialTick,
                                    com.mojang.blaze3d.vertex.PoseStack pose,
                                    net.minecraft.client.renderer.MultiBufferSource buffers,
                                    int light, int overlay, CallbackInfo ci) {
        distantstock$currentFrog = null;
    }
}
