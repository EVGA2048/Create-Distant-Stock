package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DockMode;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
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
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DockBlockEntity dock) {
            RequesterData.network(stack).ifPresentOrElse(network -> dock.setNetwork(network),
                    () -> {
                        if (RequesterData.tuned(stack)) dock.setExport(RequesterData.freq(stack));
                    });
        }
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
                    // What the requester carries is the destination. Sneak-click is what sets a
                    // dock's own group, so the two gestures read as one sentence: sneak to say
                    // "this dock belongs here", plain to say "this dock sends there".
                    java.util.UUID carried = RequesterData.receivingGroup(stack)
                            .orElse(DockGroupDirectory.DEFAULT_GROUP_ID);
                    String carriedName = RequesterData.receivingGroupName(stack)
                            .orElse(DockGroupDirectory.DEFAULT_GROUP_NAME);
                    // The node is this one unless a network is bound, which is what makes an
                    // in-save pair of systems work with no Transerver anywhere: the destination is
                    // (my node, that group), and the node delivers it to itself.
                    java.util.UUID node = RequesterData.network(stack)
                            .map(dev.distantstock.routing.RemoteNetworkId::nodeId)
                            .orElseGet(() -> java.util.UUID.fromString(TranserverBridge.localNodeId()));
                    be.setDefaultDestination(node, carried);
                    RequesterData.setReceivingGroup(stack, carried, carriedName);
                    RequesterData.network(stack).ifPresent(network -> {
                        // 旧写法是 .filter(network -> !network.nodeId().equals(TranserverBridge.nodeId()))，
                        // 想「不要把包裹发给本机」，但它是错的：TranserverBridge.nodeId() 在装了 Transerver
                        // 却没接上 API 时返回 null（单机存档就是这种情况），而 equals(null) 恒为 false，
                        // 过滤器等于失效——本机网络照样被写成默认目的地。而且就算它返回了真实节点 id，
                        // 「发到本机」本身并不非法：同一个存档里的两个港组就是两套系统，服内互传靠的就是它。
                        // The old filter was meant to skip "send this to myself" but failed at both ends: it
                        // never fired when nodeId() was null (equals(null) is always false), and a parcel to
                        // another group on the same node is a legitimate destination, not a mistake.
                        //
                        // 新判断：本机节点是合法目的地，只跳过「目的地就是本机 且 组也与本港相同」这种真正
                        // 无意义的自环——那只会让包裹绕一圈回到自己的收货槽。其余交给 LoadedDocks.importFor
                        // 按组和地址选港，选不到就 RETRY。
                        boolean selfLoop = TranserverBridge.isLocal(network.nodeId().toString())
                                && DockGroupDirectory.DEFAULT_GROUP_ID.equals(be.groupId());
                        if (!selfLoop) {
                            be.setDefaultDestination(network.nodeId(), DockGroupDirectory.DEFAULT_GROUP_ID);
                        }
                    });
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
        if (PackageItem.isPackage(stack)) {
            if (!level.isClientSide) {
                // The dock holds one parcel at a time, so a refusal here is normal and it used to be
                // silent: the click reported success and the parcel stayed in hand with no hint why.
                boolean accepted = be.acceptParcel(stack);
                if (accepted && !player.isCreative()) {
                    stack.shrink(1);
                }
                if (accepted) {
                    player.displayClientMessage(
                            Component.translatable("gui.distantstock.dock.accepted"), true);
                } else {
                    // To chat, not the action bar. Wearing goggles and looking at a dock puts the
                    // readout over the action bar, which is exactly when this needs to be read.
                    player.sendSystemMessage(Component.translatable("gui.distantstock.dock.busy"));
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.isEmpty()) {
            if (!level.isClientSide && player.isShiftKeyDown()) {
                be.clearNetwork();
                player.displayClientMessage(Component.translatable("gui.distantstock.dock_unbound"), true);
                return ItemInteractionResult.sidedSuccess(false);
            }
            if (!level.isClientSide && !player.isShiftKeyDown() && be.takeReceived(player)) {
                return ItemInteractionResult.sidedSuccess(false);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Holding something the dock has no use for is worth saying out loud: it is the only way to
        // tell "the dock looked at this item and shrugged" apart from "the dock never saw the
        // click".
        if (!level.isClientSide && !stack.isEmpty() && !(stack.getItem() instanceof BlockItem)) {
            player.sendSystemMessage(Component.translatable("gui.distantstock.dock.unhandled",
                    stack.getHoverName()));
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    static boolean isWrench(ItemStack stack) {
        return stack.is(net.neoforged.neoforge.common.Tags.Items.TOOLS_WRENCH);
    }
}
