package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Small wall appliance: dye recolours its Create Nixie glyphs; holding use toggles 12/24-hour mode. */
public final class NixieClockBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<NixieClockBlock> CODEC = simpleCodec(NixieClockBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // A 16x8x3 wall body. The glass tubes protrude visually from this base.
    private static final VoxelShape NORTH = Block.box(0, 4, 13, 16, 12, 16);
    private static final VoxelShape SOUTH = Block.box(0, 4, 0, 16, 12, 3);
    private static final VoxelShape WEST = Block.box(13, 4, 0, 16, 12, 16);
    private static final VoxelShape EAST = Block.box(0, 4, 0, 3, 12, 16);

    public NixieClockBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) face = context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, face);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, net.minecraft.world.InteractionHand hand,
                                               BlockHitResult hit) {
        DyeColor dye = DyeColor.getColor(stack);
        if (dye == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof NixieClockBlockEntity clock) {
            // Create's own Nixie recolouring does not consume dye; keep the same interaction here.
            clock.setColor(dye);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
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
        builder.add(FACING);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public InteractionResult onWrenched(BlockState state, net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof NixieClockBlockEntity clock) {
            clock.toggleMuted();
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        clock.muted() ? "message.distantstock.nixie_clock.muted"
                                : "message.distantstock.nixie_clock.unmuted"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NixieClockBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.NIXIE_CLOCK.get(), NixieClockBlockEntity::tickClock);
    }
}
