package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's target support check is intentionally strict to its own Frogport BE type.
 * Distant Stock's two variants are behavioural subclasses with their own registered BE types,
 * so explicitly admit them without widening support to unrelated block entities.
 */
@Mixin(PackagePortTarget.ChainConveyorFrogportTarget.class)
public abstract class ChainConveyorFrogportTargetMixin {
    @Inject(method = "canSupport", at = @At("HEAD"), cancellable = true)
    private void distantstock$allowDiagnosticFrogports(BlockEntity be,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (be instanceof DiagnosticFrogportBlockEntity || be instanceof CacheFrogportBlockEntity) {
            cir.setReturnValue(true);
        }
    }
}
