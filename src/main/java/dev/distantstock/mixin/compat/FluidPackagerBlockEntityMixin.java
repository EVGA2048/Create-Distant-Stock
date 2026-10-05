package dev.distantstock.mixin.compat;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import dev.distantstock.compat.fluidlogistics.FluidLogisticsCompat;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FluidLogistics 1.3.x no longer has attemptToSendFluidRequest(). Fluid packaging now runs through
 * ResourcePackagerEngine and writes the resulting package into the ordinary Create Packager fields.
 * Hook the engine output boundary, then promote only packages belonging to remembered Distant Stock
 * orders. @Coerce keeps the optional FluidLogistics ResourcePackager type out of our class linkage.
 */
@Pseudo
@Mixin(targets = "com.yision.fluidlogistics.content.logistics.packageResource.ResourcePackagerEngine", remap = false)
public abstract class FluidPackagerBlockEntityMixin {
    @Inject(method = "output", at = @At("RETURN"), require = 1)
    private static void distantstock$promoteRemoteFluidOrders(PackagerBlockEntity owner,
                                                               @Coerce Object resourcePackager,
                                                               ItemStack produced, CallbackInfo ci) {
        if (owner == null || owner.getLevel() == null || owner.getLevel().isClientSide
                || owner.getLevel().getServer() == null) {
            return;
        }

        boolean changed = false;
        ItemStack promotedHeld = FluidLogisticsCompat.promoteIfRemoteOrder(
                owner.heldBox, owner.getLevel().getServer());
        if (promotedHeld != owner.heldBox) {
            owner.heldBox = promotedHeld;
            changed = true;
        }
        if (owner.queuedExitingPackages != null) {
            for (var queued : owner.queuedExitingPackages) {
                ItemStack promoted = FluidLogisticsCompat.promoteIfRemoteOrder(
                        queued.stack, owner.getLevel().getServer());
                if (promoted != queued.stack) {
                    queued.stack = promoted;
                    changed = true;
                }
            }
        }
        if (changed) {
            owner.setChanged();
            owner.notifyUpdate();
        }
    }
}
