package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.menu.MenuSync;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class GaugeBlock extends BaseEntityBlock implements IWrenchable {

    /** Rotation would silently move the cabin or the panel slots, so a wrench click only reports state. */
    @Override
    public net.minecraft.world.InteractionResult onWrenched(net.minecraft.world.level.block.state.BlockState state,
                                                            net.minecraft.world.item.context.UseOnContext context) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final MapCodec<GaugeBlock> CODEC = simpleCodec(GaugeBlock::new);
    /**
     * The console is floor-standing furniture, so its whole silhouette is solid.
     *
     * The head is a single box rather than a staircase following its -22.5 degree tilt: an
     * AABB union cannot express the tilt, and a cheaper approximation would let players walk
     * through the board. The head's real extent is y 9.02..16.99 by z 0.23..15.77, clipped
     * here to the block. The antenna reaches y=25 and is deliberately left out, the same way
     * the previous mast was.
     */
    private static final VoxelShape NORTH = Shapes.or(
            Block.box(1, 0, 2, 15, 2, 15),
            Block.box(3, 2, 6, 13, 12, 14),
            Block.box(0, 9, 0, 16, 16, 16));
    private static final VoxelShape EAST = quarterTurn(NORTH);
    private static final VoxelShape SOUTH = quarterTurn(EAST);
    private static final VoxelShape WEST = quarterTurn(SOUTH);

    private static VoxelShape quarterTurn(VoxelShape shape) {
        VoxelShape rotated = Shapes.empty();
        for (var box : shape.toAabbs()) {
            rotated = Shapes.or(rotated, Block.box(
                    (1 - box.maxZ) * 16, box.minY * 16, box.minX * 16,
                    (1 - box.minZ) * 16, box.maxY * 16, box.maxX * 16));
        }
        return rotated;
    }

    public GaugeBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override
    protected MapCodec<GaugeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GaugeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.GAUGE.get(), GaugeBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (DockBlock.isWrench(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof RequesterItem) {
            // Holding the terminal means configuring, never opening: the desk's own screen is what
            // an empty hand is for. A player who came to point this desk at their network would
            // otherwise get a menu and no binding, and would have to work out that the two gestures
            // are one.
            if (!RequesterData.tuned(stack)) {
                if (!level.isClientSide) {
                    RequesterItem.sayUntuned(player);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof GaugeBlockEntity be) {
                be.setFreq(RequesterData.freq(stack));
                if (!RequesterData.address(stack).isEmpty()) {
                    be.setAddress(RequesterData.address(stack));
                }
                // The receiving group travels with the rest of the binding: a desk pointed at a
                // network but left on the default group would deliver into a system the player
                // never chose.
                RequesterData.receivingGroup(stack).ifPresent(be::setReceivingGroup);
                player.displayClientMessage(Component.translatable("gui.distantstock.tuned")
                        .append(Component.literal(" " + RequesterData.shortFreq(be.freq()))), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        open(level, pos, player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        open(level, pos, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void open(Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new RequesterMenu(id, inv, pos),
                    Component.translatable("gui.distantstock.title")
            ), buf -> {
                UUID freq = level.getBlockEntity(pos) instanceof GaugeBlockEntity be ? be.freq() : null;
                MenuSync.writeGauge(buf, pos, freq);
            });
        }
    }
}
