package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.event.EventRegistry;
import dev.distantstock.item.EventReceiptItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Refresh, acknowledge and filter actions from one logger screen. */
public record LoggerActionC2S(BlockPos source, int action, UUID eventId, int value)
        implements CustomPacketPayload {
    public static final int REFRESH = 0;
    public static final int ACKNOWLEDGE = 1;
    public static final int SET_LEVEL = 2;
    public static final int PRINT = 3;
    public static final int SET_SOUND = 4;

    public static final Type<LoggerActionC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "logger_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LoggerActionC2S> STREAM_CODEC =
            StreamCodec.of(LoggerActionC2S::write, LoggerActionC2S::read);

    public static LoggerActionC2S refresh(BlockPos pos) {
        return new LoggerActionC2S(pos, REFRESH, null, 0);
    }

    public static LoggerActionC2S acknowledge(BlockPos pos, UUID eventId) {
        return new LoggerActionC2S(pos, ACKNOWLEDGE, eventId, 0);
    }

    public static LoggerActionC2S level(BlockPos pos, EventRegistry.Severity severity) {
        return new LoggerActionC2S(pos, SET_LEVEL, null, severity.ordinal());
    }

    public static LoggerActionC2S print(BlockPos pos, UUID eventId) {
        return new LoggerActionC2S(pos, PRINT, eventId, 0);
    }

    public static LoggerActionC2S sound(BlockPos pos, LoggerBlockEntity.AlarmSoundMode mode) {
        return new LoggerActionC2S(pos, SET_SOUND, null, mode.ordinal());
    }

    private static void write(RegistryFriendlyByteBuf buf, LoggerActionC2S message) {
        buf.writeBlockPos(message.source());
        buf.writeVarInt(message.action());
        buf.writeBoolean(message.eventId() != null);
        if (message.eventId() != null) buf.writeUUID(message.eventId());
        buf.writeVarInt(message.value());
    }

    private static LoggerActionC2S read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int action = buf.readVarInt();
        UUID eventId = buf.readBoolean() ? buf.readUUID() : null;
        return new LoggerActionC2S(pos, action, eventId, buf.readVarInt());
    }

    @Override
    public Type<LoggerActionC2S> type() {
        return TYPE;
    }

    public static void handle(LoggerActionC2S message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level().getBlockEntity(message.source()) instanceof LoggerBlockEntity logger)) {
                return;
            }
            if (player.distanceToSqr(message.source().getX() + .5,
                    message.source().getY() + .5, message.source().getZ() + .5) > 64) {
                return;
            }
            if (message.action() == SET_LEVEL) {
                int index = Math.clamp(message.value(), 0, EventRegistry.Severity.values().length - 1);
                logger.setMinimumSeverity(EventRegistry.Severity.values()[index]);
            } else if (message.action() == SET_SOUND) {
                int index = Math.clamp(message.value(), 0,
                        LoggerBlockEntity.AlarmSoundMode.values().length - 1);
                logger.setAlarmSoundMode(LoggerBlockEntity.AlarmSoundMode.values()[index]);
            } else if (message.action() == ACKNOWLEDGE && message.eventId() != null) {
                // ACK is the horn-silence operation. It must work even with no paper; the AC latch
                // remains until a real incident slip is printed later.
                EventRegistry events = EventRegistry.get(player.getServer());
                if (events.acknowledge(message.eventId(), System.currentTimeMillis())) {
                    logger.operatorEventChanged();
                }
            } else if (message.action() == PRINT && message.eventId() != null) {
                if (!logger.hasPaper()) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "message.distantstock.logger.no_paper"), true);
                } else printAndAcknowledge(logger, EventRegistry.get(player.getServer()), message.eventId(),
                        stack -> {
                            if (!player.addItem(stack)) {
                                player.drop(stack, false);
                            }
                        }, System.currentTimeMillis());
            } else if (message.action() != REFRESH) {
                return;
            }
            PacketDistributor.sendToPlayer(player, OpenLoggerS2C.from(logger));
        });
    }

    /**
     * Printing is the physical ACK operation. The output is produced before ACK is recorded, so a
     * future printer implementation can fail safely without acknowledging an event it never printed.
     */
    public static boolean printAndAcknowledge(LoggerBlockEntity logger, EventRegistry events,
                                              UUID eventId,
                                              java.util.function.Consumer<net.minecraft.world.item.ItemStack> output,
                                              long now) {
        if (logger == null || events == null || eventId == null || output == null) {
            return false;
        }
        EventRegistry.Record record = events.find(eventId).orElse(null);
        if (record == null || !record.active() || record.printed() || !logger.visible(record)) {
            return false;
        }
        if (!logger.hasPaper()) return false;
        output.accept(EventReceiptItem.create(record, now));
        if (!logger.consumePaper()) return false;
        boolean printed = events.markPrinted(eventId, now);
        if (printed) logger.showPrintedReceipt();
        return printed;
    }
}
