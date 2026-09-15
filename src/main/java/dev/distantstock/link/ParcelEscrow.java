package dev.distantstock.link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import dev.distantstock.routing.DockGroupDirectory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Source-side durable ownership of parcels until the destination confirms APPLIED. */
public final class ParcelEscrow extends SavedData {
    private static final String DATA_NAME = "distantstock_parcel_escrow";
    private static final Factory<ParcelEscrow> FACTORY = new Factory<>(ParcelEscrow::new, ParcelEscrow::load);

    public enum State {
        HELD,
        SUBMITTED,
        REJECTED
    }

    public record Record(UUID parcelId, String encodedPackage, String address, String destinationNode,
                         UUID receivingDockGroupId, String originDimension, long originPos,
                         long createdAt, State state, UUID messageId, String detail,
                         List<String> stripIds, int strips) {
        public Record {
            stripIds = List.copyOf(stripIds);
            // Never null, and not because every caller remembers: a record whose group came back
            // absent from a file would otherwise be written out again by putUUID, which throws on a
            // null — during a world save, which is the one place an exception must not be raised.
            receivingDockGroupId = receivingDockGroupId == null
                    ? DockGroupDirectory.DEFAULT_GROUP_ID : receivingDockGroupId;
        }
    }

    private final Map<UUID, Record> records = new LinkedHashMap<>();

    public static ParcelEscrow get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public UUID hold(ItemStack parcel, String address, String destinationNode, UUID groupId,
                     String originDimension, BlockPos originPos, long gameTime,
                     HolderLookup.Provider registries) {
        String encoded = PackageCodec.encode(parcel, registries);
        if (encoded.isBlank()) {
            throw new IllegalArgumentException("Parcel cannot be encoded for escrow");
        }
        return holdEncoded(encoded, address, destinationNode, groupId, originDimension, originPos,
                gameTime);
    }

    /**
     * Stores an already encoded parcel, so custody rules can be exercised without a running server.
     *
     * @param gameTime the level's own clock, and it has to be that clock rather than
     *                 {@code System.currentTimeMillis()}: the only thing that reads it is the
     *                 escrow's hold deadline, which compares it against the level's game time.
     *                 Stamping a wall clock here made every parcel look younger than the deadline
     *                 for ever — the timeout ran, found nothing due, and returned, silently.
     */
    public UUID holdEncoded(String encodedPackage, String address, String destinationNode, UUID groupId,
                            String originDimension, BlockPos originPos, long gameTime) {
        if (encodedPackage == null || encodedPackage.isBlank()) {
            throw new IllegalArgumentException("Parcel cannot be encoded for escrow");
        }
        UUID parcelId = UUID.randomUUID();
        records.put(parcelId, new Record(parcelId, encodedPackage, address, destinationNode, groupId,
                originDimension, originPos.asLong(), gameTime, State.HELD, null, "",
                List.of(), 0));
        setDirty();
        return parcelId;
    }

    public List<Record> records() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    public int size() {
        return records.size();
    }

    public long count(State state) {
        return records.values().stream().filter(record -> record.state() == state).count();
    }

    public Optional<Record> find(UUID parcelId) {
        return Optional.ofNullable(records.get(parcelId));
    }

    public void submitted(UUID parcelId, UUID messageId) {
        update(parcelId, State.SUBMITTED, messageId, "");
    }

    public void rejected(UUID parcelId, String detail) {
        Record old = records.get(parcelId);
        update(parcelId, State.REJECTED, old == null ? null : old.messageId(), detail);
    }

    /**
     * Records the registry entries the destination reported as missing. The pump strips exactly these
     * entries out of the parcel and hands them back through the dock's fallback face.
     */
    public boolean attachStrip(UUID parcelId, List<String> missingIds, String detail) {
        Record old = records.get(parcelId);
        if (old == null || missingIds == null || missingIds.isEmpty()) {
            return false;
        }
        records.put(parcelId, new Record(old.parcelId(), old.encodedPackage(), old.address(),
                old.destinationNode(), old.receivingDockGroupId(), old.originDimension(), old.originPos(),
                old.createdAt(), old.state(), old.messageId(),
                detail == null || detail.isBlank() ? old.detail() : detail,
                List.copyOf(missingIds), old.strips()));
        setDirty();
        return true;
    }

    /**
     * Replaces the stored payload after items were removed from it, clears the strip request and queues the
     * parcel for another attempt. The strip counter bounds how often a parcel may be re-sent.
     */
    public boolean replacePayload(UUID parcelId, String encodedPackage, int strips) {
        Record old = records.get(parcelId);
        if (old == null || encodedPackage == null || encodedPackage.isBlank()) {
            return false;
        }
        records.put(parcelId, new Record(old.parcelId(), encodedPackage, old.address(), old.destinationNode(),
                old.receivingDockGroupId(), old.originDimension(), old.originPos(), old.createdAt(),
                State.HELD, null, old.detail(), List.of(), strips));
        setDirty();
        return true;
    }

    public void clearStrip(UUID parcelId) {
        Record old = records.get(parcelId);
        if (old == null || old.stripIds().isEmpty()) {
            return;
        }
        records.put(parcelId, new Record(old.parcelId(), old.encodedPackage(), old.address(),
                old.destinationNode(), old.receivingDockGroupId(), old.originDimension(), old.originPos(),
                old.createdAt(), old.state(), old.messageId(), old.detail(), List.of(), old.strips()));
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

    private void update(UUID parcelId, State state, UUID messageId, String detail) {
        Record old = records.get(parcelId);
        if (old == null) {
            return;
        }
        records.put(parcelId, new Record(old.parcelId(), old.encodedPackage(), old.address(),
                old.destinationNode(), old.receivingDockGroupId(), old.originDimension(), old.originPos(),
                old.createdAt(), state, messageId, detail == null ? "" : detail,
                old.stripIds(), old.strips()));
        setDirty();
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
            row.putString("State", record.state().name());
            if (record.messageId() != null) {
                row.putUUID("MessageId", record.messageId());
            }
            row.putString("Detail", record.detail());
            row.putInt("Strips", record.strips());
            if (!record.stripIds().isEmpty()) {
                ListTag stripIds = new ListTag();
                for (String id : record.stripIds()) {
                    stripIds.add(StringTag.valueOf(id));
                }
                row.put("StripIds", stripIds);
            }
            rows.add(row);
        }
        tag.put("Records", rows);
        return tag;
    }

    static ParcelEscrow load(CompoundTag tag, HolderLookup.Provider registries) {
        ParcelEscrow escrow = new ParcelEscrow();
        ListTag rows = tag.getList("Records", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            try {
                UUID parcelId = row.getUUID("ParcelId");
                if (parcelId == null) {
                    // A row without the id it is keyed by cannot be filed and cannot be saved again.
                    // Dropping it loses one parcel; keeping it would take the whole save down.
                    continue;
                }
                List<String> stripIds = new ArrayList<>();
                ListTag storedStripIds = row.getList("StripIds", Tag.TAG_STRING);
                for (int entry = 0; entry < storedStripIds.size(); entry++) {
                    stripIds.add(storedStripIds.getString(entry));
                }
                Record record = new Record(parcelId, row.getString("Package"), row.getString("Address"),
                        row.getString("Destination"), row.getUUID("DockGroup"),
                        row.getString("OriginDimension"), row.getLong("OriginPos"), row.getLong("CreatedAt"),
                        State.valueOf(row.getString("State")),
                        row.hasUUID("MessageId") ? row.getUUID("MessageId") : null,
                        row.getString("Detail"), stripIds, row.getInt("Strips"));
                escrow.records.put(parcelId, record);
            } catch (RuntimeException ignored) {
            }
        }
        return escrow;
    }
}
