package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.distantstock.event.EventRegistry;
import dev.distantstock.ModSounds;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.NetworkHealth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Wall-mounted event/alarm panel. The event history itself remains in {@link EventRegistry}. */
public final class LoggerBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    public static final int SNAPSHOT_LIMIT = 48;
    public static final int PAPER_CAPACITY = 16;

    private UUID createFrequency;
    private RemoteNetworkId networkId;
    private UUID distantNetworkId;
    private EventRegistry.Severity minimumSeverity = EventRegistry.Severity.INFO;
    private long printedUntilTick;
    private long nextBuzzerTick;
    private int promiseBusySeconds;
    private boolean networkKnown = true;
    private String displayCode = "PE";
    private int paperRemaining;

    public LoggerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LOGGER.get(), pos, state);
    }

    public UUID createFrequency() {
        return createFrequency;
    }

    public UUID distantNetworkId() {
        return distantNetworkId;
    }

    public String displayCode() {
        return displayCode == null || displayCode.isBlank() ? "--" : displayCode;
    }

    public int paperRemaining() {
        return Math.clamp(paperRemaining, 0, PAPER_CAPACITY);
    }

    public boolean hasPaper() {
        return paperRemaining() > 0;
    }

    /** Installs one replacement roll only after the current roll has run out. */
    public boolean installPaperRoll() {
        if (paperRemaining() > 0) return false;
        paperRemaining = PAPER_CAPACITY;
        sync();
        updateStatus();
        return true;
    }

    public boolean consumePaper() {
        if (!hasPaper()) return false;
        paperRemaining--;
        sync();
        updateStatus();
        return true;
    }

    public void setBinding(RemoteNetworkId network, UUID distantNetworkId) {
        this.networkId = network;
        this.createFrequency = network == null ? null : network.createFrequency();
        this.distantNetworkId = distantNetworkId;
        sync();
        updateStatus();
    }

    public void setCreateFrequency(UUID createFrequency) {
        this.createFrequency = createFrequency;
        this.networkId = null;
        this.distantNetworkId = null;
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
        if (createFrequency == null) return true;
        if (createFrequency.equals(record.createFrequency())) return true;
        if (distantNetworkId != null && distantNetworkId.equals(record.distantNetworkId())) return true;
        // The transport itself is shared infrastructure: if it is down, every bound logger should
        // say so even though the link event has no one Create frequency to attach to.
        return record.createFrequency() == null && "link".equals(record.sourceType());
    }

    public boolean visible(EventRegistry.Record record) {
        return record != null && inScope(record)
                && record.severity().ordinal() >= minimumSeverity.ordinal();
    }

    public LoggerBlock.Status status() {
        if (!networkKnown) return LoggerBlock.Status.OFFLINE;
        EventRegistry.Severity worst = null;
        boolean unacknowledged = false;
        for (EventRegistry.Record record : scopedActive()) {
            if (record.severity() == EventRegistry.Severity.INFO) continue;
            if (worst == null || record.severity().ordinal() > worst.ordinal()) {
                worst = record.severity();
                unacknowledged = !record.acknowledged();
            } else if (record.severity() == worst && !record.acknowledged()) {
                unacknowledged = true;
            }
        }
        if (worst == EventRegistry.Severity.ERROR) {
            return unacknowledged ? LoggerBlock.Status.ERROR : LoggerBlock.Status.ERROR_ACK;
        }
        if (worst == EventRegistry.Severity.WARN) {
            return unacknowledged ? LoggerBlock.Status.WARN : LoggerBlock.Status.WARN_ACK;
        }
        // Running out of paper is itself an operator-actionable warning. It must never leave the
        // panel advertising OK while the logger is unable to print/acknowledge the next alarm.
        if (!hasPaper()) return LoggerBlock.Status.WARN;
        return LoggerBlock.Status.NORMAL;
    }

    private List<EventRegistry.Record> scopedActive() {
        if (level == null || level.getServer() == null) return List.of();
        return EventRegistry.get(level.getServer()).active().stream().filter(this::inScope).toList();
    }

    /** Highest-priority active warning/error that has not been printed/acknowledged yet. */
    public EventRegistry.Record nextPrintableAlarm() {
        for (EventRegistry.Record record : scopedActive()) {
            if (record.severity() != EventRegistry.Severity.INFO && !record.acknowledged()) return record;
        }
        return null;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LoggerBlockEntity be) {
        if (level.isClientSide) return;
        if (state.hasProperty(LoggerBlock.PRINTED) && state.getValue(LoggerBlock.PRINTED)
                && level.getGameTime() >= be.printedUntilTick) {
            be.setPrinted(false);
        }
        if (level.getGameTime() % 20 == 0) {
            be.sampleNetworkHealth();
            be.updateStatus();
        }
        be.tickBuzzer();
    }

    /** Shows the physical paper output briefly after a successful print/ack operation. */
    public void showPrintedReceipt() {
        if (level == null || level.isClientSide) return;
        printedUntilTick = level.getGameTime() + 60;
        setPrinted(true);
        updateStatus();
    }

    private void setPrinted(boolean printed) {
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(LoggerBlock.PRINTED) || state.getValue(LoggerBlock.PRINTED) == printed) return;
        level.setBlock(worldPosition, state.setValue(LoggerBlock.PRINTED, printed), 3);
        setChanged();
    }

    private void updateStatus() {
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(LoggerBlock.STATUS)) return;
        LoggerBlock.Status next = status();
        String nextCode = codeFor(next);
        boolean codeChanged = !nextCode.equals(displayCode);
        displayCode = nextCode;
        if (state.getValue(LoggerBlock.STATUS) != next) level.setBlock(worldPosition, state.setValue(LoggerBlock.STATUS, next), 3);
        if (codeChanged) sync();
    }

    private String codeFor(LoggerBlock.Status status) {
        if (status == LoggerBlock.Status.OFFLINE) return "--";
        if (status == LoggerBlock.Status.NORMAL) return "OK";
        if (status == LoggerBlock.Status.ERROR) return "ER";
        if (status == LoggerBlock.Status.ERROR_ACK || status == LoggerBlock.Status.WARN_ACK) return "AC";
        // WARN with no active warning record is the local paper-empty condition.
        boolean activeWarning = scopedActive().stream()
                .anyMatch(row -> row.severity() == EventRegistry.Severity.WARN);
        return activeWarning ? "AL" : "PE";
    }

    private void tickBuzzer() {
        if (level == null || level.isClientSide) return;
        EventRegistry.Record alarm = nextPrintableAlarm();
        if (alarm == null) {
            nextBuzzerTick = level.getGameTime();
            return;
        }
        long now = level.getGameTime();
        if (now < nextBuzzerTick) return;
        level.playSound(null, worldPosition, ModSounds.STACK_LIGHT_BUZZER.get(),
                SoundSource.BLOCKS, alarm.severity() == EventRegistry.Severity.ERROR ? 0.78f : 0.58f,
                alarm.severity() == EventRegistry.Severity.ERROR ? 1.0f : 1.08f);
        nextBuzzerTick = now + (alarm.severity() == EventRegistry.Severity.ERROR ? 22 : 60);
    }

    private void sampleNetworkHealth() {
        if (level == null || level.isClientSide || level.getServer() == null
                || createFrequency == null || networkId == null) return;
        NetworkHealth health = CreateStock.health(createFrequency, 4);
        networkKnown = health.known();
        EventRegistry events = EventRegistry.get(level.getServer());
        String source = "network:" + createFrequency;
        long now = System.currentTimeMillis();
        condition(events, EventRegistry.Codes.NETWORK_OFFLINE, EventRegistry.Severity.ERROR,
                !health.known(), source, "Create logistics network is not loaded", now);
        condition(events, EventRegistry.Codes.NETWORK_LINKS_OFFLINE, EventRegistry.Severity.WARN,
                health.known() && health.offline() > 0, source,
                health.offline() + " / " + health.totalLinks() + " logistics links offline", now);
        condition(events, EventRegistry.Codes.NETWORK_LOCKED, EventRegistry.Severity.WARN,
                health.known() && health.locked(), source, "Create logistics network is locked", now);

        if (health.known() && !health.idle()) promiseBusySeconds++;
        else promiseBusySeconds = 0;
        EventRegistry.Severity stallSeverity = promiseBusySeconds >= 180
                ? EventRegistry.Severity.ERROR : EventRegistry.Severity.WARN;
        condition(events, EventRegistry.Codes.AUTOMATION_STALLED, stallSeverity,
                promiseBusySeconds >= 60, source,
                "Create package promise pending for " + promiseBusySeconds + "s", now);
    }

    private void condition(EventRegistry events, String code, EventRegistry.Severity severity,
                           boolean active, String source, String detail, long now) {
        if (active) {
            if (events.active(code, "network", source).isEmpty()) {
                events.raise(severity, code, "network", source, detail,
                        createFrequency, distantNetworkId, now);
            } else if (severity == EventRegistry.Severity.ERROR) {
                // Raising an existing WARN once at ERROR upgrades its severity in-place.
                EventRegistry.Record current = events.active(code, "network", source).orElse(null);
                if (current != null && current.severity() != EventRegistry.Severity.ERROR) {
                    events.raise(severity, code, "network", source, detail,
                            createFrequency, distantNetworkId, now);
                }
            }
        } else {
            events.clear(code, "network", source, now);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (createFrequency != null) tag.putUUID("CreateFrequency", createFrequency);
        if (networkId != null) tag.put("RemoteNetwork", networkId.save());
        if (distantNetworkId != null) tag.putUUID("DistantNetwork", distantNetworkId);
        tag.putString("MinimumSeverity", minimumSeverity.name());
        if (printedUntilTick > 0) tag.putLong("PrintedUntilTick", printedUntilTick);
        tag.putInt("PromiseBusySeconds", promiseBusySeconds);
        tag.putBoolean("NetworkKnown", networkKnown);
        tag.putString("DisplayCode", displayCode);
        tag.putInt("PaperRemaining", paperRemaining());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        createFrequency = tag.hasUUID("CreateFrequency") ? tag.getUUID("CreateFrequency") : null;
        networkId = tag.contains("RemoteNetwork") ? RemoteNetworkId.read(tag.getCompound("RemoteNetwork")).orElse(null) : null;
        distantNetworkId = tag.hasUUID("DistantNetwork") ? tag.getUUID("DistantNetwork") : null;
        try {
            minimumSeverity = tag.contains("MinimumSeverity")
                    ? EventRegistry.Severity.valueOf(tag.getString("MinimumSeverity"))
                    : EventRegistry.Severity.INFO;
        } catch (IllegalArgumentException ignored) {
            minimumSeverity = EventRegistry.Severity.INFO;
        }
        printedUntilTick = tag.getLong("PrintedUntilTick");
        promiseBusySeconds = tag.getInt("PromiseBusySeconds");
        networkKnown = !tag.contains("NetworkKnown") || tag.getBoolean("NetworkKnown");
        displayCode = tag.contains("DisplayCode") ? tag.getString("DisplayCode") : "OK";
        paperRemaining = Math.clamp(tag.getInt("PaperRemaining"), 0, PAPER_CAPACITY);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.logger");
        GoggleText.value(tip, "goggle.distantstock.logger.paper",
                hasPaper() ? ChatFormatting.GREEN : ChatFormatting.RED,
                paperRemaining(), PAPER_CAPACITY);
        if (!hasPaper()) {
            GoggleText.value(tip, "goggle.distantstock.logger.no_paper", ChatFormatting.GOLD);
        }
        return true;
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
