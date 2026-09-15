package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.routing.RemoteGaugeOrders;
import dev.distantstock.stock.StockCache;
import dev.distantstock.routing.TowerActivation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Create's redstone requester, pointed at a warehouse on another server.
 *
 * <p>Everything about the machine is Create's: the nine ghost slots, the screen, the address field,
 * the rising edge that fires it. What changes is where the order goes — a bound requester sends it
 * across servers instead of asking the local packagers, and the goods arrive at a dock group on this
 * side.
 *
 * <p><b>Unbound is a plain redstone requester.</b> The block is a variant of one, and a player who
 * places it and never binds it has a redstone requester with a different coat of paint; taking that
 * away would make an unconfigured machine look broken. The binding is what makes it distant.
 *
 * <p><b>Nothing is ordered twice for one pulse.</b> The pulse is the whole trigger, and the machine
 * answers one edge with one order — there is no outstanding count to keep here as there is on a
 * gauge, because there is no target to hold at. What the far side does with it is its own business.
 */
public final class RemoteRedstoneRequesterBlockEntity extends RedstoneRequesterBlockEntity
        implements IHaveGoggleInformation {
    private RemoteBinding binding;

    public RemoteRedstoneRequesterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REMOTE_REDSTONE_REQUESTER.get(), pos, state);
    }

    @Nullable
    public RemoteBinding binding() {
        return binding;
    }

    /** Points this requester at a warehouse. Null unbinds it, leaving Create's own behaviour. */
    public void bind(@Nullable RemoteBinding next) {
        this.binding = next;
        if (next != null) {
            // The far stock is only refreshed for networks something is watching, and the partial
            // check below is the only reason this machine reads it.
            StockCache.watch(next.network());
        }
        setChanged();
        sendData();
    }

    /**
     * One pulse, one order.
     *
     * <p>Bound, the order goes across servers; unbound, Create's own path runs untouched. The
     * partial check is against the warehouse's cached summary rather than this world's, because the
     * question "can they supply this" is only answerable by their side — and when nothing has
     * answered yet, the order goes anyway. Refusing to order until a cache this machine does not
     * control happens to be warm would be a machine that ignores its pulse for no reason the player
     * could see, and an order the far side cannot fill comes back through the return path rather
     * than vanishing.
     */
    @Override
    public void triggerRequest() {
        RemoteBinding bound = binding;
        if (bound == null) {
            super.triggerRequest();
            return;
        }
        if (encodedRequest == null || encodedRequest.isEmpty()) {
            return;
        }
        if (level == null || !TowerActivation.active(level, worldPosition)) {
            // Carried by a tower or it does not send. The unbound path above is Create's own machine
            // and is left alone; this one spends a warehouse's stock across a server boundary, and
            // that is the tower's to carry.
            playEffect(false);
            lastRequestSucceeded = false;
            return;
        }
        List<LinkQueues.Line> lines = linesFor(bound);
        if (lines.isEmpty()) {
            playEffect(false);
            lastRequestSucceeded = false;
            return;
        }
        boolean ok = RemoteGaugeOrders.orderAll(level == null ? null : level.getServer(),
                bound.network(), bound.address(), bound.receivingGroup(), lines);
        lastRequestSucceeded = ok;
        playEffect(ok);
    }

    /** The configured items as order lines, cut down to what the warehouse is known to hold. */
    private List<LinkQueues.Line> linesFor(RemoteBinding bound) {
        Map<String, Integer> available = cachedStock(bound);
        List<LinkQueues.Line> lines = new ArrayList<>();
        for (BigItemStack entry : encodedRequest.stacks()) {
            if (entry == null || entry.stack == null || entry.stack.isEmpty() || entry.count <= 0) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString();
            int count = entry.count;
            Integer known = available.get(id);
            if (known != null) {
                if (known <= 0) {
                    if (!allowPartialRequests) {
                        // Not a partial order but a missing line: the far side cannot supply any of
                        // it, and sending the rest would answer a pulse with something the player
                        // did not ask for.
                        return List.of();
                    }
                    continue;
                }
                count = Math.min(count, known);
            }
            lines.add(new LinkQueues.Line(id, count));
        }
        return lines;
    }

    /** The warehouse's last reported stock, by item id, or empty when nothing has reported yet. */
    private Map<String, Integer> cachedStock(RemoteBinding bound) {
        List<StockCache.Entry> summary = StockCache.get(bound.network());
        if (summary == null || summary.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (StockCache.Entry entry : summary) {
            if (entry != null && entry.itemId != null) {
                counts.merge(entry.itemId, entry.count, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Where this machine orders from, or nothing at all when it is a plain requester.
     *
     * <p>An unbound machine says nothing rather than saying "unbound": the screen behind it already
     * shows nine items and an address, and a line announcing the absence of a binding would read as
     * a fault on a machine that is working exactly as a redstone requester should.
     */
    @Override
    public boolean addToGoggleTooltip(List<net.minecraft.network.chat.Component> tooltip, boolean sneaking) {
        tooltip.addAll(bindingLines());
        return !tooltip.isEmpty();
    }

    /** How this machine's binding is doing, for the goggles. */
    public List<net.minecraft.network.chat.Component> bindingLines() {
        if (binding == null) {
            return List.of();
        }
        List<net.minecraft.network.chat.Component> tip = new ArrayList<>();
        GoggleText.title(tip, "block.distantstock.remote_redstone_requester");
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.source", binding.network().shortLabel());
        GoggleText.line(tip, "goggle.distantstock.remote_gauge.group",
                binding.receivingGroup() == null ? "—" : RequesterData.shortFreq(binding.receivingGroup()));
        return tip;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (binding != null) {
            tag.put("RemoteBinding", binding.save());
        }
    }

    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        // The drop carries its binding: a machine picked up and put down again should still know
        // where its goods come from, the same way it keeps the nine items and the address.
        if (binding != null) {
            tag.put("RemoteBinding", binding.save());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        binding = RemoteBinding.read(tag.getCompound("RemoteBinding"));
    }
}
