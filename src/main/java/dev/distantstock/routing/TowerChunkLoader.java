package dev.distantstock.routing;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoadedTowers;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.block.TowerTier;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The chunks a tower keeps loaded, through NeoForge's ticket controller rather than
 * {@code setChunkForced}.
 *
 * <p>The owner of every ticket is the tower's base position, which is the one thing a validation
 * callback can turn back into a tower: the callback runs while the world is loading, long before
 * block entities exist, and a controller that cannot find what owns a ticket has no way to tell
 * "gone" from "not loaded yet".
 *
 * <p>The work is incremental. A tower that is standing, turning and unchanged costs nothing: the
 * reconciler only ever compares the chunks it wants against the chunks it already forced, and
 * forces the difference. Chunks are added and released one at a time, never as a wholesale
 * re-force — dropping a chunk and taking it again would unload and reload the machines standing
 * in it, and the one place that is guaranteed to be standing there is the tower itself.
 */
@EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TowerChunkLoader {
    private static final Logger LOG = LogManager.getLogger();

    public static final ResourceLocation CONTROLLER_ID =
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "tower_core");

    /**
     * Sixty ticks after a world starts loading, tickets whose tower cannot be found are released.
     *
     * <p>The validation callback runs before the block entities of a level exist, so at that moment
     * every owner looks missing. Deleting on the spot would drop the chunks of every tower in the
     * save — including the tower's own chunk, which is the chunk its block entity needs in order to
     * ever load again. The wait is the price of being able to tell the difference; five seconds of
     * chunks that were going to be loaded anyway is cheaper than a tower that unloads itself.
     */
    /**
     * How long a ticket found in the level at startup waits for its tower to load before it is
     * handed back: twenty seconds, not the five it was.
     *
     * <p>A large save with many towers loading at once can take longer than a hundred ticks to
     * reach every one of them. Waiting costs nothing — those chunks are already forced — while
     * being wrong costs a live tower every chunk it kept loaded, with no way back but a player
     * walking past it. {@link #expirePending} is what this bounds, not how fast the server starts.
     */
    private static final int GRACE_TICKS = 400;

    private static final int RECONCILE_TICKS = 20;

    /** How many tickets one tick may release. A broken tower should not hitch the server it left. */
    private static final int UNLOADS_PER_TICK = 32;

    private static final TicketController CONTROLLER =
            new TicketController(CONTROLLER_ID, TowerChunkLoader::validateTickets);

    /** Chunks currently forced, per tower. The reconciler's memory of what it has already done. */
    private static final Map<TowerSystem.TowerId, Tracked> OWNED = new HashMap<>();

    /** Releases waiting for a server tick: tickets are never changed from inside a callback. */
    private static final Deque<Release> QUEUE = new ArrayDeque<>();

    /** Tickets from the save whose tower could not be checked yet. See {@link #GRACE_TICKS}. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private static int ticks;

    private static final class Tracked {
        private final ServerLevel level;
        private final BlockPos owner;
        /**
         * The chunks this tower holds right now.
         *
         * <p>What was <em>wanted</em> last is deliberately not kept. The wanted set is a pure
         * function of the base and the tier, so the difference between it and this set is the whole
         * of the change; a second copy of it could only ever disagree with the first.
         */
        private final LongSet ticking = new LongOpenHashSet();

        private Tracked(ServerLevel level, BlockPos owner) {
            this.level = level;
            this.owner = owner;
        }
    }

    private record Release(ServerLevel level, BlockPos owner, LongSet chunks) {
    }

    private record Pending(ServerLevel level, BlockPos owner, LongSet ticking, int seenAt) {
    }

    @SubscribeEvent
    public static void controllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        releaseQueued();
        expirePending();
        if (ticks % RECONCILE_TICKS == 0) {
            reconcile(server);
        }
    }

    /** Drops everything: called when the server stops, where a level reference outlives its world. */
    public static void reset() {
        OWNED.clear();
        QUEUE.clear();
        PENDING.clear();
    }

    /**
     * Releases a tower's chunks because the tower is gone.
     *
     * <p>Called from the block entity when it is destroyed. A chunk unload takes the block entity
     * with it but leaves the tower standing in the world, and that case is deliberately not a
     * release: the tickets exist so the tower's chunk loads again without a player walking past it.
     */
    public static void forget(ServerLevel level, BlockPos owner) {
        LongSet chunks = new LongOpenHashSet();
        Tracked tracked = OWNED.remove(TowerSystem.TowerId.of(level.dimension(), owner));
        if (tracked != null) {
            chunks.addAll(tracked.ticking);
        }
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (pending.level() == level && pending.owner().equals(owner)) {
                // Tickets read back from the save are already in the level by the time anything
                // ticks, so they have to be released through the queue exactly like forced ones.
                chunks.addAll(pending.ticking());
                it.remove();
            }
        }
        if (!chunks.isEmpty()) {
            QUEUE.add(new Release(level, owner, chunks));
        }
    }

    /**
     * Adopts tickets that were already in the save, so the reconciler does not force them twice.
     *
     * <p>Called from the validation callback for a tower whose block entity is already loaded, and
     * again from the deferred pass once the grace period has passed.
     */
    public static void reclaim(ServerLevel level, BlockPos owner, LongSet ticking) {
        Tracked tracked = OWNED.computeIfAbsent(TowerSystem.TowerId.of(level.dimension(), owner),
                ignored -> new Tracked(level, owner));
        tracked.ticking.addAll(ticking);
    }

    /**
     * The square of chunks a tower keeps loaded, centred on its base.
     *
     * <p>The offset is {@code (side - 1) / 2}: a side of one is the tower's own chunk and nothing
     * else, a side of three is that chunk and its eight neighbours. Using the side itself as the
     * radius would load the wrong square for every tier, and would still look plausible on the two
     * sides where the two happen to agree.
     *
     * <p>Centred on the base, always. Whatever radius a tower has been given, its own chunk is the
     * middle of the square, which is what makes radius zero mean "this tower and nothing else" and
     * what keeps a tower from unloading the chunk it stands in.
     */
    public static LongSet chunksAround(BlockPos base, int side) {
        int radius = Math.max(0, (side - 1) / 2);
        int centerX = base.getX() >> 4;
        int centerZ = base.getZ() >> 4;
        LongSet chunks = new LongOpenHashSet((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(ChunkPos.asLong(centerX + dx, centerZ + dz));
            }
        }
        return chunks;
    }

    /**
     * The chunks the reconciler has already forced for a tower, as it remembers them.
     *
     * <p>Read-only, and copied: the only writer is the reconciler itself, and a caller that could
     * edit this set would be able to lie to it about what is already forced.
     */
    public static LongSet forced(Level level, BlockPos owner) {
        Tracked tracked = OWNED.get(TowerSystem.TowerId.of(level.dimension(), owner));
        return tracked == null ? new LongOpenHashSet() : new LongOpenHashSet(tracked.ticking);
    }

    private static void reconcile(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }
        // A level that is no longer served cannot hold a tower we still believe in; its tickets
        // went with the level.
        OWNED.entrySet().removeIf(entry -> !levels.contains(entry.getValue().level));

        TowerDirectory directory = TowerDirectory.get(server);
        for (TowerCoreBlockEntity be : LoadedTowers.all()) {
            if (!(be.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            TowerTier tier = be.tier();
            if (tier == null) {
                // Not a tower yet. Whatever it forced as a shorter tower is released by the same
                // path a destroyed tower takes, so a mast taken apart never leaves chunks behind.
                forget(level, be.getBlockPos());
                continue;
            }
            if (isPending(level, be.getBlockPos())) {
                // The save handed this tower tickets and the grace period has not passed; forcing
                // anything now would race the tickets that are about to be reinstated.
                continue;
            }
            TowerDirectory.Settings settings = directory.settings(
                    TowerSystem.TowerId.of(level.dimension(), be.getBlockPos()));
            if (!settings.loading()) {
                // Switched off is off for this tower's own chunk too. The tickets have to be given
                // back rather than left alone: a ticket in the level keeps forcing a chunk whether
                // or not this loop still wants it, and "the reconciler stopped looking" is exactly
                // how a switch that does nothing gets written.
                forget(level, be.getBlockPos());
                continue;
            }
            apply(level, be.getBlockPos(), chunksAround(be.getBlockPos(),
                    TowerTier.sideForRadius(settings.radiusFor(tier))));
        }
    }

    private static void apply(ServerLevel level, BlockPos owner, LongSet wanted) {
        Tracked tracked = OWNED.computeIfAbsent(TowerSystem.TowerId.of(level.dimension(), owner),
                ignored -> new Tracked(level, owner));
        for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            if (tracked.ticking.add(chunk)) {
                force(level, owner, chunk, true);
            }
        }
        for (LongIterator it = tracked.ticking.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            if (!wanted.contains(chunk)) {
                it.remove();
                force(level, owner, chunk, false);
            }
        }
    }

    /**
     * Whether this tower is holding this chunk, by the loader's own ledger.
     *
     * <p>Asked of the ledger rather than of the level because the level cannot answer: a forced chunk
     * is keyed by NeoForge's own owner object, which is package-private and has no public accessor,
     * so "which of these tickets is this tower's" is not a question the save can be asked. What the
     * ledger holds is what the loader gives back when the tower goes.
     */
    public static boolean forces(ServerLevel level, BlockPos owner, ChunkPos chunk) {
        Tracked tracked = OWNED.get(TowerSystem.TowerId.of(level.dimension(), owner));
        return tracked != null && tracked.ticking.contains(chunk.toLong());
    }

    private static void force(ServerLevel level, BlockPos owner, long chunk, boolean add) {
        CONTROLLER.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), add, true);
    }

    /**
     * Hands back the chunks of towers that are gone.
     *
     * <p>A release whose level has no server left is put back rather than dropped. Dropping it
     * would be silent and permanent: {@code forget} has already taken the tower out of the ledger
     * that tracks its chunks, so nothing would ever ask for those chunks to be released again and
     * they would stay forced for the rest of the save. They are collected and re-queued after the
     * batch so that one unloaded level cannot hold up the releases of another.
     */
    private static void releaseQueued() {
        List<Release> deferred = null;
        for (int i = 0; i < UNLOADS_PER_TICK && !QUEUE.isEmpty(); i++) {
            Release release = QUEUE.poll();
            if (release.level().getServer() == null) {
                if (deferred == null) {
                    deferred = new ArrayList<>();
                }
                deferred.add(release);
                continue;
            }
            for (LongIterator it = release.chunks().iterator(); it.hasNext(); ) {
                force(release.level(), release.owner(), it.nextLong(), false);
            }
        }
        if (deferred != null) {
            QUEUE.addAll(deferred);
        }
    }

    private static void expirePending() {
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (ticks - pending.seenAt() < GRACE_TICKS) {
                continue;
            }
            it.remove();
            if (LoadedTowers.at(pending.level(), pending.owner()) != null) {
                // The tower was simply not loaded when the save was read. Its tickets are already
                // in the level again, so they are adopted rather than forced a second time.
                reclaim(pending.level(), pending.owner(), pending.ticking());
                continue;
            }
            LOG.info("[DistantStock/Tower] releasing {} chunks of a tower that is no longer at {} in {}",
                    pending.ticking().size(), pending.owner(), pending.level().dimension().location());
            QUEUE.add(new Release(pending.level(), pending.owner(), pending.ticking()));
        }
    }

    private static boolean isPending(ServerLevel level, BlockPos owner) {
        for (Pending pending : PENDING) {
            if (pending.level() == level && pending.owner().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one chance to clean up tickets left by a world that has since changed.
     *
     * <p>A tower's own tickets are ticking ones, so anything non-ticking carrying our controller id
     * is stale by construction and is dropped here. Ticking tickets are kept for a tower that is
     * still standing, and bookkeeping for the rest until the grace period says otherwise — see
     * {@link #GRACE_TICKS}.
     */
    private static void validateTickets(ServerLevel level, TicketHelper helper) {
        for (Map.Entry<BlockPos, TicketSet> entry : helper.getBlockTickets().entrySet()) {
            BlockPos owner = entry.getKey();
            TicketSet tickets = entry.getValue();
            for (long chunk : tickets.nonTicking()) {
                helper.removeTicket(owner, chunk, false);
            }
            if (tickets.ticking().isEmpty()) {
                continue;
            }
            if (ownsTower(level, owner)) {
                reclaim(level, owner, new LongOpenHashSet(tickets.ticking()));
                continue;
            }
            PENDING.add(new Pending(level, owner, new LongOpenHashSet(tickets.ticking()), ticks));
        }
    }

    /**
     * Whether a tower base is standing at this position right now.
     *
     * <p>Never loads a chunk to find out. During validation the level is half built, and asking for
     * a chunk that is not in memory would pull it in from underneath the loader.
     */
    private static boolean ownsTower(ServerLevel level, BlockPos owner) {
        return level.hasChunkAt(owner) && level.getBlockEntity(owner) instanceof TowerCoreBlockEntity;
    }

    private TowerChunkLoader() {
    }
}
