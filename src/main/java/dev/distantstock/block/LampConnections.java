package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Lamp connections are signal outputs, never recipe ingredients. */
public final class LampConnections {
    public static boolean isLamp(FactoryPanelBehaviour panel) {
        return panel != null && panel.blockEntity instanceof SignalPanelBlockEntity be
                && be.isLamp(panel.getPanelPosition().slot());
    }

    public static String check(FactoryPanelBehaviour gauge, FactoryPanelBehaviour lamp) {
        if (gauge == null || lamp == null || !gauge.isActive() || !lamp.isActive()) return "factory_panel.connection_aborted";
        if (isLamp(gauge) || !isLamp(lamp)) return "factory_panel.connection_aborted";
        if (lamp.targetedBy.containsKey(gauge.getPanelPosition())) return "factory_panel.already_connected";
        if (lamp.targetedBy.size() >= 9) return "factory_panel.cannot_add_more_inputs";
        var a = gauge.blockEntity.getBlockState();
        var b = lamp.blockEntity.getBlockState();
        if (a.getValue(BlockStateProperties.ATTACH_FACE) != b.getValue(BlockStateProperties.ATTACH_FACE)
                || a.getValue(BlockStateProperties.HORIZONTAL_FACING) != b.getValue(BlockStateProperties.HORIZONTAL_FACING))
            return "factory_panel.same_orientation";
        BlockPos diff = gauge.getPos().subtract(lamp.getPos());
        var axis = com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.connectedDirection(a).getAxis();
        if (axis.choose(diff.getX(), diff.getY(), diff.getZ()) != 0) return "factory_panel.same_surface";
        if (!diff.closerThan(BlockPos.ZERO, 16)) return "factory_panel.too_far_apart";
        return gauge.getFilter().isEmpty() ? "factory_panel.no_item" : null;
    }
    private LampConnections() {}
}
