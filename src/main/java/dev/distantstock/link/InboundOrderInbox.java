package dev.distantstock.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable target-side order inbox. PROCESSING is deliberately never auto-replayed after a crash. */
public final class InboundOrderInbox extends SavedData {
    private static final String DATA_NAME = "distantstock_inbound_orders";
    private static final Factory<InboundOrderInbox> FACTORY =
            new Factory<>(InboundOrderInbox::new, InboundOrderInbox::load);

    public enum State {
        RECEIVED,
        PROCESSING,
        APPLIED,
        REJECTED
    }

    public record Record(UUID childOrderId, UUID sourceNodeId, OrderRequestCodec.Request request,
                         State state, long updatedAt, String detail) {
    }

    private final Map<UUID, Record> records = new LinkedHashMap<>();

    public static InboundOrderInbox get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Record find(UUID childOrderId) {
        return records.get(childOrderId);
    }

    public Record receive(UUID sourceNodeId, OrderRequestCodec.Request request) {
        Record existing = records.get(request.childOrderId());
        if (existing != null) {
            if (!java.util.Objects.equals(existing.sourceNodeId(), sourceNodeId)
                    || !existing.request().equals(request)) {
                return null;
            }
            return existing;
        }
        Record record = new Record(request.childOrderId(), sourceNodeId, request,
                State.RECEIVED, System.currentTimeMillis(), "");
        records.put(record.childOrderId(), record);
        setDirty();
        return record;
    }

    public void state(UUID childOrderId, State state, String detail) {
        Record old = records.get(childOrderId);
        if (old == null) {
            return;
        }
        records.put(childOrderId, new Record(old.childOrderId(), old.sourceNodeId(), old.request(),
                state, System.currentTimeMillis(), detail == null ? "" : detail));
        setDirty();
    }

    public List<Record> records() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    public long count(State state) {
        return records.values().stream().filter(record -> record.state() == state).count();
    }

    public void flush(MinecraftServer server) {
        server.overworld().getDataStorage().save();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Record record : records.values()) {
            try {
                CompoundTag row = new CompoundTag();
                row.putUUID("ChildOrder", record.childOrderId());
                if (record.sourceNodeId() != null) {
                    // Absent, not zero: a node with no id and a node with the zero id are different
                    // senders, and the dedup above compares them as such.
                    row.putUUID("SourceNode", record.sourceNodeId());
                }
                row.putString("Request", Base64.getEncoder().encodeToString(
                        OrderRequestCodec.encode(record.request())));
                row.putString("State", record.state().name());
                row.putLong("UpdatedAt", record.updatedAt());
                row.putString("Detail", record.detail());
                rows.add(row);
            } catch (IOException ignored) {
            }
        }
        tag.put("Orders", rows);
        return tag;
    }

    private static InboundOrderInbox load(CompoundTag tag, HolderLookup.Provider registries) {
        InboundOrderInbox inbox = new InboundOrderInbox();
        ListTag rows = tag.getList("Orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            try {
                OrderRequestCodec.Request request = OrderRequestCodec.decode(
                        Base64.getDecoder().decode(row.getString("Request")));
                if (!row.hasUUID("ChildOrder")) {
                    continue;
                }
                Record record = new Record(row.getUUID("ChildOrder"),
                        row.hasUUID("SourceNode") ? row.getUUID("SourceNode") : null, request,
                        State.valueOf(row.getString("State")), row.getLong("UpdatedAt"), row.getString("Detail"));
                inbox.records.put(record.childOrderId(), record);
            } catch (RuntimeException | IOException ignored) {
            }
        }
        return inbox;
    }
}
