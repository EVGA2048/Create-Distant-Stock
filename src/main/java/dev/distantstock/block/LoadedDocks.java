package dev.distantstock.block;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DockSelection;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoadedDocks {
    private static final Set<DockBlockEntity> ALL = ConcurrentHashMap.newKeySet();
    private static final Set<GaugeBlockEntity> GAUGES = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, AtomicInteger> NEXT_BY_GROUP = new ConcurrentHashMap<>();

    public static void add(DockBlockEntity be) {
        ALL.add(be);
    }

    public static void remove(DockBlockEntity be) {
        ALL.remove(be);
    }

    public static void add(GaugeBlockEntity be) {
        GAUGES.add(be);
    }

    public static void remove(GaugeBlockEntity be) {
        GAUGES.remove(be);
    }

    public static List<UUID> watched() {
        List<UUID> out = new ArrayList<>();
        for (DockBlockEntity be : ALL) {
            if (be.freq() != null) {
                out.add(be.freq());
            }
        }
        for (GaugeBlockEntity be : GAUGES) {
            if (be.freq() != null) {
                out.add(be.freq());
            }
        }
        return out;
    }

    public static DockBlockEntity importFor(ItemStack pkg) {
        return importFor(pkg, DockGroupDirectory.DEFAULT_GROUP_ID);
    }

    /**
     * Selects an available dock in the requested group: the highest priority tier that has room wins, and
     * docks of equal priority take turns in a stable order.
     */
    public static DockBlockEntity importFor(ItemStack pkg, UUID groupId) {
        List<DockBlockEntity> matching = new ArrayList<>();
        for (DockBlockEntity be : ALL) {
            if (!be.canReceive() || be.isRemoved() || !be.groupId().equals(groupId)) {
                continue;
            }
            String filter = be.address().isBlank() ? "*" : be.address();
            if (!PackageItem.matchAddress(pkg, filter)) {
                continue;
            }
            matching.add(be);
        }
        if (matching.isEmpty()) {
            return null;
        }
        matching.sort(Comparator
                .comparing((DockBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        List<DockSelection.Candidate> candidates = matching.stream()
                .map(be -> new DockSelection.Candidate(be.priority(), !be.isFull()))
                .toList();
        int sequence = NEXT_BY_GROUP.computeIfAbsent(groupId, ignored -> new AtomicInteger())
                .getAndIncrement();
        int index = DockSelection.select(candidates, sequence);
        return index < 0 ? null : matching.get(index);
    }

    public static void noMatch(ItemStack pkg) {
        noMatch(pkg, DockGroupDirectory.DEFAULT_GROUP_ID);
    }

    public static void noMatch(ItemStack pkg, UUID groupId) {
        for (DockBlockEntity be : ALL) {
            if (!be.canReceive() || be.isRemoved() || !be.groupId().equals(groupId)) {
                continue;
            }
            String filter = be.address().isBlank() ? "*" : be.address();
            if (!PackageItem.matchAddress(pkg, filter)) {
                be.rejected();
            }
        }
    }

    public static DockBlockEntity at(String dimension, long packedPos) {
        for (DockBlockEntity dock : ALL) {
            if (dock.isRemoved() || dock.getLevel() == null) {
                continue;
            }
            if (dock.getBlockPos().asLong() == packedPos
                    && dock.getLevel().dimension().location().toString().equals(dimension)) {
                return dock;
            }
        }
        return null;
    }

    private LoadedDocks() {
    }
}
