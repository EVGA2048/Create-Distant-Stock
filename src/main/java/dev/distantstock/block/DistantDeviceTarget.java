package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/**
 * One server-side configuration target for devices that join a Distant Stock network first and
 * select a member warehouse second.
 */
public final class DistantDeviceTarget {
    public static final int WHOLE_DEVICE = -1;

    public static UUID scope(Level level, BlockPos pos, int slot) {
        BlockEntity be = level == null ? null : level.getBlockEntity(pos);
        FactoryPanelBlock.PanelSlot panel = panel(slot);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester && slot == WHOLE_DEVICE) {
            return requester.distantNetworkScope();
        }
        if (be instanceof RemoteGaugeBlockEntity gauge && panel != null) {
            return gauge.distantNetworkScope(panel);
        }
        if (be instanceof SignalPanelBlockEntity signal && panel != null && signal.isRemoteGauge(panel)) {
            return signal.distantNetworkScope(panel);
        }
        return null;
    }

    public static boolean setScope(Level level, BlockPos pos, int slot, UUID scope) {
        if (!DistantNetworkDirectory.isFormalId(scope)) return false;
        BlockEntity be = level == null ? null : level.getBlockEntity(pos);
        FactoryPanelBlock.PanelSlot panel = panel(slot);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester && slot == WHOLE_DEVICE) {
            requester.setDistantNetworkScope(scope);
            return true;
        }
        if (be instanceof RemoteGaugeBlockEntity gauge && panel != null) {
            gauge.setDistantNetworkScope(panel, scope);
            return true;
        }
        if (be instanceof SignalPanelBlockEntity signal && panel != null && signal.isRemoteGauge(panel)) {
            signal.setDistantNetworkScope(panel, scope);
            return true;
        }
        return false;
    }

    public static RemoteBinding binding(Level level, BlockPos pos, int slot) {
        BlockEntity be = level == null ? null : level.getBlockEntity(pos);
        FactoryPanelBlock.PanelSlot panel = panel(slot);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester && slot == WHOLE_DEVICE) {
            return requester.binding();
        }
        if (be instanceof RemoteGaugeBlockEntity gauge && panel != null) {
            return gauge.binding(panel);
        }
        if (be instanceof SignalPanelBlockEntity signal && panel != null && signal.isRemoteGauge(panel)) {
            return signal.binding(panel);
        }
        return null;
    }

    public static boolean selectWarehouse(Level level, BlockPos pos, int slot,
                                          RemoteNetworkId network, UUID scope) {
        if (network == null || !DistantNetworkDirectory.isFormalId(scope)) return false;
        BlockEntity be = level == null ? null : level.getBlockEntity(pos);
        RemoteBinding previous = binding(level, pos, slot);
        UUID group = previous == null ? null : previous.receivingGroup();
        String address = previous == null ? "" : previous.address();
        String home = previous == null ? "" : previous.homeAddress();
        RemoteBinding next = new RemoteBinding(network, scope, group, address, home);
        FactoryPanelBlock.PanelSlot panel = panel(slot);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester && slot == WHOLE_DEVICE) {
            requester.setDistantNetworkScope(scope);
            requester.bind(next);
            return true;
        }
        if (be instanceof RemoteGaugeBlockEntity gauge && panel != null) {
            gauge.setDistantNetworkScope(panel, scope);
            gauge.bind(panel, next);
            return true;
        }
        if (be instanceof SignalPanelBlockEntity signal && panel != null && signal.isRemoteGauge(panel)) {
            signal.setDistantNetworkScope(panel, scope);
            signal.bind(panel, next);
            return true;
        }
        return false;
    }

    private static FactoryPanelBlock.PanelSlot panel(int ordinal) {
        FactoryPanelBlock.PanelSlot[] values = FactoryPanelBlock.PanelSlot.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private DistantDeviceTarget() {
    }
}
