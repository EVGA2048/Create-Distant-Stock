package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.config.StockConfig;
import dev.distantstock.routing.RemoteGaugeOrders;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.stock.StockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A factory gauge board whose panels can order from another server.
 *
 * <p>Each panel keeps everything Create gives it — its filter, its target amount, its value box,
 * its connections — and gains one thing: a binding that says where its goods should come from. A
 * bound panel watches the number its own logistics network reports, exactly as an unbound one does,
 * and when that number is short of the target it files an order with the bound warehouse instead of
 * asking the local packagers for the item.
 *
 * <p><b>Unbound is the old behaviour, exactly.</b> Every panel on every board placed before this
 * existed has no binding, and a board that did anything on their behalf would turn a display into a
 * device that spends the player's stock. Nothing here runs for a panel with no binding.
 *
 * <p><b>One order at a time, per panel.</b> See {@link RemoteGaugeOrders}: the panel counts what it
 * asked for and stays quiet until the stock it watches reaches the target or the order is written
 * off as lost.
 */
public final class RemoteGaugeBlockEntity extends FactoryPanelBlockEntity implements IHaveGoggleInformation {
    private final RemoteOrderBook orders = new RemoteOrderBook(this);

    public RemoteGaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REMOTE_GAUGE.get(), pos, state);
    }

    /** This panel's binding, or null when it is an ordinary factory gauge. */
    public RemoteOrderBook.Binding binding(FactoryPanelBlock.PanelSlot slot) {
        return orders.binding(slot);
    }

    /** Points one panel at a warehouse, replacing whatever it was pointed at. */
    public void bind(FactoryPanelBlock.PanelSlot slot, RemoteNetworkId network, UUID receivingGroup,
                     String address) {
        orders.bind(slot, network, receivingGroup, address);
    }

    public void unbind(FactoryPanelBlock.PanelSlot slot) {
        orders.unbind(slot);
    }

    /** How much this panel has asked for and not yet seen arrive. */
    public int outstanding(FactoryPanelBlock.PanelSlot slot) {
        return orders.outstanding(slot);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RemoteGaugeBlockEntity be) {
        be.orders.tickOrders();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        orders.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        orders.read(tag, registries, clientPacket);
    }

    /**
     * What this board is bound to, per panel.
     *
     * <p>Nothing is drawn for an unbound board: Create's own panel lines are where a factory gauge's
     * readings belong when it is only a factory gauge, and printing an empty block under them would
     * suggest this board is a device that is switched off rather than one that never had a source.
     */
    @Override
    public boolean addToGoggleTooltip(java.util.List<Component> tooltip, boolean isPlayerSneaking) {
        java.util.List<Component> lines = orders.goggleLines();
        if (lines.isEmpty()) {
            return false;
        }
        tooltip.addAll(lines);
        return true;
    }
}
