package dev.distantstock.panel;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.block.RemoteGaugeModels;
import dev.distantstock.block.RemoteOrderSlot;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.TowerActivation;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.liukrast.deployer.lib.logistics.board.AbstractPanelBehaviour;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.logistics.board.connection.PanelConnectionBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A remote gauge as a panel <em>type</em>: the same machine, able to sit on anybody's board.
 *
 * <p>Everything a gauge is, Create already provides — the filter, the amount, the value box, the
 * reading of the network the board is attached to — because Deployer's base class is Create's own
 * panel behaviour. What is added here is the half that is ours: where the goods come from when the
 * reading is too low. That is {@link RemoteOrderSlot}, the same object the board's four slots use,
 * so a remote gauge on somebody else's board counts exactly like a remote gauge on ours.
 *
 * <p>The pace is the panel's own, and it is the same pace the board uses: once a second, and only
 * while a tower carries this position. A panel that ordered on every tick would drain a warehouse;
 * one that ordered without a tower would spend stock nothing is able to move.
 */
public class RemoteGaugePanelBehaviour extends AbstractPanelBehaviour {
    private final RemoteOrderSlot orders;

    public RemoteGaugePanelBehaviour(PanelType<?> type, FactoryPanelBlockEntity board,
                                     FactoryPanelBlock.PanelSlot slot) {
        super(type, board, slot);
        this.orders = new RemoteOrderSlot(board, slot);
    }

    /** Where this panel orders from. Never null; a panel with no binding simply does not order. */
    public RemoteOrderSlot orders() {
        return orders;
    }

    /**
     * Nothing to offer a neighbour.
     *
     * <p>A connection between panels carries a promise: one panel undertakes to supply the other's
     * item. This panel's goods come from another server, so it has nothing to promise locally, and
     * advertising a connection it cannot honour would be worse than advertising none.
     */
    @Override
    public void addConnections(PanelConnectionBuilder builder) {
    }

    /**
     * The item in this panel's filter slot.
     *
     * <p>Deployer's base class deliberately answers {@code EMPTY} here — a panel type that has no
     * filter should not pretend to have one — so every type that does have one has to say so. Drop
     * this and the panel still shows its filter and still reads its network; it simply never sees
     * the item, which on a gauge means it never orders and on a lamp means it is not a lamp.
     */
    @Override
    public ItemStack getFilter() {
        // Not super.getFilter(): Deployer's base class answers EMPTY on purpose, for panel types
        // that have no filter at all, and calling up to it would hand us that empty stack. The
        // filter itself is Create's, kept where Create keeps it.
        return filter.item();
    }

    @Override
    public Item getItem() {
        return ModItems.REMOTE_GAUGE.get();
    }

    @Override
    public PartialModel getModel(FactoryPanelBlock.PanelState state, FactoryPanelBlock.PanelType type) {
        return RemoteGaugeModels.panel(type == FactoryPanelBlock.PanelType.PACKAGER,
                state == FactoryPanelBlock.PanelState.ACTIVE);
    }

    @Override
    public void tick() {
        super.tick();
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide || !isActive()) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        if (!TowerActivation.active(level, blockEntity.getBlockPos())) {
            return;
        }
        orders.tick();
    }

    @Override
    public void easyWrite(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyWrite(tag, registries, clientPacket);
        CompoundTag ours = new CompoundTag();
        orders.save(ours);
        tag.put("DistantStock", ours);
    }

    @Override
    public void easyRead(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyRead(tag, registries, clientPacket);
        if (tag.contains("DistantStock")) {
            orders.load(tag.getCompound("DistantStock"), clientPacket);
        }
    }
}
