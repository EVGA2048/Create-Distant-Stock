package dev.distantstock.routing;

import dev.distantstock.block.LoadedTowers;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.block.TowerTier;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a monitor knows about the tower that carries it, in one value.
 *
 * <p>The whole record travels as one packet component rather than as a dozen loose fields. The wire
 * codec is positional: one field written on one side and not read on the other shifts every value
 * after it, and the failure is a screen full of plausible numbers rather than an error. Keeping the
 * additions behind a single type means the codec has one place to get right.
 *
 * <p><b>Not attached is a state, not a zero.</b> A monitor in a world with no towers — the state
 * every existing save is in — has no tier, no members and no carried count, and the readout says so
 * with {@code attached == false}. It never reports zero carried out of a budget of zero, because a
 * screen cannot tell that apart from a working tower carrying nothing.
 *
 * <p>The members come from {@link TowerActivation}'s snapshot, which is already the answer to "which
 * towers are one system and what do they carry"; only the tier and the rotation are read from the
 * block entities, because those are not part of that snapshot, and the switches, the radius and the
 * tank come from {@link TowerDirectory}, which is the only place that holds them. Nothing here is
 * computed per frame or per tick: a readout is built once a second, on the same beat the monitor's
 * other numbers move.
 *
 * <p>{@code stress} and {@code speed} describe the system, not one tower: the merged system's draw
 * is the sum of its members' impacts, and it turns no faster than its slowest member — a system with
 * one stalled tower is a stalled system, which is what {@code speed == 0} says.
 */
public record TowerReadout(
        boolean attached,
        long carrierPos,
        String dimension,
        int carried,
        int limit,
        float stress,
        float speed,
        int maxSide,
        int selectedSide,
        List<Member> members
) {
    /** The readout of a monitor no tower carries. Shared, because it is immutable and common. */
    public static final TowerReadout NONE = new TowerReadout(false, 0, "", 0, 0, 0, 0, 0, 0, List.of());

    public TowerReadout {
        members = List.copyOf(members);
    }

    /**
     * One member tower as the readout shows it. {@code tier} is empty for a tower that went away.
     *
     * <p>The last six fields are per tower rather than per system, which is why they are read here
     * instead of being summed at the top: the ether in a tower's tank belongs to that base, the
     * capacity is whatever shaft happens to turn it, and the two switches and the chunk radius are
     * exactly the dials the monitor's screen edits — one tower at a time.
     *
     * @param ether      millibuckets in this tower's tank; zero when the block entity is gone
     * @param capacity   what this tower's own rotation network can supply, in stress units; zero
     *                   when it has no network under it
     * @param overstressed whether that network is over its capacity. A stalled tower and an
     *                   overstressed one both report a speed of zero, and only this tells them
     *                   apart — see {@link #speed}
     * @param chunkRadius the radius the loader actually uses for this tower: the stored setting
     *                   clamped to the tier, so a tower that lost its mast reads as what it loads
     *                   today rather than as what it once asked for
     * @param loading    this tower's chunk-loading switch, as the file holds it
     * @param carrying   this tower's device switch, as the file holds it
     */
    public record Member(long pos, String tier, int radius, int devices, boolean running, float speed,
                         int ether, float capacity, boolean overstressed,
                         int chunkRadius, boolean loading, boolean carrying,
                         int sent, int received) {
    }

    /**
     * One loaded tower, as the world half found it: where it stands, how tall it is, how fast it
     * turns. A tower whose block entity is gone keeps its place in the list with a null tier —
     * dropping it would silently shrink a system the snapshot still believes in.
     *
     * <p>The settings travel in the fact rather than being looked up later because they live in a
     * file, not in the world: an unloaded tower still has a radius the operator chose, and a
     * readout that could not see it would show a tower's dials as untouched while they are set.
     */
    public record Fact(BlockPos base, TowerTier tier, boolean running, float speed,
                       int ether, float capacity, boolean overstressed,
                       TowerDirectory.Settings settings, TowerActivation.Traffic traffic) {
    }

    /**
     * The pure half: snapshot members and loaded-tower facts in, one readout out.
     *
     * <p>Free of the world so the shape of the answer — who is a member, what the ceiling is, what
     * the merged budget reads — can be checked without building towers that turn.
     *
     * @param usage what the system carries, from the snapshot; null for a tower in no system, whose
     *              own tier is then the whole budget
     */
    public static TowerReadout describe(TowerSystem.TowerId carrier, List<Fact> facts,
                                        TowerActivation.Usage usage, int selectedSide) {
        if (facts.isEmpty()) {
            return NONE;
        }
        List<Member> members = new ArrayList<>(facts.size());
        float stress = 0;
        float speed = Float.MAX_VALUE;
        int devices = 0;
        int maxSide = 0;
        for (Fact fact : facts) {
            TowerTier tier = fact.tier();
            TowerActivation.Traffic traffic = fact.traffic();
            members.add(new Member(fact.base().asLong(), tier == null ? "" : tier.name(),
                    tier == null ? 0 : tier.radius(), tier == null ? 0 : tier.devices(),
                    fact.running(), fact.speed(),
                    fact.ether(), fact.capacity(), fact.overstressed(),
                    // Clamped to the tier on the way out, by the same call the loader makes. The
                    // screen edits the number it is shown, so the number it is shown has to be the
                    // one in force; a stored three over a tier that pays for one would otherwise
                    // come back as a three the buttons could not step down from.
                    fact.settings().radiusFor(tier),
                    fact.settings().loading(), fact.settings().carrying(),
                    count(traffic.sent()), count(traffic.received())));
            if (tier != null) {
                stress += tier.stress();
                devices += tier.devices();
                // The ceiling is the tallest member's, not the carrier's: a monitor standing in the
                // reach of the system's little tower is still served by the machine the tall one
                // makes, which is the whole point of merging them.
                maxSide = Math.max(maxSide, tier.chunkSide());
            }
            speed = Math.min(speed, fact.speed());
        }
        return new TowerReadout(true, carrier.packedPos(), carrier.dimension(),
                usage == null ? 0 : usage.carried(),
                usage == null ? devices : usage.limit(),
                stress, speed, maxSide, selectedSide, members);
    }

    /**
     * The world half: reads the snapshot, the loaded towers and the selection file for one monitor.
     *
     * <p>Called once a second per loaded monitor, so it is allowed to walk the loaded tower list —
     * single digits by design — but not to load a chunk: a tower that is not in memory simply has no
     * fact, and its member row reads as an unknown tier instead of pulling the chunk in.
     */
    public static TowerReadout survey(Level level, BlockPos monitor) {
        if (level == null || level.isClientSide || monitor == null) {
            return NONE;
        }
        TowerSystem.TowerId carrier = TowerActivation.carrier(level, monitor);
        if (carrier == null) {
            // Not carried is not the same as standing under no tower, and a monitor is the device
            // where the difference decides whether its own controls exist. A tower that is stopped,
            // or one whose device switch is off, carries nothing at all — this monitor included,
            // and that switch is drawn on the very page this call feeds. Asking only "who carries
            // me" would answer nothing and drop the page, taking with it both the switch that
            // turned it off and the line that would have said why. Fall back to the tower standing
            // over the monitor; the numbers below still report the truth, which is that nothing is
            // carried.
            carrier = TowerActivation.towerAt(level, monitor);
            if (carrier == null) {
                return NONE;
            }
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return NONE;
        }
        TowerDirectory directory = TowerDirectory.get(server);
        List<TowerSystem.Member> members = TowerActivation.snapshot().systemMembers(carrier);
        Map<TowerSystem.TowerId, TowerCoreBlockEntity> loaded = loadedTowers();
        List<Fact> facts = new ArrayList<>();
        if (members.isEmpty()) {
            // Carried, but in no system: a scan can land between a tower's last tick and the
            // snapshot that still remembers it, and a game test can pin a carrier to a tower whose
            // mast is not turning at all. Either way the tower itself is the whole system.
            TowerCoreBlockEntity own = loaded.get(carrier);
            if (own == null) {
                return NONE;
            }
            facts.add(factOf(own, BlockPos.of(carrier.packedPos()), directory.settings(carrier),
                    TowerActivation.snapshot().traffic(carrier)));
            return describe(carrier, facts, TowerActivation.usage(carrier), selectedSide(level, carrier));
        }
        for (TowerSystem.Member member : members) {
            facts.add(factOf(loaded.get(member.id()), member.base(), directory.settings(member.id()),
                    TowerActivation.snapshot().traffic(member.id())));
        }
        return describe(carrier, facts, TowerActivation.usage(carrier), selectedSide(level, carrier));
    }

    /**
     * The side of a stored square, or zero when the set is not one.
     *
     * <p>Selections are squares, so the side is what the screen draws and what the buttons compare
     * against. A set written by something else — a hand-edited file, or an older shape — reads as
     * zero and shows as "none" rather than as a guess: a side the file does not hold is worse than
     * no side at all.
     */
    public static int sideOf(LongSet chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        int side = (int) Math.round(Math.sqrt(chunks.size()));
        if (side < 1 || side * side != chunks.size()) {
            return 0;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            int x = ChunkPos.getX(chunk);
            int z = ChunkPos.getZ(chunk);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return maxX - minX + 1 == side && maxZ - minZ + 1 == side ? side : 0;
    }

    /**
     * The square the carrier tower is set to keep loaded, in chunks of side.
     *
     * <p>One tower's answer, not the system's: the operator sets a radius per tower, so two members
     * of one system can be holding different squares. The readout's own {@code selectedSide} is the
     * carrier's, and each member carries its own alongside.
     */
    private static int selectedSide(Level level, TowerSystem.TowerId carrier) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return 0;
        }
        TowerDirectory.Settings settings = TowerDirectory.get(server).settings(carrier);
        if (!settings.loading()) {
            return 0;
        }
        TowerCoreBlockEntity tower = loadedTowers().get(carrier);
        return TowerTier.sideForRadius(settings.radiusFor(tower == null ? null : tower.tier()));
    }

    /**
     * A ten-minute count, narrowed for the wire.
     *
     * <p>The counter is a long because it is a running total; the readout is an int because it goes
     * out as a varint and a screen is not going to draw a number anywhere near this large. Saturating
     * rather than wrapping keeps a nonsensical total reading as "a lot" instead of as a small number.
     */
    private static int count(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private static Fact factOf(TowerCoreBlockEntity tower, BlockPos base,
                               TowerDirectory.Settings settings, TowerActivation.Traffic traffic) {
        if (tower == null) {
            return new Fact(base, null, false, 0, 0, 0, false, settings, traffic);
        }
        return new Fact(base, tower.tier(), tower.isRunning(), tower.getSpeed(),
                tower.ether(), tower.networkCapacity(), tower.overstressed(), settings, traffic);
    }

    private static Map<TowerSystem.TowerId, TowerCoreBlockEntity> loadedTowers() {
        Map<TowerSystem.TowerId, TowerCoreBlockEntity> loaded = new HashMap<>();
        for (TowerCoreBlockEntity tower : LoadedTowers.all()) {
            loaded.put(TowerSystem.TowerId.of(tower.getLevel().dimension(), tower.getBlockPos()), tower);
        }
        return loaded;
    }

    @Override
    public String toString() {
        return "TowerReadout[" + (attached
                ? carrierPos + "@" + dimension + " " + carried + "/" + limit
                + " stress=" + stress + " speed=" + speed + " maxSide=" + maxSide
                + " selected=" + selectedSide + " members=" + members.size()
                : "unattached") + "]";
    }
}
