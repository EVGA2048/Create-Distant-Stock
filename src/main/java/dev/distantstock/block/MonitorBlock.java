package dev.distantstock.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.mojang.serialization.MapCodec;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.net.AdminConfigS2C;
import dev.distantstock.net.LinkSnapshotS2C;
import net.minecraft.core.BlockPos;
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

public final class MonitorBlock extends WallPanelBlock implements IWrenchable {

    /** Rotation would silently move the cabin or the panel slots, so a wrench click only reports state. */
    @Override
    public net.minecraft.world.InteractionResult onWrenched(net.minecraft.world.level.block.state.BlockState state,
                                                            net.minecraft.world.item.context.UseOnContext context) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    public static final MapCodec<MonitorBlock> CODEC = simpleCodec(MonitorBlock::new);
    public static final EnumProperty<Status> STATUS = EnumProperty.create("status", Status.class);

    public MonitorBlock(Properties props) {
        super(props);
        registerDefaultState(defaultBlockState().setValue(STATUS, Status.GREEN));
    }

    @Override
    protected MapCodec<MonitorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> b) {
        super.createBlockStateDefinition(b);
        b.add(STATUS);
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.MONITOR.get(), MonitorBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (DockBlock.isWrench(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        open(level, player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        open(level, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void open(Level level, Player player) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (player.isShiftKeyDown() && player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(sp, AdminConfigS2C.fromConfig());
            } else {
                PacketDistributor.sendToPlayer(sp, new LinkSnapshotS2C(LinkSnapshot.view()));
            }
        }
    }
}
