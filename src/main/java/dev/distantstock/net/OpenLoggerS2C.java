package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.client.ClientPayloadHandlers;
import dev.distantstock.event.EventRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/** Snapshot used both to open and refresh one logger screen. */
public record OpenLoggerS2C(BlockPos source, EventRegistry.Severity minimumSeverity,
                            UUID createFrequency, List<Row> rows) implements CustomPacketPayload {
    public static final Type<OpenLoggerS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "open_logger"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenLoggerS2C> STREAM_CODEC =
            StreamCodec.of(OpenLoggerS2C::write, OpenLoggerS2C::read);

    public OpenLoggerS2C {
        minimumSeverity = minimumSeverity == null ? EventRegistry.Severity.INFO : minimumSeverity;
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static OpenLoggerS2C from(LoggerBlockEntity logger) {
        return new OpenLoggerS2C(logger.getBlockPos(), logger.minimumSeverity(), logger.createFrequency(),
                logger.rows().stream().map(Row::from).toList());
    }

    private static void write(RegistryFriendlyByteBuf buf, OpenLoggerS2C message) {
        buf.writeBlockPos(message.source());
        buf.writeVarInt(message.minimumSeverity().ordinal());
        buf.writeBoolean(message.createFrequency() != null);
        if (message.createFrequency() != null) buf.writeUUID(message.createFrequency());
        buf.writeVarInt(Math.min(LoggerBlockEntity.SNAPSHOT_LIMIT, message.rows().size()));
        for (int i = 0; i < Math.min(LoggerBlockEntity.SNAPSHOT_LIMIT, message.rows().size()); i++) {
            message.rows().get(i).write(buf);
        }
    }

    private static OpenLoggerS2C read(RegistryFriendlyByteBuf buf) {
        BlockPos source = buf.readBlockPos();
        int severity = Math.clamp(buf.readVarInt(), 0, EventRegistry.Severity.values().length - 1);
        UUID frequency = buf.readBoolean() ? buf.readUUID() : null;
        int count = Math.clamp(buf.readVarInt(), 0, LoggerBlockEntity.SNAPSHOT_LIMIT);
        java.util.ArrayList<Row> rows = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) rows.add(Row.read(buf));
        return new OpenLoggerS2C(source, EventRegistry.Severity.values()[severity], frequency, rows);
    }

    @Override
    public Type<OpenLoggerS2C> type() {
        return TYPE;
    }

    public static void handle(OpenLoggerS2C message, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandlers.openOrUpdateLogger(message));
    }

    public record Row(UUID id, long createdAt, long updatedAt, EventRegistry.Severity severity,
                      String code, String sourceType, String sourceId, String detail,
                      boolean active, boolean acknowledged, int count) {
        private static Row from(EventRegistry.Record record) {
            return new Row(record.id(), record.createdAt(), record.updatedAt(), record.severity(),
                    record.code(), record.sourceType(), record.sourceId(), record.detail(),
                    record.active(), record.acknowledged(), record.count());
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeLong(createdAt);
            buf.writeLong(updatedAt);
            buf.writeVarInt(severity.ordinal());
            buf.writeUtf(code, 64);
            buf.writeUtf(sourceType, 64);
            buf.writeUtf(sourceId, 160);
            buf.writeUtf(detail, 256);
            buf.writeBoolean(active);
            buf.writeBoolean(acknowledged);
            buf.writeVarInt(count);
        }

        private static Row read(RegistryFriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            long created = buf.readLong();
            long updated = buf.readLong();
            int severity = Math.clamp(buf.readVarInt(), 0, EventRegistry.Severity.values().length - 1);
            return new Row(id, created, updated, EventRegistry.Severity.values()[severity],
                    buf.readUtf(64), buf.readUtf(64), buf.readUtf(160), buf.readUtf(256),
                    buf.readBoolean(), buf.readBoolean(), Math.max(1, buf.readVarInt()));
        }
    }
}
