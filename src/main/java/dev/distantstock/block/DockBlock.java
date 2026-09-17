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
                    // 潜行右键：把这个港挂到终端携带的那个接收港组上，并切成收货。
                    //
                    // **只写组，不写地址**：港的收件条件从此只有组一个（见 LoadedDocks.importFor）。
                    // 同组的港互相顶替 —— 包裹来了按优先级挑一个空闲的 —— 若再让每个港按自己的地址
                    // 各筛一半，"一组多个港"就退化成了"几个各管一半的港"。地址是包裹身上的东西：
                    // 落地之后由本机物流（蛙港、溜槽、传送带）按它继续分拣。
                    //
                    // 没设组的终端按默认组算，和模组里其它地方的解释一致；否则这一下会什么都不做，
                    // 而玩家在动作栏里看到的是"已加入"。
                    java.util.UUID group = RequesterData.receivingGroup(stack)
                            .orElse(DockGroupDirectory.DEFAULT_GROUP_ID);
                    // Joining someone else's group means their parcels come out of this dock. A
                    // closed group refuses, and says so out loud: a click that is silently ignored
                    // reads as a broken item, not as a locked door.
                    if (admits(level, group, player)) {
                        be.setImport();
                        be.setGroupId(group);
                        // 整个手势的全部效果就是方块上的一行字，不说出来玩家不知道写进去没有。
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.dock.joined",
                                RequesterData.receivingGroupName(stack)
                                        .orElse(Component.translatable(
                                                "gui.distantstock.group.default").getString())), true);
                    } else {
                        player.displayClientMessage(
                                Component.translatable("gui.distantstock.group.closed"), true);
                    }
                } else if (!RequesterData.tuned(stack)) {
                    // Untuned terminal, plain click: say so rather than doing nothing. Sneak-click
                    // still works — writing an address and joining a group need no network, and a
                    // terminal is the only thing that can do either.
                    RequesterItem.sayUntuned(player);
                } else {
                    be.setMode(DockMode.SEND);
                    // What the requester carries is the destination. Sneak-click is what sets a
                    // dock's own group, so the two gestures read as one sentence: sneak to say
                    // "this dock belongs here", plain to say "this dock sends there".
                    java.util.UUID carried = RequesterData.receivingGroup(stack)
                            .orElse(DockGroupDirectory.DEFAULT_GROUP_ID);
                    // The real name, looked up, not the default's. An older requester carries an id
                    // with no name on it, and falling back to the default group's name would write
                    // that wrong name back onto the item — a label that is worse than none, because
                    // it looks like an answer.
                    String carriedName = RequesterData.receivingGroupName(stack)
                            .orElseGet(() -> level.getServer() == null ? DockGroupDirectory.DEFAULT_GROUP_NAME
                                    : DockGroupDirectory.get(level.getServer()).find(carried)
                                            .map(dev.distantstock.routing.DockGroup::name)
                                            .orElse(DockGroupDirectory.DEFAULT_GROUP_NAME));
                    // The node is this one unless a network is bound, which is what makes an
                    // in-save pair of systems work with no Transerver anywhere: the destination is
                    // (my node, that group), and the node delivers it to itself.
                    //
                    // This used to be written twice — once here and once inside a network check
                    // below that always paired the network's node with the *default* group. A
                    // player who picked a system and then bound a network got the system silently
                    // replaced, which is the sort of thing that reads as "groups do not work".
                    //
                    // 旧写法还有一处更早的错：.filter(network -> !network.nodeId().equals(nodeId()))
                    // 想「不要把包裹发给本机」。TranserverBridge.nodeId() 在装了 Transerver 却没接上
                    // API 时返回 null（单机存档就是这种情况），而 equals(null) 恒为 false，过滤器等于
                    // 失效；而且就算它返回真实节点 id，「发到本机」本身也不非法——同一个存档里的两个
                    // 港组就是两套系统，服内互传靠的就是它。
                    java.util.UUID node = RequesterData.network(stack)
                            .map(dev.distantstock.routing.RemoteNetworkId::nodeId)
                            .orElseGet(() -> java.util.UUID.fromString(TranserverBridge.localNodeId()));
                    if (!admits(level, carried, player)) {
                        // The same refusal for the sending side. Pointing a dock at a group fills
                        // that group's docks with parcels, which is no more a stranger's business
                        // than adding a dock to it.
                        player.displayClientMessage(
                                Component.translatable("gui.distantstock.group.closed"), true);
                        return ItemInteractionResult.sidedSuccess(level.isClientSide);
                    }
                    be.setDefaultDestination(node, carried);
                    RequesterData.setReceivingGroup(stack, carried, carriedName);
                    // Say what was just written, in the bar rather than only on the goggles. The
                    // gesture's whole effect is a line of text on a block the player is not looking
                    // at while they hold a terminal, and the failure it used to have — a terminal
                    // holding no group silently aiming the dock at the wildcard group — is
                    // indistinguishable from success without being told which group it was.
                    boolean noGroup = RequesterData.receivingGroup(stack).isEmpty();
                    if (noGroup && !dev.distantstock.link.TranserverBridge.isLocal(node.toString())) {
                        // The one combination that cannot work, said before the player walks away:
                        // a terminal holding no group aims a dock at the wildcard group, and a
                        // parcel leaving this node towards it is refused at the dock. Better to
                        // say so now than to leave them watching a parcel that will not go.
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.dock.target.no_group", carriedName), true);
                    } else {
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.dock.target",
                                carriedName, nodeLabel(level, node)), true);
                    }
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (isWrench(stack)) {
            if (!level.isClientSide && !player.isShiftKeyDown()) {
                be.clearFault();
                // Reports only. The mode itself is set in the value settings panel, which holding
                // the wrench opens — adding a click gesture here as well would be a second writer
                // for one piece of state, and the two would disagree: the panel marks the current
                // row, and a click would move it without the panel knowing.
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
            // An empty hand takes whatever is in the dock: what arrived, or what is stuck in it and
            // cannot leave. The second half was missing, which left a refused parcel with no way out
            // short of breaking the block.
            String someoneElse = !level.isClientSide && !player.isShiftKeyDown()
                    ? be.heldForSomeoneElse(player) : null;
            if (someoneElse != null) {
                // 拿不走就说清楚是给谁的。地址写成 @名字 的包裹只有那个人能取 —— 一句"没反应"
                // 会让人以为港坏了。
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.parcel.for_other", someoneElse), true);
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && !player.isShiftKeyDown()
                    && (be.takeReceived(player) || be.takeStuck(player))) {
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

    /**
     * Whether this player may point anything at that group.
     *
     * <p>One check for both gestures, because from the group's side they are the same act: a dock
     * that joins it receives into it, and a dock that sends to it fills it. Both make machinery work
     * for a group the player does not own, and a lock that allowed half of that would not be one.
     */
    /**
     * How to name the node a dock is aimed at: "this server", or the other server's short label.
     *
     * <p>Read from the remote destinations this server has been let into, which is where the label
     * the player saw in the list came from — the same name for the same thing, rather than a UUID.
     */
    private static String nodeLabel(Level level, java.util.UUID node) {
        if (level.getServer() == null || dev.distantstock.link.TranserverBridge.isLocal(node.toString())) {
            return Component.translatable("gui.distantstock.local").getString();
        }
        return dev.distantstock.routing.RemoteGroups.get(level.getServer()).all().stream()
                .filter(entry -> entry.node().equals(node))
                .map(entry -> entry.display().split("·")[0])
                .findFirst()
                .orElse(node.toString().substring(0, 8));
    }

    private static boolean admits(Level level, java.util.UUID group, Player player) {
        if (level.getServer() == null) {
            return false;
        }
        dev.distantstock.routing.DockGroup found = DockGroupDirectory.get(level.getServer()).find(group).orElse(null);
        // A group that is gone cannot be joined, and saying yes would leave a dock pointed at
        // nothing while reporting success.
        return found != null && found.admits(player.getUUID());
    }
}
