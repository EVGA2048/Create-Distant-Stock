package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import dev.distantstock.routing.RemoteOrderParcelStamp;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Remote-terminal routing belongs to the order, not to the colour of the packager.
 *
 * <p>Stamp after Create has produced/queued boxes. Vanilla packagers keep vanilla package items;
 * RemotePackagerBlockEntity's subclass override subsequently transmutes its own output to blue while
 * preserving these data components.
 */
@Mixin(value = PackagerBlockEntity.class, remap = false)
public abstract class PackagerBlockEntityMixin {
    @Inject(method = "attemptToSend", at = @At("RETURN"), require = 1)
    private void distantstock$stampRemoteOrderPackages(List<PackagingRequest> requests, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide || self.getLevel().getServer() == null) {
            return;
        }
        PackagerBlockEntity packager = (PackagerBlockEntity) (Object) this;
        if (RemoteOrderParcelStamp.stamp(packager, self.getLevel().getServer())) {
            packager.setChanged();
            packager.notifyUpdate();
        }
    }
}
