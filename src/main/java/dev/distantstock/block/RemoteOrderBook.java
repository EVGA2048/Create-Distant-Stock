package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.config.StockConfig;
import dev.distantstock.item.RequesterData;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.RemoteGaugeOrders;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.stock.StockCache;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The panels on a board that order from another server, and the beat that runs them.
 *
 * <p>A remote gauge board and the signal panel both host remote gauge panels — one as its whole
 * purpose, the other beside its lamps — and both need the same three things: somewhere to keep
 * which warehouse each panel is bound to, a beat that compares what the panel reads against what it
 * wants, and the order that follows. It is kept here rather than written twice because the second
 * copy is always the one that gets forgotten, and a board whose panels order differently from
 * another board's would be a bug nobody could see from the outside.
 *
 * <p>The board itself is passed in rather than inherited from: both hosts already extend Create's
 * panel block entity, and Java has one parent.
 */
final class RemoteOrderBook {
    /** What one panel asked for and when, so a slow delivery is not ordered twice. */
    private record Outstanding(int count, long since) {
    }

    private final FactoryPanelBlockEntity board;
    private final Map<FactoryPanelBlock.PanelSlot, RemoteBinding> bindings =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
    private final Map<FactoryPanelBlock.PanelSlot, Outstanding> outstanding =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);

    RemoteOrderBook(FactoryPanelBlockEntity board) {
        this.board = board;
    }

    boolean isEmpty() {
        return bindings.isEmpty();
    }

    RemoteBinding binding(FactoryPanelBlock.PanelSlot slot) {
        return bindings.get(slot);
    }

    Map<FactoryPanelBlock.PanelSlot, RemoteBinding> bindings() {
        return Map.copyOf(bindings);
    }

    int outstanding(FactoryPanelBlock.PanelSlot slot) {
        Outstanding pending = outstanding.get(slot);
        return pending == null ? 0 : pending.count();
    }

    /** Points one panel at a warehouse, replacing whatever it was pointed at. */
    void bind(FactoryPanelBlock.PanelSlot slot, RemoteNetworkId network, UUID receivingGroup,
              String address) {
        if (network == null || slot == null) {
            return;
        }
        bindings.put(slot, new RemoteBinding(network, receivingGroup, address));
        // Whatever the panel had outstanding was for a different warehouse and must not silence
        // this one.
        outstanding.remove(slot);
        // A remote summary is only refreshed for networks something is watching, and the goggle
        // line that reads it is the only reason this device needs it.
        StockCache.watch(network);
        changed();
    }

    void unbind(FactoryPanelBlock.PanelSlot slot) {
        if (bindings.remove(slot) != null) {
            outstanding.remove(slot);
            changed();
        }
    }

    /** Drops a panel's binding for good, for the slot that no longer has a panel in it. */
    void forget(FactoryPanelBlock.PanelSlot slot) {
        if (bindings.remove(slot) != null || outstanding.remove(slot) != null) {
            changed();
        }
    }

    private void changed() {
        board.setChanged();
        board.sendData();
    }

    // ------------------------------------------------------------------ the ordering beat

    /**
     * Compares every bound panel against its target and files what is missing.
     *
     * <p>Called once a second from the host board's server tick. Nothing here runs for a panel with
     * no binding: an unbound board is a factory gauge, and a factory gauge does not spend the
     * player's stock on its own.
     */
    void tickOrders() {
        Level level = board.getLevel();
        if (level == null || level.isClientSide || bindings.isEmpty()) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            // Once a second, whoever is calling. The reading behind it walks the panel's whole
            // logistics network, and neither host's tick rate is the right pace for that.
            return;
        }
        if (!TowerActivation.active(level, board.getBlockPos())) {
            // A board no tower carries would be placing orders without the machine that moves them.
            // The panels still read; they do not spend.
            return;
        }
        var server = level.getServer();
        if (server == null) {
            return;
        }
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            RemoteBinding binding = bindings.get(slot);
            if (binding == null) {
                continue;
            }
            FactoryPanelBehaviour behaviour = board.panels.get(slot);
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            ItemStack filter = behaviour.getFilter();
            if (filter.isEmpty()) {
                continue;
            }
            int target = RemoteGaugeOrders.target(behaviour.getAmount(), behaviour.upTo,
                    filter.getMaxStackSize());
            int have = behaviour.getLevelInStorage();
            int inFlight = settle(slot, have, target);
            int cap = Math.max(1, filter.getMaxStackSize() * StockConfig.remoteGaugeOrderStacks());
            int count = RemoteGaugeOrders.plan(target, have, inFlight, cap);
            if (count <= 0) {
                continue;
            }
            if (RemoteGaugeOrders.order(server, binding.network(), binding.address(),
                    binding.receivingGroup(), filter, count)) {
                outstanding.put(slot, new Outstanding(count, level.getGameTime()));
                changed();
            }
        }
    }

    /**
     * Drops what this panel had outstanding when it no longer means anything, and reports what is
     * left.
     *
     * <p>Two ways it stops meaning anything, and both are needed. The stock reaching the target is
     * the order having worked; {@link RemoteGaugeOrders#TIMEOUT_TICKS} passing is it having not
     * worked — the parcel went to a dock the player never plumbed into the network, so the reading
     * this panel watches will never move, and a panel that stayed quiet forever would be a machine
     * that stopped with no way to tell.
     */
    private int settle(FactoryPanelBlock.PanelSlot slot, int have, int target) {
        Outstanding pending = outstanding.get(slot);
        if (pending == null) {
            return 0;
        }
        Level level = board.getLevel();
        if (have >= target
                || (level != null && level.getGameTime() - pending.since() > RemoteGaugeOrders.TIMEOUT_TICKS)) {
            outstanding.remove(slot);
            changed();
            return 0;
        }
        return pending.count();
    }

    // ------------------------------------------------------------------ persistence

    void write(CompoundTag tag) {
        CompoundTag bound = new CompoundTag();
        for (var entry : bindings.entrySet()) {
            bound.put(entry.getKey().name(), entry.getValue().save());
        }
        tag.put("RemoteBindings", bound);
        CompoundTag pending = new CompoundTag();
        for (var entry : outstanding.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putInt("Count", entry.getValue().count());
            row.putLong("Since", entry.getValue().since());
            pending.put(entry.getKey().name(), row);
        }
        tag.put("RemoteOutstanding", pending);
    }

    void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        bindings.clear();
        CompoundTag bound = tag.getCompound("RemoteBindings");
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            if (!bound.contains(slot.name())) {
                continue;
            }
            RemoteBinding binding = RemoteBinding.read(bound.getCompound(slot.name()));
            if (binding != null) {
                bindings.put(slot, binding);
            }
        }
        outstanding.clear();
        // The count is server state, told to the client so the goggle line can show it. A client
        // that read it back into its own map would keep a stale count across the update that
        // follows every order.
        if (clientPacket) {
            return;
        }
        CompoundTag pending = tag.getCompound("RemoteOutstanding");
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            if (pending.contains(slot.name())) {
                CompoundTag row = pending.getCompound(slot.name());
                outstanding.put(slot, new Outstanding(row.getInt("Count"), row.getLong("Since")));
            }
        }
    }

    // ------------------------------------------------------------------ goggles

    /** The lines a bound board adds to the goggle overlay. Server side readings, client side text. */
    List<Component> goggleLines() {
        List<Component> tip = new java.util.ArrayList<>();
        if (bindings.isEmpty()) {
            return tip;
        }
        GoggleText.title(tip, "block.distantstock.remote_gauge");
        for (var entry : bindings.entrySet()) {
            String slot = Component.translatable("gui.distantstock.remote_gauge.slot."
                    + entry.getKey().name().toLowerCase(java.util.Locale.ROOT)).getString();
            RemoteBinding binding = entry.getValue();
            GoggleText.line(tip, "goggle.distantstock.remote_gauge.source", slot,
                    binding.network().shortLabel());
            GoggleText.line(tip, "goggle.distantstock.remote_gauge.group",
                    binding.receivingGroup() == null ? "—" : groupName(binding.receivingGroup()));
            FactoryPanelBehaviour behaviour = board.panels.get(entry.getKey());
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            ItemStack filter = behaviour.getFilter();
            GoggleText.line(tip, "goggle.distantstock.remote_gauge.target",
                    RemoteGaugeOrders.target(behaviour.getAmount(), behaviour.upTo,
                            filter.getMaxStackSize()));
            if (!filter.isEmpty()) {
                GoggleText.line(tip, "goggle.distantstock.remote_gauge.stock",
                        behaviour.getLevelInStorage());
            }
            int inFlight = outstanding(entry.getKey());
            if (inFlight > 0) {
                GoggleText.line(tip, "goggle.distantstock.remote_gauge.inflight", inFlight);
            }
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
