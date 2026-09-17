package dev.distantstock.routing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the other servers call themselves.
 *
 * <p>Transerver identifies nodes by UUID and hands out no display name, so every readout that
 * mentioned another server said "5d2a90b4" — which is not a name, and told the player nothing about
 * which machine they were looking at. The peers do have names: every announcement carries the
 * sender's own alias, the same string its own terminal shows in its title. This is where that is
 * written down, so the readouts that need a name have one.
 *
 * <p>Written on every announcement, which arrives every forty-five seconds from every node. That is
 * also why nothing here expires: an entry is refreshed long before it could go stale, and a node
 * that has gone away is a node whose name is still the truth about it.
 *
 * <p>Not a permission of any kind — a label. Nothing routes by it and nothing checks it; it is here
 * so a player reads "远仓B" where they used to read eight characters of a UUID.
 */
public final class PeerNames extends SavedData {
    private static final String DATA_NAME = "distantstock_peer_names";
    private static final Factory<PeerNames> FACTORY = new Factory<>(PeerNames::new, PeerNames::load);
    /** How many peers one file keeps. Nodes come and go; a server does not meet thousands. */
    private static final int MAX_ENTRIES = 64;

    private final Map<UUID, String> names = new LinkedHashMap<>();

    public static PeerNames get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Records what a node calls itself. Called from the announcement that just arrived. */
    public void remember(UUID node, String alias) {
        if (node == null || alias == null || alias.isBlank()) {
            return;
        }
        String name = alias.trim();
        if (name.equals(names.get(node))) {
            return;
        }
        if (!names.containsKey(node) && names.size() >= MAX_ENTRIES) {
            // Oldest out: LinkedHashMap keeps insertion order and a re-remembered node is moved to
            // the end, so the one dropped is the one that has been quiet the longest.
            UUID oldest = names.keySet().iterator().next();
            names.remove(oldest);
        }
        names.remove(node);
        names.put(node, name);
        setDirty();
    }

    /** What that node calls itself, or empty when this server has never heard from it. */
    public String name(UUID node) {
        return node == null ? "" : names.getOrDefault(node, "");
    }

    /**
     * What to call a node on a screen: its own name, "本服" for this one, or a uuid prefix.
     *
     * <p>Lived in the pairing service until the codes were deleted, which is why the fallback is a
     * uuid prefix rather than something better — a code could be redeemed before the first
     * announcement ever arrived, and eight characters was all there was to show. The codes are
     * gone; the fallback stays, because a peer that goes quiet keeps its rows in the destination
     * list and a row with no label at all is worse than a short one.
     */
    public static String label(MinecraftServer server, UUID node) {
        if (node == null) {
            return "";
        }
        if (node.toString().equals(dev.distantstock.link.TranserverBridge.localNodeId())) {
            return "本服";
        }
        if (server != null) {
            String heard = get(server).name(node);
            if (!heard.isEmpty()) {
                return heard;
            }
        }
        return node.toString().substring(0, 8);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag written = new CompoundTag();
        names.forEach((node, name) -> written.putString(node.toString(), name));
        tag.put("Names", written);
        return tag;
    }

    /** Public so the file can be read back in a test: a saved label is only as good as its round trip. */
    public static PeerNames load(CompoundTag tag, HolderLookup.Provider registries) {
        PeerNames data = new PeerNames();
        CompoundTag written = tag.getCompound("Names");
        for (String key : written.getAllKeys()) {
            try {
                data.names.put(UUID.fromString(key), written.getString(key));
            } catch (IllegalArgumentException malformed) {
                // A key that is not a uuid is not a name for anything. Skipped rather than fixed.
            }
        }
        return data;
    }
}
