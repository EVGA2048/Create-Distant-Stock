package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.TowerActivation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The four panels of one board that order from another server, and the beat that runs them.
 *
 * <p>A remote gauge board and the signal panel both host remote gauge panels — one as its whole
 * purpose, the other beside its lamps — and both need the same two things: somewhere to keep which
 * warehouse each panel is bound to, and a beat that compares what the panel reads against what it
 * wants. Per panel, that is {@link RemoteOrderSlot}; what is left here is the board — four of them,
 * the pace, and the tower that has to be carrying the board before any of them may spend anything.
 *
 * <p>The board is passed in rather than inherited from: both hosts already extend Create's panel
 * block entity, and Java has one parent.
 */
final class RemoteOrderBook {
    private final FactoryPanelBlockEntity board;
    private final Map<FactoryPanelBlock.PanelSlot, RemoteOrderSlot> slots =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);

    RemoteOrderBook(FactoryPanelBlockEntity board) {
        this.board = board;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            slots.put(slot, new RemoteOrderSlot(board, slot));
        }
    }

    boolean isEmpty() {
        for (RemoteOrderSlot slot : slots.values()) {
            if (slot.binding() != null) {
                return false;
            }
        }
        return true;
    }

    RemoteBinding binding(FactoryPanelBlock.PanelSlot slot) {
        RemoteOrderSlot entry = slots.get(slot);
        return entry == null ? null : entry.binding();
    }

    int outstanding(FactoryPanelBlock.PanelSlot slot) {
        RemoteOrderSlot entry = slots.get(slot);
        return entry == null ? 0 : entry.outstanding();
    }

    /** Points one panel at a warehouse, replacing whatever it was pointed at. */
    void bind(FactoryPanelBlock.PanelSlot slot, RemoteNetworkId network, UUID receivingGroup,
              String address) {
        if (network == null || slot == null) {
            return;
        }
        slots.get(slot).bind(new RemoteBinding(network, receivingGroup, address));
    }

    /**
     * 同一件事，但整份绑定一起写。
     *
     * <p>手势那条路（手持终端点面板）从终端身上一次拿到四个字段，包括"过海以后换哪个门牌"；用上面
     * 那个三参版本会把 homeAddress 清掉 —— 而玩家只是把面板重新指一台仓库，不该顺手丢掉另一个设置。
     */
    void bind(FactoryPanelBlock.PanelSlot slot, RemoteBinding binding) {
        if (binding == null || slot == null) {
            return;
        }
        slots.get(slot).bind(binding);
    }

    void unbind(FactoryPanelBlock.PanelSlot slot) {
        if (slot != null) {
            slots.get(slot).unbind();
        }
    }

    /** Drops a panel's binding for good, for the slot that no longer has a panel in it. */
    void forget(FactoryPanelBlock.PanelSlot slot) {
        if (slot != null) {
            slots.get(slot).forget();
        }
    }

    // ------------------------------------------------------------------ the ordering beat

    /**
     * Compares every bound panel against its target and files what is missing.
     *
     * <p>The pace and the tower gate are decided once for the whole board rather than per panel: the
     * reading behind each of them walks its logistics network, no board's tick rate is the right
     * pace for that, and a board no tower carries would be placing orders without the machine that
     * moves them.
     */
    void tickOrders() {
        Level level = board.getLevel();
        if (level == null || level.isClientSide || isEmpty()) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        if (!TowerActivation.active(level, board.getBlockPos())) {
            return;
        }
        for (RemoteOrderSlot slot : slots.values()) {
            slot.tick();
        }
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Writes the board the way it has always been written.
     *
     * <p>Two compounds keyed by slot name, kept exactly as they were: boards saved with this layout
     * exist in the world, and a reader that changed shape would take every binding on every placed
     * board with it. A panel that <em>is</em> a board of its own writes through
     * {@link RemoteOrderSlot#save} directly — it has no history to honour.
     */
    void write(CompoundTag tag) {
        CompoundTag bound = new CompoundTag();
        CompoundTag pending = new CompoundTag();
        for (var entry : slots.entrySet()) {
            RemoteOrderSlot slot = entry.getValue();
            String name = entry.getKey().name();
            if (slot.binding() != null) {
                bound.put(name, slot.binding().save());
            }
            if (slot.outstanding() > 0) {
                CompoundTag row = new CompoundTag();
                row.putInt("Count", slot.outstanding());
                row.putLong("Since", slot.since());
                pending.put(name, row);
            }
        }
        tag.put("RemoteBindings", bound);
        tag.put("RemoteOutstanding", pending);
    }

    void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        CompoundTag bound = tag.getCompound("RemoteBindings");
        CompoundTag pending = tag.getCompound("RemoteOutstanding");
        for (var entry : slots.entrySet()) {
            String name = entry.getKey().name();
            CompoundTag row = new CompoundTag();
            CompoundTag legacy = bound.getCompound(name);
            if (!legacy.isEmpty()) {
                row.put("Binding", legacy);
            }
            CompoundTag flight = pending.getCompound(name);
            row.putInt("Outstanding", flight.getInt("Count"));
            row.putLong("Since", flight.getLong("Since"));
            entry.getValue().load(row, clientPacket);
        }
    }

    // ------------------------------------------------------------------ goggles

    /** The lines the bound panels of this board add to the goggle overlay. */
    List<Component> goggleLines() {
        List<Component> tip = new ArrayList<>();
        if (isEmpty()) {
            return tip;
        }
        GoggleText.title(tip, "block.distantstock.remote_gauge");
        for (RemoteOrderSlot slot : slots.values()) {
            tip.addAll(slot.goggleLines());
        }
        return tip;
    }
}
