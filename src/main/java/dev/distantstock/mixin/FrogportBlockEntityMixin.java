package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.diagnostics.PingPackageData;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrogportBlockEntity.class)
public abstract class FrogportBlockEntityMixin {
    @Inject(method = "startAnimation", at = @At("HEAD"), cancellable = true)
    private void distantstock$consumePingAtReceiver(ItemStack stack, boolean depositing, CallbackInfo ci) {
        if (depositing || !PingPackageData.isPing(stack)) return;
        ChainDiagnostics.pingArrived((FrogportBlockEntity) (Object) this, stack);
        ci.cancel();
    }
}
