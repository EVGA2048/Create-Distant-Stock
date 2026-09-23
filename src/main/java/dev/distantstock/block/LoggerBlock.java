package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.item.EventReceiptItem;
import dev.distantstock.item.ModItems;
import dev.distantstock.event.EventRegistry;
import dev.distantstock.net.LoggerActionC2S;
import dev.distantstock.net.OpenLoggerS2C;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** Industrial event/alarm panel backed by the shared Event Registry. */
public final class LoggerBlock extends WallPanelBlock implements IWrenchable {
    public static final MapCodec<LoggerBlock> CODEC = simpleCodec(LoggerBlock::new);
    public static final EnumProperty<Status> STATUS = EnumProperty.create("status", Status.class);
    public static final BooleanProperty PRINTED = BooleanProperty.create("printed");

    public LoggerBlock(Properties props) {
        super(props);
        registerDefaultState(defaultBlockState()
                .setValue(STATUS, Status.WARN)
                .setValue(PRINTED, false));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof LoggerBlockEntity logger)) return;
        RequesterData.network(stack).ifPresentOrElse(
                network -> logger.setBinding(network, RequesterData.distantNetwork(stack).orElse(null)),
                () -> logger.setCreateFrequency(RequesterData.freq(stack)));
    }

    @Override
    protected MapCodec<LoggerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(STATUS, PRINTED);
    }

    public enum Status implements StringRepresentable {
        NORMAL("normal"), WARN("warn"), WARN_ACK("warn_ack"),
        ERROR("error"), ERROR_ACK("error_ack"), OFFLINE("offline");
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
        if (stack.getItem() instanceof RequesterItem
                && level.getBlockEntity(pos) instanceof LoggerBlockEntity logger) {
            if (!level.isClientSide) {
                if (player.isShiftKeyDown()) {
                    logger.clearBinding();
                    player.displayClientMessage(Component.translatable(
                            "gui.distantstock.logger.scope_all"), true);
                } else if (!RequesterData.tuned(stack)) {
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.terminal_unbound"), true);
                } else {
                    var network = RequesterData.network(stack).orElse(null);
                    if (network != null) {
                        java.util.UUID distant = RequesterData.formalDistantNetwork(stack, level.getServer())
                                .orElseGet(() -> RequesterData.distantNetwork(stack).orElse(null));
                        logger.setBinding(network, distant);
                    } else {
                        logger.setCreateFrequency(RequesterData.freq(stack));
                    }
                    player.displayClientMessage(Component.translatable(
                            "gui.distantstock.logger.scope_bound",
                            RequesterData.shortFreq(RequesterData.freq(stack))), true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(ModItems.LOGGER_PAPER_ROLL.get())
                && level.getBlockEntity(pos) instanceof LoggerBlockEntity logger) {
            if (!level.isClientSide) {
                if (logger.installPaperRoll()) {
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.paper_loaded", LoggerBlockEntity.PAPER_CAPACITY), true);
                } else {
                    player.displayClientMessage(Component.translatable(
                            "message.distantstock.logger.paper_remaining", logger.paperRemaining()), true);
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
            if (!player.isShiftKeyDown()) {
                EventRegistry.Record alarm = logger.nextPrintableAlarm();
                if (alarm != null) {
                    EventRegistry events = EventRegistry.get(serverPlayer.getServer());
                    long now = System.currentTimeMillis();
                    if (logger.hasPaper() && LoggerActionC2S.printAndAcknowledge(logger,
                            events, alarm.id(), stack -> {
                                if (!serverPlayer.addItem(stack)) serverPlayer.drop(stack, false);
                            }, now)) {
                        player.displayClientMessage(Component.translatable(
                                "message.distantstock.logger.printed", EventReceiptItem.eventLabel(
                                        alarm.severity(), alarm.id())), true);
                        return InteractionResult.sidedSuccess(false);
                    }

                    // No paper: the physical ACK key is still allowed to silence the horn. The
                    // event remains unprinted, so the front tubes stay on AC until a roll is loaded
                    // and the incident slip is actually produced.
                    if (!logger.hasPaper()) {
                        if (!alarm.acknowledged() && events.acknowledge(alarm.id(), now)) {
                            logger.operatorEventChanged();
                            player.displayClientMessage(Component.translatable(
                                    "message.distantstock.logger.silenced_no_paper"), true);
                        } else {
                            player.displayClientMessage(Component.translatable(
                                    "message.distantstock.logger.no_paper"), true);
                        }
                        return InteractionResult.sidedSuccess(false);
                    }
                }
            }
            PacketDistributor.sendToPlayer(serverPlayer, OpenLoggerS2C.from(logger));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
