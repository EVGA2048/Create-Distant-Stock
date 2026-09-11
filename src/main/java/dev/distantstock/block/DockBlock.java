package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DockMode;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class DockBlock extends BaseEntityBlock implements IWrenchable {

    /** Rotation would silently move the cabin or the panel slots, so a wrench click only reports state. */
    @Override
    public net.minecraft.world.InteractionResult onWrenched(net.minecraft.world.level.block.state.BlockState state,
                                                            net.minecraft.world.item.context.UseOnContext context) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DockStatus> STATUS = EnumProperty.create("status", DockStatus.class);
    public static final MapCodec<DockBlock> CODEC = simpleCodec(DockBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    public DockBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(STATUS, DockStatus.INACTIVE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, STATUS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DockBlockEntity(ModBlockEntities.DOCK.get(), pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Runs on both sides: Create only initialises behaviours from SmartBlockEntity.tick().
        return createTickerHelper(type, ModBlockEntities.DOCK.get(), DockBlockEntity::serverTick);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DockBlockEntity dock) {
            dock.spillContents();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof DockBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof RequesterItem) {
            if (!level.isClientSide) {
                if (player.isShiftKeyDown()) {
                    // Sneak + requester: apply the requester's dock group and address to this dock.
                    be.setImport(RequesterData.address(stack));
                    RequesterData.receivingGroup(stack).ifPresent(be::setGroupId);
                } else if (RequesterData.tuned(stack)) {
                    be.setMode(DockMode.SEND);
                    // Copy this dock's group onto the requester for later application.
                    RequesterData.setReceivingGroup(stack, be.groupId());
                    RequesterData.network(stack)
                            .filter(network -> !network.nodeId().equals(TranserverBridge.nodeId()))
                            .ifPresent(network -> be.setDefaultDestination(network.nodeId(),
                                    DockGroupDirectory.DEFAULT_GROUP_ID));
                }
                be.clearFault();
                player.displayClientMessage(be.modeMessage(), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (isWrench(stack)) {
            if (!level.isClientSide && !player.isShiftKeyDown()) {
                be.clearFault();
                player.displayClientMessage(be.modeMessage(), true);
            }
            // Never consume the wrench: Create removes blocks with sneak-right-click and opens the value
            // settings panel by holding it, both of which need this click to fall through.
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.isEmpty()) {
            if (!level.isClientSide) {
                be.clearFault();
                player.displayClientMessage(be.modeMessage(), true);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    static boolean isWrench(ItemStack stack) {
        return stack.is(net.neoforged.neoforge.common.Tags.Items.TOOLS_WRENCH);
    }
}
