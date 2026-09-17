package dev.distantstock.block;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DockSelection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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
            if (deliverable(be) && be.freq() != null) {
                out.add(be.freq());
            }
        }
        for (GaugeBlockEntity be : GAUGES) {
            if (deliverable(be) && be.freq() != null) {
                out.add(be.freq());
            }
        }
        return out;
    }

    /**
     * Whether this is a dock the server may hand a parcel to.
     *
     * <p>One definition, used by every path that picks a dock, because two of them had their own:
     * the listing methods filtered the client half out and the delivery ones did not, and the
     * client half is not a copy that can be ignored — it is a second {@code DockBlockEntity} for
     * the same position, in the same JVM, in the same static set. A single-player client builds one
     * for every loaded dock, it answers yes to every question the filter asks, and a parcel handed
     * to it is written into client memory that the next server update overwrites. That is a parcel
     * destroyed by being delivered, which is the one outcome this whole file exists to prevent.
     */
    private static boolean deliverable(DockBlockEntity be) {
        return be != null && !be.isRemoved() && be.getLevel() != null && !be.getLevel().isClientSide;
    }

    /** The same question for a request desk, which is read by the stock scanner. */
    private static boolean deliverable(GaugeBlockEntity be) {
        return be != null && !be.isRemoved() && be.getLevel() != null && !be.getLevel().isClientSide;
    }

    /**
     * Drops everything this level held, for the half of a session the game does not tell us about.
     *
     * <p>A server level unloads its block entities and every one of them deregisters itself. A
     * client level does not: leaving a world sets the level aside without removing a single block
     * entity, so its copies would stay in these sets holding the level alive, and the next world
     * loaded in the same client would find them still answering yes.
     */
    public static void forget(Level level) {
        ALL.removeIf(be -> be.getLevel() == level);
        GAUGES.removeIf(be -> be.getLevel() == level);
    }

    public static DockBlockEntity importFor(ItemStack pkg) {
        return importFor(pkg, DockGroupDirectory.DEFAULT_GROUP_ID);
    }

    /**
     * Selects an available dock in the requested group: the highest priority tier that has room wins, and
     * docks of equal priority take turns in a stable order.
     *
     * <p><b>The group is the only thing that picks a dock.</b> A dock used to filter on an address of
     * its own as well, and that made the group mean less than it says: two docks in one group, given
     * different addresses, silently stopped being interchangeable — each took only the half of the
     * traffic its own address matched, and which half depended on a field nothing on the dock showed.
     * A group whose docks are not interchangeable with each other is a longer way of naming one dock.
     *
     * <p>The address still decides where a parcel goes, just not here: it is what the local logistics
     * network sorts by <em>after</em> the parcel has left the dock. The one it wears by then is the
     * home address the crossing swapped in, and a frogport or a chute reading it is what splits a
     * delivery between two corners of one server.
     */
    public static DockBlockEntity importFor(ItemStack pkg, UUID groupId) {
        List<DockBlockEntity> matching = new ArrayList<>();
        for (DockBlockEntity be : ALL) {
            if (!deliverable(be) || !be.canReceive() || !be.groupId().equals(groupId)) {
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

    /** Tells a group's docks that a parcel for them could not be placed, so they can report it. */
    public static void noMatch(ItemStack pkg, UUID groupId) {
        for (DockBlockEntity be : ALL) {
            if (!deliverable(be) || !be.canReceive() || !be.groupId().equals(groupId)) {
                continue;
            }
            be.rejected();
        }
    }

    /**
     * 按组枚举已加载的港，顺序稳定（维度 + 坐标），好让 /distantstock group list 的同一份数据每次打印一致。
     *
     * <p>Only server-side docks count. In a single-player save the client half of the same JVM also
     * builds a DockBlockEntity for every loaded dock, and those copies are not the ones a parcel can
     * be delivered to, so counting them would double every number an operator reads.
     */
    /**
     * How many loaded docks are in this group, without building the list.
     *
     * <p>For the readouts that only want the number: the goggle line, and the value it is synced
     * from, which is re-checked on a tick. {@link #allInGroup} collects and sorts, which is right
     * for the screens that draw the docks in a stable order and wrong for a count.
     */
    public static int countInGroup(UUID groupId) {
        int count = 0;
        for (DockBlockEntity be : ALL) {
            if (be.isRemoved() || be.getLevel() == null || be.getLevel().isClientSide) {
                continue;
            }
            if (be.groupId().equals(groupId)) {
                count++;
            }
        }
        return count;
    }

    public static List<DockBlockEntity> allInGroup(UUID groupId) {
        List<DockBlockEntity> matching = new ArrayList<>();
        for (DockBlockEntity be : ALL) {
            if (be.isRemoved() || be.getLevel() == null || be.getLevel().isClientSide) {
                continue;
            }
            if (be.groupId().equals(groupId)) {
                matching.add(be);
            }
        }
        matching.sort(Comparator
                .comparing((DockBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        return List.copyOf(matching);
    }

    /**
     * Every loaded dock, server side, sorted by dimension and position.
     *
     * <p>Added for the tower system, which has to look at all of them at once to decide which ones
     * a tower carries. The selection helpers above deliberately never do that: a delivery only ever
     * considers one group.
     */
    public static List<DockBlockEntity> allDocks() {
        List<DockBlockEntity> matching = new ArrayList<>();
        for (DockBlockEntity be : ALL) {
            if (!be.isRemoved() && be.getLevel() != null && !be.getLevel().isClientSide) {
                matching.add(be);
            }
        }
        matching.sort(Comparator
                .comparing((DockBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        return List.copyOf(matching);
    }

    /** Every loaded request desk, server side, sorted the same way as {@link #allDocks()}. */
    public static List<GaugeBlockEntity> allGauges() {
        List<GaugeBlockEntity> matching = new ArrayList<>();
        for (GaugeBlockEntity be : GAUGES) {
            if (!be.isRemoved() && be.getLevel() != null && !be.getLevel().isClientSide) {
                matching.add(be);
            }
        }
        matching.sort(Comparator
                .comparing((GaugeBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        return List.copyOf(matching);
    }

    public static DockBlockEntity at(String dimension, long packedPos) {
        for (DockBlockEntity dock : ALL) {
            if (!deliverable(dock)) {
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
