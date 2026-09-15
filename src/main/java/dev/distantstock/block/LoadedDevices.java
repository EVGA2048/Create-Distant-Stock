package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The half of the device registry {@link LoadedDocks} does not already keep: monitors and remote
 * packagers. Docks and request desks stay where they are; nothing here duplicates or replaces them.
 *
 * <p>The remote gauge is deliberately not here. It is Create's own factory panel block entity —
 * this mod only supplies a different {@code BlockEntityType} for it — so there is no
 * {@code onLoad} of ours to hang a registration on. {@link dev.distantstock.routing.TowerActivation}
 * finds those by looking through the chunks its towers already cover, which is the only place a
 * remote gauge could possibly be carried from.
 *
 * <p>Signal panels and indicator lamps are not devices. They announce what the logistics machines
 * are doing; they are not one of them, and counting them would let a player spend a tower's budget
 * on decoration.
 */
public final class LoadedDevices {
    private static final Set<MonitorBlockEntity> MONITORS = ConcurrentHashMap.newKeySet();
    private static final Set<RemotePackagerBlockEntity> PACKAGERS = ConcurrentHashMap.newKeySet();

    public static void add(MonitorBlockEntity be) {
        MONITORS.add(be);
    }

    public static void remove(MonitorBlockEntity be) {
        MONITORS.remove(be);
    }

    public static void add(RemotePackagerBlockEntity be) {
        PACKAGERS.add(be);
    }

    public static void remove(RemotePackagerBlockEntity be) {
        PACKAGERS.remove(be);
    }

    /** Loaded monitors, server side, sorted so the activation snapshot is rebuilt identically. */
    public static List<MonitorBlockEntity> monitors() {
        List<MonitorBlockEntity> found = new ArrayList<>();
        for (MonitorBlockEntity be : MONITORS) {
            if (serverSide(be.getLevel()) && !be.isRemoved()) {
                found.add(be);
            }
        }
        found.sort(Comparator
                .comparing((MonitorBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        return List.copyOf(found);
    }

    /** Loaded remote packagers, server side, same ordering rule as {@link #monitors()}. */
    public static List<RemotePackagerBlockEntity> packagers() {
        List<RemotePackagerBlockEntity> found = new ArrayList<>();
        for (RemotePackagerBlockEntity be : PACKAGERS) {
            if (serverSide(be.getLevel()) && !be.isRemoved()) {
                found.add(be);
            }
        }
        found.sort(Comparator
                .comparing((RemotePackagerBlockEntity be) -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));
        return List.copyOf(found);
    }

    /**
     * Whether a remote gauge is still there.
     *
     * <p>The only caller is the scan that walks the chunks a tower covers, and the position it asks
     * about always lies in a chunk that is loaded — the scan reads that very chunk. The chunk
     * lookup here is the non-loading kind for the same reason the scan is: finding devices must
     * never pull a chunk into memory.
     */
    public static boolean isRemoteGauge(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity be
                && be.getType() == ModBlockEntities.REMOTE_GAUGE.get();
    }

    /** Drops a level's devices. See {@link LoadedDocks#forget}: a client level never says goodbye. */
    public static void forget(Level level) {
        MONITORS.removeIf(be -> be.getLevel() == level);
        PACKAGERS.removeIf(be -> be.getLevel() == level);
    }

    private static boolean serverSide(Level level) {
        return level != null && !level.isClientSide;
    }

    private LoadedDevices() {
    }
}
