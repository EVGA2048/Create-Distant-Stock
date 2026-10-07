package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortConfigurationPacket;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic/cache Frogport routing is system-owned. Ignore Create's ordinary Frogport address
 * configuration packet so closing the stock GUI (or a modified client) cannot overwrite it.
 */
@Mixin(PackagePortConfigurationPacket.class)
public abstract class PackagePortConfigurationPacketMixin {
    @Inject(method = "applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/logistics/packagePort/PackagePortBlockEntity;)V",
            at = @At("HEAD"), cancellable = true)
    private void distantstock$protectManagedFrogportSettings(ServerPlayer player,
                                                              PackagePortBlockEntity be,
                                                              CallbackInfo ci) {
        if (be instanceof DiagnosticFrogportBlockEntity || be instanceof CacheFrogportBlockEntity) {
            ci.cancel();
        }
    }
}
