package dev.distantstock.mixin;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.kinetics.chainConveyor.ChainPackageInteractionPacket;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.diagnostics.PingPackageData;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChainPackageInteractionPacket.class)
public abstract class ChainPackageInteractionPacketMixin {
    @Shadow @Final private BlockPos selectedConnection;
    @Shadow @Final private float chainPosition;
    @Shadow @Final private boolean removingPackage;

    @Inject(method = "applySettings", at = @At("HEAD"))
    private void distantstock$skipProbeWhenPlayerTakesIt(ServerPlayer player,
                                                          ChainConveyorBlockEntity conveyor,
                                                          CallbackInfo ci) {
        if (!removingPackage) return;
        ChainConveyorAccessor accessor = (ChainConveyorAccessor) conveyor;
        List<ChainConveyorPackage> packages = selectedConnection == null || selectedConnection.equals(BlockPos.ZERO)
                ? accessor.distantstock$loopingPackages()
                : accessor.distantstock$travellingPackages().get(selectedConnection);
        if (packages == null || packages.isEmpty()) return;

        ChainConveyorPackage nearest = null;
        float best = Float.POSITIVE_INFINITY;
        for (ChainConveyorPackage box : packages) {
            float diff = selectedConnection == null || selectedConnection.equals(BlockPos.ZERO)
                    ? Math.abs(AngleHelper.getShortestAngleDiff(box.chainPosition, chainPosition))
                    : Math.abs(box.chainPosition - chainPosition);
            if (diff < best) {
                best = diff;
                nearest = box;
            }
        }
        if (nearest != null && PingPackageData.isPing(nearest.item)) {
            // Mutate before Create copies the stack into the player's hand; the copy is therefore
            // already a stale return-to-diagnostic probe if they put it back on the chain.
            ChainDiagnostics.playerRemovedPing(player, nearest.item);
        }
    }
}
