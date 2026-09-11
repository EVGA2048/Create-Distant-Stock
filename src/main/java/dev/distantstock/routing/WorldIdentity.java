package dev.distantstock.routing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Stable ID stored with a world/dimension; survives display-name and dimension-key changes. */
public final class WorldIdentity extends SavedData {
    private static final String DATA_NAME = "distantstock_world_identity";
    private static final Factory<WorldIdentity> FACTORY = new Factory<>(WorldIdentity::new, WorldIdentity::load);

    private UUID id = UUID.randomUUID();

    public static UUID get(ServerLevel level) {
        return data(level).id;
    }

    /** A copied world must not keep the same routable identity while both copies are loaded. */
    public static void ensureUnique(MinecraftServer server) {
        var levels = new ArrayList<ServerLevel>();
        server.getAllLevels().forEach(levels::add);
        levels.sort(Comparator.comparing(level -> level.dimension().location().toString()));
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : levels) {
            WorldIdentity identity = data(level);
            if (!seen.add(identity.id)) {
                identity.id = UUID.randomUUID();
                identity.setDirty();
                seen.add(identity.id);
            }
        }
    }

    private static WorldIdentity data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putUUID("WorldId", id);
        return tag;
    }

    private static WorldIdentity load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldIdentity identity = new WorldIdentity();
        if (tag.hasUUID("WorldId")) {
            identity.id = tag.getUUID("WorldId");
        }
        return identity;
    }
}
