package dev.distantstock.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable event/alarm ledger shared by Distant Stock devices and future status consumers.
 *
 * <p>An active condition is unique by {@code (sourceType, sourceId, code)}. Re-reporting it updates
 * the same row and increments its occurrence count instead of appending one record per tick.
 */
public final class EventRegistry extends SavedData {
    private static final String DATA_NAME = "distantstock_events";
    /** Cleared history is bounded; active alarms are never silently evicted to make room for logs. */
    public static final int MAX_HISTORY_RECORDS = 512;
    /** Compatibility name for tests/callers written against the first draft. */
    public static final int MAX_RECORDS = MAX_HISTORY_RECORDS;
    private static final int MAX_CODE = 64;
    private static final int MAX_SOURCE = 160;
    private static final int MAX_DETAIL = 256;
    private static final Factory<EventRegistry> FACTORY =
            new Factory<>(EventRegistry::new, EventRegistry::load);

    public static final class Codes {
        public static final String DOCK_NO_ADDRESS = "DOCK_NO_ADDRESS";
        public static final String DOCK_NO_ROUTE = "DOCK_NO_ROUTE";
        public static final String DOCK_RETURN_BLOCKED = "DOCK_RETURN_BLOCKED";
        public static final String DOCK_OUTBOUND_STUCK = "DOCK_OUTBOUND_STUCK";
        public static final String DOCK_NO_RECEIVER = "DOCK_NO_RECEIVER";
        public static final String ADDRESS_CONFLICT = "ADDRESS_CONFLICT";
        public static final String LINK_OFFLINE = "LINK_OFFLINE";
        public static final String TOWER_STOPPED = "TOWER_STOPPED";
        public static final String TOWER_OVERSTRESSED = "TOWER_OVERSTRESSED";
        public static final String TOWER_NO_ETHER = "TOWER_NO_ETHER";
        public static final String PARCEL_QUARANTINED = "PARCEL_QUARANTINED";
        public static final String NETWORK_OFFLINE = "NETWORK_OFFLINE";
        public static final String NETWORK_LINKS_OFFLINE = "NETWORK_LINKS_OFFLINE";
        public static final String NETWORK_LOCKED = "NETWORK_LOCKED";
        public static final String AUTOMATION_STALLED = "AUTOMATION_STALLED";
        public static final String CHAIN_NO_ROUTE = "CHAIN_NO_ROUTE";
        public static final String CHAIN_PING_TIMEOUT = "CHAIN_PING_TIMEOUT";
        public static final String CHAIN_CACHE_FULL = "CHAIN_CACHE_FULL";

        private Codes() {
        }
    }

    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

    public record Record(UUID id, long createdAt, long updatedAt, Severity severity,
                         String code, String sourceType, String sourceId, String detail,
                         UUID createFrequency, UUID distantNetworkId,
                         boolean active, boolean acknowledged, long acknowledgedAt,
                         long clearedAt, int count) {
        public Record {
            if (id == null) throw new IllegalArgumentException("event id is null");
            severity = severity == null ? Severity.INFO : severity;
            code = bounded(code, MAX_CODE);
            sourceType = bounded(sourceType, MAX_CODE);
            sourceId = bounded(sourceId, MAX_SOURCE);
            detail = bounded(detail, MAX_DETAIL);
            count = Math.max(1, count);
        }
    }

    private final Map<UUID, Record> records = new LinkedHashMap<>();

    public static EventRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static String blockSource(Level level, BlockPos pos) {
        String dimension = level == null ? "unknown" : level.dimension().location().toString();
        return dimension + "@" + (pos == null ? "0,0,0" : pos.toShortString());
    }

    public Record raise(Severity severity, String code, String sourceType, String sourceId,
                        String detail, long now) {
        return raise(severity, code, sourceType, sourceId, detail, null, null, now);
    }

    public Record raise(Severity severity, String code, String sourceType, String sourceId,
                        String detail, UUID createFrequency, UUID distantNetworkId, long now) {
        String cleanCode = bounded(code, MAX_CODE);
        String cleanType = bounded(sourceType, MAX_CODE);
        String cleanSource = bounded(sourceId, MAX_SOURCE);
        String cleanDetail = bounded(detail, MAX_DETAIL);
        Record existing = active(cleanCode, cleanType, cleanSource).orElse(null);
        if (existing != null) {
            Record updated = new Record(existing.id(), existing.createdAt(), now,
                    stronger(existing.severity(), severity), cleanCode, cleanType, cleanSource,
                    cleanDetail.isBlank() ? existing.detail() : cleanDetail,
                    createFrequency == null ? existing.createFrequency() : createFrequency,
                    distantNetworkId == null ? existing.distantNetworkId() : distantNetworkId,
                    true, existing.acknowledged(), existing.acknowledgedAt(), 0,
                    existing.count() + 1);
            records.put(updated.id(), updated);
            setDirty();
            return updated;
        }
        Record created = new Record(UUID.randomUUID(), now, now, severity, cleanCode, cleanType,
                cleanSource, cleanDetail, createFrequency, distantNetworkId,
                true, false, 0, 0, 1);
        records.put(created.id(), created);
        pruneHistory();
        setDirty();
        return created;
    }

    public boolean acknowledge(UUID id, long now) {
        Record old = records.get(id);
        if (old == null || old.acknowledged()) {
            return false;
        }
        records.put(id, new Record(old.id(), old.createdAt(), old.updatedAt(), old.severity(),
                old.code(), old.sourceType(), old.sourceId(), old.detail(),
                old.createFrequency(), old.distantNetworkId(), old.active(), true,
                now, old.clearedAt(), old.count()));
        setDirty();
        return true;
    }

    public boolean clear(String code, String sourceType, String sourceId, long now) {
        Record old = active(code, sourceType, sourceId).orElse(null);
        if (old == null) {
            return false;
        }
        records.put(old.id(), new Record(old.id(), old.createdAt(), now, old.severity(),
                old.code(), old.sourceType(), old.sourceId(), old.detail(),
                old.createFrequency(), old.distantNetworkId(), false,
                old.acknowledged(), old.acknowledgedAt(), now, old.count()));
        pruneHistory();
        setDirty();
        return true;
    }

    public Optional<Record> active(String code, String sourceType, String sourceId) {
        String wantedCode = bounded(code, MAX_CODE);
        String wantedType = bounded(sourceType, MAX_CODE);
        String wantedSource = bounded(sourceId, MAX_SOURCE);
        return records.values().stream()
                .filter(Record::active)
                .filter(record -> record.code().equals(wantedCode)
                        && record.sourceType().equals(wantedType)
                        && record.sourceId().equals(wantedSource))
                .findFirst();
    }

    public List<Record> active() {
        return records.values().stream().filter(Record::active)
                .sorted(Comparator.comparingInt((Record record) -> severityRank(record.severity()))
                        .reversed().thenComparing(Comparator.comparingLong(Record::updatedAt).reversed()))
                .toList();
    }

    /** Active alarms attached to one Create logistics network, most urgent first. */
    public List<Record> activeForFrequency(UUID frequency) {
        if (frequency == null) return List.of();
        return active().stream()
                .filter(record -> frequency.equals(record.createFrequency()))
                .toList();
    }

    /** Active alarms attached to one formal Distant Stock network, most urgent first. */
    public List<Record> activeForDistantNetwork(UUID distantNetworkId) {
        if (distantNetworkId == null) return List.of();
        return active().stream()
                .filter(record -> distantNetworkId.equals(record.distantNetworkId()))
                .toList();
    }

    public List<Record> recent(int limit) {
        if (limit <= 0) return List.of();
        return records.values().stream()
                .sorted(Comparator.comparingLong(Record::updatedAt).reversed())
                .limit(limit).toList();
    }

    public Optional<Record> find(UUID id) {
        return Optional.ofNullable(records.get(id));
    }

    public int size() {
        return records.size();
    }

    private void pruneHistory() {
        long history = records.values().stream().filter(record -> !record.active()).count();
        while (history > MAX_HISTORY_RECORDS) {
            Record victim = records.values().stream()
                    .filter(record -> !record.active())
                    .min(Comparator.comparingLong(Record::updatedAt)).orElse(null);
            if (victim == null) return;
            records.remove(victim.id());
            history--;
        }
    }

    private static Severity stronger(Severity first, Severity second) {
        Severity a = first == null ? Severity.INFO : first;
        Severity b = second == null ? Severity.INFO : second;
        return severityRank(b) > severityRank(a) ? b : a;
    }

    private static int severityRank(Severity severity) {
        return switch (severity == null ? Severity.INFO : severity) {
            case INFO -> 0;
            case WARN -> 1;
            case ERROR -> 2;
        };
    }

    private static String bounded(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Record record : records.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Id", record.id());
            row.putLong("CreatedAt", record.createdAt());
            row.putLong("UpdatedAt", record.updatedAt());
            row.putString("Severity", record.severity().name());
            row.putString("Code", record.code());
            row.putString("SourceType", record.sourceType());
            row.putString("SourceId", record.sourceId());
            row.putString("Detail", record.detail());
            if (record.createFrequency() != null) {
                row.putUUID("CreateFrequency", record.createFrequency());
            }
            if (record.distantNetworkId() != null) {
                row.putUUID("DistantNetwork", record.distantNetworkId());
            }
            row.putBoolean("Active", record.active());
            row.putBoolean("Acknowledged", record.acknowledged());
            row.putLong("AcknowledgedAt", record.acknowledgedAt());
            row.putLong("ClearedAt", record.clearedAt());
            row.putInt("Count", record.count());
            rows.add(row);
        }
        tag.put("Events", rows);
        return tag;
    }

    public static EventRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        EventRegistry registry = new EventRegistry();
        ListTag rows = tag.getList("Events", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            try {
                Record record = new Record(row.getUUID("Id"), row.getLong("CreatedAt"),
                        row.getLong("UpdatedAt"), Severity.valueOf(row.getString("Severity")),
                        row.getString("Code"), row.getString("SourceType"), row.getString("SourceId"),
                        row.getString("Detail"),
                        row.hasUUID("CreateFrequency") ? row.getUUID("CreateFrequency") : null,
                        row.hasUUID("DistantNetwork") ? row.getUUID("DistantNetwork") : null,
                        row.getBoolean("Active"),
                        row.getBoolean("Acknowledged"), row.getLong("AcknowledgedAt"),
                        row.getLong("ClearedAt"), row.getInt("Count"));
                registry.records.put(record.id(), record);
            } catch (RuntimeException ignored) {
            }
        }
        registry.pruneHistory();
        return registry;
    }
}
