package dev.distantstock.mixin;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.diagnostics.ChainDiagnostics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChainConveyorBlockEntity.class)
public abstract class ChainConveyorBlockEntityMixin {
    @Redirect(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/logistics/box/PackageItem;matchAddress(Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;)Z"))
    private boolean distantstock$diagnosticPortOnlyCatchesUnroutable(ItemStack stack, String filter) {
        return ChainDiagnostics.matchDiagnosticPort((ChainConveyorBlockEntity) (Object) this, stack, filter);
    }
}
