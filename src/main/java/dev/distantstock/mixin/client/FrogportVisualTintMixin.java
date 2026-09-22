package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportVisual;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep Create's Frogport geometry and animation verbatim; only multiply the animated body/head/tongue
 * for Distant Stock's two diagnostic variants. The rig and carried parcel are deliberately untouched.
 */
@Mixin(FrogportVisual.class)
public abstract class FrogportVisualTintMixin {
    @Shadow @Final private TransformedInstance body;
    @Shadow private TransformedInstance head;
    @Shadow @Final private TransformedInstance tongue;

    @Unique
    private int distantstock$frogTint = -1;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void distantstock$selectTint(VisualizationContext context, FrogportBlockEntity be,
                                         float partialTick, CallbackInfo ci) {
        if (be instanceof DiagnosticFrogportBlockEntity) {
            // Warm industrial amber/orange.
            distantstock$frogTint = 0xE6A23A;
        } else if (be instanceof CacheFrogportBlockEntity) {
            // Muted machine green rather than saturated lime.
            distantstock$frogTint = 0x72B879;
        }
        distantstock$applyTint();
    }

    @Inject(method = "updateGoggles", at = @At("RETURN"))
    private void distantstock$retintReplacementHead(CallbackInfo ci) {
        distantstock$applyTint();
    }

    @Unique
    private void distantstock$applyTint() {
        if (distantstock$frogTint < 0) return;
        body.colorRgb(distantstock$frogTint);
        tongue.colorRgb(distantstock$frogTint);
        if (head != null) head.colorRgb(distantstock$frogTint);
    }
}
