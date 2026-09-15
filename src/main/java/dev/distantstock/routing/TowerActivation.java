package dev.distantstock.routing;

import dev.distantstock.block.LoadedDevices;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.block.LoadedTowers;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.block.TowerTier;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which distant devices a tower carries right now, and whether the mechanic is in force at all.
 *
 * <p>Docks ask this every tick, so the answer cannot be a search: it is a snapshot, rebuilt on the
 * server's beat and read by position. Rebuilding is bounded by the number of towers and devices —
 * both small — and the gates that read it are two hash lookups.
 *
 * <p>The snapshot is rebuilt once a second, when the tower set or a device registry changed, or
 * when {@link TowerCoreBlockEntity} reports that a mast has grown or shrunk. Between rebuilds a
 * dock reads a picture at most one second old, which is the same beat the towers rescan on, so a
 * tower can never carry a device under a state the tower itself has already forgotten.
 *
 * <p><b>The mechanic is only in force in a dimension that contains a running tower.</b> A world
 * with no towers at all behaves exactly as it did before towers existed: every dock keeps sending
 * and receiving, and nothing is charged. That is the promise this whole stage is built around —
 * an existing save must not stall because the mod grew a new machine.
 *
 * <p><b>A running tower claims a dimension, an idle one does not.</b> Once a tower stands and turns
 * in a dimension, devices there are on when a system reaches them and off when none does. A tower
 * that is built but not turning carries nothing and claims nothing, so a broken shaft degrades to
 * "the tower is dark" instead of "every dock in the dimension is dark and there is no way to see
 * why". This is also what keeps the two halves of a test world apart: a tower built by one test
 * does not switch off the docks of another.
 */
public final class TowerActivation {
    /** The snapshot's beat, the same twenty ticks the towers and the docks rescan on. */
    private static final int RECONCILE_TICKS = 20;

    /**
     * What a system carries and what it could carry, for the readout on a tower's goggles.
     *
     * @param limit   the sum of the members' device counts
     * @param carried how many devices stand in reach and won a place in that budget
     */
    public record Usage(int limit, int carried) {
    }

    /**
     * Parcels that crossed one tower in the last ten minutes, counted at the docks it carries.
     *
     * <p>A rolling window, not a lifetime total: what an operator wants to know is whether the line
     * is moving now, and a number that only ever grows cannot answer that. The buckets live on the
     * docks — see {@code DockBlockEntity} — because a dock is the only place that sees both a parcel
     * leaving and a parcel arriving.
     */
    public record Traffic(long sent, long received) {
        public static final Traffic NONE = new Traffic(0, 0);

        public Traffic plus(Traffic other) {
            return new Traffic(sent + other.sent, received + other.received);
        }
    }

    /** A frozen answer: the systems, the dimensions they claim, and the devices they carry. */
    public static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        private final List<TowerSystem.System> systems;
        private final Map<ResourceKey<Level>, LongSet> activated;
        private final Map<ResourceKey<Level>, Long2ObjectMap<TowerSystem.TowerId>> carriers;
        private final Map<TowerSystem.TowerId, Usage> usage;
        private final Map<TowerSystem.TowerId, Integer> carriedBy;
        private final Map<TowerSystem.TowerId, Traffic> traffic;

        Snapshot(List<TowerSystem.System> systems, Map<ResourceKey<Level>, LongSet> activated,
                 Map<ResourceKey<Level>, Long2ObjectMap<TowerSystem.TowerId>> carriers,
                 Map<TowerSystem.TowerId, Usage> usage, Map<TowerSystem.TowerId, Integer> carriedBy,
                 Map<TowerSystem.TowerId, Traffic> traffic) {
            this.systems = systems;
            this.activated = activated;
            this.carriers = carriers;
            this.usage = usage;
            this.carriedBy = carriedBy;
            this.traffic = traffic;
        }

        public List<TowerSystem.System> systems() {
            return systems;
        }

        /**
         * Whether the given dimension is under a tower system.
         *
         * <p>A dimension that is not in here behaves as if towers had never been added, which is
         * why the maps are keyed by dimension rather than kept in one global set: the players of a
         * quiet overworld should not be affected by a tower someone built in the nether.
         */
        public boolean gated(ResourceKey<Level> dimension) {
            return activated.containsKey(dimension);
        }

        /** How much of its budget one member's system is using, or null for a tower in no system. */
        public Usage usage(TowerSystem.TowerId tower) {
            return usage.get(tower);
        }

        /** How many devices this one tower carries, which is what its row on a monitor shows. */
        public int carriedBy(TowerSystem.TowerId tower) {
            Integer count = carriedBy.get(tower);
            return count == null ? 0 : count;
        }

        /** What crossed this tower's docks in the last ten minutes, or zero for a tower in none. */
        public Traffic traffic(TowerSystem.TowerId tower) {
            Traffic found = traffic.get(tower);
            return found == null ? Traffic.NONE : found;
        }

        /**
         * Whether a device at this position is switched on.
         *
         * <p>Only meaningful for gated dimensions; callers that asked {@link #gated} already know
         * the answer for the rest, and a device in a dimension with no running tower is on.
         */
        public boolean active(ResourceKey<Level> dimension, BlockPos pos) {
            LongSet on = activated.get(dimension);
            return on == null || on.contains(pos.asLong());
        }

        /** The tower that carries a device, or null when nothing does. */
        public TowerSystem.TowerId carrier(ResourceKey<Level> dimension, BlockPos pos) {
            Long2ObjectMap<TowerSystem.TowerId> found = carriers.get(dimension);
            return found == null ? null : found.get(pos.asLong());
        }

        /**
         * The towers that share a system with this one, or an empty list for a tower in none.
         *
         * <p>What a monitor shows its operator: the merged machine, not just the tower the device
         * happens to stand closest to in it. The list is the snapshot's own, so a caller can read it
         * without searching and without touching the world.
         */
        public List<TowerSystem.Member> systemMembers(TowerSystem.TowerId tower) {
            for (TowerSystem.System system : systems) {
                for (TowerSystem.Member member : system.members()) {
                    if (member.id().equals(tower)) {
                        return system.members();
                    }
                }
            }
            return List.of();
        }
    }

    private static volatile Snapshot current = Snapshot.EMPTY;
    private static boolean dirty = true;
    private static int ticks;

    public static void tick(MinecraftServer server) {
        ticks++;
        if (!dirty && ticks % RECONCILE_TICKS != 0) {
            return;
        }
        dirty = false;
        current = survey(server);
    }

    /** Forces the next tick to rebuild, for the changes a rebuild cannot see coming. */
    public static void markDirty() {
        dirty = true;
    }

    /**
     * Whether this device is switched on.
     *
     * <p>The one question every gate asks. Cheap on purpose: two map lookups, no allocation, and an
     * immediate yes for the common case of a world that has no towers in it.
     */
    public static boolean active(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            // The client half of a single-player save builds the same block entities as the server
            // and must not decide anything on its own; the server owns this answer.
            return true;
        }
        if (pinnedAny) {
            Pinned pinned = PINNED.get(TowerSystem.TowerId.of(level.dimension(), pos));
            if (pinned != null) {
                return pinned.active();
            }
        }
        return current.active(level.dimension(), pos);
    }

    /** The tower that pays for this device's transfers, or null when nothing carries it. */
    public static TowerSystem.TowerId carrier(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return null;
        }
        if (pinnedAny) {
            Pinned pinned = PINNED.get(TowerSystem.TowerId.of(level.dimension(), pos));
            if (pinned != null) {
                return pinned.carrier();
            }
        }
        return current.carrier(level.dimension(), pos);
    }

    /** What the system this tower belongs to carries, or null when it is in no system. */
    public static Usage usage(TowerSystem.TowerId tower) {
        return current.usage(tower);
    }

    /** The snapshot as it stands, for the readouts. */
    public static Snapshot snapshot() {
        return current;
    }

    /**
     * Test seam: one device's answer, replacing whatever the snapshot says about it.
     *
     * <p>Per device rather than per world, because the game test runner shares one level between
     * cases running in parallel. A whole-world override would switch every other case's docks off
     * while it was in place; this one reaches exactly the block under test, and a case that does
     * not pin anything sees the real world.
     */
    private record Pinned(boolean active, TowerSystem.TowerId carrier) {
    }

    private static final Map<TowerSystem.TowerId, Pinned> PINNED = new ConcurrentHashMap<>();
    private static volatile boolean pinnedAny;

    /** Forces what a single device is told. See {@link #Pinned}. */
    public static void pinDevice(TowerSystem.TowerId device, boolean active, TowerSystem.TowerId carrier) {
        PINNED.put(device, new Pinned(active, carrier));
        pinnedAny = true;
    }

    /** Drops every pinned answer. */
    public static void unpinDevices() {
        PINNED.clear();
        pinnedAny = false;
    }

    /**
     * The pure half of a rebuild: towers and devices in, coverage and budget out.
     *
     * <p>Kept free of the world so the rules can be checked directly. {@link #survey} is the thin
     * world half, and it does no deciding of its own beyond which entities are loaded.
     */
    public static Snapshot of(List<TowerSystem.Member> towers, List<TowerSystem.Device> devices) {
        return of(towers, devices, Map.of());
    }

    /**
     * The same, with the parcels the docks counted this minute.
     *
     * <p>The counts arrive already taken, keyed by device, because the ticking is the docks' — this
     * half only has to add each device's window to the tower that carries it, which is the same
     * decision it has just made for every other purpose.
     *
     * @param traffic per device, the parcels that crossed it in the last ten minutes
     */
    public static Snapshot of(List<TowerSystem.Member> towers, List<TowerSystem.Device> devices,
                             Map<TowerSystem.Device, Traffic> traffic) {
        List<TowerSystem.System> systems = TowerSystem.merge(towers);
        if (systems.isEmpty()) {
            return Snapshot.EMPTY;
        }
        List<TowerSystem.Carried> carried = TowerSystem.carry(systems, devices);

        Map<ResourceKey<Level>, LongSet> activated = new HashMap<>();
        Map<ResourceKey<Level>, Long2ObjectMap<TowerSystem.TowerId>> carriers = new HashMap<>();
        for (TowerSystem.System system : systems) {
            for (TowerSystem.Member member : system.members()) {
                // An empty set, not a missing entry: a claimed dimension with nothing carried in
                // it has to read as "everything here is off", not as "towers do not apply here".
                ResourceKey<Level> dimension = dimensionKey(member.id().dimension());
                activated.computeIfAbsent(dimension, ignored -> new LongOpenHashSet());
                carriers.computeIfAbsent(dimension, ignored -> new Long2ObjectOpenHashMap<>());
            }
        }
        for (TowerSystem.Carried entry : carried) {
            ResourceKey<Level> dimension = dimensionKey(entry.carrier().dimension());
            activated.computeIfAbsent(dimension, ignored -> new LongOpenHashSet())
                    .add(entry.device().pos().asLong());
            carriers.computeIfAbsent(dimension, ignored -> new Long2ObjectOpenHashMap<>())
                    .put(entry.device().pos().asLong(), entry.carrier());
        }

        Map<TowerSystem.TowerId, Usage> usage = new HashMap<>();
        Map<TowerSystem.TowerId, Integer> carriedBy = new HashMap<>();
        Map<TowerSystem.TowerId, Traffic> trafficByTower = new HashMap<>();
        for (TowerSystem.Carried entry : carried) {
            carriedBy.merge(entry.carrier(), 1, Integer::sum);
            Traffic window = traffic.get(entry.device());
            if (window != null) {
                trafficByTower.merge(entry.carrier(), window, Traffic::plus);
            }
        }
        for (TowerSystem.System system : systems) {
            Set<TowerSystem.TowerId> members = Set.copyOf(system.ids());
            int carriedHere = 0;
            for (TowerSystem.Carried entry : carried) {
                if (members.contains(entry.carrier())) {
                    carriedHere++;
                }
            }
            for (TowerSystem.Member member : system.members()) {
                usage.put(member.id(), new Usage(system.devices(), carriedHere));
            }
        }
        return new Snapshot(List.copyOf(systems), Map.copyOf(activated), Map.copyOf(carriers),
                Map.copyOf(usage), Map.copyOf(carriedBy), Map.copyOf(trafficByTower));
    }

    /**
     * The world half: reads every loaded tower and every loaded device, in place.
     *
     * <p>Devices are only collected in dimensions a running tower stands in. That is not just an
     * optimisation: it is the rule. A dimension with no running tower has no coverage to compute.
     */
    public static Snapshot survey(MinecraftServer server) {
        if (server == null) {
            return Snapshot.EMPTY;
        }
        TowerDirectory directory = TowerDirectory.get(server);
        List<TowerSystem.Member> towers = new ArrayList<>();
        for (TowerCoreBlockEntity be : LoadedTowers.all()) {
            TowerTier tier = be.tier();
            if (tier == null || be.getLevel() == null) {
                continue;
            }
            TowerSystem.TowerId id = TowerSystem.TowerId.of(be.getLevel().dimension(), be.getBlockPos());
            towers.add(new TowerSystem.Member(id, be.getBlockPos(), tier.radius(), tier.devices(),
                    be.isRunning(), directory.settings(id).carrying()));
        }
        Set<String> claimed = new HashSet<>();
        for (TowerSystem.Member member : towers) {
            if (member.running()) {
                claimed.add(member.id().dimension());
            }
        }
        if (claimed.isEmpty()) {
            // No tower is turning anywhere: nothing to carry, and nothing to read. The gates answer
            // "on" for every device in the world, at the cost of this one check a second.
            return Snapshot.EMPTY;
        }

        List<TowerSystem.Device> devices = new ArrayList<>();
        Map<String, LongSet> seen = new HashMap<>();
        Map<TowerSystem.Device, Traffic> traffic = new HashMap<>();
        for (var be : LoadedDocks.allDocks()) {
            TowerSystem.Device device = addDevice(devices, seen, claimed, be.getLevel(), be.getBlockPos());
            if (device != null) {
                traffic.put(device, be.traffic());
            }
        }
        for (var be : LoadedDocks.allGauges()) {
            addDevice(devices, seen, claimed, be.getLevel(), be.getBlockPos());
        }
        for (var be : LoadedDevices.monitors()) {
            addDevice(devices, seen, claimed, be.getLevel(), be.getBlockPos());
        }
        for (var be : LoadedDevices.packagers()) {
            addDevice(devices, seen, claimed, be.getLevel(), be.getBlockPos());
        }
        for (TowerSystem.Member member : towers) {
            if (!member.running()) {
                continue;
            }
            ServerLevel level = levelOf(server, member.id().dimension());
            if (level == null) {
                // Cross-dimension bookkeeping: the dimension a saved tower names may not exist in
                // this server at all, and a level that is gone is not an error worth failing over.
                continue;
            }
            scanRemoteGauges(level, member, devices, seen, claimed);
        }
        return of(towers, devices);
    }

    /**
     * Finds the remote gauges a tower could reach.
     *
     * <p>There is no registry to walk: the remote gauge is Create's factory panel block entity with
     * one of our block entity types on it, so there is no {@code onLoad} of ours to register from.
     * The chunks a tower reaches are the only places a gauge could be carried from, and they are
     * looked up without loading anything — a chunk that is not in memory is skipped, never pulled
     * in. A gauge that is broken while its chunk stays loaded simply stops being found by the next
     * scan, so this needs no invalidation of its own.
     */
    private static void scanRemoteGauges(ServerLevel level, TowerSystem.Member member,
                                         List<TowerSystem.Device> devices, Map<String, LongSet> seen,
                                         Set<String> claimed) {
        BlockPos base = member.base();
        int minChunkX = (base.getX() - member.radius()) >> 4;
        int maxChunkX = (base.getX() + member.radius()) >> 4;
        int minChunkZ = (base.getZ() - member.radius()) >> 4;
        int maxChunkZ = (base.getZ() + member.radius()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    if (LoadedDevices.isRemoteGauge(level, pos)) {
                        addDevice(devices, seen, claimed, level, pos);
                    }
                }
            }
        }
    }

    /** Collects one device, and answers the one it made so a caller can hang a reading on it. */
    private static TowerSystem.Device addDevice(List<TowerSystem.Device> devices, Map<String, LongSet> seen,
                                                Set<String> claimed, Level level, BlockPos pos) {
        if (level == null) {
            return null;
        }
        String dimension = level.dimension().location().toString();
        if (!claimed.contains(dimension)) {
            return null;
        }
        if (!seen.computeIfAbsent(dimension, ignored -> new LongOpenHashSet()).add(pos.asLong())) {
            return null;
        }
        TowerSystem.Device device = new TowerSystem.Device(pos, dimension);
        devices.add(device);
        return device;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        return server.getLevel(dimensionKey(dimension));
    }

    private static ResourceKey<Level> dimensionKey(String dimension) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
    }

    private TowerActivation() {
    }
}
