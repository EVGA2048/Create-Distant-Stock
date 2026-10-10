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
        // A Frogport with no address registers a blank filter at distance zero, and a blank filter
        // matches an unaddressed parcel, so vanilla hands back that port's connection for every
        // address-less parcel on the chain. That is the reported symptom: parcels drift to a Frogport
        // nobody addressed them to. Ask what the exit would have been with those ports skipped, and
        // prefer it whenever vanilla's answer came only from a blank one.
        if (vanilla != null && !vanilla.equals(BlockPos.ZERO)) {
            if (!ChainDiagnostics.blankPortExit(entriesByDistance, vanilla)) return;
            BlockPos normal = ChainDiagnostics.normalExit(entriesByDistance, stack);
            if (!normal.equals(BlockPos.ZERO)) {
                cir.setReturnValue(normal);
                return;
            }
            // Past the blank filter there is no real destination left, so the parcel is genuinely
            // unroutable and belongs to the diagnostic port. Dropping to ZERO is what lets a
            // diagnostic on this network pick it up; with none present it loops as it always did.
            cir.setReturnValue(BlockPos.ZERO);
            return;
        }
        BlockPos diagnostic = ChainDiagnostics.diagnosticExit(entriesByDistance, stack);
        if (!diagnostic.equals(BlockPos.ZERO)) cir.setReturnValue(diagnostic);
    }
}
