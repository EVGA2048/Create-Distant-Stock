package dev.distantstock.mixin.client;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.packager.PackagerRenderer;
import dev.distantstock.block.RemotePackagerBlock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both Create's vanilla renderer and Flywheel visual use this selector. */
@Mixin(value = PackagerRenderer.class, remap = false)
public abstract class PackagerRendererMixin {
    @Inject(method = "getTrayModel", at = @At("HEAD"), cancellable = true, require = 1)
    private static void distantstock$regularTray(BlockState state,
                                                CallbackInfoReturnable<PartialModel> cir) {
        if (state.getBlock() instanceof RemotePackagerBlock) {
            cir.setReturnValue(AllPartialModels.PACKAGER_TRAY_REGULAR);
        }
    }
}
