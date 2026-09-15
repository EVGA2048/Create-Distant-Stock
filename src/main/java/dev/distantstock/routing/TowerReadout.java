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
 * block entities, because those are not part of that snapshot. Nothing here is computed per frame or
 * per tick: a readout is built once a second, on the same beat the monitor's other numbers move.
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

    /** One member tower as the readout shows it. {@code tier} is empty for a tower that went away. */
    public record Member(long pos, String tier, int radius, int devices, boolean running, float speed) {
    }

    /**
     * One loaded tower, as the world half found it: where it stands, how tall it is, how fast it
     * turns. A tower whose block entity is gone keeps its place in the list with a null tier —
     * dropping it would silently shrink a system the snapshot still believes in.
     */
    public record Fact(BlockPos base, TowerTier tier, boolean running, float speed) {
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
            members.add(new Member(fact.base().asLong(), tier == null ? "" : tier.name(),
                    tier == null ? 0 : tier.radius(), tier == null ? 0 : tier.devices(),
                    fact.running(), fact.speed()));
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
            return NONE;
        }
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
            facts.add(factOf(own, BlockPos.of(carrier.packedPos())));
            return describe(carrier, facts, TowerActivation.usage(carrier), selectedSide(level, carrier));
        }
        for (TowerSystem.Member member : members) {
            facts.add(factOf(loaded.get(member.id()), member.base()));
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

    private static Fact factOf(TowerCoreBlockEntity tower, BlockPos base) {
        if (tower == null) {
            return new Fact(base, null, false, 0);
        }
        return new Fact(base, tower.tier(), tower.isRunning(), tower.getSpeed());
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
