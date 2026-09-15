package dev.distantstock.routing;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * What the players have configured about their towers, per tower.
 *
 * <p>Towers themselves are not stored: a tower is its blocks, and {@link TowerActivation} reads
 * them. This file only holds the decisions a block cannot carry — at the moment, the patch of
 * chunks a monitor has asked its tower to keep loaded.
 *
 * <p><b>Who writes:</b> the monitor's selection screen, and only it. A selection is written through
 * {@link #setSelection}, which is the single place that calls {@code setDirty}; clearing one goes
 * through {@link #clear} and marks the file dirty the same way. Nothing writes yet — the monitor
 * that chooses a centre is the next stage — and, deliberately, nothing reads yet either: until a
 * selection can exist, the chunk loader keeps the tier's square around the tower's own base, and
 * teaching it to prefer this file belongs to the same change that makes the selections possible.
 *
 * <p>An empty file is not an error and is the normal state of a world that has not reached the
 * monitor stage. Keys are (dimension, base position) like everywhere else, so a tower that is
 * rebuilt where it stood keeps its selection and one that is moved loses it.
 */
public final class TowerDirectory extends SavedData {
    private static final String DATA_NAME = "distantstock_tower_selections";
    private static final Factory<TowerDirectory> FACTORY =
            new Factory<>(TowerDirectory::new, TowerDirectory::load);

    private final Map<TowerSystem.TowerId, LongSet> selections = new HashMap<>();

    public static TowerDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** The chunks selected for a tower, or an empty set when it has none. */
    public LongSet selection(TowerSystem.TowerId tower) {
        LongSet selected = selections.get(tower);
        return selected == null ? new LongOpenHashSet() : new LongOpenHashSet(selected);
    }

    /** Records a selection. The copy is deliberate: the caller keeps its own set afterwards. */
    public void setSelection(TowerSystem.TowerId tower, LongSet chunks) {
        selections.put(tower, new LongOpenHashSet(chunks));
        setDirty();
    }

    public void clear(TowerSystem.TowerId tower) {
        if (selections.remove(tower) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Map.Entry<TowerSystem.TowerId, LongSet> entry : selections.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("Dimension", entry.getKey().dimension());
            row.putLong("Base", entry.getKey().packedPos());
            row.put("Chunks", new LongArrayTag(entry.getValue().toLongArray()));
            rows.add(row);
        }
        tag.put("Selections", rows);
        return tag;
    }

    private static TowerDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerDirectory directory = new TowerDirectory();
        ListTag rows = tag.getList("Selections", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            String dimension = row.getString("Dimension");
            if (dimension.isBlank()) {
                continue;
            }
            LongSet chunks = new LongOpenHashSet();
            for (long chunk : row.getLongArray("Chunks")) {
                chunks.add(chunk);
            }
            directory.selections.put(
                    new TowerSystem.TowerId(dimension, row.getLong("Base")), chunks);
        }
        return directory;
    }
}
