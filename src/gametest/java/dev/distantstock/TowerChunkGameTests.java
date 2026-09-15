package dev.distantstock;

import dev.distantstock.block.ModBlocks;
import dev.distantstock.routing.TowerChunkLoader;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * The square of chunks a tower keeps loaded.
 *
 * <p>The arithmetic is checked on its own, because that is where an off-by-one hides: a side of
 * three that loads a five-by-five square looks entirely plausible until a server runs out of
 * memory, and a side of one that loads nine chunks does too.
 *
 * <p>The end-to-end case reads the real forced-chunk data rather than the loader's own bookkeeping.
 * Bookkeeping that agrees with itself proves nothing; what matters is whether a ticket is in the
 * level, and that is what is counted here — per chunk, so a case running in parallel in the next
 * arena cannot make this one pass or fail.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class TowerChunkGameTests {
    private static final int X = 3;
    private static final int Z = 3;

    /** A side of one is the tower's own chunk, and a side of three is that chunk and its ring. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theLoadedSquareMatchesTheTier(GameTestHelper h) {
        BlockPos base = new BlockPos(16, 64, 32);
        ChunkPos center = new ChunkPos(base);

        LongSet single = TowerChunkLoader.chunksAround(base, 1);
        h.assertTrue(single.size() == 1, "a side of one loaded " + single.size() + " chunks");
        h.assertTrue(single.contains(center.toLong()), "a side of one did not load the base's own chunk");

        LongSet three = TowerChunkLoader.chunksAround(base, 3);
        h.assertTrue(three.size() == 9, "a side of three loaded " + three.size() + " chunks");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                h.assertTrue(three.contains(ChunkPos.asLong(center.x + dx, center.z + dz)),
                        "the square is missing " + (center.x + dx) + "," + (center.z + dz));
            }
        }
        // The edge is one short of the side, not equal to it: half of three is one. Taking the side
        // itself as the radius would load seven chunks across where three were asked for.
        h.assertFalse(three.contains(ChunkPos.asLong(center.x + 2, center.z)),
                "a side of three reached two chunks out");

        LongSet five = TowerChunkLoader.chunksAround(base, 5);
        h.assertTrue(five.size() == 25, "a side of five loaded " + five.size() + " chunks");
        h.assertTrue(five.contains(ChunkPos.asLong(center.x + 2, center.z + 2)),
                "a side of five did not reach two chunks out");
        h.assertFalse(five.contains(ChunkPos.asLong(center.x + 3, center.z)),
                "a side of five reached three chunks out");
        h.succeed();
    }

    /**
     * A tower takes a ticket on its own chunk, and gives it back when the tower is taken down.
     *
     * <p>Counted from the level's forced-chunk file, one chunk at a time, so the parallel cases
     * sharing this level cannot move the number.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aTowerTakesItsChunkAndReleasesIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos core = h.absolutePos(new BlockPos(X, 0, Z));
        ChunkPos chunk = new ChunkPos(core);
        int before = ownersForcing(level, chunk);

        build(h, 5);
        // The base rescans its mast every twenty ticks and the loader reconciles on the same beat;
        // eighty ticks is four of each, with room to spare.
        h.runAfterDelay(80, () -> {
            int loaded = ownersForcing(level, chunk);
            h.assertTrue(loaded == before + 1,
                    "a tier I tower forced " + (loaded - before) + " owners on its own chunk instead of one");

            // Taking the mast down is not the same as taking the block away: the tower is gone, so
            // its chunk is released.
            h.setBlock(X, 0, Z, Blocks.AIR.defaultBlockState());
            h.runAfterDelay(80, () -> {
                int left = ownersForcing(level, chunk);
                h.assertTrue(left == before,
                        "the ticket outlived the tower: " + (left - before) + " owners still hold the chunk");
                h.succeed();
            });
        });
    }

    /**
     * How many owners, of any ticket controller, force this chunk.
     *
     * <p>Both sets are counted: a tower takes a ticking ticket, and anything non-ticking on the same
     * chunk would be a leftover from a build that used to. Vanilla's own forced chunks live in a
     * different set entirely and are deliberately not counted — the game test framework forces the
     * arena itself, and that must not be mistaken for a tower's ticket.
     */
    private static int ownersForcing(ServerLevel level, ChunkPos chunk) {
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
        if (data == null) {
            return 0;
        }
        long packed = chunk.toLong();
        return ownersIn(data.getBlockForcedChunks().getChunks(), packed)
                + ownersIn(data.getBlockForcedChunks().getTickingChunks(), packed);
    }

    private static int ownersIn(Map<?, ?> byOwner, long chunk) {
        int owners = 0;
        for (Object held : byOwner.values()) {
            if (held instanceof LongSet chunks && chunks.contains(chunk)) {
                owners++;
            }
        }
        return owners;
    }

    /** Core at the bottom, {@code couplers} segments, and a resonator on top: the shortest tower. */
    private static void build(GameTestHelper h, int couplers) {
        h.setBlock(X, 0, Z, ModBlocks.TOWER_CORE.get().defaultBlockState());
        for (int i = 1; i <= couplers; i++) {
            h.setBlock(X, i, Z, ModBlocks.TOWER_COUPLER.get().defaultBlockState());
        }
        h.setBlock(X, couplers + 1, Z, ModBlocks.ETHER_RESONATOR.get().defaultBlockState());
    }

    private TowerChunkGameTests() {
    }
}
