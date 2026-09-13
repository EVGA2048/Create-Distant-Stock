package dev.distantstock.mixin;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import dev.distantstock.block.LampConnections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public abstract class LampConnectionMixin {
    @Inject(method = "addConnection", at = @At("HEAD"), cancellable = true, require = 1)
    private void distantstock$signalDirection(FactoryPanelPosition sourcePos, CallbackInfo ci) {
        FactoryPanelBehaviour target = (FactoryPanelBehaviour) (Object) this;
        FactoryPanelBehaviour source = FactoryPanelBehaviour.at(target.getWorld(), sourcePos);
        if (LampConnections.isLamp(source)) {
            // Create's 'add connection' normally adds an ingredient input to the starting gauge.
            // Clicking a lamp instead makes that gauge the source and the lamp the destination.
            ci.cancel();
            if (LampConnections.check(target, source) == null) {
                source.addConnection(target.getPanelPosition());
                target.blockEntity.notifyUpdate();
            }
        } else if (LampConnections.isLamp(target)) {
            if (LampConnections.check(source, target) != null) ci.cancel();
        }
    }
}
