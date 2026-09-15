package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import dev.distantstock.config.StockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The distant casing: the tower's 3x3 skirt and the decorative panel everywhere else.
 *
 * <p>Two textures, picked by a connected-texture behaviour on the client from the eight casings
 * around each face. A redstone signal turns the panel into a window, and the window spreads along
 * connected casings the way a signal spreads along wire — which is the part that lives here,
 * because it has to be in the block state: the texture is chosen while the chunk is baked, and
 * that code only ever sees a state, never a world.
 *
 * <p>{@link #POWERED} therefore means "this casing is connected, within range, to one that is
 * being driven directly". It is derived, never set by redstone itself — a casing can be lit with
 * nothing attached to it.
 */
public final class TowerCasingBlock extends Block {
    public static final MapCodec<TowerCasingBlock> CODEC = simpleCodec(TowerCasingBlock::new);
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");

    private static final Direction[] DIRECTIONS = Direction.values();
    /**
     * Ceiling on how many casings one search may look at.
     *
     * A radius on its own does not bound the work: a wall is mostly surface, so the number of
     * casings within N steps grows with N squared. This is the number that actually keeps a large
     * build from hitching when someone flips a lever, and hitting it simply reports unpowered.
     */
    private static final int SEARCH_LIMIT = 4096;

    public TowerCasingBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    /**
     * Settle on the next tick rather than now.
     *
     * A redstone change reaches all six neighbours before any of them reacts, so deciding inline
     * would let a casing read a neighbour that has not been updated yet. One tick also collapses a
     * burst of changes — a lever, a piston, a whole bank of them — into a single pass per casing.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos neighbourPos, boolean isMoving) {
        if (!level.isClientSide && !level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        boolean lit = drivenWithinRange(level, pos);
        if (lit == state.getValue(POWERED)) {
            return;
        }
        // setBlock, not just a state swap: the neighbours have to be told, or the change stops here
        // and the window never spreads past the casings the lever itself touches.
        level.setBlock(pos, state.setValue(POWERED, lit), Block.UPDATE_ALL);
    }

    /**
     * Whether any casing connected to this one is being driven directly by redstone, within range.
     *
     * <p>Note what is searched for: a casing with an actual signal, not a casing that is merely
     * {@link #POWERED}. The window is a light, and this is the bulb — a lit casing does not light
     * its neighbours any more than a lit lamp does, so the effect cannot walk away from the source
     * one casing at a time and outrun the range check.
     */
    private static boolean drivenWithinRange(Level level, BlockPos origin) {
        int range = StockConfig.casingRedstoneRange();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> frontier = new ArrayList<>();
        seen.add(origin);
        frontier.add(origin);

        for (int step = 0; step <= range && !frontier.isEmpty(); step++) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos pos : frontier) {
                if (level.hasNeighborSignal(pos)) {
                    return true;
                }
                for (Direction direction : DIRECTIONS) {
                    BlockPos neighbour = pos.relative(direction);
                    if (seen.size() >= SEARCH_LIMIT) {
                        return false;
                    }
                    if (seen.add(neighbour)
                            && level.getBlockState(neighbour).getBlock() instanceof TowerCasingBlock) {
                        next.add(neighbour);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }
}
