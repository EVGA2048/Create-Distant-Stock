package dev.distantstock.mixin.client;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import dev.distantstock.block.LampConnections;
import dev.distantstock.block.ModBlocks;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import net.minecraft.core.BlockPos;
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
        else if (from != null && to != null) {
            var a = from.blockEntity.getBlockState();
            var b = to.blockEntity.getBlockState();
            if (a.getBlock() == b.getBlock() || !(a.getBlock() instanceof FactoryPanelBlock)
                    || !(b.getBlock() instanceof FactoryPanelBlock)
                    || !(a.is(ModBlocks.REMOTE_GAUGE.get()) || b.is(ModBlocks.REMOTE_GAUGE.get())
                    || a.is(ModBlocks.SIGNAL_PANEL.get()) || b.is(ModBlocks.SIGNAL_PANEL.get()))) return;
            String issue = null;
            BlockPos diff = to.getPos().subtract(from.getPos());
            if (from.targetedBy.containsKey(to.getPanelPosition())) issue = "factory_panel.already_connected";
            else if (from.targetedBy.size() >= 9) issue = "factory_panel.cannot_add_more_inputs";
            else if (a.getValue(FactoryPanelBlock.FACE) != b.getValue(FactoryPanelBlock.FACE)
                    || a.getValue(FactoryPanelBlock.FACING) != b.getValue(FactoryPanelBlock.FACING))
                issue = "factory_panel.same_orientation";
            else if (FactoryPanelBlock.connectedDirection(a).getAxis().choose(diff.getX(), diff.getY(), diff.getZ()) != 0)
                issue = "factory_panel.same_surface";
            else if (!diff.closerThan(BlockPos.ZERO, 16)) issue = "factory_panel.too_far_apart";
            else if (to.panelBE().restocker) issue = "factory_panel.input_in_restock_mode";
            else if (to.getFilter().isEmpty() || from.getFilter().isEmpty()) issue = "factory_panel.no_item";
            cir.setReturnValue(issue);
        }
    }
}
