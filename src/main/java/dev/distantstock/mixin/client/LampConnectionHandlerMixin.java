package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import dev.distantstock.block.LampConnections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FactoryPanelConnectionHandler.class, remap = false)
public abstract class LampConnectionHandlerMixin {
    @Inject(method = "checkForIssues(Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;Lcom/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void distantstock$lampOutput(FactoryPanelBehaviour from, FactoryPanelBehaviour to,
                                              CallbackInfoReturnable<String> cir) {
        if (LampConnections.isLamp(to)) cir.setReturnValue(LampConnections.check(from, to));
        else if (LampConnections.isLamp(from)) cir.setReturnValue(LampConnections.check(to, from));
    }
}
