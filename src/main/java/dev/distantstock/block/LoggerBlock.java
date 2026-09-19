package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.net.OpenLoggerS2C;
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

/** Industrial event/alarm panel backed by the shared Event Registry. */
public final class LoggerBlock extends WallPanelBlock implements IWrenchable {
    public static final MapCodec<LoggerBlock> CODEC = simpleCodec(LoggerBlock::new);
    public static final EnumProperty<Status> STATUS = EnumProperty.create("status", Status.class);

    public LoggerBlock(Properties props) {
        super(props);
        registerDefaultState(defaultBlockState().setValue(STATUS, Status.NORMAL));
    }

    @Override
    protected MapCodec<LoggerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(STATUS);
    }

    public enum Status implements StringRepresentable {
        NORMAL("normal"), WARN("warn"), ERROR("error");
        private final String id;
        Status(String id) { this.id = id; }
        @Override public String getSerializedName() { return id; }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LoggerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.LOGGER.get(),
                LoggerBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof RequesterItem) {
            if (!RequesterData.tuned(stack)) {
                if (!level.isClientSide) RequesterItem.sayUntuned(player);
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof LoggerBlockEntity logger) {
                if (player.isShiftKeyDown()) {
                    logger.setCreateFrequency(null);
                    player.displayClientMessage(Component.translatable("gui.distantstock.logger.scope_all"), true);
                } else {
                    logger.setCreateFrequency(RequesterData.freq(stack));
                    player.displayClientMessage(Component.translatable("gui.distantstock.logger.scope_bound",
                            RequesterData.shortFreq(RequesterData.freq(stack))), true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof LoggerBlockEntity logger) {
            PacketDistributor.sendToPlayer(serverPlayer, OpenLoggerS2C.from(logger));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
