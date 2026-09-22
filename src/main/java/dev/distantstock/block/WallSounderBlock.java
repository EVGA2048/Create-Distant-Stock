package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Wall-mounted visual/audible alarm.  Redstone is the only run input; the block entity owns the
 * selected sound and the timing of both the sound loop and the double-flash lamp pattern.
 */
public final class WallSounderBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<WallSounderBlock> CODEC = simpleCodec(WallSounderBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    // V4 artwork occupies x=4..12, y=2..14 and z=11..16 when facing north.
    private static final VoxelShape NORTH = Block.box(4, 2, 11, 12, 14, 16);
    private static final VoxelShape SOUTH = Block.box(4, 2, 0, 12, 14, 5);
    private static final VoxelShape WEST = Block.box(11, 2, 4, 16, 14, 12);
    private static final VoxelShape EAST = Block.box(0, 2, 4, 5, 14, 12);

    public WallSounderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<WallSounderBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        // The alarm is a wall appliance.  Clicking floor/ceiling falls back to the wall in front of
        // the player instead of creating a nonsensical horizontal siren.
        if (!face.getAxis().isHorizontal()) {
            face = context.getHorizontalDirection().getOpposite();
        }
        boolean powered = context.getLevel().hasNeighborSignal(context.getClickedPos());
        return defaultBlockState()
                .setValue(FACING, face)
                .setValue(POWERED, powered)
                .setValue(LIT, powered);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                   BlockPos neighborPos, boolean movedByPiston) {
        if (level.isClientSide) return;
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) == powered) return;
        level.setBlock(pos, state.setValue(POWERED, powered).setValue(LIT, powered), 3);
        if (level.getBlockEntity(pos) instanceof WallSounderBlockEntity sounder) {
            sounder.powerChanged(powered);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, LIT);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WallSounderBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Runs on both sides: Create initializes ValueSettingsBehaviour from SmartBlockEntity.tick().
        return createTickerHelper(type, ModBlockEntities.WALL_SOUNDER.get(),
                WallSounderBlockEntity::tickSounder);
    }
}
