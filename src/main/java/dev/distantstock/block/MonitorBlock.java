package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.net.AdminConfigS2C;
import dev.distantstock.net.OpenMonitorS2C;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The link monitor's block.
 *
 * <p>It hands its ticking to Create, which is not how it used to be: the flap display that fills
 * this block's face animates on the client, so the ticker has to run on both sides. {@code IBE}
 * gives exactly that, and Create's smart ticker is what calls the block entity's own {@code tick}.
 */
public final class MonitorBlock extends WallPanelBlock implements IWrenchable,
        com.simibubi.create.foundation.block.IBE<MonitorBlockEntity> {

    /** Rotation would silently move the cabin or the panel slots, so a wrench click only reports state. */
    @Override
    public net.minecraft.world.InteractionResult onWrenched(net.minecraft.world.level.block.state.BlockState state,
                                                            net.minecraft.world.item.context.UseOnContext context) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    public static final MapCodec<MonitorBlock> CODEC = simpleCodec(MonitorBlock::new);
    public static final EnumProperty<Status> STATUS = EnumProperty.create("status", Status.class);
    /**
     * What the second bulb shows: the state of the link, not of this server.
     *
     * <p>A separate property from {@link #STATUS} because the two lamps say different things and a
     * monitor is read at a glance — the left bulb is this server's own tick rate, the right one is
     * whether the other end is answering. See {@code scripts/gen_monitor_face.py} for how the two
     * colours are picked out of one bulb texture.
     */
    public static final EnumProperty<Link> LINK = EnumProperty.create("link", Link.class);

    public MonitorBlock(Properties props) {
        super(props);
        registerDefaultState(defaultBlockState().setValue(STATUS, Status.GREEN).setValue(LINK, Link.OFF));
    }

    @Override
    protected MapCodec<MonitorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> b) {
        super.createBlockStateDefinition(b);
        b.add(STATUS);
        b.add(LINK);
    }

    public enum Status implements StringRepresentable {
        GREEN("green"),
        ORANGE("orange"),
        RED("red");

        private final String name;

        Status(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public static Status fromTps(double tps) {
            if (tps >= 18.0) {
                return GREEN;
            }
            if (tps >= 12.0) {
                return ORANGE;
            }
            return RED;
        }
    }

    /** The link lamp's four states, in the order the bulb texture lists its colours. */
    public enum Link implements StringRepresentable {
        /** Nothing attached, or the transport is down: the other end is not being talked to. */
        OFF("off"),
        /** The link answers and the other server has said how it is doing. */
        ONLINE("online"),
        /** The link answers but nothing has come back yet — waiting, not broken. */
        SYNCING("syncing"),
        /** The link answers and the last attempt to use it failed. */
        FAULT("fault");

        private final String name;

        Link(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Override
    public Class<MonitorBlockEntity> getBlockEntityClass() {
        return MonitorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MonitorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.MONITOR.get();
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            open(level, pos, player);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.getItem() instanceof RequesterItem && !RequesterData.tuned(stack)) {
            if (!level.isClientSide) {
                RequesterItem.sayUntuned(player);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.getItem() instanceof RequesterItem && RequesterData.tuned(stack)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof MonitorBlockEntity be) {
                RequesterData.network(stack).ifPresentOrElse(be::setNetwork,
                        () -> be.setFrequency(RequesterData.freq(stack)));
                player.displayClientMessage(Component.translatable("gui.distantstock.tuned"), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        open(level, pos, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void open(Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (player.isShiftKeyDown() && player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(sp, AdminConfigS2C.fromConfig());
            } else {
                // Same reason as the periodic broadcast: the screen's tower half only exists if the
                // readout is built for the monitor that asked.
                PacketDistributor.sendToPlayer(sp,
                        new OpenMonitorS2C(pos, LinkSnapshot.view(
                                dev.distantstock.routing.TowerReadout.survey(level, pos))));
            }
        }
    }
}
