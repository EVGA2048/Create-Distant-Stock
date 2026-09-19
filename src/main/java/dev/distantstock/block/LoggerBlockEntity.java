package dev.distantstock.block;

import dev.distantstock.event.EventRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Wall-mounted event/alarm panel. The event history itself remains in {@link EventRegistry}. */
public final class LoggerBlockEntity extends BlockEntity {
    public static final int SNAPSHOT_LIMIT = 48;

    private UUID createFrequency;
    private EventRegistry.Severity minimumSeverity = EventRegistry.Severity.INFO;

    public LoggerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LOGGER.get(), pos, state);
    }

    public UUID createFrequency() {
        return createFrequency;
    }

    public void setCreateFrequency(UUID createFrequency) {
        this.createFrequency = createFrequency;
        sync();
        updateStatus();
    }

    public EventRegistry.Severity minimumSeverity() {
        return minimumSeverity;
    }

    public void setMinimumSeverity(EventRegistry.Severity severity) {
        this.minimumSeverity = severity == null ? EventRegistry.Severity.INFO : severity;
        sync();
        updateStatus();
    }

    /**
     * Snapshot for the panel: active alarms first, then recent history, without duplicates.
     *
     * <p>An alarm that has been active for hours is still more important than the 48 newest cleared
     * INFO rows. The screen limit therefore applies after active rows have been reserved.
     */
    public List<EventRegistry.Record> rows() {
        if (level == null || level.getServer() == null) return List.of();
        EventRegistry events = EventRegistry.get(level.getServer());
        LinkedHashMap<UUID, EventRegistry.Record> rows = new LinkedHashMap<>();
        for (EventRegistry.Record record : events.active()) {
            if (visible(record)) rows.put(record.id(), record);
            if (rows.size() >= SNAPSHOT_LIMIT) return List.copyOf(rows.values());
        }
        for (EventRegistry.Record record : events.recent(EventRegistry.MAX_HISTORY_RECORDS + 256)) {
            if (visible(record)) rows.putIfAbsent(record.id(), record);
            if (rows.size() >= SNAPSHOT_LIMIT) break;
        }
        return List.copyOf(rows.values());
    }

    /** Active rows in this panel's scope, highest severity first. */
    public List<EventRegistry.Record> activeRows() {
        if (level == null || level.getServer() == null) return List.of();
        return EventRegistry.get(level.getServer()).active().stream()
                .filter(this::inScope)
                .filter(record -> record.severity().ordinal() >= minimumSeverity.ordinal())
                .toList();
    }

    private boolean inScope(EventRegistry.Record record) {
        return createFrequency == null || createFrequency.equals(record.createFrequency());
    }

    public boolean visible(EventRegistry.Record record) {
        return record != null && inScope(record)
                && record.severity().ordinal() >= minimumSeverity.ordinal();
    }

    public LoggerBlock.Status status() {
        EventRegistry.Severity worst = null;
        for (EventRegistry.Record record : activeRows()) {
            if (worst == null || record.severity().ordinal() > worst.ordinal()) {
                worst = record.severity();
            }
        }
        if (worst == EventRegistry.Severity.ERROR) return LoggerBlock.Status.ERROR;
        if (worst == EventRegistry.Severity.WARN) return LoggerBlock.Status.WARN;
        return LoggerBlock.Status.NORMAL;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LoggerBlockEntity be) {
        if (level.isClientSide || level.getGameTime() % 20 != 0) return;
        be.updateStatus();
    }

    private void updateStatus() {
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(LoggerBlock.STATUS)) return;
        LoggerBlock.Status next = status();
        if (state.getValue(LoggerBlock.STATUS) != next) {
            level.setBlock(worldPosition, state.setValue(LoggerBlock.STATUS, next), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (createFrequency != null) tag.putUUID("CreateFrequency", createFrequency);
        tag.putString("MinimumSeverity", minimumSeverity.name());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        createFrequency = tag.hasUUID("CreateFrequency") ? tag.getUUID("CreateFrequency") : null;
        try {
            minimumSeverity = tag.contains("MinimumSeverity")
                    ? EventRegistry.Severity.valueOf(tag.getString("MinimumSeverity"))
                    : EventRegistry.Severity.INFO;
        } catch (IllegalArgumentException ignored) {
            minimumSeverity = EventRegistry.Severity.INFO;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void sync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
