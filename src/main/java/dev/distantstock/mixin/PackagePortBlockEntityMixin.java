package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.diagnostics.ChainDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PackagePortBlockEntity.class)
public abstract class PackagePortBlockEntityMixin {
    @Inject(method = "getFilterString", at = @At("HEAD"), cancellable = true)
    private void distantstock$freezeFrogportRoute(CallbackInfoReturnable<String> cir) {
        if (!((Object) this instanceof FrogportBlockEntity frog)) return;
        String frozen = ChainDiagnostics.frozenFilter(frog);
        if (frozen != null) cir.setReturnValue(frozen);
    }
}
