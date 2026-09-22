package dev.distantstock.mixin;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRoutingTable;
import dev.distantstock.diagnostics.ChainDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ChainConveyorRoutingTable.class)
public abstract class ChainConveyorRoutingTableMixin {
    @Shadow public List<ChainConveyorRoutingTable.RoutingTableEntry> entriesByDistance;

    @Inject(method = "getExitFor", at = @At("RETURN"), cancellable = true)
    private void distantstock$routeUnroutableTowardDiagnostic(ItemStack stack,
                                                               CallbackInfoReturnable<BlockPos> cir) {
        BlockPos vanilla = cir.getReturnValue();
        if (vanilla != null && !vanilla.equals(BlockPos.ZERO)) return;
        BlockPos diagnostic = ChainDiagnostics.diagnosticExit(entriesByDistance, stack);
        if (!diagnostic.equals(BlockPos.ZERO)) cir.setReturnValue(diagnostic);
    }
}
