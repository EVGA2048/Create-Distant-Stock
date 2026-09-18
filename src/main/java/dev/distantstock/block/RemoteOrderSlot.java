package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.RequesterData;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.RemoteGaugeOrders;
import dev.distantstock.stock.StockCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * One panel's side of an order: where its goods come from, what is already on its way, and the beat
 * that decides whether to ask again.
 *
 * <p>A board keeps four of these, one per slot; a panel registered as a type of its own keeps one,
 * because that panel is its own board. The counting is one implementation rather than two because
 * it is the part that goes wrong quietly: a panel that asks every beat drains a warehouse, and a
 * panel that never asks again stops a factory with nothing on screen to say why. Two copies of that
 * would eventually disagree, and the disagreement would look like one board behaving worse than
 * another for no reason a player could see.
 *
 * <p>Everything here is per panel. The pace and the tower gate belong to whoever drives the beat,
 * because they are the same for every panel on a board and asking them once is the point.
 */
public final class RemoteOrderSlot {
    private final FactoryPanelBlockEntity board;
    private final FactoryPanelBlock.PanelSlot slot;
    private RemoteBinding binding;
    /** What this panel asked for and has not seen arrive, so a slow delivery is not ordered twice. */
    private int outstanding;
    private long since;

    public RemoteOrderSlot(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        this.board = board;
        this.slot = slot;
    }

    public RemoteBinding binding() {
        return binding;
    }

    public int outstanding() {
        return outstanding;
    }

    /** Points this panel at a warehouse, replacing whatever it was pointed at. */
    public void bind(RemoteBinding next) {
        if (next == null) {
            return;
        }
        binding = next;
        // Whatever this panel had outstanding was for a different warehouse and must not silence
        // this one.
        outstanding = 0;
        // A remote summary is only refreshed for networks something is watching, and the goggle
        // line that reads it is the only reason this device needs it.
        StockCache.watch(next.network());
        StockCache.clearRefusal(next.network());
        changed();
    }

    public void unbind() {
        if (binding != null) {
            binding = null;
            outstanding = 0;
            changed();
        }
    }

    /** Drops this panel's binding for good, for a slot that no longer has a panel in it. */
    public void forget() {
        if (binding != null || outstanding != 0) {
            binding = null;
            outstanding = 0;
            changed();
        }
    }

    /**
     * One beat: write off what no longer means anything, then ask for what is missing.
     *
     * <p>Does nothing at all for a panel with no binding. An unbound panel is a factory gauge, and a
     * factory gauge does not spend the player's stock on its own.
     */
    public void tick() {
        if (binding == null) {
            return;
        }
        Level level = board.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        FactoryPanelBehaviour behaviour = board.panels.get(slot);
        if (behaviour == null || !behaviour.isActive()) {
            return;
        }
        ItemStack filter = behaviour.getFilter();
        if (filter.isEmpty()) {
            return;
        }
        int target = RemoteGaugeOrders.target(behaviour.getAmount(), behaviour.upTo,
                filter.getMaxStackSize());
        int have = behaviour.getLevelInStorage();
        settle(have, target);
        int cap = Math.max(1, filter.getMaxStackSize() * StockConfig.remoteGaugeOrderStacks());
        int count = RemoteGaugeOrders.plan(target, have, outstanding, cap);
        if (count <= 0) {
            return;
        }
        if (RemoteGaugeOrders.order(level.getServer(), binding.network(), binding.address(),
                binding.receivingGroup(), filter, count)) {
            outstanding = count;
            since = level.getGameTime();
            changed();
        }
    }

    /**
     * Drops what this panel had outstanding when it no longer means anything, then reports what is
     * left.
     *
     * <p>Two ways it stops meaning anything, and both are needed. The stock reaching the target is
     * the order having worked; {@link RemoteGaugeOrders#TIMEOUT_TICKS} passing is it having not
     * worked — the parcel went to a dock the player never plumbed into the network, so the reading
     * this panel watches will never move, and a panel that stayed quiet forever would be a machine
     * that stopped with no way to tell.
     */
    private void settle(int have, int target) {
        if (outstanding == 0) {
            return;
        }
        Level level = board.getLevel();
        if (have >= target || (level != null
                && level.getGameTime() - since > RemoteGaugeOrders.TIMEOUT_TICKS)) {
            outstanding = 0;
            changed();
        }
    }

    private void changed() {
        board.setChanged();
        board.sendData();
    }

    // ------------------------------------------------------------------ persistence

    /** Writes this one panel's state under its own keys. The board wraps these per slot. */
    public void save(CompoundTag tag) {
        if (binding != null) {
            tag.put("Binding", binding.save());
        }
        tag.putInt("Outstanding", outstanding);
        tag.putLong("Since", since);
    }

    public void load(CompoundTag tag, boolean clientPacket) {
        binding = RemoteBinding.read(tag.getCompound("Binding"));
        if (binding != null) {
            StockCache.watch(binding.network());
        }
        // The count is server state, told to the client so the goggle line can show it. A client
        // that read it back into its own field would keep a stale count across the update that
        // follows every order.
        if (clientPacket) {
            return;
        }
        outstanding = tag.getInt("Outstanding");
        since = tag.getLong("Since");
    }

    /** When the outstanding order was filed, for the board's own record of it. */
    public long since() {
        return since;
    }

    // ------------------------------------------------------------------ goggles

    /** The lines this panel adds to the goggle overlay. Server side readings, client side text. */
    public List<Component> goggleLines() {
        if (binding == null) {
            return List.of();
        }
        List<Component> tip = new java.util.ArrayList<>();
        String label = Component.translatable("gui.distantstock.remote_gauge.slot."
                + slot.name().toLowerCase(java.util.Locale.ROOT)).getString();
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.source", label,
                binding.network().shortLabel());
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.group",
                binding.receivingGroup() == null ? "—" : groupName(binding.receivingGroup()));
        FactoryPanelBehaviour behaviour = board.panels.get(slot);
        if (behaviour == null || !behaviour.isActive()) {
            return tip;
        }
        ItemStack filter = behaviour.getFilter();
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.target",
                RemoteGaugeOrders.target(behaviour.getAmount(), behaviour.upTo,
                        filter.getMaxStackSize()));
        if (!filter.isEmpty()) {
            GoggleText.line(tip, "goggle.distantstock.remote_gauge.stock",
                    behaviour.getLevelInStorage());
        }
        if (outstanding > 0) {
            GoggleText.line(tip, "goggle.distantstock.remote_gauge.inflight", outstanding);
        }
        return tip;
    }

    private String groupName(UUID group) {
        Level level = board.getLevel();
        if (level == null || level.getServer() == null) {
            return RequesterData.shortFreq(group);
        }
        return DockGroupDirectory.get(level.getServer())
                .find(group)
                .map(DockGroup::name)
                .orElse(RequesterData.shortFreq(group));
    }
}
