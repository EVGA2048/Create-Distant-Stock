package dev.distantstock.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side durable holding area for rejected parcels whose origin dock could not take them back.
 *
 * <p>Ownership moves here only after the new record is written and flushed to disk. The escrow copy is
 * dropped afterwards, and {@link #reconcile} makes the return inbox authoritative whenever both copies
 * survive a crash, so a parcel is never returned twice.
 */
public final class ParcelReturnInbox extends SavedData {
    private static final String DATA_NAME = "distantstock_parcel_returns";
    private static final int MAX_RECORDS = 32_768;
    private static final Factory<ParcelReturnInbox> FACTORY =
            new Factory<>(ParcelReturnInbox::new, ParcelReturnInbox::load);

    public record Record(UUID parcelId, String encodedPackage, String address, String destinationNode,
                         UUID receivingDockGroupId, String originDimension, long originPos, long createdAt,
                         long handedOffAt, String reason, String detail, int attempts, long lastAttemptAt) {
        public Record {
            // See ParcelEscrow.Record: this record is written out with putUUID, so a null group
            // read back from a file would become an exception in the middle of a save.
            receivingDockGroupId = receivingDockGroupId == null
                    ? dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID : receivingDockGroupId;
        }

        public Record withAttempt(long gameTime) {
            return new Record(parcelId, encodedPackage, address, destinationNode, receivingDockGroupId,
                    originDimension, originPos, createdAt, handedOffAt, reason, detail, attempts + 1, gameTime);
        }
    }

    private final Map<UUID, Record> records = new LinkedHashMap<>();

    public static ParcelReturnInbox get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Takes ownership of a rejected escrow record. The caller must flush this data and only then drop the
     * escrow copy; use {@link #handOver} to keep that order.
     */
    public Record adopt(ParcelEscrow.Record escrowRecord, String reason) {
        if (records.size() >= MAX_RECORDS && !records.containsKey(escrowRecord.parcelId())) {
            throw new IllegalStateException("Return inbox is full");
        }
        Record adopted = new Record(escrowRecord.parcelId(), escrowRecord.encodedPackage(),
                escrowRecord.address(), escrowRecord.destinationNode(), escrowRecord.receivingDockGroupId(),
                escrowRecord.originDimension(), escrowRecord.originPos(), escrowRecord.createdAt(),
                System.currentTimeMillis(), reason == null ? "" : reason,
                escrowRecord.detail() == null ? "" : escrowRecord.detail(), 0, 0);
        records.put(adopted.parcelId(), adopted);
        setDirty();
        return adopted;
    }

    /**
     * Single-owner hand-over: the new record is made durable first, and the escrow copy is deleted only
     * after the flush succeeded. A failed flush rolls the hand-over back, so the escrow keeps the parcel.
     *
     * <p>The vanilla flush logs disk failures instead of throwing, so those surface as ERROR lines in the
     * server log rather than as a rollback.
     */
    public Record handOver(ParcelEscrow escrow, ParcelEscrow.Record escrowRecord, String reason, Runnable durableFlush) {
        Record adopted = adopt(escrowRecord, reason);
        try {
            durableFlush.run();
        } catch (RuntimeException failure) {
            records.remove(adopted.parcelId());
            setDirty();
            throw failure;
        }
        escrow.remove(adopted.parcelId());
        return adopted;
    }

    /**
     * Drops escrow copies that another store already owns. The return inbox and the quarantine library are
     * written before the escrow record is deleted, so a crash in between can leave two copies on disk.
     * Resolving that overlap in favour of the new owner keeps the parcel from being returned twice.
     */
    public void reconcile(ParcelEscrow escrow, ParcelQuarantine quarantine) {
        for (Record record : records.values()) {
            escrow.remove(record.parcelId());
        }
        for (ParcelQuarantine.Record record : quarantine.records()) {
            if (record.parcelId() != null) {
                escrow.remove(record.parcelId());
            }
        }
    }

    public List<Record> records() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    public int size() {
        return records.size();
    }

    public Optional<Record> find(UUID parcelId) {
        return Optional.ofNullable(records.get(parcelId));
    }

    public void noteAttempt(UUID parcelId, long gameTime) {
        Record old = records.get(parcelId);
        if (old == null) {
            return;
        }
        records.put(parcelId, old.withAttempt(gameTime));
        setDirty();
    }

    public void remove(UUID parcelId) {
        if (records.remove(parcelId) != null) {
            setDirty();
        }
    }

    public void flush(MinecraftServer server) {
        server.overworld().getDataStorage().save();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Record record : records.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("ParcelId", record.parcelId());
            row.putString("Package", record.encodedPackage());
            row.putString("Address", record.address());
            row.putString("Destination", record.destinationNode());
            row.putUUID("DockGroup", record.receivingDockGroupId());
            row.putString("OriginDimension", record.originDimension());
            row.putLong("OriginPos", record.originPos());
            row.putLong("CreatedAt", record.createdAt());
            row.putLong("HandedOffAt", record.handedOffAt());
            row.putString("Reason", record.reason());
            row.putString("Detail", record.detail());
            row.putInt("Attempts", record.attempts());
            row.putLong("LastAttemptAt", record.lastAttemptAt());
            rows.add(row);
        }
        tag.put("Returns", rows);
        return tag;
    }

    static ParcelReturnInbox load(CompoundTag tag, HolderLookup.Provider registries) {
        ParcelReturnInbox inbox = new ParcelReturnInbox();
        ListTag rows = tag.getList("Returns", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            try {
                UUID parcelId = row.getUUID("ParcelId");
                if (parcelId == null) {
                    continue;
                }
                Record record = new Record(parcelId, row.getString("Package"), row.getString("Address"),
                        row.getString("Destination"), row.getUUID("DockGroup"),
                        row.getString("OriginDimension"), row.getLong("OriginPos"), row.getLong("CreatedAt"),
                        row.getLong("HandedOffAt"), row.getString("Reason"), row.getString("Detail"),
                        row.getInt("Attempts"), row.getLong("LastAttemptAt"));
                inbox.records.put(parcelId, record);
            } catch (RuntimeException ignored) {
            }
        }
        return inbox;
    }
}
