package dev.distantstock.mixin.compat;

import com.simibubi.create.content.logistics.BigItemStack;
import dev.distantstock.compat.fluidlogistics.FluidLogisticsCompat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Optional FluidLogistics hook. After its own request packaging is finished, promote only packages
 * whose Create order id belongs to Distant Stock's remembered remote order.
 */
@Pseudo
@Mixin(targets = "com.yision.fluidlogistics.block.FluidPackager.FluidPackagerBlockEntity", remap = false)
public abstract class FluidPackagerBlockEntityMixin {
    @Shadow public ItemStack heldBox;
    @Shadow public List<BigItemStack> queuedExitingPackages;

    @Inject(method = "attemptToSendFluidRequest", at = @At("RETURN"), require = 0)
    private void distantstock$promoteRemoteFluidOrders(List<?> requests, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide || self.getLevel().getServer() == null) {
            return;
        }
        boolean changed = false;
        ItemStack promotedHeld = FluidLogisticsCompat.promoteIfRemoteOrder(heldBox, self.getLevel().getServer());
        if (promotedHeld != heldBox) {
            heldBox = promotedHeld;
            changed = true;
        }
        if (queuedExitingPackages != null) {
            for (BigItemStack queued : queuedExitingPackages) {
                ItemStack promoted = FluidLogisticsCompat.promoteIfRemoteOrder(
                        queued.stack, self.getLevel().getServer());
                if (promoted != queued.stack) {
                    queued.stack = promoted;
                    changed = true;
                }
            }
        }
        if (changed && self instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) {
            smart.setChanged();
            smart.notifyUpdate();
        }
    }
}
