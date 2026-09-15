package dev.distantstock.routing;

import dev.distantstock.block.TowerTier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the players have configured about their towers, per tower.
 *
 * <p>Towers themselves are not stored: a tower is its blocks, and {@link TowerActivation} reads
 * them. This file only holds the decisions a block cannot carry — how far a tower keeps chunks
 * loaded, whether it loads them at all, and whether it carries devices.
 *
 * <p><b>Who writes:</b> the monitor's screen and only it, through {@link #setSettings}, which is the
 * single place that calls {@code setDirty}. <b>Who reads:</b> the chunk loader (every beat, through
 * {@link #settings}), the activation snapshot (every rebuild), the monitor's readout and the goggle
 * lines of a tower base.
 *
 * <p>Keys are (dimension, base position) like everywhere else, which is what makes a tower's
 * settings its own: two towers in one system have two entries, and setting one never moves the
 * other's. A tower that is taken apart and built again is the same key, so its settings are dropped
 * on the way out — see {@link dev.distantstock.block.TowerCoreBlockEntity#remove()} — and the new
 * tower starts from the defaults, which are the tier's own full radius and both switches on. A
 * tower that is merely unloaded keeps everything.
 *
 * <p>An empty file is not an error and is the normal state of a world that has not reached the
 * monitor stage: {@link #settings} answers {@link Settings#DEFAULT} for anything it has not seen.
 */
public final class TowerDirectory extends SavedData {
    private static final String DATA_NAME = "distantstock_tower_selections";
    private static final Factory<TowerDirectory> FACTORY =
            new Factory<>(TowerDirectory::new, TowerDirectory::load);

    /**
     * One tower's switches and its chunk radius.
     *
     * @param radius   chunks to load outward from the tower's own chunk: {@code -1} means the
     *                 operator never chose, so the tier's full radius stands. Distinguishing "not
     *                 chosen" from a chosen zero matters: zero is a real setting with a real cost
     *                 (the tower's own chunk and nothing else), and the screen shows it as a choice
     *                 rather than as an untouched tower.
     * @param loading  whether this tower keeps chunks loaded at all. Off means off for a tower's own
     *                 chunk too — a tower that is switched off loads nothing, which is the state an
     *                 operator asks for when the server, not the tower, should decide what is loaded.
     * @param carrying whether this tower carries devices. Independent of {@code loading}: a tower can
     *                 carry parcels while keeping nothing forced, and it can hold chunks open while
     *                 carrying nothing.
     */
    public record Settings(int radius, boolean loading, boolean carrying) {
        /** A tower nobody has configured: full tier radius, loading and carrying both on. */
        public static final Settings DEFAULT = new Settings(-1, true, true);

        /**
         * The radius the loader and the screen actually use.
         *
         * <p>Clamped to the tier, never above it: a tower that was set to radius three and then had
         * its mast taken down to tier I must not keep loading a 7x7 square the tier no longer pays
         * for. The stored number is left alone, so building the mast back up restores what the
         * operator asked for.
         */
        public int radiusFor(TowerTier tier) {
            int ceiling = tier == null ? 0 : tier.chunkRadius();
            return radius < 0 ? ceiling : Math.min(radius, ceiling);
        }

        /** The ceiling this tower's tier allows, or zero for a tower with no tier. */
        public static int ceiling(TowerTier tier) {
            return tier == null ? 0 : tier.chunkRadius();
        }

        /** The chunks this setting asks the loader to force: {@code (2r+1)^2}. */
        public static int chunks(int radius) {
            int side = TowerTier.sideForRadius(radius);
            return side * side;
        }
    }

    private final Map<TowerSystem.TowerId, Settings> settings = new HashMap<>();

    public static TowerDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** This tower's settings, or {@link Settings#DEFAULT} when nobody has configured it. */
    public Settings settings(TowerSystem.TowerId tower) {
        Settings found = settings.get(tower);
        return found == null ? Settings.DEFAULT : found;
    }

    /** Records a tower's settings. A whole record at a time, so a partial write cannot exist. */
    public void setSettings(TowerSystem.TowerId tower, Settings next) {
        settings.put(tower, next);
        setDirty();
    }

    /**
     * Drops everything recorded about a tower, for the block that is no longer there.
     *
     * <p>The settings are what the tower keeps loaded, so they cannot outlive it: a base that is
     * broken and placed again is a new tower, and it starts from the defaults rather than from a
     * radius somebody chose for a machine that is gone.
     */
    public void clear(TowerSystem.TowerId tower) {
        if (settings.remove(tower) != null) {
            setDirty();
        }
    }

    /**
     * Every tower this file holds settings for.
     *
     * <p>The budget a new setting has to fit into is the whole file's, not one tower's: the chunks
     * are forced by the same server whatever asked for them, so a per-tower limit would let twenty
     * towers each stay inside a limit the server cannot pay.
     */
    public Set<TowerSystem.TowerId> towers() {
        return Set.copyOf(settings.keySet());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rows = new ListTag();
        for (Map.Entry<TowerSystem.TowerId, Settings> entry : settings.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("Dimension", entry.getKey().dimension());
            row.putLong("Base", entry.getKey().packedPos());
            row.putInt("Radius", entry.getValue().radius());
            row.putBoolean("Loading", entry.getValue().loading());
            row.putBoolean("Carrying", entry.getValue().carrying());
            rows.add(row);
        }
        tag.put("Towers", rows);
        return tag;
    }

    private static TowerDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        TowerDirectory directory = new TowerDirectory();
        ListTag rows = tag.getList("Towers", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            String dimension = row.getString("Dimension");
            if (dimension.isBlank()) {
                continue;
            }
            // A missing key means the row predates the key, and the behaviour it had was
            // Settings.DEFAULT: full tier radius, both switches on. Reading absent keys as their
            // Java defaults would give radius 0 with both switches off, which is a tower that does
            // nothing — the one outcome a file written before a field existed must not produce.
            directory.settings.put(new TowerSystem.TowerId(dimension, row.getLong("Base")),
                    new Settings(
                            row.contains("Radius") ? row.getInt("Radius") : Settings.DEFAULT.radius(),
                            !row.contains("Loading") || row.getBoolean("Loading"),
                            !row.contains("Carrying") || row.getBoolean("Carrying")));
        }
        return directory;
    }

}
