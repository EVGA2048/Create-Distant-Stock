package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.menu.DockMenu;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
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
                java.util.UUID scope = RequesterData.distantNetwork(stack)
                        .filter(dev.distantstock.routing.DistantNetworkDirectory::isFormalId)
                        .orElse(null);
                if (scope == null) {
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.network.required"), true);
                    return ItemInteractionResult.sidedSuccess(false);
                }
                RemoteNetworkId dockNetwork = be.networkId();
                if (dockNetwork == null && be.freq() != null) {
                    dockNetwork = dev.distantstock.stock.NetworkDirectory.findByFreq(be.freq())
                            .filter(dev.distantstock.stock.NetworkDirectory.Entry::local)
                            .map(dev.distantstock.stock.NetworkDirectory.Entry::networkId)
                            .orElse(null);
                }
                if (dockNetwork == null) {
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.dock.bind_create_first"), true);
                    return ItemInteractionResult.sidedSuccess(false);
                }
                var directory = dev.distantstock.routing.DistantNetworkDirectory.get(level.getServer());
                java.util.UUID currentScope = directory.formalNetworkOf(dockNetwork).orElse(null);
                if (currentScope == null) {
                    if (!dev.distantstock.stock.CreateNetworkAccess.mayAdministrate(
                            dockNetwork, dockNetwork.createFrequency(), player)) {
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.network.create_admin_required"), true);
                        return ItemInteractionResult.sidedSuccess(false);
                    }
                    if (!directory.attach(dockNetwork, scope)) {
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.network.warehouse_join_failed"), true);
                        return ItemInteractionResult.sidedSuccess(false);
                    }
                    directory.assignDefaultMemberName(dockNetwork, scope, player.getName().getString());
                    be.setNetwork(dockNetwork);
                    RequesterData.setNetwork(stack, dockNetwork, scope);
                    player.getInventory().setChanged();
                    dev.distantstock.stock.StockScanner.scan(level.getServer());
                    String name = directory.find(scope).map(dev.distantstock.routing.DistantNetworkDirectory.Network::name)
                            .orElse(scope.toString().substring(0, 8));
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.network.warehouse_joined", name), true);
                    return ItemInteractionResult.sidedSuccess(false);
                }
                if (!scope.equals(currentScope)) {
                    String name = directory.find(currentScope)
                            .map(dev.distantstock.routing.DistantNetworkDirectory.Network::name)
                            .orElse(currentScope.toString().substring(0, 8));
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.network.warehouse_other", name), true);
                    return ItemInteractionResult.sidedSuccess(false);
                }
                RemoteNetworkId selected = RequesterData.network(stack).orElse(null);
                if (!dockNetwork.equals(selected)) {
                    RequesterData.setNetwork(stack, dockNetwork, scope);
                    player.getInventory().setChanged();
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.network.warehouse_selected",
                            dockNetwork.shortLabel()), true);
                    return ItemInteractionResult.sidedSuccess(false);
                }
                // Network binding ends here. Receiving address, mode and priority now belong to
                // the dock's own one-page UI; a terminal click must not secretly rewrite any of
                // those settings depending on whether the player happened to be sneaking.
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.dock.bound_open_ui"), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (isWrench(stack)) {
            if (!level.isClientSide && !player.isShiftKeyDown()) {
                be.clearFault();
                // Wrench is maintenance-only. Configuration lives exclusively in DockScreen;
                // keeping a second writer here would reintroduce the old split interaction model.
                player.displayClientMessage(be.modeMessage(), true);
            }
            // Never consume the wrench: IWrenchable still owns removal/rotation semantics.
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
        // Holding something the dock has no use for is worth saying out loud: it is the only way to
        // tell "the dock looked at this item and shrugged" apart from "the dock never saw the
        // click".
        if (!level.isClientSide && !stack.isEmpty() && !(stack.getItem() instanceof BlockItem)) {
            player.sendSystemMessage(Component.translatable("gui.distantstock.dock.unhandled",
                    stack.getHoverName()));
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Empty-hand interaction is a different Minecraft hook from {@link #useItemOn}. The first UI
     * implementation incorrectly put this path in useItemOn, which made the menu perfectly valid
     * but unreachable in play. Distant Dock configuration now has one interaction: empty-hand
     * right-click opens the Package-Port-style screen, sneaking or not.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, inv, ignored) -> DockMenu.server(id, inv, dock),
                    Component.translatable("gui.distantstock.dock.title")),
                    buf -> DockMenu.writeOpenData(buf, dock));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static boolean isWrench(ItemStack stack) {
        return stack.is(net.neoforged.neoforge.common.Tags.Items.TOOLS_WRENCH);
    }

}
