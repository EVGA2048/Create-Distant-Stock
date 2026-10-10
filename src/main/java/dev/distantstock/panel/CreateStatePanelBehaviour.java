package dev.distantstock.panel;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;
import net.liukrast.deployer.lib.logistics.board.AbstractPanelBehaviour;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * A Deployer panel type that is still a Create panel underneath: it has a filter, an amount and a
 * frequency, and all of them survive a save and a sync.
 *
 * <p>Deployer's {@code AbstractPanelBehaviour.write} lets Create write the slot's tag and then
 * <em>replaces</em> it with a fresh one that holds only the connections and whatever
 * {@code easyWrite} adds. A panel type without a filter loses nothing. Ours lost everything Create
 * had written: the client received a lamp with an empty filter — so it was not a lamp, and Deployer
 * drew Create's factory gauge housing in its place — and a remote gauge came back from a reload with
 * no item, no amount and a fresh random frequency.
 *
 * <p>So every key Create wrote that Deployer's tag does not already carry is copied back. Create's
 * own {@code read} runs on that same tag before {@code easyRead}, so nothing has to be read by hand,
 * and keys other mods add to Create's tag through mixins come along too.
 *
 * <p>Deployer's base class also answers {@code EMPTY} for the filter and ignores
 * {@code setNetwork}, on purpose, for panel types that have neither. Ours have both, so both are
 * handed back to Create here.
 */
public abstract class CreateStatePanelBehaviour extends AbstractPanelBehaviour {
    /** The block entity's tag while this panel is writing into it; null at any other time. */
    private CompoundTag writing;

    protected CreateStatePanelBehaviour(PanelType<?> type, FactoryPanelBlockEntity board,
                                        FactoryPanelBlock.PanelSlot slot) {
        super(type, board, slot);
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        writing = nbt;
        try {
            super.write(nbt, registries, clientPacket);
        } finally {
            writing = null;
        }
    }

    /**
     * Copies Create's slot tag into Deployer's replacement before subclasses add their own keys.
     *
     * <p>While this runs the block entity's tag still holds the slot tag Create wrote; Deployer puts
     * its own over it only once {@code easyWrite} returns.
     */
    @Override
    public void easyWrite(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyWrite(tag, registries, clientPacket);
        if (writing == null) {
            return;
        }
        CompoundTag create = writing.getCompound(CreateLang.asId(slot.name()));
        for (String key : create.getAllKeys()) {
            if (!tag.contains(key)) {
                // Create's tag is thrown away right after this, so its entries can move as they are.
                tag.put(key, create.get(key));
            }
        }
    }

    /**
     * The item in this panel's filter slot.
     *
     * <p>Not super.getFilter(): Deployer's base class answers EMPTY on purpose, for panel types that
     * have no filter at all. Drop this and the panel still shows its filter and still reads its
     * network; it simply never sees the item, which on a gauge means it never orders and on a lamp
     * means it is not a lamp.
     */
    @Override
    public ItemStack getFilter() {
        return filter.item();
    }

    /** Create's own: Deployer's base class ignores the frequency it is given. */
    @Override
    public void setNetwork(UUID network) {
        this.network = network;
    }
}
