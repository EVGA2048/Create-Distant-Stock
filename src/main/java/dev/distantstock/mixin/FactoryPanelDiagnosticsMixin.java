package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import dev.distantstock.diagnostics.ChainDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Factory Gauges already know both the Create logistics network and the Frogport address they send
 * to. Sampling that association lets chain faults inherit the same scope as Logger/Andon instead of
 * becoming dimension-wide alarms.
 */
@Mixin(FactoryPanelBehaviour.class)
public abstract class FactoryPanelDiagnosticsMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void distantstock$publishGaugeAddress(CallbackInfo ci) {
        ChainDiagnostics.observeGauge((FactoryPanelBehaviour) (Object) this);
    }
}
