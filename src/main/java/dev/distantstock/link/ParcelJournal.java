package dev.distantstock.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistent parcel flight recorder. This never owns cargo; custody remains in escrow/returns/quarantine. */
public final class ParcelJournal extends SavedData {
    private static final String DATA_NAME = "distantstock_parcel_journal";
    private static final int MAX_TRACES = 16_384;
    private static final int MAX_STEPS = 24;
    private static final Factory<ParcelJournal> FACTORY = new Factory<>(ParcelJournal::new, ParcelJournal::load);

    public record Step(long at, String stage, String node, String detail, int count) {
        public Step {
            stage = clean(stage, 64);
            node = clean(node, 96);
            detail = clean(detail, 256);
            count = Math.max(1, count);
        }
    }

    public record Trace(UUID parcelId, long createdAt, long updatedAt, String address,
                        String destinationNode, UUID receivingDockGroupId, List<Step> steps) {
        public Trace {
            address = clean(address, 192);
            destinationNode = clean(destinationNode, 96);
            receivingDockGroupId = receivingDockGroupId == null
                    ? dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID : receivingDockGroupId;
            steps = List.copyOf(steps == null ? List.of() : steps);
        }

        public Step latest() {
            return steps.isEmpty() ? null : steps.getLast();
        }
    }

    private final Map<UUID, Trace> traces = new LinkedHashMap<>();

    public static ParcelJournal get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void record(UUID parcelId, String address, String destinationNode, UUID receivingDockGroupId,
                       String stage, String detail) {
        recordAt(parcelId, address, destinationNode, receivingDockGroupId,
                System.currentTimeMillis(), localNode(), stage, detail);
    }

    public void recordRemote(UUID parcelId, long at, String node, String stage, String detail) {
        recordAt(parcelId, "", "", null, at <= 0 ? System.currentTimeMillis() : at,
                node == null ? "remote" : node, stage, detail);
    }

    private void recordAt(UUID parcelId, String address, String destinationNode, UUID receivingDockGroupId,
                          long now, String node, String stage, String detail) {
        if (parcelId == null || stage == null || stage.isBlank()) return;
        Trace old = traces.get(parcelId);
        List<Step> steps = new ArrayList<>(old == null ? List.of() : old.steps());
        Step next = new Step(now, stage, node, detail, 1);
        Step last = steps.isEmpty() ? null : steps.getLast();
        if (last != null && last.stage().equals(next.stage()) && last.detail().equals(next.detail())
                && last.node().equals(next.node())) {
            steps.set(steps.size() - 1, new Step(last.at(), last.stage(), last.node(), last.detail(), last.count() + 1));
        } else {
            steps.add(next);
            while (steps.size() > MAX_STEPS) steps.removeFirst();
        }
        Trace trace = new Trace(parcelId, old == null ? now : old.createdAt(), now,
                old == null ? address : firstNonBlank(address, old.address()),
                old == null ? destinationNode : firstNonBlank(destinationNode, old.destinationNode()),
                receivingDockGroupId == null && old != null ? old.receivingDockGroupId() : receivingDockGroupId,
                steps);
        traces.put(parcelId, trace);
        prune();
        setDirty();
    }

    public void record(UUID parcelId, String stage, String detail) {
        record(parcelId, "", "", null, stage, detail);
    }

    public Optional<Trace> find(UUID parcelId) {
        return Optional.ofNullable(traces.get(parcelId));
    }

    public List<Trace> all() {
        return traces.values().stream()
                .sorted(Comparator.comparingLong(Trace::updatedAt).reversed())
                .toList();
    }

    public Optional<Trace> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String needle = token.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        List<Trace> matches = traces.values().stream()
                .filter(trace -> trace.parcelId().toString().replace("-", "").startsWith(needle))
                .limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public int size() {
        return traces.size();
    }

    private void prune() {
        while (traces.size() > MAX_TRACES) {
            UUID oldest = traces.values().stream().min(Comparator.comparingLong(Trace::updatedAt))
                    .map(Trace::parcelId).orElse(null);
            if (oldest == null) break;
            traces.remove(oldest);
        }
    }

    private static String localNode() {
        UUID id = TranserverBridge.localNodeUuid();
        return id == null ? "local" : id.toString();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Trace trace : traces.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("ParcelId", trace.parcelId());
            row.putLong("CreatedAt", trace.createdAt());
            row.putLong("UpdatedAt", trace.updatedAt());
            row.putString("Address", trace.address());
            row.putString("Destination", trace.destinationNode());
            row.putUUID("DockGroup", trace.receivingDockGroupId());
            ListTag steps = new ListTag();
            for (Step step : trace.steps()) {
                CompoundTag s = new CompoundTag();
                s.putLong("At", step.at());
                s.putString("Stage", step.stage());
                s.putString("Node", step.node());
                s.putString("Detail", step.detail());
                s.putInt("Count", step.count());
                steps.add(s);
            }
            row.put("Steps", steps);
            rows.add(row);
        }
        tag.put("Traces", rows);
        return tag;
    }

    private static ParcelJournal load(CompoundTag tag, HolderLookup.Provider registries) {
        ParcelJournal journal = new ParcelJournal();
        ListTag rows = tag.getList("Traces", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            if (!row.hasUUID("ParcelId")) continue;
            try {
                List<Step> steps = new ArrayList<>();
                ListTag stored = row.getList("Steps", Tag.TAG_COMPOUND);
                for (int n = Math.max(0, stored.size() - MAX_STEPS); n < stored.size(); n++) {
                    CompoundTag s = stored.getCompound(n);
                    steps.add(new Step(s.getLong("At"), s.getString("Stage"),
                            s.getString("Node"), s.getString("Detail"),
                            s.contains("Count") ? s.getInt("Count") : 1));
                }
                UUID id = row.getUUID("ParcelId");
                Trace trace = new Trace(id, row.getLong("CreatedAt"), row.getLong("UpdatedAt"),
                        row.getString("Address"), row.getString("Destination"), row.getUUID("DockGroup"), steps);
                journal.traces.put(id, trace);
            } catch (RuntimeException ignored) {
            }
        }
        journal.prune();
        return journal;
    }

    private ParcelJournal() {
    }
}
