package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * One segment of the tower's mast. Stack them to raise a tower's tier.
 *
 * <p>Two properties, because the model changes at both ends. Where a neighbour is another coupler
 * the joint's faces are internal: the ring that closes the block is dropped, and a cross-frame is
 * drawn across the seam instead. Without that the two blocks put a cap each on the same plane and
 * they tear each other apart — the handoff calls this out, and it is the same coincidence that
 * shredded the requester console's antenna.
 *
 * <p>The frame belongs to the upper block of a pair, drawn whole rather than half by each: one
 * block drawing it keeps its uv rectangles intact, where splitting it would mean re-authoring them
 * on both sides of the seam.
 */
public final class TowerCouplerBlock extends Block {
    public static final MapCodec<TowerCouplerBlock> CODEC = simpleCodec(TowerCouplerBlock::new);
    public static final BooleanProperty ABOVE = BooleanProperty.create("above");
    public static final BooleanProperty BELOW = BooleanProperty.create("below");

    public TowerCouplerBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(ABOVE, false).setValue(BELOW, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ABOVE, BELOW);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return connect(defaultBlockState(), ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction.getAxis().isVertical()) {
            return connect(state, level, pos);
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    private static BlockState connect(BlockState state, LevelAccessor level, BlockPos pos) {
        return state.setValue(ABOVE, isCoupler(level, pos.above()))
                .setValue(BELOW, isCoupler(level, pos.below()));
    }

    private static boolean isCoupler(LevelAccessor level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof TowerCouplerBlock;
    }

    /** Whether this coupler has another one directly above, which the tower scan also needs. */
    public static boolean connected(Level level, BlockPos pos, Direction direction) {
        return level.getBlockState(pos.relative(direction)).getBlock() instanceof TowerCouplerBlock;
    }
}
