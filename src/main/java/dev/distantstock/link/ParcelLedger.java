package dev.distantstock.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent target-side idempotency ledger. A parcel ID is applied at most once. */
public final class ParcelLedger extends SavedData {
    private static final String DATA_NAME = "distantstock_applied_parcels";
    private static final int MAX_ENTRIES = 262_144;
    private static final Factory<ParcelLedger> FACTORY = new Factory<>(ParcelLedger::new, ParcelLedger::load);

    private final Map<UUID, Long> applied = new LinkedHashMap<>();

    public static ParcelLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public boolean contains(UUID parcelId) {
        return applied.containsKey(parcelId);
    }

    public void markApplied(UUID parcelId) {
        applied.put(parcelId, System.currentTimeMillis());
        while (applied.size() > MAX_ENTRIES) {
            UUID oldest = applied.entrySet().stream()
                    .min(Comparator.comparingLong(Map.Entry::getValue))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) {
                break;
            }
            applied.remove(oldest);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Map.Entry<UUID, Long> entry : applied.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("ParcelId", entry.getKey());
            row.putLong("AppliedAt", entry.getValue());
            rows.add(row);
        }
        tag.put("Applied", rows);
        return tag;
    }

    private static ParcelLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        ParcelLedger ledger = new ParcelLedger();
        ListTag rows = tag.getList("Applied", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            if (row.hasUUID("ParcelId")) {
                ledger.applied.put(row.getUUID("ParcelId"), row.getLong("AppliedAt"));
            }
        }
        return ledger;
    }
}
