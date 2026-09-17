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
 * <p><b>没塔就不能用。</b>远仓设备必须站在一座**正在运行**的塔的范围里才工作：不在任何塔范围内
 * 的港不收也不发，打包机不打包，仪表不下单。这是用户 2026-09-17 拍板的规则，也是这个模组"要先把
 * 基础设施搭起来"那句话的字面意思。
 *
 * <p>原来的写法是反的：一个维度里没有在转的塔时，所有设备照常工作、也不扣费（当时那条注释叫
 * "老存档不该因为模组长出一台新机器就停摆"）。那条承诺被推翻了 —— 它真正的后果是"邻居建了座塔，
 * 我全厂停产"，而且新玩家永远学不会塔是必需品。测试里用 {@code pinDevice} 显式说"这台设备是被
 * 带着的"，因为测试世界本来就不该为每个用例搭一座真塔。
 *
 * <p><b>一座正在转的塔才算数，建好不转的不算。</b>传动轴断了就是"塔是暗的"，而不是"整个维度全黑"，
 * 玩家一眼能看出该去修哪。
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
         * 这台设备是不是被某座在转的塔带着。
         *
         * <p>**没塔就是不在**：维度不在表里（这个维度没有任何在转的塔）、或者位置不在集合里
         * （塔够不着、或者带载名额用完了），答案都是"不工作"。以前这里是反的（{@code on == null}
         * 当作"全都算"），那是"老存档不该停摆"那条已废弃的承诺留下的。
         */
        public boolean active(ResourceKey<Level> dimension, BlockPos pos) {
            LongSet on = activated.get(dimension);
            return on != null && on.contains(pos.asLong());
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

    /**
     * The tower standing over this position, or null when none reaches it.
     *
     * <p>Every other question here is asked of the snapshot, which is built out of towers that are
     * <em>running</em> and answers with the devices they <em>carry</em>. This one is neither, on
     * purpose. "Which tower am I under" does not stop being true because a shaft came off or a
     * switch was flipped, and the two devices that ask it — a monitor and its tower page — are
     * exactly where those two facts are shown and where the switches that cause them live. Answer
     * it from the snapshot and the page that says "not turning" vanishes the moment the tower
     * stops turning, and the switch that was switched off takes its own page with it.
     *
     * <p>Read from the loaded tower blocks instead: a built tower has a base and a radius whether
     * or not anything is turning it. Deliberately not run through the pinned seam either — a pin
     * overrides what a device is told about itself, and this is a fact about the world.
     */
    public static TowerSystem.TowerId towerAt(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        TowerSystem.TowerId nearest = null;
        long bestDistance = Long.MAX_VALUE;
        for (TowerCoreBlockEntity tower : LoadedTowers.all()) {
            TowerTier tier = tower.tier();
            if (tier == null || tower.getLevel() == null
                    || !tower.getLevel().dimension().equals(dimension)) {
                continue;
            }
            BlockPos base = tower.getBlockPos();
            long distance = distanceSq(base, pos);
            if (distance <= (long) tier.radius() * tier.radius() && distance < bestDistance) {
                nearest = TowerSystem.TowerId.of(dimension, base);
                bestDistance = distance;
            }
        }
        return nearest;
    }

    /** Squared distance, in the same integer arithmetic the merge uses to decide a reach. */
    private static long distanceSq(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
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

    /**
     * Drops one pinned answer.
     *
     * <p>用例收尾要用这一个，不要用{@link #unpinDevices()} —— 那张表是全局的，而 game test 是
     * **并行跑在同一个 JVM 里**的：一个用例把整张表清空，等于把同时在跑的另一批用例的 pin 一起擦了，
     * 表现是"失败的那几条每次都不一样"。这件事在塔的硬门槛之后才暴露出来，因为从那以后每个碰设备的
     * 用例都得先 pin 一下。
     */
    public static void unpinDevice(TowerSystem.TowerId device) {
        PINNED.remove(device);
        pinnedAny = !PINNED.isEmpty();
    }

    /** Drops every pinned answer. For the world going away, not for a case finishing. */
    public static void unpinDevices() {
        PINNED.clear();
        pinnedAny = false;
    }

    /**
     * The pure half of a rebuild: towers and devices in, coverage and budget out.
     *
     * <p>Kept free of the world so the rules can be checked directly. {@link #survey} is the thin
     * world half, and it does no deciding of its own beyond which entities are loaded.
     *
     * <p>The counts arrive already taken, keyed by device, because the ticking is the docks' — this
     * half only has to add each device's window to the tower that carries it, which is the same
     * decision it has just made for every other purpose.
     *
     * <p>The map is not optional, and there is deliberately no overload that leaves it out. There
     * was one, and {@code survey} took it: it collected every dock's window and then called the
     * two-argument form, so the readout asked for a tower's traffic, was handed an empty map, and
     * printed zero for as long as the tower stood. Nothing failed — a flow that has never moved and
     * a flow nobody wired up look exactly alike. A caller with nothing to report passes
     * {@code Map.of()} and says so in its own line.
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
        // 没有塔在转 = 没有设备被带着。这里**不能**提前返回"一切照旧"（原来是那样）：那正是
        // 被推翻的那条老承诺，也是"没塔免费用"的来源。继续往下走，claimed 为空就意味着
        // addDevice 一台设备都不收，快照里一台设备也没有 —— 结果一样，但道理是对的。

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
        return of(towers, devices, traffic);
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
