package dev.distantstock.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrator-visible quarantine for parcels that neither the origin dock nor the source server can
 * restore. Raw bytes, digest and route are kept untouched so a parcel is never silently destroyed.
 */
public final class ParcelQuarantine extends SavedData {
    private static final String DATA_NAME = "distantstock_parcel_quarantine";
    private static final int MAX_RECORDS = 8_192;
    private static final Factory<ParcelQuarantine> FACTORY =
            new Factory<>(ParcelQuarantine::new, ParcelQuarantine::load);

    public record Intake(UUID parcelId, String encodedPackage, String address, String destinationNode,
                         UUID receivingDockGroupId, String originDimension, long originPos) {
        public static Intake fromEscrow(ParcelEscrow.Record record) {
            return new Intake(record.parcelId(), record.encodedPackage(), record.address(),
                    record.destinationNode(), record.receivingDockGroupId(), record.originDimension(),
                    record.originPos());
        }

        public static Intake fromReturn(ParcelReturnInbox.Record record) {
            return new Intake(record.parcelId(), record.encodedPackage(), record.address(),
                    record.destinationNode(), record.receivingDockGroupId(), record.originDimension(),
                    record.originPos());
        }
    }

    public record Record(UUID id, UUID parcelId, String encodedPackage, String sha256, String address,
                         String destinationNode, UUID receivingDockGroupId, String originDimension,
                         long originPos, String reason, String detail, long createdAt) {
        /** True when the stored bytes still hash to the digest recorded on intake. */
        public boolean intact() {
            return sha256.equals(hashOf(encodedPackage));
        }
    }

    private final Map<UUID, Record> records = new LinkedHashMap<>();

    public static ParcelQuarantine get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Record store(Intake intake, String reason, String detail) {
        if (records.size() >= MAX_RECORDS) {
            throw new IllegalStateException("Quarantine is full");
        }
        Record record = new Record(UUID.randomUUID(), intake.parcelId(),
                intake.encodedPackage() == null ? "" : intake.encodedPackage(),
                hashOf(intake.encodedPackage() == null ? "" : intake.encodedPackage()),
                intake.address() == null ? "" : intake.address(),
                intake.destinationNode() == null ? "" : intake.destinationNode(),
                intake.receivingDockGroupId(), intake.originDimension() == null ? "" : intake.originDimension(),
                intake.originPos(), reason == null ? "" : reason,
                detail == null ? "" : detail, System.currentTimeMillis());
        records.put(record.id(), record);
        setDirty();
        return record;
    }

    /**
     * Single-owner move out of the escrow. The quarantine record is flushed to disk before the escrow copy
     * is deleted, so a crash keeps the parcel in exactly one store.
     */
    public Record transfer(ParcelEscrow escrow, ParcelEscrow.Record escrowRecord, String reason,
                           String detail, Runnable durableFlush) {
        Record stored = store(Intake.fromEscrow(escrowRecord), reason, detail);
        try {
            durableFlush.run();
        } catch (RuntimeException failure) {
            records.remove(stored.id());
            setDirty();
            throw failure;
        }
        escrow.remove(escrowRecord.parcelId());
        return stored;
    }

    /** Same order as {@link #transfer(ParcelEscrow, ParcelEscrow.Record, String, String, Runnable)}. */
    public Record transfer(ParcelReturnInbox inbox, ParcelReturnInbox.Record returnRecord, String reason,
                           String detail, Runnable durableFlush) {
        Record stored = store(Intake.fromReturn(returnRecord), reason, detail);
        try {
            durableFlush.run();
        } catch (RuntimeException failure) {
            records.remove(stored.id());
            setDirty();
            throw failure;
        }
        inbox.remove(returnRecord.parcelId());
        return stored;
    }

    public List<Record> records() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    public int size() {
        return records.size();
    }

    public Optional<Record> find(UUID id) {
        return Optional.ofNullable(records.get(id));
    }

    public void remove(UUID id) {
        if (records.remove(id) != null) {
            setDirty();
        }
    }

    public void flush(MinecraftServer server) {
        server.overworld().getDataStorage().save();
    }

    public static String hashOf(String payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Record record : records.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Id", record.id());
            if (record.parcelId() != null) {
                row.putUUID("ParcelId", record.parcelId());
            }
            row.putString("Package", record.encodedPackage());
            row.putString("Sha256", record.sha256());
            row.putString("Address", record.address());
            row.putString("Destination", record.destinationNode());
            if (record.receivingDockGroupId() != null) {
                row.putUUID("DockGroup", record.receivingDockGroupId());
            }
            row.putString("OriginDimension", record.originDimension());
            row.putLong("OriginPos", record.originPos());
            row.putString("Reason", record.reason());
            row.putString("Detail", record.detail());
            row.putLong("CreatedAt", record.createdAt());
            rows.add(row);
        }
        tag.put("Quarantine", rows);
        return tag;
    }

    static ParcelQuarantine load(CompoundTag tag, HolderLookup.Provider registries) {
        ParcelQuarantine quarantine = new ParcelQuarantine();
        ListTag rows = tag.getList("Quarantine", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            try {
                Record record = new Record(row.getUUID("Id"),
                        row.hasUUID("ParcelId") ? row.getUUID("ParcelId") : null,
                        row.getString("Package"), row.getString("Sha256"), row.getString("Address"),
                        row.getString("Destination"),
                        row.hasUUID("DockGroup") ? row.getUUID("DockGroup") : null,
                        row.getString("OriginDimension"), row.getLong("OriginPos"), row.getString("Reason"),
                        row.getString("Detail"), row.getLong("CreatedAt"));
                quarantine.records.put(record.id(), record);
            } catch (RuntimeException ignored) {
            }
        }
        return quarantine;
    }
}
