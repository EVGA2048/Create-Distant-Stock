package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Three-colour factory stack light. The lamps are deliberately independent: red/yellow/green may
 * be lit in any combination and the buzzer enable line lives in the block entity, not in the model.
 */
public final class StackLightBlock extends FaceAttachedHorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    public static final MapCodec<StackLightBlock> CODEC = simpleCodec(StackLightBlock::new);
    public static final BooleanProperty RED = BooleanProperty.create("red");
    public static final BooleanProperty YELLOW = BooleanProperty.create("yellow");
    public static final BooleanProperty GREEN = BooleanProperty.create("green");

    private static final VoxelShape FLOOR = Block.box(4, 0, 4, 12, 24, 12);
    private static final VoxelShape CEILING = Block.box(4, -8, 4, 12, 16, 12);
    private static final VoxelShape NORTH = Block.box(4, 4, 7, 12, 29, 16);
    private static final VoxelShape SOUTH = Block.box(4, 4, 0, 12, 29, 9);
    private static final VoxelShape WEST = Block.box(7, 4, 4, 16, 29, 12);
    private static final VoxelShape EAST = Block.box(0, 4, 4, 9, 29, 12);

    public StackLightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(RED, false)
                .setValue(YELLOW, false)
                .setValue(GREEN, false));
    }

    @Override
    protected MapCodec<StackLightBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state
                .setValue(RED, false)
                .setValue(YELLOW, false)
                .setValue(GREEN, false);
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
        builder.add(FACE, FACING, RED, YELLOW, GREEN);
    }

    public static boolean anyLit(BlockState state) {
        return state.hasProperty(RED) && (state.getValue(RED)
                || state.getValue(YELLOW) || state.getValue(GREEN));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StackLightBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.STACK_LIGHT.get()) return null;
        return (tickerLevel, tickerPos, tickerState, entity) ->
                StackLightBlockEntity.serverTick(tickerLevel, tickerPos, tickerState,
                        (StackLightBlockEntity) entity);
    }

    /** Applies one controller sample. Block state only carries the three visible lamps. */
    public static void apply(Level level, BlockPos pos, boolean red, boolean yellow, boolean green) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof StackLightBlock)) return;
        BlockState next = state.setValue(RED, red).setValue(YELLOW, yellow).setValue(GREEN, green);
        if (next != state) {
            level.setBlock(pos, next, 3);
        }
    }
}
