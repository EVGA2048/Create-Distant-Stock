package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class IndicatorLampBlock extends FaceAttachedHorizontalDirectionalBlock implements IWrenchable {

    /** Rotation would silently move the cabin or the panel slots, so a wrench click only reports state. */
    @Override
    public net.minecraft.world.InteractionResult onWrenched(net.minecraft.world.level.block.state.BlockState state,
                                                            net.minecraft.world.item.context.UseOnContext context) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    public static final MapCodec<IndicatorLampBlock> CODEC = simpleCodec(IndicatorLampBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    private static final VoxelShape FLOOR = Block.box(4.5, 0, 4.5, 11.5, 5, 11.5);
    private static final VoxelShape CEILING = Block.box(4.5, 11, 4.5, 11.5, 16, 11.5);
    private static final VoxelShape NORTH = Block.box(4.5, 4.5, 11, 11.5, 11.5, 16);
    private static final VoxelShape SOUTH = Block.box(4.5, 4.5, 0, 11.5, 11.5, 5);
    private static final VoxelShape WEST = Block.box(11, 4.5, 4.5, 16, 11.5, 11.5);
    private static final VoxelShape EAST = Block.box(0, 4.5, 4.5, 5, 11.5, 11.5);

    public IndicatorLampBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.WALL)
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<IndicatorLampBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(LIT,
                context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                   BlockPos neighborPos, boolean movedByPiston) {
        if (level.isClientSide) {
            return;
        }
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(LIT) == powered) {
            return;
        }
        if (powered) {
            level.setBlock(pos, state.setValue(LIT, true), 2);
        } else {
            level.scheduleTick(pos, this, 2);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(LIT) && !level.hasNeighborSignal(pos)) {
            level.setBlock(pos, state.setValue(LIT, false), 2);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> FLOOR;
            case CEILING -> CEILING;
            case WALL -> switch (state.getValue(FACING)) {
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                case EAST -> EAST;
                default -> NORTH;
            };
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING, LIT);
    }
}
